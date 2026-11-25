package com.stockwatch.app.panel

import com.stockwatch.app.core.JsonArray
import com.stockwatch.app.core.JsonBoolean
import com.stockwatch.app.core.JsonElement
import com.stockwatch.app.core.JsonNull
import com.stockwatch.app.core.JsonNumber
import com.stockwatch.app.core.JsonObject
import com.stockwatch.app.core.JsonString
import com.stockwatch.app.core.asArray
import com.stockwatch.app.core.asBoolean
import com.stockwatch.app.core.asDouble
import com.stockwatch.app.core.asObject
import com.stockwatch.app.core.asString
import com.stockwatch.app.core.defaultWatchConfigMap
import com.stockwatch.app.core.normalizeSymbol
import java.time.LocalTime
import java.time.format.DateTimeParseException

data class PanelConfigResult(val config: Map<String, Any?>, val errors: List<String>)

object PanelConfigValidator {
    private val defaults = defaultWatchConfigMap()
    private val timePattern = Regex("\\d{2}:\\d{2}")

    fun validate(raw: JsonElement, previous: Map<String, JsonElement> = emptyMap()): PanelConfigResult {
        val errors = mutableListOf<String>()
        val source = raw.asObject()?.fields
        if (source == null) {
            return PanelConfigResult(emptyMap(), listOf("配置必须是 JSON 对象"))
        }
        val sessions = parseSessions(valueOrDefault(source, previous, "sessions", defaults["sessions"]), errors)
        val biasMas = parseMas(valueOrDefault(source, previous, "bias_mas", defaults["bias_mas"]), "全局均线", errors)
        val biasUpper = number(
            valueOrDefault(source, previous, "bias_upper_pct", defaults["bias_upper_pct"]),
            -90.0, 90.0, "乖离上限", errors, defaultDouble("bias_upper_pct"),
        )
        val biasLower = number(
            valueOrDefault(source, previous, "bias_lower_pct", defaults["bias_lower_pct"]),
            -90.0, 90.0, "乖离下限", errors, defaultDouble("bias_lower_pct"),
        )
        if (biasUpper <= biasLower) errors += "乖离上限必须大于下限"

        val cross = globalGroup(source, previous, "cross", "交叉", errors)
        val change = globalGroup(source, previous, "change", "涨跌幅", errors)
        val tRange = globalGroup(source, previous, "t_range", "做T", errors)
        val proxy = globalGroup(source, previous, "proxy", "代理", errors)
        val stocks = parseStocks(source["stocks"]?.asArray().orEmpty(), cross, change, tRange, proxy, errors)

        val config = linkedMapOf<String, Any?>(
            "poll_seconds" to intValue(
                valueOrDefault(source, previous, "poll_seconds", defaults["poll_seconds"]),
                5, 3600, "轮询间隔", errors, defaultInt("poll_seconds"),
            ),
            "sessions" to sessions,
            "kline_bars" to intValue(
                valueOrDefault(source, previous, "kline_bars", defaults["kline_bars"]),
                60, 500, "K线根数", errors, defaultInt("kline_bars"),
            ),
            "nav_bars" to intValue(
                valueOrDefault(source, previous, "nav_bars", defaults["nav_bars"]),
                60, 500, "净值根数", errors, defaultInt("nav_bars"),
            ),
            "bias_mas" to biasMas,
            "bias_enabled" to (source["bias_enabled"]?.asBoolean()
                ?: previous["bias_enabled"]?.asBoolean()
                ?: defaults["bias_enabled"] as Boolean),
            "bias_upper_pct" to biasUpper,
            "bias_lower_pct" to biasLower,
            "bias_cooldown_minutes" to intValue(
                valueOrDefault(source, previous, "bias_cooldown_minutes", defaults["bias_cooldown_minutes"]),
                0, 1440, "乖离冷却", errors, defaultInt("bias_cooldown_minutes"),
            ),
            "min_history_bars" to intValue(
                valueOrDefault(source, previous, "min_history_bars", defaults["min_history_bars"]),
                2, 500, "最少历史根数", errors, defaultInt("min_history_bars"),
            ),
            "cross" to cross,
            "change" to change,
            "t_range" to tRange,
            "proxy" to proxy,
            "stocks" to stocks,
        )
        return PanelConfigResult(config, errors)
    }

