package io.github.avtobot22.featureflags.retrofit

import io.github.avtobot22.featureflags.FeatureFlagsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Accepts a successful HTTP response with a body; JSON validation belongs to the codec.
 * The application supplies a cancellable, buffered Retrofit request and owns its endpoint and headers.
 */
public class RetrofitFeatureFlagsSource(
    private val request: suspend () -> Response<ResponseBody>,
) : FeatureFlagsSource {
    override suspend fun loadJson(): String = withContext(Dispatchers.IO) {
        val response = request()
        try {
            coroutineContext.ensureActive()
            if (!response.isSuccessful) throw HttpException(response)
            val body = response.body() ?: throw IOException("Feature flags response has no body")
            body.string().also { coroutineContext.ensureActive() }
        } finally {
            response.body()?.close()
            response.errorBody()?.close()
        }
    }
}
