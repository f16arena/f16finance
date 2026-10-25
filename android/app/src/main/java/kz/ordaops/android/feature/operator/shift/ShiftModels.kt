package kz.ordaops.android.feature.operator.shift

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.OffsetDateTime

// Модели смены и главной — OperatorShift.swift и часть OperatorCabinet.swift.
// Везде значения по умолчанию: новый ключ или null на бэке не должны ронять экран.

/** Разбор отметки времени из PostgREST: с зоной, с микросекундами или без. */
internal fun parseIsoMillis(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.LocalDateTime.parse(text).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
}

/** Дата «yyyy-MM-dd» с устройства — как `dateOnlyString` на айфоне. */
internal fun todayString(): String = LocalDate.now().toString()

/** Строка или число из JSON — сервер бывает непоследователен (`decodeFlexibleString`). */
internal fun JsonElement?.flexString(): String? = (this as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.flexDouble(): Double? {
    val p = this as? JsonPrimitive ?: return null
    return p.doubleOrNull ?: p.contentOrNull?.replace(',', '.')?.toDoubleOrNull()
}

/** Смена на точке — зеркало `point_shifts`. */
@Serializable
data class OperatorShift(
    val id: String = "",
    @SerialName("company_id") val companyId: String = "",
    val status: String = "open",
    @SerialName("shift_type") val shiftType: String? = null,
    @SerialName("opened_at") val openedAt: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("opening_cash") val openingCash: Double? = null,
    @SerialName("opening_notes") val openingNotes: String? = null,
    @SerialName("handover_from_shift_id") val handoverFromShiftId: String? = null,
    /** Вложенный кассир из join'а `staff`: объектом или массивом из одного. */
    @SerialName("operator") val operatorRef: JsonElement? = null,
) {
    val isOpen: Boolean get() = status == "open"
    val isNight: Boolean get() = shiftType == "night"
    val openedAtMillis: Long? get() = parseIsoMillis(openedAt)

    /** Кассир, открывший смену. Закрыть её может только он. */
    val operatorName: String?
        get() {
            val obj = when (val ref = operatorRef) {
                is JsonObject -> ref
                is JsonArray -> ref.firstOrNull() as? JsonObject
                else -> null
            } ?: return null
            return obj["short_name"].flexString() ?: obj["full_name"].flexString()
        }
}

/** Итоги смены: считает сервер постранично. */
@Serializable
data class ShiftTotals(
    @SerialName("sales_count") val salesCount: Int = 0,
    @SerialName("sales_total") val salesTotal: Double = 0.0,
    @SerialName("sales_cash") val salesCash: Double = 0.0,
    @SerialName("sales_kaspi") val salesKaspi: Double = 0.0,
    @SerialName("returns_count") val returnsCount: Int = 0,
    @SerialName("returns_total") val returnsTotal: Double = 0.0,
    @SerialName("returns_cash") val returnsCash: Double = 0.0,
    @SerialName("returns_kaspi") val returnsKaspi: Double = 0.0,
) {
    /** Выручка за вычетом возвратов. */
    val netTotal: Double get() = salesTotal - returnsTotal
    /** Сколько наличных должно быть в кассе сверх стартовой суммы. */
    val expectedCash: Double get() = salesCash - returnsCash
    val expectedKaspi: Double get() = salesKaspi - returnsKaspi
}

@Serializable
data class ChecklistTemplate(
    val id: String = "",
    val title: String = "Чек-лист",
    val description: String? = null,
    @SerialName("schedule_type") val scheduleType: String? = null,
    @SerialName("recurrence_minutes") val recurrenceMinutes: Int? = null,
    /** Обязательный: без него сервер не даст закрыть смену. */
    @SerialName("blocks_shift") val blocksShift: Boolean = false,
)

@Serializable
data class ChecklistRun(
    val id: String = "",
    @SerialName("template_id") val templateId: String = "",
    val status: String = "",
) {
    val isCompleted: Boolean get() = status == "completed"
}

@Serializable
data class PendingKnowledgeArticle(
    val id: String = "",
    val title: String = "",
    val severity: String? = null,
    val version: Int? = null,
    val summary: String? = null,
)

@Serializable
data class ChecklistsBlock(
    val templates: List<ChecklistTemplate> = emptyList(),
    val runs: List<ChecklistRun> = emptyList(),
)

@Serializable
data class KnowledgeBlock(
    @SerialName("pending_confirmations") val pendingConfirmations: List<PendingKnowledgeArticle> = emptyList(),
)

/**
 * Ответ `GET /api/operator/shift/current`. Когда смена закрыта, сервер отдаёт
 * только `{ "shift": null }`, поэтому остальное необязательно.
 */
@Serializable
data class ShiftState(
    val shift: OperatorShift? = null,
    val totals: ShiftTotals = ShiftTotals(),
    internal val checklists: ChecklistsBlock? = null,
    internal val knowledge: KnowledgeBlock? = null,
    /**
     * Моя ли это смена. Считает сервер; старый сервер поля не отдаёт — до его
     * обновления считаем смену своей, иначе кнопку отберём у того, кто смену открыл.
     */
    @SerialName("is_mine") val isMine: Boolean = true,
) {
    val templates: List<ChecklistTemplate> get() = checklists?.templates.orEmpty()
    val runs: List<ChecklistRun> get() = checklists?.runs.orEmpty()
    val pendingKnowledge: List<PendingKnowledgeArticle> get() = knowledge?.pendingConfirmations.orEmpty()

    /** Обязательные чек-листы, не завершённые в смене: именно они не дадут закрыть смену. */
    val blockingChecklists: List<ChecklistTemplate>
        get() {
            val done = runs.filter { it.isCompleted }.map { it.templateId }.toSet()
            return templates.filter { it.blocksShift && it.scheduleType != "onboarding" && it.id !in done }
        }
}

/** Тип смены. Для ночной Kaspi делится на «до» и «после» полуночи. */
@Serializable
enum class ShiftKind(val raw: String) {
    @SerialName("day") Day("day"),
    @SerialName("night") Night("night"),
}

/** Отчёт смены — то, что заполняют в программе на точке (`POST /api/operator/shift-report`). */
@Serializable
data class ShiftReportDraft(
    /** `YYYY-MM-DD`. */
    val date: String,
    val shift: String,
    @SerialName("shift_id") val shiftId: String? = null,
    val cash: Double = 0.0,
    val coins: Double = 0.0,
    @SerialName("kaspi_pos") val kaspiPos: Double = 0.0,
    @SerialName("kaspi_online") val kaspiOnline: Double = 0.0,
    @SerialName("kaspi_before_midnight") val kaspiBeforeMidnight: Double? = null,
    val debts: Double = 0.0,
    @SerialName("start_cash") val startCash: Double = 0.0,
    val wipon: Double = 0.0,
    val comment: String? = null,
)

// ── Главная: /api/operator/overview ──────────────────────────────────────────

@Serializable
data class SalaryWeek(
    val weekStart: String? = null,
    val weekEnd: String? = null,
    val netAmount: Double = 0.0,
    val paidAmount: Double = 0.0,
    val remainingAmount: Double = 0.0,
    val status: String = "draft",
) {
    val statusLabel: String
        get() = when (status) {
            "paid" -> "выплачено"
            "partial" -> "выплачено частично"
            "locked" -> "закрыта"
            else -> "в расчёте"
        }
}

@Serializable
data class OverviewCounters(
    val activeTasks: Int = 0,
    val reviewTasks: Int = 0,
    val activeDebts: Int = 0,
    val activeDebtAmount: Double = 0.0,
    val leadPoints: Int = 0,
)

@Serializable
data class NextShift(
    val date: String = "",
    val shiftType: String? = null,
    val companyName: String? = null,
    val label: String? = null,
)

@Serializable
data class HomePoints(val industries: List<String> = emptyList(), val sellsGoods: Boolean = true)

@Serializable
data class OperatorRef(
    val name: String? = null,
    @SerialName("short_name") val shortName: String? = null,
)

/** Ответ `GET /api/operator/overview` — то, что показывает главная. */
@Serializable
data class OperatorOverview(
    @SerialName("operator") val operatorRef: OperatorRef? = null,
    val week: SalaryWeek? = null,
    val counters: OverviewCounters? = null,
    val nextShift: NextShift? = null,
    val points: HomePoints? = null,
) {
    val operatorName: String get() = operatorRef?.shortName ?: operatorRef?.name ?: "Оператор"
}

/** Задача из `GET /api/operator/tasks` — главной нужны только просроченные. */
@Serializable
data class HomeTask(
    val id: String = "",
    val title: String = "Задача",
    val status: String = "new",
    @SerialName("due_date") val dueDate: String? = null,
) {
    val isDone: Boolean get() = status == "done" || status == "completed"

    /** Срок задан днём, поэтому сравниваем по дате. */
    val isOverdue: Boolean
        get() {
            if (isDone) return false
            val due = dueDate?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
            return due.isBefore(LocalDate.now())
        }
}

@Serializable
internal data class HomeTaskList(val tasks: List<HomeTask> = emptyList())

/** Русское склонение по числу. */
fun pluralize(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    if (mod100 in 11..14) return many
    return when (count % 10) {
        1 -> one
        2, 3, 4 -> few
        else -> many
    }
}
