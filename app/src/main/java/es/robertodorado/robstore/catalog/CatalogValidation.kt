package es.robertodorado.robstore.catalog

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One policy for discovery, disk cache and explicit download selection. */
internal object CatalogValidation {
    fun apk(repository: String, tag: String, apk: CatalogApk): Boolean {
        val url = apk.downloadUrl.toHttpUrlOrNull() ?: return false
        return safeRepository(repository) && safeTag(tag) && apk.id > 0 && safeAssetName(apk.name) &&
            apk.size in 1..CatalogPolicy.MAX_APK_BYTES &&
            (apk.sha256 == null || Regex("[a-fA-F0-9]{64}").matches(apk.sha256)) &&
            url.scheme == "https" && url.host == "github.com" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
            url.pathSegments == listOf(CatalogPolicy.OWNER, repository, "releases", "download", tag, apk.name) &&
            // Reject normalization of dot segments, backslashes or encoded separators.
            apk.downloadUrl == url.toString()
    }

    fun app(app: CatalogApp): Boolean = try {
        app.repositoryId > 0 && safeRepository(app.repositoryName) &&
            app.repositoryUrl == "https://github.com/${CatalogPolicy.OWNER}/${app.repositoryName}" &&
            app.name.isNotBlank() && safeText(app.name, 120) && safeText(app.description, 4000) &&
            safePackageName(app.packageName) && app.release.id > 0 && safeTag(app.release.tag) &&
            publishedInstant(app.release.publishedAt).toEpochMilli() > 0 && safeText(app.release.notes, 32 * 1024) &&
            (app.release.versionCode == null || app.release.versionCode > 0) &&
            (app.release.versionName == null || safeVersionName(app.release.versionName)) &&
            apk(app.repositoryName, app.release.tag, app.apk) &&
            (app.icon == null || (app.icon.repository == app.repositoryName && app.icon.releaseId == app.release.id &&
                app.icon.tag == app.release.tag && safePath(app.icon.path)))
    } catch (_: InvalidMetadata) { false }

    fun snapshot(snapshot: CatalogSnapshot): Boolean = snapshot.schemaVersion == 1 && snapshot.syncedAt > 0 &&
        snapshot.apps.size <= 1000 && snapshot.apps.map { it.repositoryId }.distinct().size == snapshot.apps.size &&
        snapshot.apps.all(::app)

    fun select(snapshot: CatalogSnapshot?, repositoryId: Long): CatalogApp {
        if (snapshot == null || !snapshot(snapshot)) throw InvalidMetadata("Catálogo inválido; vuelve a comprobarlo.")
        return snapshot.apps.singleOrNull { it.repositoryId == repositoryId && !isStoreCatalogEntry(it) }
            ?: throw InvalidMetadata("La aplicación no pertenece al catálogo actual.")
    }
}

internal fun safePackageName(value: String): Boolean = value.length <= 255 &&
    Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+").matches(value)
