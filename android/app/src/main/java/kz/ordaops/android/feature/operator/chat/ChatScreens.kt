package kz.ordaops.android.feature.operator.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.feature.operator.common.operatorViewModel
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Живое обновление — опросом раз в 5 секунд, но только пока экран на виду
 * (STARTED): свернули приложение — запросы прекращаются, вернулись — сразу
 * обновляемся.
 *
 * На айфоне есть SSE (`/api/realtime/messages`), но у Android-клиента токен
 * спрятан внутри ApiClient и отдельному потоку взять его негде; опрос даёт
 * тот же результат с задержкой в несколько секунд.
 */
private const val POLL_MS = 5_000L

/** Читать переписку удобно в колонке: на планшете и в окне лента не растягивается на весь экран. */
private val MaxChatWidth = 840.dp

/** Пять значков на все случаи: «принял», «сделано», «горит», «спасибо», «смешно». */
private val Emojis = listOf("👍", "✅", "🔥", "❤️", "😂")

/**
 * Выполняет [tick] каждые [intervalMs], пока экран виден. Первый запуск пропускаем —
 * его делает начальная загрузка; после возврата из фона обновляемся сразу.
 */
@Composable
private fun PollWhileStarted(intervalMs: Long, tick: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(tick)
    LaunchedEffect(owner) {
        var skip = true
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (!skip) current()
                skip = false
                delay(intervalMs)
            }
        }
    }
}

/**
 * Лента перевёрнута (низ экрана = индекс 0), поэтому позиция внизу не «уезжает» ни при
 * приходе сообщений, ни при открытии клавиатуры. Прокручиваем к новому, только если
 * человек и так был внизу или написал его сам; читающему историю ничего не двигаем.
 */
@Composable
private fun FollowNewest(listState: LazyListState, newestKey: String?, newestIsMine: Boolean) {
    val atBottom by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
    }
    // Запоминаем «внизу ли» в миг смены ключа — до того, как список перестроится под новое сообщение.
    val wasAtBottom = remember(newestKey) { atBottom }
    var firstSeen by remember { mutableStateOf(true) }
    LaunchedEffect(newestKey) {
        if (newestKey == null) return@LaunchedEffect
        if (firstSeen) {
            // Лента и так открывается снизу.
            firstSeen = false
            return@LaunchedEffect
        }
        if (wasAtBottom || newestIsMine) listState.animateScrollToItem(0)
    }
}

// ── Командный чат ────────────────────────────────────────────────────────────

