package io.github.avtobot22.featureflags.json

import io.github.avtobot22.featureflags.ConfigurationCodec
import io.github.avtobot22.featureflags.EvaluationInput
import io.github.avtobot22.featureflags.Feature
import io.github.avtobot22.featureflags.FeatureValue
import io.github.avtobot22.featureflags.FlagDefinition
import io.github.avtobot22.featureflags.ParametersDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JsonConfigurationCodecTest {
    @Test
    fun `PAR-01 valid nonempty fixture evaluates typed flags`() {
        val codec: ConfigurationCodec = JsonConfigurationCodec
        val configuration = codec.decode(fixture("valid-nonempty.json"))

        assertFalse(configuration.isEmpty)
        assertTrue(configuration.evaluate(QuizmanFlags.guestMode))
        assertEquals(
            FeatureValue(true, QuizmanFlags.PrefetchParams(2, 8)),
            configuration.evaluate(QuizmanFlags.prefetch),
        )
        assertEquals(
            FeatureValue(false, QuizmanFlags.PaginationParams(20)),
            configuration.evaluate(QuizmanFlags.pagination),
        )
    }

    @Test
    fun `PAR-02 empty fixture returns defaults without invoking decoder`() {
        val configuration = JsonConfigurationCodec.decode(fixture("empty.json"))
        val defaults = Any()
        val definition = Feature.config(
            key = "missing",
            defaultEnabled = true,
            defaultParams = defaults,
            decoder = ParametersDecoder { _, _ -> error("An absent flag must not invoke the decoder.") },
        )

        assertTrue(configuration.isEmpty)
        assertEquals(FeatureValue(true, defaults), configuration.evaluate(definition))
        assertSame(defaults, configuration.evaluate(definition).params)
    }

    @Test
    fun `PAR-09 keys differing in case both survive`() {
        val configuration = JsonConfigurationCodec.decode(
            """{"featureToggles":[{"feature":"Flag","enabled":true},{"feature":"flag","enabled":false}]}""",
        )

        assertTrue(configuration.evaluate(Feature.boolean("Flag")))
        assertFalse(configuration.evaluate(Feature.boolean("flag", default = true)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", ",\"parameters\":null", ",\"parameters\":{}"])
    fun `PAR-10 and PAR-11 absent null and empty parameters use field defaults`(parameters: String) {
        val configuration = JsonConfigurationCodec.decode(
            """{"featureToggles":[{"feature":"dashboard.prefetch","enabled":true$parameters}]}""",
        )

        assertEquals(
            FeatureValue(true, QuizmanFlags.PrefetchParams(2, 8)),
            configuration.evaluate(QuizmanFlags.prefetch),
        )
    }

    @Test
    fun `PAR-13 and PAR-14 unknown flags and nested JSON parameters are retained`() {
        val parameters = """{"depth":5,"future":{"items":[1,2.5,1e3,true,null,{"text":"value"}]}}"""
        val configuration = JsonConfigurationCodec.decode(
            """{"featureToggles":[{"feature":"future.flag","enabled":true,"parameters":$parameters}]}""",
        )

        assertTrue(configuration.evaluate(Feature.boolean("future.flag")))
        assertEquals<JsonElement?>(
            Json.parseToJsonElement(parameters),
            (configuration as JsonConfiguration).flags.getValue("future.flag").jsonParameters,
        )
        assertFalse(configuration.evaluate(QuizmanFlags.guestMode))
    }

    @Test
    fun `PAR-15 extra DTO fields are ignored without changing known data`() {
        val configuration = JsonConfigurationCodec.decode(
            """{"extra":{"items":[null,true]},"featureToggles":[{"feature":"x","enabled":true,"extra":[1,2]}]}""",
        )

        assertTrue(configuration.evaluate(Feature.boolean("x")))
    }

    @Test
    fun `disabled flag retains parameters and applies strict numeric defaults`() {
        val configuration = JsonConfigurationCodec.decode(
            """{"featureToggles":[{"feature":"dashboard.prefetch","enabled":false,
                "parameters":{"depth":5,"maxConcurrent":"4"}}]}""",
        )

        assertEquals(
            FeatureValue(false, QuizmanFlags.PrefetchParams(5, 8)),
            configuration.evaluate(QuizmanFlags.prefetch),
        )
    }

    @Test
    fun `configuration does not evaluate definitions until requested and propagates decoder errors`() {
        val failure = IllegalStateException("Decoder failed.")
        val cancelled = CancellationException("Cancelled.")
        val configuration = JsonConfigurationCodec.decode("""{"featureToggles":[{"feature":"x","enabled":false}]}""")
        for (exception in listOf(failure, cancelled)) {
            val definition = Feature.config(
                key = "x",
                defaultParams = 0,
                decoder = ParametersDecoder { _, _ -> throw exception },
            )
            assertSame(exception, assertFailsWith<RuntimeException> { configuration.evaluate(definition) })
        }
    }

    @Test
    fun `empty feature key remains a valid case sensitive identifier`() {
        val configuration = JsonConfigurationCodec.decode("""{"featureToggles":[{"feature":"","enabled":true}]}""")

        assertTrue(configuration.evaluate(Feature.boolean("")))
    }

    @Test
    fun `validated configuration owns its map and preserves earlier results across decoding`() {
        val first = JsonConfigurationCodec.decode("""{"featureToggles":[{"feature":"x","enabled":true}]}""")
        val second = JsonConfigurationCodec.decode(fixture("empty.json"))

        assertTrue(first.evaluate(Feature.boolean("x")))
        assertFalse(second.evaluate(Feature.boolean("x")))
        assertFailsWith<UnsupportedOperationException> { ((first as JsonConfiguration).flags as MutableMap).clear() }
    }

    @Test
    fun `custom definition sees presence and parameters from the same flag`() {
        val configuration = JsonConfigurationCodec.decode(
            """{"featureToggles":[{"feature":"x","enabled":false,"parameters":{"value":"text"}}]}""",
        )
        val definition = object : FlagDefinition<String> {
            override val key: String = "x"
            override fun evaluate(input: EvaluationInput): String =
                "${input.present}:${input.enabled}:${input.parameters.string("value", default = "absent")}"
        }

        assertEquals("true:false:text", configuration.evaluate(definition))
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/fixtures/$name")).readText()
}
