package com.headmon.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.headmon.app.data.AppDatabase
import com.headmon.app.data.SessionLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReportScreen(
    db: AppDatabase,
    onBack: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // Состояние фильтра
    var selectedPatient by remember { mutableStateOf<String?>(null) }
    var patientNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var logs by remember { mutableStateOf<List<SessionLog>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }

    // Загрузка данных при старте
    LaunchedEffect(Unit) {
        patientNames = db.sessionLogDao().getAllPatientNames()
        logs = if (selectedPatient == null) {
            db.sessionLogDao().getAllLogs()
        } else {
            db.sessionLogDao().getLogsForPatient(selectedPatient!!)
        }
    }

    // Перезагрузка логов при смене пациента
    LaunchedEffect(selectedPatient) {
        logs = if (selectedPatient == null) {
            db.sessionLogDao().getAllLogs()
        } else {
            db.sessionLogDao().getLogsForPatient(selectedPatient!!)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Отчеты по пациентам", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        // Выпадающий список пациентов
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selectedPatient ?: "Все пациенты")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Все пациенты") },
                    onClick = {
                        selectedPatient = null
                        expanded = false
                    }
                )
                patientNames.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            selectedPatient = name
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (logs.isEmpty()) {
            Text("Нет данных для отображения")
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Назад в меню")
            }
            return
        }

        // Статистика
        val totalMinutes = logs.size.toFloat()
        val deviatedMinutes = logs.count { it.isDeviated }.toFloat()
        val compliancePercent = if (totalMinutes > 0) ((totalMinutes - deviatedMinutes) / totalMinutes * 100).toInt() else 0

        Text("Общее время: $totalMinutes мин.", style = MaterialTheme.typography.bodyLarge)
        Text("Время в норме: ${totalMinutes - deviatedMinutes} мин. ($compliancePercent%)", color = Color(0xFF388E3C), fontWeight = FontWeight.Bold)
        Text("Время с отклонениями: $deviatedMinutes мин.", color = Color.Red, fontWeight = FontWeight.Bold)

        Spacer(modifier = Modifier.height(24.dp))

        // Таймлайн
        Text("Таймлайн:", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            val segmentWidthPx = 10f
            Canvas(modifier = Modifier.width((logs.size * 10).dp).fillMaxHeight()) {
                logs.forEachIndexed { index, log ->
                    val color = if (log.isDeviated) Color.Red else Color.Green
                    drawRect(
                        color = color,
                        topLeft = Offset(index * segmentWidthPx, 0f),
                        size = Size(segmentWidthPx, size.height)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("Зафиксированные отклонения:", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        val deviations = logs.filter { it.isDeviated }
        if (deviations.isEmpty()) {
            Text("Отклонений не было", color = Color(0xFF388E3C))
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(deviations) { log ->
                    Row(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text("${log.patientName} | ${timeFormat.format(Date(log.timestamp))} - ", color = Color.Red)
                        Text("до %.1f°".format(log.maxDeviation))
                    }
                }
            }
        }

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text("Назад в меню")
        }
    }
}