    private fun globalGroup(
        source: Map<String, JsonElement>,
        previous: Map<String, JsonElement>,
        key: String,
        label: String,
        errors: MutableList<String>,
    ): Map<String, Any?> {
        val block = if (key in source) source[key]?.asObject()?.fields.orEmpty()
        else previous[key]?.asObject()?.fields.orEmpty()
        return mergeGroup(block, nestedDefault(key), label, errors, includeAll = true)
    }

    private fun parseStocks(
        rows: List<JsonElement>,
        cross: Map<String, Any?>,
        change: Map<String, Any?>,
        tRange: Map<String, Any?>,
        proxy: Map<String, Any?>,
        errors: MutableList<String>,
    ): List<Map<String, Any?>> {
        val stocks = mutableListOf<Map<String, Any?>>()
        rows.forEachIndexed { index, element ->
            val row = element.asObject()?.fields
            if (row == null) {
                errors += "第 ${index + 1} 行格式不对"
                return@forEachIndexed
            }
            val label = "第 ${index + 1} 行"
            val code = text(row["code"], "$label 代码", errors).lowercase()
            if (code.isEmpty()) {
                errors += "$label 没填代码"
                return@forEachIndexed
            }
            val symbol = try {
                normalizeSymbol(code)
            } catch (_: IllegalArgumentException) {
                errors += "$label 代码 $code 无法识别（6 位数字，可带 sh/sz/bj 前缀）"
                return@forEachIndexed
            }
            val rawType = (row["type"]?.asString() ?: "auto").lowercase()
            val type = if (rawType in setOf("auto", "listed", "fund")) rawType else {
                errors += "$label 类型 $rawType 不支持"
                "auto"
            }
            val item = linkedMapOf<String, Any?>("code" to code, "type" to type)
            val name = text(row["name"], "$label 名称", errors)
            if (name.isNotEmpty()) item["name"] = name.take(40)
            if (row["enabled"]?.asBoolean() == false) item["enabled"] = false
            if (type == "fund") {
                val proxyCode = text(row["proxy"], "$label 代理代码", errors).lowercase().take(12)
                if (proxyCode.isNotEmpty()) {
                    val proxySymbol = try {
                        normalizeSymbol(proxyCode)
                    } catch (_: IllegalArgumentException) {
                        errors += "$label 代理代码 $proxyCode 无法识别"
                        null
                    }
                    if (proxySymbol != null) {
                        if (proxySymbol.substring(2) == symbol.substring(2)) {
                            errors += "$label 代理代码不能是自身"
                        } else {
                            item["proxy"] = proxyCode
                        }
                    }
                }
            }
            val mas = row["bias_mas"]
            if (mas != null && mas !is JsonNull && mas.asArray().isNotEmpty()) {
                item["bias_mas"] = parseMas(mas, "$label 均线", errors)
            }
            optionalNumber(row, "bias_upper_pct", -90.0, 90.0, "$label 乖离上限", errors, item)
            optionalNumber(row, "bias_lower_pct", -90.0, 90.0, "$label 乖离下限", errors, item)
            optionalInt(row, "bias_cooldown_minutes", 0, 1440, "$label 冷却分钟", errors, item)
            optionalInt(row, "min_history_bars", 2, 500, "$label 最少根数", errors, item)
            itemGroup(row["cross"], cross, "$label 交叉", errors)?.takeIf { it.isNotEmpty() }?.let { item["cross"] = it }
            itemGroup(row["change"], change, "$label 涨跌幅", errors)?.takeIf { it.isNotEmpty() }?.let { item["change"] = it }
            itemGroup(row["t_range"], tRange, "$label 做T", errors)?.takeIf { it.isNotEmpty() }?.let { item["t_range"] = it }
            itemGroup(row["proxy_rules"], proxy, "$label 代理", errors)?.takeIf { it.isNotEmpty() }?.let { item["proxy_rules"] = it }
            stocks += item
        }
        return stocks
    }

    private fun itemGroup(
        value: JsonElement?,
        global: Map<String, Any?>,
        label: String,
        errors: MutableList<String>,
    ): Map<String, Any?> {
        val block = value?.asObject()?.fields.orEmpty()
        return if (block.isEmpty()) emptyMap() else mergeGroup(block, global, label, errors, includeAll = false)
    }

