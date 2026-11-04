package es.robertodorado.robstore.inventory

import es.robertodorado.robstore.catalog.fixtureSnapshot
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CatalogInventoryTest {
    @Test fun queriesOnlyDistinctCatalogPackagesAndRemovesOldObservations() = runTest {
        val queries = mutableListOf<String>()
        val inventory = CatalogInventory({ queries += it; InstallationQuery.Unknown }, StandardTestDispatcher(testScheduler))
        val app = fixtureSnapshot().apps.single()
        inventory.refresh(listOf(app, app.copy(repositoryId = 11)))
        assertEquals(listOf(app.packageName), queries)
        assertEquals(setOf(10L, 11L), inventory.state.value.keys)
        assertTrue(inventory.state.value.values.all { it.comparison == VersionComparison.INSTALLATION_UNKNOWN })
        inventory.refresh(emptyList())
        assertTrue(inventory.state.value.isEmpty())
        assertEquals(1, queries.size)
    }
}
