package com.headmon.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Gravity
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.headmon.app.thermal.ThermalCameraController
import com.headmon.app.thermal.ThermalFrameAnalyzer
import com.headmon.app.thermal.ThermalHeadTrackingSource
import com.headmon.app.thermal.ThermalTrackingEvent
import com.headmon.app.util.AppLogger
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import java.util.Locale

/**
 * Отладочный экран тепловизора.
 *
 * Показывает живое превью, статус камеры и метрики MVP-анализатора:
 * самое яркое тепловое пятно, его центр, наклон и угловые прокси.
 */
class ThermalPreviewActivity : AppCompatActivity() {

    private lateinit var cameraView: AspectRatioTextureView
    private lateinit var statusView: TextView
    private lateinit var source: ThermalHeadTrackingSource

    private var surfaceReady = false
    private var cameraPermissionGranted = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        if (granted) {
            startIfReady()
        } else {
            statusView.text = "Нет разрешения на использование камеры"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.log("THERMAL", "ThermalPreviewActivity started")

        cameraPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        buildUi()

        source = ThermalHeadTrackingSource(
            context = this,
            analyzer = ThermalFrameAnalyzer(),
            previewView = cameraView
        ) { event ->
            renderEvent(event)
        }

        if (!cameraPermissionGranted) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        startIfReady()
    }

    override fun onPause() {
        source.stop()
        super.onPause()
    }

    override fun onDestroy() {
        source.release()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        cameraView = AspectRatioTextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    surfaceReady = true
                    startIfReady()
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) = Unit

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    surfaceReady = false
                    if (::source.isInitialized) {
                        source.stop()
                    }
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
        root.addView(cameraView)

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            text = "Ожидание камеры..."
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }
        root.addView(statusView)

        val backButton = Button(this).apply {
            text = "Назад"
            setOnClickListener { finish() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                leftMargin = 32
                bottomMargin = 32
            }
        }
        root.addView(backButton)

        val resetButton = Button(this).apply {
            text = "Сброс"
            setOnClickListener {
                if (::source.isInitialized) {
                    source.resetAnalyzer()
                    statusView.text = "Анализатор сброшен"
                }
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                rightMargin = 32
                bottomMargin = 32
            }
        }
        root.addView(resetButton)

        setContentView(root)
    }

    private fun startIfReady() {
        if (!surfaceReady || !cameraPermissionGranted || !::source.isInitialized) {
            return
        }
        source.start()
    }

    private fun renderEvent(event: ThermalTrackingEvent) {
        statusView.text = when (event) {
            is ThermalTrackingEvent.CameraState -> formatCameraState(event)
            is ThermalTrackingEvent.Device -> {
                if (event.name == null) "Тепловизор не найден. Подключите устройство по OTG."
                else "Тепловизор: ${event.name}"
            }

            is ThermalTrackingEvent.Pose -> formatPose(event)
            ThermalTrackingEvent.NoHead -> "Голова не обнаружена"
            ThermalTrackingEvent.Ffc -> "FFC: глобальный скачок яркости, стабилизация..."
            is ThermalTrackingEvent.Error -> "Ошибка: ${event.message}"
        }
    }

    private fun formatCameraState(event: ThermalTrackingEvent.CameraState): String {
        return when (event.state) {
            ThermalCameraController.State.IDLE -> "Ожидание..."
            ThermalCameraController.State.WAITING_FOR_DEVICE -> "Поиск тепловизора..."
            ThermalCameraController.State.REQUESTING_PERMISSION -> "Запрос доступа к USB..."
            ThermalCameraController.State.OPENING -> "Открытие тепловизора..."
            ThermalCameraController.State.STREAMING ->
                "Тепловизор работает${event.message?.let { ": $it" } ?: ""}"

            ThermalCameraController.State.STOPPED -> "Тепловизор остановлен"
            ThermalCameraController.State.ERROR -> "Ошибка: ${event.message ?: "неизвестная"}"
        }
    }

    private fun formatPose(event: ThermalTrackingEvent.Pose): String {
        val m = event.metrics
        val box = m.bbox
        return String.format(
            Locale.US,
            "Голова: yaw=%.1f°  pitch=%.1f°  roll=%.1f°\n" +
                "Смещение: dx=%.3f  dy=%.3f\n" +
                "Confidence: %.2f  Area: %.3f\n" +
                "BBox: [%d,%d]-[%d,%d]  mean=%.1f  thr=%.1f",
            m.yawDeg,
            m.pitchDeg,
            m.rollDeg,
            m.dxNorm,
            m.dyNorm,
            m.confidence,
            m.areaRatio,
            box.left,
            box.top,
            box.right,
            box.bottom,
            m.meanBrightness,
            m.threshold
        )
    }
}
