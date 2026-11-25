package com.stockwatch.app.core

data class PriceBar(val date: String, val close: Double)

data class Cross(val kind: String, val shortMa: Double, val longMa: Double) {
    companion object {
        const val GOLD = "gold"
        const val DEATH = "death"
    }
}

fun sma(values: List<Double>, n: Int, offset: Int = 0): Double? {
    val end = values.size - offset
    if (n <= 0 || offset < 0 || end - n < 0) return null
    var sum = 0.0
    for (i in end - n until end) sum += values[i]
    return sum / n
}

fun buildSeries(history: List<PriceBar>, quoteDate: String?, price: Double): List<Double> {
    if (history.isEmpty()) return emptyList()
    val closes = history.map { it.close }.toMutableList()
    if (!quoteDate.isNullOrEmpty() && history.last().date == quoteDate) {
        closes[closes.lastIndex] = price
    } else {
        closes += price
    }
    return closes
}

fun detectCross(closes: List<Double>, short: Int, long: Int): Cross? {
    val shortNow = sma(closes, short, 0) ?: return null
    val longNow = sma(closes, long, 0) ?: return null
    val shortBefore = sma(closes, short, 1) ?: return null
    val longBefore = sma(closes, long, 1) ?: return null
    if (shortBefore <= longBefore && shortNow > longNow) {
        return Cross(Cross.GOLD, shortNow, longNow)
    }
    if (shortBefore >= longBefore && shortNow < longNow) {
        return Cross(Cross.DEATH, shortNow, longNow)
    }
    return null
}
