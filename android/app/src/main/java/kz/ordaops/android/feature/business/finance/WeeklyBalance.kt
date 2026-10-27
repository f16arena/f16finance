package kz.ordaops.android.feature.business.finance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kz.ordaops.android.ui.components.money
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToLong

// Недельный баланс (/weekly-report): расчёт без сети и без Compose. Порт
// `lib/reports/weekly-balance.ts`. Правила — как на сайте:
//  - безналичный ночной смены после полуночи уходит на следующий день;
//  - отклонённые расходы не считаются, расходы на согласовании — считаются, но
//    показываются отдельно;
//  - «Безналичный» = терминал + онлайн + карта, расход безналом — kaspi_amount;
//  - точка «не в общих итогах» (флаг точки) — в итогах только по галочке, но в
//    списке точек видна всегда;
//  - сравнение — по тем же дням: идёт среда, значит пн–ср против пн–ср.

/** Строка прихода с полями, нужными недельному балансу. */
@Serializable
data class WeekIncome(
    val id: String = "",
    val date: String = "",
    val shift: String? = null,
    @SerialName("company_id") val companyId: String = "",
    @SerialName("cash_amount") val cash: Double = 0.0,
    @SerialName("kaspi_amount") val kaspi: Double = 0.0,
    @SerialName("kaspi_before_midnight") val kaspiBeforeMidnight: Double? = null,
    @SerialName("online_amount") val online: Double = 0.0,
    @SerialName("card_amount") val card: Double = 0.0,
)

/** Строка расхода с получателем разового платежа. */
@Serializable
data class WeekExpense(
    val date: String = "",
    @SerialName("company_id") val companyId: String = "",
    val category: String? = null,
    @SerialName("cash_amount") val cash: Double = 0.0,
    @SerialName("kaspi_amount") val kaspi: Double = 0.0,
    val status: String? = null,
    val comment: String? = null,
    @SerialName("one_off_payee") val payee: String? = null,
)

/** Точка для баланса; [isExtra] решает флаг точки «не в общих итогах». */
data class WeekCompany(val id: String, val name: String, val isExtra: Boolean)

enum class CompareMode(val key: String, val offsets: List<Int>, val label: String, val short: String) {
    Week("week", listOf(7), "те же дни прошлой недели", "Прошлая неделя"),
    Month("month", listOf(28), "те же дни 4 недели назад", "4 недели назад"),
    Avg8("avg8", (1..8).map { it * 7 }, "в среднем те же дни за 8 недель", "Среднее за 8 недель"),
}

class PeriodSums {
    var cash = 0.0; var terminal = 0.0; var online = 0.0; var card = 0.0
    var day = 0.0; var night = 0.0
    var expCash = 0.0; var expCashless = 0.0

    val incomeCashless get() = terminal + online + card
    val income get() = cash + incomeCashless
    val expense get() = expCash + expCashless
    val profit get() = income - expense
    val margin get() = if (income != 0.0) profit / income * 100 else 0.0
    val netCash get() = cash - expCash
    val netCashless get() = incomeCashless - expCashless
}

data class WeekDayRow(val date: String, val weekday: Int, val current: PeriodSums, val previous: PeriodSums, val future: Boolean) {
    val hasData get() = current.income != 0.0 || current.expense != 0.0
}

data class CompanyWeek(
    val id: String, val name: String, val isExtra: Boolean, val inTotals: Boolean,
    val current: PeriodSums, val compared: PeriodSums, val previous: PeriodSums,
)

data class CategoryWeek(val name: String, val cash: Double, val cashless: Double, val compared: Double, val previous: Double, val share: Double) {
    val total get() = cash + cashless
}

data class LargeExpense(val date: String, val company: String, val category: String, val payee: String, val total: Double, val pending: Boolean)
data class WeekChange(val label: String, val isRevenue: Boolean, val current: Double, val previous: Double, val effect: Double)
data class WeekAlert(val tone: String, val title: String, val text: String)
data class MissingShift(val company: String, val date: String, val shift: String)

data class WeeklyBalance(
    val weekStart: String,
    val weekEnd: String,
    val compare: CompareMode,
    /** Последний сравнимый день текущей недели; null — данных ещё нет. */
    val compareUntil: String?,
    val comparedDays: Int,
    val current: PeriodSums,
    val compared: PeriodSums,
    val previous: PeriodSums,
    val days: List<WeekDayRow>,
    val companies: List<CompanyWeek>,
    val categories: List<CategoryWeek>,
    val largest: List<LargeExpense>,
    val changes: List<WeekChange>,
    val alerts: List<WeekAlert>,
    val pendingCount: Int,
    val pendingTotal: Double,
    val extraNames: List<String>,
    val excludedIncome: Double,
)

