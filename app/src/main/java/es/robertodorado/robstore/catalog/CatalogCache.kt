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
        catalogJson.decodeFromString<CatalogSnapshot>(it).let { snapshot ->
            // Una instalación anterior puede conservar RobStore como ficha en caché.
            snapshot.copy(apps = snapshot.apps.filterNot(::isStoreCatalogEntry))
        }.takeIf(CatalogValidation::snapshot)
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

    @Synchronized internal fun <T> read(name: String, limit: Int, decode: (String) -> T?): T? = try {
        val file = File(directory, name)
        if (!file.isFile || file.length() > limit) null else decode(file.readText(Charsets.UTF_8))
    } catch (_: java.io.IOException) { null } catch (_: IllegalArgumentException) { null }

    @Synchronized internal fun write(name: String, text: String, limit: Int) {
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
