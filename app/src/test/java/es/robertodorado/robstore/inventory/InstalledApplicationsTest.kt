package es.robertodorado.robstore.inventory

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 30, 33, 36])
class InstalledApplicationsTest {
    private val name = "es.example.catalog"
    private val packages get() = RuntimeEnvironment.getApplication().packageManager

    @Suppress("DEPRECATION")
    private fun install(launcher: Boolean, code: Long = 42) {
        val info = PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply { packageName = name; enabled = true }
            versionCode = code.toInt()
            if (android.os.Build.VERSION.SDK_INT >= 28) longVersionCode = code
            versionName = "2.0"
        }
        shadowOf(packages).installPackage(info)
        if (launcher) {
            val activity = ActivityInfo().apply {
                packageName = name; this.name = "es.example.catalog.MainActivity"
                exported = true; enabled = true; applicationInfo = info.applicationInfo
            }
            shadowOf(packages).addResolveInfoForIntent(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(name),
                ResolveInfo().apply { activityInfo = activity },
            )
        }
    }

    @Test fun readsVersionAndLauncherUsingPlatformAppropriateApis() {
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) (1L shl 33) + 42 else 42L
        install(true, code)
        val observed = AndroidInstalledApplications(packages).query(name) as InstallationQuery.Installed
        assertEquals(name, observed.application.packageName)
        assertEquals(code, observed.application.versionCode)
        assertEquals("2.0", observed.application.versionName)
        assertTrue(observed.application.launcher is LauncherQuery.Available)
    }

    @Test fun visibleNonLauncherApplicationStillHasReadableVersion() {
        install(false)
        val observed = AndroidInstalledApplications(packages).query(name) as InstallationQuery.Installed
        assertEquals(42L, observed.application.versionCode)
        assertEquals(LauncherQuery.Unavailable, observed.application.launcher)
    }

    @Test fun notFoundIsAmbiguousWithPackageVisibilityRestrictions() {
        assertEquals(if (android.os.Build.VERSION.SDK_INT >= 30) InstallationQuery.Unknown else InstallationQuery.Absent,
            AndroidInstalledApplications(packages).query(name))
    }

    @Test
    @Config(shadows = [DeniedPackageManager::class])
    fun securityFailureIsUnknownNeverAbsent() {
        assertEquals(InstallationQuery.Unknown, AndroidInstalledApplications(packages).query(name))
    }

    @org.robolectric.annotation.Implements(className = "android.app.ApplicationPackageManager")
    class DeniedPackageManager : org.robolectric.shadows.ShadowApplicationPackageManager() {
        @org.robolectric.annotation.Implementation
        override fun getPackageInfo(packageName: String, flags: Int): PackageInfo? {
            if (packageName == "es.example.catalog") throw SecurityException("denied")
            return super.getPackageInfo(packageName, flags)
        }

        @org.robolectric.annotation.Implementation(minSdk = 33)
        override fun getPackageInfo(packageName: String,
            @org.robolectric.annotation.ClassName("android.content.pm.PackageManager\$PackageInfoFlags") flags: Any,
        ): PackageInfo? {
            if (packageName == "es.example.catalog") throw SecurityException("denied")
            return super.getPackageInfo(packageName, flags)
        }
    }

    @Test
    @Config(shadows = [DeniedLauncherPackageManager::class])
    fun launcherQueryFailureRetainsInstalledPackageAndVersion() {
        install(false)
        val observed = AndroidInstalledApplications(packages).query(name) as InstallationQuery.Installed
        assertEquals(42L, observed.application.versionCode)
        assertEquals(LauncherQuery.Unknown, observed.application.launcher)
    }

    @org.robolectric.annotation.Implements(className = "android.app.ApplicationPackageManager")
    class DeniedLauncherPackageManager : org.robolectric.shadows.ShadowApplicationPackageManager() {
        @org.robolectric.annotation.Implementation
        override fun queryIntentActivities(intent: Intent, flags: Int): List<ResolveInfo> {
            if (intent.`package` == "es.example.catalog") throw SecurityException("denied")
            return super.queryIntentActivities(intent, flags)
        }
    }

    @Test fun unreadableVersionDoesNotEraseKnownInstallation() {
        install(false, 0)
        val observed = AndroidInstalledApplications(packages).query(name) as InstallationQuery.Installed
        assertNull(observed.application.versionCode)
    }
}
