package es.robertodorado.robstore.ui.robstyle

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val RobStyleDarkColors = darkColorScheme(
    primary = Color(0xFF69DCFF), onPrimary = Color(0xFF002C3B),
    secondary = Color(0xFF9EBFFF), onSecondary = Color(0xFF08294F),
    background = Color(0xFF070D18), onBackground = Color(0xFFE7EFFA),
    surface = Color(0xFF101C2D), onSurface = Color(0xFFE7EFFA),
    surfaceVariant = Color(0xFF1A2B41), onSurfaceVariant = Color(0xFFBAC9DC),
    outline = Color(0xFF8094AD), outlineVariant = Color(0xFF34465E),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)
internal val RobStyleLightColors = lightColorScheme(
    primary = Color(0xFF006582), onPrimary = Color.White,
    background = Color(0xFFF2F6FC), onBackground = Color(0xFF101C2D),
    surface = Color(0xFFE5EDF7), onSurface = Color(0xFF101C2D),
    onSurfaceVariant = Color(0xFF3D5067),
)
