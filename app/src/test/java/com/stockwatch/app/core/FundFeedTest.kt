package com.stockwatch.app.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FundFeedTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun parsesBasicInfo() {
        val raw = """{"Datas":{"SHORTNAME":"测试基金","FTYPE":"混合型","FSRQ":"2026-11-24","DWJZ":"1.2345","RZDF":"1.25"}}"""
        val info = parseFundInfo(raw)
        assertNotNull(info)
        assertEquals("测试基金", info!!.name)
        assertEquals("20261124", info.navDate)
        assertEquals(1.2345, info.nav!!, 1e-9)
        assertEquals(1.25, info.pct!!, 1e-9)
    }

    @Test
    fun rebuildsAdjustedSeriesFromDailyGrowth() {
        val rows = listOf(
            parseJson("""{"FSRQ":"2026-11-20","DWJZ":"1.0","JZZZL":"10"}""").asObject()!!,
            parseJson("""{"FSRQ":"2026-11-19","DWJZ":"1.0","JZZZL":"0"}""").asObject()!!,
        )
        val (adj, raw) = rebuildFundSeries(rows)
        assertEquals(listOf(PriceBar("20261119", 1.0), PriceBar("20261120", 1.0)), raw)
        assertEquals(1.0, adj.last().close, 1e-9)
        assertEquals(0.909091, adj.first().close, 1e-6)
    }

    @Test
    fun snapshotUsesCachedInfoAndPersistsHistory() {
        val file = File(temp.root, "fund.json")
        var infoCalls = 0
        var historyCalls = 0
        val info = FundInfo("测试基金", "混合型", "20261124", 1.2, 2.0)
        val adj = listOf(PriceBar("20261123", 1.0), PriceBar("20261124", 1.2))
        val raw = listOf(PriceBar("20261123", 1.0), PriceBar("20261124", 1.2))
        val feed = FundFeed(file, nowMillis = { 5_000L }, infoFetcher = {
            infoCalls++
            info
        }, historyFetcher = { _, _ ->
            historyCalls++
            adj to raw
        })
        val first = feed.snapshot("000001", 130)!!
        assertEquals(1.2, first.price, 1e-9)
        assertEquals(1.0, first.prevClose!!, 1e-9)
        assertEquals(2.0, first.changePct, 1e-9)
        val second = feed.snapshot("000001", 130)!!
        assertEquals(first.price, second.price, 1e-9)
        assertEquals(1, infoCalls)
        assertEquals(1, historyCalls)
    }
}
