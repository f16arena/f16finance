package kz.ordaops.android.core.net

import kotlinx.serialization.Serializable

/**
 * Ошибка обращения к API — то же деление, что `APIError` в apple/OrdaKit.
 * 403 разбирается отдельно: человеку нужно «нет права X», а не «ошибка 403».
 */
sealed class ApiError(message: String) : Exception(message) {
    /** Нет сети, таймаут, обрыв. */
    class Transport(message: String) : ApiError(message)
    /** Сессия истекла и обновиться не удалось. */
    class Unauthorized(message: String) : ApiError(message)
    class Forbidden(val capability: String?, val reason: ForbiddenReason, message: String) : ApiError(message)
    /** Нет открытой смены, конфликт состояния (409). */
    class Conflict(val code: String?, message: String) : ApiError(message)
    /** Ошибка проверки (400/422). */
    class BadRequest(val code: String?, message: String) : ApiError(message)
    class NotFound(message: String) : ApiError(message)
    class Server(val status: Int, message: String) : ApiError(message)
    class Decoding(message: String) : ApiError(message)

    enum class ForbiddenReason { MissingCapability, OrgDisabled, StaffOnly, FeatureLocked, Unknown }

    /** Текст для человека — без кодов и технических слов. */
    val userMessage: String
        get() = when (this) {
            is Transport -> "Нет связи с сервером. Проверьте интернет — данные сохранятся и уйдут позже."
            is Unauthorized -> "Сессия истекла. Войдите заново."
            is Forbidden -> when (reason) {
                ForbiddenReason.OrgDisabled -> "Это действие отключено для вашей организации. Обратитесь в поддержку Orda."
                ForbiddenReason.StaffOnly -> "Раздел доступен только сотрудникам."
                ForbiddenReason.FeatureLocked -> "Модуль не подключён к вашей организации."
                else -> withoutRoute(message).ifEmpty { "У вас нет доступа к этому действию." }
            }
            is Conflict -> withoutRoute(message).ifEmpty { "Действие сейчас невозможно." }
            is BadRequest -> withoutRoute(message).ifEmpty { "Проверьте введённые данные." }
            is NotFound -> "Не найдено."
            is Server -> "Сервер временно недоступен. Попробуйте ещё раз."
            is Decoding -> "Не удалось прочитать ответ сервера."
        }

    /** Подпись мелким шрифтом там, где текст для человека ничего не объясняет. */
    val technicalDetail: String?
        get() = when (this) {
            is Server -> if (message.isNullOrEmpty()) "HTTP $status" else "HTTP $status · $message"
            is Decoding, is Transport -> message?.ifEmpty { null }
            else -> null
        }

    val isRetryable: Boolean get() = this is Transport || this is Server

    companion object {
        /** Клиент дописывает « · /api/…» для журнала — человеку это шум. */
        fun withoutRoute(message: String?): String {
            val m = message ?: return ""
            val i = m.indexOf(" · /api")
            return if (i >= 0) m.substring(0, i) else m
        }
    }
}

@Serializable
internal data class ApiErrorBody(
    val error: String? = null,
    val message: String? = null,
    val capability: String? = null,
    val reason: String? = null,
    val code: String? = null,
)

/** Текст ошибки для экрана: и наши `ApiError`, и всё остальное. */
fun Throwable.userMessage(): String = (this as? ApiError)?.userMessage ?: (message ?: "Что-то пошло не так")
