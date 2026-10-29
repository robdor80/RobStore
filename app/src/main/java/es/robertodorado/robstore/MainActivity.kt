package es.robertodorado.robstore

import android.os.Bundle
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
                CompositionLocalProvider(LocalCatalogIcons provides model.icons) {
                    RobStoreApp(state = state, onRefresh = model::refresh)
                }
            }
        }
    }
}
