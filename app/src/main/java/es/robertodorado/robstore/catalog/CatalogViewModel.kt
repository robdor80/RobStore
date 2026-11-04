package es.robertodorado.robstore.catalog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
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
    private val client = GitHubClient(CatalogRuntime.transportFactory(), cache)
    private val repository = CatalogRepository(cache, client, io = io)
    internal val icons = CatalogIconLoader(File(application.cacheDir, "catalog-icons"), client)
    val state = repository.state
    private val inventory = CatalogInventory(applications, io)
    val installedState = inventory.state
    private val installer = AndroidInstaller(application)
    private val launcher = ApplicationLauncher(applications, io)
    private val mutableOpenMessage = MutableStateFlow<String?>(null)
    val openMessage = mutableOpenMessage.asStateFlow()
    private val downloads = DownloadCoordinator(viewModelScope, { state.value.snapshot },
        DownloadFiles(File(application.filesDir, "apk-downloads")), BinaryClient(),
        { app, token ->
            val transport = OkHttpTransport(onCall = token::attach)
            CatalogDiscovery(GitHubClient(transport, cache), System::currentTimeMillis).revalidate(app)
        }, AndroidArchiveInspector(application.packageManager), applications = applications)
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
    fun refreshInstalled() { viewModelScope.launch { inventory.refresh(state.value.snapshot?.apps.orEmpty()) } }
    fun installationError() = downloads.installationError()
    override fun onCleared() { downloads.close(); super.onCleared() }

    init {
        viewModelScope.launch {
            state.map { it.snapshot?.apps.orEmpty() }.distinctUntilChanged().collect { inventory.refresh(it) }
        }
        viewModelScope.launch { repository.initialize() }
    }
    fun refresh() { viewModelScope.launch { repository.refresh() } }
}
