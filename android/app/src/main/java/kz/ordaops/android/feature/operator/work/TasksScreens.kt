package kz.ordaops.android.feature.operator.work

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.key
import kz.ordaops.android.ui.components.rememberHaptics
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.money
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

// ── Задачи ───────────────────────────────────────────────────────────────────

/** Ответ, которому нужна причина. */
private data class NoteRequest(val task: OperatorTask, val response: TaskResponse)

/** Задачи оператора — `TasksScreen`. */
@Composable
fun TasksTab() {
    val env = LocalOperatorEnv.current
    val context = LocalContext.current
    val store = remember { CabinetStore(env, context) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var noteRequest by remember { mutableStateOf<NoteRequest?>(null) }
    val haptics = rememberHaptics()

    LaunchedEffect(Unit) {
        store.loadCachedTasks()
        store.loadTasks()
    }

    /**
     * «Нужны уточнения» и «не могу выполнить» без причины бесполезны:
     * руководитель всё равно придёт спрашивать — у них спрашиваем пояснение.
     */
    fun respond(task: OperatorTask, response: TaskResponse) {
        if (response.needsNote) {
            noteRequest = NoteRequest(task, response)
            return
        }
        scope.launch {
            error = store.respondToTask(task, response)
            if (error == null) haptics.success() else haptics.error()
        }
    }

    Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
        SectionTitle("Задачи")
        Refreshable(refreshing = store.isLoadingTasks && store.tasks.isNotEmpty(), onRefresh = { scope.launch { store.loadTasks() } }) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                store.deferredNotice?.let { NoticeCard(it, Orda.colors.warning) }
                val tasks = store.tasks
                when {
                    store.isLoadingTasks && tasks.isEmpty() -> Box(Modifier.fillMaxWidth().padding(Spacing.xxl), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Orda.colors.brand)
                    }
                    tasks.isEmpty() -> EmptyState(
                        icon = Icons.Filled.Verified,
                        title = "Задач нет",
                        message = store.error ?: "Как только руководитель поставит задачу, она появится здесь.",
                    )
                    else -> {
                        val active = store.activeTasks
                        if (active.isNotEmpty()) {
                            SectionHeader("В работе", "${active.size} ${pluralize(active.size, "задача", "задачи", "задач")}")
                            active.forEach { task ->
                                key(task.id) {
                                    TaskSwipe(
                                        enabled = !task.isOnReview,
                                        onDone = {
                                            haptics.success()
                                            respond(task, TaskResponse.AlreadyDone)
                                        },
                                    ) { TaskCard(task) { respond(task, it) } }
                                }
                            }
                        }
                        val done = tasks.filter { it.isDone }
                        if (done.isNotEmpty()) {
                            Box(Modifier.padding(top = Spacing.md)) { SectionHeader("Завершённые", null) }
                            done.forEach { task -> TaskCard(task, onRespond = null) }
                        }
                    }
                }
                error?.let { Text(it, color = Orda.colors.negative, fontSize = 14.sp) }
            }
        }
    }

    noteRequest?.let { request ->
        TaskNoteDialog(
            response = request.response,
            onDismiss = { noteRequest = null },
            send = { note ->
                val failure = store.respondToTask(request.task, request.response, note)
                if (failure == null) {
                    noteRequest = null
                    haptics.success()
                } else {
                    haptics.error()
                }
                failure
            },
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

/** Причина к ответу по задаче — `TaskNoteSheet`. */
@Composable
private fun TaskNoteDialog(response: TaskResponse, onDismiss: () -> Unit, send: suspend (String) -> String?) {
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        containerColor = Orda.colors.elevated,
        title = { Text(response.title, color = Orda.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
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
                onClick = {
                    scope.launch {
                        sending = true
                        error = send(note.trim())
                        sending = false
                    }
                },
            ) { Text(if (sending) "Отправляем…" else "Отправить") }
        },
        dismissButton = { TextButton(enabled = !sending, onClick = onDismiss) { Text("Отмена") } },
    )
}

// ── Чек-листы ────────────────────────────────────────────────────────────────

/** Список чек-листов смены — `ChecklistsScreen`. */
@Composable
fun ChecklistsTab() {
    val env = LocalOperatorEnv.current
    val context = LocalContext.current
    val store = remember { CabinetStore(env, context) }
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf<ChecklistTemplate?>(null) }

    LaunchedEffect(Unit) {
        store.prepareOutbox()
        store.loadCachedKnowledge()
        // Экран открыли — самое время дослать: связь могла вернуться, пока человек ходил по точке.
        store.flushChecklists()
        store.loadKnowledge()
        store.loadShift()
    }

    running?.let { template ->
        BackHandler { running = null }
        ChecklistRunScreen(store, template) { running = null }
        return
    }

    val shift = store.shift
    val knowledge = store.knowledge
    // Чек-лист привязывается к смене: пройденный на чужой окажется в её отчёте со штрафами и премиями.
    val canRun = shift?.isMyShift ?: false

    Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
        SectionTitle("Чек-листы")
        Refreshable(
            refreshing = store.isLoadingKnowledge && knowledge != null,
            onRefresh = { scope.launch { store.loadKnowledge(); store.loadShift() } },
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (shift != null) {
                    if (shift.isSomeoneElses) {
                        NoticeCard(
                            "Сейчас смену ведёт ${shift.shift?.operatorName ?: "сменщик"}. Чек-листы проходит тот, кто на смене.",
                            Orda.colors.info,
                        )
                    } else if (!shift.hasOpenShift) {
                        NoticeCard("Чек-листы проходят в открытой смене. Откройте смену, чтобы начать.", Orda.colors.warning)
                    }
                }

                if (store.undelivered.isNotEmpty()) {
                    val n = store.undelivered.size
                    OrdaCard {
                        Text(
                            "$n ${pluralize(n, "чек-лист ждёт", "чек-листа ждут", "чек-листов ждут")} связи",
                            color = Orda.colors.text,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(store.undelivered.joinToString(", ") { it.title }, color = Orda.colors.textMuted, fontSize = 13.sp)
                        SecondaryButton("Отправить сейчас") { scope.launch { store.flushChecklists() } }
                    }
                }

                store.deferredNotice?.let { NoticeCard(it, Orda.colors.warning) }

                if (knowledge == null) {
                    if (store.isLoadingKnowledge) {
                        Box(Modifier.fillMaxWidth().padding(Spacing.xxl), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Orda.colors.brand)
                        }
                    } else {
                        store.error?.let { ErrorBanner(it) }
                    }
                } else if (knowledge.templates.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Checklist,
                        title = "Чек-листов нет",
                        message = "Для вашей точки чек-листы пока не настроены.",
                    )
                } else {
                    knowledge.templates.forEach { template ->
                        ChecklistCard(
                            template = template,
                            itemCount = knowledge.items(template.id).size,
                            completedRun = knowledge.completedRun(template.id),
                            canRun = canRun,
                            onRun = { running = template },
                        )
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

/** Прохождение чек-листа: пункт за пунктом — `ChecklistRunScreen`. */
@Composable
private fun ChecklistRunScreen(store: CabinetStore, template: ChecklistTemplate, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var runId by remember { mutableStateOf<String?>(null) }
    val answers = remember { mutableStateMapOf<String, ChecklistAnswer>() }
    var isStarting by remember { mutableStateOf(true) }
    var isSubmitting by remember { mutableStateOf(false) }
    // Начали без связи: ответы уйдут одной пачкой позже.
    var isOffline by remember { mutableStateOf(false) }
    // Чек-лист сложен в очередь — показываем это вместо начислений.
    var isDeferred by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ChecklistRunResult?>(null) }

    val items = store.knowledge?.items(template.id).orEmpty()
    val answeredCount = items.count { answers[it.id] != null }
    val missingRequired = items.filter { it.isRequired && answers[it.id] == null }

    LaunchedEffect(template.id) {
        if (store.knowledge == null) store.loadKnowledge()
        when (val started = store.startChecklist(template)) {
            is CabinetStore.StartOutcome.Started -> runId = started.runId
            // Без связи чек-лист всё равно проходят: обход делают там, где сети нет.
            is CabinetStore.StartOutcome.Failed -> if (started.offline) isOffline = true else error = started.message
        }
        isStarting = false
    }

    fun submit() {
        isSubmitting = true
        error = null
        val payload = answers.values.toList()
        val id = runId
        scope.launch {
            // Запуска нет — значит начинали без связи. Складываем целиком.
            if (id == null) {
                store.deferChecklist(template, payload)
                isSubmitting = false
                isDeferred = true
                return@launch
            }
            val failure = store.saveChecklist(id, payload)
            if (failure != null) {
                error = failure
                isSubmitting = false
                return@launch
            }
            store.completeChecklist(id)
                .onSuccess { result = it }
                .onFailure { error = it.message }
            isSubmitting = false
        }
    }

    Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
        SectionTitle(template.title, onBack = onClose)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            val finished = result
            when {
                isStarting -> Box(Modifier.fillMaxWidth().padding(Spacing.xxl), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Orda.colors.brand)
                }
                isDeferred -> {
                    // Начислений сейчас не будет: их считает сервер. Обещать премию, которой ещё нет, нельзя.
                    AccentCard(Orda.colors.warning) {
                        Text("Сохранено на устройстве", color = Orda.colors.text, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        Text(
                            "Чек-лист уйдёт сам, как только появится связь. Штрафы и премии по нему посчитает сервер — они появятся вместе с отправкой.",
                            color = Orda.colors.textMuted,
                            fontSize = 14.sp,
                        )
                    }
                    PrimaryButton("Готово", Modifier.fillMaxWidth(), onClick = onClose)
                }
                finished != null -> {
                    AccentCard(Orda.colors.positive) {
                        Text("Чек-лист пройден", color = Orda.colors.positive, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        if (finished.bonusesTotal > 0) ResultRow("Начислен бонус", money(finished.bonusesTotal), Orda.colors.positive)
                        if (finished.finesTotal > 0) ResultRow("Начислен штраф", money(finished.finesTotal), Orda.colors.negative)
                        if (finished.bonusesTotal == 0.0 && finished.finesTotal == 0.0) {
                            Text("Без штрафов и бонусов.", color = Orda.colors.textMuted, fontSize = 14.sp)
                        }
                    }
                    PrimaryButton("Готово", Modifier.fillMaxWidth(), onClick = onClose)
                }
                else -> {
                    if (isOffline) {
                        NoticeCard(
                            "Связи нет — проходите как обычно. Ответы сохранятся на устройстве и уйдут потом.",
                            Orda.colors.warning,
                        )
                    }
                    OrdaCard {
                        Text("Отвечено пунктов: $answeredCount из ${items.size}", color = Orda.colors.textMuted, fontSize = 14.sp)
                        LinearProgressIndicator(
                            progress = { if (items.isEmpty()) 0f else answeredCount.toFloat() / items.size },
                            modifier = Modifier.fillMaxWidth(),
                            color = Orda.colors.brand,
                            trackColor = Orda.colors.surfaceRaised,
                        )
                        if (template.blocksShift) StatusChip("без него смену не закрыть", ChipKind.Warning)
                    }

                    items.forEach { item ->
                        ChecklistItemCard(item, answers[item.id]) { answers[item.id] = it }
                    }

                    error?.let { Text(it, color = Orda.colors.negative, fontSize = 14.sp) }

                    PrimaryButton(
                        "Завершить чек-лист",
                        Modifier.fillMaxWidth(),
                        enabled = missingRequired.isEmpty(),
                        loading = isSubmitting,
                        onClick = ::submit,
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

@Composable
private fun ResultRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Orda.colors.textMuted, fontSize = 14.sp)
        Text(value, color = color, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** Один пункт чек-листа: ответ и комментарий — `ChecklistItemCard`. */
@Composable
private fun ChecklistItemCard(item: ChecklistItem, answer: ChecklistAnswer?, onChange: (ChecklistAnswer) -> Unit) {
    val haptics = rememberHaptics()
    var comment by remember { mutableStateOf("") }
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
