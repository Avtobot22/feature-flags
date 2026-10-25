package io.github.avtobot22.featureflags.json

import io.github.avtobot22.featureflags.ParameterReader
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public object JsonParameters {
    /**
     * Accepts a JSON object or null; null behaves like an empty parameter object.
     *
     * @throws IllegalArgumentException if [json] is malformed or is neither an object nor null.
     */
    public fun decode(json: String): ParameterReader {
        requireValidLexemes(json)
        val element = try {
            Json.parseToJsonElement(json)
        } catch (exception: SerializationException) {
            throw IllegalArgumentException("Expected a JSON parameter object or null.", exception)
        }
        require(element is JsonObject || element == JsonNull) { "Expected a JSON parameter object or null." }
        return JsonParameterReader(element as? JsonObject)
    }

    private fun requireValidLexemes(json: String) {
        var quoted = false
        var escaped = false
        var tokenStart = -1
        for ((index, character) in json.withIndex()) {
            if (quoted) {
                require(character >= ' ') { "Unescaped control character in JSON parameter string." }
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                }
            } else {
                when {
                    character == '"' -> {
                        requireValidLiteral(json, tokenStart, index)
                        tokenStart = -1
                        quoted = true
                    }
                    character in "{}[],: \t\r\n" -> {
                        requireValidLiteral(json, tokenStart, index)
                        tokenStart = -1
                    }
                    tokenStart == -1 -> tokenStart = index
                }
            }
        }
        requireValidLiteral(json, tokenStart, json.length)
    }

    private fun requireValidLiteral(json: String, start: Int, end: Int) {
        if (start == -1) return
        val token = json.substring(start, end)
        require(token == "true" || token == "false" || token == "null" || JSON_NUMBER.matches(token)) {
            "Invalid JSON parameter literal."
        }
    }

    private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
}

private class JsonParameterReader(private val parameters: JsonObject?) : ParameterReader {
    override fun int(name: String, default: Int, min: Int?, max: Int?): Int {
        require(min == null || max == null || min <= max) { "Minimum must not exceed maximum." }
        val number = primitive(name)
            ?.takeUnless { it.isString }
            ?.content
            ?.takeIf(STRICT_INT::matches)
            ?.toIntOrNull()
            ?: return default
        return number.coerceIn(min ?: Int.MIN_VALUE, max ?: Int.MAX_VALUE)
    }

    override fun boolean(name: String, default: Boolean): Boolean {
        val primitive = primitive(name) ?: return default
        return if (primitive.isString) default else primitive.content.toBooleanStrictOrNull() ?: default
    }

    override fun string(name: String, default: String): String {
        val primitive = primitive(name) ?: return default
        return if (primitive.isString) primitive.content else default
    }

    private fun primitive(name: String): JsonPrimitive? =
        (parameters?.get(name) as? JsonPrimitive)?.takeUnless { it == JsonNull }

    private companion object {
        val STRICT_INT = Regex("-?(0|[1-9][0-9]*)")
    }
}
