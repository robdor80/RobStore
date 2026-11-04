package es.robertodorado.robstore.download

import es.robertodorado.robstore.catalog.*
import org.junit.Assert.*
import org.junit.Test

class DownloadMetadataTest {
    @Test fun freshSelectedRepositoryReleaseManifestAndAssetMustMatchSnapshot() {
        val transport = FixtureTransport()
        val discovery = CatalogDiscovery(GitHubClient(transport, MemoryCache()) { TEST_NOW }) { TEST_NOW }
        val app = fixtureSnapshot().apps.single()
        discovery.revalidate(app)
        assertEquals(4, transport.requests.size)
        for (altered in listOf(app.copy(repositoryId = 999), app.copy(packageName = "es.other.app"),
            app.copy(release = app.release.copy(id = 999)), app.copy(apk = app.apk.copy(id = 999)),
            app.copy(apk = app.apk.copy(sha256 = "b".repeat(64))), app.copy(apk = app.apk.copy(size = 9)))) {
            assertThrows(InvalidMetadata::class.java) { discovery.revalidate(altered) }
        }
    }
    @Test fun changedOwnerPrivateArchivedTopicOrLatestReleaseBlocksDownload() {
        val app = fixtureSnapshot().apps.single()
        for (repo in listOf(fixtureRepo(owner = "other"), fixtureRepo(private = true), fixtureRepo(archived = true), fixtureRepo(topics = emptyList()))) {
            val transport = FixtureTransport().apply { repos = listOf(repo) }
            assertThrows(InvalidMetadata::class.java) {
                CatalogDiscovery(GitHubClient(transport, MemoryCache()) { TEST_NOW }) { TEST_NOW }.revalidate(app)
            }
        }
        val transport = FixtureTransport().apply { releases = listOf(fixtureRelease(id = 99)) }
        assertThrows(InvalidMetadata::class.java) {
            CatalogDiscovery(GitHubClient(transport, MemoryCache()) { TEST_NOW }) { TEST_NOW }.revalidate(app)
        }
    }
}
