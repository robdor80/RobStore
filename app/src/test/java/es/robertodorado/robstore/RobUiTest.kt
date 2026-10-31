package es.robertodorado.robstore

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.Box
import org.junit.Assert.*
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.junit.After
import es.robertodorado.robstore.catalog.*
import es.robertodorado.robstore.ui.*
import es.robertodorado.robstore.ui.robstyle.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RobUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @org.junit.Before fun fakeNetwork() {
        disableActivityNetwork()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private var testClock: Clock = Clock.fixed(Instant.ofEpochMilli(TEST_NOW), ZoneId.of("UTC"))
    private val originalLocale = Locale.getDefault()
    private val originalZone = TimeZone.getDefault()

    private lateinit var controller: ActivityController<MainActivity>

    private fun render(content: @Composable () -> Unit) {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        controller.get().setContent { CompositionLocalProvider(LocalCatalogClock provides testClock) { content() } }
    }

    @After fun tearDown() {
        if (::controller.isInitialized) controller.close()
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalZone)
        CatalogRuntime.transportFactory = ::OkHttpTransport
    }

    @Test fun settingsNavigationAndReturn() {
        render { RobStyleTheme { RobStoreApp() } }
        compose.onNodeWithText("Pendiente de primera sincronización").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refrescar catálogo").assertIsEnabled()
        compose.onNodeWithContentDescription("Ajustes").performClick()
        compose.onNodeWithText("Origen del catálogo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText("Pendiente de primera sincronización").assertIsDisplayed()
    }

    @Test fun detailRouteAndBack() {
        lateinit var nav: androidx.navigation.NavHostController
        render {
            nav = androidx.navigation.compose.rememberNavController()
            RobStyleTheme { RobStoreApp(nav) }
        }
        compose.runOnIdle { nav.navigate(RobRoutes.appDetail("internal/id")) }
        compose.onNodeWithText("Detalle de aplicación").assertIsDisplayed()
        compose.runOnIdle {
            org.junit.Assert.assertEquals("internal/id", nav.currentBackStackEntry?.arguments?.getString("appId"))
        }
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText("Pendiente de primera sincronización").assertIsDisplayed()
    }

    @Test fun homeCardOpensRealMetadataAndBack() {
        val state = CatalogState(snapshot = fixtureSnapshot(), loading = false)
        render { RobStyleTheme { RobStoreApp(state = state) } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Aplicación de prueba"))
        compose.onNodeWithText("Aplicación de prueba").performClick()
        compose.onNodeWithText("Detalle de aplicación").assertIsDisplayed()
        compose.onNodeWithText("Versión disponible: edition-october").assertExists()
        compose.onNodeWithText("Paquete Android: es.robertodorado.demo").performScrollTo().assertExists()
        compose.onNodeWithText("Instalar · disponible en una fase posterior").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Versión instalada", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Repositorio: https://github.com/robdor80/Demo").assertExists()
        compose.onNodeWithText("Cambios publicados para esta edición.").assertExists()
        compose.onNodeWithText("Adjunto: app-release.apk").assertExists()
        compose.onNodeWithText("Repositorio oficial: Demo").assertExists()
        compose.onNodeWithText("SHA-256 declarado por GitHub: " + "a".repeat(64)).assertExists()
        compose.onNodeWithText("APK sin verificación local de integridad ni firma. No se descarga ni instala en esta fase.").assertExists()
        compose.onNodeWithText("Tamaño: " + displayApkSize(1234)).assertExists()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithContentDescription("Ajustes").assertIsDisplayed()
        compose.onNodeWithText("Aplicación de prueba").assertIsDisplayed()
    }

    @Test fun missingIconUsesAccessibleRobStyleFallback() {
        val app = fixtureSnapshot().apps.single().copy(icon = null)
        render { RobStyleTheme { AppDetailScreen(onBack = {}, app = app) } }
        compose.onNodeWithContentDescription("Icono de sustitución de Aplicación de prueba").assertExists()
    }

    @Test fun refreshCallbackAndUpdatingStateAreReal() {
        var count = 0
        render { RobStyleTheme { HomeScreen(onSettings = {}, onRefresh = { count++ }) } }
        compose.onNodeWithContentDescription("Refrescar catálogo").performClick()
        org.junit.Assert.assertEquals(1, count)
    }

    @Test fun cachedErrorAndEmptyAreDistinguished() {
        render { RobStyleTheme { HomeScreen(onSettings = {}, state = CatalogState(snapshot = fixtureSnapshot(), loading = false,
            stale = true, failed = true, message = "Sin conexión")) } }
        compose.onNodeWithText("Comprobación fallida · caché desactualizada").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Aplicación de prueba"))
        compose.onNodeWithText("Aplicación de prueba").assertExists()
        compose.onNodeWithText("Aún no hay aplicaciones publicadas").assertDoesNotExist()
    }

    @Test fun firstRefreshShowsLoadingInsteadOfEmpty() {
        render { RobStyleTheme { HomeScreen(onSettings = {}, state = CatalogState(loading = false, refreshing = true)) } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Cargando aplicaciones…"))
        compose.onNodeWithText("Cargando aplicaciones…").assertExists()
        compose.onNodeWithText("Aún no hay aplicaciones publicadas").assertDoesNotExist()
        compose.onNodeWithContentDescription("Refrescar catálogo").assertIsNotEnabled()
    }

    @Test fun errorRetryIsAccessibleAndCallsHandler() {
        var retried = false
        render { RobStyleTheme { RobStatus(RobVisualState.Error) { retried = true } } }
        compose.onNodeWithText("No se pudo cargar el catálogo").assertIsDisplayed()
        compose.onNodeWithText("Reintentar").performClick()
        org.junit.Assert.assertTrue(retried)
    }

    @Test fun loadingHasProgressSemantics() {
        render { RobStyleTheme { RobStatus(RobVisualState.Loading) } }
        compose.onNodeWithText("Cargando aplicaciones…").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
            androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate,
        )).assertExists()
    }

    @Test fun successHasTextualStatus() {
        render { RobStyleTheme { RobStatus(RobVisualState.Success) } }
        compose.onNodeWithText("Catálogo preparado").assertIsDisplayed()
    }
    private fun gridFixture(count: Int = 30): CatalogState {
        val snapshot = fixtureSnapshot()
        val app = snapshot.apps.single()
        return CatalogState(loading = false, snapshot = snapshot.copy(apps = (1..count).map {
            app.copy(repositoryId = it.toLong(), name = "Aplicación $it con nombre largo", release = app.release.copy(tag = "edition-october-stable-2026-very-long"))
        }))
    }

    private fun checkGrid(width: Int, fontScale: Float, columns: Int) {
        val state = gridFixture()
        render { RobStyleTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.requiredWidth(width.dp)) { HomeScreen(onSettings = {}, state = state) }
            }
        } }
        compose.onNodeWithTag("app_1").assert(hasText("Aplicación 1 con nombre largo"))
            .assert(hasText("edition-october-stable-2026-very-long"))
        val nameLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNode(hasText("Aplicación 1 con nombre largo") and hasAnyAncestor(hasTestTag("app_1")), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(nameLayouts) }
        assertTrue(nameLayouts.isNotEmpty())
        assertTrue(nameLayouts.all { it.lineCount <= 2 })
        val versionLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNode(hasText("edition-october-stable-2026-very-long") and hasAnyAncestor(hasTestTag("app_1")), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(versionLayouts) }
        assertTrue(versionLayouts.isNotEmpty())
        assertTrue(versionLayouts.all { it.lineCount == 1 })
        compose.onNodeWithText(controller.get().getString(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Ajustes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refrescar catálogo").assertIsDisplayed()
        val first = compose.onNodeWithTag("app_1").fetchSemanticsNode().boundsInRoot
        val lastInRow = compose.onNodeWithTag("app_$columns").fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithTag("app_${columns + 1}").fetchSemanticsNode().boundsInRoot
        val grid = compose.onNodeWithTag("catalog_grid").fetchSemanticsNode().boundsInRoot
        with(compose.density) { assertTrue(grid.width <= 1000.dp.toPx() + 1f) }
        assertEquals(first.top, lastInRow.top, 1f)
        assertTrue(next.top >= first.bottom)
        assertTrue(first.width > 0)
        if (columns > 1) assertTrue(lastInRow.left >= first.right)
        saveNativeEvidence("home-${width}-${fontScale}")
        compose.onNodeWithTag("catalog_grid").performScrollToNode(hasTestTag("app_30"))
        compose.onNodeWithTag("app_30").assertExists().assertHasClickAction()
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test @Config(qualifiers = "w320dp-h900dp")
    fun compactGridHasTwoColumnsAndLongLabels() = checkGrid(320, 1f, 2)

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test @Config(qualifiers = "w600dp-h900dp")
    fun largePhoneAdaptsToThreeColumns() = checkGrid(600, 1f, 3)

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test @Config(qualifiers = "w1200dp-h1000dp")
    fun tabletGridUsesCappedWidthAndSixColumns() = checkGrid(1200, 1f, 6)

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test @Config(qualifiers = "w320dp-h1000dp")
    fun enlargedFontUsesOneColumn() = checkGrid(320, 2f, 1)

    @Test fun refreshingPreservesGridAndBlocksCallback() {
        var calls = 0
        val state = gridFixture(2).copy(refreshing = true)
        render { RobStyleTheme { HomeScreen(onSettings = {}, state = state, onRefresh = { calls++ }) } }
        compose.onNodeWithTag("app_1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refrescar catálogo").assertIsNotEnabled().performClick()
        assertEquals(0, calls)
        compose.onNodeWithText("Aún no hay aplicaciones publicadas").assertDoesNotExist()
    }

    @Test fun initialLoadingBlocksRefreshCallback() {
        var calls = 0
        render { RobStyleTheme { HomeScreen(onSettings = {}, state = CatalogState(), onRefresh = { calls++ }) } }
        compose.onNodeWithText("Cargando aplicaciones…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refrescar catálogo").assertIsNotEnabled().performClick()
        assertEquals(0, calls)
    }

    @Test fun errorWithoutCacheRetriesRealCallback() {
        var calls = 0
        render { RobStyleTheme { HomeScreen(onSettings = {}, state = CatalogState(loading = false, failed = true), onRefresh = { calls++ }) } }
        compose.onNodeWithText("No se pudo cargar el catálogo").assertIsDisplayed()
        compose.onNodeWithText("Reintentar").performClick()
        assertEquals(1, calls)
        compose.onNodeWithText("Aún no hay aplicaciones publicadas").assertDoesNotExist()
    }

    @Test fun validEmptyCacheSurvivesFailure() {
        val state = CatalogState(loading = false, failed = true, snapshot = fixtureSnapshot().copy(apps = emptyList()))
        render { RobStyleTheme { HomeScreen(onSettings = {}, state = state) } }
        compose.onNodeWithText("Aún no hay aplicaciones publicadas").assertExists()
        compose.onNodeWithText("Comprobación fallida · catálogo guardado disponible").assertExists()
        compose.onNodeWithText("Reintentar").assertDoesNotExist()
    }

    @Test fun refreshLimitDisplaysStructuredLocalTime() {
        val next = TEST_NOW + 300_000
        render { RobStyleTheme { HomeScreen(onSettings = {}, state = CatalogState(loading = false,
            message = "Aún no se permite otra comprobación.", nextRefreshAt = next)) } }
        compose.onNodeWithText("Próxima comprobación permitida: " + displayDate(next)).assertExists()
        compose.onNodeWithText("Comprobación aplazada. Espera hasta la próxima hora permitida.").assertExists()
        compose.onNodeWithText("Catálogo preparado").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun noSnapshotIsPendingUntilARealEmptySnapshotArrives() {
        var state by mutableStateOf(CatalogState(loading = false))
        render { RobStyleTheme { HomeScreen(onSettings = {}, state = state) } }
        compose.onNodeWithText("Pendiente de primera sincronización").assertIsDisplayed()
        compose.onNodeWithText("Todavía no hay una sincronización válida.").assertIsDisplayed()
        compose.onNodeWithText("Aún no hay aplicaciones publicadas").assertDoesNotExist()
        compose.onNodeWithText("Catálogo preparado").assertDoesNotExist()
        saveNativeEvidence("home-pending-sync")
        // Snapshot produced by the real RS-02 discovery with an empty GitHub response.
        val empty = CatalogDiscovery(GitHubClient(HttpTransport { HttpResult(200, "[]".toByteArray()) }, MemoryCache()) { TEST_NOW }) { TEST_NOW }.fetch()
        compose.runOnIdle { state = state.copy(snapshot = empty) }
        compose.onNodeWithText("Aún no hay aplicaciones publicadas").assertIsDisplayed()
        compose.onNodeWithText("Pendiente de primera sincronización").assertDoesNotExist()
    }

    private fun checkNoticesBeforeThirtyApps(fontScale: Float) {
        var state by mutableStateOf(gridFixture().copy(stale = true))
        render { RobStyleTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                HomeScreen(onSettings = {}, state = state)
            }
        } }
        fun checkVisible(text: String) {
            compose.onNodeWithText(text).assertIsDisplayed()
            compose.onNodeWithTag("app_1").assertIsDisplayed()
            compose.onNodeWithTag("app_30").assertDoesNotExist()
            val notice = compose.onNodeWithTag("sync_notice").fetchSemanticsNode()
            assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Polite,
                notice.config[androidx.compose.ui.semantics.SemanticsProperties.LiveRegion])
            assertTrue(notice.boundsInRoot.bottom <= compose.onNodeWithTag("app_1").fetchSemanticsNode().boundsInRoot.top)
        }
        checkVisible("Caché desactualizada. Los datos pueden haber cambiado.")
        saveNativeEvidence("home-30-stale-$fontScale")
        compose.runOnIdle { state = state.copy(failed = true, message = "Diagnóstico técnico sin conexión") }
        checkVisible("Comprobación fallida · caché desactualizada")
        compose.onNodeWithText("Caché desactualizada. Los datos pueden haber cambiado.").assertDoesNotExist()
        compose.onNodeWithText("Diagnóstico técnico sin conexión").assertDoesNotExist()
        saveNativeEvidence("home-30-error-$fontScale")
        compose.runOnIdle { state = state.copy(stale = false, failed = false, nextRefreshAt = TEST_NOW + 300_000) }
        checkVisible("Comprobación aplazada. Espera hasta la próxima hora permitida.")
        compose.onNodeWithText("Próxima comprobación permitida: " + displayDate(state.nextRefreshAt)).assertIsDisplayed()
        saveNativeEvidence("home-30-limited-$fontScale")
    }

    @Test @Config(qualifiers = "w360dp-h800dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun thirtyAppsKeepStaleErrorAndLimitedNoticesVisible() = checkNoticesBeforeThirtyApps(1f)

    @Test @Config(qualifiers = "w320dp-h1000dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun thirtyAppsKeepNoticesVisibleWithEnlargedFont() = checkNoticesBeforeThirtyApps(2f)

    @Test fun expiredAndMissingRefreshTimesDoNotAnnounceFutureCheck() {
        var next by mutableLongStateOf(TEST_NOW - 1)
        render { RobStyleTheme { HomeScreen(onSettings = {}, state = gridFixture().copy(
            message = "Aún no se permite otra comprobación.", nextRefreshAt = next)) } }
        compose.onNodeWithText("Ya puedes comprobar el catálogo.").assertIsDisplayed()
        compose.onNodeWithText("Próxima comprobación permitida:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Comprobación aplazada", substring = true).assertDoesNotExist()
        compose.runOnIdle { next = TEST_NOW }
        compose.onNodeWithText("Ya puedes comprobar el catálogo.").assertIsDisplayed()
        for (invalid in listOf(0L, -1L)) {
            compose.runOnIdle { next = invalid }
            compose.onNodeWithText("Hora de próxima comprobación no disponible.").assertIsDisplayed()
            compose.onNodeWithText("Ya puedes comprobar el catálogo.").assertDoesNotExist()
            compose.onNodeWithText("Próxima comprobación permitida:", substring = true).assertDoesNotExist()
        }
    }

    @Test fun visibleDeadlineExpiresWithoutPeriodicWorkOrRefresh() {
        var now = TEST_NOW
        testClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("UTC")
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
            override fun instant(): Instant = Instant.ofEpochMilli(now)
        }
        var calls = 0
        render { RobStyleTheme { HomeScreen(onSettings = {}, state = gridFixture().copy(
            message = "Intervalo mínimo", nextRefreshAt = TEST_NOW + 1000), onRefresh = { calls++ }) } }
        compose.onNodeWithText("Próxima comprobación permitida:", substring = true).assertIsDisplayed()
        now += 1000
        compose.mainClock.advanceTimeBy(1100)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100))
        compose.onNodeWithText("Ya puedes comprobar el catálogo.").assertIsDisplayed()
        compose.onNodeWithText("Próxima comprobación permitida:", substring = true).assertDoesNotExist()
        assertEquals(0, calls)
    }

    @Test fun futureGateWithoutDiagnosticIsVisibleBeforeApps() {
        render { RobStyleTheme { HomeScreen(onSettings = {}, state = gridFixture().copy(nextRefreshAt = TEST_NOW + 300_000)) } }
        compose.onNodeWithText("Comprobación aplazada. Espera hasta la próxima hora permitida.").assertIsDisplayed()
        compose.onNodeWithTag("app_1").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("sync_notice").fetchSemanticsNode().boundsInRoot.bottom <=
            compose.onNodeWithTag("app_1").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun resumeRechecksExpiredGateWithoutNetwork() {
        var now = TEST_NOW
        testClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("UTC")
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
            override fun instant(): Instant = Instant.ofEpochMilli(now)
        }
        var calls = 0
        render { RobStyleTheme { HomeScreen(onSettings = {}, state = gridFixture().copy(
            message = "Intervalo mínimo", nextRefreshAt = TEST_NOW + 300_000), onRefresh = { calls++ }) } }
        compose.onNodeWithText("Próxima comprobación permitida:", substring = true).assertIsDisplayed()
        compose.runOnIdle { controller.pause(); now += 300_001; controller.resume() }
        compose.onNodeWithText("Ya puedes comprobar el catálogo.").assertIsDisplayed()
        compose.onNodeWithText("Próxima comprobación permitida:", substring = true).assertDoesNotExist()
        assertEquals(0, calls)
    }

    @Test fun settingsKeepsTechnicalWarningsAndDistinguishesRefreshTiming() {
        var next by mutableLongStateOf(TEST_NOW + 300_000)
        render { RobStyleTheme { SettingsScreen(onBack = {}, state = gridFixture().copy(stale = true,
            failed = true, message = "Diagnóstico técnico sin conexión", nextRefreshAt = next)) } }
        compose.onNodeWithText("Caché desactualizada. Los datos pueden haber cambiado.").assertExists()
        compose.onNodeWithText("No se pudo completar la comprobación. Se conserva el catálogo anterior.").assertExists()
        compose.onNodeWithText("Diagnóstico técnico sin conexión").performScrollTo().assertExists()
        compose.onNodeWithText("Próxima comprobación permitida:", substring = true).assertExists()
        compose.runOnIdle { next = TEST_NOW }
        compose.onNodeWithText("Ya puedes comprobar el catálogo.").assertExists()
        compose.onNodeWithText("Próxima comprobación permitida:", substring = true).assertDoesNotExist()
        compose.runOnIdle { next = 0 }
        compose.onNodeWithText("Hora de próxima comprobación no disponible.").assertExists()
    }

    @Test fun removedAppAndAndroidBackReturnToHome() {
        var state by mutableStateOf(CatalogState(loading = false, snapshot = fixtureSnapshot()))
        render { RobStyleTheme { RobStoreApp(state = state) } }
        compose.onNodeWithText("Aplicación de prueba").performClick()
        compose.runOnIdle { state = state.copy(snapshot = state.snapshot!!.copy(apps = emptyList())) }
        compose.onNodeWithText("Esta aplicación no está disponible en el catálogo actual.").assertIsDisplayed()
        compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Ajustes").assertIsDisplayed()
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun officialIconLoadsThroughExistingLoaderWithFakeHttp() {
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val cache = MemoryCache()
        val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequest>()
        val transport = HttpTransport { request -> requests += request; HttpResult(200, bytes) }
        val directory = java.nio.file.Files.createTempDirectory("rs03-icons").toFile()
        try {
            val loader = CatalogIconLoader(directory, GitHubClient(transport, cache) { TEST_NOW })
            render { RobStyleTheme { CompositionLocalProvider(LocalCatalogIcons provides loader) {
                HomeScreen(onSettings = {}, state = CatalogState(loading = false, snapshot = fixtureSnapshot()))
            } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Icono de Aplicación de prueba").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Icono de Aplicación de prueba").assertExists()
            assertTrue(requests.isNotEmpty())
            assertTrue(requests.all { !it.url.encodedPath.endsWith(".apk") })
        } finally { directory.deleteRecursively() }
    }

    @Test fun settingsRetainsLongDiagnosticsAndOmitsZeroDates() {
        val diagnostic = "Repositorio excluido: " + "metadatos incompletos ".repeat(80)
        render { RobStyleTheme { SettingsScreen(onBack = {}, state = CatalogState(loading = false,
            snapshot = fixtureSnapshot().copy(syncedAt = 0, diagnostics = listOf(diagnostic)))) } }
        compose.onNodeWithText(diagnostic).performScrollTo().assertExists()
        compose.onNodeWithText("Última sincronización:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Próxima comprobación permitida:", substring = true).assertDoesNotExist()
    }

    @Test fun invalidOfficialIconUsesFallbackWithoutChangingBounds() {
        val directory = java.nio.file.Files.createTempDirectory("rs03-bad-icon").toFile()
        val attempted = java.util.concurrent.atomic.AtomicBoolean()
        val loader = CatalogIconLoader(directory, GitHubClient(HttpTransport {
            attempted.set(true)
            HttpResult(200, "invalid image".toByteArray())
        }, MemoryCache()) { TEST_NOW })
        try {
            render { RobStyleTheme { CompositionLocalProvider(LocalCatalogIcons provides loader) {
                AppDetailScreen(onBack = {}, app = fixtureSnapshot().apps.single())
            } } }
            val fallback = compose.onNodeWithContentDescription("Icono de sustitución de Aplicación de prueba")
            fallback.assertExists()
            val bounds = fallback.fetchSemanticsNode().boundsInRoot
            compose.waitUntil(10_000) { attempted.get() }
            compose.waitForIdle()
            fallback.assertExists()
            assertEquals(bounds, fallback.fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithContentDescription("Icono de Aplicación de prueba").assertDoesNotExist()
        } finally { directory.deleteRecursively() }
    }

    private fun saveNativeEvidence(name: String) {
        lateinit var bitmap: android.graphics.Bitmap
        compose.runOnIdle {
            val view = controller.get().window.decorView
            bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
        }
        val output = java.io.File("build/rs03-evidence/${BuildConfig.BUILD_TYPE}/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(output.length() > 0)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun nativeHomeVisualEvidence() {
        var state by mutableStateOf(gridFixture(4))
        render { RobStyleTheme { HomeScreen(onSettings = {}, state = state) } }
        saveNativeEvidence("home-compact")
        compose.runOnIdle { state = state.copy(snapshot = state.snapshot!!.copy(apps = emptyList())) }
        compose.onNodeWithText("Aún no hay aplicaciones publicadas").assertIsDisplayed()
        saveNativeEvidence("home-empty")
        compose.runOnIdle { state = gridFixture(4).copy(stale = true, failed = true, message = "Sin conexión") }
        compose.onNodeWithText("Comprobación fallida · caché desactualizada").assertIsDisplayed()
        saveNativeEvidence("home-cached-error")
    }

    @Test
    @Config(qualifiers = "w360dp-h900dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun nativeDetailAndSettingsEvidence() {
        var screen by mutableIntStateOf(0)
        val app = fixtureSnapshot().apps.single()
        val state = CatalogState(loading = false, snapshot = fixtureSnapshot().copy(
            diagnostics = listOf("Repositorio excluido: metadatos incompletos. ".repeat(12))))
        render { RobStyleTheme {
            when (screen) {
                0 -> AppDetailScreen(onBack = {}, app = app)
                1 -> AppDetailScreen(onBack = {}, app = app.copy(name = "Aplicación con un nombre especialmente largo",
                    description = (app.description + "\n\n").repeat(20),
                    release = app.release.copy(notes = (app.release.notes + "\n\n").repeat(20))))
                else -> SettingsScreen(onBack = {}, state = state)
            }
        } }
        compose.onNodeWithText("Instalar · disponible en una fase posterior").assertIsNotEnabled()
        saveNativeEvidence("detail")
        compose.onNodeWithText("SHA-256 declarado por GitHub: " + "a".repeat(64)).performScrollTo().assertIsDisplayed()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        saveNativeEvidence("detail-technical")
        compose.runOnIdle { screen = 1 }
        compose.onNodeWithText("Aplicación con un nombre especialmente largo").performScrollTo()
        saveNativeEvidence("detail-long")
        compose.runOnIdle { screen = 2 }
        compose.onNodeWithText("Origen del catálogo").assertIsDisplayed()
        saveNativeEvidence("settings")
    }
}
