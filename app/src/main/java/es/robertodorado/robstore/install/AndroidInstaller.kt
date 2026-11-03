package es.robertodorado.robstore.install

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import es.robertodorado.robstore.download.PreparedApk

/** Only Android's interactive installer receives a temporary, read-only content URI. */
internal class AndroidInstaller(private val context: Context) {
    fun authorized(): Boolean = context.packageManager.canRequestPackageInstalls()
    fun settings(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"))
    fun intent(apk: PreparedApk): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.apkfiles", apk.file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("APK preparado", uri) }
    }
}
