package es.robertodorado.robstore.download

import es.robertodorado.robstore.catalog.*
import es.robertodorado.robstore.inventory.*
import es.robertodorado.robstore.update.SelfUpdateCandidate
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// State contains presentation data only; paths and install capabilities stay inside the coordinator.
enum class DownloadPhase { AVAILABLE, CHECKING, DOWNLOADING, CANCELLING, VERIFYING, OFFICIAL_VERIFIED, NO_OFFICIAL_SHA, READY, PREPARING_INSTALL, PERMISSION_PENDING, INSTALLER_OPEN, UNCONFIRMED, INSTALL_ERROR, ERROR, SECURITY_ERROR }
data class DownloadState(
    val repositoryId: Long? = null,
    val appName: String = "",
    val phase: DownloadPhase = DownloadPhase.AVAILABLE,
    val received: Long = 0,
    val expected: Long = 0,
    val message: String? = null,
    val localSha256: String? = null,
) {
    val busy get() = phase in setOf(DownloadPhase.CHECKING, DownloadPhase.DOWNLOADING, DownloadPhase.CANCELLING, DownloadPhase.VERIFYING, DownloadPhase.PREPARING_INSTALL)
}

internal sealed interface DownloadTarget {
    data class Catalog(val id: Long) : DownloadTarget
    data object SelfUpdate : DownloadTarget
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
    private val applications: InstalledApplications = InstalledApplications { InstallationQuery.Unknown },
    private val selfUpdate: () -> SelfUpdateCandidate = { throw InvalidMetadata("Canal no configurado") },
    private val revalidateSelf: suspend (SelfUpdateCandidate, DownloadCancellation) -> Unit = { _, _ -> throw InvalidMetadata("Canal no configurado") },
) {
    private val mutableState = MutableStateFlow(DownloadState())
    val state = mutableState.asStateFlow()
    private val initialized = scope.async(io) { try { files.cleanup(); null } catch (failure: IOException) { failure } }
    private var job: Job? = null
    private var cancellation: DownloadCancellation? = null
    private var prepared: PreparedApk? = null

    private var target: DownloadTarget? = null
    private fun select(destination: DownloadTarget): ReleaseArtifact = when (destination) {
        is DownloadTarget.Catalog -> CatalogValidation.select(snapshot(), destination.id)
        DownloadTarget.SelfUpdate -> selfUpdate()
    }
    private suspend fun validateRemote(app: ReleaseArtifact, token: DownloadCancellation) = when (app) {
        is CatalogApp -> revalidate(app, token)
        is SelfUpdateCandidate -> revalidateSelf(app, token)
        else -> throw InvalidMetadata("Destino desconocido")
    }
    fun start(repositoryId: Long): Boolean = start(DownloadTarget.Catalog(repositoryId))
    fun startSelfUpdate(): Boolean = start(DownloadTarget.SelfUpdate)
    @Synchronized private fun start(destination: DownloadTarget): Boolean {
        val repositoryId = (destination as? DownloadTarget.Catalog)?.id ?: SELF_UPDATE_ID
        if (job?.isCompleted == false || state.value.phase == DownloadPhase.INSTALLER_OPEN) return false
        target = destination
        val token = DownloadCancellation()
        cancellation = token
        job = scope.launch(io, start = CoroutineStart.LAZY) {
            var partial: File? = null
            try {
                initialized.await()
                files.cleanup() // Retry a failed startup cleanup and enforce the one-file policy.
                prepared = null
                val app = select(destination)
                mutableState.value = DownloadState(repositoryId, if (app is CatalogApp) app.name else "RobStore", DownloadPhase.CHECKING, expected = app.apk.size)
                validateRemote(app, token)
                token.check(); ensureActive()
                mutableState.value = mutableState.value.copy(phase = DownloadPhase.DOWNLOADING)
                partial = files.receive(app, client, token) { received, expected ->
                    if (!token.cancelled) mutableState.value = mutableState.value.copy(received = received, expected = expected)
                }
                token.check(); ensureActive()
                if (destination == DownloadTarget.SelfUpdate) validateRemote(app, token)
                if (select(destination) != app) throw DownloadSecurity("El catálogo ha cambiado.")
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
                    message = if (destination == DownloadTarget.SelfUpdate) "La publicación de RobStore ha cambiado o es inconsistente. Comprueba de nuevo las actualizaciones."
                    else "Metadatos inconsistentes. Actualiza el catálogo antes de reintentar.")
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

    /** Rehash/reparse before creating a URI, then hand over only on an explicit install action. */
    companion object { const val SELF_UPDATE_ID = -1L }
    fun requestSelfInstall(authorized: () -> Boolean, deliver: (PreparedApk) -> Unit): Boolean =
        requestInstall(SELF_UPDATE_ID, authorized, deliver)
    @Synchronized fun requestInstall(repositoryId: Long, authorized: () -> Boolean, deliver: (PreparedApk) -> Unit): Boolean {
        if (job?.isCompleted == false || state.value.repositoryId != repositoryId || state.value.phase !in
            setOf(DownloadPhase.READY, DownloadPhase.PERMISSION_PENDING, DownloadPhase.UNCONFIRMED, DownloadPhase.INSTALL_ERROR)) return false
        val apk = prepared ?: return false
        val destination = target ?: return false
        val token = DownloadCancellation()
        cancellation = token
        mutableState.value = state.value.copy(phase = DownloadPhase.PREPARING_INSTALL, message = null)
        job = scope.launch(io, start = CoroutineStart.LAZY) {
            try {
                if (select(destination) != apk.app) throw DownloadSecurity("El catálogo ha cambiado.")
                if (destination == DownloadTarget.SelfUpdate) validateRemote(apk.app, token)
                apk.recheck(files, inspector, token, now())
                // Fresh local observation after rehashing, including after an external update during download.
                val installed = applications.query(apk.app.packageName)
                if (installed is InstallationQuery.Installed &&
                    installed.application.versionCode?.let { it > apk.metadata.versionCode } == true) {
                    throw DownloadSecurity("La versión instalada es superior al APK.")
                }
                if (destination == DownloadTarget.SelfUpdate) requireNewerSelf(apk)
                token.check(); ensureActive()
                withContext(Dispatchers.Main.immediate) {
                    if (select(destination) != apk.app) throw DownloadSecurity("El catálogo ha cambiado.")
                    if (destination == DownloadTarget.SelfUpdate) requireNewerSelf(apk)
                    if (!authorized()) {
                        mutableState.value = state.value.copy(phase = DownloadPhase.PERMISSION_PENDING)
                    } else {
                        mutableState.value = state.value.copy(phase = DownloadPhase.INSTALLER_OPEN)
                        deliver(apk)
                    }
                }
            } catch (_: CancellationException) {
                apk.file.delete(); prepared = null
                mutableState.value = DownloadState(repositoryId = repositoryId, message = "Preparación cancelada.")
            } catch (_: DownloadSecurity) { blockedInstall(repositoryId) }
            catch (_: InvalidMetadata) { blockedInstall(repositoryId) }
            catch (_: IOException) { blockedInstall(repositoryId) }
            catch (_: SecurityException) { installationError() }
            catch (_: IllegalArgumentException) { installationError() }
        }.also { it.start() }
        return true
    }
    private fun requireNewerSelf(apk: PreparedApk) {
        val installed = (applications.query(apk.app.packageName) as? InstallationQuery.Installed)?.application
        if (installed?.packageName != apk.app.packageName || installed.versionCode?.let { it < apk.metadata.versionCode } != true ||
            !apk.officialDigestMatched) throw DownloadSecurity("No se demuestra una actualización propia superior")
    }
    private fun blockedInstall(repositoryId: Long) {
        prepared?.file?.delete(); prepared = null
        mutableState.value = DownloadState(repositoryId = repositoryId, phase = DownloadPhase.SECURITY_ERROR,
            message = "Instalación bloqueada: el catálogo o el APK cambió, caducó o la versión instalada es superior. Archivo eliminado; revisa el catálogo antes de descargar de nuevo.")
    }
    fun permissionChecked(granted: Boolean) {
        if (state.value.phase == DownloadPhase.PERMISSION_PENDING) {
            mutableState.value = state.value.copy(phase = if (granted) DownloadPhase.READY else DownloadPhase.PERMISSION_PENDING,
                message = if (granted) "Permiso concedido. Pulsa Instalar para continuar." else "Android aún no autoriza este origen.")
        }
    }
    fun installerReturned() {
        if (state.value.phase == DownloadPhase.INSTALLER_OPEN) mutableState.value = state.value.copy(phase = DownloadPhase.UNCONFIRMED)
    }
    fun installationError() {
        if (prepared != null) mutableState.value = state.value.copy(phase = DownloadPhase.INSTALL_ERROR,
            message = "No se pudo abrir la actividad de Android. Puede no existir un instalador compatible o estar restringido.")
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
