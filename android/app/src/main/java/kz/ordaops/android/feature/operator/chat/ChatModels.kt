package kz.ordaops.android.feature.operator.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

// Модели чата — зеркало FeedModels.swift. Все поля с умолчаниями: новый ключ
// или null на бэке не должны ронять экран.

/** Время из ответа сервера (ISO-8601 с поясом) в миллисекундах; мусор — null. */
internal fun parseTimeMs(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
}

@Serializable
internal data class ChatAttachment(
    val type: String = "file",
    val url: String = "",
    val name: String? = null,
) {
    val isImage: Boolean get() = type == "image" || type == "gif" || type == "sticker" || type.startsWith("image/")

    /** Тип приходит то `audio`, то MIME браузера, то вообще не приходит — угадываем по расширению. */
    val isAudio: Boolean
        get() = type == "audio" || type.startsWith("audio/") ||
            url.endsWith(".m4a") || url.endsWith(".mp3") || url.endsWith(".ogg")

    /** Опрос на сайте рисуется отдельным виджетом — вложением в ленте он не нужен. */
    val isPoll: Boolean get() = type == "poll"

    val label: String
        get() = when {
            !name.isNullOrEmpty() -> name
            isAudio -> "Голосовое"
            isImage -> "Изображение"
            else -> "Файл"
        }
}

@Serializable
internal data class ChatReaction(
    val id: String = "",
    @SerialName("user_name") val userName: String? = null,
    val emoji: String = "👍",
)

/** Одинаковые эмодзи, свёрнутые в счётчик (порядок первого появления). */
internal data class ReactionGroup(val emoji: String, val count: Int)

internal fun groupReactions(reactions: List<ChatReaction>): List<ReactionGroup> {
    val counts = LinkedHashMap<String, Int>()
    reactions.forEach { counts[it.emoji] = (counts[it.emoji] ?: 0) + 1 }
    return counts.map { ReactionGroup(it.key, it.value) }
}

@Serializable
internal data class TeamChatMessage(
    val id: String = "",
    @SerialName("sender_user_id") val senderUserId: String? = null,
    @SerialName("sender_operator_id") val senderOperatorId: String? = null,
    @SerialName("sender_name") val senderName: String = "Аноним",
    @SerialName("sender_role") val senderRole: String? = null,
    @SerialName("sender_avatar_url") val senderAvatarUrl: String? = null,
    @SerialName("message") val text: String = "",
    val attachments: List<ChatAttachment> = emptyList(),
    @SerialName("reply_to_id") val replyToId: String? = null,
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("is_announcement") val isAnnouncement: Boolean = false,
    @SerialName("pinned_until") val pinnedUntil: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val reactions: List<ChatReaction> = emptyList(),
    /** Сообщение уже на экране, а на сервер ещё летит. На сервер не ходит. */
    val isPending: Boolean = false,
) {
    val isDeleted: Boolean get() = deletedAt != null
    val isEdited: Boolean get() = editedAt != null
    val createdMs: Long? get() = parseTimeMs(createdAt)
    val displayText: String get() = ChatText.display(text)
    val reactionGroups: List<ReactionGroup> get() = groupReactions(reactions)

    /** Закрепление живёт до даты: истёкшее не держит сообщение наверху. */
    val isPinned: Boolean get() = (parseTimeMs(pinnedUntil) ?: 0L) > System.currentTimeMillis()

    val roleLabel: String?
        get() = when (senderRole) {
            "owner" -> "владелец"
            "manager" -> "менеджер"
            "operator" -> "оператор"
            "super_admin" -> "супер-админ"
            else -> null
        }
}

@Serializable
internal data class PollOption(val id: String = "", val label: String = "")

@Serializable
internal data class PollInfo(
    val id: String = "",
    val question: String = "",
    val options: List<PollOption> = emptyList(),
    @SerialName("multiple_choice") val multipleChoice: Boolean = false,
)

