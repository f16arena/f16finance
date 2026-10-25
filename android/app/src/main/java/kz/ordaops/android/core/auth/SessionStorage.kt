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
    // Ленивое: открытие Keystore и расшифровка занимают десятки миллисекунд —
    // первым к хранилищу обращается restore() уже в фоновом потоке.
    private val prefs: SharedPreferences by lazy { open(context) }

    private fun open(context: Context): SharedPreferences = runCatching {
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

    // ── Быстрый вход ─────────────────────────────────────────────────────────
    // Копия сессии, которая переживает «Выйти»: по отпечатку продлеваем её и
    // входим без пароля — как quick-entry в Keychain на айфоне. Ручной выход
    // её не стирает; стирает отказ сервера (пароль сменили, сессию отозвали).

    fun loadQuick(): SessionClient.Session? = prefs.getString(KEY_QUICK, null)?.let {
        runCatching { ApiJson.decodeFromString(SessionClient.Session.serializer(), it) }.getOrNull()
    }

    fun saveQuick(session: SessionClient.Session) {
        prefs.edit().putString(KEY_QUICK, ApiJson.encodeToString(SessionClient.Session.serializer(), session)).apply()
    }

    fun clearQuick() {
        prefs.edit().remove(KEY_QUICK).apply()
    }

    fun hasQuick(): Boolean = prefs.contains(KEY_QUICK)

    private companion object {
        const val KEY = "session"
        const val KEY_QUICK = "quick-entry"
    }
}
