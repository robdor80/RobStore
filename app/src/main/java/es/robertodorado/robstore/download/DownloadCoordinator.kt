package es.robertodorado.robstore.download

import es.robertodorado.robstore.catalog.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// State contains presentation data only; paths and install capabilities stay inside the coordinator.
enum class DownloadPhase { AVAILABLE, CHECKING, DOWNLOADING, CANCELLING, VERIFYING, OFFICIAL_VERIFIED, NO_OFFICIAL_SHA, READY, ERROR, SECURITY_ERROR }
data class DownloadState(
    val repositoryId: Long? = null,
    val appName: String = "",
    val phase: DownloadPhase = DownloadPhase.AVAILABLE,
    val received: Long = 0,
    val expected: Long = 0,
    val message: String? = null,
    val localSha256: String? = null,
) {
    val busy get() = phase in setOf(DownloadPhase.CHECKING, DownloadPhase.DOWNLOADING, DownloadPhase.CANCELLING, DownloadPhase.VERIFYING)
}

/** One foreground, user-initiated transfer. Other selections are rejected until cancellation finishes. */
internal class DownloadCoordinator(
    private val scope: CoroutineScope,
    private val snapshot: () -> CatalogSnapshot?,
    private val files: DownloadFiles,
    private val client: BinaryClient,
    private val revalidate: suspend (CatalogApp, DownloadCancellation) -> Unit,
    private val inspector: ArchiveInspector,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(DownloadState())
    val state = mutableState.asStateFlow()
    private val initialized = scope.async(io) { files.cleanup() }
    private var job: Job? = null
    private var cancellation: DownloadCancellation? = null
    private var prepared: PreparedApk? = null

    @Synchronized fun start(repositoryId: Long): Boolean {
        if (job?.isCompleted == false) return false
        val token = DownloadCancellation()
        cancellation = token
        job = scope.launch(io, start = CoroutineStart.LAZY) {
            var partial: File? = null
            try {
                initialized.await()
                prepared?.file?.delete(); prepared = null
                val app = CatalogValidation.select(snapshot(), repositoryId)
                mutableState.value = DownloadState(repositoryId, app.name, DownloadPhase.CHECKING, expected = app.apk.size)
                revalidate(app, token)
                token.check(); ensureActive()
                mutableState.value = mutableState.value.copy(phase = DownloadPhase.DOWNLOADING)
                partial = files.receive(app, client, token) { received, expected ->
                    if (!token.cancelled) mutableState.value = mutableState.value.copy(received = received, expected = expected)
                }
                token.check(); ensureActive()
                if (CatalogValidation.select(snapshot(), repositoryId) != app) throw DownloadSecurity("El catálogo ha cambiado.")
                mutableState.value = mutableState.value.copy(phase = DownloadPhase.VERIFYING)
                val verified = PreparedApk.verify(partial, app, files, inspector, token, now())
                prepared = verified
                token.check(); ensureActive()
                mutableState.value = mutableState.value.copy(phase = if (verified.officialDigestMatched)
                    DownloadPhase.OFFICIAL_VERIFIED else DownloadPhase.NO_OFFICIAL_SHA, localSha256 = verified.localSha256)
            } catch (_: CancellationException) {
                partial?.delete(); prepared?.file?.delete(); prepared = null
                mutableState.value = DownloadState(repositoryId = repositoryId, message = "Descarga cancelada.")
            } catch (_: InvalidMetadata) {
                partial?.delete(); prepared?.file?.delete(); prepared = null
                mutableState.value = DownloadState(repositoryId = repositoryId, phase = DownloadPhase.SECURITY_ERROR,
                    message = "Metadatos inconsistentes. Actualiza el catálogo antes de reintentar.")
            } catch (_: DownloadSecurity) {
                partial?.delete(); prepared?.file?.delete(); prepared = null
                mutableState.value = DownloadState(repositoryId = repositoryId, phase = DownloadPhase.SECURITY_ERROR,
                    message = "La descarga no supera los controles de seguridad. Archivo eliminado.")
            } catch (_: IOException) {
                partial?.delete(); prepared?.file?.delete(); prepared = null
                mutableState.value = DownloadState(repositoryId = repositoryId, phase = if (token.cancelled) DownloadPhase.AVAILABLE else DownloadPhase.ERROR,
                    message = if (token.cancelled) "Descarga cancelada." else "No se pudo descargar. Comprueba conexión, espacio y disponibilidad; reintenta manualmente.")
            }
        }.also { it.start() }
        return true
    }
    @Synchronized fun confirmPrepared() {
        if (state.value.phase in setOf(DownloadPhase.OFFICIAL_VERIFIED, DownloadPhase.NO_OFFICIAL_SHA)) {
            mutableState.value = state.value.copy(phase = DownloadPhase.READY)
        }
    }

    @Synchronized fun cancel() {
        if (job?.isCompleted == false && state.value.busy) {
            mutableState.value = state.value.copy(phase = DownloadPhase.CANCELLING)
            cancellation?.cancel()
            job?.cancel()
        }
    }
    fun close() { cancellation?.cancel(); job?.cancel() }
}
