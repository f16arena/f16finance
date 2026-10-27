package kz.ordaops.android.core.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Источник токена — его держит AuthStore. */
interface TokenProvider {
    fun currentAccessToken(): String?
    /** Обновить сессию. Новый токен или null, если обновиться нельзя. */
    suspend fun refreshAccessToken(): String?
}

enum class HttpMethod { GET, POST, PATCH, PUT, DELETE }

val JsonMediaType = "application/json; charset=utf-8".toMediaType()

data class ApiRequest(
    val path: String,
    val method: HttpMethod = HttpMethod.GET,
    val query: Map<String, String> = emptyMap(),
    val body: RequestBody? = null,
    /** Не подставлять `x-organization-id` (платформенные запросы суперадмина). */
    val skipsOrganizationHeader: Boolean = false,
) {
    companion object {
        /** Запрос с JSON-телом из готового элемента. */
        fun json(path: String, body: JsonElement, method: HttpMethod = HttpMethod.POST) =
            ApiRequest(path, method, body = body.toString().toRequestBody(JsonMediaType))

        /** Запрос с JSON-телом из сериализуемого объекта. */
        inline fun <reified T> body(path: String, body: T, method: HttpMethod = HttpMethod.POST) =
            ApiRequest(path, method, body = ApiJson.encodeToString(serializer<T>(), body).toRequestBody(JsonMediaType))
    }
}

/**
 * Клиент Next.js API — тот же путь, что у айфона и веб-портала:
 * `Authorization: Bearer <jwt>` + `x-organization-id`. Ключей Supabase в
 * приложении нет.
 */
