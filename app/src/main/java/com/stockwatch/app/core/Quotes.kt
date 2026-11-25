package com.stockwatch.app.core

import java.nio.charset.Charset

const val QT_URL = "https://qt.gtimg.cn/q={codes}"

data class Quote(
    val name: String,
    val code: String,
    val price: Double,
    val prevClose: Double,
    val changePct: Double,
    val high: Double,
    val low: Double,
    val ts: String,
    val date: String,
    val timeDisplay: String,
)

private val QUOTE_RE = Regex("v_(\\w+)=\"([^\"]*)\"")

fun parseQuotes(raw: String): Map<String, Quote> {
    val quotes = linkedMapOf<String, Quote>()
    for (match in QUOTE_RE.findAll(raw)) {
        val fields = match.groupValues[2].split('~')
        if (fields.size < 35) continue
        val price = fields[3].toDoubleOrNull() ?: continue
        val prevClose = fields[4].toDoubleOrNull() ?: continue
        if (price <= 0.0 || prevClose <= 0.0) continue
        val ts = fields[30]
        val date = if (ts.length >= 8) ts.substring(0, 8) else ""
        val timeDisplay = if (ts.length >= 14) {
            "${ts.substring(0, 4)}-${ts.substring(4, 6)}-${ts.substring(6, 8)} " +
                "${ts.substring(8, 10)}:${ts.substring(10, 12)}:${ts.substring(12, 14)}"
        } else {
            ts
        }
        quotes[match.groupValues[1]] = Quote(
            name = fields[1],
            code = fields[2],
            price = price,
            prevClose = prevClose,
            changePct = (price / prevClose - 1.0) * 100.0,
            high = fields[33].toDoubleOrNull() ?: 0.0,
            low = fields[34].toDoubleOrNull() ?: 0.0,
            ts = ts,
            date = date,
            timeDisplay = timeDisplay,
        )
    }
    return quotes
}

fun fetchQuotes(symbols: Collection<String>): Map<String, Quote> {
    if (symbols.isEmpty()) return emptyMap()
    val codes = symbols.sorted().joinToString(",")
    val raw = Http.getText(QT_URL.replace("{codes}", codes), charset = Charset.forName("GBK"))
    return parseQuotes(raw)
}
