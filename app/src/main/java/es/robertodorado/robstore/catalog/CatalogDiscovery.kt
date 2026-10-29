package es.robertodorado.robstore.catalog

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class CatalogDiscovery(private val client: GitHubClient, private val now: () -> Long) {
    fun fetch(): CatalogSnapshot {
        val session = client.discovery()
        val apps = mutableListOf<CatalogApp>()
        for (repository in session.repositories()) {
            val id = (repository["id"] as? JsonPrimitive)?.longOrNull ?: 0
            try {
                val owner = (repository["owner"] as? JsonObject)?.string("login")
                if (owner != CatalogPolicy.OWNER || repository.boolean("private") || repository.string("visibility") != "public" || repository.boolean("archived")) continue
                val topics = repository["topics"] as? JsonArray ?: throw InvalidMetadata("Topics incorrectos")
                if (topics.none { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content == CatalogPolicy.TOPIC }) continue
                val name = repository.string("name")
                if (!safeRepository(name)) throw InvalidMetadata("Nombre de repositorio incorrecto")
                val repoUrl = "https://github.com/${CatalogPolicy.OWNER}/$name"
                if (repository.string("html_url") != repoUrl) throw InvalidMetadata("Origen del repositorio incorrecto")
                apps += application(session, id, name, repoUrl)
            } catch (invalid: InvalidMetadata) {
                // Only contract errors exclude an individual repo. Transport and incomplete pages abort the snapshot.
                session.diagnostics += "Repositorio $id: ${invalid.message}"
            }
        }
        return CatalogSnapshot(syncedAt = now(), apps = apps.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            diagnostics = session.diagnostics)
    }

    private fun application(session: GitHubClient.Discovery, id: Long, name: String, url: String): CatalogApp {
        val stable = session.releases(name).filter { !it.boolean("draft") && !it.boolean("prerelease") }
            .filter { it["published_at"] != null && it["published_at"] != JsonNull }
        val release = stable.maxWithOrNull(compareBy<JsonObject> { publishedInstant(it.string("published_at")) }
            .thenBy { it.long("id") }) ?: throw InvalidMetadata("Sin Release estable publicada")
        val tag = release.string("tag_name")
        val releaseId = release.long("id")
        val publishedAt = release.string("published_at")
        if (releaseId <= 0 || !safeTag(tag) || publishedInstant(publishedAt).toEpochMilli() > now()) {
            throw InvalidMetadata("Metadatos de Release incorrectos")
        }
        val manifestBytes = session.manifest(name, tag) ?: throw InvalidMetadata("Sin robstore.json en la etiqueta publicada")
        val manifest = ManifestValidator.parse(manifestBytes)
        val assets = session.assets(name, releaseId)
        val matching = assets.filter { it.string("name") == manifest.apkAssetName }
        if (matching.size != 1) throw InvalidMetadata("APK ausente o ambiguo en la última Release estable")
        val asset = matching.single()
        val assetId = asset.long("id")
        val size = asset.long("size")
        val downloadUrl = asset.string("browser_download_url")
        val parsed = downloadUrl.toHttpUrlOrNull()
        if (assetId <= 0 || asset.string("state") != "uploaded" || size !in 1..CatalogPolicy.MAX_APK_BYTES ||
            parsed == null || parsed.scheme != "https" || parsed.host != "github.com" || parsed.port != 443 ||
            parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null ||
            parsed.pathSegments != listOf(CatalogPolicy.OWNER, name, "releases", "download", tag, manifest.apkAssetName) ||
            asset.string("content_type") !in listOf("application/vnd.android.package-archive", "application/octet-stream")) {
            throw InvalidMetadata("APK o URL oficial incorrectos")
        }
        val digest = when (val value = asset["digest"]) {
            null, JsonNull -> null
            else -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { Regex("sha256:[a-fA-F0-9]{64}").matches(it) }
                ?.substringAfter(':')?.lowercase() ?: throw InvalidMetadata("Digest de GitHub incorrecto")
        }
        val notes = when (release["body"]) { null, JsonNull -> ""; else -> release.string("body") }
        if (!safeText(notes, 32 * 1024)) throw InvalidMetadata("Notas de Release incorrectas o demasiado largas")
        return CatalogApp(id, name, url, manifest.name, manifest.description, manifest.packageName,
            CatalogRelease(releaseId, tag, publishedAt, notes), CatalogApk(assetId, manifest.apkAssetName, size, downloadUrl, digest),
            CatalogIcon(name, releaseId, tag, manifest.icon))
    }
}
