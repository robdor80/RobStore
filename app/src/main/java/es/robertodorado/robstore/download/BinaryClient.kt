package es.robertodorado.robstore.download

import es.robertodorado.robstore.catalog.*
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class DownloadSecurity(message: String) : IOException(message)

/** Explicit allowlist, never suffix matching. CDN queries are accepted only after an official redirect. */
internal object BinaryOrigin {
    private val cdn = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
    fun redirect(from: HttpUrl, location: String): HttpUrl {
        val next = from.resolve(location) ?: throw DownloadSecurity("Redirección incorrecta.")
        if (next.scheme != "https" || next.port != 443 || next.username.isNotEmpty() ||
            next.password.isNotEmpty() || next.fragment != null || next.host !in cdn) {
            throw DownloadSecurity("Destino de descarga no autorizado.")
        }
        return next
    }
}

/** Cancellation closes an active call, including a blocked socket read, and cannot lose a race with attach. */
internal class DownloadCancellation {
    @Volatile var cancelled = false
        private set
    private var active: Call? = null
    @Synchronized fun attach(call: Call) { check(); active = call; if (cancelled) call.cancel() }
    @Synchronized fun cancel() { cancelled = true; active?.cancel() }
    fun check() { if (cancelled) throw java.util.concurrent.CancellationException("Descarga cancelada") }
}

internal class BinaryResponse(private val response: Response) : Closeable {
    val length: Long get() = response.body?.contentLength() ?: -1
    val stream: InputStream get() = response.body?.byteStream() ?: throw IOException("Respuesta sin contenido.")
    override fun close() = response.close()
}

/** Separate large binary client. No JSON buffering, credentials, cookies, custom TLS or automatic retries. */
internal class BinaryClient(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).build()

    fun open(app: CatalogApp, cancellation: DownloadCancellation): BinaryResponse {
        if (!CatalogValidation.app(app)) throw DownloadSecurity("Metadatos del APK inválidos.")
        var url = app.apk.downloadUrl.toHttpUrlOrNull()!!
        val seen = mutableSetOf<String>()
        var redirects = 0
        while (true) {
            cancellation.check()
            if (!seen.add(url.toString())) throw DownloadSecurity("Bucle de redirecciones.")
            // Build fresh headers at every hop; no sensitive headers travel across domains.
            val call = client.newCall(Request.Builder().url(url).header("Accept", "application/octet-stream")
                .header("Accept-Encoding", "identity").header("User-Agent", "RobStore-Android").build())
            cancellation.attach(call)
            val response = call.execute()
            if (response.code == 200) {
                val encoding = response.header("Content-Encoding")
                if (encoding != null && !encoding.equals("identity", true)) {
                    response.close(); throw DownloadSecurity("Codificación de descarga no admitida.")
                }
                return BinaryResponse(response)
            }
            response.use {
                if (response.code !in setOf(301, 302, 303, 307, 308)) throw IOException("Descarga: HTTP ${response.code}. Reintenta manualmente.")
                if (++redirects > 3) throw DownloadSecurity("Demasiadas redirecciones.")
                url = BinaryOrigin.redirect(url, response.header("Location") ?: throw DownloadSecurity("Redirección sin destino."))
            }
        }
    }
}
