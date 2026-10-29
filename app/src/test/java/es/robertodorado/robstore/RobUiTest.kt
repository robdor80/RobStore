package es.robertodorado.robstore

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.junit.After
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

    private lateinit var controller: ActivityController<MainActivity>

    private fun render(content: @Composable () -> Unit) {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        controller.get().setContent(content = content)
    }

    @After fun tearDown() {
        if (::controller.isInitialized) controller.close()
    }

    @Test fun settingsNavigationAndReturn() {
        render { RobStyleTheme { RobStoreApp() } }
        compose.onNodeWithText("Aún no hay aplicaciones disponibles").assertIsDisplayed()
        compose.onNodeWithText("Refrescar catálogo").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Ajustes").performClick()
        compose.onNodeWithText("Comprobación automática").assertIsDisplayed()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText("Aún no hay aplicaciones disponibles").assertIsDisplayed()
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
        compose.onNodeWithText("Aún no hay aplicaciones disponibles").assertIsDisplayed()
    }

    @Test fun detailHasMetadataAndDisabledAction() {
        render { RobStyleTheme { AppDetailScreen(onBack = {}) } }
        compose.onNodeWithText("Versión instalada: sin datos").assertExists()
        compose.onNodeWithText("Versión disponible: sin datos").assertExists()
        compose.onNodeWithText("Acción no disponible").assertIsNotEnabled()
        compose.onNodeWithText("Novedades").assertExists()
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
}
