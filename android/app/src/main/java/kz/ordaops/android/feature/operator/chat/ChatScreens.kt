package kz.ordaops.android.feature.operator.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.LoadingView
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Живое обновление — опросом раз в 5 секунд, пока экран открыт.
 *
 * На айфоне есть SSE (`/api/realtime/messages`), но у Android-клиента токен
 * спрятан внутри ApiClient и отдельному потоку взять его негде; опрос даёт
 * тот же результат с задержкой в несколько секунд. Раздел уходит из
 * композиции при смене вкладки, так что в фоне ничего не стучится.
 */
private const val POLL_MS = 5_000L

/** Пять значков на все случаи: «принял», «сделано», «горит», «спасибо», «смешно». */
private val Emojis = listOf("👍", "✅", "🔥", "❤️", "😂")

// ── Командный чат ────────────────────────────────────────────────────────────

private sealed interface ChatRow {
    val key: String

    data class Note(override val key: String = "note") : ChatRow
    data class Pinned(val messages: List<TeamChatMessage>, override val key: String = "pinned") : ChatRow
    data class Empty(override val key: String = "empty") : ChatRow
    data class Day(val label: String, override val key: String) : ChatRow
    data class Message(val message: TeamChatMessage, val showsSender: Boolean, val gap: Boolean, override val key: String) : ChatRow
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTab() {
    val env = LocalOperatorEnv.current
    val scope = rememberCoroutineScope()
    val service = remember(env.api) { ChatService(env.api) }
    val store = remember(service) { TeamChatStore(service, scope) }

    var draft by remember { mutableStateOf("") }
    var acting by remember { mutableStateOf<TeamChatMessage?>(null) }
    var editing by remember { mutableStateOf<TeamChatMessage?>(null) }
    var person by remember { mutableStateOf<TeamChatMessage?>(null) }
    var showPoll by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }

    val canPin = env.resolver.can("team-chat.pin")
    val myName = env.resolver.session.displayName ?: "Вы"

    LaunchedEffect(store) { store.load() }
    LaunchedEffect(store) {
        while (true) {
            delay(POLL_MS)
            store.refresh()
            // Связь есть, раз лента обновилась, — самое время дослать застрявшее.
            store.flushUndelivered()
        }
    }

    fun isMine(m: TeamChatMessage): Boolean {
        if (m.isPending) return true
        val op = m.senderOperatorId
        return op != null && op == env.operatorId
    }

    Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
        // Шапка раздела: у оператора сверху нет системной панели с заголовком.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Командный чат", color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                searching = !searching
                if (!searching) store.updateSearch("")
            }) {
                Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Поиск", tint = Orda.colors.textMuted)
            }
        }
        if (searching) {
            OutlinedTextField(
                value = store.search,
                onValueChange = { store.updateSearch(it) },
                placeholder = { Text("Найти в переписке") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
            )
        }

        val feed = store.feed
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                feed == null && store.error != null -> ErrorWithRetry(store.error!!) { scope.launch { store.load() } }
                feed == null -> LoadingView("Загружаем переписку…")
                else -> TeamMessages(
                    store = store,
                    feed = feed,
                    isMine = ::isMine,
                    onOpenSender = { person = it },
                    onLongPress = { acting = it },
                )
            }
        }

        if (store.undelivered.isNotEmpty()) {
            val n = store.undelivered.size
            Text(
                "$n ${plural(n, "сообщение ждёт", "сообщения ждут", "сообщений ждут")} связи",
                color = Orda.colors.warning,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            )
        }

        store.replyTo?.let { reply ->
            ReplyBar(reply.senderName, ChatText.preview(reply.text, 70)) { store.replyTo = null }
        }

        ChatComposer(
            text = draft,
            onTextChange = { draft = it },
            placeholder = "Написать команде",
            isSending = store.isSending,
            errorText = store.sendError,
            onSend = {
                // Поле очищаем сразу: сообщение уже в переписке, на сервер летит фоном.
                val text = draft
                draft = ""
                store.send(text, myName, env.resolver.session.staffRole ?: "operator")
            },
            onPickFile = { bytes, name, mime, kind -> store.sendAttachment(bytes, name, mime, kind) },
            onPoll = { showPoll = true },
        )
    }

    acting?.let { message ->
        MessageActionsSheet(
            message = message,
            canPin = canPin,
            isMine = isMine(message),
            onDismiss = { acting = null },
            onReact = { store.react(message.id, it) },
            onReply = { store.replyTo = message },
            onPin = { store.pin(message.id, message.isPinned) },
            onEdit = { editing = message },
            onDelete = { store.delete(message.id) },
            onReport = { store.report(message.id) },
        )
    }

    editing?.let { message ->
        EditMessageDialog(
            initial = message.text,
            onDismiss = { editing = null },
            onSave = { text -> store.edit(message.id, text) },
        )
    }

    if (showPoll) {
        PollDialog(onDismiss = { showPoll = false }, onCreate = { q, o -> store.createPoll(q, o) })
    }

    person?.let { who ->
        PersonDialog(service = service, message = who, onDismiss = { person = null })
    }
}