/** Опрос с результатами. Голоса не тайные: видно, кто за что. */
@Serializable
internal data class ChatPoll(
    val poll: PollInfo = PollInfo(),
    val counts: Map<String, Int> = emptyMap(),
    val voters: Map<String, List<String>> = emptyMap(),
    val myVote: List<String> = emptyList(),
    val totalVotes: Int = 0,
) {
    fun count(option: PollOption): Int = counts[option.id] ?: 0
    fun hasVoted(option: PollOption): Boolean = option.id in myVote

    /** Ноль голосов — ноль ширины, а не полоса во всю карточку. */
    fun share(option: PollOption): Float = if (totalVotes > 0) count(option).toFloat() / totalVotes else 0f
}

@Serializable
internal data class TeamChatFeed(
    val messages: List<TeamChatMessage> = emptyList(),
    val pinned: List<TeamChatMessage> = emptyList(),
    /** Опросы по id сообщения — так их отдаёт сервер. */
    val polls: Map<String, ChatPoll> = emptyMap(),
) {
    /** Удалённые сервер отдаёт с пустым текстом — в ленте им делать нечего. */
    val visible: List<TeamChatMessage> get() = messages.filter { !it.isDeleted }

    fun message(id: String): TeamChatMessage? = messages.firstOrNull { it.id == id }
}

// ── Личные сообщения ─────────────────────────────────────────────────────────

@Serializable
internal data class DirectContact(
    @SerialName("user_id") val userId: String = "",
    val name: String = "Без имени",
    val role: String? = null,
    /** `staff` или `operator`. */
    val kind: String = "staff",
) {
    val roleLabel: String?
        get() = when (role) {
            "owner" -> "владелец"
            "manager" -> "менеджер"
            "marketer" -> "маркетолог"
            "operator" -> "оператор"
            else -> role
        }
}

@Serializable
internal data class DirectThread(
    val otherUserId: String = "",
    val otherName: String = "Без имени",
    val lastMessage: String = "",
    val lastAt: String? = null,
    val lastFromMe: Boolean = false,
    val unreadCount: Int = 0,
) {
    val preview: String get() = ChatText.preview(lastMessage)
    val hasUnread: Boolean get() = unreadCount > 0
}

@Serializable
internal data class DirectThreadList(val threads: List<DirectThread> = emptyList()) {
    val usable: List<DirectThread> get() = threads.filter { it.otherUserId.isNotEmpty() }
}

@Serializable
internal data class DirectMessage(
    val id: String = "",
    @SerialName("sender_user_id") val senderUserId: String? = null,
    @SerialName("recipient_user_id") val recipientUserId: String? = null,
    @SerialName("sender_name") val senderName: String = "Аноним",
    @SerialName("message") val text: String = "",
    val attachments: List<ChatAttachment> = emptyList(),
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val isPending: Boolean = false,
) {
    val isDeleted: Boolean get() = deletedAt != null
    val isEdited: Boolean get() = editedAt != null
    val createdMs: Long? get() = parseTimeMs(createdAt)
    val displayText: String get() = ChatText.display(text)

    /** Своё определяем по собеседнику: своего id в ответе нет, а собеседник известен по маршруту. */
    fun isMine(otherUserId: String): Boolean = senderUserId != otherUserId
}

@Serializable
internal data class DirectConversation(val messages: List<DirectMessage> = emptyList()) {
    val visible: List<DirectMessage> get() = messages.filter { !it.isDeleted }
}

/** Карточка коллеги: `/api/team-chat/person`. Телефон намеренно не приходит. */
@Serializable
internal data class ChatPersonCard(
    val name: String = "Коллега",
    val position: String? = null,
    val companies: List<String> = emptyList(),
    @SerialName("hire_date") val hireDate: String? = null,
    @SerialName("on_shift") val onShift: Boolean = false,
    @SerialName("shift_company") val shiftCompany: String? = null,
)

@Serializable
internal data class DataEnvelope<T>(val data: T? = null)

@Serializable
internal data class DataList<T>(val data: List<T> = emptyList())

@Serializable
internal data class UploadResponse(val url: String = "", val name: String? = null)

/** Загруженный файл — то, что уходит в сообщение. */
internal data class UploadedAttachment(val type: String, val url: String, val name: String)
