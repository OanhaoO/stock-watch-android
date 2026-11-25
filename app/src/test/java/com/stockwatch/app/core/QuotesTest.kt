package com.stockwatch.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class QuotesTest {
    @Test
    fun parsesTencentQuoteFields() {
        val fields = MutableList(35) { "" }
        fields[1] = "平安银行"
        fields[2] = "000001"
        fields[3] = "10.25"
        fields[4] = "10.00"
        fields[30] = "20261124150000"
        fields[33] = "10.50"
        fields[34] = "9.98"
        val raw = "v_sz000001=\"${fields.joinToString("~")}\";"
        val quote = parseQuotes(raw).getValue("sz000001")
        assertEquals("平安银行", quote.name)
        assertEquals(10.25, quote.price, 1e-9)
        assertEquals(2.5, quote.changePct, 1e-9)
        assertEquals("20261124", quote.date)
        assertEquals("2026-11-24 15:00:00", quote.timeDisplay)
        assertEquals(10.50, quote.high, 1e-9)
        assertEquals(9.98, quote.low, 1e-9)
    }
}
