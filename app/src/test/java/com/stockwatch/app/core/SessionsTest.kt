package com.stockwatch.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class SessionsTest {
    private val sessions = listOf(TradingSession("09:30", "11:30"), TradingSession("13:00", "15:00"))

    @Test
    fun tradingSessionBoundariesAreInclusiveOnWeekdays() {
        assertTrue(inSession(at("2026-11-25T09:30:00"), sessions))
        assertTrue(inSession(at("2026-11-25T11:30:00"), sessions))
        assertFalse(inSession(at("2026-11-25T11:30:01"), sessions))
        assertTrue(inSession(at("2026-11-25T13:00:00"), sessions))
        assertTrue(inSession(at("2026-11-25T15:00:00"), sessions))
        assertFalse(inSession(at("2026-11-25T15:00:01"), sessions))
        assertFalse(inSession(at("2026-11-25T09:29:59"), sessions))
        assertFalse(inSession(at("2026-11-28T10:00:00"), sessions))
    }

    @Test
    fun sleepUsesNextSessionAndCapsLongWaitsAtSixHours() {
        assertEquals(0L, secondsToNextSession(sessions, at("2026-11-25T10:00:00")))
        assertEquals(5_399L, secondsToNextSession(sessions, at("2026-11-25T11:30:01")))
        assertEquals(5_400L, secondsToNextSession(sessions, at("2026-11-25T08:00:00")))
        assertEquals(21_600L, secondsToNextSession(sessions, at("2026-11-25T15:00:01")))
        assertEquals(21_600L, secondsToNextSession(sessions, at("2026-11-27T15:00:01")))
        assertEquals(21_600L, secondsToNextSession(sessions, at("2026-11-28T08:00:00")))
        assertEquals(3_600L, secondsToNextSession(emptyList(), at("2026-11-25T12:00:00")))
    }

    @Test
    fun shanghaiClockDoesNotDependOnDeviceTimeZone() {
        val clock = Clock.fixed(Instant.parse("2026-11-24T16:30:00Z"), ZoneOffset.UTC)
        val now = MarketTime.now(clock)
        assertEquals(LocalDateTime.of(2026, 11, 25, 0, 30), now)
        assertEquals("20261125", MarketTime.day(now))
    }

    private fun at(value: String): LocalDateTime = LocalDateTime.parse(value)
}
