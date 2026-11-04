package es.robertodorado.robstore.download

import es.robertodorado.robstore.catalog.*
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal val APK_BYTES = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3, 4)
internal fun preparedApp() = downloadApp().let { it.copy(apk = it.apk.copy(size = APK_BYTES.size.toLong())) }
internal fun fakeInspector() = ArchiveInspector { ArchiveMetadata(preparedApp().packageName, "1.0", 1) }
internal fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

class ApkVerifierTest {
    @get:Rule val temp = TemporaryFolder()
    private fun verify(bytes: ByteArray = APK_BYTES, app: CatalogApp = preparedApp(),
        inspector: ArchiveInspector = fakeInspector(), token: DownloadCancellation = DownloadCancellation()): Pair<PreparedApk, DownloadFiles> {
        val files = DownloadFiles(temp.newFolder())
        val partial = files.partial().also { it.writeBytes(bytes) }
        return PreparedApk.verify(partial, app, files, inspector, token, TEST_NOW) to files
    }
    @Test fun matchingOfficialShaAndPackagePublishesOnlyVerifiedFile() {
        val app = preparedApp().let { it.copy(apk = it.apk.copy(sha256 = hash(APK_BYTES).uppercase())) }
        val (result, files) = verify(app = app)
        assertTrue(result.officialDigestMatched)
        assertEquals(hash(APK_BYTES), result.localSha256)
        assertEquals(files.verifiedDirectory, result.file.parentFile)
        assertEquals(app.packageName, result.metadata.packageName)
        assertFalse(files.directory.listFiles()!!.any { it.extension == "part" })
        result.recheck(files, fakeInspector(), DownloadCancellation(), TEST_NOW + 1)
    }
    @Test fun absentOfficialShaStillCalculatesLocalHashWithoutClaimingMatch() {
        val (result, _) = verify()
        assertFalse(result.officialDigestMatched)
        assertEquals(hash(APK_BYTES), result.localSha256)
    }
    @Test fun mismatchingOrMalformedDigestDeletesPartialAndNeverPublishes() {
        for (sha in listOf("a".repeat(64), "bad", "sha256:" + hash(APK_BYTES))) {
            val files = DownloadFiles(temp.newFolder())
            val partial = files.partial().also { it.writeBytes(APK_BYTES) }
            val app = preparedApp().let { it.copy(apk = it.apk.copy(sha256 = sha)) }
            assertThrows(DownloadSecurity::class.java) { PreparedApk.verify(partial, app, files, fakeInspector(), DownloadCancellation(), TEST_NOW) }
            assertFalse(partial.exists()); assertTrue(files.verifiedDirectory.listFiles().isNullOrEmpty())
        }
    }
    @Test fun corruptUnparseableWrongPackageTruncatedAndOversizedAreBlocked() {
        val invalid = listOf(APK_BYTES.copyOf(2) to fakeInspector(), ByteArray(8) to fakeInspector(),
            APK_BYTES to ArchiveInspector { null }, APK_BYTES to ArchiveInspector { ArchiveMetadata("es.other.app", "1", 1) },
            APK_BYTES.copyOf(9) to fakeInspector())
        for ((bytes, inspector) in invalid) {
            assertThrows(DownloadSecurity::class.java) { verify(bytes = bytes, inspector = inspector) }
        }
    }
    @Test fun alteredPreparedFileIsDeletedBeforeInstallationEvenWithoutOfficialSha() {
        val (result, files) = verify()
        result.file.writeBytes(APK_BYTES.copyOf().also { it[7] = 99 })
        assertThrows(DownloadSecurity::class.java) { result.recheck(files, fakeInspector(), DownloadCancellation(), TEST_NOW) }
        assertFalse(result.file.exists())
    }
    @Test fun expiredPreparedFileAndClockRollbackAreBlocked() {
        for (now in listOf(TEST_NOW - 1, TEST_NOW + 24 * 60 * 60 * 1000L)) {
            val (result, files) = verify()
            assertThrows(DownloadSecurity::class.java) { result.recheck(files, fakeInspector(), DownloadCancellation(), now) }
            assertFalse(result.file.exists())
        }
    }
    @Test fun pathsOutsideAuthorizedDirectoryCannotMintCapability() {
        val files = DownloadFiles(temp.newFolder())
        val outside = temp.newFile("outside.part").also { it.writeBytes(APK_BYTES) }
        assertThrows(DownloadSecurity::class.java) { PreparedApk.verify(outside, preparedApp(), files, fakeInspector(), DownloadCancellation(), TEST_NOW) }
        assertTrue(files.verifiedDirectory.listFiles().isNullOrEmpty())
    }
    @Test fun cancellationDuringVerificationNeverPublishes() {
        val files = DownloadFiles(temp.newFolder())
        val part = files.partial().also { it.writeBytes(APK_BYTES) }
        val token = DownloadCancellation().also { it.cancel() }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            PreparedApk.verify(part, preparedApp(), files, fakeInspector(), token, TEST_NOW)
        }
        assertFalse(part.exists()); assertTrue(files.verifiedDirectory.listFiles().isNullOrEmpty())
    }
    @Test fun restartCleansPreparedAndPartialWithoutResuming() {
        val (prepared, files) = verify()
        val part = files.partial().also { it.writeBytes(APK_BYTES) }
        DownloadFiles(files.directory).cleanup()
        assertFalse(prepared.file.exists()); assertFalse(part.exists())
    }
}
