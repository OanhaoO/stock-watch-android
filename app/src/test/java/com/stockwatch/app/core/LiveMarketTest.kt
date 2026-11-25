package com.stockwatch.app.core

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveMarketTest {
    @Test
    fun realTencentQuoteAndKlineParse() {
        val quote = try {
            fetchQuotes(listOf("sh000001"))["sh000001"]
        } catch (exc: Exception) {
            assumeNoException("实时行情接口暂时不可达，跳过在线解析测试", exc)
            return
        }
        assumeTrue("实时行情接口未返回标的，跳过在线解析测试", quote != null)
        val safeQuote = quote!!
        assertTrue(safeQuote.price > 0.0)
        val rows = try {
            fetchDailyCloses("sh000001", 80)
        } catch (exc: Exception) {
            assumeNoException("实时日线接口暂时不可达，跳过在线解析测试", exc)
            return
        }
        assumeTrue("实时日线数据不足 60 根，跳过在线解析测试", rows.size >= 60)
        assertTrue(rows.all { it.close > 0.0 })
    }

    @Test
    fun realEastmoneyFundParse() {
        val info = try {
            fundName("000001")
        } catch (exc: Exception) {
            assumeNoException("天天基金接口暂时不可达，跳过在线解析测试", exc)
            return
        }
        assumeTrue("天天基金接口未返回标的，跳过在线解析测试", info != null)
        val safeInfo = info!!
        assertTrue(safeInfo.navDate.length == 8)
        val (adj, raw) = try {
            fundNavHistory("000001", 80)
        } catch (exc: Exception) {
            assumeNoException("天天基金历史净值接口暂时不可达，跳过在线解析测试", exc)
            return
        }
        assumeTrue("天天基金历史净值不足 60 根，跳过在线解析测试", adj.size >= 60)
        assertTrue(raw.size == adj.size)
        assertTrue(adj.last().close > 0.0)
    }
}
