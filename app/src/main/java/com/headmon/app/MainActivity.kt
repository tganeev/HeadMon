package com.headmon.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.sqrt
import java.util.concurrent.Executors

private const val TAG = "HeadMon"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HeadMonScreen()
                }
            }
        }
    }
}

@Composable
fun HeadMonScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    // Состояние для переключения камеры (false = задняя, true = передняя)
    var isFrontCamera by remember { mutableStateOf(false) }

    var eulerAngles by remember { mutableStateOf<Triple<Float, Float, Float>?>(null) }
    var statusText by remember { mutableStateOf("Ожидание лица...") }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Требуется разрешение на камеру")
        }
        return
    }

    val executor = remember { Executors.newSingleThreadExecutor() }

    // Инициализация MediaPipe
    val landmarker = remember {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener { result: FaceLandmarkerResult, _ ->
                val matricesOptional = result.facialTransformationMatrixes()

                if (matricesOptional.isPresent && matricesOptional.get().isNotEmpty()) {
                    val matrix = matricesOptional.get()[0]
                    val (yaw, pitch, roll) = matrixToEulerAngles(matrix)
                    eulerAngles = Triple(yaw, pitch, roll)
                    statusText = "Лицо обнаружено"
                } else {
                    statusText = "Матрица недоступна"
                }
            }
            .setErrorListener { error ->
                Log.e(TAG, "MediaPipe error: ${error.message}")
                statusText = "Ошибка: ${error.message}"
            }
            .build()
        FaceLandmarker.createFromOptions(context, options)
    }

    val previewView = remember { PreviewView(context) }

    // Этот LaunchedEffect перезапустится при изменении isFrontCamera
    LaunchedEffect(isFrontCamera) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
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

            val cameraSelector = if (isFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Карточка с информацией
        Card(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(statusText, style = MaterialTheme.typography.titleMedium)
                eulerAngles?.let { (yaw, pitch, roll) ->
                    Spacer(Modifier.height(8.dp))
                    Text("Yaw: %.1f°".format(yaw))
                    Text("Pitch: %.1f°".format(pitch))
                    Text("Roll: %.1f°".format(roll))
                }
            }
        }

        // Кнопка переключения камеры
        Button(
            onClick = { isFrontCamera = !isFrontCamera },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
        ) {
            Text(if (isFrontCamera) "Переключить на заднюю" else "Переключить на переднюю")
        }
    }
}

fun matrixToEulerAngles(m: FloatArray): Triple<Float, Float, Float> {
    val m00 = m[0]; val m01 = m[4]; val m02 = m[8]
    val m10 = m[1]; val m11 = m[5]; val m12 = m[9]
    val m20 = m[2]; val m21 = m[6]; val m22 = m[10]

    val pitch = atan2(-m20, sqrt(m00 * m00 + m10 * m10)) * 180f / Math.PI.toFloat()
    val yaw = atan2(m10, m00) * 180f / Math.PI.toFloat()
    val roll = atan2(m21, m22) * 180f / Math.PI.toFloat()

    // Примечание: m01, m02, m11, m12 не используются в этой упрощенной формуле,
    // но предупреждения об их неиспользовании не помешают компиляции.

    return Triple(yaw, pitch, roll)
}