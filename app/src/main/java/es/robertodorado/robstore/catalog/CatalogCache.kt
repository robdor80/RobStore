package es.robertodorado.robstore.catalog

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString

interface CatalogCache {
    fun load(): CatalogSnapshot?
    fun save(snapshot: CatalogSnapshot)
    fun readGate(): RefreshGate
    fun updateGate(update: (RefreshGate) -> RefreshGate) = synchronized(this) { saveGate(update(readGate())) }
    fun saveGate(gate: RefreshGate)
}

/** Private, versioned JSON. Failed writes leave the previous file intact. */
class FileCatalogCache(private val directory: File) : CatalogCache {
    @Synchronized override fun load(): CatalogSnapshot? = read("catalog-v1.json", 4 * 1024 * 1024) {
        catalogJson.decodeFromString<CatalogSnapshot>(it).takeIf { snapshot ->
            snapshot.schemaVersion == 1 && snapshot.syncedAt > 0 && snapshot.apps.size <= 1000 &&
                snapshot.apps.map { app -> app.repositoryId }.distinct().size == snapshot.apps.size &&
                snapshot.apps.all(::validCachedApp)
        }
    }
    @Synchronized override fun save(snapshot: CatalogSnapshot) {
        require(snapshot.schemaVersion == 1)
        write("catalog-v1.json", catalogJson.encodeToString(snapshot), 4 * 1024 * 1024)
    }
    @Synchronized override fun readGate(): RefreshGate = read("refresh-v1.json", 1024) {
        catalogJson.decodeFromString<RefreshGate>(it)
    } ?: RefreshGate()
    @Synchronized override fun saveGate(gate: RefreshGate) {
        write("refresh-v1.json", catalogJson.encodeToString(gate), 1024)
    }

    private fun <T> read(name: String, limit: Int, decode: (String) -> T?): T? = try {
        val file = File(directory, name)
        if (!file.isFile || file.length() > limit) null else decode(file.readText(Charsets.UTF_8))
    } catch (_: java.io.IOException) { null } catch (_: IllegalArgumentException) { null }

    private fun write(name: String, text: String, limit: Int) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > limit) throw java.io.IOException("Caché demasiado grande")
        if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException("No se pudo crear la caché")
        val target = File(directory, name)
        val temp = File(directory, "$name.tmp")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temp.delete() }
    }
}

/** A syntactically valid but damaged JSON file must not crash Compose. */
private fun validCachedApp(app: CatalogApp): Boolean = try {
    app.repositoryId > 0 && safeRepository(app.repositoryName) &&
        app.repositoryUrl == "https://github.com/${CatalogPolicy.OWNER}/${app.repositoryName}" &&
        app.name.isNotBlank() && safeText(app.name, 120) && safeText(app.description, 4000) &&
        app.release.id > 0 && safeTag(app.release.tag) && publishedInstant(app.release.publishedAt).toEpochMilli() > 0 &&
        safeText(app.release.notes, 32 * 1024) && safeAssetName(app.apk.name) && app.apk.size in 1..CatalogPolicy.MAX_APK_BYTES &&
        (app.icon == null || (app.icon.repository == app.repositoryName && app.icon.releaseId == app.release.id &&
            app.icon.tag == app.release.tag && safePath(app.icon.path)))
} catch (_: InvalidMetadata) { false }
