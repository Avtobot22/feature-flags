package io.github.avtobot22.featureflags.json

import io.github.avtobot22.featureflags.EvaluationContext
import io.github.avtobot22.featureflags.Feature
import io.github.avtobot22.featureflags.FeatureValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonRolloutConfigurationCodecTest {
    @Test
    fun `percentage applies before definition and retains parameters for excluded and disabled flags`() {
        val definition = Feature.config("flag", defaultEnabled = true, defaultParams = -1) { reader, default ->
            reader.int("value", default)
        }
        for (enabled in listOf(true, false)) {
            val configuration = JsonRolloutConfigurationCodec.decode(document(enabled = enabled))
            assertEquals(FeatureValue(enabled, 7), configuration.evaluate(definition, EvaluationContext("user-1")))
            assertEquals(FeatureValue(false, 7), configuration.evaluate(definition, EvaluationContext("user-3")))
            assertEquals(FeatureValue(false, 7), configuration.evaluate(definition))
            assertTrue(configuration.evaluate(Feature.boolean("missing", default = true)))
            assertEquals(1, configuration.flagCount)
        }
    }

    @Test
    fun `zero full absent rule and empty set keep explicit defaults`() {
        val flag = Feature.boolean("flag")
        val context = EvaluationContext("user-1")
        assertFalse(JsonRolloutConfigurationCodec.decode(document(points = "0")).evaluate(flag, context))
        val full = JsonRolloutConfigurationCodec.decode(document(points = "10000"))
        assertTrue(full.evaluate(flag, context))
        assertFalse(full.evaluate(flag))
        val plain = JsonRolloutConfigurationCodec.decode(
            """{"schemaVersion":2,"flags":[{"feature":"flag","enabled":true}]}""",
        )
        assertTrue(plain.evaluate(flag))
        val empty = JsonRolloutConfigurationCodec.decode("""{"schemaVersion":2,"flags":[]}""")
        assertTrue(empty.isEmpty)
        assertTrue(empty.evaluate(Feature.boolean("missing", default = true), context))
    }

    @Test
    fun `group intentionally aligns different features and definition exceptions propagate`() {
        val json = """{"schemaVersion":2,"flags":[
            {"feature":"flag","enabled":true,"rollout":{"kind":"percentage","basisPoints":2500,"group":"guest-v1"}},
            {"feature":"other","enabled":true,"rollout":{"kind":"percentage","basisPoints":2500,"group":"guest-v1"}}
        ]}"""
        val configuration = JsonRolloutConfigurationCodec.decode(json)
        for (key in listOf("user-1", "user-3")) {
            val context = EvaluationContext(key)
            assertEquals(
                configuration.evaluate(Feature.boolean("flag"), context),
                configuration.evaluate(Feature.boolean("other"), context),
            )
        }
        val failing = Feature.config("flag", defaultParams = Unit) { _, _ -> error("decoder") }
        assertFailsWith<IllegalStateException> { configuration.evaluate(failing, EvaluationContext("user-3")) }
    }

    @Test
    fun `strict percentages reject coercions null fractions exponents overflow and out of range`() {
        for (points in listOf("-1", "10001", "2147483648", "null", "true", "\"2500\"", "2500.0", "25e2", "[]")) {
            assertFailsWith<IllegalArgumentException>(points) { JsonRolloutConfigurationCodec.decode(document(points)) }
        }
    }

    @Test
    fun `malformed unknown and future mandatory rules reject even disabled unknown flags`() {
        val rules = listOf(
            "null", "[]", "true", "{}",
            """{"kind":"segment","basisPoints":2500,"group":"guest-v1"}""",
            """{"kind":null,"basisPoints":2500,"group":"guest-v1"}""",
            """{"kind":"percentage","basisPoints":2500}""",
            """{"kind":"percentage","basisPoints":2500,"group":""}""",
            """{"kind":"percentage","basisPoints":2500,"group":42}""",
            """{"kind":"percentage","basisPoints":2500,"group":null}""",
            """{"kind":"percentage","basisPoints":2500,"group":"\uD800"}""",
            """{"kind":"percentage","basisPoints":2500,"group":"guest-v1","version":2}""",
        )
        for (rule in rules) {
            val json = """{"schemaVersion":2,"flags":[{"feature":"unknown","enabled":false,"rollout":$rule}]}"""
            assertFailsWith<IllegalArgumentException>(rule) { JsonRolloutConfigurationCodec.decode(json) }
        }
    }

    @Test
    fun `v2 requires negotiated version and flags and rejects hybrid or unsupported fields`() {
        val invalid = listOf(
            "{}", "null", "[]", "{", "",
            """{"flags":[]}""",
            """{"schemaVersion":1,"flags":[]}""",
            """{"schemaVersion":3,"flags":[]}""",
            """{"schemaVersion":"2","flags":[]}""",
            """{"schemaVersion":2.0,"flags":[]}""",
            """{"schemaVersion":2e0,"flags":[]}""",
            """{"schemaVersion":null,"flags":[]}""",
            """{"schemaVersion":2,"flags":null}""",
            """{"schemaVersion":2,"featureToggles":[],"flags":[]}""",
            """{"schemaVersion":2,"flags":[],"rules":[]}""",
            """{"schemaVersion":2,"flags":[{"feature":"f","enabled":true,"segment":"all"}]}""",
            """{"schemaVersion":2,"flags":[{"feature":"f","enabled":"true"}]}""",
            """{"schemaVersion":2,"flags":[{"feature":"f","enabled":true,"parameters":[]}]}""",
            """{"schemaVersion":2,"flags":[{"feature":"f","enabled":true},{"feature":"f","enabled":false}]}""",
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException>(it) { JsonRolloutConfigurationCodec.decode(it) } }
    }

    @Test
    fun `legacy rejects v2 and hybrids instead of treating rollout as ignored metadata`() {
        val documents = listOf(
            document(),
            """{"featureToggles":[{"feature":"f","enabled":true,"rollout":{}}],"featureToggles":[]}""",
            """{"schemaVersion":2,"flags":[],"featureToggles":[]}""",
            """{"schemaVersion":2,"featureToggles":[]}""",
            """{"flags":[],"featureToggles":[]}""",
            """{"rules":[],"featureToggles":[]}""",
            """{"featureToggles":[{"feature":"f","enabled":true,"rollout":null}]}""",
            """{"featureToggles":[{"feature":"f","enabled":true,"rules":[]}]}""",
        )
        documents.forEach { assertFailsWith<IllegalArgumentException>(it) { JsonConfigurationCodec.decode(it) } }
        assertFailsWith<IllegalArgumentException> {
            JsonRolloutConfigurationCodec.decode("""{"featureToggles":[]}""")
        }
    }

    @Test
    fun `duplicate control keys cannot hide unsupported versions or overwritten rules`() {
        val invalid = listOf(
            """{"schemaVersion":3,"schemaVersion":2,"flags":[]}""",
            """{"schemaVersion":2,"flags":[{}],"flags":[]}""",
            """{"schemaVersion":2,"flags":[],"fl\u0061gs":[]}""",
            document().replace("\"enabled\":true", "\"enabled\":false,\"enabled\":true"),
            document().replace("\"feature\":\"flag\"", "\"feature\":\"other\",\"feature\":\"flag\""),
            document().replace("\"rollout\":", "\"rollout\":null,\"rollout\":"),
            document().replace("\"kind\":", "\"kind\":\"unsupported\",\"kind\":"),
            document().replace("\"basisPoints\":2500", "\"basisPoints\":0,\"basisPoints\":10000"),
            document().replace("\"basisPoints\":2500", "\"basisPoints\":null,\"basisPoints\":2500"),
            document().replace("\"group\":", "\"group\":\"\",\"group\":"),
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException>(it) { JsonRolloutConfigurationCodec.decode(it) } }
        val nestedParameters = document().replace(
            "\"value\":7",
            "\"value\":0,\"value\":7,\"nested\":{\"kind\":1,\"kind\":2}",
        )
        val configuration = JsonRolloutConfigurationCodec.decode(nestedParameters)
        val definition = Feature.config("flag", defaultParams = -1) { reader, default -> reader.int("value", default) }
        assertEquals(7, configuration.evaluate(definition, EvaluationContext("user-1")).params)
    }

    private fun document(points: String = "2500", enabled: Boolean = true): String =
        """{"schemaVersion":2,"flags":[{"feature":"flag","enabled":$enabled,"parameters":{"value":7},
            "rollout":{"kind":"percentage","basisPoints":$points,"group":"guest-v1"}}]}"""
}
