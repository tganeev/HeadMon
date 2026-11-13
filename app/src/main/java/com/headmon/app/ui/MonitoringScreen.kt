package com.headmon.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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
import com.headmon.app.util.PoseAnalyzer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

private const val DEVIATION_DELAY_MS = 1500L
private const val FACE_STABLE_MS = 2000L
private const val HISTORY_SIZE = 10

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

    val soundEnabled by remember { mutableStateOf(prefs.getBoolean("sound_enabled", true)) }
    val detectionMode by remember { mutableStateOf(prefs.getString("detection_mode", "BOTH") ?: "BOTH") }
    // Читаем порог из настроек (по умолчанию 20)
    val deviationThreshold by remember { mutableStateOf(prefs.getFloat("deviation_threshold", 20f)) }

    var isFrontCamera by remember { mutableStateOf(false) }

    var currentAngles by remember { mutableStateOf<Triple<Float, Float, Float>?>(null) }
    var baselineAngles by remember { mutableStateOf<Triple<Float, Float, Float>?>(null) }

    var currentPoseAngle by remember { mutableStateOf<Float?>(null) }
    var baselinePoseAngle by remember { mutableStateOf<Float?>(null) }

    var statusText by remember { mutableStateOf("Ожидание...") }
    var isDeviated by remember { mutableStateOf(false) }
    var isSoundPlaying by remember { mutableStateOf(false) }

    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }
    val currentMinuteLogs = remember { Collections.synchronizedList(mutableListOf<Float>()) }

    var lastFaceSeenTime by remember { mutableLongStateOf(0L) }
    var faceStableTime by remember { mutableLongStateOf(0L) }
    var deviationStartTime by remember { mutableLongStateOf(0L) }
    val angleHistory = remember { mutableStateListOf<Triple<Float, Float, Float>>() }

    LaunchedEffect(patientName) {
        AppLogger.log("MONITOR", "Started monitoring for patient: $patientName. Mode: $detectionMode. Threshold: $deviationThreshold")
    }

    LaunchedEffect(detectionMode) {
        if (detectionMode == "POSE" || detectionMode == "BOTH") {
            PoseAnalyzer.init(context) { angle ->
                currentPoseAngle = angle
            }
        }
    }

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

    LaunchedEffect(currentAngles, currentPoseAngle, lastFaceSeenTime, baselineAngles, baselinePoseAngle, deviationThreshold) {
        if (detectionMode == "FACE") {
            if (currentAngles != null) statusText = "Лицо обнаружено"
            else if (System.currentTimeMillis() - lastFaceSeenTime > 2000) statusText = "Лицо не обнаружено"
        } else if (detectionMode == "POSE") {
            if (currentPoseAngle != null) statusText = "Тело обнаружено"
            else statusText = "Тело не обнаружено"
        } else if (detectionMode == "BOTH") {
            if (currentAngles != null) statusText = "Лицо обнаружено"
            else if (currentPoseAngle != null) statusText = "Тело обнаружено (Pose)"
            else statusText = "Лицо и тело не обнаружены"
        }

        val now = System.currentTimeMillis()

        // Проверка Face
        if (currentAngles != null && baselineAngles != null) {
            val (yaw, pitch, roll) = currentAngles!!
            val (bYaw, bPitch, bRoll) = baselineAngles!!
            val deltaYaw = calculateAngleDelta(yaw, bYaw)
            val deltaPitch = calculateAngleDelta(pitch, bPitch)
            val deltaRoll = calculateAngleDelta(roll, bRoll)
            val maxDelta = maxOf(deltaYaw, deltaPitch, deltaRoll)

            if (maxDelta > deviationThreshold) {
                if (deviationStartTime == 0L) deviationStartTime = now
                if (now - deviationStartTime > DEVIATION_DELAY_MS) {
                    if (!isSoundPlaying) AppLogger.log("ALERT", "Face deviation detected!")
                    isSoundPlaying = true
                }
                isDeviated = true
            } else {
                if (isSoundPlaying && (currentPoseAngle == null || baselinePoseAngle == null)) {
                    isSoundPlaying = false
                    AppLogger.log("ALERT", "Face deviation returned to normal.")
                }
                if (currentPoseAngle == null) {
                    isDeviated = false
                    deviationStartTime = 0L
                }
            }
            currentMinuteLogs.add(maxDelta)
        }

        // Проверка Pose
        val shouldCheckPose = (detectionMode == "POSE") || (detectionMode == "BOTH" && currentAngles == null)
        if (shouldCheckPose && currentPoseAngle != null && baselinePoseAngle != null) {
            val deltaPose = calculateAngleDelta(currentPoseAngle!!, baselinePoseAngle!!)

            if (deltaPose > deviationThreshold) {
                if (deviationStartTime == 0L) deviationStartTime = now
                if (now - deviationStartTime > DEVIATION_DELAY_MS) {
                    if (!isSoundPlaying) AppLogger.log("ALERT", "Pose deviation detected!")
                    isSoundPlaying = true
                }
                isDeviated = true
            } else {
                if (isSoundPlaying && (currentAngles == null || baselineAngles == null)) {
                    isSoundPlaying = false
                    AppLogger.log("ALERT", "Pose deviation returned to normal.")
                }
                if (currentAngles == null) {
                    isDeviated = false
                    deviationStartTime = 0L
                }
            }
            currentMinuteLogs.add(deltaPose)
        }
    }

    val executor = remember { Executors.newSingleThreadExecutor() }

    val landmarker = remember(detectionMode) {
        if (detectionMode == "POSE") return@remember null

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

                    val matrix = matricesOptional.get()[0]
                    val (rawYaw, rawPitch, rawRoll) = matrixToEulerAngles(matrix)

                    var isValidFrame = true
                    if (angleHistory.isNotEmpty()) {
                        val last = angleHistory.last()
                        if (abs(rawYaw - last.first) > 45f ||
                            abs(rawPitch - last.second) > 45f ||
                            abs(rawRoll - last.third) > 45f) {
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
                }
            }
            .setErrorListener { error ->
                AppLogger.log("MEDIAPIPE", "Error: ${error.message}")
            }
            .build()
        FaceLandmarker.createFromOptions(context, options)
    }

    val previewView = remember { PreviewView(context) }
    LaunchedEffect(isFrontCamera, detectionMode) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(executor) { imageProxy: ImageProxy ->
                var bitmap = imageProxy.toBitmap()

                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                if (rotationDegrees != 0) {
                    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                }

                if (landmarker != null) {
                    val mpImage = BitmapImageBuilder(bitmap).build()
                    landmarker.detectAsync(mpImage, imageProxy.imageInfo.timestamp)
                }

                if (detectionMode == "POSE" || detectionMode == "BOTH") {
                    PoseAnalyzer.detect(bitmap, imageProxy.imageInfo.timestamp)
                }

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

    LaunchedEffect(baselineAngles, baselinePoseAngle) {
        if (baselineAngles != null || baselinePoseAngle != null) {
            AppLogger.log("DB", "Baseline set: Face=$baselineAngles, Pose=$baselinePoseAngle")
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
                                isDeviated = maxDev > deviationThreshold
                            )
                        )
                        AppLogger.log("DB", "Saved minute log: maxDev=$maxDev, isDev=${maxDev > deviationThreshold}")
                    }
                } catch (e: Exception) {
                    AppLogger.log("DB", "Failed to save log: ${e.message}")
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        if (baselineAngles != null || baselinePoseAngle != null) {
            Box(modifier = Modifier.fillMaxSize().background(if (isDeviated) Color.Red.copy(alpha = 0.3f) else Color.Green.copy(alpha = 0.1f)))
        }

        Card(modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Пациент: $patientName", style = MaterialTheme.typography.titleMedium)
                Text("Режим: $detectionMode, Порог: ${deviationThreshold.toInt()}°", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Text(statusText)
                currentAngles?.let { (yaw, pitch, roll) ->
                    Spacer(Modifier.height(8.dp))
                    Text("Yaw: %.1f°".format(yaw))
                    Text("Pitch: %.1f°".format(pitch))
                    Text("Roll: %.1f°".format(roll))
                }
                currentPoseAngle?.let { angle ->
                    Spacer(Modifier.height(8.dp))
                    Text("Pose: %.1f°".format(angle), color = MaterialTheme.colorScheme.secondary)
                }
                if (baselineAngles != null || baselinePoseAngle != null) {
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
            if (baselineAngles == null && baselinePoseAngle == null) {
                val canCalibrateFace = currentAngles != null && (System.currentTimeMillis() - faceStableTime > FACE_STABLE_MS)
                val canCalibratePose = currentPoseAngle != null

                Button(
                    onClick = {
                        AppLogger.log("CALIBRATION", "Raw angles at calibration: Face=$currentAngles, Pose=$currentPoseAngle")
                        baselineAngles = currentAngles
                        baselinePoseAngle = currentPoseAngle
                    },
                    enabled = canCalibrateFace || canCalibratePose
                ) {
                    Text("Зафиксировать положение")
                }

                if (!canCalibrateFace && !canCalibratePose && (currentAngles != null || currentPoseAngle != null)) {
                    Text("Подождите, данные не стабильны", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            } else {
                Button(
                    onClick = {
                        baselineAngles = null
                        baselinePoseAngle = null
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
                                    isDeviated = maxDev > deviationThreshold
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