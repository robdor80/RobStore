package es.robertodorado.robstore.update

import es.robertodorado.robstore.catalog.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SelfUpdateDiscoveryTest {
    private val transport = UpdateTransport()
    private val cache = MemoryCache()
    private fun discovery() = SelfUpdateDiscovery(GitHubClient(transport, cache) { TEST_NOW }) { TEST_NOW }
    private fun fetch(channel: SelfUpdateChannel = SelfUpdateChannel.RELEASE) = discovery().fetch(channel)

    @Test fun effectivePackageSelectsOnlyExactIdentity() {
        assertEquals(SelfUpdateChannel.RELEASE, SelfUpdateChannel.identify("es.robertodorado.robstore"))
        assertEquals(SelfUpdateChannel.DEV, SelfUpdateChannel.identify("es.robertodorado.robstore.debug"))
        assertNull(SelfUpdateChannel.identify("es.robertodorado.robstore.other"))
    }
    @Test fun releaseWorksWithoutTopicAndReadsOnlyPublishedTagContract() {
        assertEquals(2L, fetch()!!.release.versionCode)
        assertTrue(transport.requests.none { it.url.encodedPath.endsWith("/repos") })
        val contract = transport.requests.single { it.url.encodedPath.endsWith("robstore-update.json") }
        assertEquals("refs/tags/edition-october", contract.url.queryParameter("ref"))
    }
    @Test fun draftAndPrereleaseAreExcludedFromStable() {
        for (release in listOf(updateRelease(draft = true), updateRelease(prerelease = true))) {
            transport.releases = listOf(release); assertNull(fetch())
        }
    }
    @Test fun devRequiresExplicitDevPrereleaseAndNeverStableRelease() {
        assertNull(fetch(SelfUpdateChannel.DEV))
        transport.releases = listOf(updateRelease(prerelease = true))
        assertNull(fetch(SelfUpdateChannel.DEV))
        transport.manifest = updateManifest("channel" to JsonPrimitive("dev"), "packageName" to JsonPrimitive(SelfUpdateChannel.DEV.packageName))
        assertEquals(SelfUpdateChannel.DEV, fetch(SelfUpdateChannel.DEV)!!.channel)
        assertNull(fetch())
    }
    @Test fun missingContractDoesNotInventVersionFromTag() {
        transport.manifest = null; assertThrows(InvalidMetadata::class.java) { fetch() }
    }
    @Test fun incompatibleOrUnknownMetadataCannotSelectApk() {
        val invalid = listOf("schemaVersion" to JsonPrimitive(2), "versionCode" to JsonPrimitive("2"),
            "versionCode" to JsonPrimitive(0), "versionName" to JsonPrimitive(""), "packageName" to JsonPrimitive("es.other"),
            "channel" to JsonPrimitive("beta"), "apkAssetName" to JsonPrimitive("../RobStore.apk"), "sha256" to JsonPrimitive("bad"))
        for (change in invalid) { transport.manifest = updateManifest(change); assertThrows(InvalidMetadata::class.java) { fetch() } }
        transport.manifest = "{}".toByteArray(); assertThrows(InvalidMetadata::class.java) { fetch() }
    }
    @Test fun absentHashAndContradictoryGithubHashAreRejected() {
        transport.manifest = updateManifest().decodeToString().replace(Regex(",?\"sha256\":\"[a-f0-9]+\""), "").toByteArray()
        assertThrows(InvalidMetadata::class.java) { fetch() }
        transport.manifest = updateManifest("sha256" to JsonPrimitive("b".repeat(64))); assertThrows(InvalidMetadata::class.java) { fetch() }
    }
    @Test fun officialContractHashSufficesIfGithubHasNoDigest() {
        transport.assets = transport.assets.map { JsonObject(it - "digest") }
        assertNotNull(fetch())
    }
    @Test fun wrongOriginOrAssetStateIsRejected() {
        for (key in listOf("browser_download_url", "state")) {
            transport.assets = listOf(JsonObject(transport.assets.single() + (key to JsonPrimitive("incorrect"))))
            assertThrows(InvalidMetadata::class.java) { fetch() }
        }
        transport.repo = JsonObject(transport.repo + ("full_name" to JsonPrimitive("other/RobStore")))
        assertThrows(InvalidMetadata::class.java) { fetch() }
    }
    @Test fun changedReleaseIsRejectedAtRevalidation() {
        val candidate = fetch()!!
        transport.manifest = updateManifest("versionCode" to JsonPrimitive(3))
        assertThrows(InvalidMetadata::class.java) { discovery().revalidate(candidate) }
    }
    @Test fun revalidateOnlyReadsChosenContractDespiteHistoricReleases() {
        val selected = fetch()!!
        val old = JsonObject(updateRelease() + mapOf(
            "id" to JsonPrimitive(21),
            "tag_name" to JsonPrimitive("historical"),
            "html_url" to JsonPrimitive("https://github.com/robdor80/RobStore/releases/tag/historical")))
        transport.releases = listOf(old, updateRelease())
        transport.intercept = { request ->
            if (request.url.queryParameter("ref") == "refs/tags/historical") {
                throw AssertionError("Historic contracts must not be re-read during update")
            }
            null
        }
        transport.requests.clear()
        discovery().revalidate(selected)
        assertEquals(1, transport.requests.count { it.url.encodedPath.endsWith("robstore-update.json") })
        assertEquals(1, transport.requests.count { it.url.encodedPath.endsWith("/assets") })
    }
    @Test fun codeRatherThanTagOrPublicationDateSelectsNewest() {
        val older = JsonObject(updateRelease() + mapOf("id" to JsonPrimitive(21), "tag_name" to JsonPrimitive("v999"),
            "html_url" to JsonPrimitive("https://github.com/robdor80/RobStore/releases/tag/v999")))
        transport.releases = listOf(older, updateRelease())
        transport.intercept = { req -> when {
            req.url.queryParameter("ref") == "refs/tags/v999" -> HttpResult(200, updateManifest("versionCode" to JsonPrimitive(1)))
            req.url.encodedPath.endsWith("/21/assets") -> HttpResult(200, JsonArray(listOf(JsonObject(transport.assets.single() +
                ("browser_download_url" to JsonPrimitive("https://github.com/robdor80/RobStore/releases/download/v999/RobStore.apk"))))).toString().toByteArray())
            else -> null
        } }
        assertEquals(2L, fetch()!!.release.versionCode)
    }
    @Test fun missingTagContractNeverFallsBackToSameNamedBranch() {
        transport.releases = listOf(JsonObject(updateRelease() + mapOf("tag_name" to JsonPrimitive("main"),
            "html_url" to JsonPrimitive("https://github.com/robdor80/RobStore/releases/tag/main"))))
        transport.intercept = { request ->
            if (request.url.encodedPath.endsWith("robstore-update.json")) {
                assertEquals("refs/tags/main", request.url.queryParameter("ref"))
                HttpResult(404)
            } else null
        }
        assertThrows(InvalidMetadata::class.java) { fetch() }
        assertEquals(1, transport.requests.count { it.url.encodedPath.endsWith("robstore-update.json") })
    }
    @Test fun sharedQuotaBlocksAllSubsequentClients() {
        transport.intercept = { HttpResult(429, headers = mapOf("Retry-After" to "600")) }
        assertThrows(GitHubFailure::class.java) { fetch() }
        val count = transport.requests.size
        assertThrows(GitHubFailure::class.java) { GitHubClient(transport, cache) { TEST_NOW }.discovery().repositories() }
        assertEquals(count, transport.requests.size)
        assertTrue(cache.gate.blockedUntil >= TEST_NOW + 600_000)
    }
}
