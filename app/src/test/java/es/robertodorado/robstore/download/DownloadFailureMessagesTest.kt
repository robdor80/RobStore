package es.robertodorado.robstore.download

import es.robertodorado.robstore.catalog.GitHubFailure
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.*
import org.junit.Test

class DownloadFailureMessagesTest {
    @Test fun rateLimitedMetadataSuggestsAccountConnectionWithoutLeakingDetails() {
        val message = downloadFailureMessage(GitHubFailure(403, 1_800_000L))
        assertTrue(message.contains("HTTP 403"))
        assertTrue(message.contains("Conecta tu cuenta GitHub"))
        assertTrue(downloadFailureMessage(GitHubFailure(429, 1_800_000L)).contains("HTTP 429"))
    }

    @Test fun binaryFailuresDistinguishMissingAssetAndServerErrors() {
        assertTrue(downloadFailureMessage(DownloadHttpFailure(404)).contains("APK ya no está disponible"))
        assertTrue(downloadFailureMessage(DownloadHttpFailure(503)).contains("HTTP 503"))
        assertTrue(downloadFailureMessage(DownloadHttpFailure(403)).contains("servidor de descarga"))
    }

    @Test fun noNetworkTimeoutAndStorageHaveDistinctUserActions() {
        assertTrue(downloadFailureMessage(UnknownHostException()).contains("DNS"))
        assertTrue(downloadFailureMessage(SocketTimeoutException()).contains("demasiado"))
        assertTrue(downloadFailureMessage(InsufficientDownloadStorage()).contains("Libera almacenamiento"))
    }

    @Test fun unknownIOExceptionMustNotLeakTokensPathsOrHostnames() {
        val error = IOException("Bearer SECRET https://private.example/test /data/user/0")
        val message = downloadFailureMessage(error)
        assertFalse(message.contains("SECRET"))
        assertFalse(message.contains("private.example"))
        assertFalse(message.contains("/data/user"))
    }
    @Test fun incompleteIntegritySessionAndConnectionErrorsHaveSpecificRecoveryActions() {
        assertTrue(downloadFailureMessage(IncompleteDownload()).contains("incompleto"))
        assertTrue(downloadFailureMessage(DownloadIntegrity()).contains("SHA-256 oficial"))
        assertTrue(downloadFailureMessage(GitHubFailure(401)).contains("Conecta de nuevo"))
        assertTrue(downloadFailureMessage(java.net.ConnectException()).contains("Comprueba Internet"))
        assertTrue(downloadFailureMessage(javax.net.ssl.SSLHandshakeException("SECRET")).contains("conexión segura"))
    }
}
