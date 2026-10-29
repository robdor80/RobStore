package es.robertodorado.robstore.catalog

import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class GitHubClientTest {
    private val transport = FixtureTransport()
    private val cache = MemoryCache()
    private val client = GitHubClient(transport, cache) { TEST_NOW }
    private fun fetch() = client.discovery().repositories()

    @Test fun conditionalRequestsUseEtagAndReuse304Body() {
        transport.intercept = { request ->
            if (request.headers["If-None-Match"] == "\"repos-v1\"") HttpResult(304)
            else HttpResult(200, "[]".toByteArray(), mapOf("ETag" to "\"repos-v1\""))
        }
        assertTrue(fetch().isEmpty()); assertTrue(fetch().isEmpty())
        assertEquals("\"repos-v1\"", transport.requests.last().headers["If-None-Match"])
    }
    @Test fun unexpected304IsNotSuccess() {
        transport.intercept = { HttpResult(304) }
        assertThrows(IncompleteCatalog::class.java) { fetch() }
    }
    @Test fun primaryRateLimitPersistsResetAndAvoidsAnotherRequest() {
        val reset = TEST_NOW / 1000 + 3600
        transport.intercept = { HttpResult(403, headers = mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to reset.toString())) }
        val failure = assertThrows(GitHubFailure::class.java) { fetch() }
        assertEquals(reset * 1000, failure.retryAt)
        assertEquals(reset * 1000, cache.gate.blockedUntil)
        assertThrows(GitHubFailure::class.java) { fetch() }
        assertEquals(1, transport.requests.size)
        val restarted = GitHubClient(transport, cache) { TEST_NOW }
        assertThrows(GitHubFailure::class.java) { restarted.discovery().repositories() }
        assertEquals(1, transport.requests.size)
    }
    @Test fun secondaryLimitRespectsRetryAfterAndConservativeFallback() {
        for (status in listOf(403, 429)) {
            cache.gate = RefreshGate()
            transport.intercept = { HttpResult(status, headers = mapOf("Retry-After" to "120")) }
            assertThrows(GitHubFailure::class.java) { fetch() }
            assertEquals(TEST_NOW + 120_000, cache.gate.blockedUntil)
        }
        cache.gate = RefreshGate()
        transport.intercept = { HttpResult(429) }
        assertThrows(GitHubFailure::class.java) { fetch() }
        assertEquals(TEST_NOW + 900_000, cache.gate.blockedUntil)
    }
    @Test fun retryAfterHttpDateIsSupported() {
        val date = java.time.Instant.ofEpochMilli(TEST_NOW + 180_000).atZone(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        transport.intercept = { HttpResult(429, headers = mapOf("Retry-After" to date)) }
        assertThrows(GitHubFailure::class.java) { fetch() }
        assertEquals(TEST_NOW + 180_000, cache.gate.blockedUntil)
    }
    @Test fun successfulLastQuotaResponseIsUsedButBlocksFurtherRequests() {
        transport.intercept = { HttpResult(200, "[]".toByteArray(), mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to (TEST_NOW / 1000 + 600).toString())) }
        assertTrue(fetch().isEmpty())
        assertThrows(GitHubFailure::class.java) { fetch() }
        assertEquals(1, transport.requests.size)
    }
    @Test fun contentRefsAndPathsAreEncodedWithoutChangingOrigin() {
        val url = contentUrl("Demo", "releases/2026 #1", "robstore/icon.png")
        assertEquals("api.github.com", url.host)
        assertEquals("releases/2026 #1", url.queryParameter("ref"))
        assertEquals("/repos/robdor80/Demo/contents/robstore/icon.png", url.encodedPath)
        assertThrows(IllegalArgumentException::class.java) { contentUrl("../Other", "tag", "icon.png") }
    }
    @Test fun productionTransportRejectsExternalOrCleartextOriginsBeforeNetwork() {
        val real = OkHttpTransport()
        for (url in listOf("http://api.github.com/", "https://example.com/", "https://api.github.com:444/", "https://user@api.github.com/")) {
            val parsed = url.toHttpUrl()
            assertThrows(IllegalArgumentException::class.java) { real.execute(HttpRequest(parsed, maxBytes = 10)) }
        }
    }
}
