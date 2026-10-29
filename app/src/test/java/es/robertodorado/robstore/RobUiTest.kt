package es.robertodorado.robstore

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
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

    @org.junit.Before fun fakeNetwork() { disableActivityNetwork() }

    private lateinit var controller: ActivityController<MainActivity>

    private fun render(content: @Composable () -> Unit) {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        controller.get().setContent(content = content)
    }

    @After fun tearDown() {
        if (::controller.isInitialized) controller.close()
        CatalogRuntime.transportFactory = ::OkHttpTransport
    }

    @Test fun settingsNavigationAndReturn() {
        render { RobStyleTheme { RobStoreApp() } }
        compose.onNodeWithText("Aún no hay aplicaciones disponibles").assertIsDisplayed()
        compose.onNodeWithText("Refrescar catálogo").assertIsEnabled()
        compose.onNodeWithContentDescription("Ajustes").performClick()
        compose.onNodeWithText("Origen del catálogo").assertIsDisplayed()
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

    @Test fun homeCardOpensRealMetadataAndBack() {
        val state = CatalogState(snapshot = fixtureSnapshot(), loading = false)
        render { RobStyleTheme { RobStoreApp(state = state) } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Aplicación de prueba"))
        compose.onNodeWithText("Aplicación de prueba").performClick()
        compose.onNodeWithText("Detalle de aplicación").assertIsDisplayed()
        compose.onNodeWithText("Versión disponible: edition-october").assertExists()
        compose.onNodeWithText("Paquete Android: es.robertodorado.demo").assertExists()
        compose.onNodeWithText("Instalar · disponible en una fase posterior").assertIsNotEnabled()
        compose.onNodeWithText("Versión instalada", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Repositorio: https://github.com/robdor80/Demo").assertExists()
        compose.onNodeWithText("Cambios publicados para esta edición.").assertExists()
        compose.onNodeWithText("Adjunto: app-release.apk").assertExists()
        compose.onNodeWithText("Tamaño: 1234 bytes").assertExists()
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
        compose.onNodeWithText("Refrescar catálogo").performScrollTo().performClick()
        org.junit.Assert.assertEquals(1, count)
    }

    @Test fun cachedErrorAndEmptyAreDistinguished() {
        render { RobStyleTheme { HomeScreen(onSettings = {}, state = CatalogState(snapshot = fixtureSnapshot(), loading = false,
            stale = true, failed = true, message = "Sin conexión")) } }
        compose.onNodeWithText("Caché desactualizada. Los datos pueden haber cambiado.").assertExists()
        compose.onNodeWithText("Sin conexión").assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Aplicación de prueba"))
        compose.onNodeWithText("Aplicación de prueba").assertExists()
        compose.onNodeWithText("Aún no hay aplicaciones disponibles").assertDoesNotExist()
    }

    @Test fun firstRefreshShowsLoadingInsteadOfEmpty() {
        render { RobStyleTheme { HomeScreen(onSettings = {}, state = CatalogState(loading = false, refreshing = true)) } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Cargando aplicaciones…"))
        compose.onNodeWithText("Cargando aplicaciones…").assertExists()
        compose.onNodeWithText("Aún no hay aplicaciones disponibles").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Refrescar catálogo"))
        compose.onNodeWithText("Refrescar catálogo").assertIsNotEnabled()
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
