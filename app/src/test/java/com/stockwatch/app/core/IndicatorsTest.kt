package com.stockwatch.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndicatorsTest {
    @Test
    fun smaMatchesPcVersion() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(4.0, sma(values, 3)!!, 1e-9)
        assertEquals(2.0, sma(values, 3, 1)!!, 1e-9)
        assertNull(sma(values, 6))
        assertNull(sma(values, 0))
    }

    @Test
    fun buildSeriesOverridesSameDayAndAppendsNewDay() {
        val history = listOf(PriceBar("20261120", 10.0), PriceBar("20261121", 11.0))
        assertEquals(listOf(10.0, 12.0), buildSeries(history, "20261121", 12.0))
        assertEquals(listOf(10.0, 11.0, 12.0), buildSeries(history, "20261122", 12.0))
    }

    @Test
    fun detectsGoldAndDeathCrosses() {
        val gold = detectCross(listOf(10.0, 9.0, 8.0, 7.0, 20.0), 2, 3)
        assertEquals(Cross.GOLD, gold?.kind)
        val death = detectCross(listOf(7.0, 8.0, 9.0, 10.0, 1.0), 2, 3)
        assertEquals(Cross.DEATH, death?.kind)
        assertNull(detectCross(listOf(1.0, 2.0, 3.0), 2, 3))
    }
}
