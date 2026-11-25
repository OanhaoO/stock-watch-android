package com.stockwatch.app.core

sealed interface JsonElement

data class JsonObject(val fields: Map<String, JsonElement>) : JsonElement
data class JsonArray(val items: List<JsonElement>) : JsonElement
data class JsonString(val value: String) : JsonElement
data class JsonNumber(val value: Double) : JsonElement
data class JsonBoolean(val value: Boolean) : JsonElement
object JsonNull : JsonElement

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.fields?.get(key)

fun JsonElement?.asString(): String? = (this as? JsonString)?.value
fun JsonElement?.asDouble(): Double? = (this as? JsonNumber)?.value
fun JsonElement?.asLong(): Long? = (this as? JsonNumber)?.value?.toLong()
fun JsonElement?.asInt(): Int? = (this as? JsonNumber)?.value?.toInt()
fun JsonElement?.asBoolean(): Boolean? = (this as? JsonBoolean)?.value
fun JsonElement?.asArray(): List<JsonElement> = (this as? JsonArray)?.items ?: emptyList()
fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

fun parseJson(text: String): JsonElement = JsonParser(text).parse()

fun stringifyJson(value: Any?): String = buildString { appendJson(value) }

private class JsonParser(private val text: String) {
    private var pos = 0

    fun parse(): JsonElement {
        skipSpace()
        val value = parseValue()
        skipSpace()
        if (pos != text.length) fail("Unexpected trailing content")
        return value
    }

    private fun parseValue(): JsonElement {
        skipSpace()
        return when (peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonString(parseString())
            't' -> parseLiteral("true", JsonBoolean(true))
            'f' -> parseLiteral("false", JsonBoolean(false))
            'n' -> parseLiteral("null", JsonNull)
            else -> parseNumber()
        }
    }

    private fun parseObject(): JsonObject {
        expect('{')
        skipSpace()
        val fields = linkedMapOf<String, JsonElement>()
        if (peek() == '}') {
            pos++
            return JsonObject(fields)
        }
        while (true) {
            skipSpace()
            val key = parseString()
            skipSpace()
            expect(':')
            fields[key] = parseValue()
            skipSpace()
            when (peek()) {
                '}' -> {
                    pos++
                    return JsonObject(fields)
                }
                ',' -> pos++
                else -> fail("Expected ',' or '}'")
            }
        }
    }

    private fun parseArray(): JsonArray {
        expect('[')
        skipSpace()
        val items = mutableListOf<JsonElement>()
        if (peek() == ']') {
            pos++
            return JsonArray(items)
        }
        while (true) {
            items += parseValue()
            skipSpace()
            when (peek()) {
                ']' -> {
                    pos++
                    return JsonArray(items)
                }
                ',' -> pos++
                else -> fail("Expected ',' or ']'")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (pos < text.length) {
            val ch = text[pos++]
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    if (pos >= text.length) fail("Unterminated escape")
                    when (val esc = text[pos++]) {
                        '"', '\\', '/' -> out.append(esc)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) fail("Invalid unicode escape")
                            val hex = text.substring(pos, pos + 4)
                            val code = hex.toIntOrNull(16) ?: fail("Invalid unicode escape")
                            out.append(code.toChar())
                            pos += 4
                        }
                        else -> fail("Invalid escape: \\$esc")
                    }
                }
                else -> if (ch.code < 0x20) fail("Control character in string") else out.append(ch)
            }
        }
        fail("Unterminated string")
    }

    private fun parseLiteral(literal: String, value: JsonElement): JsonElement {
        if (!text.startsWith(literal, pos)) fail("Expected $literal")
        pos += literal.length
        return value
    }

    private fun parseNumber(): JsonNumber {
        val start = pos
        while (pos < text.length && text[pos] in "-+0123456789.eE") pos++
        val raw = text.substring(start, pos)
        val number = raw.toDoubleOrNull() ?: fail("Invalid number: $raw")
        return JsonNumber(number)
    }

    private fun skipSpace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun peek(): Char = text.getOrNull(pos) ?: fail("Unexpected end of input")

    private fun expect(ch: Char) {
        if (peek() != ch) fail("Expected '$ch'")
        pos++
    }

    private fun fail(message: String): Nothing = throw IllegalArgumentException("$message at position $pos")
}

private fun StringBuilder.appendJson(value: Any?) {
    when (value) {
        null, JsonNull -> append("null")
        is JsonElement -> appendJsonElement(value)
        is String -> appendQuoted(value)
        is Boolean -> append(value)
        is Double -> {
            if (!value.isFinite()) throw IllegalArgumentException("JSON does not support $value")
            append(value)
        }
        is Float -> appendJson(value.toDouble())
        is Number -> append(value)
        is Map<*, *> -> {
            append('{')
            value.entries.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                appendQuoted(entry.key.toString())
                append(':')
                appendJson(entry.value)
            }
            append('}')
        }
        is Iterable<*> -> {
            append('[')
            value.forEachIndexed { index, item ->
                if (index > 0) append(',')
                appendJson(item)
            }
            append(']')
        }
        is Array<*> -> {
            append('[')
            value.forEachIndexed { index, item ->
                if (index > 0) append(',')
                appendJson(item)
            }
            append(']')
        }
        else -> appendQuoted(value.toString())
    }
}

private fun StringBuilder.appendJsonElement(value: JsonElement) {
    when (value) {
        is JsonObject -> {
            append('{')
            value.fields.entries.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                appendQuoted(entry.key)
                append(':')
                appendJson(entry.value)
            }
            append('}')
        }
        is JsonArray -> {
            append('[')
            value.items.forEachIndexed { index, item ->
                if (index > 0) append(',')
                appendJson(item)
            }
            append(']')
        }
        is JsonString -> appendQuoted(value.value)
        is JsonNumber -> appendJson(value.value)
        is JsonBoolean -> append(value.value)
        JsonNull -> append("null")
    }
}

private fun StringBuilder.appendQuoted(value: String) {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}
