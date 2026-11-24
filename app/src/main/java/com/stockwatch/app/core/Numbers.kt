package com.stockwatch.app.core

import java.util.Locale

/** 价格显示：None 给占位符，缺小数位时按量级自动取 3/2 位，跟 PC 版 watcher.fmt 一致。 */
fun fmt(v: Double?, digits: Int? = null): String {
    if (v == null) return "-"
    val d = digits ?: if (Math.abs(v) < 10.0) 3 else 2
    return String.format(Locale.ROOT, "%.${d}f", v)
}

fun toDoubleOrNull(raw: String?): Double? =
    raw?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

/** 只有正数才算有效价格，0 和负数一律当「没填」。 */
fun price(v: Double?): Double? = if (v != null && v > 0.0) v else null

fun price(raw: String?): Double? = price(toDoubleOrNull(raw))