    private fun mergeGroup(
        block: Map<String, JsonElement>,
        defaults: Map<String, Any?>,
        label: String,
        errors: MutableList<String>,
        includeAll: Boolean,
    ): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        if ("enabled" in defaults && (includeAll || "enabled" in block)) {
            out["enabled"] = block["enabled"]?.asBoolean() ?: defaults["enabled"] as Boolean
        }
        if ("short" in defaults && "long" in defaults &&
            (includeAll || "short" in block || "long" in block)
        ) {
            val fallbackShort = defaultInt(defaults, "short")
            val fallbackLong = defaultInt(defaults, "long")
            var short = intValue(block["short"], 1, 250, "$label 短均线", errors, fallbackShort)
                ?: fallbackShort
            var long = intValue(block["long"], 1, 250, "$label 长均线", errors, fallbackLong)
                ?: fallbackLong
            if (short >= long) {
                errors += "$label：短均线周期必须小于长均线"
                short = fallbackShort
                long = fallbackLong
            }
            out["short"] = short
            out["long"] = long
        }
        if ("upper_pct" in defaults && "lower_pct" in defaults &&
            (includeAll || "upper_pct" in block || "lower_pct" in block)
        ) {
            val fallbackUpper = defaultDouble(defaults, "upper_pct")
            val fallbackLower = defaultDouble(defaults, "lower_pct")
            var upper = number(block["upper_pct"], -90.0, 90.0, "$label 上限", errors, fallbackUpper)
            var lower = number(block["lower_pct"], -90.0, 90.0, "$label 下限", errors, fallbackLower)
            if (upper <= lower) {
                errors += "$label：上限必须大于下限"
                upper = fallbackUpper
                lower = fallbackLower
            }
            out["upper_pct"] = upper
            out["lower_pct"] = lower
        }
        if ("cooldown_minutes" in defaults && (includeAll || "cooldown_minutes" in block)) {
            out["cooldown_minutes"] = intValue(
                block["cooldown_minutes"], 0, 1440, "$label 冷却", errors,
                defaultInt(defaults, "cooldown_minutes"),
            )
        }
        if ("bias_mas" in defaults && (includeAll || "bias_mas" in block)) {
            val mas = if (includeAll) block["bias_mas"] ?: jsonValue(defaults["bias_mas"]) else block["bias_mas"]
            out["bias_mas"] = parseMas(mas, "$label 均线", errors)
        }
        val includeBase = "base" in defaults && (includeAll || "base" in block)
        val includeDev1 = "dev1_pct" in defaults && (includeAll || "dev1_pct" in block)
        val includeDev2 = "dev2_pct" in defaults && (includeAll || "dev2_pct" in block)
        if (includeBase || includeDev1 || includeDev2) {
            val fallbackDev1 = defaultDouble(defaults, "dev1_pct")
            val fallbackDev2 = defaultDouble(defaults, "dev2_pct")
            val base = number(block["base"], 0.0, 1_000_000.0, "$label 基准价", errors, defaultDouble(defaults, "base"))
            var dev1 = number(block["dev1_pct"], 0.0, 90.0, "$label 一档偏离", errors, fallbackDev1)
            var dev2 = number(block["dev2_pct"], 0.0, 90.0, "$label 二档偏离", errors, fallbackDev2)
            if (dev1 <= 0.0) {
                errors += "$label：一档偏离要大于 0"
                dev1 = fallbackDev1
            }
            if (dev2 > 0.0 && dev2 <= dev1) {
                errors += "$label：二档偏离必须大于一档偏离（不用二档就填 0）"
                dev2 = fallbackDev2
            }
            if (includeBase) out["base"] = base
            if (includeDev1) out["dev1_pct"] = dev1
            if (includeDev2) out["dev2_pct"] = dev2
        }
        return out
    }

    private fun parseSessions(value: JsonElement?, errors: MutableList<String>): List<List<String>> {
        val rows = value?.asArray().orEmpty()
        if (rows.isEmpty()) return defaultSessions()
        val sessions = mutableListOf<List<String>>()
        rows.forEach { element ->
            val pair = element.asArray()
            val start = pair.getOrNull(0)?.asString().orEmpty()
            val end = pair.getOrNull(1)?.asString().orEmpty()
            val valid = pair.size == 2 && timePattern.matches(start) && timePattern.matches(end) && try {
                LocalTime.parse(start) <= LocalTime.parse(end)
            } catch (_: DateTimeParseException) {
                false
            }
            if (!valid) {
                errors += "交易时段 [$start, $end] 不合法"
            } else {
                sessions += listOf(start, end)
            }
        }
        return sessions.ifEmpty { defaultSessions() }
    }

    private fun parseMas(value: JsonElement?, label: String, errors: MutableList<String>): List<Int> {
        val rows = value?.asArray().orEmpty()
        if (rows.isEmpty()) {
            errors += "$label 至少填一个周期"
            return defaultMas()
        }
        val values = rows.mapNotNull { intValue(it, 1, 250, "$label 的周期", errors, null) }
        return values.ifEmpty { defaultMas() }
    }

    private fun optionalNumber(
        row: Map<String, JsonElement>,
        key: String,
        lo: Double,
        hi: Double,
        label: String,
        errors: MutableList<String>,
        out: MutableMap<String, Any?>,
    ) {
        val value = row[key]
        if (isMissing(value)) return
        out[key] = number(value, lo, hi, label, errors, 0.0)
    }

    private fun optionalInt(
        row: Map<String, JsonElement>,
        key: String,
        lo: Int,
        hi: Int,
        label: String,
        errors: MutableList<String>,
        out: MutableMap<String, Any?>,
    ) {
        val value = row[key]
        if (isMissing(value)) return
        out[key] = intValue(value, lo, hi, label, errors, 0)
    }

    private fun number(
        value: JsonElement?,
        lo: Double,
        hi: Double,
        label: String,
        errors: MutableList<String>,
        default: Double,
    ): Double {
        if (isMissing(value)) return default
        val parsed = value.asDouble() ?: value.asString()?.toDoubleOrNull()
        if (parsed == null || !parsed.isFinite()) {
            errors += "$label 不是数字"
            return default
        }
        if (parsed !in lo..hi) {
            errors += "$label 需在 $lo～$hi 之间"
            return default
        }
        return parsed
    }

    private fun intValue(
        value: JsonElement?,
        lo: Int,
        hi: Int,
        label: String,
        errors: MutableList<String>,
        default: Int?,
    ): Int? {
        if (isMissing(value)) return default
        val parsed = value.asDouble() ?: value.asString()?.toDoubleOrNull()
        if (parsed == null || !parsed.isFinite()) {
            errors += "$label 不是数字"
            return default
        }
        if (parsed !in lo.toDouble()..hi.toDouble()) {
            errors += "$label 需在 $lo～$hi 之间"
            return default
        }
        if (parsed % 1.0 != 0.0) {
            errors += "$label 必须是整数"
            return default
        }
        return parsed.toInt()
    }

    private fun isMissing(value: JsonElement?): Boolean = when (value) {
        null, is JsonNull -> true
        is JsonString -> value.value.isBlank()
        else -> false
    }

    private fun text(value: JsonElement?, label: String, errors: MutableList<String>): String = when (value) {
        null, is JsonNull -> ""
        is JsonString -> value.value.trim()
        else -> {
            errors += "$label 必须是文本"
            ""
        }
    }

    private fun valueOrDefault(
        source: Map<String, JsonElement>,
        previous: Map<String, JsonElement>,
        key: String,
        default: Any?,
    ): JsonElement? = source[key] ?: previous[key] ?: jsonValue(default)

    private fun jsonValue(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonBoolean(value)
        is Number -> JsonNumber(value.toDouble())
        is String -> JsonString(value)
        is Iterable<*> -> JsonArray(value.map(::jsonValue))
        else -> JsonNull
    }

    private fun nestedDefault(key: String): Map<String, Any?> = defaults[key] as? Map<String, Any?> ?: emptyMap()
    private fun defaultSessions(): List<List<String>> = defaults["sessions"] as? List<List<String>> ?: emptyList()
    private fun defaultMas(): List<Int> = defaults["bias_mas"] as? List<Int> ?: emptyList()
    private fun defaultInt(key: String): Int = defaultInt(defaults, key)
    private fun defaultDouble(key: String): Double = defaultDouble(defaults, key)
    private fun defaultInt(values: Map<String, Any?>, key: String): Int = (values[key] as? Number)?.toInt() ?: 0
    private fun defaultDouble(values: Map<String, Any?>, key: String): Double = (values[key] as? Number)?.toDouble() ?: 0.0
}
