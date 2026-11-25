package com.stockwatch.app.core

import java.io.File
import java.net.URLEncoder
import kotlin.math.round

const val FUND_BASIC = "https://fundmobapi.eastmoney.com/FundMNewApi/FundMNBasicInformation" +
    "?FCODE={code}&deviceid=1&plat=Iphone&product=EFund&version=6.2.8"
const val FUND_NAV = "https://api.fund.eastmoney.com/f10/lsjz?fundCode={code}&pageIndex={page}&pageSize={size}"
const val FUND_SEARCH = "https://fundsuggest.eastmoney.com/FundSearch/api/FundSearchAPI.ashx?m=1&key={key}"

data class FundInfo(
    val name: String,
    val ftype: String,
    val navDate: String,
    val nav: Double?,
    val pct: Double?,
    val at: Long = 0L,
)

data class FundSearchResult(val code: String, val name: String, val py: String)

data class FundSnapshot(
    val name: String,
    val ftype: String,
    val code: String,
    val price: Double,
    val prevClose: Double?,
    val changePct: Double,
    val date: String,
    val timeDisplay: String,
    val series: List<PriceBar>,
    val raw: List<PriceBar>,
    val navUpdated: String,
)

private data class FundHistoryRecord(val adj: List<PriceBar>, val raw: List<PriceBar>)

fun positiveDouble(value: JsonElement?): Double? =
    value?.asDouble()?.takeIf { it > 0.0 }

internal fun parseFundInfo(raw: String): FundInfo? {
    val row = parseJson(raw)["Datas"]?.asObject() ?: return null
    val name = row["SHORTNAME"]?.asString()?.takeIf { it.isNotEmpty() } ?: return null
    return FundInfo(
        name = name,
        ftype = row["FTYPE"]?.asString().orEmpty(),
        navDate = row["FSRQ"]?.asString().orEmpty().replace("-", ""),
        nav = positiveDouble(row["DWJZ"]),
        pct = row["RZDF"]?.asDouble(),
    )
}

fun fundName(code: String): FundInfo? = try {
    parseFundInfo(Http.getText(FUND_BASIC.replace("{code}", code)))
} catch (_: Exception) {
    null
}

