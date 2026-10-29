package es.robertodorado.robstore.catalog

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class HttpRequest(val url: HttpUrl, val headers: Map<String, String> = emptyMap(), val maxBytes: Int)
internal data class HttpResult(val status: Int, val body: ByteArray = byteArrayOf(), val headers: Map<String, String> = emptyMap()) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

internal fun interface HttpTransport { fun execute(request: HttpRequest): HttpResult }
internal class ResourceTooLarge : IOException("Recurso mayor que el límite permitido")

/** Only API metadata and bounded icons are fetched. Redirects are never followed. */
internal class OkHttpTransport(private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()) : HttpTransport {

    @Synchronized override fun execute(request: HttpRequest): HttpResult {
        require(request.url.scheme == "https" && request.url.host == "api.github.com" &&
            request.url.port == 443 && request.url.username.isEmpty() && request.url.password.isEmpty())
        val builder = Request.Builder().url(request.url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "RobStore-Android")
        request.headers.forEach { (key, value) -> builder.header(key, value) }
        return client.newCall(builder.build()).execute().use { response ->
            val body = response.body
            if (body != null && body.contentLength() > request.maxBytes) throw ResourceTooLarge()
            val bytes = body?.byteStream()?.let { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > request.maxBytes) throw ResourceTooLarge()
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            HttpResult(response.code, bytes, response.headers.toMap())
        }
    }
}
