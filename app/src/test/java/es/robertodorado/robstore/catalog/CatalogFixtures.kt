package es.robertodorado.robstore.catalog

import kotlinx.serialization.json.*

internal const val TEST_NOW = 1_793_318_400_000L // 2026-10-30 UTC

internal fun fixtureRepo(id: Long = 10, name: String = "Demo", owner: String = "robdor80", private: Boolean = false,
    archived: Boolean = false, topics: List<String> = listOf("robstore-app")): JsonObject = buildJsonObject {
    put("id", id); put("name", name); put("owner", buildJsonObject { put("login", owner) })
    put("visibility", if (private) "private" else "public"); put("private", private); put("archived", archived); put("topics", JsonArray(topics.map(::JsonPrimitive)))
    put("html_url", "https://github.com/$owner/$name")
}
internal fun fixtureRelease(id: Long = 20, tag: String = "edition-october", date: String? = "2026-10-28T10:00:00Z",
    draft: Boolean = false, prerelease: Boolean = false): JsonObject = buildJsonObject {
    put("id", id); put("tag_name", tag); put("published_at", date?.let(::JsonPrimitive) ?: JsonNull)
    put("draft", draft); put("prerelease", prerelease); put("body", "Cambios publicados para esta edición.")
}
internal fun fixtureAsset(id: Long = 30, name: String = "app-release.apk", size: Long = 1234,
    state: String = "uploaded", url: String = "https://github.com/robdor80/Demo/releases/download/edition-october/$name"): JsonObject = buildJsonObject {
    put("id", id); put("name", name); put("size", size); put("state", state); put("browser_download_url", url)
    put("content_type", "application/vnd.android.package-archive"); put("digest", "sha256:" + "a".repeat(64))
}
internal fun fixtureManifest(vararg changes: Pair<String, JsonElement>): ByteArray = buildJsonObject {
    put("schemaVersion", 1); put("name", "Aplicación de prueba"); put("description", "Descripción del manifiesto publicado")
    put("packageName", "es.robertodorado.demo"); put("icon", "robstore/icon.png"); put("apkAssetName", "app-release.apk")
    changes.forEach { (key, value) -> put(key, value) }
}.toString().toByteArray()

internal class MemoryCache(var snapshot: CatalogSnapshot? = null) : CatalogCache {
    var gate = RefreshGate()
    var saves = 0
    var failSave = false
    override fun load() = snapshot
    override fun save(snapshot: CatalogSnapshot) {
        if (failSave) throw java.io.IOException("Disco lleno")
        this.snapshot = snapshot; saves++
    }
    override fun readGate() = gate
    override fun saveGate(gate: RefreshGate) { this.gate = gate }
}

internal class FixtureTransport : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    var repos: List<JsonObject> = listOf(fixtureRepo())
    var releases: List<JsonObject> = listOf(fixtureRelease())
    var assets: List<JsonObject> = listOf(fixtureAsset())
    var manifest: ByteArray? = fixtureManifest()
    var intercept: ((HttpRequest) -> HttpResult?)? = null
    override fun execute(request: HttpRequest): HttpResult {
        requests += request
        intercept?.invoke(request)?.let { return it }
        val path = request.url.encodedPath
        if (path.endsWith("/robstore.json")) return manifest?.let { HttpResult(200, it) } ?: HttpResult(404)
        val source = when {
            path.endsWith("/repos") -> repos
            path.endsWith("/releases") -> releases
            path.endsWith("/assets") -> assets
            else -> return HttpResult(404)
        }
        val page = request.url.queryParameter("page")!!.toInt()
        return HttpResult(200, JsonArray(source.drop((page - 1) * 100).take(100)).toString().toByteArray())
    }
}
internal fun fixtureSnapshot(): CatalogSnapshot = CatalogDiscovery(GitHubClient(FixtureTransport(), MemoryCache()) { TEST_NOW }) { TEST_NOW }.fetch()

internal fun disableActivityNetwork() {
    CatalogRuntime.transportFactory = { HttpTransport { HttpResult(200, "[]".toByteArray()) } }
}
