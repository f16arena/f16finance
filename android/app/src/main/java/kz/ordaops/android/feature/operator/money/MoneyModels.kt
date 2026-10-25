package kz.ordaops.android.feature.operator.money

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kz.ordaops.android.core.net.ApiError
import kz.ordaops.android.feature.operator.OperatorPoints
import kz.ordaops.android.ui.components.money
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs

// Модели ответов /api/operator/{overview,salary,shifts,incidents,lead,sales-kpi}.
// Порт OperatorCabinet.swift, LeadDesk.swift, OperatorSalesQuality.swift:
// у каждого поля есть значение по умолчанию — как `decodeIfPresent ?? …` на айфоне.

/** Сводка недели по зарплате. */
@Serializable
data class SalaryWeek(
    val weekStart: String? = null,
    val weekEnd: String? = null,
    val grossAmount: Double = 0.0,
    val bonusAmount: Double = 0.0,
    val fineAmount: Double = 0.0,
    val debtAmount: Double = 0.0,
    val advanceAmount: Double = 0.0,
    val netAmount: Double = 0.0,
    val paidAmount: Double = 0.0,
    val remainingAmount: Double = 0.0,
    val status: String = "draft",
    val shiftsCount: Int? = null,
    /** Автобонус за смены — считают правила точки, в `bonusAmount` не входит. */
    val autoBonusTotal: Double = 0.0,
    /** Надбавка за стаж — уже внутри начисления за смены, показываем справочно. */
    val seniorityBonusTotal: Double = 0.0,
    val shifts: List<SalaryShift> = emptyList(),
) {
    val statusLabel: String
        get() = when (status) {
            "paid" -> "выплачено"
            "partial" -> "выплачено частично"
            "locked" -> "закрыта"
            else -> "в расчёте"
        }

    /**
     * Что не сошлось: если сервер добавит составляющую, о которой приложение не
     * знает, разница окажется здесь — честнее строки «прочее», чем цифры, которые
     * на экране не сходятся.
     */
    val unexplainedAmount: Double
        get() = netAmount - (grossAmount + autoBonusTotal + bonusAmount - fineAmount - debtAmount - advanceAmount)
}

@Serializable
data class SalaryShift(
    val date: String = "",
    val shift: String? = null,
    val companyName: String? = null,
    /** Выручка смены — от неё считается процент. */
    val totalIncome: Double = 0.0,
    val baseSalary: Double = 0.0,
    val seniorityBonus: Double = 0.0,
    val seniorityPercent: Double = 0.0,
    val autoBonus: Double = 0.0,
    val roleBonus: Double = 0.0,
    /** Итог за смену. */
    val salary: Double = 0.0,
) {
    val id: String get() = "$date-${shift.orEmpty()}"
    val shiftLabel: String get() = if (shift == "night") "ночная" else "дневная"
}

/** Ответ `GET /api/operator/salary`. */
@Serializable
data class OperatorSalary(val week: SalaryWeek? = null) {
    val shifts: List<SalaryShift> get() = week?.shifts.orEmpty()
}

@Serializable
data class OperatorDebt(
    val id: String = "",
    val amount: Double = 0.0,
    val comment: String? = null,
    @SerialName("week_start") val weekStart: String? = null,
    val companyName: String? = null,
)

@Serializable
data class OperatorRef(val name: String? = null, @SerialName("short_name") val shortName: String? = null)

/** Нужное из `GET /api/operator/overview`: имя, неделя, долги, точки. */
@Serializable
data class MoneyOverview(
    @SerialName("operator") private val operatorRef: OperatorRef? = null,
    val week: SalaryWeek? = null,
    val recentDebts: List<OperatorDebt> = emptyList(),
    val points: OperatorPoints? = null,
) {
    val operatorName: String get() = operatorRef?.shortName ?: operatorRef?.name ?: "Оператор"
}

