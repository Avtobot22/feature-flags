package io.github.avtobot22.featureflags.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

internal class ConfigurationControlKeys(private val document: String) {
    private var position = 0

    fun validate() {
        visitValue(Location.ROOT)
    }

    private fun visitValue(location: Location) {
        skipWhitespace()
        when (document[position]) {
            '{' -> visitObject(location)
            '[' -> visitArray(location)
            '"' -> readString()
            else -> {
                while (position < document.length && document[position] !in ",]} \t\r\n") position++
            }
        }
    }

    private fun visitObject(location: Location) {
        position++
        skipWhitespace()
        val keys = HashSet<String>()
        while (document[position] != '}') {
            val key = readString()
            if (location in setOf(Location.ROOT, Location.FLAG, Location.RULE)) {
                require(keys.add(key)) { "Duplicate configuration control field." }
            }
            skipWhitespace()
            position++
            val child = when {
                location == Location.ROOT && key in setOf("flags", "featureToggles") -> Location.FLAGS
                location == Location.FLAG && key == "rollout" -> Location.RULE
                else -> Location.OTHER
            }
            visitValue(child)
            skipWhitespace()
            if (document[position] == ',') {
                position++
                skipWhitespace()
            }
        }
        position++
    }

    private fun visitArray(location: Location) {
        position++
        skipWhitespace()
        while (document[position] != ']') {
            visitValue(if (location == Location.FLAGS) Location.FLAG else Location.OTHER)
            skipWhitespace()
            if (document[position] == ',') {
                position++
                skipWhitespace()
            }
        }
        position++
    }

    private fun readString(): String {
        val start = position++
        while (document[position] != '"') {
            if (document[position] == '\\') position++
            position++
        }
        position++
        return (Json.parseToJsonElement(document.substring(start, position)) as JsonPrimitive).content
    }

    private fun skipWhitespace() {
        while (position < document.length && document[position] in " \t\r\n") position++
    }

    private enum class Location { ROOT, FLAGS, FLAG, RULE, OTHER }
}
