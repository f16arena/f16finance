package kz.ordaops.android.feature.operator.work

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.ordaops.android.feature.operator.common.operatorViewModel
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.WavyProgress
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.time.format.DateTimeFormatter
import java.util.Locale

private val shortDateFormat = DateTimeFormatter.ofPattern("d MMM", Locale("ru"))

private fun shortDate(iso: String?): String? = parseDateOnly(iso)?.format(shortDateFormat) ?: iso?.takeIf { it.isNotBlank() }

private fun TaskResponse.icon(): ImageVector = when (this) {
    TaskResponse.Accept -> Icons.Filled.PlayCircle
    TaskResponse.NeedInfo -> Icons.Filled.Help
    TaskResponse.Blocked -> Icons.Filled.ReportProblem
    TaskResponse.AlreadyDone -> Icons.Filled.CheckCircle
    TaskResponse.Complete -> Icons.Filled.Send
}

private val listPadding = PaddingValues(Spacing.lg)

// ── Задачи ───────────────────────────────────────────────────────────────────

/** Задачи оператора — `TasksScreen`. Детального экрана задачи нет, поэтому на планшете список центрируется. */
@Composable
fun TasksTab() {
    val vm = operatorViewModel { env -> TasksViewModel(env) }
    val state by vm.state.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    // Ответ, которому нужна причина: хранится идентификатором, чтобы пережить поворот и пересоздание.
    var noteTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var noteResponse by rememberSaveable { mutableStateOf<String?>(null) }
    val haptics = rememberHaptics()
    val seenCompleted = remember { action.completed }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(action.completed) {
        if (action.completed != seenCompleted) {
            noteTaskId = null
            haptics.success()
        }
    }
    LaunchedEffect(action.error) { if (action.error != null) haptics.error() }

    /**
     * «Нужны уточнения» и «не могу выполнить» без причины бесполезны:
     * руководитель всё равно придёт спрашивать — у них спрашиваем пояснение.
     */
    fun respond(task: OperatorTask, response: TaskResponse) {
        if (response.needsNote) {
            vm.clearActionError()
            noteTaskId = task.id
            noteResponse = response.raw
            return
        }
        vm.respond(task, response)
    }

    Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
        SectionTitle("Задачи")
        notice?.let { Box(Modifier.padding(horizontal = Spacing.lg)) { NoticeCard(it, Orda.colors.warning) } }
        StateContainer(
            state = state,
            onRetry = vm::refresh,
            modifier = Modifier.weight(1f),
            isEmpty = { it.isEmpty },
            emptyIcon = Icons.Filled.Verified,
            emptyTitle = "Задач нет",
            emptyMessage = "Как только руководитель поставит задачу, она появится здесь.",
        ) { ui ->
            CenteredContent {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = listPadding,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    if (ui.active.isNotEmpty()) {
                        item(key = "h-active", contentType = "header") {
                            SectionHeader("В работе", "${ui.active.size} ${pluralize(ui.active.size, "задача", "задачи", "задач")}")
                        }
                        items(ui.active, key = { it.id }, contentType = { "task" }) { task ->
                            TaskSwipe(
                                enabled = !task.isOnReview,
                                onDone = {
                                    haptics.success()
                                    respond(task, TaskResponse.AlreadyDone)
                                },
                            ) { TaskCard(task) { respond(task, it) } }
                        }
                    }
                    if (ui.done.isNotEmpty()) {
                        item(key = "h-done", contentType = "header") {
                            Box(Modifier.padding(top = Spacing.md)) { SectionHeader("Завершённые", null) }
                        }
                        items(ui.done, key = { it.id }, contentType = { "task-done" }) { task ->
                            TaskCard(task, onRespond = null)
                        }
                    }
                    if (noteTaskId == null) {
                        action.error?.let { e ->
                            item(key = "error", contentType = "error") { Text(e, color = Orda.colors.negative, fontSize = 14.sp) }
                        }
                    }
                }
            }
        }
    }

    val noteTask = noteTaskId?.let { id -> state.data?.let { ui -> (ui.active + ui.done).firstOrNull { it.id == id } } }
    val noteKind = TaskResponse.entries.firstOrNull { it.raw == noteResponse }
    if (noteTask != null && noteKind != null) {
        TaskNoteDialog(
            response = noteKind,
            sending = action.sending,
            error = action.error,
            onDismiss = { noteTaskId = null },
            onSend = { note -> vm.respond(noteTask, noteKind, note) },
        )
    }
}