@Serializable
data class OperatorIncident(
    val id: String = "",
    val title: String? = null,
    val description: String? = null,
    val kind: String? = null,
    val type: String? = null,
    val amount: Double? = null,
) {
    /** Штраф это или поощрение — определяет цвет. */
    val isPenalty: Boolean
        get() {
            val k = kind ?: type
            return (amount ?: 0.0) < 0 || k == "fine" || k == "penalty"
        }
}

/** Корень ответа incidents: разные роуты называют ключ по-разному. */
@Serializable
data class OperatorIncidentList(
    val incidents: List<OperatorIncident>? = null,
    val data: List<OperatorIncident>? = null,
) {
    val items: List<OperatorIncident> get() = incidents ?: data ?: emptyList()
}

// ── График ──────────────────────────────────────────────────────────────────

@Serializable
data class ScheduledShift(
    val id: String = "",
    val date: String = "",
    @SerialName("shift_type") val shiftType: String = "day",
) {
    val isNight: Boolean get() = shiftType == "night"
    val typeLabel: String get() = if (isNight) "ночная" else "дневная"
}

/** Ответ оператора на опубликованный график недели. */
@Serializable
data class ScheduleWeekResponse(
    val id: String = "",
    val status: String = "pending",
)

/** Заявка «не смогу выйти» по конкретной смене. */
@Serializable
data class ShiftChangeRequest(
    val id: String = "",
    @SerialName("shift_date") val shiftDate: String = "",
    @SerialName("shift_type") val shiftType: String = "day",
    val reason: String? = null,
    val status: String = "open",
) {
    val isOpen: Boolean get() = status == "open" || status == "awaiting_reason"

    val statusLabel: String
        get() = when (status) {
            "open", "awaiting_reason" -> "На рассмотрении"
            "resolved", "closed" -> "Решено"
            "rejected" -> "Отклонено"
            else -> humanize(status)
        }
}

@Serializable
data class CompanyName(val name: String? = null)

@Serializable
data class ScheduleGroup(
    @SerialName("company") private val companyRef: CompanyName? = null,
    val shifts: List<ScheduledShift> = emptyList(),
    val response: ScheduleWeekResponse? = null,
    val requests: List<ShiftChangeRequest> = emptyList(),
) {
    val companyName: String get() = companyRef?.name ?: "Точка"

    /** Неделя опубликована и ждёт подтверждения. */
    val needsConfirmation: Boolean get() = response != null && response.status != "confirmed"
}

/** Ответ `GET /api/operator/shifts`. */
@Serializable
data class OperatorSchedule(
    val weekStart: String? = null,
    val weekEnd: String? = null,
    @SerialName("schedule") val groups: List<ScheduleGroup> = emptyList(),
)

// ── Старший смены ───────────────────────────────────────────────────────────

@Serializable
data class LeadPerson(val name: String? = null)

@Serializable
data class LeadRef(@SerialName("operator") val person: LeadPerson? = null)

@Serializable
data class LeadCompany(
    val id: String = "",
    val name: String = "Точка",
    val weeklyStatus: Weekly? = null,
) {
    @Serializable
    data class Weekly(
        val state: String? = null,
        val total: Int? = null,
        val confirmed: Int? = null,
        val pending: Int? = null,
        val issues: Int? = null,
        val proposals: Int? = null,
    )

    val total: Int get() = weeklyStatus?.total ?: 0
    val confirmed: Int get() = weeklyStatus?.confirmed ?: 0
    val pending: Int get() = weeklyStatus?.pending ?: 0
    val issues: Int get() = weeklyStatus?.issues ?: 0

    /** График ещё не опубликован — подтверждать нечего. */
    val isDraft: Boolean get() = (weeklyStatus?.state ?: "draft") == "draft"
}

