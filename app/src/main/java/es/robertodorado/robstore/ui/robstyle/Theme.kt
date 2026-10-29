package es.robertodorado.robstore.ui.robstyle

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun RobStyleTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) RobStyleDarkColors else RobStyleLightColors,
        typography = RobStyleTypography,
        shapes = RobStyleShapes,
        content = content,
    )
}
