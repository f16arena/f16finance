package kz.ordaops.android.feature.operator.work

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate

// Модели «Задач», «Чек-листов» и «Знаний» — OperatorCabinet.swift / OperatorShift.swift.
// У каждого поля есть значение по умолчанию: сервер добавляет и теряет ключи,
// и это не должно ронять экран.

// ── Задачи: /api/operator/tasks ──────────────────────────────────────────────

@Serializable
@Immutable
data class OperatorTask(
    val id: String,
    val title: String = "Задача",
    val description: String? = null,
    val status: String = "new",
    val priority: String = "medium",
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("company_name") val companyName: String? = null,
) {
    val isDone: Boolean get() = status == "done" || status == "completed"
    val isOnReview: Boolean get() = status == "review"

    /** Просрочена ли. Сравниваем по дате, а не по времени: срок задан днём. */
    val isOverdue: Boolean
        get() {
            val due = parseDateOnly(dueDate) ?: return false
            return !isDone && due.isBefore(LocalDate.now())
        }

    val isUrgent: Boolean get() = priority == "high" || priority == "urgent"
}

@Serializable
data class OperatorTaskList(val tasks: List<OperatorTask> = emptyList())

/** Дата без времени из «2026-10-25» или полного ISO. */
fun parseDateOnly(value: String?): LocalDate? {
    if (value.isNullOrBlank() || value.length < 10) return null
    return runCatching { LocalDate.parse(value.substring(0, 10)) }.getOrNull()
}

/** Что оператор может ответить по задаче — `TaskResponse`. Статус из ответа выводит сервер. */
enum class TaskResponse(val raw: String, val title: String) {
    Accept("accept", "Принять в работу"),
    NeedInfo("need_info", "Нужны уточнения"),
    Blocked("blocked", "Не могу выполнить"),
    AlreadyDone("already_done", "Уже сделано"),
    Complete("complete", "Отправить на проверку");

    /** «Не могу» и «нужны уточнения» без причины бесполезны — руководитель всё равно придёт спрашивать. */
    val needsNote: Boolean get() = this == NeedInfo || this == Blocked
}

// ── Чек-листы ────────────────────────────────────────────────────────────────

@Serializable
@Immutable
data class ChecklistTemplate(
    val id: String,
    val title: String = "Чек-лист",
    val description: String? = null,
    @SerialName("schedule_type") val scheduleType: String? = null,
    @SerialName("recurrence_minutes") val recurrenceMinutes: Int? = null,
    /** Обязательный: без него сервер не даст закрыть смену. */
    @SerialName("blocks_shift") val blocksShift: Boolean = false,
)

@Serializable
data class ChecklistRun(
    val id: String,
    @SerialName("template_id") val templateId: String = "",
    val status: String = "",
    @SerialName("fines_total") val finesTotal: Double? = null,
    @SerialName("bonuses_total") val bonusesTotal: Double? = null,
) {
    val isCompleted: Boolean get() = status == "completed"
}

@Serializable
@Immutable
data class ChecklistItem(
    val id: String,
    @SerialName("template_id") val templateId: String = "",
    val title: String = "Пункт",
    val description: String? = null,
    @SerialName("answer_type") val answerType: String = "yes_no",
    @SerialName("is_required") val isRequired: Boolean = false,
    @SerialName("requires_photo") val requiresPhoto: Boolean = false,
    @SerialName("fine_amount") val fineAmount: Double? = null,
    @SerialName("bonus_amount") val bonusAmount: Double? = null,
)

/** Ответ на пункт. Хранится и в очереди «ждёт связи», поэтому сериализуется. */
@Serializable
@Immutable
data class ChecklistAnswer(
    val itemId: String,
    val answer: String,
    val comment: String? = null,
    /** Фото как data URL — сервер сохраняет его в ответах запуска. */
    val photoDataUrl: String? = null,
) {
    fun payload(): JsonObject = JsonObject(
        buildMap {
            put("item_id", JsonPrimitive(itemId))
            put("answer", JsonPrimitive(answer))
            if (!comment.isNullOrEmpty()) put("comment", JsonPrimitive(comment))
            if (photoDataUrl != null) put("photo_base64", JsonPrimitive(photoDataUrl))
        },
    )
}

