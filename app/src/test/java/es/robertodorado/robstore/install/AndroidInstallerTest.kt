package es.robertodorado.robstore.install

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.FileProvider
import es.robertodorado.robstore.download.*
import es.robertodorado.robstore.catalog.TEST_NOW
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33])
class AndroidInstallerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @org.junit.Before fun resetProviderForRobolectricSandbox() {
        // AndroidX caches canonical roots statically, while Robolectric gives each case a new dataDir.
        val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (field.get(null) as MutableMap<*, *>).clear()
        val authority = "${context.packageName}.apkfiles"
        val info = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        val provider = FileProvider().apply { attachInfo(this@AndroidInstallerTest.context, info); onCreate() }
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider)
    }
    private fun syntheticApk(): Pair<PreparedApk, DownloadFiles> {
        val files = DownloadFiles(File(context.filesDir, "apk-downloads"))
        files.cleanup()
        val partial = files.partial()
        val manifest = javaClass.getResourceAsStream("/rs04/manifest.b64")!!.use {
            Base64.getMimeDecoder().decode(it.readBytes())
        }
        ZipOutputStream(partial.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml")); zip.write(manifest); zip.closeEntry()
        }
        val app = preparedApp().let { it.copy(apk = it.apk.copy(size = partial.length())) }
        return PreparedApk.verify(partial, app, files, AndroidArchiveInspector(context.packageManager), DownloadCancellation(), TEST_NOW) to files
    }
    @Test fun officialAndroidParserRecognizesSyntheticPackageMetadata() {
        val (apk, files) = syntheticApk()
        assertEquals("es.robertodorado.demo", apk.metadata.packageName)
        assertEquals("7.0", apk.metadata.versionName)
        assertEquals(7L, apk.metadata.versionCode)
        apk.recheck(files, AndroidArchiveInspector(context.packageManager), DownloadCancellation(), TEST_NOW)
    }
    @Test fun officialAndroidParserRejectsCorruptArchive() {
        val file = File(context.cacheDir, "corrupt.apk").also { it.writeBytes(APK_BYTES) }
        assertNull(AndroidArchiveInspector(context.packageManager).inspect(file))
    }
    @Test fun installIntentUsesVariantAuthorityReadOnlyUriAndApkMime() {
        val (apk, _) = syntheticApk()
        val intent = AndroidInstaller(context).intent(apk)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${context.packageName}.apkfiles", intent.data!!.authority)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
        context.contentResolver.openInputStream(intent.data!!)!!.use { assertTrue(it.read() >= 0) }
    }
    @Test fun providerIsNotExportedAndCannotSharePartialsOrOtherPrivateFiles() {
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.apkfiles", 0)!!
        assertFalse(provider.exported); assertTrue(provider.grantUriPermissions)
        val files = DownloadFiles(File(context.filesDir, "apk-downloads"))
        for (file in listOf(files.partial(), File(context.filesDir, "catalog/catalog-v1.json"), File(context.cacheDir, "other.apk"))) {
            assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, provider.authority, file) }
        }
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions!!.toSet()
        assertTrue("android.permission.REQUEST_INSTALL_PACKAGES" in permissions)
        assertFalse(permissions.any { it.contains("WRITE_EXTERNAL") || it.contains("MANAGE_EXTERNAL") || it.contains("DELETE_PACKAGES") })
    }
    @Test fun permissionSettingsPointsToCorrectDebugOrReleasePackage() {
        val intent = AndroidInstaller(context).settings()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
        assertFalse(AndroidInstaller(context).authorized())
    }
    @Test fun absentOrRestrictedInstallerIsAnErrorAndSuccessfulDispatchDoesNotClaimInstallation() {
        val intent = Intent(Intent.ACTION_VIEW)
        var errors = 0
        var deliveries = 0
        launchSystemActivity(intent, { deliveries++ }, { errors++ })
        assertEquals(1, deliveries); assertEquals(0, errors)
        for (error in listOf(android.content.ActivityNotFoundException(), SecurityException(), IllegalArgumentException())) {
            launchSystemActivity(intent, { throw error }, { errors++ })
        }
        assertEquals(3, errors)
    }
}
