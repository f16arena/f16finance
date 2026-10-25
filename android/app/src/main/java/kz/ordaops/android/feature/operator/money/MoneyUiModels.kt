package kz.ordaops.android.feature.operator.money

import androidx.compose.runtime.Immutable
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.ui.components.money
import java.time.LocalDate
import java.time.format.TextStyle
import kotlin.math.abs

// Готовые к показу модели экранов. Всё тяжёлое (форматирование денег и дат,
// фильтры, группировки) считается один раз во ViewModel на фоновом потоке, а не
// при каждой перерисовке.

/** Во что красить значение строки; цвет берёт экран из темы. */
internal enum class Tone { Normal, Muted, Positive, Negative, Warning }

@Immutable
internal data class BreakdownRow(
    val key: String,
    val label: String,
    val value: String,
    val tone: Tone = Tone.Normal,
    val emphasized: Boolean = false,
    val divider: Boolean = false,
)

@Immutable
internal data class ShiftRowUi(val key: String, val title: String, val label: String, val amount: String, val details: String?)

@Immutable
internal data class ChartBarUi(val key: String, val dayLabel: String, val fraction: Float, val highlighted: Boolean)

@Immutable
internal data class DebtLineUi(val key: String, val title: String, val amount: String)

@Immutable
internal data class DebtWeekUi(
    val key: String,
    val title: String,
    val caption: String,
    val total: String,
    val lines: List<DebtLineUi>,
)

@Immutable
internal data class IncidentUi(
    val key: String,
    val title: String,
    val amount: String?,
    val isPenalty: Boolean,
    val description: String?,
)

@Immutable
internal data class MoneyContent(
    val heroTitle: String,
    val heroAmount: String,
    /** Доля выплаченного; null — выплат не было. */
    val paidFraction: Float?,
    val paidCaption: String?,
    val weekRange: String,
    val statusLabel: String,
    val breakdown: List<BreakdownRow>,
    val shiftsCaption: String?,
    val shifts: List<ShiftRowUi>,
    val chart: List<ChartBarUi>,
    val hasDebts: Boolean,
    val weekDebtTotal: String,
    val weekDebts: List<DebtLineUi>,
    val hasOtherDebts: Boolean,
    val totalDebt: String,
    val allDebtWeeks: List<DebtWeekUi>,
    val incidents: List<IncidentUi>,
)

