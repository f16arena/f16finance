package kz.ordaops.android.feature.operator.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kz.ordaops.android.core.net.ApiError
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load
import java.time.Instant
import java.util.UUID

private const val OFFLINE_TEXT = "Нет связи. Сообщение отправится само, когда появится интернет."

/** Ошибка для экрана; отмену корутины пробрасываем, чтобы не глотать её. */
private fun Throwable.screenMessage(): String {
    if (this is CancellationException) throw this
    return userMessage()
}

/** Тихая попытка: при сбое null, отмену пробрасываем. */
private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    null
}

// ── Строки ленты ─────────────────────────────────────────────────────────────
// Список строк собираем вне главного потока и отдаём готовым: экран только рисует.
// Порядок «новое первым» — лента перевёрнута (reverseLayout), низ экрана = индекс 0.

@Immutable
internal sealed interface ChatRow {
    val key: String

    /** Для LazyColumn: разные типы строк не делят общий пул разметки. */
    val contentType: String

    data class Note(override val key: String = "note") : ChatRow {
        override val contentType get() = "note"
    }

    data class Pinned(val messages: List<TeamChatMessage>, override val key: String = "pinned") : ChatRow {
        override val contentType get() = "pinned"
    }

    data class Empty(override val key: String = "empty") : ChatRow {
        override val contentType get() = "empty"
    }

    data class Day(val label: String, override val key: String) : ChatRow {
        override val contentType get() = "day"
    }

    data class Message(
        val message: TeamChatMessage,
        val showsSender: Boolean,
        val gap: Boolean,
        val isMine: Boolean,
        val replyPreview: String?,
        val poll: ChatPoll?,
        override val key: String,
    ) : ChatRow {
        override val contentType get() = if (message.isAnnouncement) "announcement" else "message"
    }
}

@Immutable
internal data class TeamChatContent(
    val rows: List<ChatRow>,
    /** Ключ самого нового сообщения: по его смене лента понимает, что пришло новое. */
    val newestKey: String?,
    val newestIsMine: Boolean,
)

/**
 * Начинается ли новая реплика. Человек пишет очередями — имя над каждой строкой
 * превращает ленту в перечень карточек; после паузы в 5 минут это уже новая реплика.
 */
private fun startsGroup(items: List<TeamChatMessage>, index: Int): Boolean {
    if (index == 0) return true
    val current = items[index]
    val previous = items[index - 1]
    if (previous.isAnnouncement) return true
    if (previous.senderName != current.senderName) return true
    val left = previous.createdMs ?: return true
    val right = current.createdMs ?: return true
    return right - left > 5 * 60 * 1000
}

private fun buildTeamContent(feed: TeamChatFeed, pending: List<TeamChatMessage>, operatorId: String?): TeamChatContent {
    fun mine(m: TeamChatMessage) = m.isPending || (operatorId != null && m.senderOperatorId == operatorId)
    val visible = feed.visible + pending
    var newest: ChatRow.Message? = null
    val chronological = buildList<ChatRow> {
        add(ChatRow.Note())
        if (feed.pinned.isNotEmpty()) add(ChatRow.Pinned(feed.pinned))
        if (visible.isEmpty()) add(ChatRow.Empty())
        for ((day, list) in groupByDay(visible) { it.createdMs }) {
            add(ChatRow.Day(dayLabel(day), "day-$day"))
            list.forEachIndexed { i, m ->
                val starts = startsGroup(list, i)
                val reply = m.replyToId?.let { feed.message(it) }
                val row = ChatRow.Message(
                    message = m,
                    showsSender = starts,
                    gap = starts,
                    isMine = mine(m),
                    replyPreview = reply?.let { "${it.senderName}: ${ChatText.preview(it.text, 70)}" },
                    poll = feed.polls[m.id],
                    key = m.id,
                )
                newest = row
                add(row)
            }
        }
    }
    return TeamChatContent(chronological.reversed(), newest?.key, newest?.isMine == true)
}

private fun <T> Loadable<*>.withData(data: T?): Loadable<T> =
    Loadable(data = data, isLoading = isLoading, isRefreshing = isRefreshing, error = error, isOffline = isOffline)

// ── Командный чат ────────────────────────────────────────────────────────────

/** Состояние командного чата. Живёт с Activity: поворот и смена вкладки его не сбрасывают. */
internal class TeamChatViewModel(private val service: ChatService, operatorId: String?) : ViewModel() {
    private val feed = MutableStateFlow(Loadable<TeamChatFeed>())

    /** Отправленные, но ещё не подтверждённые сервером. */
    private val pending = MutableStateFlow(listOf<TeamChatMessage>())

    val content: StateFlow<Loadable<TeamChatContent>> = combine(feed, pending) { f, p -> f to p }
        .map { (f, p) -> f.withData(f.data?.let { buildTeamContent(it, p, operatorId) }) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Loadable(isLoading = true))

