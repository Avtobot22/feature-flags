package io.github.avtobot22.featureflags.json

import io.github.avtobot22.featureflags.ParameterReader
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
        val element = StrictJson.decode(json)
        require(element is JsonObject || element == JsonNull) { "Expected a JSON parameter object or null." }
        return JsonParameterReader(element as? JsonObject)
    }
}

internal class JsonParameterReader(private val parameters: JsonObject?) : ParameterReader {
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
