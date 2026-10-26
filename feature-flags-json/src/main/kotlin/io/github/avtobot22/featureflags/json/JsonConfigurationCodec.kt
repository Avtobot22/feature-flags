package io.github.avtobot22.featureflags.json

import io.github.avtobot22.featureflags.Configuration
import io.github.avtobot22.featureflags.ConfigurationCodec
import io.github.avtobot22.featureflags.EvaluationContext
import io.github.avtobot22.featureflags.EvaluationInput
import io.github.avtobot22.featureflags.FlagDefinition
import io.github.avtobot22.featureflags.ParameterReader
import io.github.avtobot22.featureflags.PercentageRollout
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
        ConfigurationControlKeys(json).validate()
        require(response.keys.none { it in setOf("schemaVersion", "flags", "rollout", "rules") }) {
            "Versioned rules require the rollout codec."
        }
        val toggles = response["featureToggles"] as? JsonArray
        requireNotNull(toggles) { "Expected a featureToggles array." }
        val flags = LinkedHashMap<String, JsonToggle>()
        for (element in toggles) {
            require(element !is JsonObject || element.keys.none { it in setOf("rollout", "rules") }) {
                "Rollout is not supported in the legacy format."
            }
            val toggle = decodeToggle(element)
            require(flags.putIfAbsent(toggle.feature, toggle) == null) { "Duplicate feature key." }
        }
        return JsonConfiguration(Collections.unmodifiableMap(flags))
    }

    internal fun decodeToggle(element: JsonElement): JsonToggle {
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

    override fun <T> evaluate(definition: FlagDefinition<T>): T = evaluate(definition, EvaluationContext())

    override fun <T> evaluate(definition: FlagDefinition<T>, context: EvaluationContext): T {
        val toggle = flags[definition.key] ?: return definition.evaluate(MissingToggle)
        val input = object : EvaluationInput {
            override val present: Boolean = true
            override val enabled: Boolean = toggle.enabled && (toggle.rollout?.includes(context) ?: true)
            override val parameters: ParameterReader = toggle.parameters
        }
        return definition.evaluate(input)
    }
}

internal class JsonToggle(
    val feature: String,
    override val enabled: Boolean,
    val jsonParameters: JsonObject?,
    val rollout: PercentageRollout? = null,
) : EvaluationInput {
    override val present: Boolean = true
    override val parameters: ParameterReader = JsonParameterReader(jsonParameters)
}

private object MissingToggle : EvaluationInput {
    override val present: Boolean = false
    override val enabled: Boolean = false
    override val parameters: ParameterReader = JsonParameterReader(null)
}
