package kz.ordaops.android.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Сейф для быстрого входа — как Keychain с биометрией на айфоне.
 *
 * Пара ключей RSA живёт в Android Keystore. Открытым ключом сессию шифруем
 * когда угодно (токен продлевается молча, без отпечатка), а закрытый ключ
 * расшифровывает только после подтверждения отпечатком: без пальца копию не
 * прочитать даже с полным доступом к данным приложения. Добавили новый отпечаток
 * в телефон — ключ сгорает, нужен вход паролем.
 *
 * Сессия больше блока RSA, поэтому схема гибридная: случайный AES-ключ шифрует
 * сессию (GCM), а RSA шифрует этот AES-ключ.
 */
class QuickEntryVault(context: Context) {
    private val prefs = context.getSharedPreferences("orda.quick-vault", Context.MODE_PRIVATE)
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    fun hasSecret(): Boolean = prefs.contains(KEY_DATA)

    fun clear() {
        prefs.edit().clear().apply()
        runCatching { keyStore.deleteEntry(ALIAS) }
    }

    /** Зашифровать без отпечатка — открытым ключом. */
    fun store(plain: String) {
        runCatching {
            val publicKey = (keyStore.getCertificate(ALIAS) ?: generate().let { keyStore.getCertificate(ALIAS) }).publicKey
            val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val gcm = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aes) }
            val data = gcm.doFinal(plain.toByteArray())
            val rsa = Cipher.getInstance(RSA).apply { init(Cipher.ENCRYPT_MODE, publicKey, OAEP) }
            prefs.edit()
                .putString(KEY_WRAPPED, b64(rsa.doFinal(aes.encoded)))
                .putString(KEY_IV, b64(gcm.iv))
                .putString(KEY_DATA, b64(data))
                .apply()
        }
    }

    /**
     * Шифр для окна отпечатка (CryptoObject). null — ключа нет или он сгорел
     * после нового отпечатка: копию стираем, вход паролем.
     */
    fun decryptCipher(): Cipher? = try {
        val key = keyStore.getKey(ALIAS, null) as? PrivateKey ?: return null
        Cipher.getInstance(RSA).apply { init(Cipher.DECRYPT_MODE, key, OAEP) }
    } catch (e: KeyPermanentlyInvalidatedException) {
        clear()
        null
    } catch (e: Exception) {
        null
    }

    /** Расшифровать шифром, который открыл отпечаток. */
    fun open(cipher: Cipher): String? = runCatching {
        val aesBytes = cipher.doFinal(unb64(prefs.getString(KEY_WRAPPED, null)!!))
        val gcm = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aesBytes, "AES"), GCMParameterSpec(128, unb64(prefs.getString(KEY_IV, null)!!)))
        }
        String(gcm.doFinal(unb64(prefs.getString(KEY_DATA, null)!!)))
    }.getOrNull()

    private fun generate() {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    // Закрытый ключ — только после отпечатка, каждый раз.
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build(),
            )
        }.generateKeyPair()
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "orda.quick-entry"
        const val RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        // MGF1 с SHA-1 — единственный вариант, который Keystore принимает при расшифровке.
        val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
        const val KEY_WRAPPED = "wrapped"
        const val KEY_IV = "iv"
        const val KEY_DATA = "data"
    }
}
