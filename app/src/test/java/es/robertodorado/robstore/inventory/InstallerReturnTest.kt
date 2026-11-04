package es.robertodorado.robstore.inventory

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import es.robertodorado.robstore.catalog.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class InstallerReturnTest {
    @Test fun returnRequeriesActualInstalledVersionWithoutNetworkAndKeepsUnknownUnknown() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val application = RuntimeEnvironment.getApplication()
            val app = fixtureSnapshot().apps.single().let { it.copy(release = it.release.copy(versionCode = 3)) }
            FileCatalogCache(File(application.filesDir, "catalog")).save(CatalogSnapshot(
                syncedAt = System.currentTimeMillis(), apps = listOf(app)))
            var network = 0; var queries = 0
            CatalogRuntime.transportFactory = { HttpTransport { network++; error("Unexpected network") } }
            var query: InstallationQuery = InstallationQuery.Installed(InstalledApplication(app.packageName, 1, "uno", LauncherQuery.Unavailable))
            val model = ViewModelProvider(store, viewModelFactory { initializer {
                CatalogViewModel(application, { queries++; query }, dispatcher)
            } })[CatalogViewModel::class.java]
            advanceUntilIdle()
            assertEquals(VersionComparison.PUBLISHED_NEWER, model.installedState.value[app.repositoryId]!!.comparison)
            val before = queries
            query = InstallationQuery.Installed(InstalledApplication(app.packageName, 2, "dos", LauncherQuery.Unavailable))
            model.install(app.repositoryId) { fail("No prepared APK must be delivered") }; advanceUntilIdle()
            assertTrue(queries > before)
            assertEquals(2L, (model.installedState.value[app.repositoryId]!!.installation as InstallationQuery.Installed).application.versionCode)
            query = InstallationQuery.Installed(InstalledApplication(app.packageName, 3, "tres", LauncherQuery.Unavailable))
            model.installerReturned(); advanceUntilIdle()
            assertTrue(queries > before)
            val actual = model.installedState.value[app.repositoryId]!!
            assertEquals(VersionComparison.SAME, actual.comparison)
            assertEquals("tres", (actual.installation as InstallationQuery.Installed).application.versionName)
            query = InstallationQuery.Unknown
            model.installerReturned(); advanceUntilIdle()
            assertEquals(VersionComparison.INSTALLATION_UNKNOWN, model.installedState.value[app.repositoryId]!!.comparison)
            assertEquals(0, network)
        } finally {
            store.clear()
            CatalogRuntime.transportFactory = ::OkHttpTransport
            Dispatchers.resetMain()
        }
    }
}