@Composable
private fun TeamMessages(
    store: TeamChatStore,
    feed: TeamChatFeed,
    isMine: (TeamChatMessage) -> Boolean,
    onOpenSender: (TeamChatMessage) -> Unit,
    onLongPress: (TeamChatMessage) -> Unit,
) {
    val visible = store.visible
    val rows = remember(visible, feed.pinned) {
        buildList {
            add(ChatRow.Note())
            if (feed.pinned.isNotEmpty()) add(ChatRow.Pinned(feed.pinned))
            if (visible.isEmpty()) add(ChatRow.Empty())
            for ((day, list) in groupByDay(visible) { it.createdMs }) {
                add(ChatRow.Day(dayLabel(day), "day-$day"))
                list.forEachIndexed { i, m ->
                    val starts = startsGroup(list, i)
                    add(ChatRow.Message(m, starts, starts, m.id))
                }
            }
        }
    }

    val listState = rememberLazyListState()
    var pinnedOnce by remember { mutableStateOf(false) }
    val lastKey = visible.lastOrNull()?.id
    // Открывают чат ради последнего сообщения, а не первого.
    LaunchedEffect(lastKey) {
        if (lastKey == null || rows.isEmpty()) return@LaunchedEffect
        if (!pinnedOnce) {
            listState.scrollToItem(rows.lastIndex)
            pinnedOnce = true
        } else {
            listState.animateScrollToItem(rows.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is ChatRow.Note -> Text(
                    "Сообщения старше суток удаляются. Закреплённые и объявления остаются.",
                    color = Orda.colors.textDim,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xs),
                )
                is ChatRow.Pinned -> PinnedCard(row.messages)
                is ChatRow.Empty -> EmptyState(Icons.Filled.Chat, "Сообщений пока нет")
                is ChatRow.Day -> DayDivider(row.label)
                is ChatRow.Message -> {
                    val m = row.message
                    if (m.isAnnouncement) {
                        AnnouncementCard(m)
                    } else {
                        val replySource = m.replyToId?.let { feed.message(it) }
                        Box(Modifier.padding(top = if (row.gap) Spacing.sm else 0.dp)) {
                            ChatBubble(
                                text = m.displayText,
                                senderName = m.senderName,
                                roleLabel = m.roleLabel,
                                timeMs = m.createdMs,
                                isMine = isMine(m),
                                isEdited = m.isEdited,
                                isPending = m.isPending,
                                showsSender = row.showsSender,
                                showsAvatar = row.showsSender,
                                avatarUrl = m.senderAvatarUrl,
                                attachments = m.attachments,
                                replyPreview = replySource?.let { "${it.senderName}: ${ChatText.preview(it.text, 70)}" },
                                reactions = m.reactionGroups,
                                poll = feed.polls[m.id],
                                onOpenSender = if (m.senderUserId != null) ({ onOpenSender(m) }) else null,
                                onVote = feed.polls[m.id]?.let { poll -> { optionId: String -> store.vote(poll.poll.id, optionId) } },
                                onReact = if (m.isPending) null else ({ emoji: String -> store.react(m.id, emoji) }),
                                onLongPress = if (m.isPending) null else ({ onLongPress(m) }),
                            )
                        }
                    }
                }
            }
        }
    }
}

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

