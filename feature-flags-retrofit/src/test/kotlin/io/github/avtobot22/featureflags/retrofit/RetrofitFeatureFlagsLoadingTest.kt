package io.github.avtobot22.featureflags.retrofit

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
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RetrofitFeatureFlagsLoadingTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build()
    private val guestMode = Feature.boolean("dashboard.guestMode")
    private var localCalls = 0

    @AfterEach
    fun close() {
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun `valid remote configuration is published and only empty remote uses local`() = runBlocking {
        val system = system()
        server.enqueue(MockResponse().setBody(fixture("valid-nonempty.json")))

        assertEquals(RefreshResult.Applied(1, ConfigOrigin.REMOTE, 4), system.manager.refresh())
        assertTrue(system.reader.get(guestMode))
        assertTrue(system.reader.get(Feature.boolean("future.unknown")))
        assertEquals(0, localCalls)
        server.enqueue(MockResponse().setBody(fixture("empty.json")))

        assertEquals(RefreshResult.Applied(2, ConfigOrigin.BUNDLED_ASSET, 0), system.manager.refresh())
        assertFalse(system.reader.get(guestMode))
        assertEquals(1, localCalls)
    }

    @Test
    fun `HTTP errors do not fallback and preserve previous publication`() = runBlocking {
        val system = system()
        server.enqueue(MockResponse().setBody(fixture("valid-nonempty.json")))
        system.manager.refresh()

        for (code in listOf(204, 205, 401, 500)) {
            server.enqueue(MockResponse().setResponseCode(code))

            val result = assertIs<RefreshResult.Failure>(system.manager.refresh())
            val error = assertIs<RefreshError.Transport>(result.error)

            if (code == 204 || code == 205) {
                assertIs<IOException>(error.cause)
            } else {
                assertEquals(code, assertIs<HttpException>(error.cause).code())
            }
            assertTrue(system.reader.get(guestMode))
            assertEquals(Readiness.Ready(1, ConfigOrigin.REMOTE), system.reader.readiness.value)
        }
        assertEquals(0, localCalls)
    }

    @Test
    fun `HTTP 200 with invalid document is invalid configuration without fallback`() = runBlocking {
        val system = system()
        for (body in listOf("", "{", fixture("duplicates.json"), fixture("invalid-enabled.json"))) {
            server.enqueue(MockResponse().setBody(body))

            val error = assertIs<RefreshError.InvalidConfiguration>(
                assertIs<RefreshResult.Failure>(system.manager.refresh()).error,
            )

            assertEquals(ConfigOrigin.REMOTE, error.origin)
            assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
        }
        assertEquals(0, localCalls)
    }

    @Test
    fun `network timeout is transport failure without local fallback`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val system = system()

        val error = assertIs<RefreshError.Transport>(assertIs<RefreshResult.Failure>(system.manager.refresh()).error)

        assertIs<IOException>(error.cause)
        assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
        assertEquals(0, localCalls)
    }

    private fun system(): FeatureFlagsSystem {
        val api = Retrofit.Builder().baseUrl(server.url("/")).client(client).build()
            .create(ApplicationFlagsApi::class.java)
        return FeatureFlagsSystem(
            remote = RetrofitFeatureFlagsSource(api::getFlags),
            codec = JsonConfigurationCodec,
            fallback = FallbackPolicy.OnRemoteEmpty,
            local = FeatureFlagsSource {
                localCalls++
                fixture("empty.json")
            },
        )
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/fixtures/$name")).readText()

    private interface ApplicationFlagsApi {
        @GET("settings/flags")
        suspend fun getFlags(): Response<ResponseBody>
    }
}
