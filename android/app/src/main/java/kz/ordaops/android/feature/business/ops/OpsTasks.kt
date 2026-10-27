package kz.ordaops.android.feature.business.ops

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.operator.money.SoftDivider
import kz.ordaops.android.feature.operator.money.StatRow
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.feature.operator.work.TaskResponse
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

private const val TASKS_PATH = "/api/admin/tasks"

private enum class TaskFilter(val title: String) { Mine("Мои"), Open("Активные"), Done("Готовые"), All("Все") }

private val Violet = Color(0xFF8B5CF6)
private val Orange = Color(0xFFF97316)
private val Blue = Color(0xFF3B82F6)

private fun parseTasks(root: JsonObject): List<OpsTask> = root.items("data").map(OpsTask::parse).filter { it.id.isNotEmpty() }

// ── Экран «Задачи» ───────────────────────────────────────────────────────────

/**
 * Задачи команды, сгруппированные по смыслу: просроченные наверх, дальше срочные,
 * дальше по сроку. Порт `TeamTasksScreen`. Тому, кто задачи не ставит, доска нужна
 * не целиком — открываем сразу «Мои».
 */
@Composable
fun TasksScreen() {
    val env = LocalBusiness.current
    val canCreate = env.can("tasks.create")
    val canComplete = env.can("tasks.complete")
    val all = rememberOpsLoader("tasks", ApiRequest(TASKS_PATH, query = mapOf("page_size" to "200")), ::parseTasks)
    val mine = rememberOpsLoader("tasks-mine", ApiRequest(TASKS_PATH, query = mapOf("mine" to "1", "page_size" to "100")), ::parseTasks)
    val allState by all.state.collectAsStateWithLifecycle()
    val mineState by mine.state.collectAsStateWithLifecycle()

    var filter by rememberSaveable { mutableStateOf(if (canCreate) TaskFilter.Open.name else TaskFilter.Mine.name) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val current = TaskFilter.valueOf(filter)
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var actionError by remember { mutableStateOf<String?>(null) }

    fun reload() { all.refresh(); mine.refresh() }

    // «Мои» приходят своим запросом: на длинной доске страница обрывается раньше, чем доходит до твоей задачи.
    val source = if (current == TaskFilter.Mine) mineState else allState
    val shown = source.copy(
        data = source.data?.let { list ->
            sortTasks(
                when (current) {
                    TaskFilter.Open -> list.filter { !it.isDone }
                    TaskFilter.Done -> list.filter { it.isDone }
                    else -> list
                },
            )
        },
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                PillSegment(TaskFilter.entries.map { it to it.title }, current) { filter = it.name }
            }
            actionError?.let { Box(Modifier.padding(horizontal = Spacing.lg)) { ErrorBanner(it) } }
            StateContainer(
                state = shown,
                onRetry = ::reload,
                modifier = Modifier.weight(1f),
                isEmpty = { it.isEmpty() },
                emptyIcon = Icons.Filled.Verified,
                emptyTitle = when (current) {
                    TaskFilter.Mine -> "На вас ничего не висит"
                    TaskFilter.Done -> "Готовых задач нет"
                    else -> "Задач нет"
                },
                emptyMessage = when (current) {
                    TaskFilter.Mine -> "Поручения, назначенные лично вам, появятся здесь."
                    TaskFilter.Open -> "Всё разобрано."
                    else -> "Здесь появятся задачи команды."
                },
            ) { tasks ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    items(tasks, key = { it.id }, contentType = { "task" }) { task ->
                        TaskRow(task, onComplete = if (canComplete && !task.isDone) {
                            {
                                haptics.success()
                                scope.launch {
                                    runCatching { env.api.sendUnit(ApiRequest.json(TASKS_PATH, taskStatusBody(task.id, TaskState.Done))) }
                                        .onSuccess { actionError = null; reload() }
                                        .onFailure { actionError = it.userMessage(); haptics.error() }
                                }
                            }
                        } else null) { detailId = task.id }
                    }
                }
            }
        }
        if (canCreate) {
            BoxScopeFab(Modifier.align(Alignment.BottomEnd), description = "Новая задача") { adding = true }
        }
    }

    val detail = (allState.data.orEmpty() + mineState.data.orEmpty()).firstOrNull { it.id == detailId }
    if (detail != null) {
        TaskDetailSheet(detail, onDismiss = { detailId = null }, onChanged = ::reload)
    }
    if (adding) {
        TaskEditorSheet(task = null, onDismiss = { adding = false }, onSaved = { adding = false; reload() })
    }
}