@Composable
private fun PinnedCard(pinned: List<TeamChatMessage>) {
    Column(
        Modifier.fillMaxWidth().background(Orda.colors.surface, RoundedCornerShape(Radius.md)).padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(Icons.Filled.PushPin, null, tint = Orda.colors.brand, modifier = Modifier.size(20.dp))
            Text("Закреплено", color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        pinned.forEach { m ->
            Column {
                Text(m.senderName, color = Orda.colors.textDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(m.displayText, color = Orda.colors.text, fontSize = 15.sp)
            }
        }
    }
}

/** Объявление — карточкой с рупором: выделено, но не кричит рамкой. */
@Composable
private fun AnnouncementCard(m: TeamChatMessage) {
    Column(
        Modifier.fillMaxWidth().background(Orda.colors.surface, RoundedCornerShape(Radius.md)).padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(Icons.Filled.Campaign, null, tint = Orda.colors.brand, modifier = Modifier.size(20.dp))
            Text(m.senderName, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(timeLabel(m.createdMs), color = Orda.colors.textDim, fontSize = 12.sp)
        }
        Text(m.displayText, color = Orda.colors.textMuted, fontSize = 15.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    message: TeamChatMessage,
    canPin: Boolean,
    isMine: Boolean,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    var confirmingReport by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Orda.colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Emojis.forEach { emoji ->
                    Box(
                        Modifier.size(52.dp).background(Orda.colors.surfaceRaised, CircleShape).clickable {
                            onReact(emoji)
                            onDismiss()
                        },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 26.sp) }
                }
            }
            // Само сообщение — чтобы не гадать, к какому относятся действия.
            Text(message.displayText, color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 2)

            Column(Modifier.fillMaxWidth().background(Orda.colors.surface, RoundedCornerShape(Radius.md))) {
                ActionRow("Ответить", Icons.Filled.Reply) { onReply(); onDismiss() }
                if (canPin) {
                    HorizontalDivider(color = Orda.colors.border)
                    ActionRow(if (message.isPinned) "Открепить" else "Закрепить на сутки", Icons.Filled.PushPin) { onPin(); onDismiss() }
                }
                if (isMine) {
                    HorizontalDivider(color = Orda.colors.border)
                    ActionRow("Изменить", Icons.Filled.Edit) { onEdit(); onDismiss() }
                    HorizontalDivider(color = Orda.colors.border)
                    ActionRow("Удалить", Icons.Filled.Delete, Orda.colors.negative) { confirmingDelete = true }
                } else {
                    HorizontalDivider(color = Orda.colors.border)
                    ActionRow("Пожаловаться", Icons.Filled.Flag, Orda.colors.warning) { confirmingReport = true }
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Удалить сообщение?") },
            text = { Text("У всех в чате оно исчезнет.") },
            confirmButton = { TextButton(onClick = { onDelete(); confirmingDelete = false; onDismiss() }) { Text("Удалить", color = Orda.colors.negative) } },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Отмена") } },
        )
    }
    if (confirmingReport) {
        AlertDialog(
            onDismissRequest = { confirmingReport = false },
            title = { Text("Пожаловаться на сообщение?") },
            text = { Text("Отправитель об этом не узнает, жалобу увидит владелец.") },
            confirmButton = { TextButton(onClick = { onReport(); confirmingReport = false; onDismiss() }) { Text("Пожаловаться", color = Orda.colors.negative) } },
            dismissButton = { TextButton(onClick = { confirmingReport = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ActionRow(title: String, icon: ImageVector, tint: androidx.compose.ui.graphics.Color = Orda.colors.text, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(title, color = tint, fontSize = 16.sp)
    }
}

@Composable
private fun EditMessageDialog(initial: String, onDismiss: () -> Unit, onSave: suspend (String) -> String?) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(ChatText.display(initial)) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить сообщение") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, maxLines = 6, modifier = Modifier.fillMaxWidth())
                error?.let { ErrorBanner(it) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && !saving,
                onClick = {
                    scope.launch {
                        saving = true
                        val failure = onSave(text.trim())
                        saving = false
                        if (failure == null) onDismiss() else error = failure
                    }
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun PollDialog(onDismiss: () -> Unit, onCreate: suspend (String, List<String>) -> String?) {
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(listOf("", "")) }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val filled = options.map { it.trim() }.filter { it.isNotEmpty() }
    val canSend = question.isNotBlank() && filled.size >= 2

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Опрос") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = question, onValueChange = { question = it },
                    label = { Text("Вопрос") }, placeholder = { Text("Кто выходит в субботу?") },
                    modifier = Modifier.fillMaxWidth(),
                )
                options.forEachIndexed { i, value ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { v -> options = options.toMutableList().also { it[i] = v } },
                            label = { Text("Вариант ${i + 1}") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        if (options.size > 2) {
                            IconButton(onClick = { options = options.toMutableList().also { it.removeAt(i) } }) {
                                Icon(Icons.Filled.Close, "Убрать вариант", tint = Orda.colors.negative)
                            }
                        }
                    }
                }
                // Десять — предел сервера: больше вариантов в чате всё равно не читают.
                if (options.size < 10) TextButton(onClick = { options = options + "" }) { Text("Добавить вариант") }
                error?.let { ErrorBanner(it) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSend && !sending,
                onClick = {
                    scope.launch {
                        sending = true
                        val failure = onCreate(question.trim(), filled)
                        sending = false
                        if (failure == null) onDismiss() else error = failure
                    }
                },
            ) { Text(if (sending) "Отправляем…" else "Создать опрос") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Карточка коллеги: где работает, сколько и на смене ли. Телефон сервер не отдаёт. */
@Composable
private fun PersonDialog(service: ChatService, message: TeamChatMessage, onDismiss: () -> Unit) {
    var card by remember { mutableStateOf<ChatPersonCard?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(message.senderUserId) {
        val id = message.senderUserId ?: return@LaunchedEffect
        runCatching { service.chatPerson(id) }.onSuccess { card = it; failed = it == null }.onFailure { failed = true }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card?.name ?: message.senderName) },
        text = {
            val c = card
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                (c?.position ?: message.roleLabel)?.let { Text(it, color = Orda.colors.textMuted) }
                when {
                    c != null -> {
                        if (c.companies.isNotEmpty()) Text("Точки: " + c.companies.joinToString(", "))
                        tenure(c.hireDate)?.let { Text("Работает: $it") }
                        Text(
                            if (c.onShift) "Сейчас на смене" + (c.shiftCompany?.let { " · $it" } ?: "") else "Не на смене",
                            color = if (c.onShift) Orda.colors.positive else Orda.colors.textDim,
                        )
                    }
                    failed -> Text("Не удалось загрузить карточку.", color = Orda.colors.textDim)
                    else -> Text("Загружаем…", color = Orda.colors.textDim)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

private fun tenure(hireDate: String?): String? {
    val date = runCatching { LocalDate.parse(hireDate?.take(10)) }.getOrNull() ?: return null
    val days = ChronoUnit.DAYS.between(date, LocalDate.now())
    if (days < 0) return null
    if (days < 31) return "$days дн."
    val months = days / 30
    if (months < 12) return "$months мес."
    val years = months / 12
    val rest = months % 12
    return if (rest == 0L) "$years г." else "$years г. $rest мес."
}

// ── Личные сообщения ─────────────────────────────────────────────────────────

@Composable
fun MessagesTab() {
    val env = LocalOperatorEnv.current
    val scope = rememberCoroutineScope()
    val service = remember(env.api) { ChatService(env.api) }
    val store = remember(service) { MessagesStore(service, scope) }

    var selected by remember { mutableStateOf<DirectThread?>(null) }
    var choosing by remember { mutableStateOf(false) }

    LaunchedEffect(store) { store.load() }
    // Список переписок живёт: непрочитанные появляются, пока человек на экране.
    LaunchedEffect(store, selected) {
        while (selected == null) {
            delay(POLL_MS * 2)
            store.load()
        }
    }

    val current = selected
    if (current != null) {
        BackHandler { selected = null; scope.launch { store.load() } }
        ConversationPane(
            thread = current,
            store = store,
            onBack = { selected = null; scope.launch { store.load() } },
        )
    } else {
        Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Сообщения", color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { choosing = true }) {
                    Icon(Icons.Filled.Edit, "Новое сообщение", tint = Orda.colors.brand)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    store.error != null && store.threads.isEmpty() -> ErrorWithRetry(store.error!!) { scope.launch { store.load() } }
                    store.isLoading && store.threads.isEmpty() -> LoadingView()
                    store.threads.isEmpty() -> EmptyState(Icons.Filled.Chat, "Переписок нет", "Нажмите карандаш, чтобы написать первым.")
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        items(store.threads, key = { it.otherUserId }) { thread ->
                            ThreadRow(thread) { selected = thread }
                        }
                    }
                }
            }
        }
    }

    if (choosing) {
        ContactPickerDialog(
            service = service,
            onDismiss = { choosing = false },
            onPick = { contact ->
                choosing = false
                scope.launch {
                    // Переписки может ещё не быть: открываем сразу, чтобы человек попал в разговор.
                    store.open(contact.userId)
                    store.load()
                    selected = store.threads.firstOrNull { it.otherUserId == contact.userId }
                        ?: DirectThread(otherUserId = contact.userId, otherName = contact.name)
                }
            },
        )
    }
}

@Composable
private fun ThreadRow(thread: DirectThread, onClick: () -> Unit) {
    val colors = Orda.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Avatar(thread.otherName, null, 40, if (thread.hasUnread) colors.brand else colors.info)
        Column(Modifier.weight(1f)) {
            Text(
                thread.otherName,
                color = colors.text,
                fontSize = 16.sp,
                fontWeight = if (thread.hasUnread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
            Text((if (thread.lastFromMe) "Вы: " else "") + thread.preview, color = colors.textDim, fontSize = 13.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            val last = parseTimeMs(thread.lastAt)
            if (last != null) Text(shortDate(last), color = colors.textDim, fontSize = 12.sp)
            if (thread.unreadCount > 0) {
                Text(
                    "${thread.unreadCount}",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(colors.brand, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Выбор собеседника. Список ограничен своей организацией — чужой сюда не
 * попадёт, как и не пройдёт проверку при отправке.
 */
@Composable
private fun ContactPickerDialog(service: ChatService, onDismiss: () -> Unit, onPick: (DirectContact) -> Unit) {
    var contacts by remember { mutableStateOf<List<DirectContact>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        error = null
        try {
            contacts = service.contacts()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.userMessage()
        }
    }

    val filtered = contacts.orEmpty().filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Кому написать") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(value = search, onValueChange = { search = it }, placeholder = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                when {
                    error != null && contacts == null -> {
                        ErrorBanner(error!!)
                        TextButton(onClick = { reload++ }) { Text("Повторить") }
                    }
                    contacts == null -> Text("Загружаем…", color = Orda.colors.textDim)
                    filtered.isEmpty() -> Text(
                        if (search.isBlank()) "В организации пока нет других людей с учётной записью." else "Никого не нашлось.",
                        color = Orda.colors.textDim,
                    )
                    else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(filtered, key = { it.userId }) { c ->
                            Column(Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = Spacing.sm)) {
                                Text(c.name, color = Orda.colors.text, fontSize = 16.sp)
                                c.roleLabel?.let { Text(it, color = Orda.colors.textDim, fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ConversationPane(thread: DirectThread, store: MessagesStore, onBack: () -> Unit) {
    val colors = Orda.colors
    val scope = rememberCoroutineScope()
    val userId = thread.otherUserId
    var draft by remember(userId) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<DirectMessage?>(null) }
    var confirmBlock by remember { mutableStateOf(false) }
    val env = LocalOperatorEnv.current

    LaunchedEffect(userId) {
        store.open(userId)
        store.loadBlocked()
    }
    LaunchedEffect(userId) {
        while (true) {
            delay(POLL_MS)
            store.refreshConversation(userId)
            store.flushUndelivered()
        }
    }

    val isBlocked = userId in store.blocked
    // Пустой список сам по себе ничего не значит: тем же он выглядит до первого ответа сервера.
    val isReady = store.openedUserId == userId && !store.isLoadingConversation

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = colors.text) }
            Avatar(thread.otherName, null, 34)
            Text(
                thread.otherName,
                color = colors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm),
            )
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Ещё", tint = colors.textMuted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (isBlocked) "Снять блокировку" else "Заблокировать") },
                        leadingIcon = { Icon(Icons.Filled.Block, null) },
                        onClick = {
                            menu = false
                            if (isBlocked) scope.launch { store.setBlocked(false, userId) } else confirmBlock = true
                        },
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val conversation = store.conversation
            when {
                store.conversationError != null && conversation.isEmpty() ->
                    ErrorWithRetry(store.conversationError!!) { scope.launch { store.open(userId) } }
                conversation.isEmpty() && !isReady -> LoadingView()
                conversation.isEmpty() -> EmptyState(Icons.Filled.Chat, "Пока пусто", "Напишите ${thread.otherName} первым.")
                else -> DirectMessages(conversation, userId, onLongPress = { reporting = it })
            }
        }

        if (store.undelivered.isNotEmpty()) {
            Text(
                "Сообщение ждёт связи",
                color = colors.warning,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            )
        }

        if (isBlocked) {
            // Пока заблокирован — писать некуда: сервер такое сообщение отвергнет.
            Text(
                "Вы заблокировали этого человека. Снимите блокировку, чтобы написать.",
                color = colors.textMuted,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(Spacing.lg),
            )
        } else {
            ChatComposer(
                text = draft,
                onTextChange = { draft = it },
                placeholder = "Сообщение для ${thread.otherName}",
                isSending = store.isSending,
                errorText = store.sendError,
                onSend = {
                    val text = draft
                    draft = ""
                    store.send(text, userId, env.resolver.session.displayName ?: "Вы")
                },
                // Опрос вдвоём смысла не имеет, а фото и файл нужны и здесь.
                onPickFile = { bytes, name, mime, kind -> store.sendAttachment(bytes, name, mime, kind, userId) },
            )
        }
    }

    reporting?.let { message ->
        AlertDialog(
            onDismissRequest = { reporting = null },
            title = { Text("Пожаловаться на сообщение?") },
            text = { Text("Жалобу увидит владелец. Собеседник об этом не узнает.") },
            confirmButton = {
                TextButton(onClick = { store.report(message.id); reporting = null }) { Text("Пожаловаться", color = colors.negative) }
            },
            dismissButton = { TextButton(onClick = { reporting = null }) { Text("Отмена") } },
        )
    }
    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text("Заблокировать ${thread.otherName}?") },
            text = { Text("Писать друг другу вы не сможете, пока не снимете блокировку.") },
            confirmButton = {
                TextButton(onClick = { confirmBlock = false; scope.launch { store.setBlocked(true, userId) } }) {
                    Text("Заблокировать", color = colors.negative)
                }
            },
            dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun DirectMessages(messages: List<DirectMessage>, userId: String, onLongPress: (DirectMessage) -> Unit) {
    val groups = remember(messages) { groupByDay(messages) { it.createdMs } }
    val count = groups.sumOf { it.second.size + 1 }
    val listState = rememberLazyListState()
    var pinnedOnce by remember(userId) { mutableStateOf(false) }
    val lastId = messages.lastOrNull()?.id

    LaunchedEffect(lastId) {
        if (count == 0) return@LaunchedEffect
        if (!pinnedOnce) {
            listState.scrollToItem(count - 1)
            pinnedOnce = true
        } else {
            listState.animateScrollToItem(count - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        groups.forEach { (day, list) ->
            item(key = "day-$day") { DayDivider(dayLabel(day)) }
            items(list, key = { it.id }) { m ->
                val mine = m.isMine(userId)
                ChatBubble(
                    text = m.displayText,
                    senderName = m.senderName,
                    roleLabel = null,
                    timeMs = m.createdMs,
                    isMine = mine,
                    isEdited = m.isEdited,
                    isPending = m.isPending,
                    showsSender = false,
                    showsAvatar = false,
                    avatarUrl = null,
                    attachments = m.attachments,
                    // Пожаловаться можно и здесь: травля чаще случается один на один.
                    onLongPress = if (!mine && !m.isPending) ({ onLongPress(m) }) else null,
                )
            }
        }
    }
}

// ── Общее ────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorWithRetry(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Spacing.lg), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        ErrorBanner(message)
        PrimaryButton("Повторить", onClick = onRetry)
    }
}

/** 1 файл / 2 файла / 5 файлов. */
private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}
