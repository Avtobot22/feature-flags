package io.github.avtobot22.featureflags.json

import io.github.avtobot22.featureflags.ConfigOrigin
import io.github.avtobot22.featureflags.Configuration
import io.github.avtobot22.featureflags.ConfigurationCodec
import io.github.avtobot22.featureflags.EvaluationInput
import io.github.avtobot22.featureflags.FallbackPolicy
import io.github.avtobot22.featureflags.Feature
import io.github.avtobot22.featureflags.FeatureFlagsSource
import io.github.avtobot22.featureflags.FeatureFlagsSystem
import io.github.avtobot22.featureflags.FlagDefinition
import io.github.avtobot22.featureflags.ParameterReader
import io.github.avtobot22.featureflags.Readiness
import io.github.avtobot22.featureflags.RefreshError
import io.github.avtobot22.featureflags.RefreshResult
import io.github.avtobot22.featureflags.RefreshStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JsonFeatureFlagsLoadingTest {
    @Test
    fun `real codec loads disabled parameters and fully replaces unknown flags without evaluating definitions`() =
        runBlocking {
            var json = fixture("valid-nonempty.json").replace(
                "\"enabled\":true,\"parameters\":{\"depth\":2",
                "\"enabled\":false,\"parameters\":{\"depth\":5",
            )
            val system = system(FeatureFlagsSource { json })
            val broken = Feature.config("dashboard.prefetch", defaultParams = Unit) { _, _ -> error("Decoder bug") }

            assertEquals(RefreshResult.Applied(1, ConfigOrigin.REMOTE, 4), system.manager.refresh())
            assertEquals(5, system.reader.get(QuizmanFlags.prefetch).params.depth)
            assertFalse(system.reader.get(QuizmanFlags.prefetch).enabled)
            assertTrue(system.reader.get(Feature.boolean("future.unknown")))
            assertFailsWith<IllegalStateException> { system.reader.get(broken) }
            json = """{"featureToggles":[{"feature":"replacement","enabled":true}]}"""
            assertEquals(RefreshResult.Applied(2, ConfigOrigin.REMOTE, 1), system.manager.refresh())
            assertFalse(system.reader.get(Feature.boolean("future.unknown")))
            assertEquals(QuizmanFlags.PrefetchParams(2, 8), system.reader.get(QuizmanFlags.prefetch).params)
            assertTrue(system.reader.get(Feature.boolean("replacement")))
        }

    @Test
    fun `malformed duplicate missing schema and empty body reject entire refresh without fallback`() = runBlocking {
        val invalidDocuments = listOf(
            "{",
            fixture("duplicates.json"),
            fixture("invalid-enabled.json"),
            "{}",
            "",
            """{"featureToggles":[{"feature":"partial","enabled":true},{"feature":"broken"}]}""",
        )
        for (invalid in invalidDocuments) {
            var json = fixture("valid-nonempty.json")
            var localCalls = 0
            val system = system(
                FeatureFlagsSource { json },
                FeatureFlagsSource {
                    localCalls++
                    fixture("empty.json")
                },
            )
            system.manager.refresh()
            json = invalid

            val failure = assertIs<RefreshResult.Failure>(system.manager.refresh())

            assertEquals(ConfigOrigin.REMOTE, assertIs<RefreshError.InvalidConfiguration>(failure.error).origin)
            assertEquals(0, localCalls)
            assertEquals(Readiness.Ready(1, ConfigOrigin.REMOTE), system.reader.readiness.value)
            assertTrue(system.reader.get(Feature.boolean("future.unknown")))
            assertFalse(system.reader.get(Feature.boolean("partial")))
        }
    }

    @Test
    fun `real codec validates both fallback documents and empty fallback is a successful publication`() = runBlocking {
        for (localJson in listOf(fixture("valid-nonempty.json"), fixture("empty.json"), "{}")) {
            val system = system(FeatureFlagsSource { fixture("empty.json") }, FeatureFlagsSource { localJson })

            val result = system.manager.refresh()

            if (localJson == "{}") {
                val failure = assertIs<RefreshResult.Failure>(result)
                assertEquals(
                    ConfigOrigin.BUNDLED_ASSET,
                    assertIs<RefreshError.InvalidConfiguration>(failure.error).origin,
                )
                assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
            } else {
                val count = if (localJson == fixture("empty.json")) 0 else 4
                assertEquals(RefreshResult.Applied(1, ConfigOrigin.BUNDLED_ASSET, count), result)
                assertEquals(Readiness.Ready(1, ConfigOrigin.BUNDLED_ASSET), system.reader.readiness.value)
            }
        }
    }

    @Test
    fun `source protocol rejections such as 500 and 204 are transport failures without fallback`() = runBlocking {
        for (status in listOf(500, 204)) {
            val rejected = IOException("Rejected source response $status")
            var localCalls = 0
            val system = system(
                FeatureFlagsSource { throw rejected },
                FeatureFlagsSource {
                    localCalls++
                    fixture("empty.json")
                },
            )

            val failure = assertIs<RefreshResult.Failure>(system.manager.refresh())

            assertSame(rejected, assertIs<RefreshError.Transport>(failure.error).cause)
            assertEquals(0, localCalls)
            assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
        }
    }

    @Test
    fun `external codec configuration publishes without internal implementation dependency`() = runBlocking {
        val input = object : EvaluationInput {
            override val present: Boolean = true
            override val enabled: Boolean = true
            override val parameters: ParameterReader = JsonParameters.decode("null")
        }
        val empty = JsonConfigurationCodec.decode(fixture("empty.json"))
        val external = object : Configuration {
            override val flagCount: Int = 1
            override fun <T> evaluate(definition: FlagDefinition<T>): T =
                if (definition.key == "custom-key") definition.evaluate(input) else empty.evaluate(definition)
        }
        val system = FeatureFlagsSystem(
            FeatureFlagsSource { "custom-format" },
            ConfigurationCodec { external },
            FallbackPolicy.Never,
        )

        assertEquals(RefreshResult.Applied(1, ConfigOrigin.REMOTE, 1), system.manager.refresh())
        assertTrue(system.reader.get(Feature.boolean("custom-key")))
        assertFalse(system.reader.get(Feature.boolean("missing")))
        val finished = RefreshStatus.Finished(RefreshResult.Applied(1, ConfigOrigin.REMOTE, 1))
        assertEquals(finished, system.manager.refreshStatus.value)
    }

    private fun system(remote: FeatureFlagsSource, local: FeatureFlagsSource? = null): FeatureFlagsSystem =
        FeatureFlagsSystem(
            remote = remote,
            codec = JsonConfigurationCodec,
            fallback = if (local == null) FallbackPolicy.Never else FallbackPolicy.OnRemoteEmpty,
            local = local,
        )

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/fixtures/$name")).readText()
}