internal fun buildMoneyContent(
    week: SalaryWeek,
    salary: OperatorSalary?,
    overview: MoneyOverview?,
    incidents: List<OperatorIncident>,
    selectedWeek: String,
): MoneyContent {
    val remaining = if (week.paidAmount > 0) maxOf(week.remainingAmount, 0.0) else week.netAmount
    val showPaid = week.paidAmount > 0 && week.netAmount > 0

    val rows = buildList {
        add(BreakdownRow("gross", "Начислено за смены", money(week.grossAmount)))
        // Стаж уже внутри начисления за смены — справочно, к итогу отдельной суммой не идёт.
        if (week.seniorityBonusTotal > 0) add(BreakdownRow("seniority", "· из них надбавка за стаж", money(week.seniorityBonusTotal), Tone.Muted))
        // Автобонус считают правила точки; без него строки не сходились с итогом.
        if (week.autoBonusTotal > 0) add(BreakdownRow("auto", "Бонусы за смены", moneySigned(week.autoBonusTotal), Tone.Positive))
        if (week.bonusAmount > 0) add(BreakdownRow("bonus", "Бонусы", moneySigned(week.bonusAmount), Tone.Positive))
        if (week.fineAmount > 0) add(BreakdownRow("fine", "Штрафы", moneySigned(-week.fineAmount), Tone.Negative))
        if (week.advanceAmount > 0) add(BreakdownRow("advance", "Аванс", moneySigned(-week.advanceAmount), Tone.Warning))
        if (week.debtAmount > 0) add(BreakdownRow("debt", "Удержано в счёт долга", moneySigned(-week.debtAmount), Tone.Negative))
        // Если сервер добавит составляющую, о которой приложение не знает, разница окажется здесь.
        val rest = week.unexplainedAmount
        if (abs(rest) >= 1) {
            add(
                BreakdownRow(
                    "rest",
                    if (rest > 0) "Прочие начисления" else "Прочие удержания",
                    moneySigned(rest),
                    if (rest > 0) Tone.Positive else Tone.Negative,
                ),
            )
        }
        add(BreakdownRow("net", "Итого к выплате", money(week.netAmount), emphasized = true, divider = true))
    }

    val paidShifts = salary?.shifts.orEmpty().filter { it.salary > 0 }
    val shiftRows = paidShifts.mapIndexed { i, shift ->
        val parts = buildList {
            if (shift.baseSalary > 0) add("ставка ${money(shift.baseSalary)}")
            if (shift.seniorityBonus > 0) {
                val percent = if (shift.seniorityPercent > 0) " (${percentText(shift.seniorityPercent)})" else ""
                add("стаж ${money(shift.seniorityBonus)}$percent")
            }
            if (shift.autoBonus > 0) add("бонус ${money(shift.autoBonus)}")
            if (shift.roleBonus > 0) add("за роль ${money(shift.roleBonus)}")
            if (shift.totalIncome > 0) add("выручка ${money(shift.totalIncome)}")
            if (!shift.companyName.isNullOrEmpty()) add(shift.companyName)
        }
        ShiftRowUi("${shift.id}#$i", dayTitle(shift.date), shift.shiftLabel, money(shift.salary), parts.joinToString(" · ").ifEmpty { null })
    }

    val today = LocalDate.now().toString()
    val chart = if (paidShifts.isEmpty()) {
        emptyList()
    } else {
        val maxValue = paidShifts.maxOf { it.salary }.coerceAtLeast(1.0)
        paidShifts.mapIndexed { i, shift ->
            ChartBarUi(
                key = "${shift.id}#$i",
                dayLabel = parseDate(shift.date)?.dayOfWeek?.getDisplayName(TextStyle.SHORT, RuLocale) ?: shift.date,
                fraction = (shift.salary / maxValue).toFloat().coerceIn(0.03f, 1f),
                highlighted = shift.date == today,
            )
        }
    }

    val allDebts = overview?.recentDebts.orEmpty().filter { it.amount > 0 }
    val weekDebts = allDebts.filter { (it.weekStart ?: "") == selectedWeek }
    val weekTotal = weekDebts.sumOf { it.amount }
    val total = allDebts.sumOf { it.amount }
    val debtWeeks = groupDebtsByWeek(allDebts).map { g ->
        DebtWeekUi(
            key = g.key,
            title = g.title,
            caption = "${g.debts.size} ${pluralize(g.debts.size, "запись", "записи", "записей")}" + if (g.key == selectedWeek) " · текущая" else "",
            total = money(g.total),
            lines = g.debts.mapIndexed { i, d -> DebtLineUi("${d.id}#$i", debtTitle(d), money(d.amount)) },
        )
    }

    val start = parseDate(week.weekStart)
    val end = parseDate(week.weekEnd)
    return MoneyContent(
        heroTitle = if (week.paidAmount > 0) "Осталось получить" else "К выплате за неделю",
        heroAmount = money(remaining),
        paidFraction = if (showPaid) (week.paidAmount / week.netAmount).toFloat().coerceIn(0f, 1f) else null,
        paidCaption = if (showPaid) "Выплачено ${money(week.paidAmount)} из ${money(week.netAmount)}" else null,
        weekRange = if (start == null || end == null) "—" else "${dayMonth(start)} — ${dayMonth(end)}",
        statusLabel = week.statusLabel,
        breakdown = rows,
        shiftsCaption = if (shiftRows.isEmpty()) null else "${shiftRows.size} ${pluralize(shiftRows.size, "смена", "смены", "смен")}",
        shifts = shiftRows,
        chart = chart,
        hasDebts = allDebts.isNotEmpty(),
        weekDebtTotal = money(weekTotal),
        weekDebts = weekDebts.mapIndexed { i, d -> DebtLineUi("${d.id}#$i", debtTitle(d), money(d.amount)) },
        hasOtherDebts = total - weekTotal > 0,
        totalDebt = money(total),
        allDebtWeeks = debtWeeks,
        incidents = incidents.take(8).mapIndexed { i, inc ->
            IncidentUi(
                key = "${inc.id}#$i",
                title = inc.title ?: if (inc.isPenalty) "Штраф" else "Поощрение",
                amount = inc.amount?.let { moneySigned(if (inc.isPenalty) -abs(it) else abs(it)) },
                isPenalty = inc.isPenalty,
                description = inc.description?.takeIf { it.isNotEmpty() },
            )
        },
    )
}

