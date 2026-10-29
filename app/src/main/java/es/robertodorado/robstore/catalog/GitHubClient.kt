package es.robertodorado.robstore.catalog

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*
import okhttp3.HttpUrl

/** Sequential, bounded public REST discovery with replaceable transport and clock. */
internal class GitHubClient(
    private val transport: HttpTransport,
    private val cache: CatalogCache,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Conditional(val etag: String, val bytes: ByteArray)
    private val conditional = linkedMapOf<String, Conditional>()
    private var conditionalBytes = 0

    inner class Discovery {
        private var remaining = CatalogPolicy.REQUEST_BUDGET
        val diagnostics = mutableListOf<String>()
        fun repositories(): List<JsonObject> = pages(apiUrl("users/${CatalogPolicy.OWNER}/repos").newBuilder()
            .addQueryParameter("type", "owner").build())
        fun releases(repository: String): List<JsonObject> = pages(apiUrl("repos/${CatalogPolicy.OWNER}/$repository/releases"))
        fun assets(repository: String, releaseId: Long): List<JsonObject> = pages(apiUrl("repos/${CatalogPolicy.OWNER}/$repository/releases/$releaseId/assets"))
        fun manifest(repository: String, tag: String): ByteArray? = try {
            request(contentUrl(repository, tag, "robstore.json"), CatalogPolicy.MAX_MANIFEST_BYTES, raw = true, missingAllowed = true)
        } catch (_: ResourceTooLarge) { throw InvalidMetadata("Manifiesto demasiado grande") }

        private fun request(url: HttpUrl, limit: Int, raw: Boolean = false, missingAllowed: Boolean = false): ByteArray? {
            if (remaining-- <= 0) throw IncompleteCatalog("Se agotó el presupuesto de 50 solicitudes por refresco")
            return get(url, limit, raw, missingAllowed)
        }
        private fun pages(base: HttpUrl): List<JsonObject> {
            val all = mutableListOf<JsonObject>()
            val seen = mutableSetOf<Long>()
            for (page in 1..CatalogPolicy.MAX_PAGES) {
                val url = base.newBuilder().addQueryParameter("per_page", "100").addQueryParameter("page", page.toString()).build()
                val bytes = request(url, CatalogPolicy.MAX_RESPONSE_BYTES)!!
                val text = bytes.decodeToString(throwOnInvalidSequence = true)
                if (!boundedJsonNesting(text)) throw IncompleteCatalog("JSON de paginación con anidamiento incorrecto")
                val array = try { catalogJson.parseToJsonElement(text) as? JsonArray }
                catch (_: IllegalArgumentException) { null }
                if (array == null || array.size > 100) throw IncompleteCatalog("Respuesta de paginación incorrecta")
                array.forEach { element ->
                    val obj = element as? JsonObject ?: throw IncompleteCatalog("Elemento de página incorrecto")
                    val id = (obj["id"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: throw IncompleteCatalog("Identificador de página incorrecto")
                    if (id <= 0 || !seen.add(id)) throw IncompleteCatalog("Paginación duplicada o inconsistente")
                    all += obj
                }
                if (array.size < 100) return all
            }
            throw IncompleteCatalog("Paginación incompleta: máximo de ${CatalogPolicy.MAX_PAGES} páginas")
        }
    }

    fun discovery() = Discovery()

    @Synchronized fun icon(icon: CatalogIcon): ByteArray? = get(
        contentUrl(icon.repository, icon.tag, icon.path), CatalogPolicy.MAX_ICON_BYTES, raw = true, missingAllowed = true,
    )

    @Synchronized private fun get(url: HttpUrl, limit: Int, raw: Boolean, missingAllowed: Boolean): ByteArray? {
        val time = now()
        val gate = cache.readGate()
        if (gate.blockedUntil > time) throw GitHubFailure(429, gate.blockedUntil)
        val key = url.toString()
        val previous = conditional[key]
        val headers = mutableMapOf("Accept" to if (raw) "application/vnd.github.raw+json" else "application/vnd.github+json")
        previous?.let { headers["If-None-Match"] = it.etag }
        val response = transport.execute(HttpRequest(url, headers, limit))
        val limited = response.status == 429 || response.status == 403
        val exhausted = response.header("X-RateLimit-Remaining") == "0"
        var retryAt = 0L
        if (limited || exhausted) {
            val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()?.takeIf { it in 1..(Long.MAX_VALUE / 1000) }?.times(1000) ?: 0
            val after = response.header("Retry-After")?.let { value ->
                value.toLongOrNull()?.takeIf { it in 0..86400 }?.let { time + it * 1000 }
                    ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrDefault(0)
            } ?: 0
            retryAt = maxOf(time + if (reset > time || after > time) 60_000 else 15 * 60_000, reset, after)
            cache.updateGate { it.copy(blockedUntil = retryAt) }
        }
        if (limited) throw GitHubFailure(response.status, retryAt)
        if (response.status == 304) return previous?.bytes?.takeIf { it.size <= limit }
            ?: throw IncompleteCatalog("HTTP 304 sin respuesta anterior válida")
        if (response.status == 404 && missingAllowed) return null
        if (response.status != 200) throw GitHubFailure(response.status)
        if (response.body.size > limit) throw ResourceTooLarge()
        conditional.remove(key)?.let { conditionalBytes -= it.bytes.size }
        response.header("ETag")?.let { etag ->
            while (conditional.isNotEmpty() && conditionalBytes + response.body.size > 8 * 1024 * 1024) {
                val first = conditional.keys.first()
                conditionalBytes -= conditional.remove(first)!!.bytes.size
            }
            conditional[key] = Conditional(etag, response.body)
            conditionalBytes += response.body.size
        }
        return response.body
    }
}

internal fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?: throw InvalidMetadata("Campo $key ausente o incorrecto")
internal fun JsonObject.long(key: String): Long = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    ?: throw InvalidMetadata("Campo $key ausente o incorrecto")
internal fun JsonObject.boolean(key: String): Boolean = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    ?: throw InvalidMetadata("Campo $key ausente o incorrecto")
internal fun publishedInstant(value: String): Instant = try { Instant.parse(value) }
    catch (_: java.time.DateTimeException) { throw InvalidMetadata("Fecha de publicación incorrecta") }
