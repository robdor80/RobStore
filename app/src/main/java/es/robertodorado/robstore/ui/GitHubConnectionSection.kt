package es.robertodorado.robstore.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import es.robertodorado.robstore.auth.GitHubAuthState
import es.robertodorado.robstore.ui.robstyle.*

/** Conexión mínima desde Ajustes; no añade pantallas ni configuración de secretos. */
@Composable
internal fun GitHubConnectionSection(
    state: GitHubAuthState,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val browser = LocalUriHandler.current
    RobCard {
        RobSectionHeader("Conexión con GitHub")
        Text(if (state.connected) "Conectado como " + state.login else "Sin conexión. El catálogo público sigue disponible.")
        Text("Usamos RobGit RD. Solo tienes que autorizar la conexión una vez.")
        state.message?.let { Text(it) }
        if (state.connected) {
            OutlinedButton(onClick = onDisconnect) { Text("Desconectar") }
        } else if (!state.authorizing) {
            Button(onClick = onConnect) { Text("Conectar con GitHub") }
        }
        state.pending?.let { pending ->
            Text("Código de autorización: " + pending.userCode)
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(pending.userCode)) }) {
                Text("Copiar código")
            }
            Button(onClick = { browser.openUri(pending.verificationUri) }) {
                Text("Abrir GitHub para autorizar")
            }
            Text("Si GitHub no permite pegar el código completo, introdúcelo manualmente. La conexión se confirmará aquí.")
        }
        if (state.authorizing) {
            LinearProgressIndicator()
            OutlinedButton(onClick = onCancel) { Text("Cancelar conexión") }
        }
    }
}
