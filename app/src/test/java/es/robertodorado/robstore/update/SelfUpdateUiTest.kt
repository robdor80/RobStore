package es.robertodorado.robstore.update

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import es.robertodorado.robstore.MainActivity
import es.robertodorado.robstore.catalog.*
import es.robertodorado.robstore.download.*
import es.robertodorado.robstore.ui.*
import es.robertodorado.robstore.ui.robstyle.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelfUpdateUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<MainActivity>
    @Before fun before() { disableActivityNetwork() }
    @After fun after() {
        if (::controller.isInitialized) controller.close()
        CatalogRuntime.transportFactory = ::OkHttpTransport
    }
    private fun render(font: Float = 1f, content: @Composable () -> Unit) {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        controller.get().setContent {
            RobStyleTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, font)) { content() }
            }
        }
    }
    private fun available() = SelfUpdateState(SelfUpdateChannel.RELEASE, true, ownInstalled().application,
        ownCandidate(), checkedAt = TEST_NOW, nextCheckAt = 1)
    @Test fun devHasExplanationWithoutMisleadingControls() {
        render { SettingsScreen({}, selfUpdate = SelfUpdateState(SelfUpdateChannel.DEV, false,
            ownInstalled(channel = SelfUpdateChannel.DEV).application, message = "Falta firma persistente")) }
        compose.onNodeWithText("Canal: DEV").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Falta firma persistente").performScrollTo().assertExists()
        compose.onNodeWithText("Comprobar actualizaciones").assertDoesNotExist()
        compose.onNodeWithText("Actualizar RobStore").assertDoesNotExist()
    }
    @Test fun updateAtLargeFontRequiresExplicitDownloadAndHasAccessibleTarget() {
        var downloads = 0
        render(2f) { SettingsScreen({}, selfUpdate = available(), onDownload = { downloads++ }) }
        assertEquals(0, downloads)
        compose.onNodeWithText("Actualizar RobStore").performScrollTo().assertIsDisplayed().assertHasClickAction()
        val bounds = compose.onNodeWithText("Actualizar RobStore").fetchSemanticsNode().boundsInRoot
        with(compose.density) { assertTrue(bounds.height >= 48 * density) }
        compose.onNodeWithText("Actualizar RobStore").performClick(); assertEquals(1, downloads)
    }
    @Test fun homeNoticeNavigatesToSettingsAndDoesNotDownload() {
        var downloads = 0
        render { RobStoreApp(state = CatalogState(loading = false), selfUpdate = available(), onDownloadSelfUpdate = { downloads++ }) }
        compose.onNodeWithText("Nueva versión de RobStore disponible").assertIsDisplayed()
        compose.onNodeWithText("Ver actualización de RobStore").performClick()
        compose.onNodeWithText("Versión disponible: 0.2 (2)").performScrollTo().assertIsDisplayed()
        assertEquals(0, downloads)
    }
    @Test fun downloadingShowsProgressAndCancellation() {
        var cancellations = 0
        render { SettingsScreen({}, selfUpdate = available(), download = DownloadState(DownloadCoordinator.SELF_UPDATE_ID,
            "RobStore", DownloadPhase.DOWNLOADING, 4, 8), onCancel = { cancellations++ }) }
        compose.onNodeWithText("Cancelar descarga").performScrollTo().performClick()
        assertEquals(1, cancellations)
    }
    @Test fun offlineKeepsVersionAndExplicitlyDatesPreviousInformation() {
        render { SettingsScreen({}, selfUpdate = available().copy(failed = true, message = "Sin conexión")) }
        compose.onNodeWithText("Información anterior conservada; puede estar desactualizada.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sin conexión").performScrollTo().assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w1280dp-h800dp-land") fun tabletLandscapeAtLargeFontKeepsUpdateActionReachable() {
        render(2f) { SettingsScreen({}, selfUpdate = available()) }
        compose.onNodeWithText("Actualizar RobStore").performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription("Volver").assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w800dp-h1280dp-port") fun tabletPortraitKeepsReleaseNotesAndActionReachable() {
        render { SettingsScreen({}, selfUpdate = available()) }
        compose.onNodeWithText("Novedades").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Actualizar RobStore").performScrollTo().assertIsDisplayed().assertHasClickAction()
    }
    @Test fun installedEqualVersionHidesUpdateButton() {
        render { SettingsScreen({}, selfUpdate = available().copy(installed = ownInstalled(2).application)) }
        compose.onNodeWithText("RobStore está actualizada.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Actualizar RobStore").assertDoesNotExist()
    }
}
