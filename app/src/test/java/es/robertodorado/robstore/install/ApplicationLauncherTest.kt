package es.robertodorado.robstore.install

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import es.robertodorado.robstore.inventory.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ApplicationLauncherTest {
    private val component = ComponentName("es.example.app", "es.example.app.Main")
    private fun installed(launcher: LauncherQuery) = InstallationQuery.Installed(
        InstalledApplication(component.packageName, 4, "4", launcher))
    @Test fun explicitOpenUsesOfficialMainLauncherIntentAndFreshQuery() = runTest {
        var queries = 0
        val opener = ApplicationLauncher(applications = { queries++; installed(LauncherQuery.Available(component)) })
        assertEquals(0, queries)
        var received: Intent? = null
        assertTrue(opener.open(component.packageName) { received = it })
        assertEquals(1, queries)
        assertEquals(component, received!!.component)
        assertEquals(Intent.ACTION_MAIN, received!!.action)
        assertTrue(received!!.hasCategory(Intent.CATEGORY_LAUNCHER))
    }
    @Test fun absenceVisibilityRestrictionsAndNoLauncherDoNotLaunch() = runTest {
        for (query in listOf(InstallationQuery.Absent, InstallationQuery.Unknown,
            installed(LauncherQuery.Unknown), installed(LauncherQuery.Unavailable))) {
            assertFalse(ApplicationLauncher(applications = { query }).open(component.packageName) { fail("Must not launch") })
        }
    }
    @Test fun availabilityChangesAndPlatformLaunchErrorsAreRecoverable() = runTest {
        var query: InstallationQuery = installed(LauncherQuery.Available(component))
        val opener = ApplicationLauncher(applications = { query })
        assertTrue(opener.open(component.packageName) {})
        query = InstallationQuery.Unknown
        assertFalse(opener.open(component.packageName) { fail("Package disappeared") })
        query = installed(LauncherQuery.Available(component))
        for (failure in listOf(ActivityNotFoundException(), SecurityException(), IllegalArgumentException())) {
            assertFalse(opener.open(component.packageName) { throw failure })
        }
    }
}
