package es.robertodorado.robstore

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import es.robertodorado.robstore.ui.RobStoreApp
import es.robertodorado.robstore.ui.robstyle.RobStyleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(7, 13, 24)),
        )
        setContent {
            RobStyleTheme {
                RobStoreApp()
            }
        }
    }
}
