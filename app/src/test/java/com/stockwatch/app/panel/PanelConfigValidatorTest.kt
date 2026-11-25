package com.stockwatch.app.panel

import com.stockwatch.app.core.CrossConfig
import com.stockwatch.app.core.JsonElement
import com.stockwatch.app.core.asObject
import com.stockwatch.app.core.itemCross
import com.stockwatch.app.core.itemTRange
import com.stockwatch.app.core.parseJson
import com.stockwatch.app.core.parseWatchConfig
import com.stockwatch.app.core.stringifyJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelConfigValidatorTest {
    @Test
    fun emptyObjectProducesCompleteDefaultsWithoutGroupErrors() {
        val result = validate("{}")

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(30, result.config["poll_seconds"])
        assertEquals(setOf("enabled", "short", "long"), (result.config["cross"] as Map<*, *>).keys)
        assertEquals(setOf("bias_mas", "upper_pct", "lower_pct"), (result.config["proxy"] as Map<*, *>).keys)
        assertFalse(result.config.containsKey("wecom"))
    }

    @Test
    fun validConfigKeepsNumbersNamesDisabledStateAndSparseOverrides() {
        val result = validate(
            """
            {
              "poll_seconds": 45,
              "sessions": [["09:31", "11:29"], ["13:01", "14:59"]],
              "bias_mas": [5, 10, 20],
              "bias_enabled": false,
              "cross": {"enabled": false, "short": 7, "long": 30},
              "change": {"upper_pct": 6, "lower_pct": -6, "cooldown_minutes": 90},
              "t_range": {"enabled": true, "base": 12.5, "dev1_pct": 3, "dev2_pct": 5},
              "proxy": {"bias_mas": [5, 20], "upper_pct": 4, "lower_pct": -4},
              "stocks": [{
                "code": "000001",
                "type": "listed",
                "name": "平安银行",
                "enabled": false,
                "bias_mas": [10],
                "bias_upper_pct": 2.5,
                "cross": {"short": 3},
                "change": {"upper_pct": 4},
                "t_range": {"dev1_pct": 2},
                "proxy_rules": {"upper_pct": 5}
              }]
            }
            """.trimIndent(),
        )

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(45, result.config["poll_seconds"])
        assertEquals(listOf(5, 10, 20), result.config["bias_mas"])
        assertEquals(false, result.config["bias_enabled"])
        val item = (result.config["stocks"] as List<Map<String, Any?>>).single()
        assertEquals("平安银行", item["name"])
        assertEquals(false, item["enabled"])
        assertEquals(mapOf("short" to 3, "long" to 30), item["cross"])
        assertEquals(mapOf("upper_pct" to 4.0, "lower_pct" to -6.0), item["change"])
        assertEquals(mapOf("dev1_pct" to 2.0), item["t_range"])
        assertEquals(mapOf("upper_pct" to 5.0, "lower_pct" to -4.0), item["proxy_rules"])

        val cfg = parseWatchConfig(stringifyJson(result.config))
        val parsed = cfg.items.single()
        assertEquals("sz000001", parsed.symbol)
        assertEquals("平安银行", parsed.name)
        assertFalse(parsed.enabled)
        assertEquals(CrossConfig(false, 3, 30), itemCross(parsed, cfg))
        assertEquals(2.0, itemTRange(parsed, cfg).dev1Pct, 0.0)
    }

    @Test
    fun invalidValuesAreReportedAndReplacedWithSafeDefaults() {
        val result = validate(
            """
            {
              "poll_seconds": 4,
              "bias_mas": [0, "bad", 251],
              "bias_upper_pct": 91,
              "bias_lower_pct": -91,
              "sessions": [["25:00", "11:30"], ["13:00", "12:00"]],
              "cross": {"short": 20, "long": 10},
              "change": {"upper_pct": -5, "lower_pct": 5},
              "t_range": {"dev1_pct": 0, "dev2_pct": 2},
              "stocks": [
                {"code": "bad"},
                {"code": "110001", "type": "fund", "proxy": "sz110001"}
              ]
            }
            """.trimIndent(),
        )

        assertFalse(result.errors.isEmpty())
        assertTrue(result.errors.any { "轮询间隔" in it })
        assertTrue(result.errors.any { "交易时段" in it })
        assertTrue(result.errors.any { "短均线" in it })
        assertTrue(result.errors.any { "上限必须大于下限" in it })
        assertTrue(result.errors.any { "一档偏离" in it })
        assertTrue(result.errors.any { "代理代码不能是自身" in it })
        assertEquals(30, result.config["poll_seconds"])
        assertEquals(listOf(5, 20), result.config["bias_mas"])
        assertEquals(mapOf("enabled" to true, "short" to 5, "long" to 20), result.config["cross"])
        assertEquals(3.0, (result.config["t_range"] as Map<*, *>)["dev1_pct"])
        assertEquals(0.0, (result.config["t_range"] as Map<*, *>)["dev2_pct"])
        assertEquals(1, (result.config["stocks"] as List<*>).size)
    }

    @Test
    fun invalidNullablePeriodsDoNotBecomeZero() {
        val result = validate("""{"bias_mas": [5.5, "bad", 0, 6]}""")

        assertEquals(listOf(6), result.config["bias_mas"])
        assertTrue(result.errors.any { "必须是整数" in it })
        assertTrue(result.errors.any { "不是数字" in it })
        assertTrue(result.errors.any { "需在 1～250" in it })
    }

    @Test
    fun submittedSessionsOverridePreviousValues() {
        val previous = parseJson(
            """{"sessions": [["09:40", "10:00"]], "bias_enabled": false}""",
        ).asObject()!!.fields

        val retained = validate("{}", previous)
        assertTrue(retained.errors.toString(), retained.errors.isEmpty())
        assertEquals(listOf(listOf("09:40", "10:00")), retained.config["sessions"])
        assertEquals(false, retained.config["bias_enabled"])

        val replaced = validate("""{"sessions": [["09:45", "10:05"]]}""", previous)
        assertTrue(replaced.errors.toString(), replaced.errors.isEmpty())
        assertEquals(listOf(listOf("09:45", "10:05")), replaced.config["sessions"])
    }

    @Test
    fun blankOptionalFieldsAndEmptyGroupsStayAbsent() {
        val result = validate(
            """
            {"stocks": [{
              "code": "600000",
              "name": "",
              "enabled": false,
              "bias_mas": [],
              "bias_upper_pct": " ",
              "cross": {},
              "change": {},
              "t_range": {},
              "proxy_rules": {}
            }]}
            """.trimIndent(),
        )

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        val item = (result.config["stocks"] as List<Map<String, Any?>>).single()
        assertEquals(setOf("code", "type", "enabled"), item.keys)
        assertEquals(false, item["enabled"])
    }

    @Test
    fun enterpriseWebhookFieldsAreNeverReturned() {
        val result = validate(
            """{"wecom": {"corpsecret": "secret", "webhook": "https://example.invalid/hook"}}""",
        )

        assertTrue(result.errors.isEmpty())
        assertFalse(result.config.containsKey("wecom"))
        assertFalse(stringifyJson(result.config).contains("secret"))
    }

    private fun validate(json: String, previous: String = "{}"): PanelConfigResult = PanelConfigValidator.validate(
        parseJson(json),
        parseJson(previous).asObject()!!.fields,
    )

    private fun validate(json: String, previous: Map<String, JsonElement>): PanelConfigResult =
        PanelConfigValidator.validate(parseJson(json), previous)
}
