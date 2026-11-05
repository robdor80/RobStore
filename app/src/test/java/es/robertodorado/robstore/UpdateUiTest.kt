package es.robertodorado.robstore

import android.content.ComponentName
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import es.robertodorado.robstore.catalog.*
import es.robertodorado.robstore.download.*
import es.robertodorado.robstore.inventory.*
import es.robertodorado.robstore.ui.*
import es.robertodorado.robstore.ui.robstyle.*
import org.junit.Assert.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<MainActivity>
    private val app = preparedApp().let { it.copy(release = it.release.copy(versionCode = 2, versionName = "dos")) }
    private fun status(code: Long?, launcher: Boolean = true) = CatalogApplicationStatus(app,
        InstallationQuery.Installed(InstalledApplication(app.packageName, code, "local",
            if (launcher) LauncherQuery.Available(ComponentName(app.packageName, "Main")) else LauncherQuery.Unavailable)))
    @Before fun before() { disableActivityNetwork() }
    @After fun after() {
        if (::controller.isInitialized) controller.close()
        CatalogRuntime.transportFactory = ::OkHttpTransport
    }
    private fun render(content: @Composable () -> Unit) {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        controller.get().setContent { RobStyleTheme { content() } }
    }
    @Test fun detailActionsFollowLocalEvidenceAndNeverOpenAutomatically() {
        var local by mutableStateOf<CatalogApplicationStatus?>(status(1))
        var opens = 0; var downloads = 0
        render { AppDetailScreen({}, app, status = local, onOpen = { opens++ }, onDownload = { downloads++ }) }
        assertEquals(0, opens); assertEquals(0, downloads)
        compose.onNodeWithText("Abrir").performScrollTo().performClick()
        compose.onNodeWithText("Actualizar").performScrollTo().performClick()
        assertEquals(1, opens); assertEquals(1, downloads)
        compose.runOnIdle { local = status(2) }
        compose.onNodeWithText("Actualizar").assertDoesNotExist()
        compose.runOnIdle { local = status(3, false) }
        compose.onNodeWithText("Abrir").assertDoesNotExist()
        compose.onNodeWithText("Sin actividad de inicio accesible.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { local = CatalogApplicationStatus(app, InstallationQuery.Unknown) }
        compose.onNodeWithText("Descargar APK").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Aplicación no instalada.").assertDoesNotExist()
        compose.runOnIdle { local = CatalogApplicationStatus(app, InstallationQuery.Absent) }
        compose.onNodeWithText("Instalar").performScrollTo().assertIsDisplayed()
    }
    private fun evidence(width: Int, font: Float) {
        val state = CatalogState(loading = false, snapshot = CatalogSnapshot(syncedAt = TEST_NOW, apps = listOf(app)))
        var local by mutableStateOf<CatalogApplicationStatus?>(status(1))
        render {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                RobStoreApp(state = state, installed = local?.let { mapOf(app.repositoryId to it) }.orEmpty())
            }
        }
        compose.onNodeWithTag("app_${app.repositoryId}").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Actualización disponible.").assertExists()
        compose.onNodeWithText("Instalada: local (1)").assertExists()
        capture("home-$width-$font")
        compose.onNodeWithTag("app_${app.repositoryId}").performClick()
        compose.onNodeWithText("Actualizar").performScrollTo().assertIsDisplayed().assertHasClickAction()
        val bounds = compose.onNodeWithText("Actualizar").fetchSemanticsNode().boundsInRoot
        with(compose.density) { assertTrue(bounds.height >= 48 * density) }
        capture("detail-$width-$font")
        for (comparison in VersionComparison.entries) {
            compose.runOnIdle { local = status(1).copy(comparison = comparison) }
            compose.onNodeWithText(applicationStatusText(local)).performScrollTo().assertIsDisplayed()
        }
        compose.runOnIdle { local = null }
        compose.onNodeWithText("Consulta local pendiente.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { local = status(2) }
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText("Instalada: local (2)").assertExists()
        compose.onNodeWithText("Instalada y actualizada.").assertExists()
        capture("home-refreshed-$width-$font")
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.runOnIdle {
            val view = controller.get().window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val file = java.io.File("build/rs04-evidence/${BuildConfig.BUILD_TYPE}/p02-$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
    @Test @Config(qualifiers = "w320dp-h900dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactTwoHundredPercent() = evidence(320, 2f)
    @Test @Config(qualifiers = "w393dp-h873dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun realmePortraitApproximation() = evidence(393, 1f)
    @Test @Config(qualifiers = "w876dp-h1370dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun tabS9PlusPortraitApproximation() = evidence(876, 1f)
    @Test @Config(qualifiers = "w1370dp-h876dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun tabS9PlusLandscapeTwoHundredPercent() = evidence(1370, 2f)
}
