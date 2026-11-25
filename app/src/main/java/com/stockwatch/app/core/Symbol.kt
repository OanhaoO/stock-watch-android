package com.stockwatch.app.core

const val LISTED = "listed"
const val FUND = "fund"

private val SYMBOL_RE = Regex("(sh|sz|bj)?(\\d{6})")
private val PREFIX_RE = Regex("(sh|sz|bj)\\d{6}")

fun normalizeSymbol(code: String): String {
    val normalized = code.trim().lowercase()
    val match = SYMBOL_RE.matchEntire(normalized) ?: throw IllegalArgumentException("无法识别的代码: $code")
    val prefix = match.groupValues[1]
    val number = match.groupValues[2]
    if (prefix.isNotEmpty()) return prefix + number
    val market = when (number[0]) {
        in "569" -> "sh"
        in "0123" -> "sz"
        else -> "bj"
    }
    return market + number
}

fun hasPrefix(code: String): Boolean = PREFIX_RE.matches(code.trim().lowercase())

fun listedKind(symbol: String): String {
    val number = symbol.substring(2)
    return when {
        number[0] in "51" || symbol.startsWith("sh5") || symbol.startsWith("sz1") -> "场内基金"
        number[0] == '6' -> "沪市股票"
        number[0] in "03" -> "深市股票"
        number[0] in "48" -> "北交所"
        else -> "场内"
    }
}
