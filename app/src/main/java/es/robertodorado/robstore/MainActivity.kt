package es.robertodorado.robstore

import android.os.Bundle
import es.robertodorado.robstore.install.launchSystemActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import es.robertodorado.robstore.catalog.CatalogViewModel
import es.robertodorado.robstore.ui.LocalCatalogIcons
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import es.robertodorado.robstore.ui.RobStoreApp
import es.robertodorado.robstore.ui.robstyle.RobStyleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(7, 13, 24)),
        )
        setContent {
            RobStyleTheme {
                val model: CatalogViewModel = viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                val download by model.downloadState.collectAsStateWithLifecycle()
                val installed by model.installedState.collectAsStateWithLifecycle()
                val openMessage by model.openMessage.collectAsStateWithLifecycle()
                val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    // ACTION_VIEW results are not reliable proof of installation or cancellation.
                    model.installerReturned()
                }
                val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    model.permissionReturned()
                }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    model.permissionReturned()
                    model.refreshInstalled()
                }
                CompositionLocalProvider(LocalCatalogIcons provides model.icons) {
                    RobStoreApp(state = state, onRefresh = model::refresh, download = download,
                        installed = installed, openMessage = openMessage, onOpen = { id -> model.open(id, ::startActivity) },
                        onDownload = model::download, onCancel = model::cancelDownload,
                        onConfirm = model::confirmPrepared,
                        onInstall = { id -> model.install(id) { launchSystemActivity(it, { intent -> installer.launch(intent) }, model::installationError) } },
                        onPermission = { launchSystemActivity(model.permissionSettings(), { settings.launch(it) }, model::installationError) })
                }
            }
        }
    }
}
