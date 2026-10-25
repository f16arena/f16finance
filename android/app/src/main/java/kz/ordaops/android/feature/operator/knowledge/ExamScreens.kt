package kz.ordaops.android.feature.operator.knowledge

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kz.ordaops.android.feature.operator.common.operatorViewModel
import kz.ordaops.android.feature.operator.work.AccentCard
import kz.ordaops.android.feature.operator.work.CenteredContent
import kz.ordaops.android.feature.operator.work.NoteField
import kz.ordaops.android.feature.operator.work.SecondaryButton
import kz.ordaops.android.feature.operator.work.SectionTitle
import kz.ordaops.android.feature.operator.work.pluralize
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.WavyProgress
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ── Модели: OperatorExams.swift ──────────────────────────────────────────────
//
// Аттестация жила только в Telegram; приложение — вторая дверь к тому же
// экзамену. Билет и подсчёт общие, сдать дважды нельзя. Правильные ответы
// сервер не отдаёт: одного скриншота хватило бы, чтобы билет ушёл следующему.

@Serializable
@Immutable
data class ExamQuestion(
    val index: Int = 0,
    val text: String = "",
    /** `choice` — варианты, `open` — развёрнутый ответ. */
    val type: String = "choice",
    val choices: List<String> = emptyList(),
    @SerialName("max_score") val maxScore: Double = 1.0,
) {
    val isOpen: Boolean get() = type == "open"
}

/** Экзамен в списке; в ответе по одной попытке сюда же приходит текущий вопрос. */
@Serializable
@Immutable
data class OperatorExam(
    val id: String,
    val title: String = "Экзамен",
    val status: String = "pending",
    /** Можно ли отвечать прямо сейчас. */
    @SerialName("is_open") val isOpen: Boolean = false,
    @SerialName("deadline_at") val deadlineAt: String? = null,
    @SerialName("pass_score") val passScore: Double = 70.0,
    @SerialName("total_questions") val totalQuestions: Int = 0,
    val answered: Int = 0,
    @SerialName("current_index") val currentIndex: Int = 0,
    val score: Double? = null,
    val passed: Boolean? = null,
    @SerialName("has_open") val hasOpenQuestions: Boolean = false,
    /** Есть развёрнутые ответы, которых руководитель ещё не смотрел. */
    @SerialName("awaiting_review") val awaitingReview: Boolean = false,
    @SerialName("completed_at") val completedAt: String? = null,
    val question: ExamQuestion? = null,
) {
    /** Остаток, а не «отвечено 3 из 10»: человек всё равно переводит в «ещё семь». */
    val remaining: Int get() = maxOf(0, totalQuestions - answered)
    val isCompleted: Boolean get() = status == "completed"

    val deadline: Instant?
        get() = deadlineAt?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    val isOverdue: Boolean get() = isOpen && deadline?.isBefore(Instant.now()) == true
}

@Serializable
internal data class ExamListResponse(val data: List<OperatorExam> = emptyList())

@Serializable
internal data class ExamAttemptResponse(val data: OperatorExam? = null)

private fun percent(value: Double?): String {
    if (value == null || !value.isFinite()) return "—"
    val rounded = Math.round(value * 10) / 10.0
    val text = if (rounded == Math.floor(rounded)) rounded.toInt().toString() else String.format(Locale.US, "%.1f", rounded).replace('.', ',')
    return "$text %"
}

private val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale("ru")).withZone(ZoneId.systemDefault())

// ── Список ───────────────────────────────────────────────────────────────────

/**
 * Экзамены оператора — `ExamsScreen`. Вопрос показывается по одному, вернуться
 * к предыдущему нельзя — иначе оценка перестаёт что-либо значить.
 */
