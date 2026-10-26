package io.github.avtobot22.featureflags.retrofit

import io.github.avtobot22.featureflags.FeatureFlagsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.net.HttpURLConnection
import kotlin.coroutines.coroutineContext

/** Only HTTP 200 is accepted. JSON validation remains the responsibility of the configuration codec. */
public class RetrofitFeatureFlagsSource(private val api: FeatureTogglesApi) : FeatureFlagsSource {
    override suspend fun loadJson(): String = withContext(Dispatchers.IO) {
        val response = api.getFeatureToggles()
        try {
            coroutineContext.ensureActive()
            if (response.code() != HttpURLConnection.HTTP_OK) throw HttpException(response)
            val body = response.body() ?: throw IOException("Feature flags response has no body")
            body.string().also { coroutineContext.ensureActive() }
        } finally {
            response.body()?.close()
            response.errorBody()?.close()
        }
    }
}