@Serializable
data class LeadShiftRequest(
    val id: String = "",
    @SerialName("company_id") val companyID: String = "",
    @SerialName("company_name") val companyName: String = "Точка",
    @SerialName("operator_id") val operatorID: String = "",
    @SerialName("operator_name") val operatorName: String = "Оператор",
    @SerialName("shift_date") val shiftDate: String = "",
    @SerialName("shift_type") val shiftType: String = "day",
    val status: String = "open",
    val reason: String? = null,
    /** `keep` / `remove` / `replace`. */
    @SerialName("lead_action") val leadAction: String? = null,
    @SerialName("lead_status") val leadStatus: String? = null,
    @SerialName("lead_note") val leadNote: String? = null,
    @SerialName("lead_replacement_operator_name") val replacementName: String? = null,
    @SerialName("resolution_note") val resolutionNote: String? = null,
) {
    val isOpen: Boolean get() = status == "open" || status == "awaiting_reason"
    val hasProposal: Boolean get() = (leadStatus ?: "") == "proposed"
    val isNight: Boolean get() = shiftType == "night"

    val proposalLabel: String?
        get() = when (leadAction) {
            "keep" -> "Оставить смену за человеком"
            "remove" -> "Снять со смены"
            "replace" -> replacementName?.let { "Заменить: $it" } ?: "Заменить"
            else -> null
        }

    val statusLabel: String
        get() = when (status) {
            "open", "awaiting_reason" -> if (hasProposal) "Ждёт руководителя" else "Ждёт вашего решения"
            "resolved", "closed" -> "Решено"
            "rejected" -> "Отклонено"
            else -> humanize(status)
        }
}

@Serializable
data class LeadTeamMember(
    val id: String = "",
    @SerialName("operator_id") val operatorID: String = "",
    @SerialName("operator_name") val operatorName: String = "Оператор",
    @SerialName("company_id") val companyID: String = "",
    @SerialName("role_in_company") val role: String? = null,
) {
    val roleLabel: String
        get() = when (role) {
            "senior_operator", "lead" -> "Старший"
            "trainee" -> "Стажёр"
            else -> "Оператор"
        }
}

/** Ответ `GET /api/operator/lead`. Для не-старшего сервер отвечает 403. */
@Serializable
data class LeadDesk(
    @SerialName("lead") private val leadRef: LeadRef? = null,
    val companies: List<LeadCompany> = emptyList(),
    val requests: List<LeadShiftRequest> = emptyList(),
    @SerialName("teamAssignments") val team: List<LeadTeamMember> = emptyList(),
) {
    /** Заявки, которые ждут именно старшего: открытые и без его предложения. */
    val awaitingProposal: List<LeadShiftRequest> get() = requests.filter { it.isOpen && !it.hasProposal }

    /** Предложены, но ещё не утверждены руководителем. */
    val awaitingDecision: List<LeadShiftRequest> get() = requests.filter { it.isOpen && it.hasProposal }

    val settled: List<LeadShiftRequest> get() = requests.filter { !it.isOpen }

    /** Кого можно предложить на замену на этой точке, кроме самого заявителя. */
    fun replacements(companyID: String, excluding: String): List<LeadTeamMember> =
        team.filter { it.companyID == companyID && it.operatorID != excluding }.distinctBy { it.operatorID }
}

// ── Как я работаю ───────────────────────────────────────────────────────────

@Serializable
data class SalesQualityBonus(
    val amount: Double = 0.0,
    val paid: Boolean = false,
    @SerialName("next_step") val nextStep: String? = null,
    val strong: Double = 0.0,
    val top: Double = 0.0,
)

@Serializable
data class SalesQualityMonth(
    val available: Boolean = false,
    /** `no-sales` — не продавал, `no-shifts` — смен не было. */
    val reason: String? = null,
    val month: String = "",
    val shifts: Int = 0,
    val receipts: Int = 0,
    /** TOP, STRONG, NORMAL, NEEDS_TRAINING, INSUFFICIENT_DATA. */
    val status: String = "",
    @SerialName("status_label") val statusLabel: String = "",
    @SerialName("status_meaning") val statusMeaning: String = "",
    val strengths: List<String> = emptyList(),
    val weaknesses: List<String> = emptyList(),
    val bonus: SalesQualityBonus? = null,
)

