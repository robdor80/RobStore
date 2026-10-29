package es.robertodorado.robstore.catalog

import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CatalogRepositoryTest {
    @Test fun validCacheLoadsWithoutNetwork() = runTest {
        val cache = MemoryCache(fixtureSnapshot())
        val transport = FixtureTransport()
        val repository = CatalogRepository(cache, GitHubClient(transport, cache) { TEST_NOW }, { TEST_NOW }, StandardTestDispatcher(testScheduler))
        repository.initialize()
        assertEquals(cache.snapshot, repository.state.value.snapshot)
        assertTrue(transport.requests.isEmpty()); assertFalse(repository.state.value.stale)
    }
    @Test fun expiredCacheRemainsVisibleWhileNetworkRunsAndFailureKeepsIt() = runTest {
        val old = fixtureSnapshot().copy(syncedAt = TEST_NOW - CatalogPolicy.CACHE_TTL)
        val cache = MemoryCache(old)
        val transport = FixtureTransport()
        lateinit var repository: CatalogRepository
        transport.intercept = {
            assertEquals(old, repository.state.value.snapshot)
            assertTrue(repository.state.value.refreshing)
            throw IOException("offline")
        }
        repository = CatalogRepository(cache, GitHubClient(transport, cache) { TEST_NOW }, { TEST_NOW }, StandardTestDispatcher(testScheduler))
        repository.initialize()
        assertEquals(old, repository.state.value.snapshot); assertEquals(old, cache.snapshot)
        assertTrue(repository.state.value.stale); assertTrue(repository.state.value.failed); assertEquals(0, cache.saves)
    }
    @Test fun offlineFirstLaunchHasRetryableErrorWithoutInventedApps() = runTest {
        val cache = MemoryCache()
        val transport = FixtureTransport().apply { intercept = { throw IOException("offline") } }
        val repository = CatalogRepository(cache, GitHubClient(transport, cache) { TEST_NOW }, { TEST_NOW }, StandardTestDispatcher(testScheduler))
        repository.initialize()
        assertNull(repository.state.value.snapshot); assertTrue(repository.state.value.failed)
        assertFalse(repository.state.value.loading); assertFalse(repository.state.value.refreshing)
    }
    @Test fun manualRefreshIsRealThrottledAndDetectsNewRelease() = runTest {
        var time = TEST_NOW
        val cache = MemoryCache(fixtureSnapshot())
        val transport = FixtureTransport()
        val repository = CatalogRepository(cache, GitHubClient(transport, cache) { time }, { time }, StandardTestDispatcher(testScheduler))
        repository.initialize(); assertEquals(0, transport.requests.size)
        repository.refresh(); assertEquals(4, transport.requests.size)
        repository.refresh(); assertEquals(4, transport.requests.size)
        assertTrue(repository.state.value.message!!.contains("5 minutos"))
        time += CatalogPolicy.MANUAL_INTERVAL
        transport.releases = listOf(fixtureRelease(tag = "new-edition"))
        transport.assets = listOf(fixtureAsset(url = "https://github.com/robdor80/Demo/releases/download/new-edition/app-release.apk"))
        repository.refresh(); assertEquals(8, transport.requests.size)
        assertEquals("new-edition", repository.state.value.snapshot!!.apps.single().release.tag)
        assertEquals(time, cache.snapshot!!.syncedAt)
    }
    @Test fun partialFailureDoesNotReplacePreviousCatalog() = runTest {
        var time = TEST_NOW
        val old = fixtureSnapshot()
        val cache = MemoryCache(old)
        val transport = FixtureTransport().apply { intercept = { if (it.url.encodedPath.endsWith("/assets")) HttpResult(503) else null } }
        val repository = CatalogRepository(cache, GitHubClient(transport, cache) { time }, { time }, StandardTestDispatcher(testScheduler))
        repository.initialize(); repository.refresh()
        assertEquals(old, cache.snapshot); assertEquals(0, cache.saves)
        assertTrue(repository.state.value.failed)
        // Startup/retry also observes the persisted cooldown.
        time += 1000; repository.refresh(); assertEquals(4, transport.requests.size)
    }
    @Test fun completeEmptyResponseReplacesOldCatalog() = runTest {
        val cache = MemoryCache(fixtureSnapshot())
        val transport = FixtureTransport().apply { repos = emptyList() }
        val repository = CatalogRepository(cache, GitHubClient(transport, cache) { TEST_NOW }, { TEST_NOW }, StandardTestDispatcher(testScheduler))
        repository.initialize(); repository.refresh()
        assertTrue(cache.snapshot!!.apps.isEmpty()); assertFalse(repository.state.value.failed); assertEquals(1, cache.saves)
    }
    @Test fun saveFailureKeepsLastPersistedAndVisibleSnapshot() = runTest {
        val old = fixtureSnapshot()
        val cache = MemoryCache(old).apply { failSave = true }
        val transport = FixtureTransport().apply { repos = emptyList() }
        val repository = CatalogRepository(cache, GitHubClient(transport, cache) { TEST_NOW }, { TEST_NOW }, StandardTestDispatcher(testScheduler))
        repository.initialize(); repository.refresh()
        assertEquals(old, cache.snapshot); assertEquals(old, repository.state.value.snapshot)
        assertTrue(repository.state.value.failed)
    }
    @Test fun rateLimitAndManualCooldownSurviveRepositoryRestart() = runTest {
        val cache = MemoryCache(fixtureSnapshot()).apply { gate = RefreshGate(TEST_NOW - 1000, TEST_NOW + 600_000) }
        val transport = FixtureTransport()
        val repository = CatalogRepository(cache, GitHubClient(transport, cache) { TEST_NOW }, { TEST_NOW }, StandardTestDispatcher(testScheduler))
        repository.initialize(); repository.refresh()
        assertTrue(transport.requests.isEmpty()); assertEquals(TEST_NOW + 600_000, repository.state.value.nextRefreshAt)
    }
    @Test fun failedFirstLaunchRestartDuringCooldownShowsErrorInsteadOfEmptySuccess() = runTest {
        val cache = MemoryCache().apply { gate = RefreshGate(lastAttemptAt = TEST_NOW - 1000) }
        val transport = FixtureTransport()
        val repository = CatalogRepository(cache, GitHubClient(transport, cache) { TEST_NOW }, { TEST_NOW }, StandardTestDispatcher(testScheduler))
        repository.initialize()
        assertTrue(transport.requests.isEmpty()); assertNull(repository.state.value.snapshot)
        assertTrue(repository.state.value.failed); assertNotNull(repository.state.value.message)
    }
    @Test fun expiredCacheSuccessfullyRefreshesAndInitializationIsIdempotent() = runTest {
        val cache = MemoryCache(fixtureSnapshot().copy(syncedAt = TEST_NOW - CatalogPolicy.CACHE_TTL - 1))
        val transport = FixtureTransport()
        val repository = CatalogRepository(cache, GitHubClient(transport, cache) { TEST_NOW }, { TEST_NOW }, StandardTestDispatcher(testScheduler))
        repository.initialize(); repository.initialize()
        assertEquals(4, transport.requests.size); assertFalse(repository.state.value.stale)
        assertEquals(TEST_NOW, cache.snapshot!!.syncedAt)
    }
}
