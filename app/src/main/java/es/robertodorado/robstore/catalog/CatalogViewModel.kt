package es.robertodorado.robstore.catalog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.launch

internal object CatalogRuntime {
    // Test seam: all Activity tests replace transport before onCreate; production always uses HTTPS.
    var transportFactory: () -> HttpTransport = ::OkHttpTransport
}

class CatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val cache = FileCatalogCache(File(application.filesDir, "catalog"))
    private val client = GitHubClient(CatalogRuntime.transportFactory(), cache)
    private val repository = CatalogRepository(cache, client)
    internal val icons = CatalogIconLoader(File(application.cacheDir, "catalog-icons"), client)
    val state = repository.state

    init { viewModelScope.launch { repository.initialize() } }
    fun refresh() { viewModelScope.launch { repository.refresh() } }
}
