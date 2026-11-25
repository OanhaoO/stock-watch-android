package com.stockwatch.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolTest {
    @Test
    fun normalizesMarketPrefixes() {
        assertEquals("sh600000", normalizeSymbol("600000"))
        assertEquals("sh510300", normalizeSymbol("510300"))
        assertEquals("sz000001", normalizeSymbol("000001"))
        assertEquals("sz160119", normalizeSymbol("160119"))
        assertEquals("bj920001", normalizeSymbol("920001"))
        assertEquals("sh600000", normalizeSymbol(" SH600000 "))
    }

    @Test
    fun validatesPrefixesAndKinds() {
        assertTrue(hasPrefix("sh600000"))
        assertFalse(hasPrefix("600000"))
        assertEquals("沪市股票", listedKind("sh600000"))
        assertEquals("场内基金", listedKind("sh510300"))
        assertEquals("深市股票", listedKind("sz000001"))
        assertEquals("北交所", listedKind("bj920001"))
    }
}
