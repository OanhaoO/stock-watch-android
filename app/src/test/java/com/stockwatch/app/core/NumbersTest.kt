package com.stockwatch.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumbersTest {

    @Test
    fun fmtMatchesPcVersion() {
        assertEquals("-", fmt(null))
        assertEquals("4.175", fmt(4.1749))
        assertEquals("12.35", fmt(12.3456))
        assertEquals("4.17", fmt(4.1749, 2))
    }

    @Test
    fun priceRejectsNonPositive() {
        assertEquals(4.11, price("4.110")!!, 1e-9)
        assertNull(price("0"))
        assertNull(price(-1.0))
        assertNull(price(""))
        assertNull(price("abc"))
    }
}