class ApiClient(
    context: Context,
    private val baseUrl: String,
    private val tokenProvider: TokenProvider,
) {
    // AI-разделы считаются минутами — как на айфоне, 3 минуты на запрос.
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()

    // Ленивая: к кэшу ходят только из Dispatchers.IO, а не при старте приложения.
    private val cacheDir by lazy { File(context.cacheDir, "api").apply { mkdirs() } }

    @Volatile
    var organizationId: String? = null

    /**
     * Идентификатор пользователя входа (`sub` в токене доступа). Нужен там, где
     * «моё» определяется учёткой, а не оператором: у сотрудника `operatorId` нет,
     * и сообщения в чате команды узнаются по `sender_user_id`.
     */
    val currentUserId: String?
        get() = userIdFromJwt(tokenProvider.currentAccessToken())

    suspend inline fun <reified T> send(request: ApiRequest): T = send(request, serializer<T>())

    suspend fun <T> send(request: ApiRequest, deserializer: DeserializationStrategy<T>): T {
        val text = sendRaw(request)
        return try {
            ApiJson.decodeFromString(deserializer, text.ifEmpty { "{}" })
        } catch (e: Exception) {
            throw ApiError.Decoding(e.message ?: "decode")
        }
    }

    /** Запрос, ответ которого не нужен. */
    suspend fun sendUnit(request: ApiRequest) {
        sendRaw(request)
    }

    /** Запрос, ответ которого — файл (PDF отчёта): байты как есть, без разбора и без кэша. */
    suspend fun download(request: ApiRequest, isRetry: Boolean = false): ByteArray {
        val response = try {
            http.newCall(build(request)).await()
        } catch (e: IOException) {
            throw ApiError.Transport(e.message ?: "")
        }
        val code = response.code
        val bytes = withContext(Dispatchers.IO) { response.use { it.body?.bytes() ?: ByteArray(0) } }
        if (code in 200..299) return bytes
        if (code == 401 && !isRetry) {
            val token = runCatching { tokenProvider.refreshAccessToken() }.getOrNull()
            if (!token.isNullOrEmpty()) return download(request, isRetry = true)
        }
        throw makeError(code, bytes.decodeToString(), request.path)
    }

    suspend inline fun <reified T> cached(request: ApiRequest): T? = cached(request, serializer<T>())

    /**
     * Последний удачный ответ на тот же GET (не старше суток) — экран рисуется
     * сразу и уходит за свежим, как `cached` на айфоне.
     */
    suspend fun <T> cached(request: ApiRequest, deserializer: DeserializationStrategy<T>): T? = withContext(Dispatchers.IO) {
        val file = cacheFile(request)
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > 86_400_000L) return@withContext null
        runCatching { ApiJson.decodeFromString(deserializer, file.readText()) }.getOrNull()
    }

    /** При выходе из аккаунта — обязательно. */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private suspend fun sendRaw(request: ApiRequest, isRetry: Boolean = false): String {
        val response = try {
            http.newCall(build(request)).await()
        } catch (e: IOException) {
            throw ApiError.Transport(e.message ?: "")
        }
        val code = response.code
        val text = withContext(Dispatchers.IO) { response.use { it.body?.string().orEmpty() } }

        if (code in 200..299) {
            if (request.method == HttpMethod.GET) {
                withContext(Dispatchers.IO) { runCatching { cacheFile(request).writeText(text) } }
            }
            return text
        }
        // Протухший токен лечим один раз: refresh и повтор. Второй 401 — сессия мертва.
        if (code == 401 && !isRetry) {
            val token = runCatching { tokenProvider.refreshAccessToken() }.getOrNull()
            if (!token.isNullOrEmpty()) return sendRaw(request, isRetry = true)
        }
        throw makeError(code, text, request.path)
    }

    private fun build(request: ApiRequest): Request {
        val url = (baseUrl.trimEnd('/') + "/" + request.path.trimStart('/')).toHttpUrl().newBuilder().apply {
            request.query.toSortedMap().forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val body = request.body ?: if (request.method == HttpMethod.GET) null else ByteArray(0).toRequestBody()
        return Request.Builder().url(url).apply {
            header("Accept", "application/json")
            method(request.method.name, body)
            tokenProvider.currentAccessToken()?.let { header("Authorization", "Bearer $it") }
            val org = organizationId
            if (!request.skipsOrganizationHeader && org != null) header("x-organization-id", org)
        }.build()
    }

    private fun cacheFile(request: ApiRequest): File {
        val key = "${organizationId.orEmpty()}|${request.path}|${request.query.toSortedMap()}"
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(cacheDir, hash)
    }

    private fun makeError(status: Int, text: String, path: String): ApiError {
        val body = runCatching { ApiJson.decodeFromString(ApiErrorBody.serializer(), text) }.getOrNull()
        // Путь в тексте — чтобы по скриншоту было видно, какой запрос отказал.
        val detail = listOfNotNull(body?.message ?: body?.error, body?.code).filter { it.isNotEmpty() }.joinToString(" / ")
        val message = if (detail.isEmpty()) path else "$detail · $path"
        return when (status) {
            401 -> ApiError.Unauthorized(message)
            403 -> ApiError.Forbidden(
                body?.capability,
                when (body?.reason) {
                    "org-disabled" -> ApiError.ForbiddenReason.OrgDisabled
                    "staff-only" -> ApiError.ForbiddenReason.StaffOnly
                    "feature-locked" -> ApiError.ForbiddenReason.FeatureLocked
                    else -> if (body?.capability == null) ApiError.ForbiddenReason.Unknown else ApiError.ForbiddenReason.MissingCapability
                },
                message,
            )
            404 -> ApiError.NotFound(message)
            409 -> ApiError.Conflict(body?.error ?: body?.code, message)
            400, 422 -> ApiError.BadRequest(body?.error ?: body?.code, message)
            else -> ApiError.Server(status, message)
        }
    }
}

/** Поле `sub` из полезной части JWT; null, если токен не разобрать. */
internal fun userIdFromJwt(token: String?): String? {
    val payload = token?.split('.')?.getOrNull(1) ?: return null
    return runCatching {
        val json = String(java.util.Base64.getUrlDecoder().decode(payload))
        (ApiJson.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject)
            ?.get("sub")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }.getOrNull()
}

/** OkHttp-вызов как suspend: отмена корутины отменяет запрос. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}
