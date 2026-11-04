package es.robertodorado.robstore.catalog

import androidx.lifecycle.ViewModelStore
import es.robertodorado.robstore.inventory.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InstalledLifecycleTest {
    @Test fun startupResumeAndInstallerReturnRequeryLocallyWithoutGithubOrAssumingSuccess() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        val oldTransport = CatalogRuntime.transportFactory
        try {
            val application = RuntimeEnvironment.getApplication()
            val app = fixtureSnapshot().apps.single()
            val snapshot = CatalogSnapshot(syncedAt = System.currentTimeMillis(), apps = listOf(app))
            FileCatalogCache(File(application.filesDir, "catalog")).save(snapshot)
            var networkCalls = 0
            CatalogRuntime.transportFactory = { HttpTransport { networkCalls++; error("Unexpected network") } }
            var queries = 0
            var observation: InstallationQuery = InstallationQuery.Unknown
            val model = CatalogViewModel(application, { assertEquals(app.packageName, it); queries++; observation }, dispatcher)
            store.put("catalog", model)
            advanceUntilIdle()
            assertEquals(1, queries)
            assertEquals(VersionComparison.INSTALLATION_UNKNOWN, model.installedState.value.getValue(app.repositoryId).comparison)
            model.refreshInstalled() // ON_RESUME, including return from external installer.
            advanceUntilIdle()
            assertEquals(2, queries)
            model.installerReturned() // Cancellation or RESULT_OK must not manufacture installation.
            advanceUntilIdle()
            assertEquals(3, queries)
            assertEquals(InstallationQuery.Unknown, model.installedState.value.getValue(app.repositoryId).installation)
            observation = InstallationQuery.Installed(InstalledApplication(app.packageName, 7, "7", LauncherQuery.Unavailable))
            model.installerReturned()
            advanceUntilIdle()
            assertEquals(4, queries)
            assertEquals(observation, model.installedState.value.getValue(app.repositoryId).installation)
            assertEquals(VersionComparison.PUBLISHED_UNKNOWN, model.installedState.value.getValue(app.repositoryId).comparison)
            assertEquals(0, networkCalls)
            assertEquals(snapshot, FileCatalogCache(File(application.filesDir, "catalog")).load())
        } finally {
            store.clear()
            CatalogRuntime.transportFactory = oldTransport
            Dispatchers.resetMain()
        }
    }
}
