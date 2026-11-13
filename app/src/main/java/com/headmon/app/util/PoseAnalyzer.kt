package com.headmon.app.util

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.atan2

object PoseAnalyzer {
    private var poseLandmarker: PoseLandmarker? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastDetectLogTime = 0L

    // История для сглаживания
    private val angleHistory = mutableListOf<Float>()
    private const val HISTORY_SIZE = 10

    // Текущий коллбэк (обновляется при каждом init)
    private var currentOnResult: ((Float?) -> Unit)? = null

    fun init(context: Context, onResult: (Float?) -> Unit) {
        // Всегда обновляем коллбэк, даже если модель уже создана
        currentOnResult = onResult

        if (poseLandmarker != null) {
            AppLogger.log("POSE_INIT", "Already initialized, callback updated")
            return
        }

        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("pose_landmarker_lite.task")
                .setDelegate(Delegate.CPU)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.1f)
                .setResultListener { result: PoseLandmarkerResult, _ ->
                    val angle = calculateHeadTilt(result)
                    mainHandler.post {
                        currentOnResult?.invoke(angle)
                    }
                }
                .setErrorListener { error ->
                    AppLogger.log("POSE_ERROR", "Pose error: ${error.message}")
                }
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            AppLogger.log("POSE_INIT", "PoseLandmarker initialized successfully")
        } catch (e: Exception) {
            AppLogger.log("POSE_INIT", "PoseLandmarker init FAILED: ${e.message}")
        }
    }

    fun detect(bitmap: Bitmap, timestampMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastDetectLogTime > 2000) {
            AppLogger.log("POSE_DETECT", "detect called")
            lastDetectLogTime = now
        }

        poseLandmarker?.let {
            val mpImage = BitmapImageBuilder(bitmap).build()
            it.detectAsync(mpImage, timestampMs)
        }
    }

    private fun calculateHeadTilt(result: PoseLandmarkerResult): Float? {
        val landmarks = result.landmarks().firstOrNull() ?: return null
        if (landmarks.size < 13) return null

        val leftEar = landmarks[7]
        val rightEar = landmarks[8]
        val leftShoulder = landmarks[11]
        val rightShoulder = landmarks[12]

        val leftEarVis = maxOf(leftEar.visibility().orElse(0f), leftEar.presence().orElse(0f))
        val rightEarVis = maxOf(rightEar.visibility().orElse(0f), rightEar.presence().orElse(0f))
        val leftShVis = maxOf(leftShoulder.visibility().orElse(0f), leftShoulder.presence().orElse(0f))
        val rightShVis = maxOf(rightShoulder.visibility().orElse(0f), rightShoulder.presence().orElse(0f))

        val ear = when {
            leftEarVis > 0.1f && rightEarVis > 0.1f -> {
                if (leftEar.y() < rightEar.y()) Pair(leftEar.x(), leftEar.y())
                else Pair(rightEar.x(), rightEar.y())
            }
            leftEarVis > 0.1f -> Pair(leftEar.x(), leftEar.y())
            rightEarVis > 0.1f -> Pair(rightEar.x(), rightEar.y())
            else -> return null
        }

        if (leftShVis < 0.1f || rightShVis < 0.1f) return null

        val shoulderMidX = (leftShoulder.x() + rightShoulder.x()) / 2
        val shoulderMidY = (leftShoulder.y() + rightShoulder.y()) / 2

        val dx = ear.first - shoulderMidX
        val dy = ear.second - shoulderMidY

        val angleRad = atan2(dx, -dy)
        val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

        // Сглаживание
        angleHistory.add(angleDeg)
        if (angleHistory.size > HISTORY_SIZE) angleHistory.removeAt(0)

        val smoothAngle = angleHistory.average().toFloat()

        if (System.currentTimeMillis() % 2000 < 50) {
            AppLogger.log("POSE_CALC", "raw=$angleDeg, smooth=$smoothAngle")
        }

        return smoothAngle
    }

    fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
        currentOnResult = null
        angleHistory.clear()
        AppLogger.log("POSE_INIT", "PoseLandmarker closed")
    }
}