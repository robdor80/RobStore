package es.robertodorado.robstore.auth

import es.robertodorado.robstore.catalog.HttpRequest
import es.robertodorado.robstore.catalog.HttpResult
import es.robertodorado.robstore.catalog.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal data class GitHubAuthState(
    val login: String? = null,
    val authorizing: Boolean = false,
    val pending: DeviceAuthorization? = null,
    val message: String? = null,
) {
    val connected get() = login != null
}

internal interface AccessTokenProvider {
    fun accessToken(): String?
    fun unauthorized()
}

/**
 * Una sesión por instalación de RobStore, con renovación automática.
 * Las consultas públicas siguen funcionando sin conexión de usuario.
 */
internal class GitHubAuthManager(
    private val store: GitHubCredentialStore,
    private val oauth: GitHubOAuth = GitHubOAuth(),
    private val now: () -> Long = System::currentTimeMillis,
) : AccessTokenProvider {
    private val lock = Any()
    private val mutableState = MutableStateFlow(GitHubAuthState())
    val state = mutableState.asStateFlow()

    init {
        try {
            val saved = store.read()
            if (saved != null && saved.refreshExpiresAt > now()) {
                mutableState.value = GitHubAuthState(login = saved.login)
            } else if (saved != null) {
                store.clear()
                mutableState.value = GitHubAuthState(message = "La sesión anterior ha caducado. Conecta de nuevo.")
            }
        } catch (_: Exception) {
            runCatching { store.clear() }
            mutableState.value = GitHubAuthState(message = "No se pudo recuperar la sesión anterior. Conecta de nuevo.")
        }
    }

    /** Se invoca desde un hilo de E/S mediante el transporte HTTP. */
    override fun accessToken(): String? = synchronized(lock) {
        val saved = try { store.read() } catch (_: Exception) {
            runCatching { store.clear() }
            mutableState.value = GitHubAuthState(message = "Sesión cifrada no disponible. Conecta de nuevo.")
            return@synchronized null
        } ?: return@synchronized null

        if (now() >= saved.refreshExpiresAt) {
            runCatching { store.clear() }
            mutableState.value = GitHubAuthState(message = "La sesión ha caducado. Conecta de nuevo.")
            return@synchronized null
        }
        if (now() < saved.accessExpiresAt - 300_000L) return@synchronized saved.accessToken

        try {
            val fresh = oauth.refresh(saved)
            store.save(fresh)
            mutableState.value = mutableState.value.copy(login = fresh.login, message = null)
            fresh.accessToken
        } catch (failure: OAuthFailure) {
            if (failure.permanent) {
                runCatching { store.clear() }
                mutableState.value = GitHubAuthState(message = failure.message)
            } else {
                mutableState.value = mutableState.value.copy(message = failure.message)
            }
            null
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(message = "No se pudo renovar GitHub. El catálogo público sigue disponible.")
            null
        }
    }

    override fun unauthorized() = synchronized(lock) {
        runCatching { store.clear() }
        mutableState.value = GitHubAuthState(message = "GitHub ha rechazado la sesión. Conecta otra vez.")
    }

    fun disconnect() = synchronized(lock) {
        store.clear()
        mutableState.value = GitHubAuthState(message = "Desconectado de GitHub. El catálogo público sigue disponible.")
    }

    /**
     * Se ejecuta en viewModelScope; la comprobación periódica se cancela al salir
     * o al pulsar Cancelar. No repite la solicitud si el usuario no autorizó.
     */
    suspend fun authorize(): Boolean {
        if (mutableState.value.authorizing) return false
        mutableState.value = mutableState.value.copy(authorizing = true, pending = null, message = null)
        try {
            val pending = withContext(Dispatchers.IO) { oauth.begin() }
            mutableState.value = mutableState.value.copy(pending = pending)
            var interval = pending.intervalSeconds
            while (now() < pending.expiresAt) {
                delay(interval * 1000L)
                when (val result = withContext(Dispatchers.IO) { oauth.poll(pending) }) {
                    DeviceResult.Waiting -> Unit
                    DeviceResult.SlowDown -> interval += 5L
                    is DeviceResult.Failed -> {
                        mutableState.value = mutableState.value.copy(message = result.message)
                        return false
                    }
                    is DeviceResult.Authorized -> {
                        val login = withContext(Dispatchers.IO) { oauth.user(result.credentials.accessToken) }
                        if (login != "robdor80") {
                            mutableState.value = mutableState.value.copy(message = "Conecta con la cuenta robdor80.")
                            return false
                        }
                        currentCoroutineContext().ensureActive()
                        synchronized(lock) {
                            store.save(result.credentials.copy(login = login))
                            mutableState.value = mutableState.value.copy(login = login, message = "Conectado a GitHub. La sesión se renovará automáticamente.")
                        }
                        return true
                    }
                }
            }
            mutableState.value = mutableState.value.copy(message = "Ha caducado el código. Vuelve a conectar.")
            return false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: OAuthFailure) {
            mutableState.value = mutableState.value.copy(message = error.message)
            return false
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(message = "No se pudo conectar con GitHub. Inténtalo otra vez.")
            return false
        } finally {
            mutableState.value = mutableState.value.copy(authorizing = false, pending = null)
        }
    }

    fun cancelAuthorization() {
        mutableState.value = mutableState.value.copy(authorizing = false, pending = null, message = "Autorización cancelada.")
    }
}

/** Añade el token únicamente a la API de GitHub; nunca a dominios externos. */
internal class GitHubAuthenticatedTransport(
    private val delegate: HttpTransport,
    private val provider: AccessTokenProvider,
) : HttpTransport {
    override fun execute(request: HttpRequest): HttpResult {
        require(request.url.scheme == "https" && request.url.host == "api.github.com" &&
            request.url.port == 443 && request.url.username.isEmpty() && request.url.password.isEmpty())
        val token = provider.accessToken()
        val result = delegate.execute(
            if (token != null) request.copy(headers = request.headers + ("Authorization" to ("Bearer " + token)))
            else request,
        )
        if (token != null && result.status == 401) provider.unauthorized()
        return result
    }
}
