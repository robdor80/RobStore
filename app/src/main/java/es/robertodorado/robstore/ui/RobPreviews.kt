package es.robertodorado.robstore.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import es.robertodorado.robstore.ui.robstyle.*

@Preview(name = "Home compacta", widthDp = 320, heightDp = 640)
@Preview(name = "Home grande", widthDp = 900, heightDp = 900)
@Preview(name = "Home texto ampliado", widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
private fun HomePreview() { RobStyleTheme { HomeScreen(onSettings = {}) } }

@Preview(showBackground = true)
@Composable
private fun DetailPreview() { RobStyleTheme { AppDetailScreen(onBack = {}) } }

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() { RobStyleTheme { SettingsScreen(onBack = {}) } }

@Preview(showBackground = true)
@Composable
private fun ErrorPreview() { RobStyleTheme { RobStatus(RobVisualState.Error) } }

@Preview(showBackground = true)
@Composable
private fun LoadingPreview() { RobStyleTheme { RobStatus(RobVisualState.Loading) } }

@Preview(showBackground = true)
@Composable
private fun SuccessPreview() { RobStyleTheme { RobStatus(RobVisualState.Success) } }
