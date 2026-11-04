package es.robertodorado.robstore.inventory

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import es.robertodorado.robstore.catalog.CatalogRelease

/** Local, current-user observations only. Never persisted with the GitHub catalog. */
data class InstalledApplication(
    val packageName: String,
    val versionCode: Long?,
    val versionName: String?,
    val launcher: LauncherQuery,
)

sealed interface LauncherQuery {
    data class Available(val component: ComponentName) : LauncherQuery
    data object Unavailable : LauncherQuery
    data object Unknown : LauncherQuery
}

sealed interface InstallationQuery {
    data class Installed(val application: InstalledApplication) : InstallationQuery
    data object Absent : InstallationQuery
    data object Unknown : InstallationQuery
}

fun interface InstalledApplications { fun query(packageName: String): InstallationQuery }

/** MAIN/LAUNCHER visibility does not cover every package: NameNotFound on API 30+ is ambiguous. */
class AndroidInstalledApplications(private val packages: PackageManager) : InstalledApplications {
    @Suppress("DEPRECATION")
    override fun query(packageName: String): InstallationQuery = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packages.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else packages.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val launcher = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
            val matches = if (Build.VERSION.SDK_INT >= 33) {
                packages.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else packages.queryIntentActivities(intent, 0)
            matches.firstOrNull { it.activityInfo?.let { activity ->
                activity.packageName == packageName && activity.exported && activity.enabled &&
                    activity.applicationInfo?.enabled == true && activity.permission == null
            } == true }?.activityInfo?.let {
                LauncherQuery.Available(ComponentName(packageName, it.name))
            } ?: LauncherQuery.Unavailable
        } catch (_: RuntimeException) { LauncherQuery.Unknown }
        InstallationQuery.Installed(InstalledApplication(packageName, code.takeIf { it > 0 }, info.versionName, launcher))
    } catch (_: PackageManager.NameNotFoundException) {
        if (Build.VERSION.SDK_INT < 30) InstallationQuery.Absent else InstallationQuery.Unknown
    } catch (_: RuntimeException) { InstallationQuery.Unknown }
}

enum class VersionComparison {
    PUBLISHED_NEWER, SAME, PUBLISHED_OLDER, PUBLISHED_UNKNOWN, INSTALLED_VERSION_UNREADABLE,
    INSTALLATION_UNKNOWN, ABSENT,
}

fun compareVersions(published: CatalogRelease, installed: InstallationQuery): VersionComparison = when (installed) {
    InstallationQuery.Unknown -> VersionComparison.INSTALLATION_UNKNOWN
    InstallationQuery.Absent -> VersionComparison.ABSENT
    is InstallationQuery.Installed -> {
        val local = installed.application.versionCode
        val remote = published.versionCode
        when {
            local == null -> VersionComparison.INSTALLED_VERSION_UNREADABLE
            remote == null -> VersionComparison.PUBLISHED_UNKNOWN
            remote > local -> VersionComparison.PUBLISHED_NEWER
            remote < local -> VersionComparison.PUBLISHED_OLDER
            else -> VersionComparison.SAME
        }
    }
}
