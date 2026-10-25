package kz.ordaops.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kz.ordaops.android.ui.RootScreen
import kz.ordaops.android.ui.theme.OrdaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OrdaApplication
        setContent {
            OrdaTheme {
                RootScreen(app)
            }
        }
    }
}