@Composable
fun ChatTab() {
    val env = LocalOperatorEnv.current
    // Учётка входа нужна сотруднику: у него нет operatorId, и свои сообщения узнаются по sender_user_id.
    val vm = operatorViewModel(key = LocalOperatorEnv.current.api.currentUserId) { e -> TeamChatViewModel(ChatService(e.api), e.operatorId, e.api.currentUserId) }
    val state by vm.content.collectAsStateWithLifecycle()

    var actingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var person by remember { mutableStateOf<TeamChatMessage?>(null) }
    var showPoll by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(vm.search.isNotEmpty()) }

    val canPin = env.resolver.can("team-chat.pin")
    val myName = env.resolver.session.displayName ?: "Вы"

    LaunchedEffect(vm) { vm.load() }
    PollWhileStarted(POLL_MS) {
        vm.refresh()
        // Связь есть, раз лента обновилась, — самое время дослать застрявшее.
        vm.flushUndelivered()
    }

    fun findMessage(id: String?): TeamChatMessage? {
        if (id == null) return null
        return state.data?.rows?.firstNotNullOfOrNull { row -> (row as? ChatRow.Message)?.message?.takeIf { it.id == id } }
    }

    Box(Modifier.fillMaxSize().background(Orda.colors.background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = MaxChatWidth).fillMaxSize()) {
            // Шапка раздела: у оператора сверху нет системной панели с заголовком.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Командный чат", color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    searching = !searching
                    if (!searching) vm.updateSearch("")
                }) {
                    Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Поиск", tint = Orda.colors.textMuted)
                }
            }
            if (searching) {
                OutlinedTextField(
                    value = vm.search,
                    onValueChange = { vm.updateSearch(it) },
                    placeholder = { Text("Найти в переписке") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Потянуть-обновить здесь не нужно: лента перевёрнута, а новое и так приходит опросом.
                StateContainer(
                    state = state,
                    onRetry = vm::retry,
                    onRefresh = null,
                    loadingMessage = "Загружаем переписку…",
                ) { content ->
                    TeamMessages(
                        content = content,
                        onOpenSender = { person = it },
                        onLongPress = { actingId = it.id },
                        onVote = vm::vote,
                        onReact = vm::react,
                    )
                }
            }

            if (vm.undelivered.isNotEmpty()) {
                val n = vm.undelivered.size
                Text(
                    "$n ${plural(n, "сообщение ждёт", "сообщения ждут", "сообщений ждут")} связи",
                    color = Orda.colors.warning,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }

            vm.replyTo?.let { reply ->
                ReplyBar(reply.senderName, ChatText.preview(reply.text, 70)) { vm.replyTo = null }
            }

            ChatComposer(
                text = vm.draft,
                onTextChange = { vm.draft = it },
                placeholder = "Написать команде",
                isSending = vm.isSending,
                errorText = vm.sendError,
                onSend = {
                    // Поле очищаем сразу: сообщение уже в переписке, на сервер летит фоном.
                    val text = vm.draft
                    vm.draft = ""
                    vm.send(text, myName, env.resolver.session.staffRole ?: "operator")
                },
                onPickFile = { bytes, name, mime, kind -> vm.sendAttachment(bytes, name, mime, kind) },
                onPoll = { showPoll = true },
            )
        }
    }

    findMessage(actingId)?.let { message ->
        MessageActionsSheet(
            message = message,
            canPin = canPin,
            isMine = isOwnTeamMessage(message, env.operatorId, env.api.currentUserId),
            onDismiss = { actingId = null },
            onReact = { vm.react(message.id, it) },
            onReply = { vm.replyTo = message },
            onPin = { vm.pin(message.id, message.isPinned) },
            onEdit = { editingId = message.id },
            onDelete = { vm.delete(message.id) },
            onReport = { vm.report(message.id) },
        )
    }

    findMessage(editingId)?.let { message ->
        EditMessageDialog(
            initial = message.text,
            onDismiss = { editingId = null },
            onSave = { text -> vm.edit(message.id, text) },
        )
    }

    if (showPoll) {
        PollDialog(onDismiss = { showPoll = false }, onCreate = { q, o -> vm.createPoll(q, o) })
    }

    person?.let { who ->
        PersonDialog(loader = vm::person, message = who, onDismiss = { person = null })
    }
}

@Composable
private fun TeamMessages(
    content: TeamChatContent,
    onOpenSender: (TeamChatMessage) -> Unit,
    onLongPress: (TeamChatMessage) -> Unit,
    onVote: (pollId: String, optionId: String) -> Unit,
    onReact: (messageId: String, emoji: String) -> Unit,
) {
    val listState = rememberLazyListState()
    FollowNewest(listState, content.newestKey, content.newestIsMine)

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        items(content.rows, key = { it.key }, contentType = { it.contentType }) { row ->
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
                is ChatRow.Message -> TeamMessageItem(row, onOpenSender, onLongPress, onVote, onReact)
            }
        }
    }
}

