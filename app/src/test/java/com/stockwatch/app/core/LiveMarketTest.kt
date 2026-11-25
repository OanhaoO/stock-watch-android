package com.stockwatch.app.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class LiveMarketTest {
    @Test
    fun realTencentQuoteAndKlineParse() {
        try {
            val quote = fetchQuotes(listOf("sh000001"))["sh000001"]
            assertNotNull(quote)
            assertTrue(quote!!.price > 0.0)
            val rows = fetchDailyCloses("sh000001", 80)
            assertTrue(rows.size >= 60)
            assertTrue(rows.all { it.close > 0.0 })
        } catch (exc: Exception) {
            assumeNoException("实时行情接口暂时不可达，跳过在线解析测试", exc)
        }
    }

    @Test
    fun realEastmoneyFundParse() {
        try {
            val info = fundName("000001")
            assertNotNull(info)
            assertTrue(info!!.navDate.length == 8)
            val (adj, raw) = fundNavHistory("000001", 80)
            assertTrue(adj.size >= 60)
            assertTrue(raw.size == adj.size)
            assertTrue(adj.last().close > 0.0)
        } catch (exc: Exception) {
            assumeNoException("天天基金接口暂时不可达，跳过在线解析测试", exc)
        }
    }
}
