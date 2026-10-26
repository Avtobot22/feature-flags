package io.github.avtobot22.featureflags.android

import android.content.res.AssetManager
import io.github.avtobot22.featureflags.FeatureFlagsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Reads UTF-8 on IO. The application supplies the asset; missing files propagate as source failures. */
public class AssetsFeatureFlagsSource(
    private val assets: AssetManager,
    private val assetPath: String = "FallbackFeatureToggles.json",
) : FeatureFlagsSource {
    override suspend fun loadJson(): String = withContext(Dispatchers.IO) {
        assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText().also { coroutineContext.ensureActive() }
        }
    }
}