object WeeklyLogic {
    /** Сколько дней истории нужно для режима «среднее за 8 недель». */
    const val HISTORY_DAYS = 56
    private const val LARGEST = 15
    private const val TOP_CHANGES = 5
    private const val SHIFT_HISTORY_DAYS = 28
    private const val SHIFT_REGULAR_SHARE = 0.6
    private val DAY_SHORT = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
    val DAY_LONG = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")

    fun addDays(iso: String, days: Int): String = LocalDate.parse(iso).plusDays(days.toLong()).toString()

    fun mondayOf(iso: String): String {
        val d = LocalDate.parse(iso)
        return d.minusDays(((d.dayOfWeek.value + 6) % 7).toLong()).toString()
    }

    fun deltaPct(cur: Double, prev: Double): Double? = if (prev == 0.0) null else (cur - prev) / abs(prev) * 100

    /** Ночной безнал после 00:00 — отдельной строкой на следующие сутки (как `splitIncomeKaspiByCalendarDay`). */
    fun splitKaspiByCalendarDay(rows: List<WeekIncome>): List<WeekIncome> {
        val out = ArrayList<WeekIncome>(rows.size + 8)
        for (row in rows) {
            if (row.shift != "night") { out += row; continue }
            val total = row.kaspi
            val before = row.kaspiBeforeMidnight
            val currentKaspi = if (before == null) 0.0 else before.coerceIn(0.0, maxOf(total, 0.0))
            val nextKaspi = if (before == null) maxOf(total, 0.0) else maxOf(total - currentKaspi, 0.0)
            out += row.copy(kaspi = currentKaspi)
            if (nextKaspi > 0) {
                out += row.copy(id = "${row.id}:kaspi-next-day", date = addDays(row.date, 1), cash = 0.0, kaspi = nextKaspi, online = 0.0, card = 0.0)
            }
        }
        return out
    }

    /**
     * Какие отчёты смен не внесены: смена «обычная», если точка сдавала её в 60%+
     * дней за 4 недели до этой недели. Проверяем по вчерашний день.
     */
    fun missingShifts(incomes: List<WeekIncome>, companies: List<WeekCompany>, weekStart: String, today: String): List<MissingShift> {
        val historyFrom = addDays(weekStart, -SHIFT_HISTORY_DAYS)
        val checkUntil = addDays(today, -1)
        val weekEnd = addDays(weekStart, 6)
        val until = if (checkUntil < weekEnd) checkUntil else weekEnd
        if (until < weekStart) return emptyList()
        val seen = HashMap<String, MutableSet<String>>()
        for (r in incomes) {
            if (r.shift != "day" && r.shift != "night") continue
            seen.getOrPut("${r.companyId}|${r.shift}") { HashSet() }.add(r.date)
        }
        val dates = generateSequence(weekStart) { if (it < until) addDays(it, 1) else null }.toList()
        val out = ArrayList<MissingShift>()
        for (c in companies) for (shift in listOf("day", "night")) {
            val have = seen["${c.id}|$shift"] ?: continue
            val history = have.count { it >= historyFrom && it < weekStart }
            if (history.toDouble() / SHIFT_HISTORY_DAYS < SHIFT_REGULAR_SHARE) continue
            for (d in dates) if (d !in have) out += MissingShift(c.name, d, shift)
        }
        return out.sortedWith(compareBy({ it.date }, { it.company }, { it.shift }))
    }

    private fun PeriodSums.addIncome(r: WeekIncome, k: Double = 1.0) {
        cash += r.cash * k; terminal += r.kaspi * k; online += r.online * k; card += r.card * k
        val total = (r.cash + r.kaspi + r.online + r.card) * k
        if (r.shift == "night") night += total else day += total
    }

    private fun PeriodSums.addExpense(cash: Double, cashless: Double, k: Double = 1.0) {
        expCash += cash * k; expCashless += cashless * k
    }

