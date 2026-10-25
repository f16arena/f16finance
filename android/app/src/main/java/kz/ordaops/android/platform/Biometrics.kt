package kz.ordaops.android.platform

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Отпечаток или лицо — Android-аналог Face ID для быстрого входа. */
object Biometrics {
    private const val AUTHENTICATORS = BIOMETRIC_STRONG or BIOMETRIC_WEAK

    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** Показать системное окно; `onSuccess` — только при подтверждённой личности. */
    fun authenticate(activity: FragmentActivity, onSuccess: () -> Unit, onError: (String) -> Unit = {}) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Отмену человеком ошибкой не считаем — он просто передумал.
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        onError(errString.toString())
                    }
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Вход в Orda")
                .setSubtitle("Подтвердите, что это вы")
                .setNegativeButtonText("Ввести пароль")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }
}
