package es.robertodorado.robstore.catalog

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogCacheTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun persistsVersionedSnapshotAndCooldownAcrossInstances() {
        val directory = temp.newFolder()
        val cache = FileCatalogCache(directory)
        val snapshot = fixtureSnapshot()
        cache.save(snapshot)
        assertTrue(File(directory, "catalog-v1.json").readText().contains("\"schemaVersion\":1"))
        cache.saveGate(RefreshGate(TEST_NOW, TEST_NOW + 600_000))
        val restarted = FileCatalogCache(directory)
        assertEquals(snapshot, restarted.load()); assertEquals(RefreshGate(TEST_NOW, TEST_NOW + 600_000), restarted.readGate())
        assertFalse(File(directory, "catalog-v1.json.tmp").exists())
    }
    @Test fun cachedStoreTileDisappearsWithoutLosingOtherCachedApps() {
        val directory = temp.newFolder()
        val cache = FileCatalogCache(directory)
        val original = fixtureSnapshot()
        val app = original.apps.single()
        val store = app.copy(repositoryId = 200, repositoryName = "RobStore",
            repositoryUrl = "https://github.com/robdor80/RobStore", name = "RobStore",
            packageName = "es.robertodorado.robstore",
            apk = app.apk.copy(downloadUrl = "https://github.com/robdor80/RobStore/releases/download/edition-october/app-release.apk"),
            icon = app.icon?.copy(repository = "RobStore"))
        val cached = original.copy(apps = listOf(app, store))
        assertTrue(CatalogValidation.snapshot(cached))
        cache.save(cached)
        assertEquals(listOf(app), FileCatalogCache(directory).load()!!.apps)
        assertThrows(InvalidMetadata::class.java) { CatalogValidation.select(cached, store.repositoryId) }
    }
    @Test fun corruptOrUnknownVersionCacheIsIgnored() {
        val directory = temp.newFolder()
        val file = File(directory, "catalog-v1.json")
        file.writeText("broken")
        assertNull(FileCatalogCache(directory).load())
        file.writeText("""{"schemaVersion":2,"syncedAt":123,"apps":[]}""")
        assertNull(FileCatalogCache(directory).load())
        file.writeBytes(ByteArray(4 * 1024 * 1024 + 1))
        assertNull(FileCatalogCache(directory).load())
    }
    @Test fun failedOversizedWriteDoesNotDamagePriorSnapshot() {
        val cache = FileCatalogCache(temp.newFolder())
        val old = fixtureSnapshot()
        cache.save(old)
        assertThrows(java.io.IOException::class.java) { cache.save(old.copy(diagnostics = listOf("x".repeat(4 * 1024 * 1024)))) }
        assertEquals(old, cache.load())
    }
    @Test fun interruptedTemporaryWriteDoesNotReplaceFinalFile() {
        val directory = temp.newFolder()
        val cache = FileCatalogCache(directory)
        val old = fixtureSnapshot(); cache.save(old)
        File(directory, "catalog-v1.json.tmp").writeText("interrupted")
        assertEquals(old, FileCatalogCache(directory).load())
    }
    @Test fun publicationDateOverflowInTamperedCacheIsRejectedWithoutCrash() {
        val cache = FileCatalogCache(temp.newFolder())
        val snapshot = fixtureSnapshot()
        val app = snapshot.apps.single()
        cache.save(snapshot.copy(apps = listOf(app.copy(release = app.release.copy(publishedAt = "+999999999-12-31T23:59:59Z")))))
        assertNull(cache.load())
    }

}
