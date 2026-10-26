package io.github.avtobot22.featureflags.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.avtobot22.featureflags.ConfigOrigin
import io.github.avtobot22.featureflags.FallbackPolicy
import io.github.avtobot22.featureflags.Feature
import io.github.avtobot22.featureflags.FeatureFlagsSource
import io.github.avtobot22.featureflags.FeatureFlagsSystem
import io.github.avtobot22.featureflags.Readiness
import io.github.avtobot22.featureflags.RefreshError
import io.github.avtobot22.featureflags.RefreshResult
import io.github.avtobot22.featureflags.json.JsonConfigurationCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException

@RunWith(AndroidJUnit4::class)
class AssetsFeatureFlagsSourceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val guestMode = Feature.boolean("dashboard.guestMode")
    private val empty = """{"featureToggles":[]}"""

    @Test
    fun loadsUtf8AssetFromApkAfterEmptyRemote() = runBlocking {
        val system = system(AssetsFeatureFlagsSource(assets))

        assertEquals(RefreshResult.Applied(1, ConfigOrigin.BUNDLED_ASSET, 2), system.manager.refresh())
        assertTrue(system.reader.get(guestMode))
        assertTrue(system.reader.get(Feature.boolean("будущий.флаг")))
    }

    @Test
    fun readsCustomPathAndAllowsEmptyLocalConfiguration() = runBlocking {
        val system = system(AssetsFeatureFlagsSource(assets, "flags/empty.json"))

        assertEquals(RefreshResult.Applied(1, ConfigOrigin.BUNDLED_ASSET, 0), system.manager.refresh())
        assertFalse(system.reader.get(guestMode))
    }

    @Test
    fun missingAssetFailsWithoutReplacingActiveConfiguration() = runBlocking {
        var remote = assets.open("FallbackFeatureToggles.json").bufferedReader().use { it.readText() }
        val system = system(AssetsFeatureFlagsSource(assets, "missing.json"), FeatureFlagsSource { remote })
        system.manager.refresh()
        remote = empty

        val result = system.manager.refresh()

        assertTrue(result is RefreshResult.Failure)
        val error = (result as RefreshResult.Failure).error
        assertTrue(error is RefreshError.LocalSource)
        assertTrue((error as RefreshError.LocalSource).cause is FileNotFoundException)
        assertEquals(Readiness.Ready(1, ConfigOrigin.REMOTE), system.reader.readiness.value)
        assertTrue(system.reader.get(guestMode))
    }

    @Test
    fun invalidAssetIsConfigurationFailureWithoutPublication() = runBlocking {
        val system = system(AssetsFeatureFlagsSource(assets, "flags/invalid.json"))

        val result = system.manager.refresh()

        assertTrue(result is RefreshResult.Failure)
        val error = (result as RefreshResult.Failure).error
        assertTrue(error is RefreshError.InvalidConfiguration)
        assertEquals(ConfigOrigin.BUNDLED_ASSET, (error as RefreshError.InvalidConfiguration).origin)
        assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
    }

    @Test
    fun nonemptyRemoteDoesNotReadMissingAsset() = runBlocking {
        val system = system(
            AssetsFeatureFlagsSource(assets, "missing.json"),
            AssetsFeatureFlagsSource(assets),
        )

        assertEquals(RefreshResult.Applied(1, ConfigOrigin.REMOTE, 2), system.manager.refresh())
        assertTrue(system.reader.get(guestMode))
    }

    private fun system(
        local: FeatureFlagsSource,
        remote: FeatureFlagsSource = FeatureFlagsSource { empty },
    ): FeatureFlagsSystem = FeatureFlagsSystem(remote, JsonConfigurationCodec, FallbackPolicy.OnRemoteEmpty, local)
}
