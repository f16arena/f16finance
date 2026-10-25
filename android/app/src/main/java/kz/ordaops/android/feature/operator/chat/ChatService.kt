package kz.ordaops.android.feature.operator.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod
import java.time.Instant

/** Текст сообщений: часть набрана голым текстом, часть — в веб-редакторе (HTML). */
internal object ChatText {
    private val tag = Regex("<[a-zA-Z/][^>]*>")

    fun looksLikeHtml(text: String) = tag.containsMatchIn(text)

    /** Готовый к показу текст: разметка убрана, переносы сохранены. */
    fun display(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        if (!looksLikeHtml(text)) return text
        return text
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(p|div|li|blockquote)>", RegexOption.IGNORE_CASE), "\n")
            .replace(tag, "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .trim()
    }

    /** Однострочное превью. */
    fun preview(text: String?, limit: Int = 90): String {
        val flat = display(text).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return if (flat.length > limit) flat.take(limit).trimEnd() + "…" else flat
    }

    fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(1).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }
}

/** Все вызовы чата — те же маршруты и тела, что в FeedService на айфоне. */
internal class ChatService(private val api: ApiClient) {

    // Командный чат

    private fun teamRequest(limit: Int, search: String = ""): ApiRequest {
        val query = mutableMapOf("limit" to limit.toString())
        if (search.isNotEmpty()) query["q"] = search
        return ApiRequest("/api/team-chat", query = query)
    }

    suspend fun teamChat(search: String = "", limit: Int = 80): TeamChatFeed = api.send(teamRequest(limit, search))

    suspend fun cachedTeamChat(limit: Int = 80): TeamChatFeed? = api.cached(teamRequest(limit))

    suspend fun sendTeamMessage(text: String, replyToId: String? = null, attachments: List<UploadedAttachment> = emptyList()) {
        api.sendUnit(
            ApiRequest.json(
                "/api/team-chat",
                buildJsonObject {
                    put("message", text)
                    if (replyToId != null) put("replyToId", replyToId)
                    if (attachments.isNotEmpty()) put("attachments", attachmentsJson(attachments))
                },
            ),
        )
    }

    /** Файл в чат: сначала загрузка, потом сообщение со ссылкой — так же делает сайт. */
    suspend fun upload(bytes: ByteArray, fileName: String, mimeType: String, kind: String): UploadedAttachment {
        val media = mimeType.toMediaTypeOrNull()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(media))
            .build()
        val response: UploadResponse = api.send(ApiRequest("/api/team-chat/upload", HttpMethod.POST, body = body))
        return UploadedAttachment(kind, response.url, response.name ?: fileName)
    }

    suspend fun chatPerson(userId: String): ChatPersonCard? {
        val response: DataEnvelope<ChatPersonCard> =
            api.send(ApiRequest("/api/team-chat/person", query = mapOf("userId" to userId)))
        return response.data
    }

    suspend fun react(messageId: String, emoji: String) {
        api.sendUnit(ApiRequest.json("/api/team-chat/reactions", buildJsonObject { put("messageId", messageId); put("emoji", emoji) }))
    }

    suspend fun editTeamMessage(id: String, text: String) {
        api.sendUnit(ApiRequest.json("/api/team-chat", buildJsonObject { put("id", id); put("message", text) }, HttpMethod.PATCH))
    }

    suspend fun deleteTeamMessage(id: String) {
        api.sendUnit(ApiRequest.json("/api/team-chat", buildJsonObject { put("id", id) }, HttpMethod.DELETE))
    }

    suspend fun pinTeamMessage(id: String, untilMs: Long) {
        api.sendUnit(
            ApiRequest.json(
                "/api/team-chat/pin",
                buildJsonObject { put("id", id); put("until", Instant.ofEpochMilli(untilMs).toString()) },
            ),
        )
    }

    suspend fun unpinTeamMessage(id: String) {
        api.sendUnit(ApiRequest.json("/api/team-chat/pin", buildJsonObject { put("id", id) }, HttpMethod.DELETE))
    }

    /** Жалоба — в тот же журнал модерации, что и находки ИИ. */
    suspend fun report(messageId: String, source: String) {
        api.sendUnit(ApiRequest.json("/api/chat/report", buildJsonObject { put("messageId", messageId); put("source", source) }))
    }

    suspend fun createPoll(question: String, options: List<String>) {
        api.sendUnit(
            ApiRequest.json(
                "/api/team-chat/polls",
                buildJsonObject {
                    put("question", question)
                    put(
                        "options",
                        JsonArray(options.mapIndexed { i, label -> buildJsonObject { put("id", "opt${i + 1}"); put("label", label) } }),
                    )
                },
            ),
        )
    }

    /** Повторный голос за тот же вариант его снимает. */
    suspend fun vote(pollId: String, optionId: String) {
        api.sendUnit(ApiRequest.json("/api/team-chat/polls/$pollId/vote", buildJsonObject { put("optionId", optionId) }))
    }

    // Блокировка собеседника

    suspend fun blockedUsers(): List<String> = api.send<DataList<String>>(ApiRequest("/api/direct-messages/block")).data

    suspend fun setBlocked(blocked: Boolean, userId: String) {
        api.sendUnit(
            ApiRequest.json(
                "/api/direct-messages/block",
                buildJsonObject { put("userId", userId) },
                if (blocked) HttpMethod.POST else HttpMethod.DELETE,
            ),
        )
    }

    // Личные сообщения

    suspend fun threads(): DirectThreadList = api.send(ApiRequest("/api/direct-messages/threads"))

    suspend fun cachedThreads(): DirectThreadList? = api.cached(ApiRequest("/api/direct-messages/threads"))

    suspend fun contacts(): List<DirectContact> =
        api.send<DataList<DirectContact>>(ApiRequest("/api/direct-messages/contacts")).data

    private fun conversationRequest(userId: String, limit: Int) =
        ApiRequest("/api/direct-messages/$userId", query = mapOf("limit" to limit.toString()))

    suspend fun conversation(userId: String, limit: Int = 100): DirectConversation = api.send(conversationRequest(userId, limit))

    suspend fun cachedConversation(userId: String, limit: Int = 100): DirectConversation? = api.cached(conversationRequest(userId, limit))

    suspend fun sendDirect(userId: String, text: String, attachments: List<UploadedAttachment> = emptyList()) {
        api.sendUnit(
            ApiRequest.json(
                "/api/direct-messages",
                buildJsonObject {
                    put("recipientUserId", userId)
                    put("message", text)
                    if (attachments.isNotEmpty()) put("attachments", attachmentsJson(attachments))
                },
            ),
        )
    }

    private fun attachmentsJson(list: List<UploadedAttachment>): JsonArray = JsonArray(
        list.map { a ->
            JsonObject(mapOf("type" to JsonPrimitive(a.type), "url" to JsonPrimitive(a.url), "name" to JsonPrimitive(a.name)))
        },
    )
}
