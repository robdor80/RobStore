package es.robertodorado.robstore.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import es.robertodorado.robstore.R
import es.robertodorado.robstore.ui.robstyle.*

object RobRoutes {
    const val Home = "home"
    const val Settings = "settings"
    const val AppDetail = "app/{appId}"
    fun appDetail(appId: String) = "app/${android.net.Uri.encode(appId)}"
}

@Composable
fun RobStoreApp(nav: NavHostController = rememberNavController()) {
    NavHost(navController = nav, startDestination = RobRoutes.Home) {
        composable(RobRoutes.Home) {
            HomeScreen(onSettings = { nav.navigate(RobRoutes.Settings) { launchSingleTop = true } })
        }
        composable(RobRoutes.Settings) { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable(RobRoutes.AppDetail, arguments = listOf(navArgument("appId") { type = NavType.StringType })) {
            AppDetailScreen(onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun Screen(title: String, onBack: (() -> Unit)? = null, onSettings: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { RobTopBar(title, onBack != null, { onBack?.invoke() }, onSettings) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = RobStyleDimensions.maxContentWidth).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(RobStyleDimensions.screenPadding),
                verticalArrangement = Arrangement.spacedBy(RobStyleDimensions.contentSpacing), content = content)
        }
    }
}

@Composable
fun HomeScreen(onSettings: () -> Unit) {
    Screen(stringResource(R.string.app_name), onSettings = onSettings) {
        Text(stringResource(R.string.home_heading), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.home_intro), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        RobSectionHeader(stringResource(R.string.catalog))
        RobStatus(RobVisualState.Empty)
        RobPrimaryButton(stringResource(R.string.refresh), enabled = false, onClick = {})
        Text(stringResource(R.string.refresh_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Screen(stringResource(R.string.settings), onBack = onBack) {
        Text(stringResource(R.string.settings_intro), style = MaterialTheme.typography.bodyLarge)
        listOf(R.string.automatic, R.string.channel, R.string.downloads, R.string.notifications).forEach { title ->
            RobCard {
                RobSectionHeader(stringResource(title))
                Text(stringResource(R.string.future_option), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Empty reusable structure: RS-02 supplies actual app metadata and actions. */
@Composable
fun AppDetailScreen(onBack: () -> Unit) {
    Screen(stringResource(R.string.detail), onBack = onBack) {
        RobCard {
            Text(stringResource(R.string.icon_placeholder), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.name_placeholder), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.installed_version))
            Text(stringResource(R.string.available_version))
            RobPrimaryButton(stringResource(R.string.unavailable_action), enabled = false, onClick = {})
        }
        RobCard {
            RobSectionHeader(stringResource(R.string.description))
            Text(stringResource(R.string.pending_metadata))
        }
        RobCard {
            RobSectionHeader(stringResource(R.string.changelog))
            Text(stringResource(R.string.pending_metadata))
        }
    }
}
