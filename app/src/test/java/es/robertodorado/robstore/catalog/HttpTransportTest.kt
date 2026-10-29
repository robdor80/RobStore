package es.robertodorado.robstore.catalog

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test

/** Interceptors return local bodies and never proceed to the network. */
class HttpTransportTest {
    private fun transport(bytes: ByteArray, length: Long, status: Int = 200): OkHttpTransport {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().header("Authorization"))
            assertEquals("2022-11-28", chain.request().header("X-GitHub-Api-Version"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_2).code(status).message("Fixture")
                .body(object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = length
                    override fun source(): BufferedSource = Buffer().write(bytes)
                }).build()
        }.build()
        return OkHttpTransport(client)
    }
    private fun request(limit: Int = 10) = HttpRequest("https://api.github.com/users/robdor80/repos".toHttpUrl(), maxBytes = limit)

    @Test fun oversizedDeclaredLengthIsRejectedBeforeReading() {
        val transport = transport(byteArrayOf(), 11)
        assertThrows(ResourceTooLarge::class.java) { transport.execute(request()) }
    }
    @Test fun streamingWithoutContentLengthStillEnforcesLimit() {
        val transport = transport(ByteArray(11), -1)
        assertThrows(ResourceTooLarge::class.java) { transport.execute(request()) }
    }
    @Test fun exactLimitIsAcceptedAndStatusIsPreserved() {
        val response = transport(ByteArray(10), -1, 302).execute(request())
        assertEquals(302, response.status); assertEquals(10, response.body.size)
    }
}
