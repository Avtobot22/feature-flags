package io.github.avtobot22.featureflags.json

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal object StrictJson {
    fun decode(json: String): JsonElement {
        requireValidLexemes(json)
        return try {
            Json.parseToJsonElement(json)
        } catch (exception: SerializationException) {
            throw IllegalArgumentException("Malformed JSON document.", exception)
        }
    }

    private fun requireValidLexemes(json: String) {
        var quoted = false
        var escaped = false
        var tokenStart = -1
        for ((index, character) in json.withIndex()) {
            if (quoted) {
                require(character >= ' ') { "Unescaped control character in JSON string." }
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
            "Invalid JSON literal."
        }
    }

    private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
}
