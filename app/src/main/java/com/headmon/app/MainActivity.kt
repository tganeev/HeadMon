package com.headmon.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.headmon.app.data.AppDatabase
import com.headmon.app.ui.LogsScreen
import com.headmon.app.ui.MonitoringScreen
import com.headmon.app.ui.ReportScreen
import com.headmon.app.ui.SettingsScreen
import com.headmon.app.util.AppLogger

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(this)
        AppLogger.log("APP", "App started")
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation()
                }
            }
        }
    }
}

// Перечисление для экранов
enum class Screen { MENU, MONITORING, REPORTS, SETTINGS, LOGS }

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }

    var currentScreen by remember { mutableStateOf(Screen.MENU) }
    var currentPatientName by remember { mutableStateOf("") }
    var showNameDialog by remember { mutableStateOf(false) }
    var inputName by remember { mutableStateOf("") }

    // Запрос разрешения на камеру (один раз при старте)
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    when (currentScreen) {
        Screen.MENU -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("HeadMon", style = MaterialTheme.typography.displayMedium)
                Spacer(modifier = Modifier.height(48.dp))

                Button(
                    onClick = { showNameDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = hasPermission
                ) {
                    Text("Запустить мониторинг", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { currentScreen = Screen.REPORTS },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Отчеты по пациентам", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { currentScreen = Screen.SETTINGS },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Настройки", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { currentScreen = Screen.LOGS },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Логи", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        Screen.SETTINGS -> SettingsScreen(
            onBack = { currentScreen = Screen.MENU }
        )

        Screen.REPORTS -> ReportScreen(
            db = db,
            onBack = { currentScreen = Screen.MENU }
        )

        Screen.MONITORING -> MonitoringScreen(
            patientName = currentPatientName,
            db = db,
            onStopMonitoring = { currentScreen = Screen.MENU }
        )

        Screen.LOGS -> LogsScreen(
            onBack = { currentScreen = Screen.MENU }
        )
    }

    // Диалог ввода ФИО
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Введите ФИО пациента") },
            text = {
                OutlinedTextField(
                    value = inputName,
                    onValueChange = { inputName = it },
                    label = { Text("ФИО") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (inputName.isNotBlank()) {
                            currentPatientName = inputName.trim()
                            showNameDialog = false
                            currentScreen = Screen.MONITORING
                        }
                    }
                ) {
                    Text("Начать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}