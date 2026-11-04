package es.robertodorado.robstore.update

import es.robertodorado.robstore.catalog.*
import es.robertodorado.robstore.download.*
import es.robertodorado.robstore.inventory.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SelfUpdateDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private var candidate = ownCandidate()
    private var installed: InstallationQuery = ownInstalled()
    private var remoteCalls = 0
    private var remote: suspend (SelfUpdateCandidate, DownloadCancellation) -> Unit = { _, _ -> remoteCalls++ }
    private var archive: ArchiveMetadata? = ArchiveMetadata(candidate.packageName, "0.2", 2)
    private lateinit var files: DownloadFiles
    private fun coordinator(scope: CoroutineScope, dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        body: (okhttp3.Request) -> okhttp3.Response = { binaryResponse(it, APK_BYTES) }): DownloadCoordinator {
        files = DownloadFiles(temp.newFolder()) { Long.MAX_VALUE }
        return DownloadCoordinator(scope, { fixtureSnapshot() }, files, binaryClient(body), { _, _ -> },
            ArchiveInspector { archive }, { TEST_NOW }, dispatcher, InstalledApplications { installed }, { candidate },
            { app, token -> remote(app, token) })
    }
    @Test fun selfAndCatalogShareSingleTransferAndCancellationCleansFiles() = runTest {
        val gate = CompletableDeferred<Unit>()
        remote = { _, _ -> gate.await() }
        val controller = coordinator(this, StandardTestDispatcher(testScheduler))
        assertTrue(controller.startSelfUpdate()); assertFalse(controller.start(10)); assertFalse(controller.startSelfUpdate())
        runCurrent(); controller.cancel(); advanceUntilIdle()
        assertEquals(DownloadPhase.AVAILABLE, controller.state.value.phase)
        assertTrue(files.verifiedDirectory.listFiles().isNullOrEmpty())
        gate.complete(Unit)
        remote = { _, _ -> }
        assertTrue(controller.start(10)); assertFalse(controller.startSelfUpdate())
        controller.cancel(); advanceUntilIdle()
    }
    @Test fun validSelfDownloadRequiresHashAndExplicitConfirmationBeforeInstaller() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        try {
            val controller = coordinator(this, dispatcher)
            controller.startSelfUpdate(); advanceUntilIdle()
            assertEquals(2, remoteCalls)
            assertEquals(DownloadPhase.OFFICIAL_VERIFIED, controller.state.value.phase)
            assertFalse(controller.requestSelfInstall({ true }) { fail("Not confirmed") })
            controller.confirmPrepared()
            var deliveries = 0
            controller.requestSelfInstall({ true }) { deliveries++ }; advanceUntilIdle()
            assertEquals(3, remoteCalls); assertEquals(1, deliveries)
            controller.installerReturned(); assertEquals(DownloadPhase.UNCONFIRMED, controller.state.value.phase)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun otherPackageOtherVariantAndInternalVersionMismatchDeleteApk() = runTest {
        for (metadata in listOf(ArchiveMetadata(SelfUpdateChannel.DEV.packageName, "0.2", 2),
            ArchiveMetadata("es.other", "0.2", 2), ArchiveMetadata(candidate.packageName, "0.2", 3), null)) {
            archive = metadata
            val controller = coordinator(this, StandardTestDispatcher(testScheduler))
            controller.startSelfUpdate(); advanceUntilIdle()
            assertEquals(DownloadPhase.SECURITY_ERROR, controller.state.value.phase)
            assertTrue(files.verifiedDirectory.listFiles().isNullOrEmpty())
        }
    }
    @Test fun missingAndAlteredHashNeverPrepareInstallation() = runTest {
        for (hash in listOf(null, "f".repeat(64))) {
            candidate = ownCandidate().copy(apk = ownCandidate().apk.copy(sha256 = hash))
            val controller = coordinator(this, StandardTestDispatcher(testScheduler))
            controller.startSelfUpdate(); advanceUntilIdle()
            assertEquals(DownloadPhase.SECURITY_ERROR, controller.state.value.phase)
            assertTrue(files.verifiedDirectory.listFiles().isNullOrEmpty())
        }
    }
    @Test fun releaseChangedDuringTransferDeletesFile() = runTest {
        remote = { _, _ -> if (++remoteCalls == 2) throw InvalidMetadata("Release changed") }
        val controller = coordinator(this, StandardTestDispatcher(testScheduler))
        controller.startSelfUpdate(); advanceUntilIdle()
        assertEquals(DownloadPhase.SECURITY_ERROR, controller.state.value.phase)
        assertTrue(files.directory.listFiles()!!.none { it.isFile })
    }
    @Test fun releaseChangedBeforeInstallBlocksInstaller() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        try {
            val controller = coordinator(this, dispatcher)
            controller.startSelfUpdate(); advanceUntilIdle(); controller.confirmPrepared()
            remote = { _, _ -> throw InvalidMetadata("Release deleted") }
            controller.requestSelfInstall({ true }) { fail("Changed release") }; advanceUntilIdle()
            assertEquals(DownloadPhase.SECURITY_ERROR, controller.state.value.phase)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun equalHigherOrUnknownInstalledVersionBeforeInstallBlocksHandover() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        try {
            for (query in listOf(ownInstalled(2), ownInstalled(3), ownInstalled(null), InstallationQuery.Unknown, InstallationQuery.Absent)) {
                installed = ownInstalled()
                val controller = coordinator(this, dispatcher)
                controller.startSelfUpdate(); advanceUntilIdle(); controller.confirmPrepared()
                installed = query
                controller.requestSelfInstall({ true }) { fail("No demonstrated newer version") }; advanceUntilIdle()
                assertEquals(DownloadPhase.SECURITY_ERROR, controller.state.value.phase)
                assertTrue(files.verifiedDirectory.listFiles().isNullOrEmpty())
            }
        } finally { Dispatchers.resetMain() }
    }
    @Test fun deletedOrAlteredPreparedFileRequiresNewDownloadAndCanRecover() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        try {
            for (delete in listOf(true, false)) {
                val controller = coordinator(this, dispatcher)
                controller.startSelfUpdate(); advanceUntilIdle(); controller.confirmPrepared()
                val file = files.verifiedDirectory.listFiles()!!.single()
                if (delete) file.delete() else file.appendBytes(byteArrayOf(1))
                controller.requestSelfInstall({ true }) { fail("File missing or changed") }; advanceUntilIdle()
                assertEquals(DownloadPhase.SECURITY_ERROR, controller.state.value.phase)
                controller.startSelfUpdate(); advanceUntilIdle()
                assertEquals(DownloadPhase.OFFICIAL_VERIFIED, controller.state.value.phase)
            }
        } finally { Dispatchers.resetMain() }
    }
    @Test fun cancellationAfterBinaryCallDeletesPartialAndAllowsRetry() = runTest {
        lateinit var controller: DownloadCoordinator
        var cancelTransfer = true
        controller = coordinator(this, StandardTestDispatcher(testScheduler)) {
            if (cancelTransfer) controller.cancel()
            binaryResponse(it, APK_BYTES)
        }
        controller.startSelfUpdate(); advanceUntilIdle()
        assertEquals(DownloadPhase.AVAILABLE, controller.state.value.phase)
        assertTrue(files.directory.listFiles()!!.none { it.isFile })
        cancelTransfer = false
        controller.startSelfUpdate(); advanceUntilIdle()
        assertEquals(DownloadPhase.OFFICIAL_VERIFIED, controller.state.value.phase)
    }
    @Test fun newProcessCleansPreviousPreparedApkAndDoesNotResumeInstallation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = coordinator(this, dispatcher)
        first.startSelfUpdate(); advanceUntilIdle()
        val directory = files.directory
        assertEquals(1, files.verifiedDirectory.listFiles()!!.size)
        first.close()
        val restarted = DownloadCoordinator(this, { null }, DownloadFiles(directory), binaryClient { error("No network") },
            { _, _ -> }, ArchiveInspector { archive }, io = dispatcher)
        advanceUntilIdle()
        assertEquals(DownloadPhase.AVAILABLE, restarted.state.value.phase)
        assertTrue(files.verifiedDirectory.listFiles().isNullOrEmpty())
        assertFalse(restarted.requestSelfInstall({ true }) { fail("No capability across restart") })
    }
}
