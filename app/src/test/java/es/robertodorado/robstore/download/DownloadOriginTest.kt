package es.robertodorado.robstore.download

import es.robertodorado.robstore.catalog.*
import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal fun binaryClient(handler: (Request) -> Response): BinaryClient = BinaryClient(OkHttpClient.Builder()
    .addInterceptor { handler(it.request()) }.build())
internal fun binaryResponse(request: Request, bytes: ByteArray = byteArrayOf(1, 2, 3), code: Int = 200,
    location: String? = null, length: Long = bytes.size.toLong()): Response = Response.Builder()
    .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
    .apply { location?.let { header("Location", it) } }
    .body(object : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength() = length
        override fun source(): okio.BufferedSource = okio.Buffer().write(bytes)
    }).build()
internal fun downloadApp() = fixtureSnapshot().apps.single().let { it.copy(apk = it.apk.copy(size = 3, sha256 = null)) }

class DownloadOriginTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun acceptsOnlySelectedAppFromValidSnapshot() {
        val app = downloadApp()
        assertEquals(app, CatalogValidation.select(CatalogSnapshot(syncedAt = TEST_NOW, apps = listOf(app)), app.repositoryId))
        assertThrows(InvalidMetadata::class.java) { CatalogValidation.select(null, app.repositoryId) }
        assertThrows(InvalidMetadata::class.java) { CatalogValidation.select(fixtureSnapshot(), 999) }
    }
    @Test fun rejectsTamperedSensitiveCacheFields() {
        val app = downloadApp()
        val cache = FileCatalogCache(temp.newFolder())
        val invalid = listOf(app.copy(packageName = "bad"), app.copy(repositoryUrl = "https://github.com/other/Demo"),
            app.copy(release = app.release.copy(id = 0)), app.copy(apk = app.apk.copy(id = 0)),
            app.copy(apk = app.apk.copy(size = 0)), app.copy(apk = app.apk.copy(size = CatalogPolicy.MAX_APK_BYTES + 1)),
            app.copy(apk = app.apk.copy(name = "../evil.apk")), app.copy(apk = app.apk.copy(sha256 = "sha256:bad")))
        for (altered in invalid) {
            cache.save(CatalogSnapshot(syncedAt = TEST_NOW, apps = listOf(altered)))
            assertNull(cache.load())
        }
    }
    @Test fun rejectsHttpExternalLookalikeCredentialsQueryTraversalAndWrongRelease() {
        val app = downloadApp()
        val original = app.apk.downloadUrl
        for (url in listOf(original.replace("https:", "http:"), original.replace("github.com", "github.com.evil.test"),
            original.replace("github.com", "evilgithub.com"), original.replace("github.com", "user:pass@github.com"),
            "$original?token=secret", "$original#fragment", original.replace("robdor80", "other"),
            original.replace("edition-october", "different"), original.replace("/Demo/", "/bad/../Demo/"),
            original.replace("/Demo/", "/Demo%2f/"), original.replace("github.com", "unexpected.github.com"))) {
            assertFalse(url, CatalogValidation.apk(app.repositoryName, app.release.tag, app.apk.copy(downloadUrl = url)))
            val cache = FileCatalogCache(temp.newFolder())
            cache.save(CatalogSnapshot(syncedAt = TEST_NOW, apps = listOf(app.copy(apk = app.apk.copy(downloadUrl = url)))))
            assertNull(cache.load())
        }
    }
    @Test fun direct200StreamsExactBytesAndProgressWithoutContentLength() {
        val client = binaryClient { binaryResponse(it, length = -1) }
        val files = DownloadFiles(temp.newFolder()) { Long.MAX_VALUE }
        val progress = mutableListOf<Pair<Long, Long>>()
        val file = files.receive(downloadApp(), client, DownloadCancellation()) { a, b -> progress += a to b }
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertTrue(file.name.endsWith(".part"))
        assertEquals(listOf(0L to 3L, 3L to 3L), progress)
    }
    @Test fun controlledOfficial302DoesNotTransmitCredentials() {
        val requests = mutableListOf<Request>()
        val client = binaryClient {
            requests += it
            if (requests.size == 1) binaryResponse(it, code = 302, location = "https://release-assets.githubusercontent.com/github-production-release-asset/1?sig=test")
            else binaryResponse(it)
        }
        client.open(downloadApp(), DownloadCancellation()).use { assertEquals(3L, it.length) }
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.header("Authorization") == null && it.header("Cookie") == null })
    }
    @Test fun unknownRedirectsAreBlockedBeforeRequest() {
        for (host in listOf("evil.test", "githubusercontent.com", "evilgithubusercontent.com", "x.objects.githubusercontent.com", "github.com")) {
            var calls = 0
            val client = binaryClient { calls++; binaryResponse(it, code = 302, location = "https://$host/asset?sig=test") }
            assertThrows(DownloadSecurity::class.java) { client.open(downloadApp(), DownloadCancellation()) }
            assertEquals(1, calls)
        }
    }
    @Test fun rejectsUnsafeRedirectSchemesPortsCredentialsAndFragments() {
        for (url in listOf("http://objects.githubusercontent.com/a", "https://objects.githubusercontent.com:444/a",
            "https://user:pw@objects.githubusercontent.com/a", "https://objects.githubusercontent.com/a#fragment")) {
            assertThrows(DownloadSecurity::class.java) { BinaryOrigin.redirect(downloadApp().apk.downloadUrl.toHttpUrl(), url) }
        }
    }
    @Test fun redirectLoopAndFourthRedirectAreBlocked() {
        for (loop in listOf(true, false)) {
            var calls = 0
            val client = binaryClient { calls++; binaryResponse(it, code = 302,
                location = "https://objects.githubusercontent.com/${if (loop) "same" else calls}") }
            assertThrows(DownloadSecurity::class.java) { client.open(downloadApp(), DownloadCancellation()) }
            assertTrue(calls <= 4)
        }
    }
    @Test fun httpErrorsAndMissingLocationAreControlledAndNeverRetried() {
        for (status in listOf(302, 403, 404, 429, 500, 503, 204, 206)) {
            var calls = 0
            val client = binaryClient { calls++; binaryResponse(it, code = status) }
            assertThrows(IOException::class.java) { client.open(downloadApp(), DownloadCancellation()) }
            assertEquals(1, calls)
        }
    }
    @Test fun timeoutAndConnectionCutCleanPartialsAndAllowManualRetry() {
        for (failure in listOf(SocketTimeoutException(), IOException("connection cut"))) {
            var fail = true
            val client = binaryClient { if (fail) throw failure else binaryResponse(it) }
            val files = DownloadFiles(temp.newFolder()) { Long.MAX_VALUE }
            assertThrows(IOException::class.java) { files.receive(downloadApp(), client, DownloadCancellation()) { _, _ -> } }
            assertTrue(files.directory.listFiles()!!.none { it.isFile })
            fail = false
            assertEquals(3L, files.receive(downloadApp(), client, DownloadCancellation()) { _, _ -> }.length())
        }
    }
    @Test fun cancellationCancelsActualOkHttpCallAndPreventsNextHop() {
        val token = DownloadCancellation()
        val call = OkHttpClient().newCall(Request.Builder().url(downloadApp().apk.downloadUrl).build())
        token.attach(call); token.cancel()
        assertTrue(call.isCanceled())
        assertThrows(java.util.concurrent.CancellationException::class.java) { token.attach(call) }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            binaryClient { error("No request allowed") }.open(downloadApp(), token)
        }
    }
    @Test fun cancellationDuringStreamingDeletesPartial() {
        val token = DownloadCancellation()
        val files = DownloadFiles(temp.newFolder()) { Long.MAX_VALUE }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            files.receive(downloadApp(), binaryClient { binaryResponse(it) }, token) { a, _ -> if (a > 0) token.cancel() }
        }
        assertTrue(files.directory.listFiles()!!.none { it.isFile })
    }
    @Test fun sizeMismatchTruncatedOversizedAndWrongLengthAreBlocked() {
        for ((bytes, length) in listOf(byteArrayOf(1) to -1L, ByteArray(4) to -1L, ByteArray(3) to 4L, ByteArray(3) to 2L)) {
            val files = DownloadFiles(temp.newFolder()) { Long.MAX_VALUE }
            assertThrows(DownloadSecurity::class.java) {
                files.receive(downloadApp(), binaryClient { binaryResponse(it, bytes, length = length) }, DownloadCancellation()) { _, _ -> }
            }
            assertTrue(files.directory.listFiles()!!.none { it.isFile })
        }
    }
    @Test fun insufficientSpaceBeforeAndDuringStreamingIsRecoverable() {
        for (during in listOf(false, true)) {
            var checks = 0
            val files = DownloadFiles(temp.newFolder()) { if (during && checks++ == 0) Long.MAX_VALUE else 0 }
            assertThrows(IOException::class.java) {
                files.receive(downloadApp(), binaryClient { binaryResponse(it) }, DownloadCancellation()) { _, _ -> }
            }
            assertTrue(files.directory.listFiles()!!.none { it.isFile })
        }
    }
    @Test fun restartDeletesOrphanPartAndOldVerifiedFiles() {
        val directory = temp.newFolder()
        java.io.File(directory, "orphan.part").writeText("partial")
        java.io.File(directory, "old.apk").writeText("old")
        DownloadFiles(directory).cleanup()
        assertTrue(directory.listFiles()!!.none { it.isFile })
    }
    @Test fun readFailureClosesBodyAndDeletesPartial() {
        var closed = false
        val client = binaryClient { request -> binaryResponse(request).newBuilder().body(object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): okio.BufferedSource = (object : okio.Source {
                override fun timeout() = okio.Timeout.NONE
                override fun close() { closed = true }
                override fun read(sink: okio.Buffer, byteCount: Long): Long = throw IOException("read failed")
            }).buffer()
        }).build() }
        val files = DownloadFiles(temp.newFolder()) { Long.MAX_VALUE }
        assertThrows(IOException::class.java) { files.receive(downloadApp(), client, DownloadCancellation()) { _, _ -> } }
        assertTrue(closed)
        assertTrue(files.directory.listFiles()!!.none { it.isFile })
    }
    @Test fun compressedBodiesAreRejected() {
        val client = binaryClient { binaryResponse(it).newBuilder().header("Content-Encoding", "gzip").build() }
        assertThrows(DownloadSecurity::class.java) { client.open(downloadApp(), DownloadCancellation()) }
    }

}
