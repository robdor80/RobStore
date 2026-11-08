package es.robertodorado.robstore.auth

import es.robertodorado.robstore.catalog.HttpRequest
import es.robertodorado.robstore.catalog.HttpResult
import es.robertodorado.robstore.catalog.HttpTransport
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class GitHubAuthenticatedTransportTest {
    private class Session(var token: String?) : AccessTokenProvider {
        var invalidations = 0
        override fun accessToken(): String? = token
        override fun unauthorized() { invalidations++; token = null }
    }

    private val request = HttpRequest("https://api.github.com/users/robdor80/repos".toHttpUrl(), maxBytes = 1024)

    @Test fun authenticatedCallsCarryBearerToGitHubOnly() {
        val session = Session("test-access-token")
        var received: HttpRequest? = null
        val client = GitHubAuthenticatedTransport(HttpTransport {
            received = it
            HttpResult(200, "[]".toByteArray())
        }, session)
        assertEquals(200, client.execute(request).status)
        assertEquals("Bearer test-access-token", received?.headers?.get("Authorization"))
        assertEquals(0, session.invalidations)
        assertThrows(IllegalArgumentException::class.java) {
            client.execute(HttpRequest("https://example.com/token".toHttpUrl(), maxBytes = 1024))
        }
    }

    @Test fun publicCatalogStillWorksWithoutSession() {
        val session = Session(null)
        val client = GitHubAuthenticatedTransport(HttpTransport {
            assertNull(it.headers["Authorization"])
            HttpResult(200, "[]".toByteArray())
        }, session)
        assertEquals(200, client.execute(request).status)
    }

    @Test fun invalidTokenClearsSessionButPermissionFailureDoesNot() {
        val session = Session("old-token")
        var status = 403
        val client = GitHubAuthenticatedTransport(HttpTransport { HttpResult(status) }, session)
        assertEquals(403, client.execute(request).status)
        assertEquals(0, session.invalidations)
        status = 401
        assertEquals(401, client.execute(request).status)
        assertEquals(1, session.invalidations)
        assertNull(session.token)
    }
}
