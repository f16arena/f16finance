package kz.ordaops.android.feature.operator.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kz.ordaops.android.core.net.ApiError
import kz.ordaops.android.core.net.userMessage
import java.time.Instant
import java.util.UUID

private const val OFFLINE_TEXT = "Нет связи. Сообщение отправится само, когда появится интернет."

/** Ошибка для экрана; отмену корутины пробрасываем, чтобы не глотать её. */
private fun Throwable.screenMessage(): String {
    if (this is CancellationException) throw this
    return userMessage()
}

/** Состояние командного чата. Не ViewModel: живёт, пока жив экран (`remember`). */
internal class TeamChatStore(private val service: ChatService, private val scope: CoroutineScope) {
    var feed by mutableStateOf<TeamChatFeed?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var isSending by mutableStateOf(false)
        private set
    var sendError by mutableStateOf<String?>(null)
        private set

    /** Отправленные, но ещё не подтверждённые сервером. */
    var pending by mutableStateOf(listOf<TeamChatMessage>())
        private set

    /** Что не ушло из-за связи: ждёт своей очереди, а не теряется. */
    var undelivered by mutableStateOf(listOf<String>())
        private set

    var replyTo by mutableStateOf<TeamChatMessage?>(null)
    var search by mutableStateOf("")
        private set

    private var searchJob: Job? = null

    /** Что показывать: пришедшее с сервера плюс своё, ещё летящее. */
    val visible: List<TeamChatMessage> get() = (feed?.visible ?: emptyList()) + pending

    suspend fun load() {
        // Прошлая лента — сразу. Поиск не кэшируем: там ждут ответа на запрос.
        if (feed == null && search.isEmpty()) service.cachedTeamChat()?.let { feed = it }
        try {
            feed = service.teamChat(search)
            error = null
        } catch (e: Throwable) {
            error = e.screenMessage()
        }
    }

    /** Тихое обновление: без сброса того, что человек уже читает. */
    suspend fun refresh() {
        if (isSending) return
        runCatching { service.teamChat(search) }.onSuccess { feed = it }
    }

    /** Поиск идёт на сервере — по тексту и по имени отправителя; с паузой, а не на каждую букву. */
    fun updateSearch(value: String) {
        if (value == search) return
        search = value
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(350)
            load()
        }
    }

    fun react(messageId: String, emoji: String) {
        scope.launch {
            runCatching { service.react(messageId, emoji) }
            refresh()
        }
    }

    suspend fun edit(messageId: String, text: String): String? = try {
        service.editTeamMessage(messageId, text)
        load()
        null
    } catch (e: Throwable) {
        e.screenMessage()
    }

    /** Жалоба молча: отправитель не узнает, владелец увидит в модерации. */
    fun report(messageId: String) {
        scope.launch { runCatching { service.report(messageId, "team_chat") } }
    }

    fun delete(messageId: String) {
        scope.launch {
            runCatching { service.deleteTeamMessage(messageId) }
            load()
        }
    }

    /** Закрепление на сутки — обычный срок объявления по смене. */
    fun pin(messageId: String, isPinned: Boolean) {
        scope.launch {
            runCatching {
                if (isPinned) service.unpinTeamMessage(messageId)
                else service.pinTeamMessage(messageId, System.currentTimeMillis() + 24 * 3600 * 1000L)
            }
            load()
        }
    }

    fun vote(pollId: String, optionId: String) {
        scope.launch {
            try {
                service.vote(pollId, optionId)
                load()
            } catch (e: Throwable) {
                sendError = e.screenMessage()
            }
        }
    }

    suspend fun createPoll(question: String, options: List<String>): String? = try {
        service.createPoll(question, options)
        load()
        null
    } catch (e: Throwable) {
        e.screenMessage()
    }

    /** Файл: ждём ответа сервера — «отправлено» раньше времени было бы неправдой. */
    suspend fun sendAttachment(bytes: ByteArray, fileName: String, mimeType: String, kind: String): String? {
        isSending = true
        sendError = null
        return try {
            val uploaded = service.upload(bytes, fileName, mimeType, kind)
            service.sendTeamMessage("", attachments = listOf(uploaded))
            load()
            null
        } catch (e: Throwable) {
            val text = e.screenMessage()
            sendError = text
            text
        } finally {
            isSending = false
        }
    }

    /** Отправка без ожидания: сообщение появляется сразу, на сервер летит фоном. */
    fun send(text: String, senderName: String, role: String?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val draft = TeamChatMessage(
            id = "pending-" + UUID.randomUUID(),
            senderName = senderName,
            senderRole = role,
            text = trimmed,
            createdAt = Instant.now().toString(),
            isPending = true,
        )
        pending = pending + draft
        sendError = null
        val replyId = replyTo?.id

        scope.launch {
            try {
                service.sendTeamMessage(trimmed, replyId)
                replyTo = null
                load()
                pending = pending.filter { it.id != draft.id }
                flushUndelivered()
            } catch (e: Throwable) {
                pending = pending.filter { it.id != draft.id }
                if (e is ApiError.Transport) {
                    // Связи нет: текст не теряем, в клубе интернет рвётся по несколько раз за смену.
                    undelivered = undelivered + trimmed
                    sendError = OFFLINE_TEXT
                } else {
                    // Фильтр мата отвечает 422 — текст возвращаем человеку, а не досылаем молча.
                    sendError = e.screenMessage() + "\n\n" + trimmed
                }
            }
        }
    }

    /** Дослать застрявшее по порядку — иначе разговор рассыпается. */
    suspend fun flushUndelivered() {
        if (undelivered.isEmpty()) return
        val queue = undelivered
        undelivered = emptyList()
        val rest = mutableListOf<String>()
        for (text in queue) {
            if (rest.isNotEmpty()) { rest += text; continue }
            try {
                service.sendTeamMessage(text)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                rest += text
            }
        }
        undelivered = rest
        if (rest.isEmpty()) {
            sendError = null
            load()
        }
    }

    fun dismissSendError() { sendError = null }
}

