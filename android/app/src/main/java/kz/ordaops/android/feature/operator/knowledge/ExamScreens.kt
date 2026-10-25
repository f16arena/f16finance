package kz.ordaops.android.feature.operator.knowledge

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import kz.ordaops.android.ui.components.WavyProgress
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.feature.operator.work.AccentCard
import kz.ordaops.android.feature.operator.work.NoteField
import kz.ordaops.android.feature.operator.work.Refreshable
import kz.ordaops.android.feature.operator.work.SecondaryButton
import kz.ordaops.android.feature.operator.work.SectionTitle
import kz.ordaops.android.feature.operator.work.pluralize
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.PrimaryButton
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
private data class ExamListResponse(val data: List<OperatorExam> = emptyList())

@Serializable
private data class ExamAttemptResponse(val data: OperatorExam? = null)

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
    val api = LocalOperatorEnv.current.api
    val scope = rememberCoroutineScope()
    var exams by remember { mutableStateOf<List<OperatorExam>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var openId by remember { mutableStateOf<String?>(null) }
    val request = remember { ApiRequest("/api/operator/exams") }

    suspend fun load() {
        isLoading = true
        try {
            exams = api.send<ExamListResponse>(request).data
            loadError = null
        } catch (e: Exception) {
            loadError = e.userMessage()
        }
        isLoading = false
    }

    LaunchedEffect(Unit) {
        api.cached<ExamListResponse>(request)?.let { exams = it.data }
        load()
    }

    val current = openId?.let { id -> exams.firstOrNull { it.id == id } }
    if (current != null) {
        BackHandler { openId = null }
        // Вернулись из экзамена — список перечитываем: прогресс изменился.
        ExamRunnerScreen(current.id, current.title, onBack = {
            openId = null
            scope.launch { load() }
        })
        return
    }

    val open = exams.filter { it.isOpen }
    val finished = exams.filter { !it.isOpen }

    Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
        if (showTitle) SectionTitle("Экзамены")
        Refreshable(refreshing = isLoading && exams.isNotEmpty(), onRefresh = { scope.launch { load() } }) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                when {
                    loadError != null && exams.isEmpty() -> ErrorBanner(loadError.orEmpty())
                    isLoading && exams.isEmpty() -> Box(Modifier.fillMaxWidth().padding(Spacing.xxl), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Orda.colors.brand)
                    }
                    exams.isEmpty() -> EmptyState(
                        icon = Icons.Filled.School,
                        title = "Экзаменов нет",
                        message = "Когда руководитель назначит аттестацию, она появится здесь.",
                    )
                    else -> {
                        if (open.isNotEmpty()) {
                            OrdaCard {
                                SectionLabel("Сдать", "вопросы задаются по одному")
                                open.forEachIndexed { i, exam ->
                                    if (i > 0) HorizontalDivider(color = Orda.colors.border)
                                    ExamRow(exam, Modifier.clickable { openId = exam.id })
                                }
                            }
                        }
                        if (finished.isNotEmpty()) {
                            OrdaCard {
                                SectionLabel("Сданные", null)
                                finished.forEachIndexed { i, exam ->
                                    if (i > 0) HorizontalDivider(color = Orda.colors.border)
                                    ExamRow(exam, Modifier)
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
private fun ExamRunnerScreen(examId: String, title: String, onBack: () -> Unit) {
    val api = LocalOperatorEnv.current.api
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableStateOf<OperatorExam?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var openAnswer by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            isLoading = true
            try {
                attempt = api.send<ExamAttemptResponse>(ApiRequest("/api/operator/exams/$examId")).data
                loadError = null
            } catch (e: Exception) {
                loadError = e.userMessage()
            }
            isLoading = false
        }
    }

    LaunchedEffect(examId) { load() }

    /** Ответ на текущий вопрос; в ответе сразу следующее состояние, второй запрос не нужен. */
    fun answer(index: Int, choice: Int? = null, text: String? = null) {
        if (isSending) return
        scope.launch {
            isSending = true
            sendError = null
            try {
                val body = buildJsonObject {
                    put("index", index)
                    if (choice != null) put("choice", choice)
                    if (text != null) put("text", text)
                }
                attempt = api.send<ExamAttemptResponse>(ApiRequest.json("/api/operator/exams/$examId", body)).data
                openAnswer = ""
            } catch (e: Exception) {
                sendError = e.userMessage()
            }
            isSending = false
        }
    }

    Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
        SectionTitle(title, onBack = onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val current = attempt
            when {
                loadError != null && current == null -> {
                    ErrorBanner(loadError.orEmpty())
                    SecondaryButton("Повторить") { load() }
                }
                isLoading && current == null -> Box(Modifier.fillMaxWidth().padding(Spacing.xxl), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Orda.colors.brand)
                }
                current != null -> {
                    val question = current.question
                    if (question != null && current.isOpen) {
                        ProgressCard(current)
                        QuestionCard(question, isSending, sendError, openAnswer, { openAnswer = it }) { choice, text ->
                            answer(question.index, choice, text)
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
    question: ExamQuestion,
    isSending: Boolean,
    sendError: String?,
    openAnswer: String,
    onOpenAnswerChange: (String) -> Unit,
    onAnswer: (choice: Int?, text: String?) -> Unit,
) {
    OrdaCard {
        Text(question.text, color = Orda.colors.text, fontSize = 16.sp)
        if (question.isOpen) {
            val max = question.maxScore.toInt()
            Text(
                "Развёрнутый ответ · до $max ${pluralize(max, "балла", "баллов", "баллов")}",
                color = Orda.colors.textDim, fontSize = 13.sp,
            )
            NoteField(openAnswer, onOpenAnswerChange, "Ваш ответ", minLines = 5, maxLines = 12)
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
