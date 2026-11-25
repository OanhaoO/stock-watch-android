package com.stockwatch.app.core

import java.io.File

class AlertState(
    private val file: File? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val alerts = mutableMapOf<String, AlertRecord>()
    private val zones = mutableMapOf<String, ZoneRecord>()

    init {
        load()
    }

    @Synchronized
    fun allow(key: String, day: String, cooldownMinutes: Long?): Boolean {
        val record = alerts[key] ?: return true
        if (record.day != day) return true
        if (cooldownMinutes == null) return false
        return nowMillis() - record.ts >= cooldownMinutes * 60_000
    }

    @Synchronized
    fun record(key: String, day: String, value: Any? = null) {
        alerts[key] = AlertRecord(day, nowMillis(), value)
        save()
    }

    @Synchronized
    fun zone(key: String, day: String): String {
        val record = zones[key] ?: return TSide.IN
        return if (record.day == day) record.zone else TSide.IN
    }

    @Synchronized
    fun setZone(key: String, day: String, zone: String) {
        zones[key] = ZoneRecord(day, zone, nowMillis())
        save()
    }

    private fun load() {
        val path = file ?: return
        if (!path.exists()) return
        try {
            val root = parseJson(path.readText()).asObject() ?: return
            root["alerts"]?.asObject()?.fields?.forEach { (key, element) ->
                val row = element.asObject() ?: return@forEach
                val day = row["day"]?.asString() ?: return@forEach
                alerts[key] = AlertRecord(day, row["ts"]?.asLong() ?: 0L, row["value"])
            }
            root["t_zone"]?.asObject()?.fields?.forEach { (key, element) ->
                val row = element.asObject() ?: return@forEach
                val day = row["day"]?.asString() ?: return@forEach
                val zone = row["zone"]?.asString() ?: return@forEach
                zones[key] = ZoneRecord(day, zone, row["ts"]?.asLong() ?: 0L)
            }
        } catch (_: Exception) {
            alerts.clear()
            zones.clear()
        }
    }

    private fun save() {
        val path = file ?: return
        val alertJson = alerts.mapValues { (_, record) ->
            mapOf("day" to record.day, "ts" to record.ts, "value" to record.value)
        }
        val zoneJson = zones.mapValues { (_, record) ->
            mapOf("day" to record.day, "zone" to record.zone, "ts" to record.ts)
        }
        path.parentFile?.mkdirs()
        path.writeText(stringifyJson(mapOf("alerts" to alertJson, "t_zone" to zoneJson)))
    }

    private data class AlertRecord(val day: String, val ts: Long, val value: JsonElement?)
    private data class ZoneRecord(val day: String, val zone: String, val ts: Long)
}