/**
 * Свайп справа налево — «отправить на проверку» (как кнопка на карточке). Карточка
 * всегда возвращается на место: из списка её уберёт только обновлённое состояние.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskSwipe(enabled: Boolean, onDone: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            if (target == SwipeToDismissBoxValue.EndToStart) onDone()
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(Orda.colors.positive.copy(alpha = 0.2f), RoundedCornerShape(Radius.lg)).padding(end = Spacing.xl),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("Выполнено", color = Orda.colors.positive, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Orda.colors.positive)
                }
            }
        },
    ) { content() }
}

@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(title, color = Orda.colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) Text(subtitle, color = Orda.colors.textDim, fontSize = 13.sp)
    }
}

@Composable
private fun TaskCard(task: OperatorTask, onRespond: ((TaskResponse) -> Unit)?) {
    AccentCard(if (task.isOverdue) Orda.colors.negative else null) {
        Text(task.title, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        task.description?.takeIf { it.isNotEmpty() }?.let {
            Text(it, color = Orda.colors.textMuted, fontSize = 13.sp, maxLines = 3)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            when {
                task.isOverdue -> StatusChip("просрочена", ChipKind.Danger)
                task.isOnReview -> StatusChip("на проверке", ChipKind.Info)
                task.isDone -> StatusChip("выполнена", ChipKind.Good)
                task.isUrgent -> StatusChip("срочно", ChipKind.Warning)
            }
            shortDate(task.dueDate)?.let { Text("до $it", color = Orda.colors.textDim, fontSize = 13.sp) }
            task.companyName?.let { Text("· $it", color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1) }
        }
        if (onRespond != null && !task.isOnReview) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SecondaryButton("Отправить на проверку", Modifier.weight(1f)) { onRespond(TaskResponse.AlreadyDone) }
                // Остальные ответы — под многоточием: раньше сказать «принял» или «не могу» из приложения было нечем.
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Другие ответы", tint = Orda.colors.textDim)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        TaskResponse.entries.filter { it != TaskResponse.AlreadyDone }.forEach { response ->
                            DropdownMenuItem(
                                text = { Text(response.title) },
                                leadingIcon = { Icon(response.icon(), contentDescription = null) },
                                onClick = {
                                    menu = false
                                    onRespond(response)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Причина к ответу по задаче — `TaskNoteSheet`. Текст переживает поворот. */
@Composable
private fun TaskNoteDialog(
    response: TaskResponse,
    sending: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var note by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        containerColor = Orda.colors.elevated,
        title = { Text(response.title, color = Orda.colors.text) },
        text = {
            Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    if (response == TaskResponse.Blocked) "Что мешает выполнить задачу?" else "Что именно нужно уточнить?",
                    color = Orda.colors.text,
                    fontWeight = FontWeight.SemiBold,
                )
                NoteField(note, { note = it }, "Коротко, своими словами", minLines = 3, maxLines = 8)
                // Ответ попадёт в историю задачи — руководитель увидит его вместе с задачей.
                Text("Ответ появится в истории задачи.", color = Orda.colors.textDim, fontSize = 13.sp)
                error?.let { Text(it, color = Orda.colors.negative, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending && note.trim().length >= 3,
                onClick = { onSend(note.trim()) },
            ) { Text(if (sending) "Отправляем…" else "Отправить") }
        },
        dismissButton = { TextButton(enabled = !sending, onClick = onDismiss) { Text("Отмена") } },
    )
}

// ── Чек-листы ────────────────────────────────────────────────────────────────