/** Строка задачи: состояние иконкой в кружке, заголовок, срок, чек-лист и комментарии. */
@Composable
private fun TaskRow(task: OpsTask, onComplete: (() -> Unit)?, onClick: () -> Unit) {
    val overdue = task.isOverdue()
    val tint = when {
        task.isDone -> Orda.colors.positive
        overdue -> Orda.colors.negative
        task.isUrgent -> Orange
        else -> Violet
    }
    val icon: ImageVector = when {
        task.isDone -> Icons.Filled.CheckCircle
        overdue -> Icons.Filled.Warning
        task.isUrgent -> Icons.Filled.LocalFireDepartment
        else -> Icons.Filled.Circle
    }
    val facts = buildList {
        shortDay(task.dueDate)?.let { add(if (overdue) "$it · просрочена" else it) }
        if (task.checklist.isNotEmpty()) add("${task.doneCount}/${task.checklist.size}")
        if (task.commentsCount > 0) add("${task.commentsCount} ${plural(task.commentsCount, "комментарий", "комментария", "комментариев")}")
    }.joinToString(" · ")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OpsRow(
                icon = icon, tint = tint, title = task.title, subtitle = facts.ifEmpty { null },
                trailing = if (task.isUrgent && !task.isDone) "срочно" else null,
                trailingColor = Orda.colors.negative, strike = task.isDone, onClick = onClick,
            )
        }
        if (onComplete != null) {
            IconButton(onClick = onComplete) {
                Icon(Icons.Outlined.CheckCircle, "Завершить", tint = Orda.colors.positive)
            }
        }
    }
}

// ── Карточка задачи ──────────────────────────────────────────────────────────

