package es.robertodorado.robstore.download

import es.robertodorado.robstore.catalog.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class InstallationFlowTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun absentShaNeedsExtraConfirmationAndInstallationNeverStartsAutomatically() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val app = preparedApp()
            val coordinator = DownloadCoordinator(this, { CatalogSnapshot(syncedAt = TEST_NOW, apps = listOf(app)) },
                DownloadFiles(temp.newFolder()) { Long.MAX_VALUE }, binaryClient { binaryResponse(it, APK_BYTES) },
                { _, _ -> }, fakeInspector(), { TEST_NOW }, dispatcher)
            var deliveries = 0
            coordinator.start(app.repositoryId); advanceUntilIdle()
            assertEquals(DownloadPhase.NO_OFFICIAL_SHA, coordinator.state.value.phase)
            assertFalse(coordinator.requestInstall(app.repositoryId, { true }) { deliveries++ })
            assertEquals(0, deliveries)
            coordinator.confirmPrepared()
            assertEquals(DownloadPhase.READY, coordinator.state.value.phase)
            coordinator.requestInstall(app.repositoryId, { false }) { deliveries++ }; advanceUntilIdle()
            assertEquals(DownloadPhase.PERMISSION_PENDING, coordinator.state.value.phase)
            coordinator.permissionChecked(false)
            assertEquals(DownloadPhase.PERMISSION_PENDING, coordinator.state.value.phase)
            coordinator.permissionChecked(true)
            assertEquals(DownloadPhase.READY, coordinator.state.value.phase); assertEquals(0, deliveries)
            assertTrue(coordinator.requestInstall(app.repositoryId, { true }) { deliveries++ })
            assertFalse(coordinator.requestInstall(app.repositoryId, { true }) { deliveries++ })
            advanceUntilIdle()
            assertEquals(1, deliveries); assertEquals(DownloadPhase.INSTALLER_OPEN, coordinator.state.value.phase)
            assertFalse(coordinator.start(app.repositoryId)) // Retain the file while Android may still read it.
            coordinator.installerReturned()
            assertEquals(DownloadPhase.UNCONFIRMED, coordinator.state.value.phase)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun alteredFileOrChangedSelectionCannotBeInstalled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            for (alterFile in listOf(true, false)) {
                val app = preparedApp()
                var snapshot = CatalogSnapshot(syncedAt = TEST_NOW, apps = listOf(app))
                val files = DownloadFiles(temp.newFolder()) { Long.MAX_VALUE }
                val coordinator = DownloadCoordinator(this, { snapshot }, files, binaryClient { binaryResponse(it, APK_BYTES) },
                    { _, _ -> }, fakeInspector(), { TEST_NOW }, dispatcher)
                coordinator.start(app.repositoryId); advanceUntilIdle(); coordinator.confirmPrepared()
                if (alterFile) files.verifiedDirectory.listFiles()!!.single().writeText("tampered") else snapshot = snapshot.copy(apps = emptyList())
                var deliveries = 0
                coordinator.requestInstall(app.repositoryId, { true }) { deliveries++ }; advanceUntilIdle()
                assertEquals(0, deliveries); assertEquals(DownloadPhase.SECURITY_ERROR, coordinator.state.value.phase)
                assertTrue(files.verifiedDirectory.listFiles()!!.isEmpty())
            }
        } finally { Dispatchers.resetMain() }
    }
    @Test fun officialShaIsReportedSeparatelyAndMissingInstallerCanBeRetried() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val app = preparedApp().let { it.copy(apk = it.apk.copy(sha256 = hash(APK_BYTES))) }
            val coordinator = DownloadCoordinator(this, { CatalogSnapshot(syncedAt = TEST_NOW, apps = listOf(app)) },
                DownloadFiles(temp.newFolder()) { Long.MAX_VALUE }, binaryClient { binaryResponse(it, APK_BYTES) },
                { _, _ -> }, fakeInspector(), { TEST_NOW }, dispatcher)
            coordinator.start(app.repositoryId); advanceUntilIdle()
            assertEquals(DownloadPhase.OFFICIAL_VERIFIED, coordinator.state.value.phase)
            coordinator.confirmPrepared()
            coordinator.requestInstall(app.repositoryId, { true }) { coordinator.installationError() }; advanceUntilIdle()
            assertEquals(DownloadPhase.INSTALL_ERROR, coordinator.state.value.phase)
            var delivered = false
            coordinator.requestInstall(app.repositoryId, { true }) { delivered = true }; advanceUntilIdle()
            assertTrue(delivered); assertEquals(DownloadPhase.INSTALLER_OPEN, coordinator.state.value.phase)
        } finally { Dispatchers.resetMain() }
    }
}
