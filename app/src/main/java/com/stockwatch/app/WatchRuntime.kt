package com.stockwatch.app

import android.content.Context
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object WatchRuntime {
    private var engine: WatchEngine? = null
    private var store: ConfigStore? = null
    private var pushed = 0L
    private var cycles = 0L
    private var lastAt: String? = null
    private var lastError: String? = null

    @Synchronized
    fun engine(context: Context): WatchEngine {
        val app = context.applicationContext
        return engine ?: WatchEngine(app.filesDir, Notifier(app)).also { engine = it }
    }

    @Synchronized
    fun store(context: Context): ConfigStore {
        val app = context.applicationContext
        return store ?: ConfigStore(java.io.File(app.filesDir, "config.json")).also { store = it }
    }

    @Synchronized
    fun recordCycle(count: Int, at: LocalDateTime) {
        pushed += count
        cycles++
        lastAt = at.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        lastError = null
    }

    @Synchronized
    fun recordError(message: String?) {
        lastError = message
    }

    @Synchronized
    fun status(): Map<String, Any?> = mapOf(
        "pushed" to pushed,
        "cycles" to cycles,
        "last_at" to lastAt,
        "last_error" to lastError,
    )
}
