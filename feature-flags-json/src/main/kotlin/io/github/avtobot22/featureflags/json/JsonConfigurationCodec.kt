package io.github.avtobot22.featureflags.json

import io.github.avtobot22.featureflags.Configuration
import io.github.avtobot22.featureflags.ConfigurationCodec
import io.github.avtobot22.featureflags.EvaluationInput
import io.github.avtobot22.featureflags.FlagDefinition
import io.github.avtobot22.featureflags.ParameterReader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections

/** Decodes the featureToggles schema; keys are case-sensitive and duplicate feature keys are rejected. */
public object JsonConfigurationCodec : ConfigurationCodec {
    override fun decode(json: String): Configuration {
        val response = StrictJson.decode(json) as? JsonObject
        requireNotNull(response) { "Expected a configuration object." }
        val toggles = response["featureToggles"] as? JsonArray
        requireNotNull(toggles) { "Expected a featureToggles array." }
        val flags = LinkedHashMap<String, JsonToggle>()
        for (element in toggles) {
            val toggle = decodeToggle(element)
            require(flags.putIfAbsent(toggle.feature, toggle) == null) { "Duplicate feature key." }
        }
        return JsonConfiguration(Collections.unmodifiableMap(flags))
    }

    private fun decodeToggle(element: JsonElement): JsonToggle {
        val toggle = element as? JsonObject
        requireNotNull(toggle) { "Expected a feature toggle object." }
        val feature = toggle["feature"] as? JsonPrimitive
        require(feature != null && feature.isString) { "Expected a feature string." }
        val enabled = toggle["enabled"] as? JsonPrimitive
        require(enabled != null && !enabled.isString && enabled.content in setOf("true", "false")) {
            "Expected an enabled Boolean."
        }
        val parameters = toggle["parameters"]
        require(parameters == null || parameters == JsonNull || parameters is JsonObject) {
            "Expected a parameters object or null."
        }
        return JsonToggle(feature.content, enabled.content == "true", parameters as? JsonObject)
    }
}

internal class JsonConfiguration(val flags: Map<String, JsonToggle>) : Configuration {
    override val flagCount: Int
        get() = flags.size

    override fun <T> evaluate(definition: FlagDefinition<T>): T =
        definition.evaluate(flags[definition.key] ?: MissingToggle)
}

internal class JsonToggle(
    val feature: String,
    override val enabled: Boolean,
    val jsonParameters: JsonObject?,
) : EvaluationInput {
    override val present: Boolean = true
    override val parameters: ParameterReader = JsonParameterReader(jsonParameters)
}

private object MissingToggle : EvaluationInput {
    override val present: Boolean = false
    override val enabled: Boolean = false
    override val parameters: ParameterReader = JsonParameterReader(null)
}