@Serializable
data class ChecklistRunStart(
    @SerialName("run_id") val runId: String = "",
    val reused: Boolean = false,
)

/** Итог завершения: штрафы и бонусы начисляются сразу. */
@Serializable
@Immutable
data class ChecklistRunResult(
    val status: String = "completed",
    @SerialName("fines_total") val finesTotal: Double = 0.0,
    @SerialName("bonuses_total") val bonusesTotal: Double = 0.0,
)

// ── Знания ───────────────────────────────────────────────────────────────────

@Serializable
@Immutable
data class KnowledgeArticle(
    val id: String,
    val title: String = "Статья",
    val summary: String? = null,
    val body: String? = null,
    // Текст статьи в разных версиях схемы лежит то в body, то в content.
    val content: String? = null,
    val severity: String? = null,
    val version: Int = 1,
    @SerialName("requires_confirmation") val requiresConfirmation: Boolean = false,
) {
    val text: String? get() = body ?: content
    val isCritical: Boolean get() = severity == "critical" || severity == "high"
}

@Serializable
data class KnowledgeData(
    val articles: List<KnowledgeArticle> = emptyList(),
    @SerialName("pending_confirmations") val pendingConfirmations: List<KnowledgeArticle> = emptyList(),
    @SerialName("checklist_templates") val templates: List<ChecklistTemplate> = emptyList(),
    @SerialName("checklist_items") val items: List<ChecklistItem> = emptyList(),
    @SerialName("checklist_runs") val runs: List<ChecklistRun> = emptyList(),
    @SerialName("open_shift") val openShift: JsonElement? = null,
    /**
     * Можно ли вообще подтверждать прочтение: подтверждение пишется на карточку
     * сотрудника, а у оператора её может не быть. Старый сервер флага не шлёт —
     * считаем, что можно, иначе кнопка исчезла бы у всех.
     */
    @SerialName("can_confirm") val canConfirm: Boolean = true,
)

/** Ответ `GET /api/operator/knowledge` — всё лежит в `data`. */
@Serializable
@Immutable
data class KnowledgeCenter(val data: KnowledgeData = KnowledgeData()) {
    val articles get() = data.articles
    val pendingConfirmations get() = data.pendingConfirmations
    val templates get() = data.templates
    val canConfirm get() = data.canConfirm

    fun items(templateId: String) = data.items.filter { it.templateId == templateId }
    fun completedRun(templateId: String) = data.runs.firstOrNull { it.templateId == templateId && it.isCompleted }
}

@Serializable
data class ExplainResponse(val answer: JsonElement? = null) {
    val text: String get() = (answer as? JsonPrimitive)?.contentOrNull.orEmpty()
}

// ── Смена: нужна только чек-листам, чтобы понять, чья смена ──────────────────

@Serializable
@Immutable
data class ShiftHead(
    val shift: ShiftBrief? = null,
    // Старый сервер поля не отдаёт: считаем смену своей, иначе кнопка пропадёт у того, кто её открыл.
    @SerialName("is_mine") val isMine: Boolean = true,
) {
    val hasOpenShift: Boolean get() = shift?.status == "open"
    val isSomeoneElses: Boolean get() = hasOpenShift && !isMine
    val isMyShift: Boolean get() = hasOpenShift && isMine
}

@Serializable
data class ShiftBrief(
    val status: String = "",
    // PostgREST отдаёт связь то объектом, то массивом — принимаем оба вида.
    val `operator`: JsonElement? = null,
) {
    val operatorName: String?
        get() {
            val ref = when (val o = `operator`) {
                is JsonObject -> o
                is JsonArray -> o.firstOrNull() as? JsonObject
                else -> null
            } ?: return null
            return (ref["short_name"] as? JsonPrimitive)?.contentOrNull
                ?: (ref["full_name"] as? JsonPrimitive)?.contentOrNull
        }
}

// ── Общие мелочи ─────────────────────────────────────────────────────────────

/** Склонение: 1 задача, 2 задачи, 5 задач. */
fun pluralize(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    if (mod100 in 11..14) return many
    return when (count % 10) {
        1 -> one
        2, 3, 4 -> few
        else -> many
    }
}
