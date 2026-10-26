package io.github.avtobot22.featureflags.json

import io.github.avtobot22.featureflags.Configuration
import io.github.avtobot22.featureflags.ConfigurationCodec
import io.github.avtobot22.featureflags.PercentageRollout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections

/** Decodes only schemaVersion=2 with flags; use a separately negotiated endpoint and cache scope for this format. */
public object JsonRolloutConfigurationCodec : ConfigurationCodec {
    override fun decode(json: String): Configuration {
        val response = StrictJson.decode(json) as? JsonObject
        requireNotNull(response) { "Expected a configuration object." }
        ConfigurationControlKeys(json).validate()
        require(response.keys.all { it in setOf("schemaVersion", "flags") }) { "Unsupported configuration field." }
        require(strictInt(response["schemaVersion"]) == SCHEMA_VERSION) { "Unsupported configuration schema version." }
        val toggles = response["flags"] as? JsonArray
        requireNotNull(toggles) { "Expected a flags array." }
        val flags = LinkedHashMap<String, JsonToggle>()
        for (element in toggles) {
            val objectValue = element as? JsonObject
            requireNotNull(objectValue) { "Expected a feature toggle object." }
            require(objectValue.keys.all { it in setOf("feature", "enabled", "parameters", "rollout") }) {
                "Unsupported feature field."
            }
            val base = JsonConfigurationCodec.decodeToggle(objectValue)
            val rollout = if ("rollout" in objectValue) decodeRollout(objectValue["rollout"]) else null
            val toggle = JsonToggle(base.feature, base.enabled, base.jsonParameters, rollout)
            require(flags.putIfAbsent(toggle.feature, toggle) == null) { "Duplicate feature key." }
        }
        return JsonConfiguration(Collections.unmodifiableMap(flags))
    }

    private fun decodeRollout(element: JsonElement?): PercentageRollout {
        val rule = element as? JsonObject
        requireNotNull(rule) { "Expected a rollout object." }
        require(rule.keys == setOf("kind", "basisPoints", "group")) { "Unsupported rollout fields." }
        val kind = rule["kind"] as? JsonPrimitive
        require(kind != null && kind.isString && kind.content == "percentage") { "Unsupported rollout kind." }
        val group = rule["group"] as? JsonPrimitive
        require(group != null && group.isString) { "Expected a rollout group string." }
        val basisPoints = strictInt(rule["basisPoints"])
        requireNotNull(basisPoints) { "Expected integer percentage basis points." }
        return PercentageRollout(basisPoints, group.content)
    }

    private fun strictInt(element: JsonElement?): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        return if (!primitive.isString && INTEGER.matches(primitive.content)) primitive.content.toIntOrNull() else null
    }

    private const val SCHEMA_VERSION = 2
    private val INTEGER = Regex("-?(0|[1-9][0-9]*)")
}