@Serializable
data class DataEnvelope<T>(val data: T)

// ── Мелочи форматирования ───────────────────────────────────────────────────

internal val RuLocale: Locale = Locale("ru", "RU")

internal fun parseDate(iso: String?): LocalDate? =
    runCatching { LocalDate.parse(iso!!.take(10)) }.getOrNull()

private val dayMonthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", RuLocale)

/** «12 авг.» из «2026-08-12»; нераспознанное возвращаем как есть. */
internal fun dayTitle(iso: String): String = parseDate(iso)?.format(dayMonthFormat) ?: iso

internal fun dayMonth(date: LocalDate): String = date.format(dayMonthFormat)

internal fun currentWeekStart(date: LocalDate = LocalDate.now()): String =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()

/** «17 авг. — 23 авг.»; без даты — «Без недели»: такие строки бывают у старых записей, это тоже деньги. */
internal fun weekTitle(weekStart: String): String {
    val start = parseDate(weekStart) ?: return "Без недели"
    return "${dayMonth(start)} — ${dayMonth(start.plusDays(6))}"
}

/** Склонение: 1 смена, 2 смены, 5 смен. */
internal fun pluralize(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    if (mod100 in 11..14) return many
    return when (count % 10) {
        1 -> one
        2, 3, 4 -> few
        else -> many
    }
}

/** +1 200 ₸ / −1 200 ₸. */
internal fun moneySigned(value: Double): String = (if (value >= 0) "+" else "−") + money(abs(value))

internal fun percentText(value: Double): String {
    val whole = value == Math.floor(value)
    return (if (whole) value.toLong().toString() else String.format(RuLocale, "%.1f", value)) + "%"
}

/** `kind_of_status` -> «Kind of status». */
internal fun humanize(raw: String): String {
    val spaced = raw.replace('_', ' ').trim()
    if (spaced.isEmpty()) return raw
    return spaced.replaceFirstChar { it.uppercase() }
}

/** Короткая подпись долга: первая строка списка, без хвоста. */
internal fun debtTitle(debt: OperatorDebt): String {
    val raw = debt.comment ?: debt.companyName ?: "Долг"
    val lines = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    val first = lines.firstOrNull() ?: return "Долг"
    return if (lines.size > 1) "$first и ещё ${lines.size - 1}" else first
}

/** Долги одной недели. */
internal data class DebtWeek(val key: String, val title: String, val total: Double, val debts: List<OperatorDebt>)

internal fun groupDebtsByWeek(debts: List<OperatorDebt>): List<DebtWeek> =
    debts.groupBy { it.weekStart.orEmpty() }
        .map { (key, list) -> DebtWeek(key, weekTitle(key), list.sumOf { it.amount }, list) }
        .sortedByDescending { it.key }

/** Понятная причина отказа в операторских сценариях — `operatorMessage` из OperatorService.swift. */
internal fun Throwable.operatorText(): String {
    if (this is ApiError) {
        return when (this) {
            is ApiError.Conflict -> when (code) {
                "point-shift-no-open" -> "Сначала откройте смену — без неё продавать нельзя."
                "point-shift-already-open" -> "На точке уже открыта смена."
                "point-shift-required-checklists-missing" -> "Перед закрытием завершите обязательные чек-листы."
                "act-not-open" -> "Акт ревизии уже закрыт."
                else -> ApiError.withoutRoute(message).ifEmpty { "Действие сейчас невозможно." }
            }
            // Отказы графика приходят с готовым человеческим текстом — он точнее общего.
            is ApiError.Forbidden -> ApiError.withoutRoute(message).ifEmpty { userMessage }
            is ApiError.BadRequest ->
                if (code == "opening-cash-required") "Перед открытием смены укажите, сколько денег в кассе."
                else ApiError.withoutRoute(message).ifEmpty { userMessage }
            else -> userMessage
        }
    }
    return message ?: "Что-то пошло не так"
}
