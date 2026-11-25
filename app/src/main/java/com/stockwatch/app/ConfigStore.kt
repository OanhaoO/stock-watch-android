package com.stockwatch.app

import com.stockwatch.app.core.WatchConfig
import com.stockwatch.app.core.loadWatchConfig
import com.stockwatch.app.core.parseWatchConfig
import com.stockwatch.app.core.stringifyJson
import java.io.File

class ConfigStore(private val file: File) {
    fun ensureDefault() {
        if (file.exists()) return
        file.parentFile?.mkdirs()
        file.writeText(
            stringifyJson(
                mapOf(
                    "poll_seconds" to 30,
                    "stocks" to emptyList<Map<String, Any>>(),
                ),
            ),
        )
    }

    fun load(): WatchConfig {
        ensureDefault()
        return loadWatchConfig(file)
    }

    fun readRaw(): String {
        ensureDefault()
        return file.readText()
    }

    fun save(text: String): WatchConfig {
        val parsed = parseWatchConfig(text)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return parsed
    }
}
