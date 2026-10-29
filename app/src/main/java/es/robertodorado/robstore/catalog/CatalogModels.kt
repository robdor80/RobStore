package es.robertodorado.robstore.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val catalogJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

object CatalogPolicy {
    const val OWNER = "robdor80"
    const val TOPIC = "robstore-app"
    const val CACHE_TTL = 2 * 60 * 60 * 1000L
    const val MANUAL_INTERVAL = 5 * 60 * 1000L
    const val MAX_MANIFEST_BYTES = 32 * 1024
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    const val MAX_ICON_BYTES = 512 * 1024
    const val MAX_APK_BYTES = 512 * 1024 * 1024L
    const val MAX_PAGES = 10
    const val REQUEST_BUDGET = 50
}

@Serializable
data class CatalogApp(
    val repositoryId: Long,
    val repositoryName: String,
    val repositoryUrl: String,
    val name: String,
    val description: String,
    val packageName: String,
    val release: CatalogRelease,
    val apk: CatalogApk,
    val icon: CatalogIcon?,
    val availability: Availability = Availability.AVAILABLE,
)

@Serializable
enum class Availability { AVAILABLE }

@Serializable
data class CatalogRelease(val id: Long, val tag: String, val publishedAt: String, val notes: String)

/** A GitHub digest is publisher metadata, not proof of APK integrity or signing. */
@Serializable
data class CatalogApk(
    val id: Long,
    val name: String,
    val size: Long,
    val downloadUrl: String,
    val sha256: String? = null,
    val verification: ApkVerification = ApkVerification.NOT_PERFORMED,
)

@Serializable
enum class ApkVerification { NOT_PERFORMED }

@Serializable
data class CatalogIcon(val repository: String, val releaseId: Long, val tag: String, val path: String)

@Serializable
data class CatalogSnapshot(
    val schemaVersion: Int = 1,
    val syncedAt: Long,
    val apps: List<CatalogApp>,
    val diagnostics: List<String> = emptyList(),
)

@Serializable
data class RefreshGate(val lastAttemptAt: Long = 0, val blockedUntil: Long = 0)

class InvalidMetadata(message: String) : Exception(message)
class IncompleteCatalog(message: String) : java.io.IOException(message)
class GitHubFailure(val status: Int, val retryAt: Long = 0) : java.io.IOException(
    if (retryAt > 0) "GitHub limita las solicitudes; espera hasta el próximo intento permitido."
    else "GitHub devolvió HTTP $status.",
)
