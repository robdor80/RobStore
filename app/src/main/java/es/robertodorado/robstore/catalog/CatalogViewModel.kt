package es.robertodorado.robstore.catalog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import es.robertodorado.robstore.update.*
import es.robertodorado.robstore.auth.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import es.robertodorado.robstore.inventory.*
import es.robertodorado.robstore.download.*
import es.robertodorado.robstore.install.AndroidInstaller
import android.content.Intent
import es.robertodorado.robstore.install.ApplicationLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object CatalogRuntime {
    // Test seam: all Activity tests replace transport before onCreate; production always uses HTTPS.
    var transportFactory: () -> HttpTransport = ::OkHttpTransport
}

class CatalogViewModel internal constructor(
    application: Application,
    applications: InstalledApplications,
    io: CoroutineDispatcher,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application, AndroidInstalledApplications(application.packageManager), Dispatchers.IO,
    )
    private val cache = FileCatalogCache(File(application.filesDir, "catalog"))
    private val auth = GitHubAuthManager(GitHubCredentialStore(application))
    internal val githubAuthState = auth.state
    private var authorizationJob: Job? = null
    private val client = GitHubClient(GitHubAuthenticatedTransport(CatalogRuntime.transportFactory(), auth), cache)
    private val repository = CatalogRepository(cache, client, io = io)
    internal val icons = CatalogIconLoader(File(application.cacheDir, "catalog-icons"), client)
    val state = repository.state
    fun connectGitHub() {
        if (authorizationJob?.isActive == true) return
        authorizationJob = viewModelScope.launch {
            if (auth.authorize()) {
                // Cambio de límite anónimo a autenticado: descartar bloqueo de la cuota anterior.
                cache.updateGate { it.copy(blockedUntil = 0) }
                repository.refresh(manual = true)
                updates.check(manual = true)
            }
        }
    }
    fun cancelGitHubConnection() {
        authorizationJob?.cancel()
        auth.cancelAuthorization()
    }
    fun disconnectGitHub() {
        authorizationJob?.cancel()
        auth.disconnect()
        cache.updateGate { it.copy(blockedUntil = 0) }
    }
    private val inventory = CatalogInventory(applications, io)
    val installedState = inventory.state
    private val installer = AndroidInstaller(application)
    private val launcher = ApplicationLauncher(applications, io)
    private val mutableOpenMessage = MutableStateFlow<String?>(null)
    val openMessage = mutableOpenMessage.asStateFlow()
    private val updates = SelfUpdateRepository(application.packageName, applications,
        FileCatalogCache(File(application.filesDir, "self-update")), cache, SelfUpdateDiscovery(client), io = io)
    val selfUpdateState = updates.state
    fun checkSelfUpdate() { viewModelScope.launch { updates.check() } }
    fun downloadSelfUpdate() { downloads.startSelfUpdate() }
    fun installSelfUpdate(deliver: (Intent) -> Unit) {
        downloads.requestSelfInstall(installer::authorized) { deliver(installer.intent(it)) }
        refreshInstalled()
    }
    private val downloads = DownloadCoordinator(viewModelScope, { state.value.snapshot },
        DownloadFiles(File(application.filesDir, "apk-downloads")), BinaryClient(),
        { app, token ->
            val transport = GitHubAuthenticatedTransport(OkHttpTransport(onCall = token::attach), auth)
            CatalogDiscovery(GitHubClient(transport, cache), System::currentTimeMillis).revalidate(app)
        }, AndroidArchiveInspector(application.packageManager), applications = applications,
        selfUpdate = updates::selected, revalidateSelf = { candidate, token ->
            SelfUpdateDiscovery(GitHubClient(GitHubAuthenticatedTransport(OkHttpTransport(onCall = token::attach), auth), cache)).revalidate(candidate)
        })
    val downloadState = downloads.state
    private fun catalogTarget(repositoryId: Long) = state.value.snapshot?.apps?.find {
        it.repositoryId == repositoryId && it.packageName !in setOf("es.robertodorado.robstore", "es.robertodorado.robstore.debug")
    }
    fun download(repositoryId: Long) { if (catalogTarget(repositoryId) != null) downloads.start(repositoryId) }
    fun open(repositoryId: Long, deliver: (Intent) -> Unit) {
        val app = state.value.snapshot?.apps?.find { it.repositoryId == repositoryId } ?: return
        // PackageManager calls are local; never refresh GitHub on Open or installer return.
        viewModelScope.launch {
            mutableOpenMessage.value = null
            if (!launcher.open(app.packageName, deliver)) openError()
            refreshInstalled()
        }
    }
    fun openError() { mutableOpenMessage.value = "No se pudo abrir la aplicación. Puede haber cambiado o estar restringida por Android." }

    fun cancelDownload() = downloads.cancel()
    fun confirmPrepared() = downloads.confirmPrepared()
    fun install(repositoryId: Long, deliver: (Intent) -> Unit) {
        if (catalogTarget(repositoryId) == null) return
        downloads.requestInstall(repositoryId, installer::authorized) { apk -> deliver(installer.intent(apk)) }
        // A preflight block must also refresh the visible version, even if no installer opens.
        refreshInstalled()
    }
    fun permissionSettings(): Intent = installer.settings()
    fun permissionReturned() = downloads.permissionChecked(installer.authorized())
    fun installerReturned() {
        downloads.installerReturned()
        refreshInstalled()
    }
    fun refreshInstalled() { viewModelScope.launch {
        inventory.refresh(state.value.snapshot?.apps.orEmpty())
        updates.observeInstalled()
    } }
    fun installationError() = downloads.installationError()
    override fun onCleared() { authorizationJob?.cancel(); downloads.close(); super.onCleared() }

    init {
        viewModelScope.launch {
            state.map { it.snapshot?.apps.orEmpty() }.distinctUntilChanged().collect { inventory.refresh(it) }
        }
        viewModelScope.launch { repository.initialize() }
        viewModelScope.launch { updates.initialize() }
    }
    fun refresh() { viewModelScope.launch { repository.refresh() } }
}
