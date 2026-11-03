package es.robertodorado.robstore.download

import es.robertodorado.robstore.catalog.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadCoordinatorTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun repeatedAndDifferentSelectionsCannotStartConcurrentDownloads() = runTest {
        val app = downloadApp()
        val snapshot = CatalogSnapshot(syncedAt = TEST_NOW, apps = listOf(app, app.copy(repositoryId = 99)))
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val controller = DownloadCoordinator(this, { snapshot }, DownloadFiles(temp.newFolder()) { Long.MAX_VALUE },
            binaryClient { calls++; binaryResponse(it) }, { _, _ -> gate.await() }, StandardTestDispatcher(testScheduler))
        assertTrue(controller.start(app.repositoryId))
        assertFalse(controller.start(app.repositoryId)); assertFalse(controller.start(99))
        runCurrent(); assertEquals(DownloadPhase.CHECKING, controller.state.value.phase)
        controller.cancel(); advanceUntilIdle()
        assertEquals(DownloadPhase.AVAILABLE, controller.state.value.phase)
        assertEquals(0, calls)
        gate.complete(Unit)
        assertTrue(controller.start(99)); advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(DownloadPhase.DOWNLOADED, controller.state.value.phase)
    }
    @Test fun invalidCatalogNeverRequestsNetworkAndManualRetryIsPossible() = runTest {
        var snapshot: CatalogSnapshot? = null
        var calls = 0
        val controller = DownloadCoordinator(this, { snapshot }, DownloadFiles(temp.newFolder()) { Long.MAX_VALUE },
            binaryClient { calls++; binaryResponse(it) }, { _, _ -> }, StandardTestDispatcher(testScheduler))
        controller.start(downloadApp().repositoryId); advanceUntilIdle()
        assertEquals(DownloadPhase.SECURITY_ERROR, controller.state.value.phase); assertEquals(0, calls)
        snapshot = CatalogSnapshot(syncedAt = TEST_NOW, apps = listOf(downloadApp()))
        controller.start(downloadApp().repositoryId); advanceUntilIdle()
        assertEquals(DownloadPhase.DOWNLOADED, controller.state.value.phase); assertEquals(1, calls)
    }
}
