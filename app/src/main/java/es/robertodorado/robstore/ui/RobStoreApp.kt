package es.robertodorado.robstore.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import java.time.Clock
import kotlinx.coroutines.delay
import es.robertodorado.robstore.R
import es.robertodorado.robstore.catalog.*
import es.robertodorado.robstore.ui.robstyle.*

internal val LocalCatalogClock = staticCompositionLocalOf { Clock.systemDefaultZone() }

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
    onRefresh: (() -> Unit)? = null, busy: Boolean = false,
    content: @Composable BoxScope.() -> Unit) {
    Scaffold(topBar = { RobTopBar(title, onBack != null, { onBack?.invoke() }, onSettings, onRefresh, busy) }) { padding ->
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
    val busy = state.loading || state.refreshing
    val refresh = { if (!busy) onRefresh() }
    ScreenFrame(stringResource(R.string.app_name), onSettings = onSettings, onRefresh = refresh, busy = busy) {
        RobCatalogGrid(state, refresh, onOpenApp)
    }
}

@Composable
private fun RobCatalogGrid(state: CatalogState, onRefresh: () -> Unit, onOpenApp: (Long) -> Unit) {
    val timing = refreshTiming(state.nextRefreshAt)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(RobStyleDimensions.appTileMinWidth * fontScale),
        modifier = Modifier.widthIn(max = RobStyleDimensions.maxCatalogWidth).fillMaxSize().testTag("catalog_grid"),
        contentPadding = PaddingValues(RobStyleDimensions.catalogPadding),
        horizontalArrangement = Arrangement.spacedBy(RobStyleDimensions.contentSpacing),
        verticalArrangement = Arrangement.spacedBy(RobStyleDimensions.contentSpacing),
    ) {
        item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
            Text(stringResource(R.string.catalog), style = MaterialTheme.typography.titleLarge)
        }
        if ((state.snapshot != null && (state.stale || state.failed)) || timing == RefreshAvailability.Blocked || (state.message != null && !state.failed)) {
            item(key = "sync", span = { GridItemSpan(maxLineSpan) }) { RobSyncIndicator(state, compact = true, timing = timing) }
        }
        // A snapshot, including a valid empty one, takes priority over transient loading/errors.
        if (state.snapshot != null) {
            if (state.snapshot.apps.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                RobStatus(RobVisualState.Empty)
            }
            items(state.snapshot.apps, key = { it.repositoryId }) { app ->
                RobAppTile(app, onClick = { onOpenApp(app.repositoryId) })
            }
        } else {
            item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                when {
                    state.loading || state.refreshing -> RobStatus(RobVisualState.Loading)
                    state.failed -> RobStatus(RobVisualState.Error, onRetry = onRefresh)
                    else -> RobStatus(RobVisualState.Pending)
                }
            }
        }
        state.snapshot?.let { displayDate(it.syncedAt) }?.let { date ->
            item(key = "last_sync", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.last_sync, date), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun RobAppTile(app: CatalogApp, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("app_${app.repositoryId}"),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(app, size = RobStyleDimensions.catalogIconSize)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(app.name, style = MaterialTheme.typography.titleMedium, minLines = 2, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(app.release.tag, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Re-evaluate on resume and once at expiry; no polling or background refresh. */
@Composable
private fun refreshTiming(nextRefreshAt: Long): RefreshAvailability {
    val clock = LocalCatalogClock.current
    var observedAt by remember(nextRefreshAt, clock) { mutableLongStateOf(clock.millis()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { observedAt = clock.millis() }
    LaunchedEffect(nextRefreshAt, clock, observedAt) {
        val remaining = nextRefreshAt - clock.millis()
        if (nextRefreshAt > 0 && remaining > 0) {
            delay(remaining)
            observedAt = clock.millis()
        }
    }
    return refreshAvailability(nextRefreshAt, clock)
}

@Composable
internal fun RobSyncIndicator(state: CatalogState, technical: Boolean = false, compact: Boolean = false, timing: RefreshAvailability = refreshTiming(state.nextRefreshAt)) {
    val blocked = timing == RefreshAvailability.Blocked
    if (compact) {
        // Initial failures already have an error card. Cached failures/staleness need a
        // visible notice ahead of the grid, even if there are hundreds of applications.
        val summary = when {
            state.failed && state.snapshot != null -> if (state.stale) R.string.sync_failed_stale else R.string.sync_failed_cached
            state.stale && state.snapshot != null -> R.string.stale_cache
            blocked -> R.string.refresh_limited
            state.message != null && !state.failed -> if (timing == RefreshAvailability.Allowed) R.string.refresh_allowed else R.string.refresh_time_unknown
            else -> null
        }
        val showDeadline = blocked
        if (summary != null || showDeadline) {
            Surface(modifier = Modifier.fillMaxWidth().testTag("sync_notice").semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            }, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    summary?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (showDeadline) displayDate(state.nextRefreshAt)?.let {
                        Text(stringResource(R.string.next_refresh, it), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if ((state.refreshing || state.loading) && state.snapshot != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.refreshing), style = MaterialTheme.typography.bodySmall)
        }
        state.snapshot?.let { snapshot ->
            displayDate(snapshot.syncedAt)?.let {
                Text(stringResource(R.string.last_sync, it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.stale) Text(stringResource(R.string.stale_cache), style = MaterialTheme.typography.bodySmall)
        if (state.failed && state.snapshot != null) Text(stringResource(R.string.cached_after_failure),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (technical) {
            Text(stringResource(R.string.current_state), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(when {
                state.loading -> R.string.loading
                state.refreshing -> R.string.refreshing
                state.failed -> R.string.error
                state.snapshot != null -> R.string.catalog_ready
                else -> R.string.no_sync
            }))
        }
        if (technical || state.message != null) when (timing) {
            RefreshAvailability.Blocked -> displayDate(state.nextRefreshAt)?.let {
                Text(stringResource(R.string.next_refresh, it), style = MaterialTheme.typography.bodySmall)
            }
            RefreshAvailability.Allowed -> Text(stringResource(R.string.refresh_allowed), style = MaterialTheme.typography.bodySmall)
            RefreshAvailability.Unknown -> Text(stringResource(R.string.refresh_time_unknown), style = MaterialTheme.typography.bodySmall)
        }
        if (!technical && state.message != null && !state.failed && blocked) {
            Text(stringResource(R.string.refresh_limited), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, state: CatalogState = CatalogState(loading = false)) {
    Screen(stringResource(R.string.settings), onBack = onBack) {
        RobCard {
            RobSectionHeader(stringResource(R.string.origin_heading))
            Text(stringResource(R.string.origin_owner, CatalogPolicy.OWNER))
            Text(stringResource(R.string.origin_topic, CatalogPolicy.TOPIC))
            Text(stringResource(R.string.stable_channel))
        }
        RobCard {
            RobSectionHeader(stringResource(R.string.sync_heading))
            RobSyncIndicator(state, technical = true)
            if (state.snapshot == null && (state.loading || state.refreshing || state.failed)) Text(stringResource(R.string.no_sync))
            Text(stringResource(R.string.refresh_policy))
            Text(stringResource(R.string.offline_policy))
        }
        RobInfoSection(stringResource(R.string.diagnostics)) {
            state.message?.let { Text(it) }
            state.snapshot?.diagnostics?.takeIf { it.isNotEmpty() }?.let { diagnostics ->
                Text(stringResource(R.string.diagnostics_heading))
                diagnostics.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (state.message == null && state.snapshot?.diagnostics.isNullOrEmpty()) {
                Text(stringResource(R.string.no_diagnostics))
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
            RobDetailHeader(app)
            RobInfoSection(stringResource(R.string.description)) {
                Text(app.description.ifBlank { stringResource(R.string.no_description) })
            }
            RobInfoSection(stringResource(R.string.changelog)) {
                // Plain text only: no HTML, active Markdown or remote embedded resources.
                Text(app.release.notes.ifBlank { stringResource(R.string.no_changelog) })
            }
            RobInfoSection(stringResource(R.string.apk_heading)) {
                Text(stringResource(R.string.apk_unverified), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.package_value, app.packageName))
                Text(stringResource(R.string.repository_name, app.repositoryName))
                Text(stringResource(R.string.repository_value, app.repositoryUrl))
                Text(stringResource(R.string.apk_name_value, app.apk.name))
                Text(stringResource(R.string.apk_size_readable, displayApkSize(app.apk.size)))
                Text(stringResource(R.string.apk_url_value, app.apk.downloadUrl))
                app.apk.sha256?.let { Text(stringResource(R.string.apk_digest_value, it)) }
            }
            RobSyncIndicator(state)
        }
    }
}

@Composable
internal fun RobDetailHeader(app: CatalogApp) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AppIcon(app, size = RobStyleDimensions.detailIconSize)
        Text(app.name, style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.version_value, app.release.tag), color = MaterialTheme.colorScheme.primary)
        displayReleaseDate(app.release.publishedAt)?.let { Text(stringResource(R.string.published_value, it),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        RobCard {
            RobPrimaryButton(stringResource(R.string.install_later), enabled = false, onClick = {})
            Text(stringResource(R.string.available_later), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun RobInfoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    RobCard {
        RobSectionHeader(title)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
internal fun AppIcon(app: CatalogApp, size: androidx.compose.ui.unit.Dp = RobStyleDimensions.catalogIconSize) {
    val loader = LocalCatalogIcons.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, app.icon, loader) {
        value = null
        value = app.icon?.let { loader?.load(it) }
    }
    // The same bounds surround loading, official icons and fallback: no grid jumps.
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), contentDescription = stringResource(R.string.app_icon, app.name),
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Surface(modifier = Modifier.fillMaxSize(), shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant) {
            Icon(RobCatalogSymbol, contentDescription = stringResource(R.string.fallback_icon, app.name),
                modifier = Modifier.padding(size / 4), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
