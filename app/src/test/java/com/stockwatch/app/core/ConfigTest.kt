package com.stockwatch.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {
    @Test
    fun missingFieldsUsePcDefaults() {
        val cfg = parseWatchConfig("{}")
        assertEquals(30L, cfg.pollSeconds)
        assertEquals(listOf(5, 20), cfg.biasMas)
        assertTrue(cfg.biasEnabled)
        assertEquals(3.0, cfg.biasUpperPct, 0.0)
        assertEquals(-3.0, cfg.biasLowerPct, 0.0)
        assertEquals(60L, cfg.biasCooldownMinutes)
        assertEquals(60, cfg.minHistoryBars)
        assertEquals(listOf(TradingSession("09:30", "11:30"), TradingSession("13:00", "15:00")), cfg.sessions)
    }

    @Test
    fun itemOverridesAndNestedConfigsMergeWithGlobalValues() {
        val cfg = parseWatchConfig(
            """
            {
              "bias_mas": [10, 30],
              "bias_upper_pct": 4.5,
              "bias_lower_pct": -4.5,
              "bias_cooldown_minutes": 90,
              "min_history_bars": 80,
              "cross": {"enabled": false, "short": 10, "long": 30},
              "change": {"enabled": false, "upper_pct": 7, "cooldown_minutes": 180},
              "t_range": {"enabled": true, "base": 12, "dev1_pct": 3, "dev2_pct": 5},
              "proxy": {"bias_mas": [5], "upper_pct": 6, "lower_pct": -6},
              "stocks": [
                {
                  "code": "000001",
                  "bias_mas": [5],
                  "bias_upper_pct": 2,
                  "bias_cooldown_minutes": 15,
                  "cross": {"short": 3},
                  "change": {"upper_pct": 4},
                  "t_range": {"dev1_pct": 2},
                  "proxy_rules": {"upper_pct": 5},
                  "proxy": "sh510300"
                },
                {"symbol": "sz159915", "type": "LISTED"}
              ]
            }
            """.trimIndent(),
        )

        val first = cfg.items[0]
        assertEquals("sz000001", first.symbol)
        assertEquals("sh510300", first.proxy)
        assertEquals(listOf(5), itemBiasMas(first, cfg))
        assertEquals(2.0, itemBiasUpperPct(first, cfg), 0.0)
        assertEquals(-4.5, itemBiasLowerPct(first, cfg), 0.0)
        assertEquals(15L, itemBiasCooldownMinutes(first, cfg))
        assertEquals(80, itemMinHistoryBars(first, cfg))
        assertEquals(CrossConfig(false, 3, 30), itemCross(first, cfg))
        assertEquals(ChangeConfig(false, 4.0, -5.0, 180), itemChange(first, cfg))
        assertEquals(TRangeConfig(true, 12.0, 2.0, 5.0), itemTRange(first, cfg))
        assertEquals(ProxyConfig(listOf(5), 5.0, -6.0), proxyConfig(first, cfg))
        assertEquals("sz159915", cfg.items[1].symbol)
        assertEquals("listed", cfg.items[1].type)
    }

    @Test
    fun absentItemSubConfigsKeepGlobalConfigAndBiasEnabledStaysGlobal() {
        val cfg = parseWatchConfig(
            """
            {
              "bias_enabled": false,
              "cross": {"short": 7},
              "stocks": [{"code": "600000", "bias_enabled": true, "cross": null}]
            }
            """.trimIndent(),
        )
        val item = cfg.items.single()
        assertFalse(cfg.biasEnabled)
        assertEquals(CrossConfig(true, 7, 20), itemCross(item, cfg))
        assertEquals(cfg.change, itemChange(item, cfg))
        assertEquals(cfg.tRange, itemTRange(item, cfg))
        assertEquals(cfg.proxy, proxyConfig(item, cfg))
    }

    @Test
    fun customSessionsAndInvalidSymbolAreParsedAtConfigBoundary() {
        val cfg = parseWatchConfig(
            """
            {
              "poll_seconds": "45",
              "sessions": [["09:31", "11:29"], ["13:01", "14:59"]],
              "stocks": [{"code": "not-a-code"}, {"code": "510300", "type": "fund"}]
            }
            """.trimIndent(),
        )
        assertEquals(45L, cfg.pollSeconds)
        assertEquals(listOf(TradingSession("09:31", "11:29"), TradingSession("13:01", "14:59")), cfg.sessions)
        assertEquals("", cfg.items[0].symbol)
        assertEquals("fund", cfg.items[1].type)
    }
}
