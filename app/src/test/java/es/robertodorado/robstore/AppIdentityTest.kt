package es.robertodorado.robstore

import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppIdentityTest {
    private val application get() = RuntimeEnvironment.getApplication()
    private val isDebug get() = BuildConfig.BUILD_TYPE == "debug"

    @Test
    fun installedIdentityMatchesVariant() {
        val expectedId = if (isDebug) {
            "es.robertodorado.robstore.debug"
        } else {
            "es.robertodorado.robstore"
        }
        val expectedName = if (isDebug) "RobStore DEV" else "RobStore"

        assertEquals(expectedId, application.packageName)
        assertEquals(expectedName, application.getString(R.string.app_name))
        assertEquals(
            expectedName,
            application.packageManager.getApplicationLabel(application.applicationInfo),
        )
        assertEquals(isDebug, BuildConfig.VERSION_NAME.endsWith("-DEV"))
    }

    @Test
    fun entryActivityCreatesComposeContent() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.setup().visible().get()
            val content = activity.findViewById<ViewGroup>(android.R.id.content)

            assertTrue(content.getChildAt(0) is ComposeView)
            val bars = androidx.core.view.WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            org.junit.Assert.assertFalse(bars.isAppearanceLightStatusBars)
            org.junit.Assert.assertFalse(bars.isAppearanceLightNavigationBars)
        }
    }

    @Test
    fun launcherResourcesAreResolvable() {
        assertEquals(R.mipmap.ic_launcher, application.applicationInfo.icon)
        assertNotNull(application.getDrawable(R.mipmap.ic_launcher))
        assertNotNull(application.getDrawable(R.mipmap.ic_launcher_round))
    }

    @Test
    fun manifestExposesTheEntryActivity() {
        val launchIntent = application.packageManager
            .getLaunchIntentForPackage(application.packageName)

        assertNotNull(launchIntent)
        assertEquals(MainActivity::class.java.name, launchIntent?.component?.className)
    }
}
