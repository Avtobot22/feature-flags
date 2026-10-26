package io.github.avtobot22.featureflags.retrofit

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers

/** Authorization, base URL, timeouts and retry belong to the application's Retrofit/OkHttp client. */
public interface FeatureTogglesApi {
    @GET("api/tv/feature-toggles")
    @Headers("Content-Type: application/json", "X-Endpoint-Version: 1")
    public suspend fun getFeatureToggles(): Response<ResponseBody>
}
