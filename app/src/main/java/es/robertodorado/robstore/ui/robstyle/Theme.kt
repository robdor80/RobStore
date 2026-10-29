package es.robertodorado.robstore.ui.robstyle

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun RobStyleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) RobStyleDarkColors else RobStyleLightColors,
        typography = RobStyleTypography,
        shapes = RobStyleShapes,
        content = content,
    )
}
