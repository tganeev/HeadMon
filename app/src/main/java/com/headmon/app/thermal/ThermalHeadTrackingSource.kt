package com.headmon.app.thermal

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.headmon.app.tracking.HeadPoseFrame
import com.headmon.app.tracking.HeadTrackingSource
import com.jiangdg.ausbc.widget.IAspectRatio
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

sealed interface ThermalTrackingEvent {
    data class Pose(
        val pose: HeadPoseFrame,
        val metrics: ThermalMetrics
    ) : ThermalTrackingEvent

    data object NoHead : ThermalTrackingEvent
    data object Ffc : ThermalTrackingEvent

    data class CameraState(
        val state: ThermalCameraController.State,
        val message: String? = null
    ) : ThermalTrackingEvent

    data class Device(val name: String?) : ThermalTrackingEvent
    data class Error(val message: String) : ThermalTrackingEvent
}

/**
 * Связывает ThermalCameraController и ThermalFrameAnalyzer.
 *
 * Анализ выполняется в одном фоновом потоке, а результаты приходят
 * в main thread. Очередь ограничена одним кадром: если анализ не успевает,
 * старые кадры отбрасываются.
 */
class ThermalHeadTrackingSource(
    context: Context,
    private val analyzer: ThermalFrameAnalyzer = ThermalFrameAnalyzer(),
    private var previewView: IAspectRatio? = null,
    private val onEvent: ((ThermalTrackingEvent) -> Unit)? = null
) : HeadTrackingSource {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: ((HeadPoseFrame?) -> Unit)? = null

    @Volatile
    private var released = false

    private val analysisExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { runnable ->
            Thread(runnable, "thermal-analyzer").apply { isDaemon = true }
        },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private val cameraListener = object : ThermalCameraController.Listener {
        override fun onFrame(frame: ThermalFrame) {
            if (released) return

            try {
                analysisExecutor.execute {
                    val result = analyzer.analyze(frame)
                    mainHandler.post {
                        if (released) return@post

                        when (result) {
                            is ThermalAnalysisResult.Detected -> {
                                val metrics = result.metrics
                                val pose = HeadPoseFrame(
                                    yaw = metrics.yawDeg,
                                    pitch = metrics.pitchDeg,
                                    roll = metrics.rollDeg,
                                    dx = metrics.dxNorm,
                                    dy = metrics.dyNorm,
                                    confidence = metrics.confidence,
                                    timestampMs = frame.timestampMs,
                                    source = HeadPoseFrame.Source.THERMAL
                                )
                                listener?.invoke(pose)
                                onEvent?.invoke(ThermalTrackingEvent.Pose(pose, metrics))
                            }

                            ThermalAnalysisResult.NoHead -> {
                                listener?.invoke(null)
                                onEvent?.invoke(ThermalTrackingEvent.NoHead)
                            }

                            ThermalAnalysisResult.Ffc -> {
                                onEvent?.invoke(ThermalTrackingEvent.Ffc)
                            }

                            is ThermalAnalysisResult.Error -> {
                                onEvent?.invoke(ThermalTrackingEvent.Error(result.message))
                            }
                        }
                    }
                }
            } catch (_: RejectedExecutionException) {
                // Источник уже освобождён — кадр можно безопасно игнорировать.
            }
        }

        override fun onState(state: ThermalCameraController.State, message: String?) {
            mainHandler.post {
                if (released) return@post
                onEvent?.invoke(ThermalTrackingEvent.CameraState(state, message))
            }
        }

        override fun onDevice(deviceName: String?) {
            mainHandler.post {
                if (released) return@post
                onEvent?.invoke(ThermalTrackingEvent.Device(deviceName))
            }
        }
    }

    private val controller = ThermalCameraController(context, cameraListener)

    fun setPreviewView(view: IAspectRatio?) {
        previewView = view
    }

    override fun start() {
        if (released) return
        controller.start(previewView)
    }

    override fun stop() {
        controller.stop()
    }

    override fun setListener(listener: ((HeadPoseFrame?) -> Unit)?) {
        this.listener = listener
    }

    fun resetAnalyzer() {
        if (released) return
        try {
            analysisExecutor.execute { analyzer.reset() }
        } catch (_: RejectedExecutionException) {
            // Источник уже освобождён.
        }
    }

    fun release() {
        if (released) return
        released = true
        controller.release()
        analysisExecutor.shutdownNow()
        listener = null
    }
}