    fun build(
        incomesRaw: List<WeekIncome>,
        expensesRaw: List<WeekExpense>,
        companies: List<WeekCompany>,
        weekStart: String,
        today: String,
        includeExtra: Boolean,
        compare: CompareMode = CompareMode.Week,
    ): WeeklyBalance {
        val k = 1.0 / compare.offsets.size
        val weekEnd = addDays(weekStart, 6)
        val extraIds = companies.filter { it.isExtra }.map { it.id }.toSet()
        fun inTotals(id: String) = includeExtra || id !in extraIds
        val nameOf = companies.associate { it.id to it.name }

        val incomes = splitKaspiByCalendarDay(incomesRaw)
        // Отклонённые расходы в деньги не входят — как в ОПиУ и на сайте.
        val expenses = expensesRaw.filter { it.status?.lowercase() !in REJECTED }

        var until: String? = null
        if (weekEnd < today) until = weekEnd
        else {
            val limit = if (today < weekEnd) today else weekEnd
            fun consider(date: String, companyId: String, amount: Double) {
                if (date < weekStart || date > limit || !inTotals(companyId) || amount == 0.0) return
                if (until == null || date > until!!) until = date
            }
            incomes.forEach { consider(it.date, it.companyId, it.cash + it.kaspi + it.online + it.card) }
            expenses.forEach { consider(it.date, it.companyId, it.cash + it.kaspi) }
        }
        val lastCompared = until
        val comparedDays = lastCompared?.let { (LocalDate.parse(it).toEpochDay() - LocalDate.parse(weekStart).toEpochDay()).toInt() + 1 } ?: 0

        val current = PeriodSums(); val compared = PeriodSums(); val previous = PeriodSums()
        val dates = (0..6).map { addDays(weekStart, it) }
        val dayCurrent = dates.associateWith { PeriodSums() }
        val dayPrevious = dates.associateWith { PeriodSums() }
        class Acc { val cur = PeriodSums(); val cmp = PeriodSums(); val prev = PeriodSums(); val cats = LinkedHashMap<String, DoubleArray>() }
        val acc = LinkedHashMap<String, Acc>()
        fun accOf(id: String) = acc.getOrPut(id) { Acc() }
        companies.forEach { accOf(it.id) }

        fun inWeek(d: String) = d >= weekStart && d <= weekEnd
        fun inCompared(d: String) = lastCompared != null && d >= weekStart && d <= lastCompared
        var excluded = 0.0

        for (r in incomes) {
            val sum = r.cash + r.kaspi + r.online + r.card
            if (r.cash == 0.0 && r.kaspi == 0.0 && r.online == 0.0 && r.card == 0.0) continue
            val a = accOf(r.companyId)
            val counted = inTotals(r.companyId)
            if (inWeek(r.date)) {
                a.cur.addIncome(r)
                if (!counted) excluded += sum else { current.addIncome(r); dayCurrent[r.date]?.addIncome(r) }
            }
            if (inCompared(r.date)) { a.cmp.addIncome(r); if (counted) compared.addIncome(r) }
            for (offset in compare.offsets) {
                val shifted = addDays(r.date, offset)
                if (inCompared(shifted)) { a.prev.addIncome(r, k); if (counted) previous.addIncome(r, k) }
                if (counted && inWeek(shifted)) dayPrevious[shifted]?.addIncome(r, k)
            }
        }

        // cash, cashless, compared, previous
        val catAcc = LinkedHashMap<String, DoubleArray>()
        fun catOf(name: String) = catAcc.getOrPut(name) { DoubleArray(4) }
        val largest = ArrayList<LargeExpense>()
        var pendingCount = 0; var pendingTotal = 0.0

        for (r in expenses) {
            if (r.cash == 0.0 && r.kaspi == 0.0) continue
            val category = r.category?.trim().orEmpty().ifEmpty { "Без статьи" }
            val a = accOf(r.companyId)
            val counted = inTotals(r.companyId)
            val isPending = r.status == "pending_approval"
            if (inWeek(r.date)) {
                a.cur.addExpense(r.cash, r.kaspi)
                a.cats.getOrPut(category) { DoubleArray(2) }.also { it[0] += r.cash; it[1] += r.kaspi }
                if (counted) {
                    current.addExpense(r.cash, r.kaspi)
                    dayCurrent[r.date]?.addExpense(r.cash, r.kaspi)
                    catOf(category).also { it[0] += r.cash; it[1] += r.kaspi }
                    if (isPending) { pendingCount++; pendingTotal += r.cash + r.kaspi }
                    largest += LargeExpense(r.date, nameOf[r.companyId] ?: "—", category, (r.payee ?: r.comment).orEmpty().trim(), r.cash + r.kaspi, isPending)
                }
            }
            if (inCompared(r.date)) {
                a.cmp.addExpense(r.cash, r.kaspi)
                if (counted) { compared.addExpense(r.cash, r.kaspi); catOf(category)[2] += r.cash + r.kaspi }
            }
            for (offset in compare.offsets) {
                val shifted = addDays(r.date, offset)
                if (inCompared(shifted)) {
                    a.prev.addExpense(r.cash, r.kaspi, k)
                    if (counted) { previous.addExpense(r.cash, r.kaspi, k); catOf(category)[3] += (r.cash + r.kaspi) * k }
                }
                if (counted && inWeek(shifted)) dayPrevious[shifted]?.addExpense(r.cash, r.kaspi, k)
            }
        }

        val days = dates.mapIndexed { i, d -> WeekDayRow(d, i, dayCurrent.getValue(d), dayPrevious.getValue(d), d > today) }

        val companyRows = companies.map { c ->
            val a = accOf(c.id)
            CompanyWeek(c.id, c.name, c.isExtra, inTotals(c.id), a.cur, a.cmp, a.prev)
        }.filter { it.current.income != 0.0 || it.current.expense != 0.0 || it.previous.income != 0.0 || it.previous.expense != 0.0 || !it.isExtra }
            .sortedWith(compareBy<CompanyWeek> { it.isExtra }.thenByDescending { it.current.income })

        val categories = catAcc.map { (name, v) ->
            CategoryWeek(name, v[0], v[1], v[2], v[3], if (current.expense != 0.0) (v[0] + v[1]) / current.expense * 100 else 0.0)
        }.filter { it.total != 0.0 || it.previous != 0.0 }.sortedWith(compareByDescending<CategoryWeek> { it.total }.thenByDescending { it.previous })

        val changes = ArrayList<WeekChange>()
        if (lastCompared != null) {
            val revenueEffect = compared.income - previous.income
            if (revenueEffect.roundToLong() != 0L) changes += WeekChange("Выручка", true, compared.income, previous.income, revenueEffect)
            changes += categories
                .map { WeekChange(it.name, false, it.compared, it.previous, -(it.compared - it.previous)) }
                .filter { it.effect.roundToLong() != 0L }
                .sortedByDescending { abs(it.effect) }
                .take(TOP_CHANGES)
        }

        val missing = missingShifts(incomesRaw.filter { inTotals(it.companyId) }, companies.filter { inTotals(it.id) }, weekStart, today)
        val alerts = ArrayList<WeekAlert>()
        if (missing.isNotEmpty()) {
            val shown = missing.take(3).joinToString(", ") {
                "${it.company} — ${if (it.shift == "night") "ночь" else "день"} ${DAY_SHORT[(LocalDate.parse(it.date).dayOfWeek.value + 6) % 7]} ${it.date.substring(8, 10)}.${it.date.substring(5, 7)}"
            }
            alerts += WeekAlert("warning", "Не внесены отчёты смен: ${missing.size}", "$shown${if (missing.size > 3) " и ещё" else ""}. Пока их нет, выручка недели занижена.")
        }
        if (current.income == 0.0 && current.expense == 0.0) {
            alerts += WeekAlert("info", "За неделю ещё нет данных", "Отчёты смен и расходы появятся здесь, как только их внесут.")
        } else {
            if (current.profit < 0) alerts += WeekAlert("danger", "Неделя в минусе", "Расходы больше выручки на ${money(-current.profit)}.")
            if (current.netCash < 0) alerts += WeekAlert("warning", "Наличных потрачено больше, чем получено", "Разница ${money(-current.netCash)} — проверьте остаток в кассе.")
            if (current.netCashless < 0) alerts += WeekAlert("warning", "Безналичных расходов больше поступлений", "Разница ${money(-current.netCashless)}.")
            val revenueChange = deltaPct(compared.income, previous.income)
            val daysLabel = if (comparedDays == 7 || comparedDays < 1) "" else " (пн–${DAY_SHORT[comparedDays - 1]})"
            val label = compare.label.replace("те же", "тем же")
            if (revenueChange != null && revenueChange <= -15) {
                alerts += WeekAlert("warning", "Выручка ниже обычного", "${"%.1f".format(java.util.Locale.US, revenueChange)}% к $label$daysLabel: ${money(compared.income)} против ${money(previous.income)}.")
            } else if (revenueChange != null && revenueChange >= 15) {
                alerts += WeekAlert("success", "Выручка выше обычного", "+${"%.1f".format(java.util.Locale.US, revenueChange)}% к $label$daysLabel: ${money(compared.income)} против ${money(previous.income)}.")
            }
            if (pendingCount > 0) alerts += WeekAlert("info", "Ждут согласования: $pendingCount на ${money(pendingTotal)}", "Уже учтены в расходах недели; если их отклонят — прибыль вырастет.")
            val top = categories.firstOrNull()
            if (top != null && top.share > 40 && categories.size > 1) {
                alerts += WeekAlert("info", "Одна статья — большая часть расходов", "«${top.name}» — ${"%.0f".format(java.util.Locale.US, top.share)}% расходов недели (${money(top.total)}).")
            }
        }

        return WeeklyBalance(
            weekStart, weekEnd, compare, lastCompared, comparedDays, current, compared, previous, days, companyRows, categories,
            largest.sortedByDescending { it.total }.take(LARGEST), changes, alerts.take(5), pendingCount, pendingTotal,
            companies.filter { it.isExtra }.map { it.name }, excluded,
        )
    }

    private val REJECTED = setOf("rejected", "declined", "cancelled", "canceled")
}
