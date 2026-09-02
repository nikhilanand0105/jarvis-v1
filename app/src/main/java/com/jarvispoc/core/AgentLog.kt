package com.jarvispoc.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel { INFO, STEP, WARN, ERROR, HALT, SUCCESS }

data class LogEntry(val at: Long, val level: LogLevel, val message: String)

/**
 * Single in-memory trace, mirrored to logcat under the tag "JarvisPoc".
 *
 * A process-wide object rather than an injected dependency: the accessibility
 * service and the Activity live in separate lifecycles but the same process,
 * and for a POC a shared StateFlow beats plumbing a bus through both.
 */
object AgentLog {
    const val TAG = "JarvisPoc"
    private const val MAX_ENTRIES = 400

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    fun clear() {
        _entries.value = emptyList()
    }

    fun log(level: LogLevel, message: String) {
        when (level) {
            LogLevel.ERROR -> Log.e(TAG, message)
            LogLevel.WARN, LogLevel.HALT -> Log.w(TAG, message)
            else -> Log.i(TAG, message)
        }
        _entries.update { current ->
            (current + LogEntry(System.currentTimeMillis(), level, message))
                .takeLast(MAX_ENTRIES)
        }
    }

    /**
     * Logs a summary to the in-app trace and the full stack to logcat.
     *
     * The trace pane is for reading on the phone; a stack trace is unreadable
     * there but essential in `adb logcat -s JarvisPoc`.
     */
    fun error(message: String, throwable: Throwable) {
        Log.e(TAG, message, throwable)
        _entries.update { current ->
            (current + LogEntry(
                System.currentTimeMillis(),
                LogLevel.ERROR,
                "$message — ${throwable.javaClass.simpleName}: ${throwable.message}",
            )).takeLast(MAX_ENTRIES)
        }
    }

    fun info(message: String) = log(LogLevel.INFO, message)
    fun step(message: String) = log(LogLevel.STEP, message)
    fun warn(message: String) = log(LogLevel.WARN, message)
    fun error(message: String) = log(LogLevel.ERROR, message)
    fun halt(message: String) = log(LogLevel.HALT, message)
    fun success(message: String) = log(LogLevel.SUCCESS, message)
}
