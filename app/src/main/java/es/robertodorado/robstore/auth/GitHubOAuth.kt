package es.robertodorado.robstore.auth

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Client ID público de la GitHub App RobGit RD; no es un secreto. */
internal const val ROBGIT_RD_CLIENT_ID = "Iv23liwEJPI2xtkpLqfF"

internal data class GitHubCredentials(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
    val login: String,
)

internal data class DeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAt: Long,
    val intervalSeconds: Long,
)

internal sealed interface DeviceResult {
    data object Waiting : DeviceResult
    data object SlowDown : DeviceResult
    data class Authorized(val credentials: GitHubCredentials) : DeviceResult
    data class Failed(val message: String) : DeviceResult
}

internal class OAuthFailure(message: String, val permanent: Boolean = false) : IOException(message)

/**
 * GitHub App Device Flow: no se necesita PAT, Client Secret ni redirecciones.
 * Ejecutar estos métodos desde Dispatchers.IO, nunca desde el hilo principal.
 */
internal class GitHubOAuth(private val now: () -> Long = System::currentTimeMillis) {
    fun begin(): DeviceAuthorization {
        val response = post("/login/device/code", mapOf(
            "client_id" to ROBGIT_RD_CLIENT_ID,
            "scope" to "offline_access",
        ))
        if (response.has("error")) throw OAuthFailure("GitHub no permite iniciar sesión. Revisa Device Flow en RobGit RD.")
        val verificationUri = response.getString("verification_uri")
        if (verificationUri != "https://github.com/login/device") throw OAuthFailure("GitHub devolvió una URL de autorización inesperada.")
        val expiresIn = response.getLong("expires_in").coerceIn(1, 900)
        return DeviceAuthorization(
            response.getString("device_code"),
            response.getString("user_code"),
            verificationUri,
            now() + expiresIn * 1000,
            response.optLong("interval", 5).coerceIn(5, 60),
        )
    }

    fun poll(pending: DeviceAuthorization): DeviceResult {
        if (now() >= pending.expiresAt) return DeviceResult.Failed("Ha caducado el código. Pulsa Conectar otra vez.")
        val response = post("/login/oauth/access_token", mapOf(
            "client_id" to ROBGIT_RD_CLIENT_ID,
            "device_code" to pending.deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        ))
        return when (response.optString("error")) {
            "authorization_pending" -> DeviceResult.Waiting
            "slow_down" -> DeviceResult.SlowDown
            "access_denied" -> DeviceResult.Failed("No se autorizó la conexión con GitHub.")
            "expired_token", "bad_verification_code" -> DeviceResult.Failed("El código ha caducado.")
            "device_flow_disabled" -> DeviceResult.Failed("Activa Device Flow en RobGit RD.")
            "" -> DeviceResult.Authorized(parseCredentials(response))
            else -> DeviceResult.Failed("GitHub rechazó la autorización. Inténtalo otra vez.")
        }
    }

    /** Los tokens obtenidos por Device Flow pueden renovarse sin Client Secret. */
    fun refresh(old: GitHubCredentials): GitHubCredentials {
        val response = post("/login/oauth/access_token", mapOf(
            "client_id" to ROBGIT_RD_CLIENT_ID,
            "grant_type" to "refresh_token",
            "refresh_token" to old.refreshToken,
        ))
        if (response.has("error")) {
            val error = response.optString("error")
            throw OAuthFailure(
                if (error in setOf("bad_refresh_token", "invalid_grant"))
                    "La sesión de GitHub ha caducado o está revocada. Conecta otra vez."
                else "GitHub no pudo renovar la sesión. Se volverá a intentar.",
                permanent = error in setOf("bad_refresh_token", "invalid_grant"),
            )
        }
        return parseCredentials(response, old.login)
    }

    fun user(accessToken: String): String {
        val connection = URI("https://api.github.com/user").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "RobStore-Android")
            connection.setRequestProperty("Authorization", "Bearer " + accessToken)
            if (connection.responseCode != 200) throw OAuthFailure("No se pudo verificar la cuenta de GitHub.")
            val text = connection.inputStream.use { String(it.readLimited(32 * 1024), Charsets.UTF_8) }
            return JSONObject(text).getString("login")
        } catch (error: OAuthFailure) {
            throw error
        } catch (_: Exception) {
            throw OAuthFailure("No se pudo comprobar la cuenta de GitHub.")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCredentials(json: JSONObject, login: String = ""): GitHubCredentials {
        if (!json.optString("token_type").equals("bearer", ignoreCase = true))
            throw OAuthFailure("Respuesta inesperada al conectar con GitHub.")
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        val accessLifetime = json.optLong("expires_in")
        val refreshLifetime = json.optLong("refresh_token_expires_in")
        if (access.isBlank() || refresh.isBlank() || accessLifetime <= 0 || refreshLifetime <= 0)
            throw OAuthFailure("GitHub no devolvió credenciales renovables.")
        val instant = now()
        return GitHubCredentials(
            access, refresh,
            instant + accessLifetime * 1000,
            instant + refreshLifetime * 1000, login,
        )
    }

    private fun post(path: String, fields: Map<String, String>): JSONObject {
        require(path in setOf("/login/device/code", "/login/oauth/access_token"))
        val connection = URI("https://github.com" + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "RobStore-Android")
            val payload = fields.entries.joinToString("&") { (key, value) ->
                URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
            }.toByteArray(Charsets.UTF_8)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            if (connection.responseCode !in 200..299)
                throw OAuthFailure("GitHub rechazó la conexión (HTTP " + connection.responseCode + ").")
            val response = connection.inputStream.use { String(it.readLimited(32 * 1024), Charsets.UTF_8) }
            return JSONObject(response)
        } catch (error: OAuthFailure) {
            throw error
        } catch (_: Exception) {
            throw OAuthFailure("No se pudo conectar con GitHub. Comprueba Internet.")
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readLimited(limit: Int): ByteArray {
        val buffer = ByteArray(4096)
        val result = java.io.ByteArrayOutputStream()
        while (true) {
            val count = read(buffer)
            if (count == -1) break
            if (result.size() + count > limit) throw IOException("Respuesta demasiado grande")
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }
}
