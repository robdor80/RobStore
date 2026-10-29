package es.robertodorado.robstore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import es.robertodorado.robstore.R
import es.robertodorado.robstore.ui.robstyle.RobStyleDimensions

@Composable
fun RobStoreApp() {
    Scaffold { contentPadding ->
        FoundationScreen(
            appName = stringResource(R.string.app_name),
            modifier = Modifier.padding(contentPadding),
        )
    }
}

@Composable
private fun FoundationScreen(appName: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(RobStyleDimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(
            RobStyleDimensions.contentSpacing,
            Alignment.CenterVertically,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = appName, style = MaterialTheme.typography.headlineMedium)
        Text(
            text = stringResource(R.string.foundation_phase),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
