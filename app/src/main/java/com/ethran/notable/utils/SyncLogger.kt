package com.ethran.notable.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

object SyncLogger {
    private val _logs = MutableStateFlow<List<SyncLogEntry>>(emptyList())
    val logs: StateFlow<List<SyncLogEntry>> = _logs.asStateFlow()
    
    private val maxLogEntries = 100
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    fun log(level: LogLevel, message: String, source: String = "SyncManager") {
        val entry = SyncLogEntry(
            timestamp = Date(),
            level = level,
            message = message,
            source = source
        )
        
        val currentLogs = _logs.value.toMutableList()
        currentLogs.add(0, entry) // Add to beginning for newest first
        
        // Keep only the most recent entries
        if (currentLogs.size > maxLogEntries) {
            currentLogs.removeAt(currentLogs.size - 1)
        }
        
        _logs.value = currentLogs
        
        // Also log to Android's logcat
        when (level) {
            LogLevel.DEBUG -> android.util.Log.d(source, message)
            LogLevel.INFO -> android.util.Log.i(source, message)
            LogLevel.WARN -> android.util.Log.w(source, message)
            LogLevel.ERROR -> android.util.Log.e(source, message)
        }
    }
    
    fun debug(message: String, source: String = "SyncManager") {
        log(LogLevel.DEBUG, message, source)
    }
    
    fun info(message: String, source: String = "SyncManager") {
        log(LogLevel.INFO, message, source)
    }
    
    fun warn(message: String, source: String = "SyncManager") {
        log(LogLevel.WARN, message, source)
    }
    
    fun error(message: String, source: String = "SyncManager") {
        log(LogLevel.ERROR, message, source)
    }
    
    fun clear() {
        _logs.value = emptyList()
    }
    
    fun getFormattedLogs(): List<String> {
        return _logs.value.map { entry ->
            "${dateFormat.format(entry.timestamp)} [${entry.level.name}] ${entry.message}"
        }
    }
}

data class SyncLogEntry(
    val timestamp: Date,
    val level: LogLevel,
    val message: String,
    val source: String
)

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}