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
    private val downloads = DownloadCoordinator(viewModelScope, { state.value.snapshot },
        DownloadFiles(File(application.filesDir, "apk-downloads")), BinaryClient(),
        { app, token ->
            val transport = OkHttpTransport(onCall = token::attach)
            CatalogDiscovery(GitHubClient(transport, cache), System::currentTimeMillis).revalidate(app)
        }, AndroidArchiveInspector(application.packageManager))
    val downloadState = downloads.state
    fun download(repositoryId: Long) { downloads.start(repositoryId) }
    fun cancelDownload() = downloads.cancel()
    fun confirmPrepared() = downloads.confirmPrepared()
    fun install(repositoryId: Long, deliver: (Intent) -> Unit) {
        downloads.requestInstall(repositoryId, installer::authorized) { apk -> deliver(installer.intent(apk)) }
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
