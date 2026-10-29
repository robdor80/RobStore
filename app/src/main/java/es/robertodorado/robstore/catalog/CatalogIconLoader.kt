package es.robertodorado.robstore.catalog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded PNG/WebP only. No APK fetch or parsing; a missing/invalid icon is non-fatal. */
internal class CatalogIconLoader(private val directory: File, private val client: GitHubClient) {
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val missing = mutableSetOf<String>()

    suspend fun load(icon: CatalogIcon): Bitmap? = withContext(Dispatchers.IO) {
        val key = MessageDigest.getInstance("SHA-256").digest(
            "${icon.repository}/${icon.releaseId}/${icon.tag}/${icon.path}".toByteArray(),
        ).joinToString("") { "%02x".format(it) }
        synchronized(this@CatalogIconLoader) {
            memory.get(key)?.let { return@synchronized it }
            if (key in missing) return@synchronized null
            try {
                val file = File(directory, key)
                val saved = file.takeIf { it.isFile && it.length() <= CatalogPolicy.MAX_ICON_BYTES }?.readBytes()
                val bytes = saved ?: client.icon(icon)
                val bitmap = bytes?.let(::decode)
                if (bitmap == null) {
                    // Negative cache only for the session; a new Release has a different key.
                    missing += key
                    file.delete()
                    return@synchronized null
                }
                memory.put(key, bitmap)
                if (saved == null && (directory.isDirectory || directory.mkdirs())) {
                    file.writeBytes(bytes)
                    directory.listFiles()?.sortedBy { it.lastModified() }?.let { files ->
                        var total = files.sumOf { it.length() }
                        files.forEach { cached ->
                            if (total > 20 * 1024 * 1024) { total -= cached.length(); cached.delete() }
                        }
                    }
                }
                bitmap
            } catch (_: java.io.IOException) {
                // Transient network/rate-limit failures can be retried on a later screen visit.
                null
            } catch (_: IllegalArgumentException) { null }
        }
    }

    internal fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.size > CatalogPolicy.MAX_ICON_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType !in listOf("image/png", "image/webp") || bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048 ||
            bounds.outWidth.toLong() * bounds.outHeight > 4_194_304) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 256)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
