package com.headmon.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val TAG = "HeadMonApp"
    private const val LOG_FILE_NAME = "headmon_logs.txt"
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val logBuffer = mutableListOf<String>()
    private const val MAX_LOGS = 1000
    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE_NAME)

        val defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log("CRASH", "Uncaught exception: ${throwable.message}\n${throwable.stackTraceToString()}")
            defaultUncaughtExceptionHandler?.uncaughtException(thread, throwable)
        }

        loadFromFile()
        log("APP", "App initialized")
    }

    fun log(tag: String, message: String) {
        val time = dateFormat.format(Date())
        val logEntry = "[$time] [$tag] $message"
        Log.d(TAG, logEntry)

        synchronized(logBuffer) {
            logBuffer.add(logEntry)
            if (logBuffer.size > MAX_LOGS) {
                logBuffer.removeAt(0)
            }
            saveToFile()
        }
    }

    private fun saveToFile() {
        try {
            logFile?.writeText(logBuffer.joinToString("\n"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file", e)
        }
    }

    fun getLogs(): String {
        synchronized(logBuffer) {
            return if (logBuffer.isEmpty()) "Логов пока нет." else logBuffer.joinToString("\n")
        }
    }

    fun clearLogs(context: Context) {
        synchronized(logBuffer) {
            logBuffer.clear()
            logFile?.delete()
        }
        Log.d(TAG, "Logs cleared")
    }

    private fun loadFromFile() {
        try {
            if (logFile?.exists() == true) {
                val lines = logFile!!.readLines()
                synchronized(logBuffer) {
                    logBuffer.clear()
                    logBuffer.addAll(lines.takeLast(MAX_LOGS))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load logs", e)
        }
    }
}