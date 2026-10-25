package kz.ordaops.android.core.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kz.ordaops.android.core.net.ApiJson

/**
 * Сессия на устройстве — в зашифрованном хранилище (ключ в Android Keystore),
 * как Keychain на айфоне.
 */
class SessionStorage(context: Context) {
    private val prefs: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "orda.session",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Keystore бывает сломан на отдельных прошивках. Лучше вход, который
        // не переживёт перезапуск, чем приложение, которое не открывается.
        context.getSharedPreferences("orda.session.fallback", Context.MODE_PRIVATE)
    }

    fun load(): SessionClient.Session? = prefs.getString(KEY, null)?.let {
        runCatching { ApiJson.decodeFromString(SessionClient.Session.serializer(), it) }.getOrNull()
    }

    fun save(session: SessionClient.Session): Boolean =
        prefs.edit().putString(KEY, ApiJson.encodeToString(SessionClient.Session.serializer(), session)).commit()

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    fun exists(): Boolean = prefs.contains(KEY)

    private companion object {
        const val KEY = "session"
    }
}
