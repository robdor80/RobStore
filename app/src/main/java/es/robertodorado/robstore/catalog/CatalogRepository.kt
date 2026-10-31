package es.robertodorado.robstore.catalog

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** No periodic work: refresh only at startup or on explicit user request. */
data class CatalogState(
    val snapshot: CatalogSnapshot? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val stale: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
    val nextRefreshAt: Long = 0,
)

internal class CatalogRepository(
    private val cache: CatalogCache,
    client: GitHubClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val discovery = CatalogDiscovery(client, now)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(CatalogState())
    val state = mutableState.asStateFlow()
    private var initialized = false

    suspend fun initialize() {
        withContext(io) {
            mutex.lock()
            try {
                if (initialized) return@withContext
                val snapshot = cache.load()
                mutableState.value = CatalogState(snapshot = snapshot, loading = false,
                    stale = snapshot != null && expired(snapshot), nextRefreshAt = nextAllowed())
                initialized = true
            } finally { mutex.unlock() }
            if (mutableState.value.snapshot?.let { expired(it) } != false) refresh(manual = false)
        }
    }

    suspend fun refresh(manual: Boolean = true) = withContext(io) {
        if (!mutex.tryLock()) return@withContext
        try {
            val time = now()
            val next = nextAllowed()
            if (next > time) {
                val old = mutableState.value.snapshot
                if (manual || old == null || expired(old)) mutableState.value = mutableState.value.copy(loading = false,
                    stale = old != null && expired(old), failed = old == null || mutableState.value.failed,
                    message = "Aún no se permite otra comprobación. Intervalo mínimo: 5 minutos; se respetan los límites de GitHub.",
                    nextRefreshAt = next)
                return@withContext
            }
            mutableState.value = mutableState.value.copy(loading = false, refreshing = true, message = null, failed = false)
            try {
                cache.updateGate { it.copy(lastAttemptAt = time) }
                val snapshot = discovery.fetch()
                cache.save(snapshot)
                mutableState.value = CatalogState(snapshot = snapshot, loading = false, nextRefreshAt = nextAllowed())
            } catch (failure: IOException) {
                val old = mutableState.value.snapshot
                mutableState.value = mutableState.value.copy(refreshing = false, loading = false,
                    stale = old != null && expired(old), failed = true,
                    message = failure.message ?: "No se pudo actualizar el catálogo. Comprueba la conexión.",
                    nextRefreshAt = nextAllowed())
            }
        } finally { mutex.unlock() }
    }

    private fun expired(snapshot: CatalogSnapshot): Boolean = now() < snapshot.syncedAt || now() - snapshot.syncedAt >= CatalogPolicy.CACHE_TTL
    private fun nextAllowed(): Long = cache.readGate().let { gate ->
        // Wall-clock rollback must not trigger an uncontrolled refresh loop.
        maxOf(if (gate.lastAttemptAt > 0) gate.lastAttemptAt + CatalogPolicy.MANUAL_INTERVAL else 0, gate.blockedUntil)
    }
}
