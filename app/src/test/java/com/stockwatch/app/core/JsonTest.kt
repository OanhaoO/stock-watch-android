package com.stockwatch.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    @Test
    fun parsesNestedValuesAndRoundTrips() {
        val raw = """{"name":"测试","price":1.25,"ok":true,"nil":null,"arr":[1,"x"]}"""
        val parsed = parseJson(raw)
        assertEquals("测试", parsed["name"]?.asString())
        assertEquals(1.25, parsed["price"]?.asDouble()!!, 1e-9)
        assertEquals(true, parsed["ok"]?.asBoolean())
        assertNull(parsed["nil"]?.asString())
        assertEquals(2, parsed["arr"]?.asArray()?.size)

        val encoded = stringifyJson(parsed)
        val decoded = parseJson(encoded)
        assertEquals("测试", decoded["name"]?.asString())
        assertEquals(1.25, decoded["price"]?.asDouble()!!, 1e-9)
        assertTrue(encoded.contains("\"name\""))
    }

    @Test
    fun handlesEscapesAndUnicode() {
        val parsed = parseJson("""{"text":"line\nquote\"slash\\","cn":"\u4e2d\u6587"}""")
        assertEquals("line\nquote\"slash\\", parsed["text"]?.asString())
        assertEquals("中文", parsed["cn"]?.asString())
    }
}
