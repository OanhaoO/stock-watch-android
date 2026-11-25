package com.stockwatch.app.core

import java.io.File
import java.time.LocalTime
import java.util.Locale

data class TradingSession(val start: LocalTime, val end: LocalTime) {
    constructor(start: String, end: String) : this(LocalTime.parse(start), LocalTime.parse(end))

    override fun toString(): String = "${format(start)}-${format(end)}"

    private fun format(value: LocalTime): String = value.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
}

data class CrossConfig(
    val enabled: Boolean = true,
    val short: Int = 5,
    val long: Int = 20,
)

data class ChangeConfig(
    val enabled: Boolean = true,
    val upperPct: Double = 5.0,
    val lowerPct: Double = -5.0,
    val cooldownMinutes: Long = 120,
)

data class TRangeConfig(
    val enabled: Boolean = false,
    val base: Double = 0.0,
    val dev1Pct: Double = 3.0,
    val dev2Pct: Double = 0.0,
)

data class ProxyConfig(
    val biasMas: List<Int> = listOf(5, 20),
    val upperPct: Double = 4.0,
    val lowerPct: Double = -4.0,
)

data class BiasOverrides(
    val biasMas: List<Int>? = null,
    val biasUpperPct: Double? = null,
    val biasLowerPct: Double? = null,
    val biasCooldownMinutes: Long? = null,
    val minHistoryBars: Int? = null,
)

data class WatchItem(
    val code: String,
    val symbol: String,
    val type: String = "auto",
    val proxy: String = "",
    val bias: BiasOverrides = BiasOverrides(),
    val cross: CrossConfig? = null,
    val change: ChangeConfig? = null,
    val tRange: TRangeConfig? = null,
    val proxyRules: ProxyConfig? = null,
)

data class WatchConfig(
    val pollSeconds: Long = 30,
    val sessions: List<TradingSession> = listOf(
        TradingSession("09:30", "11:30"),
        TradingSession("13:00", "15:00"),
    ),
    val klineBars: Int = 120,
    val navBars: Int = 130,
    val biasMas: List<Int> = listOf(5, 20),
    val biasEnabled: Boolean = true,
    val biasUpperPct: Double = 3.0,
    val biasLowerPct: Double = -3.0,
    val biasCooldownMinutes: Long = 60,
    val minHistoryBars: Int = 60,
    val cross: CrossConfig = CrossConfig(),
    val change: ChangeConfig = ChangeConfig(),
    val tRange: TRangeConfig = TRangeConfig(),
    val proxy: ProxyConfig = ProxyConfig(),
    val items: List<WatchItem> = emptyList(),
)

fun parseWatchConfig(text: String): WatchConfig {
    val root = parseJson(text).asObject() ?: return WatchConfig()
    val defaults = WatchConfig()
    val cross = parseCross(root["cross"], defaults.cross)
    val change = parseChange(root["change"], defaults.change)
    val tRange = parseTRange(root["t_range"], defaults.tRange)
    val proxy = parseProxy(root["proxy"], defaults.proxy)
    val items = root["stocks"]?.asArray().orEmpty().mapNotNull { element ->
        val row = element.asObject() ?: return@mapNotNull null
        val code = (row["code"]?.asString().orEmpty().ifEmpty { row["symbol"]?.asString().orEmpty() }).trim()
        val symbol = try {
            normalizeSymbol(code)
        } catch (_: IllegalArgumentException) {
            ""
        }
        WatchItem(
            code = code,
            symbol = symbol,
            type = (row["type"]?.asString() ?: "auto").lowercase(Locale.ROOT),
            proxy = row["proxy"]?.asString().orEmpty().trim(),
            bias = parseBiasOverrides(row),
            cross = if ("cross" in row.fields) parseCross(row["cross"], cross) else null,
            change = if ("change" in row.fields) parseChange(row["change"], change) else null,
            tRange = if ("t_range" in row.fields) parseTRange(row["t_range"], tRange) else null,
            proxyRules = if ("proxy_rules" in row.fields) parseProxy(row["proxy_rules"], proxy) else null,
        )
    }
    return WatchConfig(
        pollSeconds = root.long("poll_seconds", defaults.pollSeconds),
        sessions = parseSessions(root["sessions"], defaults.sessions),
        klineBars = root.int("kline_bars", defaults.klineBars),
        navBars = root.int("nav_bars", defaults.navBars),
        biasMas = parseIntList(root["bias_mas"], defaults.biasMas),
        biasEnabled = root.boolean("bias_enabled", defaults.biasEnabled),
        biasUpperPct = root.double("bias_upper_pct", defaults.biasUpperPct),
        biasLowerPct = root.double("bias_lower_pct", defaults.biasLowerPct),
        biasCooldownMinutes = root.long("bias_cooldown_minutes", defaults.biasCooldownMinutes),
        minHistoryBars = root.int("min_history_bars", defaults.minHistoryBars),
        cross = cross,
        change = change,
        tRange = tRange,
        proxy = proxy,
        items = items,
    )
}

fun loadWatchConfig(file: File): WatchConfig = parseWatchConfig(file.readText())

fun itemBiasMas(item: WatchItem, cfg: WatchConfig): List<Int> = item.bias.biasMas ?: cfg.biasMas