    /** Черновик здесь, а не в экране: переживает поворот и уход со вкладки. */
    var draft by mutableStateOf("")

    var isSending by mutableStateOf(false)
        private set
    var sendError by mutableStateOf<String?>(null)
        private set

    /** Что не ушло из-за связи: ждёт своей очереди, а не теряется. */
    var undelivered by mutableStateOf(listOf<String>())
        private set

    var replyTo by mutableStateOf<TeamChatMessage?>(null)
    var search by mutableStateOf("")
        private set

    private var searchJob: Job? = null

    /** Явная загрузка: прошлая лента — сразу, дальше поверх неё. Поиск не кэшируем. */
    suspend fun load() {
        val query = search
        feed.load(
            cached = { if (query.isEmpty()) service.cachedTeamChat() else null },
            fresh = { service.teamChat(query) },
        )
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    /** Тихое обновление раз в несколько секунд: без индикаторов и без сброса прочитанного. */
    suspend fun refresh() {
        if (isSending) return
        fetch()
    }

    private suspend fun fetch() {
        val query = search
        val fresh = attempt { service.teamChat(query) } ?: return
        // Пока шёл запрос, человек мог ввести другой поиск — старый ответ не нужен.
        if (query == search) feed.value = Loadable(data = fresh)
    }

    /** Поиск идёт на сервере — по тексту и по имени отправителя; с паузой, а не на каждую букву. */
    fun updateSearch(value: String) {
        if (value == search) return
        search = value
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            load()
        }
    }

    fun react(messageId: String, emoji: String) {
        viewModelScope.launch {
            attempt { service.react(messageId, emoji) }
            fetch()
        }
    }

    suspend fun edit(messageId: String, text: String): String? = try {
        service.editTeamMessage(messageId, text)
        fetch()
        null
    } catch (e: Throwable) {
        e.screenMessage()
    }

    /** Жалоба молча: отправитель не узнает, владелец увидит в модерации. */
    fun report(messageId: String) {
        viewModelScope.launch { attempt { service.report(messageId, "team_chat") } }
    }

    fun delete(messageId: String) {
        viewModelScope.launch {
            attempt { service.deleteTeamMessage(messageId) }
            fetch()
        }
    }

    /** Закрепление на сутки — обычный срок объявления по смене. */
    fun pin(messageId: String, isPinned: Boolean) {
        viewModelScope.launch {
            attempt {
                if (isPinned) service.unpinTeamMessage(messageId)
                else service.pinTeamMessage(messageId, System.currentTimeMillis() + 24 * 3600 * 1000L)
            }
            fetch()
        }
    }

    fun vote(pollId: String, optionId: String) {
        viewModelScope.launch {
            try {
                service.vote(pollId, optionId)
                fetch()
            } catch (e: Throwable) {
                sendError = e.screenMessage()
            }
        }
    }

    suspend fun createPoll(question: String, options: List<String>): String? = try {
        service.createPoll(question, options)
        fetch()
        null
    } catch (e: Throwable) {
        e.screenMessage()
    }

    suspend fun person(userId: String): ChatPersonCard? = service.chatPerson(userId)