@Composable
private fun TeamMessageItem(
    row: ChatRow.Message,
    onOpenSender: (TeamChatMessage) -> Unit,
    onLongPress: (TeamChatMessage) -> Unit,
    onVote: (String, String) -> Unit,
    onReact: (String, String) -> Unit,
) {
    val m = row.message
    if (m.isAnnouncement) {
        AnnouncementCard(m)
        return
    }
    Box(Modifier.padding(top = if (row.gap) Spacing.sm else 0.dp)) {
        ChatBubble(
            text = m.displayText,
            senderName = m.senderName,
            roleLabel = m.roleLabel,
            timeMs = m.createdMs,
            isMine = row.isMine,
            isEdited = m.isEdited,
            isPending = m.isPending,
            showsSender = row.showsSender,
            showsAvatar = row.showsSender,
            avatarUrl = m.senderAvatarUrl,
            attachments = m.attachments,
            replyPreview = row.replyPreview,
            reactions = m.reactionGroups,
            poll = row.poll,
            onOpenSender = if (m.senderUserId != null) ({ onOpenSender(m) }) else null,
            onVote = row.poll?.let { poll -> { optionId: String -> onVote(poll.poll.id, optionId) } },
            onReact = if (m.isPending) null else ({ emoji: String -> onReact(m.id, emoji) }),
            onLongPress = if (m.isPending) null else ({ onLongPress(m) }),
        )
    }
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
private fun PersonDialog(loader: suspend (String) -> ChatPersonCard?, message: TeamChatMessage, onDismiss: () -> Unit) {
    var card by remember { mutableStateOf<ChatPersonCard?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(message.senderUserId) {
        val id = message.senderUserId ?: return@LaunchedEffect
        runCatching { loader(id) }.onSuccess { card = it; failed = it == null }.onFailure { failed = true }
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

/**
 * Узкий экран: список или разговор по очереди. От 600dp — список переписок слева,
 * разговор справа. Выбранный собеседник хранится в навигаторе и переживает поворот.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MessagesTab() {
    val vm = operatorViewModel { e -> MessagesViewModel(ChatService(e.api)) }
    val scope = rememberCoroutineScope()
    val threads by vm.threads.collectAsStateWithLifecycle()

    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val baseDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())
    val directive = remember(baseDirective, wide) {
        baseDirective.copy(maxHorizontalPartitions = if (wide) 2 else 1)
    }
    val navigator = rememberListDetailPaneScaffoldNavigator<String>(scaffoldDirective = directive)
    val selectedId = navigator.currentDestination?.contentKey
    val dualPane = directive.maxHorizontalPartitions > 1

    var choosing by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.loadThreads() }
    val selectedNow by rememberUpdatedState(selectedId)
    val dualNow by rememberUpdatedState(dualPane)
    PollWhileStarted(POLL_MS) {
        val sel = selectedNow
        // Список виден (отдельно или рядом с разговором) — непрочитанные появляются сами.
        if (sel == null || dualNow) vm.refreshThreads()
        if (sel != null) {
            vm.refreshConversation(sel)
            vm.flushUndelivered()
        }
    }

    fun openThread(userId: String) {
        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, userId) }
    }

    fun back() {
        scope.launch {
            navigator.navigateBack()
            vm.refreshThreads()
        }
    }

    BackHandler(enabled = selectedId != null && !dualPane) { back() }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane {
                ThreadsPane(
                    threads = threads,
                    selectedId = selectedId.takeIf { dualPane },
                    onRetry = vm::retryThreads,
                    onNew = { choosing = true },
                    onOpen = ::openThread,
                )
            }
        },
        detailPane = {
            AnimatedPane {
                if (selectedId == null) {
                    Box(Modifier.fillMaxSize().background(Orda.colors.background), contentAlignment = Alignment.Center) {
                        EmptyState(Icons.Filled.Chat, "Выберите переписку", "Или нажмите карандаш, чтобы написать первым.")
                    }
                } else {
                    // key: смена собеседника начинает ленту, прокрутку и поле с чистого листа.
                    key(selectedId) {
                        ConversationPane(
                            userId = selectedId,
                            name = threads.data?.firstOrNull { it.otherUserId == selectedId }?.otherName ?: vm.nameOf(selectedId),
                            vm = vm,
                            showBack = !dualPane,
                            onBack = ::back,
                        )
                    }
                }
            }
        },
    )

    if (choosing) {
        ContactPickerDialog(
            loader = vm::contacts,
            onDismiss = { choosing = false },
            onPick = { contact ->
                choosing = false
                // Переписки может ещё не быть: открываем сразу, чтобы человек попал в разговор.
                vm.rememberName(contact.userId, contact.name)
                openThread(contact.userId)
            },
        )
    }
}

@Composable
private fun ThreadsPane(
    threads: kz.ordaops.android.core.ui.Loadable<List<DirectThread>>,
    selectedId: String?,
    onRetry: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Сообщения", color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onNew) {
                Icon(Icons.Filled.Edit, "Новое сообщение", tint = Orda.colors.brand)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            StateContainer(
                state = threads,
                onRetry = onRetry,
                isEmpty = { it.isEmpty() },
                emptyIcon = Icons.Filled.Chat,
                emptyTitle = "Переписок нет",
                emptyMessage = "Нажмите карандаш, чтобы написать первым.",
            ) { list ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(list, key = { it.otherUserId }, contentType = { "thread" }) { thread ->
                        ThreadRow(thread, selected = thread.otherUserId == selectedId) { onOpen(thread.otherUserId) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(thread: DirectThread, selected: Boolean, onClick: () -> Unit) {
    val colors = Orda.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.surfaceRaised else colors.surface, RoundedCornerShape(Radius.md))
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
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (if (thread.lastFromMe) "Вы: " else "") + thread.preview,
                color = colors.textDim,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
private fun ContactPickerDialog(loader: suspend () -> List<DirectContact>, onDismiss: () -> Unit, onPick: (DirectContact) -> Unit) {
    var contacts by remember { mutableStateOf<List<DirectContact>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        error = null
        try {
            contacts = loader()
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
private fun ConversationPane(userId: String, name: String, vm: MessagesViewModel, showBack: Boolean, onBack: () -> Unit) {
    val colors = Orda.colors
    val scope = rememberCoroutineScope()
    val env = LocalOperatorEnv.current
    val state by vm.conversationContent.collectAsStateWithLifecycle()
    val openedId by vm.opened.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<DirectMessage?>(null) }
    var confirmBlock by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(userId) {
        launch { vm.loadBlocked() }
        vm.open(userId)
    }

    val isBlocked = userId in vm.blocked
    // Пока открыт другой разговор, чужую ленту не показываем — только загрузку.
    val shown = if (openedId == userId) state else kz.ordaops.android.core.ui.Loadable(isLoading = true)

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = colors.text) }
            } else {
                Box(Modifier.size(Spacing.sm))
            }
            Avatar(name, null, 34)
            Text(
                name,
                color = colors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                            if (isBlocked) scope.launch { vm.setBlocked(false, userId) } else confirmBlock = true
                        },
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            StateContainer(
                state = shown,
                onRetry = { vm.retryConversation(userId) },
                onRefresh = null,
                isEmpty = { it.rows.isEmpty() },
                emptyIcon = Icons.Filled.Chat,
                emptyTitle = "Пока пусто",
                emptyMessage = "Напишите $name первым.",
            ) { content ->
                DirectMessages(content, onLongPress = { reporting = it })
            }
        }

        if (vm.undelivered.isNotEmpty()) {
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
                text = vm.draftOf(userId),
                onTextChange = { vm.setDraft(userId, it) },
                placeholder = "Сообщение для $name",
                isSending = vm.isSending,
                errorText = vm.sendError,
                onSend = {
                    val text = vm.draftOf(userId)
                    vm.setDraft(userId, "")
                    vm.send(text, userId, env.resolver.session.displayName ?: "Вы")
                },
                // Опрос вдвоём смысла не имеет, а фото и файл нужны и здесь.
                onPickFile = { bytes, fileName, mime, kind -> vm.sendAttachment(bytes, fileName, mime, kind, userId) },
            )
        }
    }

    reporting?.let { message ->
        AlertDialog(
            onDismissRequest = { reporting = null },
            title = { Text("Пожаловаться на сообщение?") },
            text = { Text("Жалобу увидит владелец. Собеседник об этом не узнает.") },
            confirmButton = {
                TextButton(onClick = { vm.report(message.id); reporting = null }) { Text("Пожаловаться", color = colors.negative) }
            },
            dismissButton = { TextButton(onClick = { reporting = null }) { Text("Отмена") } },
        )
    }
    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text("Заблокировать $name?") },
            text = { Text("Писать друг другу вы не сможете, пока не снимете блокировку.") },
            confirmButton = {
                TextButton(onClick = { confirmBlock = false; scope.launch { vm.setBlocked(true, userId) } }) {
                    Text("Заблокировать", color = colors.negative)
                }
            },
            dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun DirectMessages(content: DirectContent, onLongPress: (DirectMessage) -> Unit) {
    val listState = rememberLazyListState()
    FollowNewest(listState, content.newestKey, content.newestIsMine)

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(content.rows, key = { it.key }, contentType = { it.contentType }) { row ->
            when (row) {
                is DirectRow.Day -> DayDivider(row.label)
                is DirectRow.Message -> {
                    val m = row.message
                    ChatBubble(
                        text = m.displayText,
                        senderName = m.senderName,
                        roleLabel = null,
                        timeMs = m.createdMs,
                        isMine = row.isMine,
                        isEdited = m.isEdited,
                        isPending = m.isPending,
                        showsSender = false,
                        showsAvatar = false,
                        avatarUrl = null,
                        attachments = m.attachments,
                        // Пожаловаться можно и здесь: травля чаще случается один на один.
                        onLongPress = if (!row.isMine && !m.isPending) ({ onLongPress(m) }) else null,
                    )
                }
            }
        }
    }
}

// ── Общее ────────────────────────────────────────────────────────────────────

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
