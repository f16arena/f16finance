package kz.ordaops.android.feature.business.team

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.operator.money.TintedIcon
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

// ── Модель: GET /api/admin/operator-exams ────────────────────────────────────

data class ExamOperator(val id: String, val name: String, val companyIds: List<String>, val hasTelegram: Boolean)

data class AdminExam(
    val id: String,
    val title: String,
    val companyIds: List<String>,
    val questionCount: Int,
    val passScore: Int,
    val deadlineAt: String?,
    val status: String,
    val assigned: Int,
    val completed: Int,
    val passed: Int,
    val averageScore: Int?,
) {
    val isSent: Boolean get() = status != "draft"

    /** Незнакомый статус лучше показать пустым, чем английским словом из базы. */
    val statusLabel: String
        get() = when (status) {
            "draft" -> "Черновик"
            "sent", "active" -> "Идёт"
            "closed", "finished" -> "Завершён"
            "cancelled" -> "Отменён"
            else -> ""
        }

    val isClosed: Boolean get() = status == "closed" || status == "cancelled"

    companion object {
        fun from(o: kotlinx.serialization.json.JsonObject) = AdminExam(
            id = o.str("id") ?: "",
            title = o.str("title") ?: "Экзамен",
            companyIds = (o["company_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
            questionCount = o.int("question_count"),
            passScore = if (o["pass_score"] == null) 70 else o.int("pass_score"),
            deadlineAt = o.str("deadline_at"),
            status = o.str("status") ?: "draft",
            assigned = o.int("assigned"),
            completed = o.int("completed"),
            passed = o.int("passed"),
            averageScore = o.dblOrNull("avg_score")?.toInt(),
        )
    }
}

data class ExamAttempt(
    val id: String,
    val operatorId: String,
    val status: String,
    val score: Int?,
    val passed: Boolean?,
    val correctAnswers: Int?,
    val totalQuestions: Int?,
) {
    val isFinished: Boolean get() = status in listOf("completed", "expired", "undeliverable")

    val statusLabel: String
        get() = when (status) {
            "pending" -> "Ждёт"
            "in_progress" -> "Сдаёт"
            "completed", "finished" -> if (passed == true) "Сдал" else "Не сдал"
            "expired" -> "Просрочен"
            "undeliverable" -> "Не доставлен"
            "cancelled" -> "Отменён"
            else -> ""
        }

    /** Пересдача нужна тому, кто закончил и не сдал. */
    val canRetake: Boolean get() = isFinished && passed != true
}

data class ExamsOverview(val exams: List<AdminExam>, val companies: List<ScheduleCompany>, val operators: List<ExamOperator>) {
    companion object {
        fun from(root: JsonElement): ExamsOverview {
            val d = root.unwrapData().asObject()
            return ExamsOverview(
                exams = d?.get("exams").asObjects().map(AdminExam::from),
                companies = d?.get("companies").asObjects().map { ScheduleCompany(it.str("id") ?: "", it.str("name") ?: "Точка") },
                operators = d?.get("operators").asObjects().map {
                    ExamOperator(
                        it.str("id") ?: "", it.str("name") ?: "Без имени",
                        (it["company_ids"] as? JsonArray)?.mapNotNull { e -> (e as? JsonPrimitive)?.content } ?: emptyList(),
                        !it.str("telegram_chat_id").isNullOrEmpty(),
                    )
                },
            )
        }
    }
}

data class ExamDetail(val exam: AdminExam, val attempts: List<ExamAttempt>) {
    companion object {
        fun from(root: JsonElement): ExamDetail {
            val d = root.unwrapData().asObject()
            return ExamDetail(
                exam = d?.get("exam").asObject()?.let(AdminExam::from) ?: AdminExam("", "Экзамен", emptyList(), 0, 70, null, "draft", 0, 0, 0, null),
                attempts = d?.get("attempts").asObjects().map {
                    ExamAttempt(
                        it.str("id") ?: "", it.str("operator_id") ?: "", it.str("status") ?: "pending",
                        it.dblOrNull("score")?.toInt(), (it["passed"] as? JsonPrimitive)?.content?.let { c -> c == "true" },
                        it.dblOrNull("correct_answers")?.toInt(), it.dblOrNull("total_questions")?.toInt(),
                    )
                },
            )
        }
    }
}

/** Назначение экзамена: что нужно проверить до отправки. */
data class ExamAssignment(
    val title: String = "",
    val companyIds: List<String> = emptyList(),
    val operatorIds: List<String> = emptyList(),
    val questionCount: Int = 10,
    val openCount: Int = 2,
    val passScore: Int = 70,
) {
    val validationIssue: String?
        get() = when {
            title.isBlank() -> "Укажите название экзамена"
            companyIds.isEmpty() -> "Выберите хотя бы одну точку"
            operatorIds.isEmpty() -> "Выберите операторов"
            else -> null
        }
}

/** Операторы выбранных точек: чужой точки в списке быть не должно — сервер такой экзамен отвергнет. */
fun ExamsOverview.operatorsFor(companyIds: List<String>): List<ExamOperator> {
    if (companyIds.isEmpty()) return emptyList()
    val picked = companyIds.toSet()
    return operators.filter { op -> op.companyIds.any { it in picked } }
}

private suspend fun ApiClient.examAction(body: kotlinx.serialization.json.JsonObject) =
    sendUnit(ApiRequest.json("/api/admin/operator-exams", body))

// ── Экраны ───────────────────────────────────────────────────────────────────

private val ExamPurple = Color(0xFF4F46E5)

/** Экзамены операторов: список, разбор билетов, назначение. Порт `AdminExamsScreen`. */
@Composable
fun AdminExamsScreen() {
    val env = LocalBusiness.current
    val vm = rememberResource("exams", Unit, { ApiRequest("/api/admin/operator-exams") }, ExamsOverview::from)
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var assigning by remember { mutableStateOf(false) }
    val canCreate = env.can("operator-exams.create")

    val selected = state.data?.exams?.firstOrNull { it.id == selectedId }
    if (selected != null) {
        BackHandler { selectedId = null; vm.reload() }
        ExamDetailScreen(selected.id, state.data?.operators.orEmpty())
        return
    }
    StateContainer(
        state = state, onRetry = vm::reload,
        isEmpty = { it.exams.isEmpty() && !canCreate },
        emptyIcon = Icons.Filled.School, emptyTitle = "Экзаменов пока нет",
        emptyMessage = "Вопросы собираются из базы знаний вашей точки — списать со стороны нельзя.",
    ) { overview ->
        val c = Orda.colors
        OwnerScreenColumn {
            if (canCreate) {
                Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text("Экзамены", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { assigning = true }) { Icon(Icons.Filled.Add, "Новый экзамен", tint = c.brand) }
                }
            }
            if (overview.exams.isEmpty()) {
                TeamHint("Экзаменов пока нет. Вопросы собираются из базы знаний вашей точки.")
            }
            overview.exams.forEach { exam -> ExamCard(exam, overview.companies) { selectedId = exam.id } }
        }
        if (assigning) AssignExamSheet(overview, onDismiss = { assigning = false }, onDone = { vm.refreshNow() })
    }
}

@Composable
private fun ExamCard(exam: AdminExam, companies: List<ScheduleCompany>, onClick: () -> Unit) {
    val c = Orda.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(c.surface, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        TintedIcon(Icons.Filled.School, if (exam.isSent) ExamPurple else c.textDim)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(exam.title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, modifier = Modifier.weight(1f))
                Text(exam.statusLabel, color = if (exam.isSent) c.positive else c.warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            val names = exam.companyIds.mapNotNull { id -> companies.firstOrNull { it.id == id }?.name }
            Text(if (names.isEmpty()) "Точка не указана" else names.joinToString(", "), color = c.textDim, fontSize = 13.sp, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                ExamStat("Назначено", "${exam.assigned}")
                ExamStat("Сдали", "${exam.passed} из ${exam.completed}")
                exam.averageScore?.let { ExamStat("Средний", "$it%") }
            }
            exam.deadlineAt?.let { Text("Срок: " + WeekMath.dateTime(it), color = if ((WeekMath.hoursSince(it) ?: -1) > 0) c.warning else c.textMuted, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ExamStat(title: String, value: String) {
    Column {
        Text(title, color = Orda.colors.textDim, fontSize = 12.sp)
        Text(value, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ExamDetailScreen(examId: String, operators: List<ExamOperator>) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    val vm = rememberResource("exam:$examId", examId, { ApiRequest("/api/admin/operator-exams", query = mapOf("id" to it)) }, ExamDetail::from)
    val state by vm.state.collectAsStateWithLifecycle()
    var closing by remember { mutableStateOf<Boolean?>(null) }
    val canSend = env.can("operator-exams.create")
    val canRemind = env.can("operator-exams.remind")
    val canFinish = env.can("operator-exams.cancel")
    val c = Orda.colors

    StateContainer(state = state, onRetry = vm::reload) { detail ->
        val exam = detail.exam
        val canClose = canFinish && !exam.isClosed
        fun run(block: suspend () -> Unit) = action.launch(scope, onSuccess = { vm.refreshNow() }, block = block)

        OwnerScreenColumn {
            Text(exam.title, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = Spacing.sm))
            TeamHeroCard(
                "Сдали", "${exam.passed} из ${exam.completed}",
                listOf(
                    "Назначено" to "${exam.assigned}",
                    "Средний балл" to (exam.averageScore?.let { "$it%" } ?: "—"),
                    "Статус" to exam.statusLabel.ifEmpty { "—" },
                ),
            )
            Text("${exam.questionCount} вопросов, порог ${exam.passScore}%", color = c.textDim, fontSize = 13.sp)

            if ((!exam.isSent && canSend) || (exam.isSent && canRemind) || canClose) {
                OwnerSection("Действия") {
                    TeamError(action.error)
                    if (!exam.isSent && canSend) {
                        // Черновик существует, чтобы посмотреть вопросы до рассылки. Отозвать разосланный билет уже нельзя.
                        TeamActionRow(Icons.Filled.Send, "Разослать билеты", ExamPurple, action.busy) { run { env.api.examAction(buildJsonObject { put("action", "send"); put("exam_id", exam.id) }) } }
                    } else if (exam.isSent && canRemind) {
                        TeamActionRow(Icons.Filled.Notifications, "Напомнить не сдавшим", Color(0xFFF59E0B), action.busy) { run { env.api.examAction(buildJsonObject { put("action", "remind"); put("exam_id", exam.id) }) } }
                    }
                    // Завершение — норма: все сдали, ждать остальных незачем. Отмена — ошиблись при назначении. Оба оставляют экзамен в истории.
                    if (canClose) {
                        TeamActionRow(Icons.Filled.CheckCircle, "Завершить", c.positive, action.busy) { closing = false }
                        TeamActionRow(Icons.Filled.Cancel, "Отменить экзамен", c.negative, action.busy) { closing = true }
                    }
                }
            }

            if (detail.attempts.isEmpty()) {
                OwnerSection("Билеты") { TeamHint("Билеты не разосланы. Пока экзамен черновик, его видите только вы.") }
            } else {
                OwnerSection("Билеты", trailing = {
                    Text("${detail.attempts.size} ${plural(detail.attempts.size, "человек", "человека", "человек")}", color = c.textDim, fontSize = 13.sp)
                }) {
                    detail.attempts.forEachIndexed { i, a ->
                        if (i > 0) TeamDivider()
                        val person = operators.firstOrNull { it.id == a.operatorId }?.name ?: "Оператор"
                        val tint = when (a.status) {
                            "completed" -> if (a.passed == true) c.positive else c.negative
                            "expired", "undeliverable" -> c.warning
                            else -> c.info
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            PersonInitial(person, true, 40)
                            Column(Modifier.weight(1f)) {
                                Text(person, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    Text(a.statusLabel, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    if (a.correctAnswers != null && (a.totalQuestions ?: 0) > 0) Text("· ${a.correctAnswers} из ${a.totalQuestions}", color = c.textDim, fontSize = 13.sp)
                                }
                            }
                            a.score?.let { Text("$it%", color = tint, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                            // Пересдача собирает новый билет: тот же список вопросов человек уже видел.
                            if (a.canRetake && canSend) {
                                IconButton(onClick = { run { env.api.examAction(buildJsonObject { put("action", "retake"); put("attempt_id", a.id) }) } }, enabled = !action.busy) {
                                    Icon(Icons.Filled.Refresh, "Пересдача", tint = Color(0xFF3B82F6))
                                }
                            }
                        }
                    }
                }
            }
        }

        closing?.let { cancelled ->
            ConfirmDialog(
                if (cancelled) "Отменить экзамен?" else "Завершить экзамен?",
                if (cancelled) "Незавершённые билеты пропадут, результаты сдавших останутся в истории."
                else "Кто не успел — не успел: экзамен закроется, результаты сдавших останутся.",
                if (cancelled) "Отменить экзамен" else "Завершить", destructive = cancelled,
                onDismiss = { closing = null },
                onConfirm = {
                    closing = null
                    run { env.api.examAction(buildJsonObject { put("action", if (cancelled) "cancel" else "finish"); put("exam_id", exam.id) }) }
                },
            )
        }
    }
}

/** Назначение экзамена: название, точки, кому, условия, срок. Вопросы соберёт сервер из базы знаний точек. */
@Composable
private fun AssignExamSheet(overview: ExamsOverview, onDismiss: () -> Unit, onDone: suspend () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    var draft by remember { mutableStateOf(ExamAssignment()) }
    var hasDeadline by remember { mutableStateOf(true) }
    var deadline by remember { mutableStateOf(WeekMath.format(LocalDate.now().plusDays(3))) }
    val available = overview.operatorsFor(draft.companyIds)

    OrdaSheet("Новый экзамен", onDismiss = onDismiss) {
        TeamField("Название", draft.title, { draft = draft.copy(title = it) }, placeholder = "Аттестация по регламентам")
        Text("Точки · вопросы берутся из их базы знаний", color = Orda.colors.textDim, fontSize = 13.sp)
        overview.companies.forEach { company ->
            val on = company.id in draft.companyIds
            TeamChoiceRow(company.name, on) {
                val ids = if (on) draft.companyIds - company.id else draft.companyIds + company.id
                // Оператор чужой точки остаться не должен: сервер такой экзамен отвергнет.
                val allowed = overview.operatorsFor(ids).map { it.id }.toSet()
                draft = draft.copy(companyIds = ids, operatorIds = draft.operatorIds.filter { it in allowed })
            }
        }
        Text("Кому" + if (draft.operatorIds.isNotEmpty()) " · выбрано ${draft.operatorIds.size}" else "", color = Orda.colors.textDim, fontSize = 13.sp)
        if (available.isEmpty()) TeamHint("Сначала выберите точку")
        available.forEach { p ->
            val on = p.id in draft.operatorIds
            TeamChoiceRow(p.name, on, subtitle = if (p.hasTelegram) null else "только в приложении") {
                draft = draft.copy(operatorIds = if (on) draft.operatorIds - p.id else draft.operatorIds + p.id)
            }
        }
        Stepper("Вопросов", draft.questionCount, 3..20) { draft = draft.copy(questionCount = it) }
        Stepper("Из них с развёрнутым ответом", draft.openCount, 0..5) { draft = draft.copy(openCount = it) }
        Stepper("Порог сдачи, %", draft.passScore, 30..100, step = 5) { draft = draft.copy(passScore = it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Срок сдачи", color = Orda.colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = hasDeadline, onCheckedChange = { hasDeadline = it })
        }
        if (hasDeadline) TeamDateField("До", deadline) { deadline = it }
        TeamHint("Вопросы соберёт сервер из базы знаний выбранных точек. Развёрнутые ответы оценивает ИИ, последнее слово — за вами.")
        TeamError(action.error)
        SubmitButton("Назначить", action.busy, draft.validationIssue) {
            action.launch(scope, onSuccess = { onDone(); onDismiss() }) {
                env.api.examAction(buildJsonObject {
                    put("action", "create")
                    put("title", draft.title.trim())
                    put("company_ids", JsonArray(draft.companyIds.map(::JsonPrimitive)))
                    put("operator_ids", JsonArray(draft.operatorIds.map(::JsonPrimitive)))
                    put("question_count", draft.questionCount)
                    put("open_count", draft.openCount)
                    put("pass_score", draft.passScore)
                    if (hasDeadline) {
                        // Срок — конец выбранного дня по местному времени.
                        val day = WeekMath.parse(deadline) ?: LocalDate.now()
                        put("deadline_at", OffsetDateTime.of(day, LocalTime.of(23, 59), ZoneId.systemDefault().rules.getOffset(java.time.Instant.now())).toString())
                    }
                })
            }
        }
    }
}

@Composable
private fun Stepper(title: String, value: Int, range: IntRange, step: Int = 1, onChange: (Int) -> Unit) {
    val c = Orda.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.textMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange((value - step).coerceAtLeast(range.first)) }, enabled = value > range.first) { Text("−", color = c.brand, fontSize = 22.sp) }
        Text("$value", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = { onChange((value + step).coerceAtMost(range.last)) }, enabled = value < range.last) { Text("+", color = c.brand, fontSize = 22.sp) }
    }
}
