package es.robertodorado.robstore.update

import es.robertodorado.robstore.catalog.*
import es.robertodorado.robstore.inventory.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SelfUpdateRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private var time = TEST_NOW
    private val transport = UpdateTransport()
    private val quota = MemoryCache()
    private var local: InstallationQuery = ownInstalled()
    private fun repository(cache: FileCatalogCache, dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        channel: SelfUpdateChannel = SelfUpdateChannel.RELEASE) = SelfUpdateRepository(channel.packageName,
        InstalledApplications { local }, cache, quota, SelfUpdateDiscovery(GitHubClient(transport, quota) { time }) { time }, { time }, dispatcher)

    @Test fun greaterEqualLowerAndUnreadableVersions() = runTest {
        val repository = repository(FileCatalogCache(temp.newFolder()), StandardTestDispatcher(testScheduler))
        repository.initialize(); assertTrue(repository.state.value.available)
        for (code in listOf(2L, 3L, null)) {
            local = ownInstalled(code); repository.observeInstalled(); assertFalse(repository.state.value.available)
        }
    }
    @Test fun defaultDevIsUnavailableAndMakesNoRequests() = runTest {
        local = ownInstalled(channel = SelfUpdateChannel.DEV)
        val repository = repository(FileCatalogCache(temp.newFolder()), StandardTestDispatcher(testScheduler), SelfUpdateChannel.DEV)
        repository.initialize(); repository.check()
        assertFalse(repository.state.value.enabled); assertFalse(repository.state.value.available)
        assertNull(repository.state.value.candidate); assertTrue(transport.requests.isEmpty())
    }
    @Test fun manualUpdateCheckIsImmediateButAutomaticTtlSurvivesRestart() = runTest {
        val cache = FileCatalogCache(temp.newFolder())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = repository(cache, dispatcher)
        first.initialize(); val count = transport.requests.size
        first.initialize(); first.check(); assertTrue(transport.requests.size > count)
        assertEquals(0L, first.state.value.nextCheckAt)
        val afterManual = transport.requests.size
        repository(cache, dispatcher).initialize(); assertEquals(afterManual, transport.requests.size)
        time += CatalogPolicy.CACHE_TTL
        repository(cache, dispatcher).initialize(); assertTrue(transport.requests.size > afterManual)
    }
    @Test fun offlineFailureKeepsLastValidCandidateAndTimestampAcrossRestart() = runTest {
        val cache = FileCatalogCache(temp.newFolder()); val dispatcher = StandardTestDispatcher(testScheduler)
        val first = repository(cache, dispatcher); first.initialize()
        val previous = first.state.value
        time += CatalogPolicy.CACHE_TTL
        transport.intercept = { throw IOException("offline") }
        val restart = repository(cache, dispatcher); restart.initialize()
        assertEquals(previous.candidate, restart.state.value.candidate)
        assertEquals(previous.checkedAt, restart.state.value.checkedAt)
        assertTrue(restart.state.value.failed); assertTrue(restart.state.value.available)
    }
    @Test fun quotaIsSharedButCatalogCacheIsIndependent() = runTest {
        quota.gate = RefreshGate(blockedUntil = time + 900_000)
        val repository = repository(FileCatalogCache(temp.newFolder()), StandardTestDispatcher(testScheduler))
        repository.initialize(); assertTrue(transport.requests.isEmpty())
        assertNull(repository.state.value.candidate)
        time += 900_001
        repository.check(); assertTrue(repository.state.value.available)
        assertNull(quota.snapshot); assertEquals(0, quota.saves)
    }
    @Test fun restartObservesRealVersionWithoutDeclaringInstallerSuccess() = runTest {
        val cache = FileCatalogCache(temp.newFolder()); val dispatcher = StandardTestDispatcher(testScheduler)
        repository(cache, dispatcher).initialize()
        val unchanged = repository(cache, dispatcher); unchanged.initialize(); assertTrue(unchanged.state.value.available)
        local = ownInstalled(2)
        val updated = repository(cache, dispatcher); updated.initialize()
        assertEquals(2L, updated.state.value.installed!!.versionCode); assertFalse(updated.state.value.available)
    }
    @Test fun invalidRemoteMetadataKeepsPreviousValidInformation() = runTest {
        val repository = repository(FileCatalogCache(temp.newFolder()), StandardTestDispatcher(testScheduler))
        repository.initialize(); val previous = repository.state.value
        time += CatalogPolicy.MANUAL_INTERVAL
        transport.manifest = "{}".toByteArray()
        repository.check()
        assertTrue(repository.state.value.failed)
        assertEquals(previous.candidate, repository.state.value.candidate)
        assertEquals(previous.checkedAt, repository.state.value.checkedAt)
    }
    @Test fun comparisonDoesNotTruncateLongVersionCodes() {
        val candidate = ownCandidate().let { it.copy(release = it.release.copy(versionCode = 4_000_000_000L)) }
        val state = SelfUpdateState(SelfUpdateChannel.RELEASE, true, ownInstalled(3_000_000_000L).application, candidate)
        assertTrue(state.available)
        assertFalse(state.copy(installed = ownInstalled(5_000_000_000L).application).available)
    }
    @Test fun invalidCacheAndMissingReleaseAreNotNewVersions() = runTest {
        val directory = temp.newFolder(); java.io.File(directory, "self-update-v1.json").writeText("broken")
        transport.releases = emptyList()
        val repository = repository(FileCatalogCache(directory), StandardTestDispatcher(testScheduler))
        repository.initialize(); assertNull(repository.state.value.candidate)
        assertFalse(repository.state.value.available); assertEquals(time, repository.state.value.checkedAt)
    }
}
