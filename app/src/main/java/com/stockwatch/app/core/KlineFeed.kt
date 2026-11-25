package com.stockwatch.app.core

import java.io.File

val KLINE_URLS = listOf(
    "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param={sym},day,,,{count},qfq",
    "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/fqkline/get?param={sym},day,,,{count},qfq",
)

data class KlineRecord(val at: Long, val rows: List<PriceBar>)

fun parseDailyCloses(raw: String, symbol: String): List<PriceBar> {
    val root = parseJson(raw)
    val node = root["data"]?.asObject()?.get(symbol)?.asObject() ?: return emptyList()
    val rows = node["qfqday"]?.asArray().orEmpty().ifEmpty { node["day"]?.asArray().orEmpty() }
    val out = mutableListOf<PriceBar>()
    for (element in rows) {
        val row = element.asArray()
        val date = row.getOrNull(0)?.asString()?.replace("-", "") ?: continue
        val close = row.getOrNull(2)?.asDouble() ?: continue
        out += PriceBar(date, close)
    }
    return out
}

fun fetchDailyCloses(symbol: String, count: Int): List<PriceBar> {
    var lastError: Exception? = null
    for (template in KLINE_URLS) {
        try {
            val url = template.replace("{sym}", symbol).replace("{count}", count.toString())
            val rows = parseDailyCloses(Http.getText(url), symbol)
            if (rows.isNotEmpty()) return rows
            lastError = RuntimeException("接口没返回日线数据")
        } catch (exc: Exception) {
            lastError = exc
        }
    }
    throw lastError ?: RuntimeException("日线取数失败")
}

class KlineFeed(
    private val cacheFile: File? = null,
    private val ttlSeconds: Long = 1_800,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val fetcher: (String, Int) -> List<PriceBar> = ::fetchDailyCloses,
) {
    private val mem = mutableMapOf<String, KlineRecord>()

    init {
        loadCache()
    }

    @Synchronized
    fun closes(symbol: String, count: Int): List<PriceBar> {
        if (count <= 0) return emptyList()
        val record = mem[symbol]
        val rows = record?.rows.orEmpty().takeLast(count)
        val fresh = record != null &&
            nowMillis() - record.at < ttlSeconds * 1_000 &&
            rows.size >= minOf(count, 60)
        if (fresh) return rows
        return try {
            val freshRows = fetcher(symbol, count)
            if (freshRows.isEmpty()) {
                if (rows.isNotEmpty()) rows else throw RuntimeException("接口没返回日线数据")
            } else {
                mem[symbol] = KlineRecord(nowMillis(), freshRows)
                saveCache()
                freshRows
            }
        } catch (exc: Exception) {
            if (rows.isNotEmpty()) rows else throw RuntimeException("${exc.javaClass.simpleName}: ${exc.message}")
        }
    }

    private fun loadCache() {
        val file = cacheFile ?: return
        if (!file.exists()) return
        try {
            val root = parseJson(file.readText()).asObject() ?: return
            for ((symbol, element) in root.fields) {
                val obj = element.asObject() ?: continue
                val at = obj["at"]?.asLong() ?: continue
                val rows = obj["rows"]?.asArray().orEmpty().mapNotNull { rowElement ->
                    val row = rowElement.asArray()
                    val date = row.getOrNull(0)?.asString() ?: return@mapNotNull null
                    val close = row.getOrNull(1)?.asDouble() ?: return@mapNotNull null
                    PriceBar(date, close)
                }
                mem[symbol] = KlineRecord(at, rows)
            }
        } catch (_: Exception) {
            mem.clear()
        }
    }

    private fun saveCache() {
        val file = cacheFile ?: return
        val json = mem.mapValues { (_, record) ->
            mapOf(
                "at" to record.at,
                "rows" to record.rows.map { listOf(it.date, it.close) },
            )
        }
        file.parentFile?.mkdirs()
        file.writeText(stringifyJson(json))
    }
}
