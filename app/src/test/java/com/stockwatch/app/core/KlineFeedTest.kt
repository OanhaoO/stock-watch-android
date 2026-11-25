package com.stockwatch.app.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KlineFeedTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun parsesFrontAdjustedRows() {
        val raw = """{"data":{"sh000001":{"qfqday":[["2026-11-20",1,10.0],["2026-11-21",2,11.0]]}}}"""
        val rows = parseDailyCloses(raw, "sh000001")
        assertEquals(listOf(PriceBar("20261120", 10.0), PriceBar("20261121", 11.0)), rows)
    }

    @Test
    fun freshCacheAvoidsFetchAndStaleCacheSurvivesFailure() {
        val file = File(temp.root, "kline.json")
        var now = 1_000L
        var calls = 0
        val rows = listOf(PriceBar("20261120", 10.0), PriceBar("20261121", 11.0))
        val feed = KlineFeed(file, ttlSeconds = 60, nowMillis = { now }) { _, _ ->
            calls++
            rows
        }
        assertEquals(rows, feed.closes("sh000001", 2))
        assertEquals(rows, feed.closes("sh000001", 2))
        assertEquals(1, calls)

        now += 61_000
        val failing = KlineFeed(file, ttlSeconds = 60, nowMillis = { now }) { _, _ ->
            throw RuntimeException("WAF 501")
        }
        assertEquals(rows, failing.closes("sh000001", 2))
        assertTrue(file.exists())
    }
}