/** Карточка задачи: завершить, шапка, чек-лист, обсуждение; правка и удаление — по правам. */
@Composable
private fun TaskDetailSheet(task: OpsTask, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val env = LocalBusiness.current
    val canComplete = env.can("tasks.complete") && !task.isDone
    val canEdit = env.can("tasks.edit")
    val canComment = env.can("tasks.add_comment")
    val canDelete = env.can("tasks.delete")
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<OpsTaskComment>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var commentError by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    suspend fun loadComments() {
        runCatching {
            env.api.send<JsonObject>(ApiRequest(TASKS_PATH, query = mapOf("comments" to "1", "taskId" to task.id)))
                .items("comments").map(OpsTaskComment::parse)
        }.onSuccess { comments = it }
    }
    LaunchedEffect(task.id, canComment) { if (canComment) loadComments() }

    val overdue = task.isOverdue()
    OrdaSheet(task.number?.let { "Задача №$it" } ?: "Задача", onDismiss) {
        submit.error?.let { ErrorBanner(it) }
        if (canComplete) {
            PrimaryButton("Завершить задачу", Modifier.fillMaxWidth(), loading = submit.busy) {
                submit.run(
                    { env.api.sendUnit(ApiRequest.json(TASKS_PATH, taskStatusBody(task.id, TaskState.Done))) },
                    { haptics.success(); onChanged(); onDismiss() },
                )
            }
        }
        OrdaCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.statusLabel,
                    color = if (task.isDone) Orda.colors.positive else if (overdue) Orda.colors.negative else Orda.colors.textDim,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                if (canEdit) IconButton(onClick = { editing = true }, Modifier.size(36.dp)) { Icon(Icons.Filled.Edit, "Изменить", tint = Orda.colors.brand) }
                if (canDelete) IconButton(onClick = { confirmDelete = true }, Modifier.size(36.dp)) { Icon(Icons.Filled.Delete, "Удалить", tint = Orda.colors.negative) }
            }
            Text(task.title, color = Orda.colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            task.details?.takeIf { it.isNotBlank() }?.let { Text(it, color = Orda.colors.textMuted, fontSize = 15.sp) }
            SoftDivider()
            StatRow("Приоритет", task.priorityLabel, valueColor = if (task.isUrgent) Orda.colors.negative else Orda.colors.text)
            longDay(task.dueDate)?.let { StatRow("Срок", it, valueColor = if (overdue) Orda.colors.negative else Orda.colors.text) }
        }
        if (task.checklist.isNotEmpty()) {
            OpsSection("Чек-лист", count = "${task.doneCount} из ${task.checklist.size}") {
                task.checklist.forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Icon(
                            if (item.isDone) Icons.Filled.CheckCircle else Icons.Filled.Circle, null,
                            tint = if (item.isDone) Orda.colors.positive else Orda.colors.textDim, modifier = Modifier.size(22.dp),
                        )
                        Text(
                            item.text, color = if (item.isDone) Orda.colors.textDim else Orda.colors.text, fontSize = 15.sp,
                            textDecoration = if (item.isDone) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                        )
                    }
                }
            }
        }
        if (canComment) {
            OpsSection("Обсуждение", count = if (comments.isEmpty()) "пока пусто" else "${comments.size} ${plural(comments.size, "запись", "записи", "записей")}") {
                comments.forEach { c ->
                    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                        Row {
                            Text(c.authorLabel, color = Orda.colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            dateTimeLabel(c.createdAt)?.let { Text(it, color = Orda.colors.textDim, fontSize = 12.sp) }
                        }
                        Text(c.content, color = Orda.colors.text, fontSize = 15.sp)
                    }
                }
                OpsField("Написать по задаче", draft, { draft = it }, minLines = 1)
                commentError?.let { Text(it, color = Orda.colors.negative, fontSize = 12.sp) }
                PrimaryButton("Отправить", Modifier.fillMaxWidth(), enabled = draft.isNotBlank(), loading = sending) {
                    val text = draft.trim()
                    sending = true
                    scope.launch {
                        runCatching { env.api.sendUnit(ApiRequest.json(TASKS_PATH, taskCommentBody(task.id, text))) }
                            .onSuccess { draft = ""; commentError = null; haptics.success(); loadComments(); onChanged() }
                            .onFailure { commentError = it.userMessage(); haptics.error() }
                        sending = false
                    }
                }
            }
        }
    }
    if (editing) {
        TaskEditorSheet(task = task, onDismiss = { editing = false }, onSaved = { editing = false; onChanged(); onDismiss() })
    }
    if (confirmDelete) {
        OpsConfirm(
            "Удалить задачу?", "Вместе с ней исчезнет и переписка по задаче.", "Удалить",
            onConfirm = {
                confirmDelete = false
                submit.run({ env.api.sendUnit(ApiRequest.json(TASKS_PATH, taskDeleteBody(task.id))) }, { onChanged(); onDismiss() })
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

// ── Постановка и правка ──────────────────────────────────────────────────────

/**
 * Лист «Новая задача» ([task] = null) и «Изменить задачу». Задачу вспоминают на
 * обходе точки — форма короткая. Правка шлёт только изменённое: пропущенный ключ
 * сервер читает как «не трогать».
 */
@Composable
private fun TaskEditorSheet(task: OpsTask?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val env = LocalBusiness.current
    val companies = rememberCompanyOptions()
    val canAssign = task != null || env.can("tasks.assign")
    val operators = rememberOperators(canAssign)
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()

    var title by rememberSaveable { mutableStateOf(task?.title.orEmpty()) }
    var details by rememberSaveable { mutableStateOf(task?.details.orEmpty()) }
    var priority by rememberSaveable { mutableStateOf(TaskPriority.of(task?.priority).raw) }
    var status by rememberSaveable { mutableStateOf(if (task != null) TaskState.of(task.status).raw else TaskState.Todo.raw) }
    var company by rememberSaveable { mutableStateOf(task?.companyId.orEmpty()) }
    var assignee by rememberSaveable { mutableStateOf(task?.operatorId.orEmpty()) }
    var due by rememberSaveable { mutableStateOf(parseDay(task?.dueDate)?.toString().orEmpty()) }
    LaunchedEffect(companies) { if (company.isEmpty() && companies.size == 1) company = companies[0].id }

    val draft = TaskDraft(
        title = title, details = details, priority = TaskPriority.of(priority), status = TaskState.of(status),
        companyId = company, operatorId = assignee.ifEmpty { null }, dueDate = due.ifEmpty { null },
    )
    val blocker = if (task == null) draft.validationMessage else if (title.isBlank()) "Название задачи обязательно" else null

    OrdaSheet(if (task == null) "Новая задача" else "Изменить задачу", onDismiss) {
        OpsField("Что сделать", title, { title = it }, placeholder = "Например: заменить лампу в зале", minLines = 1)
        OpsField("Подробности", details, { details = it }, placeholder = "Необязательно", minLines = 2)
        if (task == null) {
            OpsPicker("Точка", listOf("" to "Не выбрана") + companies.map { it.id to it.name }, company, { company = it })
        }
        Text("Срочность", color = Orda.colors.textDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        PillSegment(TaskPriority.entries.map { it.raw to it.title }, priority) { priority = it }
        if (canAssign) {
            // Без исполнителя задача попадает в общий список точки — нормальный случай, а не пропуск.
            OpsPicker("Исполнитель", listOf("" to (if (task == null) "Не назначен" else "Без исполнителя")) + operators.map { it.id to it.name }, assignee, { assignee = it })
        }
        OpsDateField(if (task == null) "Срок" else "Срок до", due, { due = it })
        val states = (TaskState.selectable + listOfNotNull(TaskState.entries.firstOrNull { it.raw == status && it !in TaskState.selectable }))
        OpsPicker(if (task == null) "Начальное состояние" else "Состояние", states.map { it.raw to it.title }, status, { status = it })

        blocker?.let { Text(it, color = Orda.colors.warning, fontSize = 14.sp) }
        submit.error?.let { ErrorBanner(it) }
        PrimaryButton(
            if (task == null) "Поставить задачу" else "Сохранить", Modifier.fillMaxWidth(),
            enabled = blocker == null, loading = submit.busy,
        ) {
            val body = if (task == null) draft.body() else TaskPatch(
                title = title.trim(), description = details.trim(), priority = priority, status = status,
                // Пустая строка — «снять»: сервер отличает её от непереданного ключа.
                operatorId = assignee, dueDate = due,
            ).body(task.id)
            submit.run({ env.api.sendUnit(ApiRequest.json(TASKS_PATH, body)) }, { haptics.success(); onSaved() })
        }
    }
}

// ── «Мои задачи» на главной и в профиле ──────────────────────────────────────

/**
 * Карточка «Мои задачи»: открытые поручения, назначенные лично человеку. Ошибку не
 * показываем — у того, кому ничего не поручают, карточки просто нет. Тап ведёт к
 * ответу: «принял», «нужны уточнения», «не могу», «сделано».
 */
@Composable
fun MyTasksCard() {
    val loader = rememberOpsLoader("tasks-mine-card", ApiRequest(TASKS_PATH, query = mapOf("mine" to "1", "page_size" to "100")), ::parseTasks)
    val state by loader.state.collectAsStateWithLifecycle()
    var answeringId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = remember(state.data) {
        state.data.orEmpty()
            .filter { !it.isDone && it.status != "cancelled" && it.status != "canceled" }
            .sortedWith(compareByDescending<OpsTask> { it.isOverdue() }.thenBy { parseDay(it.dueDate) == null }.thenBy { parseDay(it.dueDate) ?: java.time.LocalDate.MAX })
    }
    if (open.isEmpty()) return
    OpsSection("Мои задачи", count = "${open.size} ${plural(open.size, "открытая", "открытые", "открытых")}") {
        open.take(5).forEachIndexed { index, task ->
            if (index > 0) SoftDivider()
            TaskRow(task, onComplete = null) { answeringId = task.id }
        }
        if (open.size > 5) Text("и ещё ${open.size - 5} — в разделе «Задачи»", color = Orda.colors.textDim, fontSize = 12.sp)
    }
    open.firstOrNull { it.id == answeringId }?.let { task ->
        MyTaskAnswerSheet(task, onDismiss = { answeringId = null }, onDone = { loader.refresh() })
    }
}

private fun TaskResponse.sheetIcon(): ImageVector = when (this) {
    TaskResponse.Accept -> Icons.Filled.PlayCircle
    TaskResponse.NeedInfo -> Icons.Filled.Help
    TaskResponse.Blocked -> Icons.Filled.ReportProblem
    TaskResponse.AlreadyDone -> Icons.Filled.CheckCircle
    TaskResponse.Complete -> Icons.Filled.Send
}

/** Ответ исполнителя по своей задаче. Статус из ответа выводит сервер и пишет комментарий в историю. */
@Composable
private fun MyTaskAnswerSheet(task: OpsTask, onDismiss: () -> Unit, onDone: () -> Unit) {
    val env = LocalBusiness.current
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf<TaskResponse?>(null) }
    OrdaSheet("Ответить", onDismiss) {
        OrdaCard {
            Text("Задача", color = Orda.colors.textDim, fontSize = 13.sp)
            Text(task.title, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            task.details?.takeIf { it.isNotBlank() }?.let { Text(it, color = Orda.colors.textMuted, fontSize = 13.sp) }
        }
        OpsField("Комментарий", note, { note = it }, placeholder = "Необязательно")
        submit.error?.let { ErrorBanner(it) }
        // «Готово» — главный ответ, остальные вторичны: чаще всего лист открывают, чтобы закрыть поручение.
        listOf(TaskResponse.Complete) .plus(TaskResponse.entries.filter { it != TaskResponse.Complete }).forEach { response ->
            val primary = response == TaskResponse.Complete
            val send = {
                sending = response
                submit.run(
                    { env.api.sendUnit(ApiRequest.json(TASKS_PATH, taskRespondBody(task.id, response.raw, note.trim()))) },
                    { haptics.success(); onDone(); onDismiss() },
                )
            }
            if (primary) {
                PrimaryButton(response.title, Modifier.fillMaxWidth(), enabled = !submit.busy, loading = submit.busy && sending == response) { send() }
            } else {
                TextButton(onClick = { send() }, enabled = !submit.busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(response.sheetIcon(), null, Modifier.size(20.dp))
                    Text(response.title, Modifier.padding(start = Spacing.sm))
                }
            }
        }
    }
}
