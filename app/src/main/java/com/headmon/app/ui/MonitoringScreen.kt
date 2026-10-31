package com.headmon.app.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.headmon.app.data.AppDatabase
import com.headmon.app.data.SessionLog
import com.headmon.app.util.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

private const val DEVIATION_THRESHOLD = 20f
private const val DEVIATION_DELAY_MS = 1500L // Уменьшили с 2000 до 1500 мс
private const val FACE_STABLE_MS = 2000L
private const val HISTORY_SIZE = 10 // Уменьшили окно сглаживания для более быстрой реакции

@Composable
fun MonitoringScreen(
    patientName: String,
    db: AppDatabase,
    onStopMonitoring: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("headmon_prefs", Context.MODE_PRIVATE) }

    // Читаем настройку звука через remember, чтобы при переключении она обновлялась
    val soundEnabled by remember { mutableStateOf(prefs.getBoolean("sound_enabled", true)) }

    var isFrontCamera by remember { mutableStateOf(false) }
    var currentAngles by remember { mutableStateOf<Triple<Float, Float, Float>?>(null) }
    var baselineAngles by remember { mutableStateOf<Triple<Float, Float, Float>?>(null) }
    var statusText by remember { mutableStateOf("Ожидание лица...") }

    // isDeviated теперь отвечает за визуал (красный фон и надпись)
    var isDeviated by remember { mutableStateOf(false) }
    // isSoundPlaying отвечает за звук
    var isSoundPlaying by remember { mutableStateOf(false) }

    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }
    val currentMinuteLogs = remember { Collections.synchronizedList(mutableListOf<Float>()) }

    var lastFaceSeenTime by remember { mutableLongStateOf(0L) }
    var faceStableTime by remember { mutableLongStateOf(0L) }
    var deviationStartTime by remember { mutableLongStateOf(0L) }
    val angleHistory = remember { mutableStateListOf<Triple<Float, Float, Float>>() }

    LaunchedEffect(patientName) {
        AppLogger.log("MONITOR", "Started monitoring for patient: $patientName")
    }

    // Звук
    LaunchedEffect(isSoundPlaying, soundEnabled) {
        if (isSoundPlaying && soundEnabled) {
            while (isSoundPlaying && soundEnabled) {
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
                delay(1000)
            }
        } else {
            toneGenerator.stopTone()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (System.currentTimeMillis() - lastFaceSeenTime > 2000) {
                statusText = "Лицо не обнаружено"
                faceStableTime = 0L
                deviationStartTime = 0L
                isDeviated = false
                isSoundPlaying = false
            }
        }
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val landmarker = remember {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .setDelegate(Delegate.CPU)
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener { result: FaceLandmarkerResult, _ ->
                val matricesOptional = result.facialTransformationMatrixes()

                if (matricesOptional.isPresent && matricesOptional.get().isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    lastFaceSeenTime = now
                    if (faceStableTime == 0L) faceStableTime = now
                    statusText = "Лицо обнаружено"

                    val matrix = matricesOptional.get()[0]
                    val (rawYaw, rawPitch, rawRoll) = matrixToEulerAngles(matrix)

                    // ФИЛЬТР "БИТЫХ" КАДРОВ: отбрасываем только резкие скачки (> 45 град/кадр)
                    var isValidFrame = true
                    if (angleHistory.isNotEmpty()) {
                        val lastAngles = angleHistory.last()
                        if (abs(rawYaw - lastAngles.first) > 45f ||
                            abs(rawPitch - lastAngles.second) > 45f ||
                            abs(rawRoll - lastAngles.third) > 45f) {
                            isValidFrame = false
                        }
                    }

                    if (isValidFrame) {
                        angleHistory.add(Triple(rawYaw, rawPitch, rawRoll))
                        if (angleHistory.size > HISTORY_SIZE) angleHistory.removeAt(0)
                    }

                    if (angleHistory.isEmpty()) {
                        angleHistory.add(Triple(rawYaw, rawPitch, rawRoll))
                    }

                    val smoothYaw = angleHistory.map { it.first }.average().toFloat()
                    val smoothPitch = angleHistory.map { it.second }.average().toFloat()
                    val smoothRoll = angleHistory.map { it.third }.average().toFloat()

                    currentAngles = Triple(smoothYaw, smoothPitch, smoothRoll)

                    if (now % 2000 < 50) {
                        AppLogger.log("RAW_ANGLES", "Yaw: ${"%.1f".format(rawYaw)}, Pitch: ${"%.1f".format(rawPitch)}, Roll: ${"%.1f".format(rawRoll)}")
                    }

                    baselineAngles?.let { baseline ->
                        val deltaYaw = calculateAngleDelta(smoothYaw, baseline.first)
                        val deltaPitch = calculateAngleDelta(smoothPitch, baseline.second)
                        val deltaRoll = calculateAngleDelta(smoothRoll, baseline.third)

                        val maxDelta = maxOf(deltaYaw, deltaPitch, deltaRoll)

                        // ВИЗУАЛЬНОЕ ОТКЛОНЕНИЕ: фиксируем сразу, если угол превышен
                        if (maxDelta > DEVIATION_THRESHOLD) {
                            isDeviated = true

                            // ТАЙМЕР ДЛЯ ЗВУКА: если отклонение держится долго
                            if (deviationStartTime == 0L) deviationStartTime = now
                            if (now - deviationStartTime > DEVIATION_DELAY_MS) {
                                if (!isSoundPlaying) AppLogger.log("ALERT", "Deviation detected! Playing sound.")
                                isSoundPlaying = true
                            }
                        } else {
                            isDeviated = false
                            deviationStartTime = 0L
                            if (isSoundPlaying) {
                                isSoundPlaying = false
                                AppLogger.log("ALERT", "Deviation returned to normal.")
                            }
                        }

                        currentMinuteLogs.add(maxDelta)
                    }
                }
            }
            .setErrorListener { error ->
                AppLogger.log("MEDIAPIPE", "Error: ${error.message}")
            }
            .build()
        FaceLandmarker.createFromOptions(context, options)
    }

    val previewView = remember { PreviewView(context) }
    LaunchedEffect(isFrontCamera) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(executor) { imageProxy: ImageProxy ->
                val bitmap = imageProxy.toBitmap()
                val mpImage = BitmapImageBuilder(bitmap).build()
                landmarker.detectAsync(mpImage, imageProxy.imageInfo.timestamp)
                imageProxy.close()
            }

            val cameraSelector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                AppLogger.log("CAMERA", "Camera bound: ${if (isFrontCamera) "FRONT" else "BACK"}")
            } catch (e: Exception) {
                AppLogger.log("CAMERA", "Bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    LaunchedEffect(baselineAngles) {
        if (baselineAngles != null) {
            AppLogger.log("DB", "Baseline set: Yaw=${baselineAngles!!.first}, Pitch=${baselineAngles!!.second}, Roll=${baselineAngles!!.third}")
            while (true) {
                delay(60_000)
                try {
                    var maxDev = 0f
                    var hasData = false

                    synchronized(currentMinuteLogs) {
                        if (currentMinuteLogs.isNotEmpty()) {
                            maxDev = currentMinuteLogs.maxOrNull() ?: 0f
                            hasData = true
                            currentMinuteLogs.clear()
                        }
                    }

                    if (hasData) {
                        db.sessionLogDao().insert(
                            SessionLog(
                                patientName = patientName,
                                timestamp = System.currentTimeMillis(),
                                maxDeviation = maxDev,
                                isDeviated = maxDev > DEVIATION_THRESHOLD
                            )
                        )
                        AppLogger.log("DB", "Saved minute log: maxDev=$maxDev, isDev=${maxDev > DEVIATION_THRESHOLD}")
                    }
                } catch (e: Exception) {
                    AppLogger.log("DB", "Failed to save log: ${e.message}")
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        if (baselineAngles != null) {
            Box(modifier = Modifier.fillMaxSize().background(if (isDeviated) Color.Red.copy(alpha = 0.3f) else Color.Green.copy(alpha = 0.1f)))
        }

        Card(modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Пациент: $patientName", style = MaterialTheme.typography.titleMedium)
                Text(statusText)
                currentAngles?.let { (yaw, pitch, roll) ->
                    Spacer(Modifier.height(8.dp))
                    Text("Yaw: %.1f°".format(yaw))
                    Text("Pitch: %.1f°".format(pitch))
                    Text("Roll: %.1f°".format(roll))
                }
                if (baselineAngles != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (isDeviated) "ОТКЛОНЕНИЕ!" else "Положение в норме",
                        color = if (isDeviated) Color.Red else Color(0xFF388E3C)
                    )
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (baselineAngles == null) {
                val canCalibrate = currentAngles != null && (System.currentTimeMillis() - faceStableTime > FACE_STABLE_MS)

                Button(
                    onClick = {
                        AppLogger.log("CALIBRATION", "Raw angles at calibration: $currentAngles")
                        baselineAngles = currentAngles
                    },
                    enabled = canCalibrate
                ) {
                    Text("Зафиксировать положение")
                }

                if (!canCalibrate && currentAngles != null) {
                    Text("Подождите, лицо не стабильно", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            } else {
                Button(
                    onClick = {
                        baselineAngles = null
                        isDeviated = false
                        isSoundPlaying = false
                        deviationStartTime = 0L
                        AppLogger.log("CALIBRATION", "Calibration reset")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Сбросить калибровку") }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { isFrontCamera = !isFrontCamera }) {
                Text(if (isFrontCamera) "Задняя камера" else "Передняя камера")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    scope.launch {
                        var maxDev = 0f
                        var hasData = false

                        synchronized(currentMinuteLogs) {
                            if (currentMinuteLogs.isNotEmpty()) {
                                maxDev = currentMinuteLogs.maxOrNull() ?: 0f
                                hasData = true
                                currentMinuteLogs.clear()
                            }
                        }

                        if (hasData) {
                            db.sessionLogDao().insert(
                                SessionLog(
                                    patientName = patientName,
                                    timestamp = System.currentTimeMillis(),
                                    maxDeviation = maxDev,
                                    isDeviated = maxDev > DEVIATION_THRESHOLD
                                )
                            )
                        }

                        AppLogger.log("MONITOR", "Stopped monitoring")
                        onStopMonitoring()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) { Text("Завершить мониторинг") }
        }
    }
}

fun calculateAngleDelta(current: Float, baseline: Float): Float {
    var delta = current - baseline
    while (delta > 180f) delta -= 360f
    while (delta < -180f) delta += 360f
    return abs(delta)
}

fun matrixToEulerAngles(m: FloatArray): Triple<Float, Float, Float> {
    val m00 = m[0]; val m10 = m[1]; val m20 = m[2]
    val m21 = m[6]; val m22 = m[10]
    val pitch = atan2(-m20, sqrt(m00 * m00 + m10 * m10)) * 180f / Math.PI.toFloat()
    val yaw = atan2(m10, m00) * 180f / Math.PI.toFloat()
    val roll = atan2(m21, m22) * 180f / Math.PI.toFloat()
    return Triple(yaw, pitch, roll)
}