@Composable
fun ExamsScreen(showTitle: Boolean = true) {
    val vm = operatorViewModel { env -> ExamsViewModel(env) }
    val state by vm.list.collectAsStateWithLifecycle()
    // Открытый экзамен — идентификатором в saveable: переживёт и поворот, и пересоздание процесса.
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }

    val current = openId?.let { id -> state.data?.firstOrNull { it.id == id } }
    if (current != null) {
        val leave = {
            openId = null
            vm.closeExam()
            // Вернулись из экзамена — список перечитываем: прогресс изменился.
            vm.refresh()
        }
        BackHandler(onBack = leave)
        ExamRunnerScreen(vm, current.id, current.title, onBack = leave)
        return
    }

    Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
        if (showTitle) SectionTitle("Экзамены")
        StateContainer(
            state = state,
            onRetry = vm::refresh,
            modifier = Modifier.weight(1f),
            isEmpty = { it.isEmpty() },
            emptyIcon = Icons.Filled.School,
            emptyTitle = "Экзаменов нет",
            emptyMessage = "Когда руководитель назначит аттестацию, она появится здесь.",
        ) { exams ->
            val open = remember(exams) { exams.filter { it.isOpen } }
            val finished = remember(exams) { exams.filter { !it.isOpen } }
            CenteredContent {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    if (open.isNotEmpty()) {
                        item(key = "h-open", contentType = "label") { SectionLabel("Сдать", "вопросы задаются по одному") }
                        items(open, key = { it.id }, contentType = { "exam" }) { exam ->
                            OrdaCard { ExamRow(exam, Modifier.clickable { openId = exam.id }) }
                        }
                    }
                    if (finished.isNotEmpty()) {
                        item(key = "h-done", contentType = "label") { SectionLabel("Сданные", null) }
                        items(finished, key = { it.id }, contentType = { "exam-done" }) { exam ->
                            OrdaCard { ExamRow(exam, Modifier) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String, subtitle: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Bottom) {
        Text(title, color = Orda.colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) Text(subtitle, color = Orda.colors.textDim, fontSize = 13.sp)
    }
}

/** Строка экзамена: название, срок, результат. */
@Composable
private fun ExamRow(exam: OperatorExam, modifier: Modifier) {
    val accent = when {
        exam.isCompleted -> if (exam.passed == true) Orda.colors.positive else Orda.colors.negative
        exam.isOverdue -> Orda.colors.negative
        else -> Orda.colors.brand
    }
    val subtitle = if (exam.isCompleted) {
        // Развёрнутые ответы проверяет человек — обещать окончательный балл сразу было бы неправдой.
        when {
            exam.awaitingReview -> "Ответы проверяет руководитель"
            exam.passed == true -> "Сдан"
            else -> "Не сдан, порог ${percent(exam.passScore)}"
        }
    } else {
        buildList {
            add("осталось ${exam.remaining} из ${exam.totalQuestions}")
            exam.deadline?.let { add(if (exam.isOverdue) "срок вышел" else "до ${dayFormat.format(it)}") }
        }.joinToString(" · ")
    }
    Row(
        modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).background(accent.copy(alpha = 0.12f), RoundedCornerShape(Radius.sm)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (exam.isCompleted) Icons.Filled.Verified else Icons.Filled.School,
                contentDescription = null, tint = accent, modifier = Modifier.size(17.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(exam.title, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(subtitle, color = if (exam.isOverdue) Orda.colors.negative else Orda.colors.textDim, fontSize = 13.sp)
        }
        if (exam.isCompleted && exam.score != null) {
            Text(
                percent(exam.score),
                color = if (exam.passed == true) Orda.colors.positive else Orda.colors.negative,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Orda.colors.textDim, modifier = Modifier.size(18.dp))
        }
    }
}

// ── Сдача ────────────────────────────────────────────────────────────────────

/** Сдача экзамена: один вопрос на экране — `ExamRunnerScreen`. */
@Composable
private fun ExamRunnerScreen(vm: ExamsViewModel, examId: String, title: String, onBack: () -> Unit) {
    val attempt by vm.attempt.collectAsStateWithLifecycle()
    val send by vm.send.collectAsStateWithLifecycle()

    LaunchedEffect(examId) { vm.openExam(examId) }

    Column(Modifier.fillMaxSize().background(Orda.colors.background).imePadding()) {
        SectionTitle(title, onBack = onBack)
        StateContainer(
            state = attempt,
            onRetry = vm::retry,
            onRefresh = null,
            modifier = Modifier.weight(1f),
        ) { current ->
            CenteredContent {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    val question = current.question
                    if (question != null && current.isOpen) {
                        ProgressCard(current)
                        QuestionCard(examId, question, send.isSending, send.error) { choice, text ->
                            vm.answer(question.index, choice, text)
                        }
                    } else {
                        ResultCard(current, onBack)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(exam: OperatorExam) {
    OrdaCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Вопрос ${exam.currentIndex + 1} из ${exam.totalQuestions}",
                color = Orda.colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            )
            Text("порог ${percent(exam.passScore)}", color = Orda.colors.textDim, fontSize = 13.sp)
        }
        WavyProgress(
            progress = { exam.answered.toFloat() / maxOf(1, exam.totalQuestions) },
            modifier = Modifier.fillMaxWidth(),
            color = Orda.colors.brand,
            trackColor = Orda.colors.surfaceRaised,
        )
        // Вернуться к предыдущему вопросу нельзя, и человек должен знать это до нажатия, а не после.
        Text("Ответ засчитывается сразу — вернуться к вопросу нельзя.", color = Orda.colors.textDim, fontSize = 13.sp)
    }
}

@Composable
private fun QuestionCard(
    examId: String,
    question: ExamQuestion,
    isSending: Boolean,
    sendError: String?,
    onAnswer: (choice: Int?, text: String?) -> Unit,
) {
    // Набранный развёрнутый ответ не должен пропасть на повороте или при выгрузке приложения.
    var openAnswer by rememberSaveable(examId, question.index) { mutableStateOf("") }
    OrdaCard {
        Text(question.text, color = Orda.colors.text, fontSize = 16.sp)
        if (question.isOpen) {
            val max = question.maxScore.toInt()
            Text(
                "Развёрнутый ответ · до $max ${pluralize(max, "балла", "баллов", "баллов")}",
                color = Orda.colors.textDim, fontSize = 13.sp,
            )
            NoteField(openAnswer, { openAnswer = it }, "Ваш ответ", minLines = 5, maxLines = 12)
            PrimaryButton(
                "Ответить",
                Modifier.fillMaxWidth(),
                enabled = openAnswer.trim().length >= 15,
                loading = isSending,
            ) { onAnswer(null, openAnswer) }
        } else {
            val letters = listOf("А", "Б", "В", "Г", "Д")
            question.choices.forEachIndexed { index, choice ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Orda.colors.surfaceRaised, RoundedCornerShape(Radius.sm))
                        .clickable(enabled = !isSending) { onAnswer(index, null) }
                        .padding(Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        Modifier.size(24.dp).background(Orda.colors.brand.copy(alpha = 0.12f), RoundedCornerShape(Radius.sm)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(letters.getOrElse(index) { "${index + 1}" }, color = Orda.colors.brand, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Text(choice, color = Orda.colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                }
            }
        }
        sendError?.let { Text(it, color = Orda.colors.negative, fontSize = 13.sp) }
    }
}

@Composable
private fun ResultCard(exam: OperatorExam, onDone: () -> Unit) {
    val color: Color = if (exam.passed == true) Orda.colors.positive else Orda.colors.negative
    AccentCard(color) {
        Text(if (exam.passed == true) "Экзамен сдан" else "Экзамен не сдан", color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        exam.score?.let { ResultLine("Результат", percent(it), bold = true) }
        ResultLine("Порог", percent(exam.passScore))
        if (exam.awaitingReview) {
            // Балл за развёрнутый ответ предлагает ИИ, а решает человек: окончательный итог обещать нельзя.
            Text("Развёрнутые ответы проверит руководитель — итог может измениться.", color = Orda.colors.textMuted, fontSize = 13.sp)
        }
    }
    // Правильные ответы не показываем: билет один на всех сдающих, и разбор утёк бы следующему.
    Text("Разбор ошибок — у руководителя. Он же скажет, что перечитать.", color = Orda.colors.textDim, fontSize = 13.sp)
    SecondaryButton("Готово", Modifier.fillMaxWidth(), onClick = onDone)
}

@Composable
private fun ResultLine(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Orda.colors.textMuted, fontSize = 14.sp)
        Text(value, color = Orda.colors.text, fontSize = 15.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}