// ── График ──────────────────────────────────────────────────────────────────

@Immutable
internal data class DayUi(
    val key: String,
    val date: LocalDate,
    val weekday: String,
    val dayNumber: String,
    val isToday: Boolean,
    val isPast: Boolean,
    val isWorking: Boolean,
    val isNight: Boolean,
    val typeLabel: String?,
    val shiftType: String,
    val companyName: String?,
)

@Immutable
internal data class RequestUi(val key: String, val title: String, val reason: String?, val statusLabel: String)

@Immutable
internal data class ConfirmUi(val responseId: String, val companyName: String)

@Immutable
internal data class GroupCountUi(val name: String, val count: String)

@Immutable
internal data class ScheduleContent(
    val weekStart: String,
    val days: List<DayUi>,
    val rangeTitle: String,
    val caption: String,
    val workingCount: Int,
    val nightCount: Int,
    val nextText: String,
    /** По опубликованной неделе можно подать заявку «не смогу выйти». */
    val responseId: String?,
    val confirmations: List<ConfirmUi>,
    val requests: List<RequestUi>,
    val groupCounts: List<GroupCountUi>,
)

internal fun buildScheduleContent(schedule: OperatorSchedule): ScheduleContent {
    val groups = schedule.groups
    val start = parseDate(schedule.weekStart) ?: LocalDate.now()
    val shifts = groups.flatMap { g -> g.shifts.map { it to g.companyName } }
    val now = LocalDate.now()
    val days = (0L..6L).map { offset ->
        val date = start.plusDays(offset)
        val match = shifts.firstOrNull { it.first.date == date.toString() }
        val shift = match?.first
        DayUi(
            key = date.toString(),
            date = date,
            weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, RuLocale).uppercase(),
            dayNumber = "${date.dayOfMonth}",
            isToday = date == now,
            isPast = date.isBefore(now),
            isWorking = shift != null,
            isNight = shift?.isNight == true,
            typeLabel = shift?.typeLabel,
            shiftType = shift?.shiftType ?: "day",
            companyName = match?.second,
        )
    }
    val working = days.count { it.isWorking }
    val next = days.firstOrNull { it.isWorking && !it.isPast }?.date
    return ScheduleContent(
        weekStart = start.toString(),
        days = days,
        rangeTitle = "${dayMonth(days.first().date)} — ${dayMonth(days.last().date)}",
        caption = "$working ${pluralize(working, "смена", "смены", "смен")} на неделе",
        workingCount = working,
        nightCount = days.count { it.isNight },
        nextText = next?.let { "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, RuLocale)}, ${dayMonth(it)}" } ?: "—",
        responseId = groups.mapNotNull { it.response }.firstOrNull()?.id,
        confirmations = groups.mapNotNull { g ->
            g.response?.takeIf { g.needsConfirmation }?.let { ConfirmUi(it.id, g.companyName) }
        },
        requests = groups.flatMap { it.requests }.filter { it.isOpen }.mapIndexed { i, r ->
            RequestUi(
                key = "${r.id}#$i",
                title = dayTitle(r.shiftDate) + if (r.shiftType == "night") ", ночная" else ", дневная",
                reason = r.reason?.takeIf { it.isNotEmpty() },
                statusLabel = r.statusLabel,
            )
        },
        groupCounts = if (groups.size > 1) {
            groups.map { GroupCountUi(it.companyName, "${it.shifts.size} ${pluralize(it.shifts.size, "смена", "смены", "смен")}") }
        } else {
            emptyList()
        },
    )
}

/** Открытая форма «не смогу выйти». Сам текст причины — в rememberSaveable листа. */
@Immutable
internal data class IssueDraft(
    val shiftDate: String,
    val shiftType: String,
    val companyName: String,
    val saving: Boolean = false,
    val error: String? = null,
)

/** Черновик предложения старшего по заявке. */
@Immutable
internal data class ProposalDraft(
    val requestId: String,
    val saving: Boolean = false,
    val error: String? = null,
)

internal fun <A, B> Loadable<A>.mapData(transform: (A) -> B): Loadable<B> =
    Loadable(data?.let(transform), isLoading, isRefreshing, error, isOffline)
