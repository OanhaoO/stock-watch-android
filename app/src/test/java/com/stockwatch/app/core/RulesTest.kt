package com.stockwatch.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RulesTest {
    private val item = WatchItem(code = "000001", symbol = "sz000001")
    private val now = LocalDateTime.of(2026, 11, 25, 10, 0, 0)

    @Test
    fun disablingBiasKeepsMetricsButStopsBiasAlerts() {
        val cfg = baseConfig().copy(biasEnabled = false)
        val result = evaluate(item, snapshot(13.0), cfg)
        assertTrue(result.hits.isEmpty())
        assertEquals("MA2 10.000 | 乖离 +30.00%", result.detail)
    }

    @Test
    fun biasThresholdsAreInclusiveAndItemOverridesApply() {
        val cfg = baseConfig()
        assertEquals("bias_up", evaluate(item, snapshot(10.3), cfg).hits.single().rule)
        assertEquals("bias_dn", evaluate(item, snapshot(9.7), cfg).hits.single().rule)

        val override = item.copy(bias = BiasOverrides(biasUpperPct = 31.0))
        assertTrue(evaluate(override, snapshot(13.0), cfg).hits.isEmpty())
    }

    @Test
    fun tRangeRunsBeforeMinimumHistoryGuard() {
        val cfg = baseConfig().copy(
            minHistoryBars = 60,
            tRange = TRangeConfig(enabled = true, base = 4.0, dev1Pct = 3.0, dev2Pct = 5.0),
        )
        val result = evaluate(item, snapshot(4.17, closes = emptyList()), cfg)
        assertEquals("t_above1", result.hits.single().rule)
        assertEquals("历史数据不足（0 根，需 60 根）", result.error)
        assertNull(result.detail)
    }

    @Test
    fun tLevelsUseManualBaseOrPreviousValueAndSkipNonPositiveDeviations() {
        val cfg = baseConfig().copy(
            tRange = TRangeConfig(enabled = true, base = 10.0, dev1Pct = 3.0, dev2Pct = 5.0),
        )
        val info = tLevels(item, snapshot(10.0, prevClose = 20.0), cfg)!!
        assertEquals(10.0, info.base, 0.0)
        assertEquals("手填基准", info.source)
        assertEquals(9.7, info.levels[0].low, 1e-9)
        assertEquals(10.3, info.levels[0].high, 1e-9)
        assertEquals(9.5, info.levels[1].low, 1e-9)
        assertEquals(10.5, info.levels[1].high, 1e-9)

        val previous = tLevels(item, snapshot(10.0, prevClose = 20.0), cfg.copy(tRange = cfg.tRange.copy(base = 0.0)))!!
        assertEquals(20.0, previous.base, 0.0)
        assertEquals("昨收", previous.source)
        assertNull(tLevels(item, snapshot(10.0), cfg.copy(tRange = cfg.tRange.copy(base = -1.0))))
        assertNull(
            tLevels(
                item,
                snapshot(10.0),
                cfg.copy(tRange = cfg.tRange.copy(base = 10.0, dev1Pct = 0.0, dev2Pct = -2.0)),
            ),
        )
    }

    @Test
    fun tStateTracksDeepestBandAndRearmsOnlyAfterReturningInside() {
        val cfg = baseConfig().copy(
            tRange = TRangeConfig(enabled = true, base = 4.0, dev1Pct = 3.0, dev2Pct = 5.0),
        )
        val info = tLevels(item, snapshot(4.0), cfg)!!
        val state = AlertState()

        val first = planTZone(snapshot(4.17), info, state, "20261125", now)!!
        assertEquals("above1", first.zone)
        assertFalse(first.rearmed)
        confirmTZone(state, first)

        val deeper = planTZone(snapshot(4.35), info, state, "20261125", now)!!
        assertEquals("above2", deeper.zone)
        confirmTZone(state, deeper)
        assertNull(planTZone(snapshot(4.29), info, state, "20261125", now))
        assertNull(planTZone(snapshot(4.45), info, state, "20261125", now))

        val rearmed = planTZone(snapshot(4.10), info, state, "20261125", now)!!
        assertTrue(rearmed.rearmed)
        assertNull(rearmed.notification)
        assertEquals(TSide.IN, state.zone("000001|zone", "20261125"))

        val repeated = planTZone(snapshot(4.17), info, state, "20261125", now)!!
        assertEquals("above1", repeated.zone)
    }

    @Test
    fun failedTDeliveryDoesNotConsumeStateFlip() {
        val cfg = baseConfig().copy(
            tRange = TRangeConfig(enabled = true, base = 4.0, dev1Pct = 3.0, dev2Pct = 5.0),
        )
        val info = tLevels(item, snapshot(4.0), cfg)!!
        val state = AlertState()
        val first = planTZone(snapshot(4.35), info, state, "20261125", now)!!
        val retry = planTZone(snapshot(4.35), info, state, "20261125", now)
        assertNotNull(retry)
        assertEquals(first.zone, retry!!.zone)
    }

    @Test
    fun tStateAlsoTracksLowerBands() {
        val info = TInfo(4.0, "手填基准", listOf(TLevel(1, 3.0, 3.88, 4.12), TLevel(2, 5.0, 3.8, 4.2)))
        assertEquals(TState(TSide.BELOW, 1), tState(info, 3.85))
        assertEquals(TState(TSide.BELOW, 2), tState(info, 3.75))
        assertEquals(TState(TSide.BELOW, 1), tState(info, 3.85))
        assertEquals(TState.IN, tState(info, 4.0))
    }

    @Test
    fun crossAndChangeRulesMatchPcThresholds() {
        val crossCfg = baseConfig().copy(
            biasMas = emptyList(),
            cross = CrossConfig(enabled = true, short = 2, long = 3),
        )
        val gold = evaluate(item, snapshot(20.0, closes = listOf(10.0, 9.0, 8.0, 7.0, 20.0)), crossCfg)
        assertEquals("cross_gold", gold.hits.single().rule)
        assertTrue(gold.hits.single().text.contains("金叉"))

        val changeCfg = baseConfig().copy(
            biasMas = emptyList(),
            change = ChangeConfig(enabled = true, upperPct = 5.0, lowerPct = -5.0),
        )
        assertEquals("change", evaluate(item, snapshot(10.0, changePct = 5.0), changeCfg).hits.single().rule)
        assertEquals("change", evaluate(item, snapshot(10.0, changePct = -5.0), changeCfg).hits.single().rule)
    }

    @Test
    fun proxyRulesUseTheirOwnThresholdsAndAlwaysHaveOncePerDayCooldown() {
        val cfg = baseConfig().copy(proxy = ProxyConfig(listOf(2), 4.0, -4.0))
        val result = evaluateProxy(item, snapshot(10.5, closes = listOf(10.0, 10.0), changePct = 4.5), cfg)
        assertEquals(listOf("proxy_up", "proxy_change"), result.hits.map { it.rule })
        assertEquals("MA2 10.000 | 乖离 +5.00%", result.detail)
        assertNull(cooldownMinutesFor(item, cfg, "proxy_up", FUND))
    }

    @Test
    fun cooldownsDistinguishFundsCrossesChangeAndBias() {
        val cfg = baseConfig().copy(
            change = ChangeConfig(cooldownMinutes = 33),
            cross = CrossConfig(enabled = true),
        )
        val override = item.copy(bias = BiasOverrides(biasCooldownMinutes = 12))
        assertNull(cooldownMinutesFor(override, cfg, "bias_up", FUND))
        assertNull(cooldownMinutesFor(override, cfg, "cross_gold", LISTED))
        assertEquals(33L, cooldownMinutesFor(override, cfg, "change", LISTED))
        assertEquals(12L, cooldownMinutesFor(override, cfg, "bias_dn", LISTED))
    }

    @Test
    fun notificationContentIsPlainTextWithPriceMetricsAndTimestamp() {
        val content = buildNotification(snapshot(10.25), "MA5 10.000", listOf("做T提醒"), now, "（场外净值）")
        assertEquals("测试标的 000001（场外净值）", content.title)
        assertTrue(content.body.contains("现价 10.250 (+2.50%)  前值 10.000"))
        assertTrue(content.body.contains("MA5 10.000"))
        assertTrue(content.body.contains("做T提醒"))
        assertTrue(content.body.contains("2026-11-25 10:00:00 ｜ 判定 11-25 10:00:00"))
        assertFalse(content.body.contains("<font"))
        assertFalse(content.body.contains("###"))
    }

    private fun baseConfig(): WatchConfig = WatchConfig(
        minHistoryBars = 3,
        biasMas = listOf(2),
        biasUpperPct = 3.0,
        biasLowerPct = -3.0,
        cross = CrossConfig(enabled = false),
        change = ChangeConfig(enabled = false),
    )

    private fun snapshot(
        price: Double,
        closes: List<Double> = listOf(10.0, 10.0, 10.0),
        prevClose: Double? = 10.0,
        changePct: Double? = 0.0,
    ): MarketSnapshot = MarketSnapshot(
        name = "测试标的",
        code = "000001",
        kind = LISTED,
        price = price,
        prevClose = prevClose,
        changePct = changePct,
        date = "20261125",
        timeDisplay = "2026-11-25 10:00:00",
        closes = closes,
    )
}
