package com.stockwatch.app.core

import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object MarketTime {
    val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
    private val DAY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE

    fun now(clock: Clock = Clock.systemUTC()): LocalDateTime = LocalDateTime.now(clock.withZone(SHANGHAI))

    fun day(value: LocalDateTime): String = value.format(DAY_FORMAT)
}

fun inSession(now: LocalDateTime, sessions: List<TradingSession>): Boolean {
    if (now.dayOfWeek.value >= 6) return false
    val time = now.toLocalTime()
    return sessions.any { !time.isBefore(it.start) && !time.isAfter(it.end) }
}

fun secondsToNextSession(sessions: List<TradingSession>, now: LocalDateTime): Long {
    if (inSession(now, sessions)) return 0L
    var best: Long? = null
    for (offset in 0L..7L) {
        val day = now.toLocalDate().plusDays(offset)
        if (day.dayOfWeek.value >= 6) continue
        for (session in sessions) {
            val candidate = day.atTime(session.start)
            val delta = Duration.between(now, candidate).seconds
            if (delta > 0L && (best == null || delta < best)) best = delta
        }
    }
    return best?.coerceAtMost(6L * 3_600L) ?: 3_600L
}
