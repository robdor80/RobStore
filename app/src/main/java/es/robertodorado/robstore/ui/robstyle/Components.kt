package es.robertodorado.robstore.ui.robstyle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import es.robertodorado.robstore.R

@Composable
fun RobCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.outlinedCardElevation(RobStyleDimensions.cardElevation),
    ) {
        Column(Modifier.padding(RobStyleDimensions.screenPadding),
            verticalArrangement = Arrangement.spacedBy(RobStyleDimensions.contentSpacing),
            content = content)
    }
}

@Composable
fun RobPrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) { Text(label) }
}

@Composable
fun RobSectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
}

enum class RobVisualState { Loading, Empty, Error, Success }

@Composable
fun RobStatus(state: RobVisualState, onRetry: () -> Unit = {}) {
    RobCard {
        if (state == RobVisualState.Loading) {
            CircularProgressIndicator()
        }
        Text(stringResource(when (state) {
            RobVisualState.Loading -> R.string.loading
            RobVisualState.Empty -> R.string.empty
            RobVisualState.Error -> R.string.error
            RobVisualState.Success -> R.string.success
        }), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(if (state == RobVisualState.Error) R.string.error_help else R.string.catalog_help),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state == RobVisualState.Error) {
            RobPrimaryButton(stringResource(R.string.retry), onClick = onRetry)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RobTopBar(title: String, canGoBack: Boolean, onBack: () -> Unit, onSettings: (() -> Unit)?) {
    TopAppBar(title = { Text(title) }, navigationIcon = {
        if (canGoBack) IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
        }
    }, actions = {
        onSettings?.let { callback -> IconButton(onClick = callback) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
        } }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}
