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

    /** Сильная биометрия — только она открывает ключи Keystore. */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * С `cipher` — вход с ключом из Keystore: только сильная биометрия (отпечаток,
     * 3D-лицо), и `onSuccess` получает шифр, открытый пальцем.
     */
    fun authenticate(
        activity: FragmentActivity,
        cipher: javax.crypto.Cipher? = null,
        onSuccess: (javax.crypto.Cipher?) -> Unit,
        onError: (String) -> Unit = {},
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess(result.cryptoObject?.cipher)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Отмену человеком ошибкой не считаем — он просто передумал.
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        onError(errString.toString())
                    }
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Вход в Orda")
            .setSubtitle("Подтвердите, что это вы")
            .setNegativeButtonText("Ввести пароль")
            .setAllowedAuthenticators(if (cipher != null) BIOMETRIC_STRONG else AUTHENTICATORS)
            .build()
        if (cipher != null) prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher)) else prompt.authenticate(info)
    }
}
