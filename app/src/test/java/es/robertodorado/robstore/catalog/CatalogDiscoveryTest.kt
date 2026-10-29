package es.robertodorado.robstore.catalog

import java.io.IOException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CatalogDiscoveryTest {
    private val transport = FixtureTransport()
    private val cache = MemoryCache()
    private val client = GitHubClient(transport, cache) { TEST_NOW }
    private fun fetch() = CatalogDiscovery(client) { TEST_NOW }.fetch()
    private inline fun <reified T : Throwable> fails(block: () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") } catch (failure: Throwable) {
            if (failure !is T) throw failure
        }
    }

    @Test fun discoversOnlyPublicAuthorizedUnarchivedTopicRepositories() {
        transport.repos = listOf(fixtureRepo(), fixtureRepo(11, owner = "intruder"), fixtureRepo(12, private = true),
            fixtureRepo(13, archived = true), fixtureRepo(14, topics = emptyList()), fixtureRepo(15, topics = listOf("robstore-app-extra")),
            JsonObject(fixtureRepo(16) + ("visibility" to JsonPrimitive("internal"))))
        assertEquals(listOf(10L), fetch().apps.map { it.repositoryId })
        assertEquals(4, transport.requests.size)
    }
    @Test fun paginatesRepositoriesAndReleasesAndAssets() {
        transport.repos = (1..100).map { fixtureRepo(it.toLong(), topics = emptyList()) } + fixtureRepo(101)
        transport.releases = (1..101).map { fixtureRelease(it.toLong(), draft = it < 101) }
        transport.assets = (1..101).map { fixtureAsset(it.toLong(), name = if (it == 101) "app-release.apk" else "other-$it.apk") }
        assertEquals(101L, fetch().apps.single().repositoryId)
        assertEquals(3, transport.requests.count { it.url.queryParameter("page") == "2" })
    }
    @Test fun repeatedPagesAbortInsteadOfSilentlyTruncating() {
        transport.repos = List(101) { fixtureRepo() }
        fails<IncompleteCatalog> { fetch() }
    }
    @Test fun maximumPageLimitAbortsIncompleteSnapshot() {
        transport.repos = (1..1001).map { fixtureRepo(it.toLong(), topics = emptyList()) }
        fails<IncompleteCatalog> { fetch() }
        assertEquals(10, transport.requests.size)
    }
    @Test fun requestBudgetPreventsQuotaLoops() {
        transport.repos = (1..30).map { fixtureRepo(it.toLong()) }
        fails<IncompleteCatalog> { fetch() }
        assertEquals(50, transport.requests.size)
    }
    @Test fun manifestMissingExcludesAppWithDiagnostic() {
        transport.manifest = null
        assertTrue(fetch().apps.isEmpty()); assertTrue(fetch().diagnostics.single().contains("robstore.json"))
    }
    @Test fun malformedManifestIsIsolatedFromValidRepository() {
        transport.repos = listOf(fixtureRepo(), fixtureRepo(11, name = "Broken"))
        transport.intercept = { if (it.url.encodedPath.contains("/Broken/contents")) HttpResult(200, "{bad".toByteArray()) else null }
        val result = fetch()
        assertEquals(1, result.apps.size); assertEquals(1, result.diagnostics.size)
    }
    @Test fun incompatibleSchemaAndInvalidFieldsAreRejected() {
        val cases = listOf("schemaVersion" to JsonPrimitive(2), "schemaVersion" to JsonPrimitive("1"),
            "name" to JsonPrimitive(""), "name" to JsonNull, "packageName" to JsonPrimitive("invalid"),
            "packageName" to JsonPrimitive("es.1app"), "description" to JsonPrimitive("x".repeat(4001)),
            "apkAssetName" to JsonPrimitive("../app.apk"), "apkAssetName" to JsonPrimitive("App.zip"))
        cases.forEach { change -> transport.manifest = fixtureManifest(change); assertTrue(change.toString(), fetch().apps.isEmpty()) }
    }
    @Test fun unsafePathsAndExternalUrlsAreRejected() {
        listOf("../icon.png", "/icon.png", "robstore/../icon.png", "https://evil.test/a.png", "//evil/a.png",
            "robstore\\icon.png", "robstore/%2e%2e/icon.png", "robstore//icon.png", "file:icon.png", "icon.svg", "./icon.png").forEach {
            transport.manifest = fixtureManifest("icon" to JsonPrimitive(it)); assertTrue(it, fetch().apps.isEmpty())
        }
    }
    @Test fun oversizedAndInvalidUtf8ManifestAreControlled() {
        transport.manifest = ByteArray(CatalogPolicy.MAX_MANIFEST_BYTES + 1)
        assertTrue(fetch().apps.isEmpty())
        transport.manifest = byteArrayOf(0xc3.toByte(), 0x28)
        assertTrue(fetch().apps.isEmpty())
    }
    @Test fun excessiveJsonNestingIsControlledBeforeParsing() {
        transport.manifest = ("[".repeat(1000) + "0" + "]".repeat(1000)).toByteArray()
        assertTrue(fetch().apps.isEmpty())
        transport.intercept = { HttpResult(200, ("[".repeat(1000) + "0" + "]".repeat(1000)).toByteArray()) }
        fails<IncompleteCatalog> { fetch() }
    }
    @Test fun optionalDescriptionAndDefaultIconAreSupported() {
        val json = catalogJson.parseToJsonElement(fixtureManifest().decodeToString()).jsonObject
        transport.manifest = JsonObject(json.filterKeys { it != "description" && it != "icon" }).toString().toByteArray()
        assertEquals("", fetch().apps.single().description)
        assertEquals("robstore/icon.png", fetch().apps.single().icon!!.path)
    }
    @Test fun noReleasesAndOnlyDraftPrereleaseOrUnpublishedAreExcluded() {
        listOf(emptyList(), listOf(fixtureRelease(draft = true)), listOf(fixtureRelease(prerelease = true)),
            listOf(fixtureRelease(date = null))).forEach { transport.releases = it; assertTrue(fetch().apps.isEmpty()) }
    }
    @Test fun latestStableUsesPublishedDateAcrossAllPagesWithoutSemver() {
        transport.releases = listOf(fixtureRelease(25, "v99", "2026-09-01T00:00:00Z"), fixtureRelease(),
            fixtureRelease(29, "v100", "2026-10-29T00:00:00Z", prerelease = true))
        val app = fetch().apps.single()
        assertEquals("edition-october", app.release.tag)
        val request = transport.requests.single { it.url.encodedPath.endsWith("/robstore.json") }
        assertEquals("edition-october", request.url.queryParameter("ref"))
        assertEquals("application/vnd.github.raw+json", request.headers["Accept"])
    }
    @Test fun latestWithoutApkDoesNotFallBackToOlderRelease() {
        transport.releases = listOf(fixtureRelease(), fixtureRelease(19, "old", "2026-01-01T00:00:00Z"))
        transport.intercept = { if (it.url.encodedPath.endsWith("/20/assets")) HttpResult(200, "[]".toByteArray()) else null }
        assertTrue(fetch().apps.isEmpty())
        assertFalse(transport.requests.any { it.url.encodedPath.endsWith("/19/assets") })
    }
    @Test fun missingAmbiguousAndDifferentApksNeverChooseFirst() {
        listOf(emptyList(), listOf(fixtureAsset(name = "different.apk")), listOf(fixtureAsset(), fixtureAsset(31))).forEach {
            transport.assets = it; assertTrue(fetch().apps.isEmpty())
        }
        transport.assets = listOf(fixtureAsset(31, "different.apk"), fixtureAsset())
        assertEquals("app-release.apk", fetch().apps.single().apk.name)
    }
    @Test fun apkMetadataMustBeConsistentAndOfficial() {
        listOf(fixtureAsset(size = 0), fixtureAsset(size = CatalogPolicy.MAX_APK_BYTES + 1), fixtureAsset(state = "starter"),
            fixtureAsset(url = "http://github.com/robdor80/Demo/releases/download/edition-october/app-release.apk"),
            fixtureAsset(url = "https://evil.test/app-release.apk"),
            fixtureAsset(url = "https://github.com/intruder/Demo/releases/download/edition-october/app-release.apk"),
            fixtureAsset(url = "https://github.com/robdor80/Demo/releases/download/old/app-release.apk"),
            fixtureAsset(url = "https://github.com/robdor80/Demo/releases/download/edition-october/app-release.apk?token=x")).forEach {
            transport.assets = listOf(it); assertTrue(it.toString(), fetch().apps.isEmpty())
        }
    }
    @Test fun validApkModelsDigestWithoutClaimingVerification() {
        val app = fetch().apps.single()
        assertEquals("a".repeat(64), app.apk.sha256)
        assertEquals(ApkVerification.NOT_PERFORMED, app.apk.verification)
        assertEquals(1234L, app.apk.size)
        assertEquals(app.release.tag, app.icon!!.tag)
        assertEquals(app.release.id, app.icon.releaseId)
        assertEquals("es.robertodorado.demo", app.packageName)
    }
    @Test fun malformedReleaseDateNeverFallsBack() {
        transport.releases = listOf(fixtureRelease(date = "invalid"), fixtureRelease(19, "old", "2026-01-01T00:00:00Z"))
        assertTrue(fetch().apps.isEmpty())
    }
    @Test fun malformedPagesAndHttpFailuresAbort() {
        listOf(HttpResult(500), HttpResult(302), HttpResult(200, "{}".toByteArray()), HttpResult(200, "broken".toByteArray())).forEach { response ->
            transport.intercept = { response }; fails<IOException> { fetch() }
        }
    }
    @Test fun networkFailureAbortsWithoutRetries() {
        transport.intercept = { throw IOException("offline") }
        fails<IOException> { fetch() }; assertEquals(1, transport.requests.size)
    }
    @Test fun genuineEmptyCatalogIsSuccessful() {
        transport.repos = emptyList()
        val snapshot = fetch(); assertTrue(snapshot.apps.isEmpty()); assertEquals(TEST_NOW, snapshot.syncedAt)
    }
}
