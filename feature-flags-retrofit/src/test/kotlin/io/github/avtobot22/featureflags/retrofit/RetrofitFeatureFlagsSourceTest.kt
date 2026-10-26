package io.github.avtobot22.featureflags.retrofit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RetrofitFeatureFlagsSourceTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

    @AfterEach
    fun close() {
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun `endpoint and headers belong to application request`() = runBlocking {
        val json = """{"featureToggles":[],"title":"Флаги"}"""
        val api = api()
        repeat(2) { server.enqueue(MockResponse().setBody(json).setHeader("Content-Type", "application/json")) }

        assertEquals(json, RetrofitFeatureFlagsSource(api::getFlags).loadJson())

        val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/configuration/mobile", request.path)
        assertEquals(0, request.bodySize)
        assertNull(request.getHeader("Content-Type"))
        assertNull(request.getHeader("X-Endpoint-Version"))
        assertNull(request.getHeader("Authorization"))

        assertEquals(json, RetrofitFeatureFlagsSource(api::getVersionedSettings).loadJson())

        val versionedRequest = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v2/settings/flags", versionedRequest.path)
        assertEquals("application/vnd.settings+json", versionedRequest.getHeader("Accept"))
        assertEquals("7", versionedRequest.getHeader("X-App-Schema"))
        assertNull(versionedRequest.getHeader("X-Endpoint-Version"))
    }

    @Test
    fun `app interceptor supplies current optional authorization on every request`() = runBlocking {
        var authorized = true
        val authorizedClient = client.newBuilder().addInterceptor { chain ->
            val request = chain.request().newBuilder()
            if (authorized) request.header("Authorization", "Bearer test-access")
            chain.proceed(request.build())
        }.build()
        val source = source(authorizedClient)
        repeat(2) { server.enqueue(MockResponse().setBody("{}")) }

        source.loadJson()
        val authorizedRequest = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("Bearer test-access", authorizedRequest.getHeader("Authorization"))
        authorized = false
        source.loadJson()
        assertNull(assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).getHeader("Authorization"))
    }

    @Test
    fun `unsuccessful responses are HTTP errors`() = runBlocking {
        for (code in listOf(400, 401, 403, 404, 500)) {
            server.enqueue(MockResponse().setResponseCode(code))

            val failure = assertFailsWith<HttpException> { source().loadJson() }

            assertEquals(code, failure.code())
        }
    }

    @Test
    fun `successful HTTP statuses with document body are accepted`() = runBlocking {
        val json = """{"featureToggles":[]}"""
        for (code in listOf(200, 201, 202, 206, 299)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody(json))

            assertEquals(json, source().loadJson())
        }
    }

    @Test
    fun `successful no-content response fails as missing document`() = runBlocking {
        for (code in listOf(204, 205)) {
            server.enqueue(MockResponse().setResponseCode(code))

            assertFailsWith<IOException> { source().loadJson() }
        }
    }

    @Test
    fun `empty and malformed successful body pass unchanged to codec`() = runBlocking {
        for (body in listOf("", "{", "{}", """{"featureToggles":[]}""")) {
            server.enqueue(MockResponse().setBody(body))

            assertEquals(body, source().loadJson())
        }
    }

    @Test
    fun `cancelling suspended request cancels okhttp call and next request succeeds`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val observedClient = client.newBuilder().eventListener(object : EventListener() {
            override fun requestHeadersEnd(call: Call, request: okhttp3.Request) {
                started.complete(Unit)
            }

            override fun canceled(call: Call) {
                cancelled.complete(Unit)
            }
        }).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val source = source(observedClient)
        val pending = async { source.loadJson() }
        withTimeout(5_000) { started.await() }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

        pending.cancel()

        assertFailsWith<CancellationException> { pending.await() }
        withTimeout(5_000) { cancelled.await() }
        server.enqueue(MockResponse().setBody("{}"))
        assertEquals("{}", source.loadJson())
    }

    @Test
    fun `cancelling while response body is buffering cancels okhttp call`() = runBlocking {
        val bodyStarted = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val observedClient = client.newBuilder().eventListener(object : EventListener() {
            override fun responseBodyStart(call: Call) {
                bodyStarted.complete(Unit)
            }

            override fun canceled(call: Call) {
                cancelled.complete(Unit)
            }
        }).build()
        server.enqueue(MockResponse().setBody("""{"featureToggles":[]}""").throttleBody(1, 1, TimeUnit.SECONDS))
        val pending = async { source(observedClient).loadJson() }
        withTimeout(5_000) { bodyStarted.await() }

        pending.cancel()

        assertFailsWith<CancellationException> { pending.await() }
        withTimeout(5_000) { cancelled.await() }
    }

    private fun source(httpClient: OkHttpClient = client): RetrofitFeatureFlagsSource {
        val api = api(httpClient)
        return RetrofitFeatureFlagsSource(api::getFlags)
    }

    private fun api(httpClient: OkHttpClient = client): ApplicationFlagsApi {
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).client(httpClient).build()
        return retrofit.create(ApplicationFlagsApi::class.java)
    }

    private interface ApplicationFlagsApi {
        @GET("configuration/mobile")
        suspend fun getFlags(): Response<ResponseBody>

        @GET("v2/settings/flags")
        @Headers("Accept: application/vnd.settings+json", "X-App-Schema: 7")
        suspend fun getVersionedSettings(): Response<ResponseBody>
    }
}