/** Состояние личных сообщений: список переписок и открытый разговор. */
internal class MessagesStore(private val service: ChatService, private val scope: CoroutineScope) {
    var threads by mutableStateOf(listOf<DirectThread>())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var conversation by mutableStateOf(listOf<DirectMessage>())
        private set
    var openedUserId by mutableStateOf<String?>(null)
        private set
    var isLoadingConversation by mutableStateOf(false)
        private set
    var conversationError by mutableStateOf<String?>(null)
        private set

    var isSending by mutableStateOf(false)
        private set
    var sendError by mutableStateOf<String?>(null)
        private set

    /** Кого я заблокировал: прекратить общение человек должен уметь сам. */
    var blocked by mutableStateOf(setOf<String>())
        private set

    /** Что не ушло из-за связи: кому и что. */
    var undelivered by mutableStateOf(listOf<Pair<String, String>>())
        private set

    suspend fun load() {
        if (threads.isEmpty()) service.cachedThreads()?.let { threads = it.usable }
        isLoading = threads.isEmpty()
        try {
            threads = service.threads().usable
            error = null
        } catch (e: Throwable) {
            error = e.screenMessage()
        } finally {
            isLoading = false
        }
    }

    suspend fun open(userId: String) {
        // Смена собеседника обязана обнулить ленту, иначе на миг мелькнёт чужая переписка.
        if (openedUserId != userId) {
            conversation = emptyList()
            sendError = null
        }
        openedUserId = userId
        if (conversation.isEmpty()) service.cachedConversation(userId)?.let { conversation = it.visible }
        isLoadingConversation = conversation.isEmpty()
        try {
            conversation = service.conversation(userId).visible
            conversationError = null
        } catch (e: Throwable) {
            conversationError = e.screenMessage()
        } finally {
            isLoadingConversation = false
        }
    }

    /** Тихое обновление открытой переписки — без «загружаем», чтобы не мигало. */
    suspend fun refreshConversation(userId: String) {
        if (openedUserId != userId || isSending) return
        runCatching { service.conversation(userId) }.onSuccess { if (openedUserId == userId) conversation = it.visible }
    }

    suspend fun loadBlocked() {
        blocked = runCatching { service.blockedUsers() }.getOrDefault(emptyList()).toSet()
    }

    suspend fun setBlocked(isBlocked: Boolean, userId: String) {
        try {
            service.setBlocked(isBlocked, userId)
            blocked = if (isBlocked) blocked + userId else blocked - userId
        } catch (e: Throwable) {
            sendError = e.screenMessage()
        }
    }

    fun report(messageId: String) {
        scope.launch { runCatching { service.report(messageId, "direct_messages") } }
    }

    suspend fun sendAttachment(bytes: ByteArray, fileName: String, mimeType: String, kind: String, userId: String): String? {
        isSending = true
        sendError = null
        return try {
            val uploaded = service.upload(bytes, fileName, mimeType, kind)
            service.sendDirect(userId, "", listOf(uploaded))
            isSending = false
            open(userId)
            load()
            null
        } catch (e: Throwable) {
            val text = e.screenMessage()
            sendError = text
            text
        } finally {
            isSending = false
        }
    }

    /** Письмо появляется сразу, на сервер уходит фоном. */
    fun send(text: String, userId: String, senderName: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val draft = DirectMessage(
            id = "pending-" + UUID.randomUUID(),
            recipientUserId = userId,
            senderName = senderName,
            text = trimmed,
            createdAt = Instant.now().toString(),
            isPending = true,
        )
        conversation = conversation + draft
        sendError = null

        scope.launch {
            try {
                service.sendDirect(userId, trimmed)
                open(userId)
                load()
                flushUndelivered()
            } catch (e: Throwable) {
                conversation = conversation.filter { it.id != draft.id }
                if (e is ApiError.Transport) {
                    undelivered = undelivered + (userId to trimmed)
                    sendError = OFFLINE_TEXT
                } else {
                    sendError = e.screenMessage() + "\n\n" + trimmed
                }
            }
        }
    }

    suspend fun flushUndelivered() {
        if (undelivered.isEmpty()) return
        val queue = undelivered
        undelivered = emptyList()
        val rest = mutableListOf<Pair<String, String>>()
        for (item in queue) {
            if (rest.isNotEmpty()) { rest += item; continue }
            try {
                service.sendDirect(item.first, item.second)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                rest += item
            }
        }
        undelivered = rest
        if (rest.isEmpty()) {
            sendError = null
            openedUserId?.let { open(it) }
            load()
        }
    }
}
