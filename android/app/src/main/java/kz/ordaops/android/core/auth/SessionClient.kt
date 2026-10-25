package kz.ordaops.android.core.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiJson
import kz.ordaops.android.core.net.JsonMediaType
import kz.ordaops.android.core.net.await
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Вход и продление сессии — через сайт (`/api/auth/mobile-session`), как на
 * айфоне. Сервер сам меняет пароль на токен; адреса и ключа Supabase в
 * приложении нет.
 */
class SessionClient(private val baseUrl: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Serializable
    data class Session(
        val accessToken: String,
        val refreshToken: String,
        /** Момент истечения, мс. Обновляемся заранее, не дожидаясь 401. */
        val expiresAtMillis: Long,
        val userId: String,
        val email: String? = null,
    ) {
        val isExpiringSoon: Boolean get() = expiresAtMillis - System.currentTimeMillis() < 120_000
    }

    sealed class AuthError(message: String) : Exception(message) {
        /** `hint` — как сервер прочитал ввод: оператор входит по логину, сотрудник по почте. */
        class InvalidCredentials(hint: String?) : AuthError(hint ?: "Неверный логин или пароль.")
        class RateLimited : AuthError("Слишком много попыток входа. Подождите пару минут.")
        class NotConfigured : AuthError("Вход временно недоступен: сервер не настроен.")
        class EndpointMissing : AuthError("Сайт ещё не обновлён под это приложение. Обратитесь к администратору.")
        class Network(detail: String) : AuthError("Нет связи с сервером. $detail")
        class Server(message: String) : AuthError(message)
    }

    @Serializable
    private data class SessionResponse(val session: Payload) {
        @Serializable
        data class Payload(
            val access_token: String,
            val refresh_token: String,
            val expires_at: Double,
            val user: User,
        )

        @Serializable
        data class User(val id: String, val email: String? = null)
    }

    @Serializable
    private data class ErrorResponse(val error: String? = null, val message: String? = null)

    /** `login` — почта сотрудника или логин оператора: различает сервер. */
    suspend fun signIn(login: String, password: String): Session = post(
        "api/auth/mobile-session",
        mapOf("action" to "signIn", "login" to login.trim(), "password" to password),
    )

    suspend fun refresh(refreshToken: String): Session =
        post("api/auth/mobile-session", mapOf("action" to "refresh", "refresh_token" to refreshToken))

    /** Письмо с кодом. Ответ одинаковый для заведённой и незаведённой почты. */
    suspend fun requestPasswordReset(email: String) {
        call("api/auth/password-reset", mapOf("action" to "request", "email" to email.trim()), "Не удалось сбросить пароль")
    }

    suspend fun confirmPasswordReset(email: String, code: String, password: String) {
        call(
            "api/auth/password-reset",
            mapOf("action" to "confirm", "email" to email.trim(), "code" to code.trim(), "password" to password),
            "Не удалось сбросить пароль",
        )
    }

    private suspend fun post(path: String, body: Map<String, String>): Session {
        val text = call(path, body, null)
        val decoded = try {
            ApiJson.decodeFromString(SessionResponse.serializer(), text)
        } catch (e: Exception) {
            throw AuthError.Server("Не удалось прочитать ответ сервера")
        }
        return Session(
            accessToken = decoded.session.access_token,
            refreshToken = decoded.session.refresh_token,
            expiresAtMillis = (decoded.session.expires_at * 1000).toLong(),
            userId = decoded.session.user.id,
            email = decoded.session.user.email,
        )
    }

    private suspend fun call(path: String, body: Map<String, String>, fallback: String?): String {
        val json = buildJsonObject { body.forEach { (k, v) -> put(k, v) } }.toString()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/" + path)
            .header("Accept", "application/json")
            .post(json.toRequestBody(JsonMediaType))
            .build()
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw AuthError.Network(e.message ?: "")
        }
        val code = response.code
        // Тело читается из сети — не на главном потоке.
        val text = withContext(Dispatchers.IO) { response.use { it.body?.string().orEmpty() } }
        if (code in 200..299) return text
        val payload = runCatching { ApiJson.decodeFromString(ErrorResponse.serializer(), text) }.getOrNull()
        throw when (code) {
            401 -> AuthError.InvalidCredentials(payload?.message)
            404 -> AuthError.EndpointMissing()
            429 -> AuthError.RateLimited()
            503 -> AuthError.NotConfigured()
            else -> AuthError.Server(payload?.message ?: payload?.error ?: fallback ?: "Ошибка входа ($code)")
        }
    }
}
