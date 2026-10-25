package kz.ordaops.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import kz.ordaops.android.core.auth.AuthStore
import kz.ordaops.android.platform.DeepLinks
import kz.ordaops.android.ui.RootScreen
import kz.ordaops.android.ui.theme.OrdaTheme

/** FragmentActivity, а не ComponentActivity: окно отпечатка (BiometricPrompt) требует её. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // До super: заставка подменяет стартовую тему. Держим её, пока восстанавливается
        // вход, — вместо отдельного экрана загрузки сразу попадаем на нужный.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val app = application as OrdaApplication
        splash.setKeepOnScreenCondition { app.auth.state.value.phase == AuthStore.Phase.Restoring }
        enableEdgeToEdge()
        // Открыли из уведомления смены, ярлыка или плитки — запомним раздел.
        DeepLinks.consume(intent)
        setContent {
            OrdaTheme {
                RootScreen(app)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DeepLinks.consume(intent)
    }
}