fun itemBiasUpperPct(item: WatchItem, cfg: WatchConfig): Double = item.bias.biasUpperPct ?: cfg.biasUpperPct

fun itemBiasLowerPct(item: WatchItem, cfg: WatchConfig): Double = item.bias.biasLowerPct ?: cfg.biasLowerPct

fun itemBiasCooldownMinutes(item: WatchItem, cfg: WatchConfig): Long = item.bias.biasCooldownMinutes ?: cfg.biasCooldownMinutes

fun itemMinHistoryBars(item: WatchItem, cfg: WatchConfig): Int = item.bias.minHistoryBars ?: cfg.minHistoryBars

fun itemCross(item: WatchItem, cfg: WatchConfig): CrossConfig = item.cross ?: cfg.cross

fun itemChange(item: WatchItem, cfg: WatchConfig): ChangeConfig = item.change ?: cfg.change

fun itemTRange(item: WatchItem, cfg: WatchConfig): TRangeConfig = item.tRange ?: cfg.tRange

fun proxyConfig(item: WatchItem, cfg: WatchConfig): ProxyConfig = item.proxyRules ?: cfg.proxy

private fun parseBiasOverrides(row: JsonObject): BiasOverrides = BiasOverrides(
    biasMas = if ("bias_mas" in row.fields) parseIntList(row["bias_mas"], emptyList()) else null,
    biasUpperPct = if ("bias_upper_pct" in row.fields) row.double("bias_upper_pct", 0.0) else null,
    biasLowerPct = if ("bias_lower_pct" in row.fields) row.double("bias_lower_pct", 0.0) else null,
    biasCooldownMinutes = if ("bias_cooldown_minutes" in row.fields) row.long("bias_cooldown_minutes", 0) else null,
    minHistoryBars = if ("min_history_bars" in row.fields) row.int("min_history_bars", 0) else null,
)

private fun parseCross(value: JsonElement?, defaults: CrossConfig): CrossConfig {
    val row = value?.asObject() ?: return defaults
    return CrossConfig(
        enabled = row.boolean("enabled", defaults.enabled),
        short = row.int("short", defaults.short),
        long = row.int("long", defaults.long),
    )
}

private fun parseChange(value: JsonElement?, defaults: ChangeConfig): ChangeConfig {
    val row = value?.asObject() ?: return defaults
    return ChangeConfig(
        enabled = row.boolean("enabled", defaults.enabled),
        upperPct = row.double("upper_pct", defaults.upperPct),
        lowerPct = row.double("lower_pct", defaults.lowerPct),
        cooldownMinutes = row.long("cooldown_minutes", defaults.cooldownMinutes),
    )
}

private fun parseTRange(value: JsonElement?, defaults: TRangeConfig): TRangeConfig {
    val row = value?.asObject() ?: return defaults
    return TRangeConfig(
        enabled = row.boolean("enabled", defaults.enabled),
        base = row.double("base", defaults.base),
        dev1Pct = row.double("dev1_pct", defaults.dev1Pct),
        dev2Pct = row.double("dev2_pct", defaults.dev2Pct),
    )
}

private fun parseProxy(value: JsonElement?, defaults: ProxyConfig): ProxyConfig {
    val row = value?.asObject() ?: return defaults
    return ProxyConfig(
        biasMas = if ("bias_mas" in row.fields) parseIntList(row["bias_mas"], defaults.biasMas) else defaults.biasMas,
        upperPct = row.double("upper_pct", defaults.upperPct),
        lowerPct = row.double("lower_pct", defaults.lowerPct),
    )
}

private fun parseSessions(value: JsonElement?, defaults: List<TradingSession>): List<TradingSession> {
    val rows = value?.asArray() ?: return defaults
    return rows.mapNotNull { row ->
        val pair = row.asArray()
        val start = pair.getOrNull(0)?.asString() ?: return@mapNotNull null
        val end = pair.getOrNull(1)?.asString() ?: return@mapNotNull null
        try {
            TradingSession(start, end)
        } catch (_: java.time.DateTimeException) {
            null
        }
    }
}

private fun parseIntList(value: JsonElement?, defaults: List<Int>): List<Int> {
    val rows = value?.asArray() ?: return defaults
    return rows.mapNotNull { it.intValue() }
}

private fun JsonObject.double(key: String, default: Double): Double =
    this[key].doubleValue() ?: default

private fun JsonObject.long(key: String, default: Long): Long =
    this[key].longValue() ?: default

private fun JsonObject.int(key: String, default: Int): Int =
    this[key].intValue() ?: default

private fun JsonObject.boolean(key: String, default: Boolean): Boolean =
    this[key]?.asBoolean() ?: default

private fun JsonElement?.doubleValue(): Double? = when (this) {
    is JsonNumber -> value.takeIf { it.isFinite() }
    is JsonString -> value.toDoubleOrNull()?.takeIf { it.isFinite() }
    else -> null
}

private fun JsonElement?.longValue(): Long? = when (this) {
    is JsonNumber -> value.toLong()
    is JsonString -> value.toLongOrNull()
    else -> null
}

private fun JsonElement?.intValue(): Int? = longValue()?.toInt()
