package com.stockwatch.app

import com.stockwatch.app.core.WatchConfig
import com.stockwatch.app.core.loadWatchConfig
import com.stockwatch.app.core.parseJson
import com.stockwatch.app.core.parseWatchConfig
import com.stockwatch.app.core.stringifyJson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ConfigStore(private val file: File) {
    @Synchronized
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

    @Synchronized
    fun save(text: String): WatchConfig {
        val parsed = parseWatchConfig(text)
        val parent = file.parentFile
        if (parent != null) {
            parent.mkdirs()
            if (file.exists()) {
                val current = file.readText()
                try {
                    parseJson(current)
                    file.copyTo(File(parent, "config.backup.json"), overwrite = true)
                } catch (_: Exception) {
                }
            }
            val temporary = File(parent, ".${file.name}.tmp")
            temporary.writeText(text)
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } else {
            file.writeText(text)
        }
        return parsed
    }
}
