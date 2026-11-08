package es.robertodorado.robstore.download

import es.robertodorado.robstore.catalog.GitHubFailure
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException

/**
 * Errores aptos para mostrarse: nunca copiar excepciones crudas con URLs,
 * cabeceras, tokens, rutas locales u otros detalles sensibles.
 */
internal fun downloadFailureMessage(error: IOException): String = when (error) {
    is GitHubFailure -> when (error.status) {
        403, 429 -> "GitHub limita o rechaza la comprobación de la publicación (HTTP ${error.status}). Conecta tu cuenta GitHub desde Ajustes o vuelve a intentarlo más tarde."
        404 -> "GitHub no encuentra la publicación que se iba a verificar (HTTP 404). Comprueba si sigue publicada."
        in 500..599 -> "GitHub no responde correctamente al comprobar la publicación (HTTP ${error.status}). Inténtalo más tarde."
        else -> "No se pudo comprobar la publicación en GitHub (HTTP ${error.status}). Inténtalo de nuevo."
    }
    is DownloadHttpFailure -> when (error.status) {
        403, 429 -> "El servidor de descarga de GitHub ha rechazado o limitado el APK (HTTP ${error.status}). Inténtalo más tarde."
        404 -> "El APK ya no está disponible en la Release oficial de GitHub (HTTP 404)."
        in 500..599 -> "El servidor de descarga de GitHub está fallando (HTTP ${error.status}). Vuelve a intentarlo más tarde."
        else -> "La descarga del APK ha fallado en GitHub (HTTP ${error.status})."
    }
    is InsufficientDownloadStorage -> "No hay espacio suficiente para descargar el APK. Libera almacenamiento y reintenta."
    is UnknownHostException -> "No se puede resolver GitHub. Comprueba la conexión a Internet o el DNS y reintenta."
    is InterruptedIOException -> "La conexión ha tardado demasiado. Comprueba la red y vuelve a intentarlo."
    else -> "Se ha interrumpido la descarga o la comprobación del APK. Comprueba la conexión y el almacenamiento; reintenta manualmente."
}