fun searchCode(keyword: String): List<FundSearchResult> {
    val encoded = URLEncoder.encode(keyword, "UTF-8")
    return try {
        val rows = parseJson(Http.getText(FUND_SEARCH.replace("{key}", encoded)))["Datas"]?.asArray().orEmpty()
        rows.mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            if (row["TYPE"]?.asString() == "fp") return@mapNotNull null
            val code = row["CODE"]?.asString().orEmpty().filter { it.isDigit() }.take(6)
            if (code.isEmpty()) return@mapNotNull null
            FundSearchResult(
                code = code,
                name = Regex("<[^>]+>").replace(row["NAME"]?.asString().orEmpty(), ""),
                py = row["FundType"]?.asString().orEmpty(),
            )
        }.take(10)
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun rebuildFundSeries(rows: List<JsonObject>): Pair<List<PriceBar>, List<PriceBar>> {
    val adjusted = mutableListOf<PriceBar>()
    val raw = mutableListOf<PriceBar>()
    var previousNav: Double? = null
    var factor = 1.0
    for (row in rows.reversed()) {
        val date = row["FSRQ"]?.asString()?.replace("-", "") ?: continue
        val nav = positiveDouble(row["DWJZ"]) ?: continue
        val pct = row["JZZZL"]?.asDouble()
        if (previousNav != null) {
            factor *= if (pct != null) 1.0 + pct / 100.0 else nav / previousNav
        }
        adjusted += PriceBar(date, factor)
        raw += PriceBar(date, nav)
        previousNav = nav
    }
    if (adjusted.isEmpty() || raw.isEmpty()) return emptyList<PriceBar>() to emptyList()
    val scale = raw.last().close / adjusted.last().close
    return adjusted.map { PriceBar(it.date, (it.close * scale).round(6)) } to raw
}

fun fundNavHistory(code: String, bars: Int): Pair<List<PriceBar>, List<PriceBar>> {
    val rows = mutableListOf<JsonObject>()
    var page = 1
    while (rows.size < bars && page <= 12) {
        val url = FUND_NAV.replace("{code}", code)
            .replace("{page}", page.toString())
            .replace("{size}", "20")
        try {
            val batch = parseJson(
                Http.getText(url, headers = mapOf("Referer" to "http://fundf10.eastmoney.com/")),
            )["Data"]?.asObject()?.get("LSJZList")?.asArray().orEmpty()
            if (batch.isEmpty()) break
            batch.mapNotNullTo(rows) { it.asObject() }
            page++
        } catch (_: Exception) {
            break
        }
    }
    return rebuildFundSeries(rows.take(bars))
}

class FundFeed(
    private val cacheFile: File? = null,
    private val ttlSeconds: Long = 180,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val infoFetcher: (String) -> FundInfo? = ::fundName,
    private val historyFetcher: (String, Int) -> Pair<List<PriceBar>, List<PriceBar>> = ::fundNavHistory,
) {
    private val meta = mutableMapOf<String, FundInfo>()
    private val history = mutableMapOf<String, FundHistoryRecord>()

    init {
        loadCache()
    }

    fun snapshot(code: String, bars: Int = 130, force: Boolean = false): FundSnapshot? {
        val stamp = nowMillis()
        var info = meta[code]
        val fresh = info != null && stamp - info.at < ttlSeconds * 1_000
        if (!fresh || force) {
            val fetched = infoFetcher(code) ?: return null
            info = fetched.copy(at = stamp)
            meta[code] = info
        }
        var entry = history[code]
        if (force || entry == null || entry.adj.lastOrNull()?.date != info.navDate) {
            val (adj, raw) = historyFetcher(code, bars)
            if (adj.isNotEmpty()) {
                entry = FundHistoryRecord(adj, raw)
                history[code] = entry
            }
        }
        persist()
        entry ?: return null
        val safeEntry = entry
        val price = safeEntry.raw.last().close
        val prevClose = safeEntry.raw.getOrNull(safeEntry.raw.lastIndex - 1)?.close
        val changePct = info.pct ?: if (prevClose != null && prevClose != 0.0) {
            (price / prevClose - 1.0) * 100.0
        } else {
            0.0
        }
        val date = info.navDate
        return FundSnapshot(
            name = info.name,
            ftype = info.ftype,
            code = code,
            price = price,
            prevClose = prevClose,
            changePct = changePct,
            date = date,
            timeDisplay = formatNavDate(date),
            series = safeEntry.adj,
            raw = safeEntry.raw,
            navUpdated = date,
        )
    }

    private fun formatNavDate(date: String): String =
        if (date.length == 8) {
            "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)}"
        } else {
            date
        }

    private fun loadCache() {
        val file = cacheFile ?: return
        if (!file.exists()) return
        try {
            val root = parseJson(file.readText()).asObject() ?: return
            root["meta"]?.asObject()?.fields?.forEach { (code, element) ->
                val row = element.asObject() ?: return@forEach
                val name = row["name"]?.asString() ?: return@forEach
                val navDate = row["nav_date"]?.asString().orEmpty()
                meta[code] = FundInfo(
                    name = name,
                    ftype = row["ftype"]?.asString().orEmpty(),
                    navDate = navDate,
                    nav = row["nav"]?.asDouble(),
                    pct = row["pct"]?.asDouble(),
                    at = row["at"]?.asLong() ?: 0L,
                )
            }
            root["history"]?.asObject()?.fields?.forEach { (code, element) ->
                val row = element.asObject() ?: return@forEach
                val adj = parseBars(row["adj"])
                val raw = parseBars(row["raw"])
                if (adj.isNotEmpty() && raw.isNotEmpty()) history[code] = FundHistoryRecord(adj, raw)
            }
        } catch (_: Exception) {
            meta.clear()
            history.clear()
        }
    }

    private fun parseBars(element: JsonElement?): List<PriceBar> =
        element?.asArray().orEmpty().mapNotNull { item ->
            val row = item.asArray()
            val date = row.getOrNull(0)?.asString() ?: return@mapNotNull null
            val value = row.getOrNull(1)?.asDouble() ?: return@mapNotNull null
            PriceBar(date, value)
        }

    private fun persist() {
        val file = cacheFile ?: return
        val metaJson = meta.mapValues { (_, info) ->
            mapOf(
                "name" to info.name,
                "ftype" to info.ftype,
                "nav_date" to info.navDate,
                "nav" to info.nav,
                "pct" to info.pct,
                "at" to info.at,
            )
        }
        val historyJson = history.mapValues { (_, entry) ->
            mapOf(
                "adj" to entry.adj.map { listOf(it.date, it.close) },
                "raw" to entry.raw.map { listOf(it.date, it.close) },
            )
        }
        file.parentFile?.mkdirs()
        file.writeText(stringifyJson(mapOf("meta" to metaJson, "history" to historyJson)))
    }
}
