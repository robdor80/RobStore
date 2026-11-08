package es.robertodorado.robstore.update

import es.robertodorado.robstore.catalog.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** P04 must explicitly enable DEV only after configuring a persistent distribution certificate. */
enum class SelfUpdateChannel(val packageName: String, val wireName: String) {
    RELEASE("es.robertodorado.robstore", "release"), DEV("es.robertodorado.robstore.debug", "dev");
    companion object {
        fun identify(packageName: String): SelfUpdateChannel? = entries.singleOrNull { it.packageName == packageName }
    }
}

@Serializable
internal data class UpdateManifest(
    val schemaVersion: Int,
    val channel: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkAssetName: String,
    val sha256: String,
)

@Serializable
data class SelfUpdateCandidate(
    val channel: SelfUpdateChannel,
    val repositoryId: Long,
    override val packageName: String,
    override val release: CatalogRelease,
    override val apk: CatalogApk,
) : ReleaseArtifact {
    override val repositoryName: String get() = "RobStore"
    internal fun valid(): Boolean = try {
        repositoryId > 0 && packageName == channel.packageName && release.id > 0 && safeTag(release.tag) &&
            publishedInstant(release.publishedAt).toEpochMilli() > 0 && safeText(release.notes, 32 * 1024) &&
            release.versionCode?.let { it > 0 } == true && release.versionName?.let(::safeVersionName) == true &&
            apk.sha256 != null && CatalogValidation.apk(repositoryName, release.tag, apk)
    } catch (_: InvalidMetadata) { false }
}

/** Independent of topics and the catalog. Every request remains bounded by the shared REST client. */
internal class SelfUpdateDiscovery(private val client: GitHubClient, private val now: () -> Long = System::currentTimeMillis) {
    fun fetch(channel: SelfUpdateChannel): SelfUpdateCandidate? {
        val session = client.discovery()
        val repositoryId = verifiedRepository(session)
        var invalidPublication = false
        val candidates = session.releases("RobStore").mapNotNull { release ->
            try { candidate(session, repositoryId, release, channel) }
            catch (_: InvalidMetadata) { invalidPublication = true; null }
        }
        if (candidates.isEmpty() && invalidPublication) throw InvalidMetadata("Información de publicación insuficiente o incorrecta")
        // Conflicting publications with the same versionCode are not a verifiable update.
        val newest = candidates.maxOfOrNull { it.release.versionCode!! } ?: return null
        return candidates.filter { it.release.versionCode == newest }.singleOrNull()
            ?: throw InvalidMetadata("Publicaciones ambiguas para versionCode")
    }

    fun revalidate(selected: SelfUpdateCandidate) {
        if (!selected.valid()) throw InvalidMetadata("Actualización propia no válida")
        val session = client.discovery()
        val repositoryId = verifiedRepository(session)
        // Solo la Release elegida necesita revisarse otra vez. Leer los contratos de
        // todas las Releases históricas en cada fase agotaba la cuota anónima de GitHub.
        // La firma, hash, contrato y versión siguen validándose antes de instalar.
        val release = session.releases("RobStore").singleOrNull { it.long("id") == selected.release.id }
            ?: throw InvalidMetadata("La Release seleccionada ya no está publicada")
        if (candidate(session, repositoryId, release, selected.channel) != selected) {
            throw InvalidMetadata("La Release o el contrato de actualización ha cambiado")
        }
    }

    private fun verifiedRepository(session: GitHubClient.Discovery): Long {
        val repo = session.repository("RobStore")
        if (repo.string("full_name") != "robdor80/RobStore" || repo.string("name") != "RobStore" ||
            (repo["owner"] as? JsonObject)?.string("login") != CatalogPolicy.OWNER || repo.boolean("private") ||
            repo.boolean("archived") || repo.string("visibility") != "public" ||
            repo.string("html_url") != "https://github.com/robdor80/RobStore" || repo.long("id") <= 0) {
            throw InvalidMetadata("Origen de autoactualización incorrecto")
        }
        return repo.long("id")
    }

    private fun candidate(session: GitHubClient.Discovery, repositoryId: Long, release: JsonObject,
        channel: SelfUpdateChannel): SelfUpdateCandidate? {
        if (release.boolean("draft") || release.boolean("prerelease") != (channel == SelfUpdateChannel.DEV)) return null
        val tag = release.string("tag_name")
        val id = release.long("id")
        val published = release.string("published_at")
        if (id <= 0 || !safeTag(tag) || publishedInstant(published).toEpochMilli() !in 1..now() ||
            release.string("html_url") != "https://github.com/robdor80/RobStore/releases/tag/$tag") throw InvalidMetadata("Release incorrecta")
        val bytes = session.updateManifest(tag) ?: throw InvalidMetadata("Sin contrato de actualización")
        val manifest = parse(bytes)
        if (manifest.channel != channel.wireName || manifest.packageName != channel.packageName) return null
        val asset = session.assets("RobStore", id).singleOrNull { it.string("name") == manifest.apkAssetName }
            ?: throw InvalidMetadata("APK ausente o ambiguo")
        if (asset.string("state") != "uploaded" || asset.string("content_type") !in
            setOf("application/vnd.android.package-archive", "application/octet-stream")) throw InvalidMetadata("APK no publicado")
        // The tag-bound contract is mandatory. GitHub's optional digest must agree if present.
        val digest = asset["digest"]
        if (digest != null && digest != JsonNull && asset.string("digest").lowercase() != "sha256:${manifest.sha256.lowercase()}") {
            throw InvalidMetadata("Referencias SHA-256 contradictorias")
        }
        val candidate = SelfUpdateCandidate(channel, repositoryId, manifest.packageName,
            CatalogRelease(id, tag, published, if (release["body"] == null || release["body"] == JsonNull) "" else release.string("body"),
                manifest.versionCode, manifest.versionName),
            CatalogApk(asset.long("id"), manifest.apkAssetName, asset.long("size"), asset.string("browser_download_url"), manifest.sha256.lowercase()))
        if (!candidate.valid()) throw InvalidMetadata("Contrato o APK incorrectos")
        return candidate
    }

    companion object {
        internal fun parse(bytes: ByteArray): UpdateManifest {
            try {
                if (bytes.size > CatalogPolicy.MAX_MANIFEST_BYTES) throw InvalidMetadata("Contrato demasiado grande")
                val text = bytes.decodeToString(throwOnInvalidSequence = true)
                if (!boundedJsonNesting(text)) throw InvalidMetadata("JSON incorrecto")
                val root = catalogJson.parseToJsonElement(text).jsonObject
                if (root["schemaVersion"] != JsonPrimitive(1) ||
                    root.long("versionCode") <= 0 || !Regex("[1-9][0-9]*").matches(root["versionCode"].toString())) {
                    throw InvalidMetadata("Esquema o versionCode incorrectos")
                }
                val manifest = catalogJson.decodeFromJsonElement<UpdateManifest>(root)
                if (SelfUpdateChannel.entries.none { it.wireName == manifest.channel && it.packageName == manifest.packageName } ||
                    !safeVersionName(manifest.versionName) || !safeAssetName(manifest.apkAssetName) ||
                    !Regex("[a-fA-F0-9]{64}").matches(manifest.sha256)) throw InvalidMetadata("Contrato incompatible")
                return manifest
            } catch (_: IllegalArgumentException) { throw InvalidMetadata("Contrato JSON incorrecto") }
            catch (_: CharacterCodingException) { throw InvalidMetadata("Contrato UTF-8 incorrecto") }
        }
    }
}
