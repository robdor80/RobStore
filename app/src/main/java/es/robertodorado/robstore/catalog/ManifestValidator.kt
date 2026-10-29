package es.robertodorado.robstore.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
internal data class AppManifest(
    val schemaVersion: Int,
    val name: String,
    val description: String = "",
    val packageName: String,
    val icon: String = "robstore/icon.png",
    val apkAssetName: String,
)

internal object ManifestValidator {
    fun parse(bytes: ByteArray): AppManifest {
        if (bytes.size > CatalogPolicy.MAX_MANIFEST_BYTES) throw InvalidMetadata("Manifiesto demasiado grande")
        val manifest = try {
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            if (!boundedJsonNesting(text)) throw InvalidMetadata("JSON con anidamiento excesivo")
            val root = catalogJson.parseToJsonElement(text)
            if ((root as? JsonObject)?.get("schemaVersion") != JsonPrimitive(1)) {
                throw InvalidMetadata("schemaVersion no compatible o de tipo incorrecto")
            }
            catalogJson.decodeFromJsonElement<AppManifest>(root)
        } catch (_: SerializationException) {
            throw InvalidMetadata("JSON o campos de manifiesto incorrectos")
        } catch (_: CharacterCodingException) {
            throw InvalidMetadata("El manifiesto no es UTF-8 válido")
        }
        with(manifest) {
            if (schemaVersion != 1) throw InvalidMetadata("schemaVersion no compatible")
            if (!safeText(name, 120) || name.isBlank()) throw InvalidMetadata("Nombre incorrecto")
            if (!safeText(description, 4000)) throw InvalidMetadata("Descripción incorrecta")
            if (packageName.length > 255 || !Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+").matches(packageName)) {
                throw InvalidMetadata("packageName incorrecto")
            }
            if (!safePath(icon) || !(icon.endsWith(".png") || icon.endsWith(".webp"))) {
                throw InvalidMetadata("Ruta de icono insegura o formato no compatible")
            }
            if (!safeAssetName(apkAssetName)) throw InvalidMetadata("apkAssetName incorrecto")
        }
        return manifest
    }
}

internal fun safeText(value: String, max: Int): Boolean =
    value.length <= max && value.none { it.isISOControl() && it != '\n' && it != '\t' && it != '\r' }

internal fun safeRepository(value: String): Boolean =
    value.length in 1..100 && value != "." && value != ".." && Regex("[A-Za-z0-9_.-]+").matches(value)

internal fun safePath(value: String): Boolean = value.length in 1..240 &&
    value.split('/').all { it.isNotEmpty() && it != "." && it != ".." && Regex("[A-Za-z0-9_.-]+").matches(it) }

internal fun safeAssetName(value: String): Boolean = value.length in 5..160 &&
    Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*\\.apk").matches(value)

internal fun safeTag(value: String): Boolean = value.isNotBlank() && value.length <= 200 &&
    value.none { it.isISOControl() || it == '\\' } && value.split('/').none { it == "." || it == ".." || it.isEmpty() }

internal fun apiUrl(path: String): HttpUrl = "https://api.github.com/".toHttpUrlOrNull()!!.newBuilder()
    .addPathSegments(path).build()

internal fun contentUrl(repository: String, tag: String, path: String): HttpUrl {
    require(safeRepository(repository) && safeTag(tag) && safePath(path))
    return apiUrl("repos/${CatalogPolicy.OWNER}/$repository/contents/$path").newBuilder()
        .addQueryParameter("ref", tag).build()
}

/** Bound nesting before the JSON parser touches untrusted data; ignore braces inside strings. */
internal fun boundedJsonNesting(text: String): Boolean {
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> if (++depth > 64) return false
            '}', ']' -> if (--depth < 0) return false
        }
    }
    return depth == 0 && !quoted
}
