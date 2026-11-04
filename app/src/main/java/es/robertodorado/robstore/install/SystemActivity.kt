package es.robertodorado.robstore.install

import android.content.ActivityNotFoundException
import android.content.Intent

internal fun launchSystemActivity(intent: Intent, launch: (Intent) -> Unit, onError: () -> Unit) {
    try { launch(intent) }
    catch (_: ActivityNotFoundException) { onError() }
    catch (_: SecurityException) { onError() }
    catch (_: IllegalArgumentException) { onError() }
}
