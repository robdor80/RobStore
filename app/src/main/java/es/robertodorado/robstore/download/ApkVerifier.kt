package es.robertodorado.robstore.download

import android.content.pm.PackageManager
import android.os.Build
import es.robertodorado.robstore.catalog.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal data class ArchiveMetadata(val packageName: String, val versionName: String?, val versionCode: Long)
internal fun interface ArchiveInspector { fun inspect(file: File): ArchiveMetadata? }

/** Parsing is not cryptographic signature verification. Android's installer makes the final decision. */
internal class AndroidArchiveInspector(private val packages: PackageManager) : ArchiveInspector {
    @Suppress("DEPRECATION")
    override fun inspect(file: File): ArchiveMetadata? {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packages.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else packages.getPackageArchiveInfo(file.absolutePath, 0)
        if (info?.applicationInfo == null) return null
        return ArchiveMetadata(info.packageName ?: return null, info.versionName,
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
    }
}

/** Only this verifier can mint a prepared-file capability; UI state never contains filesystem paths. */
internal class PreparedApk private constructor(
    val file: File,
    val app: CatalogApp,
    val localSha256: String,
    val metadata: ArchiveMetadata,
    val preparedAt: Long,
) {
    val officialDigestMatched get() = app.apk.sha256 != null
    companion object {
        fun verify(partial: File, app: CatalogApp, files: DownloadFiles, inspector: ArchiveInspector,
            token: DownloadCancellation, now: Long): PreparedApk {
            if (partial.parentFile?.canonicalFile != files.directory.canonicalFile ||
                partial.canonicalFile != partial.absoluteFile || !partial.name.endsWith(".part")) {
                throw DownloadSecurity("Ruta de archivo no autorizada.")
            }
            try {
                val result = inspect(partial, app, inspector, token)
                val final = File(files.verifiedDirectory, "${UUID.randomUUID()}.apk")
                // Same private filesystem: publication happens only after every check succeeds.
                Files.move(partial.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
                return PreparedApk(final, app, result.first, result.second, now)
            } catch (failure: Throwable) { partial.delete(); throw failure }
        }
        private fun inspect(file: File, app: CatalogApp, inspector: ArchiveInspector,
            token: DownloadCancellation): Pair<String, ArchiveMetadata> {
            if (!CatalogValidation.app(app) || !file.isFile || file.length() != app.apk.size) {
                throw DownloadSecurity("Tamaño o metadatos del archivo incorrectos.")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                if (input.read(magic) != 4 || !magic.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) {
                    throw DownloadSecurity("Formato APK no reconocible.")
                }
                digest.update(magic)
                val buffer = ByteArray(64 * 1024)
                var total = 4L
                while (true) {
                    token.check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > app.apk.size || total > CatalogPolicy.MAX_APK_BYTES) throw DownloadSecurity("Tamaño alterado durante la verificación.")
                    digest.update(buffer, 0, count)
                }
                if (total != app.apk.size) throw DownloadSecurity("Archivo incompleto.")
            }
            token.check()
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (app.apk.sha256 != null && !hash.equals(app.apk.sha256, true)) throw DownloadSecurity("SHA-256 distinto de la referencia oficial.")
            val metadata = inspector.inspect(file) ?: throw DownloadSecurity("Android no reconoce este APK.")
            if (metadata.packageName != app.packageName) throw DownloadSecurity("El paquete Android no coincide con el catálogo.")
            if (app.release.versionCode != null && metadata.versionCode != app.release.versionCode) {
                throw DownloadSecurity("El versionCode del APK no coincide con el catálogo.")
            }
            token.check()
            return hash to metadata
        }
    }
    fun recheck(files: DownloadFiles, inspector: ArchiveInspector, token: DownloadCancellation, now: Long) {
        try {
            if (now < preparedAt || now - preparedAt >= 24 * 60 * 60 * 1000L ||
                file.parentFile?.canonicalFile != files.verifiedDirectory.canonicalFile || file.canonicalFile != file.absoluteFile) {
                throw DownloadSecurity("El APK ha caducado o su ruta no es válida. Descárgalo de nuevo.")
            }
            val result = inspect(file, app, inspector, token)
            if (result.first != localSha256 || result.second != metadata) throw DownloadSecurity("El archivo preparado ha cambiado.")
        } catch (failure: Throwable) { file.delete(); throw failure }
    }
}
