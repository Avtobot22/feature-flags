package io.github.avtobot22.featureflags.retrofit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Retrofit
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
    fun `request uses GET path and required headers without body query or implicit auth`() = runBlocking {
        val json = """{"featureToggles":[],"title":"Флаги"}"""
        server.enqueue(MockResponse().setBody(json).setHeader("Content-Type", "application/json"))

        assertEquals(json, source().loadJson())

        val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/api/tv/feature-toggles", request.path)
        assertEquals(0, request.bodySize)
        assertEquals("application/json", request.getHeader("Content-Type"))
        assertEquals("1", request.getHeader("X-Endpoint-Version"))
        assertNull(request.getHeader("Authorization"))
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
    fun `non-200 responses including successful no-content are protocol errors`() = runBlocking {
        for (code in listOf(201, 204, 205, 401, 403, 404, 500)) {
            server.enqueue(MockResponse().setResponseCode(code))

            val failure = assertFailsWith<HttpException> { source().loadJson() }

            assertEquals(code, failure.code())
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
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).client(httpClient).build()
        return RetrofitFeatureFlagsSource(retrofit.create(FeatureTogglesApi::class.java))
    }
}
