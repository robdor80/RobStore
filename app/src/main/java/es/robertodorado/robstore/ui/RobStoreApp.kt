package es.robertodorado.robstore.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import es.robertodorado.robstore.R
import es.robertodorado.robstore.catalog.*
import es.robertodorado.robstore.ui.robstyle.*

internal val LocalCatalogIcons = staticCompositionLocalOf<CatalogIconLoader?> { null }

object RobRoutes {
    const val Home = "home"
    const val Settings = "settings"
    const val AppDetail = "app/{appId}"
    fun appDetail(appId: String) = "app/${android.net.Uri.encode(appId)}"
}

@Composable
fun RobStoreApp(nav: NavHostController = rememberNavController(), state: CatalogState = CatalogState(loading = false),
    onRefresh: () -> Unit = {}) {
    NavHost(navController = nav, startDestination = RobRoutes.Home) {
        composable(RobRoutes.Home) {
            HomeScreen(onSettings = { nav.navigate(RobRoutes.Settings) { launchSingleTop = true } },
                state = state, onRefresh = onRefresh,
                onOpenApp = { nav.navigate(RobRoutes.appDetail(it.toString())) { launchSingleTop = true } })
        }
        composable(RobRoutes.Settings) { SettingsScreen(onBack = { nav.popBackStack() }, state = state) }
        composable(RobRoutes.AppDetail, arguments = listOf(navArgument("appId") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("appId")?.toLongOrNull()
            AppDetailScreen(onBack = { nav.popBackStack() }, app = state.snapshot?.apps?.find { it.repositoryId == id }, state = state)
        }
    }
}

@Composable
private fun ScreenFrame(title: String, onBack: (() -> Unit)? = null, onSettings: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit) {
    Scaffold(topBar = { RobTopBar(title, onBack != null, { onBack?.invoke() }, onSettings) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter, content = content)
    }
}

@Composable
private fun Screen(title: String, onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    ScreenFrame(title, onBack = onBack) {
        Column(Modifier.widthIn(max = RobStyleDimensions.maxContentWidth).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(RobStyleDimensions.screenPadding),
            verticalArrangement = Arrangement.spacedBy(RobStyleDimensions.contentSpacing), content = content)
    }
}

@Composable
fun HomeScreen(onSettings: () -> Unit, state: CatalogState = CatalogState(loading = false),
    onRefresh: () -> Unit = {}, onOpenApp: (Long) -> Unit = {}) {
    ScreenFrame(stringResource(R.string.app_name), onSettings = onSettings) {
        LazyColumn(Modifier.widthIn(max = RobStyleDimensions.maxContentWidth).fillMaxWidth(),
            contentPadding = PaddingValues(RobStyleDimensions.screenPadding),
            verticalArrangement = Arrangement.spacedBy(RobStyleDimensions.contentSpacing)) {
            item {
                Text(stringResource(R.string.home_heading), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(R.string.catalog_origin), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { SyncStatus(state) }
            item {
                RobPrimaryButton(stringResource(R.string.refresh), enabled = !state.loading && !state.refreshing, onClick = onRefresh)
                Text(stringResource(R.string.refresh_policy), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { RobSectionHeader(stringResource(R.string.catalog)) }
            when {
                state.loading || (state.snapshot == null && state.refreshing) -> item { RobStatus(RobVisualState.Loading) }
                state.snapshot == null && state.failed -> item { RobStatus(RobVisualState.Error, onRetry = onRefresh) }
                state.snapshot?.apps.isNullOrEmpty() -> item { RobStatus(RobVisualState.Empty) }
                else -> items(state.snapshot.apps, key = { it.repositoryId }) { app ->
                    RobCard(onClick = { onOpenApp(app.repositoryId) }) {
                        AppIcon(app)
                        Text(app.name, style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.version_value, app.release.tag))
                        if (app.description.isNotBlank()) Text(app.description, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.available_later), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncStatus(state: CatalogState) {
    if (state.refreshing) {
        if (state.snapshot != null) CircularProgressIndicator()
        Text(stringResource(R.string.refreshing))
    }
    state.snapshot?.let { Text(stringResource(R.string.last_sync, displayDate(it.syncedAt))) }
    if (state.stale) Text(stringResource(R.string.stale_cache), color = MaterialTheme.colorScheme.tertiary)
    if (state.failed && state.snapshot != null) Text(stringResource(R.string.cached_after_failure))
    state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, state: CatalogState = CatalogState(loading = false)) {
    Screen(stringResource(R.string.settings), onBack = onBack) {
        RobCard {
            RobSectionHeader(stringResource(R.string.origin_heading))
            Text(stringResource(R.string.catalog_origin))
            Text(stringResource(R.string.stable_channel))
        }
        RobCard {
            RobSectionHeader(stringResource(R.string.sync_heading))
            SyncStatus(state)
            if (state.snapshot == null) Text(stringResource(R.string.no_sync))
            Text(stringResource(R.string.refresh_policy))
            Text(stringResource(R.string.offline_policy))
        }
        state.snapshot?.diagnostics?.takeIf { it.isNotEmpty() }?.let { diagnostics ->
            RobCard {
                RobSectionHeader(stringResource(R.string.diagnostics_heading))
                diagnostics.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
fun AppDetailScreen(onBack: () -> Unit, app: CatalogApp? = null, state: CatalogState = CatalogState(loading = false)) {
    Screen(stringResource(R.string.detail), onBack = onBack) {
        if (app == null) {
            Text(stringResource(R.string.app_not_available))
        } else {
            SyncStatus(state)
            RobCard {
                AppIcon(app)
                Text(app.name, style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(R.string.version_value, app.release.tag))
                Text(stringResource(R.string.published_value, displayDate(java.time.Instant.parse(app.release.publishedAt).toEpochMilli())))
                Text(stringResource(R.string.package_value, app.packageName))
                Text(stringResource(R.string.available_later), color = MaterialTheme.colorScheme.primary)
                RobPrimaryButton(stringResource(R.string.install_later), enabled = false, onClick = {})
            }
            RobCard {
                RobSectionHeader(stringResource(R.string.description))
                Text(app.description.ifBlank { stringResource(R.string.no_description) })
                Text(stringResource(R.string.repository_value, app.repositoryUrl))
            }
            RobCard {
                RobSectionHeader(stringResource(R.string.changelog))
                // Plain text only: no execution, HTML or remote embedded images.
                Text(app.release.notes.ifBlank { stringResource(R.string.no_changelog) })
            }
            RobCard {
                RobSectionHeader(stringResource(R.string.apk_heading))
                Text(stringResource(R.string.apk_name_value, app.apk.name))
                Text(pluralStringResource(R.plurals.apk_size_value, app.apk.size.toInt(), app.apk.size))
                Text(stringResource(R.string.apk_url_value, app.apk.downloadUrl))
                app.apk.sha256?.let { Text(stringResource(R.string.apk_digest_value, it)) }
                Text(stringResource(R.string.apk_unverified))
            }
        }
    }
}

@Composable
private fun AppIcon(app: CatalogApp) {
    val loader = LocalCatalogIcons.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, app.icon, loader) {
        value = app.icon?.let { loader?.load(it) }
    }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), contentDescription = stringResource(R.string.app_icon, app.name), modifier = Modifier.size(64.dp))
    else Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Icon(Icons.Default.Home, contentDescription = stringResource(R.string.fallback_icon, app.name),
            modifier = Modifier.padding(12.dp).size(40.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

private fun displayDate(time: Long): String = java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault())
    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm z", java.util.Locale.getDefault()))
