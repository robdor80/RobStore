package es.robertodorado.robstore.update

import es.robertodorado.robstore.catalog.*
import es.robertodorado.robstore.download.*
import es.robertodorado.robstore.inventory.*
import kotlinx.serialization.json.*
import java.security.MessageDigest

internal fun updateManifest(vararg changes: Pair<String, JsonElement>): ByteArray = buildJsonObject {
    put("schemaVersion", 1); put("channel", "release"); put("packageName", SelfUpdateChannel.RELEASE.packageName)
    put("versionCode", 2); put("versionName", "0.2"); put("apkAssetName", "RobStore.apk"); put("sha256", "a".repeat(64))
    changes.forEach { (key, value) -> put(key, value) }
}.toString().toByteArray()

internal class UpdateTransport : HttpTransport {
    var requests = mutableListOf<HttpRequest>()
    var repo = JsonObject(fixtureRepo(name = "RobStore", topics = emptyList()) + ("full_name" to JsonPrimitive("robdor80/RobStore")))
    var releases = listOf(updateRelease())
    var manifest: ByteArray? = updateManifest()
    var assets = listOf(fixtureAsset(name = "RobStore.apk", url = "https://github.com/robdor80/RobStore/releases/download/edition-october/RobStore.apk"))
    var intercept: ((HttpRequest) -> HttpResult?)? = null
    override fun execute(request: HttpRequest): HttpResult {
        requests += request
        intercept?.invoke(request)?.let { return it }
        val path = request.url.encodedPath
        return when {
            path == "/repos/robdor80/RobStore" -> HttpResult(200, repo.toString().toByteArray())
            Regex("/repos/robdor80/RobStore/releases/[0-9]+").matches(path) ->
                releases.singleOrNull { it.long("id").toString() == path.substringAfterLast('/') }
                    ?.let { HttpResult(200, it.toString().toByteArray()) } ?: HttpResult(404)
            path.endsWith("/releases") -> HttpResult(200, JsonArray(releases).toString().toByteArray())
            path.endsWith("/assets") -> HttpResult(200, JsonArray(assets).toString().toByteArray())
            path.endsWith("/robstore-update.json") -> manifest?.let { HttpResult(200, it) } ?: HttpResult(404)
            else -> error("Unexpected request $path")
        }
    }
}
internal fun updateRelease(prerelease: Boolean = false, draft: Boolean = false): JsonObject = JsonObject(
    fixtureRelease(prerelease = prerelease, draft = draft) +
        ("html_url" to JsonPrimitive("https://github.com/robdor80/RobStore/releases/tag/edition-october")))
internal fun ownInstalled(code: Long? = 1, channel: SelfUpdateChannel = SelfUpdateChannel.RELEASE) = InstallationQuery.Installed(
    InstalledApplication(channel.packageName, code, "local-$code", LauncherQuery.Unavailable))
internal fun ownCandidate(channel: SelfUpdateChannel = SelfUpdateChannel.RELEASE): SelfUpdateCandidate = SelfUpdateCandidate(
    channel, 10, channel.packageName, CatalogRelease(20, "edition-october", "2026-10-28T10:00:00Z", "Novedades", 2, "0.2"),
    CatalogApk(30, "RobStore.apk", APK_BYTES.size.toLong(), "https://github.com/robdor80/RobStore/releases/download/edition-october/RobStore.apk",
        MessageDigest.getInstance("SHA-256").digest(APK_BYTES).joinToString("") { "%02x".format(it) }))
