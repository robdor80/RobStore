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
    is IncompleteDownload -> "El archivo está incompleto. Se ha eliminado; comprueba la conexión y vuelve a descargarlo."
    is DownloadIntegrity -> "La integridad del APK no coincide con el SHA-256 oficial. Archivo eliminado; comprueba de nuevo la publicación antes de descargar."
    is DownloadSecurity -> "La descarga no supera los controles de seguridad o compatibilidad. Archivo eliminado; comprueba de nuevo la publicación."
    is GitHubFailure -> when (error.status) {
        401 -> "La sesión de GitHub no permite comprobar la publicación (HTTP 401). Conecta de nuevo GitHub desde Ajustes."
        403, 429 -> "GitHub limita o rechaza la comprobación de la publicación (HTTP ${error.status}). Conecta tu cuenta GitHub desde Ajustes o vuelve a intentarlo más tarde."
        404 -> "GitHub no encuentra la publicación que se iba a verificar (HTTP 404). Comprueba si sigue publicada."
        in 500..599 -> "GitHub no responde correctamente al comprobar la publicación (HTTP ${error.status}). Inténtalo más tarde."
        else -> "No se pudo comprobar la publicación en GitHub (HTTP ${error.status}). Inténtalo de nuevo."
    }
    is DownloadHttpFailure -> when (error.status) {
        403, 429 -> "El servidor de descarga de GitHub ha rechazado o limitado el APK (HTTP ${error.status}). Inténtalo más tarde."
        404 -> "El APK ya no está disponible en la Release oficial de GitHub (HTTP 404). Comprueba de nuevo las actualizaciones o el catálogo."
        in 500..599 -> "El servidor de descarga de GitHub está fallando (HTTP ${error.status}). Vuelve a intentarlo más tarde."
        else -> "La descarga del APK ha fallado en GitHub (HTTP ${error.status}). Comprueba de nuevo la publicación y reintenta."
    }
    is InsufficientDownloadStorage -> "No hay espacio suficiente para descargar el APK. Libera almacenamiento y reintenta."
    is UnknownHostException -> "No se puede resolver GitHub. Comprueba la conexión a Internet o el DNS y reintenta."
    is InterruptedIOException -> "La conexión ha tardado demasiado. Comprueba la red y vuelve a intentarlo."
    is java.net.ProtocolException -> "La respuesta de descarga es incorrecta o incompleta. Archivo eliminado; comprueba la conexión y vuelve a descargarlo."
    is java.net.SocketException -> "Se ha perdido la conexión con GitHub. Comprueba Internet y vuelve a intentarlo."
    is javax.net.ssl.SSLException -> "No se pudo establecer una conexión segura con GitHub. Comprueba la fecha del dispositivo y vuelve a intentarlo más tarde."
    else -> "Se ha interrumpido la descarga o la comprobación del APK. Comprueba la conexión y el almacenamiento; reintenta manualmente."
}

/** One bounded retry for transfer failures only. Quotas, storage, TLS and integrity never trigger retries. */
internal fun retryableTransfer(error: IOException): Boolean = when (error) {
    is IncompleteDownload -> true
    is DownloadHttpFailure -> error.status == 408 || error.status in 500..599
    is java.net.SocketException, is java.net.UnknownHostException,
    is java.io.InterruptedIOException, is java.io.EOFException, is java.net.ProtocolException -> true
    else -> false
}