    /** Файл: ждём ответа сервера — «отправлено» раньше времени было бы неправдой. */
    suspend fun sendAttachment(bytes: ByteArray, fileName: String, mimeType: String, kind: String): String? {
        isSending = true
        sendError = null
        return try {
            val uploaded = service.upload(bytes, fileName, mimeType, kind)
            service.sendTeamMessage("", attachments = listOf(uploaded))
            fetch()
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
        val local = TeamChatMessage(
            id = "pending-" + UUID.randomUUID(),
            senderName = senderName,
            senderRole = role,
            text = trimmed,
            createdAt = Instant.now().toString(),
            isPending = true,
        )
        pending.update { it + local }
        sendError = null
        val replyId = replyTo?.id

        viewModelScope.launch {
            try {
                service.sendTeamMessage(trimmed, replyId)
                replyTo = null
                // Сначала свежая лента, потом убираем «летящее»: иначе сообщение моргнёт.
                fetch()
                pending.update { list -> list.filter { it.id != local.id } }
                flushUndelivered()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                pending.update { list -> list.filter { it.id != local.id } }
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
            fetch()
        }
    }
}

// ── Личные сообщения ─────────────────────────────────────────────────────────

@Immutable
internal sealed interface DirectRow {
    val key: String
    val contentType: String

    data class Day(val label: String, override val key: String) : DirectRow {
        override val contentType get() = "day"
    }

    data class Message(val message: DirectMessage, val isMine: Boolean, override val key: String) : DirectRow {
        override val contentType get() = "message"
    }
}

@Immutable
internal data class DirectContent(
    val rows: List<DirectRow>,
    val newestKey: String?,
    val newestIsMine: Boolean,
)

private fun buildDirectContent(messages: List<DirectMessage>, userId: String?): DirectContent {
    var newest: DirectRow.Message? = null
    val chronological = buildList<DirectRow> {
        for ((day, list) in groupByDay(messages) { it.createdMs }) {
            add(DirectRow.Day(dayLabel(day), "day-$day"))
            for (m in list) {
                val row = DirectRow.Message(m, userId != null && m.isMine(userId), m.id)
                newest = row
                add(row)
            }
        }
    }
    return DirectContent(chronological.reversed(), newest?.key, newest?.isMine == true)
}

/** Состояние личных сообщений: список переписок и открытый разговор. */
internal class MessagesViewModel(private val service: ChatService) : ViewModel() {
    val threads = MutableStateFlow(Loadable<List<DirectThread>>())

    private val conversation = MutableStateFlow(Loadable<List<DirectMessage>>())
    private val pending = MutableStateFlow(listOf<DirectMessage>())
    val opened = MutableStateFlow<String?>(null)

    val conversationContent: StateFlow<Loadable<DirectContent>> =
        combine(conversation, pending, opened) { c, p, user -> Triple(c, p, user) }
            .map { (c, p, user) ->
                c.withData(
                    c.data?.let { buildDirectContent(it + p.filter { m -> m.recipientUserId == user }, user) },
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, Loadable(isLoading = true))

    val openedUserId: String? get() = opened.value

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

    /** Черновики по собеседникам: переключились на другого — набранное не пропало. */
    private val drafts = mutableStateMapOf<String, String>()
    fun draftOf(userId: String): String = drafts[userId].orEmpty()
    fun setDraft(userId: String, text: String) { drafts[userId] = text }

    /** Имена собеседников, которых ещё нет в списке переписок (новый разговор). */
    private val knownNames = mutableStateMapOf<String, String>()
    fun rememberName(userId: String, name: String) { knownNames[userId] = name }
    fun nameOf(userId: String): String =
        threads.value.data?.firstOrNull { it.otherUserId == userId }?.otherName ?: knownNames[userId] ?: "Собеседник"

    suspend fun loadThreads() {
        threads.load(
            cached = { service.cachedThreads()?.usable },
            fresh = { service.threads().usable },
        )
    }

    fun retryThreads() {
        viewModelScope.launch { loadThreads() }
    }

    suspend fun refreshThreads() {
        val fresh = attempt { service.threads().usable } ?: return
        threads.value = Loadable(data = fresh)
    }

    /** Открыть разговор: прошлая переписка — сразу, свежая — поверх. */
    suspend fun open(userId: String) {
        // Смена собеседника обязана обнулить ленту, иначе на миг мелькнёт чужая переписка.
        if (opened.value != userId) {
            conversation.value = Loadable()
            sendError = null
            opened.value = userId
        }
        conversation.load(
            cached = { service.cachedConversation(userId)?.visible },
            fresh = { service.conversation(userId).visible },
        )
    }

    fun retryConversation(userId: String) {
        viewModelScope.launch { open(userId) }
    }

    /** Тихое обновление открытой переписки — без «загружаем», чтобы не мигало. */
    suspend fun refreshConversation(userId: String) {
        if (opened.value != userId || isSending) return
        fetchConversation(userId)
    }

    private suspend fun fetchConversation(userId: String) {
        val fresh = attempt { service.conversation(userId).visible } ?: return
        if (opened.value == userId) conversation.value = Loadable(data = fresh)
    }

    suspend fun loadBlocked() {
        blocked = attempt { service.blockedUsers() }.orEmpty().toSet()
    }

    suspend fun setBlocked(isBlocked: Boolean, userId: String) {
        try {
            service.setBlocked(isBlocked, userId)
            blocked = if (isBlocked) blocked + userId else blocked - userId
        } catch (e: Throwable) {
            sendError = e.screenMessage()
        }
    }

    suspend fun contacts(): List<DirectContact> = service.contacts()

    fun report(messageId: String) {
        viewModelScope.launch { attempt { service.report(messageId, "direct_messages") } }
    }

    suspend fun sendAttachment(bytes: ByteArray, fileName: String, mimeType: String, kind: String, userId: String): String? {
        isSending = true
        sendError = null
        return try {
            val uploaded = service.upload(bytes, fileName, mimeType, kind)
            service.sendDirect(userId, "", listOf(uploaded))
            isSending = false
            fetchConversation(userId)
            refreshThreads()
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
        val local = DirectMessage(
            id = "pending-" + UUID.randomUUID(),
            recipientUserId = userId,
            senderName = senderName,
            text = trimmed,
            createdAt = Instant.now().toString(),
            isPending = true,
        )
        pending.update { it + local }
        sendError = null

        viewModelScope.launch {
            try {
                service.sendDirect(userId, trimmed)
                fetchConversation(userId)
                pending.update { list -> list.filter { it.id != local.id } }
                refreshThreads()
                flushUndelivered()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                pending.update { list -> list.filter { it.id != local.id } }
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
            opened.value?.let { fetchConversation(it) }
            refreshThreads()
        }
    }
}
