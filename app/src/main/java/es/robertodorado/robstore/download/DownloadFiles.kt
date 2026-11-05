package es.robertodorado.robstore.download

import es.robertodorado.robstore.catalog.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Private paths are generated locally, never from a remote filename. A single coordinator owns this directory. */
internal class DownloadFiles(root: File,
    private val output: (File) -> FileOutputStream = { FileOutputStream(it) },
    private val available: (File) -> Long = { it.usableSpace },
) {
    val directory: File = root.canonicalFile
    companion object { const val RESERVE = 8 * 1024 * 1024L }
    val verifiedDirectory = File(directory, "verified")
    private fun ensureDirectories() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("No se pudo preparar el almacenamiento privado.")
        if (!verifiedDirectory.isDirectory && !verifiedDirectory.mkdirs()) throw IOException("No se pudo crear el directorio de APK preparados.")
        if (verifiedDirectory.canonicalFile != verifiedDirectory.absoluteFile) throw IOException("Directorio de APK no autorizado.")
    }
    fun cleanup() {
        ensureDirectories()
        // No resume across process death. At most one verified APK is retained during this session.
        (directory.listFiles() ?: throw IOException("Almacenamiento privado no disponible.")).forEach { if (it.isFile && !it.delete()) throw IOException("No se pudo limpiar un temporal.") }
        (verifiedDirectory.listFiles() ?: throw IOException("Almacenamiento privado no disponible.")).forEach { if (it.isFile && !it.delete()) throw IOException("No se pudo limpiar un APK anterior.") }
    }
    fun partial(): File {
        ensureDirectories()
        return File(directory, "${UUID.randomUUID()}.part")
    }
    fun space(required: Long) {
        if (available(directory) < required + RESERVE) throw IOException("Espacio privado insuficiente. Libera espacio y reintenta.")
    }
    fun receive(app: ReleaseArtifact, client: BinaryClient, cancellation: DownloadCancellation,
        progress: (Long, Long) -> Unit): File {
        val target = partial()
        try {
            space(app.apk.size)
            client.open(app, cancellation).use { response ->
                if (response.length != -1L && response.length != app.apk.size) throw DownloadSecurity("Content-Length distinto del tamaño esperado.")
                response.stream.use { input -> output(target).use { output ->
                    var received = 0L
                    val buffer = ByteArray(64 * 1024)
                    val started = System.nanoTime()
                    progress(0, app.apk.size)
                    while (true) {
                        cancellation.check()
                        if (System.nanoTime() - started > TimeLimit.NANOS) throw IOException("Se agotó el tiempo de descarga.")
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (received + count > app.apk.size || received + count > CatalogPolicy.MAX_APK_BYTES) {
                            throw DownloadSecurity("El archivo supera el tamaño permitido.")
                        }
                        space(count.toLong())
                        output.write(buffer, 0, count)
                        received += count
                        progress(received, app.apk.size)
                    }
                    cancellation.check()
                    if (received != app.apk.size) throw DownloadSecurity("Archivo incompleto: tamaño distinto del esperado.")
                    output.fd.sync()
                } }
            }
            return target
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }
}

private object TimeLimit { const val NANOS = 10 * 60 * 1_000_000_000L }
