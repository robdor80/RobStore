package es.robertodorado.robstore

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import es.robertodorado.robstore.catalog.*
import es.robertodorado.robstore.download.*
import es.robertodorado.robstore.ui.*
import es.robertodorado.robstore.ui.robstyle.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DownloadUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<MainActivity>
    @Before fun before() { disableActivityNetwork() }
    @After fun after() {
        if (::controller.isInitialized) controller.close()
        CatalogRuntime.transportFactory = ::OkHttpTransport
    }
    private fun render(content: @Composable () -> Unit) {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        controller.get().setContent { RobStyleTheme { content() } }
    }
    @Test fun phasesExposeOnlyAppropriateExplicitActionsAndProgressSemantics() {
        val app = preparedApp()
        var phase by mutableStateOf(DownloadPhase.AVAILABLE)
        var downloads = 0; var cancels = 0; var confirms = 0; var installs = 0; var permissions = 0
        render { DownloadActions(app, DownloadState(app.repositoryId, app.name, phase, 4, 8),
            { downloads++ }, { cancels++ }, { confirms++ }, { installs++ }, { permissions++ }) }
        compose.onNodeWithText("Descargar APK").performClick(); assertEquals(1, downloads)
        compose.runOnIdle { phase = DownloadPhase.DOWNLOADING }
        compose.onNodeWithText("4 / 8 bytes (50 %)").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
            androidx.compose.ui.semantics.ProgressBarRangeInfo(0.5f, 0f..1f))).assertExists()
        compose.onNodeWithText("Cancelar descarga").performClick(); assertEquals(1, cancels)
        compose.runOnIdle { phase = DownloadPhase.CANCELLING }
        compose.onNodeWithText("Cancelar descarga").assertDoesNotExist()
        compose.runOnIdle { phase = DownloadPhase.NO_OFFICIAL_SHA }
        compose.onNodeWithText("Instalar").assertDoesNotExist()
        compose.onNodeWithText("Acepto continuar sin SHA oficial").performClick(); assertEquals(1, confirms)
        compose.runOnIdle { phase = DownloadPhase.READY }
        assertEquals(0, installs)
        compose.onNodeWithText("Instalar").performClick(); assertEquals(1, installs)
        compose.runOnIdle { phase = DownloadPhase.PERMISSION_PENDING }
        compose.onNodeWithText("Autorizar en Android").performClick(); assertEquals(1, permissions)
        compose.runOnIdle { phase = DownloadPhase.INSTALLER_OPEN }
        compose.onNodeWithText("Instalador abierto.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Instalar").assertDoesNotExist()
        compose.runOnIdle { phase = DownloadPhase.UNCONFIRMED }
        compose.onNodeWithText("Resultado de instalación no confirmado.", substring = true).assertIsDisplayed()
        for (error in listOf(DownloadPhase.ERROR, DownloadPhase.SECURITY_ERROR)) {
            compose.runOnIdle { phase = error }
            compose.onNodeWithText("Instalar").assertDoesNotExist()
            compose.onNodeWithText("Reintentar").performClick()
        }
        assertEquals(3, downloads)
    }
    @Test fun otherApplicationCannotStartWhileTransferIsActive() {
        val app = preparedApp()
        var downloads = 0; var cancels = 0
        render { DownloadActions(app, DownloadState(999, "Otra app", DownloadPhase.DOWNLOADING),
            { downloads++ }, { cancels++ }, {}, {}, {}) }
        compose.onNodeWithText("Descargar APK").assertDoesNotExist()
        compose.onNodeWithText("Hay una descarga en curso:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancelar descarga").performClick()
        assertEquals(0, downloads); assertEquals(1, cancels)
    }
    private fun enlargedEvidence(name: String) {
        val app = preparedApp()
        render {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppDetailScreen({}, app, download = DownloadState(app.repositoryId, app.name, DownloadPhase.NO_OFFICIAL_SHA))
            }
        }
        compose.onNodeWithText("Acepto continuar sin SHA oficial").performScrollTo().assertIsDisplayed().assertHasClickAction()
        val bounds = compose.onNodeWithText("Acepto continuar sin SHA oficial").fetchSemanticsNode().boundsInRoot
        with(compose.density) { assertTrue(bounds.height >= 48 * density) }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.runOnIdle {
            val view = controller.get().window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val output = java.io.File("build/rs04-evidence/${BuildConfig.BUILD_TYPE}/$name.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
    @Test @Config(qualifiers = "w320dp-h900dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun compactWarningAndActionRemainAccessibleAtTwoHundredPercent() = enlargedEvidence("no-sha-compact-200")

    @Test @Config(qualifiers = "w1200dp-h900dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun wideWarningAndActionRemainAccessibleAtTwoHundredPercent() = enlargedEvidence("no-sha-wide-200")

    @Test fun rotationRetainsViewModelAndNavigationKeepsTransferState() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val original = ViewModelProvider(controller.get())[CatalogViewModel::class.java]
        controller.recreate()
        assertSame(original, ViewModelProvider(controller.get())[CatalogViewModel::class.java])
        val app = preparedApp()
        val state = CatalogState(loading = false, snapshot = CatalogSnapshot(syncedAt = TEST_NOW, apps = listOf(app)))
        controller.get().setContent { RobStyleTheme { RobStoreApp(state = state,
            download = DownloadState(app.repositoryId, app.name, DownloadPhase.DOWNLOADING, 4, 8)) } }
        compose.onNodeWithText(app.name).performClick()
        compose.onNodeWithText("4 / 8 bytes (50 %)").assertExists()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText(app.name).performClick()
        compose.onNodeWithText("4 / 8 bytes (50 %)").assertExists()
    }
}
