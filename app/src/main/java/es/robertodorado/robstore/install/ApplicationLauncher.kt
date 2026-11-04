package es.robertodorado.robstore.install

import android.content.Intent
import es.robertodorado.robstore.inventory.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Query on IO for every explicit Open action; deliver on the caller's UI context. */
internal class ApplicationLauncher(
    private val applications: InstalledApplications,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun open(packageName: String, launch: (Intent) -> Unit): Boolean {
        val intent = withContext(io) {
            val local = applications.query(packageName) as? InstallationQuery.Installed
            val activity = local?.application?.launcher as? LauncherQuery.Available
            activity?.let { Intent.makeMainActivity(it.component) }
        } ?: return false
        var opened = true
        launchSystemActivity(intent, launch) { opened = false }
        return opened
    }
}
