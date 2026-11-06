package es.robertodorado.robstore.update

import es.robertodorado.robstore.catalog.*
import es.robertodorado.robstore.inventory.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.IOException

@Serializable
internal data class UpdateSnapshot(val schemaVersion: Int = 1, val checkedAt: Long, val candidate: SelfUpdateCandidate?)

data class SelfUpdateState(
    val channel: SelfUpdateChannel? = null,
    val enabled: Boolean = false,
    val installed: InstalledApplication? = null,
    val candidate: SelfUpdateCandidate? = null,
    val checking: Boolean = false,
    val checkedAt: Long = 0,
    val nextCheckAt: Long = 0,
    val failed: Boolean = false,
    val message: String? = null,
) {
    val available: Boolean get() = enabled && candidate?.release?.versionCode?.let { remote ->
        installed?.takeIf { it.packageName == channel?.packageName }?.versionCode?.let { remote > it }
    } == true
}

internal class SelfUpdateRepository(
    private val packageName: String,
    private val applications: InstalledApplications,
    private val cache: FileCatalogCache,
    private val quota: CatalogCache,
    private val discovery: SelfUpdateDiscovery,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val devDistributionConfigured: Boolean = false,
) {
    private val channel = SelfUpdateChannel.identify(packageName)
    private val enabled = channel == SelfUpdateChannel.RELEASE || (channel == SelfUpdateChannel.DEV && devDistributionConfigured)
    private val mutex = Mutex()
    private var initialized = false
    private var snapshot: UpdateSnapshot? = null
    private val mutableState = MutableStateFlow(SelfUpdateState(channel, enabled))
    val state = mutableState.asStateFlow()

    suspend fun observeInstalled() = withContext(io) {
        val installed = (applications.query(packageName) as? InstallationQuery.Installed)?.application?.takeIf { it.packageName == packageName }
        mutableState.value = state.value.copy(installed = installed)
    }

    suspend fun initialize() = withContext(io) {
        mutex.lock()
        try {
            if (initialized) return@withContext
            observeInstalled()
            initialized = true
            if (!enabled) {
                mutableState.value = state.value.copy(message = if (channel == SelfUpdateChannel.DEV) "Autoactualización DEV no disponible: falta una fuente con firma de distribución persistente configurada." else "Identidad de RobStore desconocida; autoactualización no disponible.")
                return@withContext
            }
            snapshot = cache.read("self-update-v1.json", 128 * 1024) {
                catalogJson.decodeFromString<UpdateSnapshot>(it).takeIf { saved ->
                    saved.schemaVersion == 1 && saved.checkedAt > 0 &&
                        (saved.candidate == null || (saved.candidate.channel == channel && saved.candidate.valid()))
                }
            }
            mutableState.value = state.value.copy(candidate = snapshot?.candidate, checkedAt = snapshot?.checkedAt ?: 0, nextCheckAt = manualBlockedUntil())
        } finally { mutex.unlock() }
        if (snapshot == null || expired(snapshot!!.checkedAt)) check(manual = false)
    }

    suspend fun check(manual: Boolean = true) = withContext(io) {
        if (!enabled || !mutex.tryLock()) return@withContext
        try {
            observeInstalled()
            val time = now()
            val next = if (manual) manualBlockedUntil() else nextAllowed()
            if ((!manual && snapshot?.let { !expired(it.checkedAt) } == true) || time < next) {
                mutableState.value = state.value.copy(nextCheckAt = manualBlockedUntil(),
                    message = if (time < manualBlockedUntil()) "GitHub limita las solicitudes. Inténtalo cuando permita otra comprobación." else null)
                return@withContext
            }
            mutableState.value = state.value.copy(checking = true, message = null)
            try {
                cache.updateGate { it.copy(lastAttemptAt = time) }
                val candidate = discovery.fetch(channel!!)
                val fresh = UpdateSnapshot(checkedAt = time, candidate = candidate)
                cache.write("self-update-v1.json", catalogJson.encodeToString(fresh), 128 * 1024)
                snapshot = fresh
                mutableState.value = state.value.copy(candidate = candidate, checkedAt = time, checking = false,
                    failed = false, message = null, nextCheckAt = manualBlockedUntil())
            } catch (_: InvalidMetadata) { failed("Información de actualización insuficiente o inconsistente.") }
            catch (_: IOException) { failed("No se pudo comprobar la actualización. Se conserva la última información conocida.") }
        } finally { mutex.unlock() }
    }
    private fun failed(message: String) {
        mutableState.value = state.value.copy(checking = false, failed = true, message = message, nextCheckAt = manualBlockedUntil())
    }
    private fun manualBlockedUntil(): Long = maxOf(quota.readGate().blockedUntil, cache.readGate().blockedUntil)
    private fun nextAllowed(): Long = maxOf(manualBlockedUntil(), cache.readGate().lastAttemptAt.let {
        if (it > 0) it + CatalogPolicy.MANUAL_INTERVAL else 0
    })
    private fun expired(time: Long) = now() < time || now() - time >= CatalogPolicy.CACHE_TTL

    /** No stale download capability: revalidate with a fresh client before and after transfer and before install. */
    internal fun selected(): SelfUpdateCandidate {
        val current = state.value
        if (!current.available || current.candidate?.channel != channel || current.candidate?.valid() != true) {
            throw InvalidMetadata("No existe una actualización compatible y superior")
        }
        return current.candidate
    }
}
