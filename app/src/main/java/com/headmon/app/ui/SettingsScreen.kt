package com.headmon.app.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.headmon.app.ThermalPreviewActivity
import com.headmon.app.util.ThermalDeviceChecker
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("headmon_prefs", Context.MODE_PRIVATE) }

    var soundEnabled by remember { mutableStateOf(prefs.getBoolean("sound_enabled", true)) }
    var showPoseCard by remember { mutableStateOf(prefs.getBoolean("show_pose_card", false)) }
    var detectionMode by remember { mutableStateOf(prefs.getString("detection_mode", "BOTH") ?: "BOTH") }
    var inputSource by remember { mutableStateOf(prefs.getString("input_source", "CAMERA") ?: "CAMERA") }
    var deviationThreshold by remember { mutableStateOf(prefs.getFloat("deviation_threshold", 20f)) }

    // Состояние для проверки USB
    var usbStatus by remember { mutableStateOf("Нажмите для проверки") }
    var isUsbChecked by remember { mutableStateOf(false) }

    var expanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Назад") }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // --- Секция: Звук ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Звуковое оповещение", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = soundEnabled,
                    onCheckedChange = {
                        soundEnabled = it
                        prefs.edit().putBoolean("sound_enabled", it).apply()
                    }
                )
            }

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            // --- Секция: Отображение табло углов ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Показывать табло углов положения головы", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = showPoseCard,
                    onCheckedChange = {
                        showPoseCard = it
                        prefs.edit().putBoolean("show_pose_card", it).apply()
                    }
                )
            }

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            // --- Секция: Источник детекции ---
            Text("Источник детекции", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = inputSource == "CAMERA",
                    onClick = {
                        inputSource = "CAMERA"
                        prefs.edit().putString("input_source", "CAMERA").apply()
                    },
                    label = { Text("Камера") }
                )
                FilterChip(
                    selected = inputSource == "THERMAL",
                    onClick = {
                        inputSource = "THERMAL"
                        prefs.edit().putString("input_source", "THERMAL").apply()
                    },
                    label = { Text("Тепловизор") }
                )
            }

            Text(
                text = if (inputSource == "THERMAL") {
                    "Мониторинг будет использовать USB-тепловизор. Подключите его по OTG и разрешите доступ."
                } else {
                    "Мониторинг будет использовать встроенную камеру устройства."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            if (inputSource == "CAMERA") {
                // --- Секция: Модели ---
                Text("Модель детекции", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val text = when (detectionMode) {
                            "FACE" -> "Только лицо (Face Landmarker)"
                            "POSE" -> "Только тело (Pose Landmarker)"
                            else -> "Гибридный режим (Face + Pose)"
                        }
                        Text(text)
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Гибридный режим (Face + Pose)") },
                            onClick = {
                                detectionMode = "BOTH"
                                prefs.edit().putString("detection_mode", "BOTH").apply()
                                expanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Только лицо (Face Landmarker)") },
                            onClick = {
                                detectionMode = "FACE"
                                prefs.edit().putString("detection_mode", "FACE").apply()
                                expanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Только тело (Pose Landmarker)") },
                            onClick = {
                                detectionMode = "POSE"
                                prefs.edit().putString("detection_mode", "POSE").apply()
                                expanded = false
                            }
                        )
                    }
                }

                Text(
                    text = "Гибридный режим использует лицо для точных углов, а тело — для случаев, когда лицо не видно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Text(
                    text = "Для тепловизора используется отдельный алгоритм: самое яркое тепловое пятно, FFC-фильтр и оценка смещения головы.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            // --- Секция: Порог отклонения ---
            Text("Допустимое отклонение: ${deviationThreshold.roundToInt()}°", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = deviationThreshold,
                onValueChange = {
                    deviationThreshold = it
                    prefs.edit().putFloat("deviation_threshold", it).apply()
                },
                valueRange = 5f..45f,
                steps = 39
            )
            Text(
                text = "Если голова отклоняется больше чем на ${deviationThreshold.roundToInt()}°, сработает сигнал.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            // --- Секция: Тепловизор (USB) ---
            Text("Тепловизор (USB)", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    // Запускаем Activity с превью
                    val intent = android.content.Intent(context, ThermalPreviewActivity::class.java)
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Открыть превью и анализ тепловизора")
            }
            Text(
                text = "MVP: детекция самого яркого теплового пятна, FFC-фильтр и метрики положения.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            Button(
                onClick = {
                    val devices = ThermalDeviceChecker.getConnectedUsbDevices(context)
                    if (devices.isEmpty()) {
                        usbStatus = "USB-устройства не найдены.\nПроверьте OTG и кабель."
                        isUsbChecked = false
                    } else {
                        usbStatus = "Найдено устройств: ${devices.size}\n" + devices.joinToString("\n")
                        isUsbChecked = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Проверить подключение")
            }

            Text(
                text = usbStatus,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
                color = if (isUsbChecked) Color(0xFF388E3C) else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Вернуться в меню")
            }

            Spacer(modifier = Modifier.height(16.dp)) // Отступ снизу
        }
    }
}