/** Список чек-листов смены — `ChecklistsScreen`. */
@Composable
fun ChecklistsTab() {
    val appContext = LocalContext.current.applicationContext
    val vm = operatorViewModel { env -> ChecklistsViewModel(env, appContext) }
    val knowledge by vm.knowledge.collectAsStateWithLifecycle()
    val shift by vm.shift.collectAsStateWithLifecycle()
    val undelivered by vm.undelivered.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.onOpen() }

    run?.let { current ->
        BackHandler { vm.closeRun() }
        ChecklistRunScreen(vm, current, knowledge.data)
        return
    }

    // Чек-лист привязывается к смене: пройденный на чужой окажется в её отчёте со штрафами и премиями.
    val canRun = shift?.isMyShift ?: false

    Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
        SectionTitle("Чек-листы")
        StateContainer(
            state = knowledge,
            onRetry = vm::refresh,
            modifier = Modifier.weight(1f),
            isEmpty = { false },
        ) { center ->
            CenteredContent {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = listPadding,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    shift?.let { head ->
                        if (head.isSomeoneElses) {
                            item(key = "shift", contentType = "notice") {
                                NoticeCard(
                                    "Сейчас смену ведёт ${head.shift?.operatorName ?: "сменщик"}. Чек-листы проходит тот, кто на смене.",
                                    Orda.colors.info,
                                )
                            }
                        } else if (!head.hasOpenShift) {
                            item(key = "shift", contentType = "notice") {
                                NoticeCard("Чек-листы проходят в открытой смене. Откройте смену, чтобы начать.", Orda.colors.warning)
                            }
                        }
                    }

                    if (undelivered.isNotEmpty()) {
                        item(key = "undelivered", contentType = "undelivered") {
                            val n = undelivered.size
                            OrdaCard {
                                Text(
                                    "$n ${pluralize(n, "чек-лист ждёт", "чек-листа ждут", "чек-листов ждут")} связи",
                                    color = Orda.colors.text,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(undelivered.joinToString(", ") { it.title }, color = Orda.colors.textMuted, fontSize = 13.sp)
                                SecondaryButton("Отправить сейчас") { vm.flushNow() }
                            }
                        }
                    }

                    notice?.let { n -> item(key = "notice", contentType = "notice") { NoticeCard(n, Orda.colors.warning) } }

                    if (center.templates.isEmpty()) {
                        item(key = "empty", contentType = "empty") {
                            EmptyState(
                                icon = Icons.Filled.Checklist,
                                title = "Чек-листов нет",
                                message = "Для вашей точки чек-листы пока не настроены.",
                            )
                        }
                    } else {
                        items(center.templates, key = { it.id }, contentType = { "template" }) { template ->
                            ChecklistCard(
                                template = template,
                                itemCount = center.items(template.id).size,
                                completedRun = center.completedRun(template.id),
                                canRun = canRun,
                                onRun = { vm.startChecklist(template) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistCard(
    template: ChecklistTemplate,
    itemCount: Int,
    completedRun: ChecklistRun?,
    canRun: Boolean,
    onRun: () -> Unit,
) {
    val accent = when {
        completedRun != null -> Orda.colors.positive
        template.blocksShift -> Orda.colors.warning
        else -> null
    }
    AccentCard(accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                template.title,
                color = Orda.colors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (completedRun != null) StatusChip("пройден", ChipKind.Good)
            else if (template.blocksShift) StatusChip("обязательный", ChipKind.Warning)
        }
        template.description?.takeIf { it.isNotEmpty() }?.let { Text(it, color = Orda.colors.textMuted, fontSize = 13.sp) }
        Text(
            "$itemCount ${pluralize(itemCount, "пункт", "пункта", "пунктов")}",
            color = Orda.colors.textDim,
            fontSize = 13.sp,
        )
        if (completedRun != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                completedRun.bonusesTotal?.takeIf { it > 0 }?.let {
                    Text("бонус ${money(it)}", color = Orda.colors.positive, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                completedRun.finesTotal?.takeIf { it > 0 }?.let {
                    Text("штраф ${money(it)}", color = Orda.colors.negative, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            PrimaryButton("Пройти", Modifier.fillMaxWidth(), enabled = canRun, onClick = onRun)
        }
    }
}

/** Прохождение чек-листа: пункт за пунктом — `ChecklistRunScreen`. Состояние — во ViewModel, поворот его не теряет. */
@Composable
private fun ChecklistRunScreen(vm: ChecklistsViewModel, run: ChecklistRunUi, center: KnowledgeCenter?) {
    val template = run.template
    val questions = remember(center, template.id) { center?.items(template.id).orEmpty() }
    val answeredCount = questions.count { run.answers[it.id] != null }
    val missingRequired = questions.filter { it.isRequired && run.answers[it.id] == null }

    Column(Modifier.fillMaxSize().background(Orda.colors.background).imePadding()) {
        SectionTitle(template.title, onBack = vm::closeRun)
        CenteredContent {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = listPadding,
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                val finished = run.result
                when {
                    run.isStarting -> item(key = "starting", contentType = "loading") {
                        Box(Modifier.fillMaxWidth().padding(Spacing.xxl), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Orda.colors.brand)
                        }
                    }
                    run.isDeferred -> item(key = "deferred", contentType = "result") {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                            // Начислений сейчас не будет: их считает сервер. Обещать премию, которой ещё нет, нельзя.
                            AccentCard(Orda.colors.warning) {
                                Text("Сохранено на устройстве", color = Orda.colors.text, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                                Text(
                                    "Чек-лист уйдёт сам, как только появится связь. Штрафы и премии по нему посчитает сервер — они появятся вместе с отправкой.",
                                    color = Orda.colors.textMuted,
                                    fontSize = 14.sp,
                                )
                            }
                            PrimaryButton("Готово", Modifier.fillMaxWidth(), onClick = vm::closeRun)
                        }
                    }
                    finished != null -> item(key = "finished", contentType = "result") {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                            AccentCard(Orda.colors.positive) {
                                Text("Чек-лист пройден", color = Orda.colors.positive, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                if (finished.bonusesTotal > 0) ResultRow("Начислен бонус", money(finished.bonusesTotal), Orda.colors.positive)
                                if (finished.finesTotal > 0) ResultRow("Начислен штраф", money(finished.finesTotal), Orda.colors.negative)
                                if (finished.bonusesTotal == 0.0 && finished.finesTotal == 0.0) {
                                    Text("Без штрафов и бонусов.", color = Orda.colors.textMuted, fontSize = 14.sp)
                                }
                            }
                            PrimaryButton("Готово", Modifier.fillMaxWidth(), onClick = vm::closeRun)
                        }
                    }
                    else -> {
                        if (run.isOffline) {
                            item(key = "offline", contentType = "notice") {
                                NoticeCard(
                                    "Связи нет — проходите как обычно. Ответы сохранятся на устройстве и уйдут потом.",
                                    Orda.colors.warning,
                                )
                            }
                        }
                        item(key = "progress", contentType = "progress") {
                            OrdaCard {
                                Text("Отвечено пунктов: $answeredCount из ${questions.size}", color = Orda.colors.textMuted, fontSize = 14.sp)
                                WavyProgress(
                                    progress = { if (questions.isEmpty()) 0f else answeredCount.toFloat() / questions.size },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Orda.colors.brand,
                                    trackColor = Orda.colors.surfaceRaised,
                                )
                                if (template.blocksShift) StatusChip("без него смену не закрыть", ChipKind.Warning)
                            }
                        }

                        items(questions, key = { it.id }, contentType = { "item" }) { item ->
                            ChecklistItemCard(item, run.answers[item.id], vm::answer)
                        }

                        item(key = "submit", contentType = "submit") {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                run.error?.let { Text(it, color = Orda.colors.negative, fontSize = 14.sp) }
                                PrimaryButton(
                                    "Завершить чек-лист",
                                    Modifier.fillMaxWidth(),
                                    enabled = missingRequired.isEmpty(),
                                    loading = run.isSubmitting,
                                    onClick = vm::submit,
                                )
                                if (missingRequired.isNotEmpty()) {
                                    val n = missingRequired.size
                                    Text(
                                        "Осталось ответить: $n ${pluralize(n, "обязательный пункт", "обязательных пункта", "обязательных пунктов")}",
                                        color = Orda.colors.textDim,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Orda.colors.textMuted, fontSize = 14.sp)
        Text(value, color = color, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** Один пункт чек-листа: ответ и комментарий — `ChecklistItemCard`. Комментарий переживает поворот. */
@Composable
private fun ChecklistItemCard(item: ChecklistItem, answer: ChecklistAnswer?, onChange: (ChecklistAnswer) -> Unit) {
    val haptics = rememberHaptics()
    var comment by rememberSaveable(item.id) { mutableStateOf(answer?.comment.orEmpty()) }
    val accent = when (answer?.answer) {
        "yes" -> Orda.colors.positive
        "no" -> Orda.colors.negative
        else -> null
    }
    AccentCard(accent) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(item.title, color = Orda.colors.text, fontSize = 16.sp)
                item.description?.takeIf { it.isNotEmpty() }?.let { Text(it, color = Orda.colors.textMuted, fontSize = 13.sp) }
            }
            if (item.isRequired) Text("обяз.", color = Orda.colors.warning, fontSize = 13.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            AnswerButton("Да", answer?.answer == "yes", Orda.colors.positive, Modifier.weight(1f)) {
                haptics.tick()
                onChange(ChecklistAnswer(item.id, "yes", comment.ifEmpty { null }))
            }
            AnswerButton("Нет", answer?.answer == "no", Orda.colors.negative, Modifier.weight(1f)) {
                haptics.tick()
                onChange(ChecklistAnswer(item.id, "no", comment.ifEmpty { null }))
            }
        }

        // Комментарий обязателен при отрицательном ответе: «нет» без объяснения бесполезно тому, кто будет разбираться.
        if (answer?.answer == "no") {
            NoteField(
                comment,
                {
                    comment = it
                    onChange(answer.copy(comment = it.ifEmpty { null }))
                },
                "Что не так?",
            )
        }

        if (item.requiresPhoto) {
            Text("Нужно фото — снимите в веб-версии", color = Orda.colors.textDim, fontSize = 13.sp)
        }

        val fine = item.fineAmount
        if (fine != null && fine > 0 && answer?.answer == "no") {
            Text("штраф за нарушение: ${money(fine)}", color = Orda.colors.negative, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AnswerButton(title: String, selected: Boolean, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(if (selected) tint else Orda.colors.surfaceRaised, RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = if (selected) Color.White else Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
