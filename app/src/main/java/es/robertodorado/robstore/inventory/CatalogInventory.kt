package es.robertodorado.robstore.inventory

import es.robertodorado.robstore.catalog.CatalogApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CatalogApplicationStatus(
    val published: CatalogApp,
    val installation: InstallationQuery,
    val comparison: VersionComparison = compareVersions(published.release, installation),
)

/** Queries only catalog packages, off the main thread. No network dependency or installed-app cache. */
internal class CatalogInventory(
    private val applications: InstalledApplications,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<Map<Long, CatalogApplicationStatus>>(emptyMap())
    val state = mutableState.asStateFlow()

    suspend fun refresh(apps: List<CatalogApp>) = withContext(io) {
        mutex.withLock {
            val observations = apps.map { it.packageName }.distinct().associateWith(applications::query)
            mutableState.value = apps.associate { app ->
                app.repositoryId to CatalogApplicationStatus(app, observations.getValue(app.packageName))
            }
        }
    }
}
