package es.robertodorado.robstore.download

import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.*
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadFailureTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun writeFailureClosesResourcesAndCleansPartial() {
        var closed = false
        val files = DownloadFiles(temp.newFolder(), output = { file -> object : FileOutputStream(file) {
            override fun write(b: ByteArray, off: Int, len: Int) { throw IOException("write failure") }
            override fun close() { closed = true; super.close() }
        } }, available = { Long.MAX_VALUE })
        assertThrows(IOException::class.java) {
            files.receive(downloadApp(), binaryClient { binaryResponse(it) }, DownloadCancellation()) { _, _ -> }
        }
        assertTrue(closed); assertFalse(files.directory.listFiles()!!.any { it.isFile })
    }
    @Test fun cancellationUnblocksAnActiveReadByCancellingItsActualCall() {
        val entered = CountDownLatch(1)
        val closed = AtomicBoolean()
        val token = DownloadCancellation()
        val client = BinaryClient(OkHttpClient.Builder().addInterceptor { chain ->
            binaryResponse(chain.request()).newBuilder().body(object : ResponseBody() {
                private val source = object : okio.Source {
                    override fun timeout() = okio.Timeout.NONE
                    override fun close() { closed.set(true) }
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        entered.countDown()
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.yield()
                        if (!chain.call().isCanceled()) throw AssertionError("Active call was not cancelled")
                        throw IOException("cancelled socket")
                    }
                }.buffer()
                override fun contentType(): MediaType? = null
                override fun contentLength() = -1L
                override fun source() = source
            }).build()
        }.build())
        val files = DownloadFiles(temp.newFolder()) { Long.MAX_VALUE }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                try { files.receive(downloadApp(), client, token) { _, _ -> }; false } catch (_: IOException) { true }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            token.cancel()
            assertTrue(result.get(5, TimeUnit.SECONDS))
            assertTrue(closed.get()); assertFalse(files.directory.listFiles()!!.any { it.isFile })
        } finally { token.cancel(); executor.shutdownNow() }
    }
    @Test fun malformedDuplicateOrAmbiguousContentLengthsAreRejected() {
        for (headers in listOf(listOf("bad"), listOf("-1"), listOf("4"), listOf("3", "3"), listOf("3", "4"))) {
            val client = binaryClient { request -> binaryResponse(request).newBuilder().apply {
                headers.forEach { addHeader("Content-Length", it) }
            }.build() }
            assertThrows(DownloadSecurity::class.java) { client.open(downloadApp(), DownloadCancellation()) }
        }
        val client = binaryClient { binaryResponse(it).newBuilder().header("Content-Length", "3").header("Transfer-Encoding", "chunked").build() }
        assertThrows(DownloadSecurity::class.java) { client.open(downloadApp(), DownloadCancellation()) }
    }
    @Test fun exactContentLengthHeaderIsAccepted() {
        binaryClient { binaryResponse(it).newBuilder().header("Content-Length", "3").build() }
            .open(downloadApp(), DownloadCancellation()).use { assertEquals(3L, it.length) }
    }
    @Test fun legitimatePrivateRootSymlinkUsesCanonicalGeneratedPaths() {
        val actual = temp.newFolder()
        val link = java.io.File(temp.root, "private-root-link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), actual.toPath())
        val files = DownloadFiles(link) { Long.MAX_VALUE }
        assertEquals(actual.canonicalFile, files.directory)
        val file = files.receive(downloadApp(), binaryClient { binaryResponse(it) }, DownloadCancellation()) { _, _ -> }
        assertEquals(actual.canonicalFile, file.parentFile)
    }
    @Test fun globalDeadlineExpiresAndNeverLengthensMetadataTimeout() {
        var now = 0L
        val token = DownloadCancellation { now }
        val call = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(downloadApp().apk.downloadUrl).build())
        token.attach(call)
        assertEquals(TimeUnit.SECONDS.toNanos(30), call.timeout().timeoutNanos())
        now = TimeUnit.MINUTES.toNanos(15)
        assertThrows(IOException::class.java) { token.check() }
    }

}
