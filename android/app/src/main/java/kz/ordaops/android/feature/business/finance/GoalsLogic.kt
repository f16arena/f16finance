package kz.ordaops.android.feature.business.finance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.roundToLong

// Цели (/goals): ответ `GET /api/admin/kpi-plans` и чистые расчёты темпа. Порт
// `lib/analysis/goal-pace.ts` и вычислений страницы `app/(main)/goals`.

@Serializable
data class GoalPlan(
    val id: String = "",
    @SerialName("company_id") val companyId: String? = null,
    val kind: String = "",
    @SerialName("target_amount") val target: Double = 0.0,
    @SerialName("period_start") val periodStart: String = "",
    @SerialName("period_end") val periodEnd: String = "",
    @SerialName("period_kind") val periodKind: String? = null,
    val metric: String? = null,
    @SerialName("fact_value") val fact: Double = 0.0,
    @SerialName("is_closed") val isClosed: Boolean = false,
)

/** Дневной итог: `company_id == null` — сеть без точек «не в общих итогах». */
@Serializable
data class GoalDay(
    val date: String = "",
    @SerialName("company_id") val companyId: String? = null,
    val revenue: Double = 0.0,
    val expenses: Double = 0.0,
    val checks: Int = 0,
)

@Serializable
data class GoalCompany(val id: String = "", val name: String = "Точка", val code: String? = null)

@Serializable
data class GoalsData(
    val year: Int = 0,
    val companies: List<GoalCompany> = emptyList(),
    val plans: List<GoalPlan> = emptyList(),
    val dailyAggregates: List<GoalDay> = emptyList(),
    val priorYearDaily: List<GoalDay> = emptyList(),
    val factEnd: String = "",
    val orgPlansAvailable: Boolean = true,
    val orgPlansHint: String? = null,
    val excludedCompanies: List<String> = emptyList(),
)

/** Цель периода и откуда она взялась: свой план, сумма месяцев или сумма точек. */
data class GoalTarget(val value: Double, val source: String)

data class GoalFacts(val revenue: Double, val expenses: Double) {
    val profit get() = revenue - expenses
    val margin get() = if (revenue > 0) (profit / revenue * 1000).roundToLong() / 10.0 else 0.0
    fun of(metric: String): Double = when (metric) {
        "revenue" -> revenue
        "profit" -> profit
        else -> margin
    }
}

data class GoalPace(
    val target: Double, val fact: Double, val expectedByNow: Double, val gap: Double,
    val daysPassed: Int, val daysLeft: Int, val requiredPerDay: Double, val currentPerDay: Double,
    val doneShare: Double, val timeShare: Double,
)

data class GoalVerdict(val status: String, val label: String, val shortfall: Double)

object GoalsLogic {
    val periods = listOf("month" to "Месяц", "h1" to "I полугодие", "h2" to "II полугодие", "year" to "Год")
    val metrics = listOf("revenue" to "Выручка", "profit" to "Прибыль", "margin" to "Маржа")
    private val additive = setOf("revenue", "profit")
    private val periodMonths = mapOf("year" to (0..11).toList(), "h1" to (0..5).toList(), "h2" to (6..11).toList())

    fun isAdditive(metric: String) = metric in additive

    fun shift(date: String, days: Int): String = LocalDate.parse(date).plusDays(days.toLong()).toString()

    fun datesBetween(start: String, end: String): List<String> {
        val out = ArrayList<String>()
        var d = LocalDate.parse(start)
        val e = LocalDate.parse(end)
        while (!d.isAfter(e)) { out += d.toString(); d = d.plusDays(1) }
        return out
    }

    /** 0 = воскресенье, как `getUTCDay()` на сайте. */
    fun weekday(date: String): Int = LocalDate.parse(date).dayOfWeek.value % 7

    fun periodBounds(period: String, year: Int, monthIdx: Int = 0): Pair<String, String> = when (period) {
        "year" -> "$year-01-01" to "$year-12-31"
        "h1" -> "$year-01-01" to "$year-06-30"
        "h2" -> "$year-07-01" to "$year-12-31"
        else -> {
            val first = LocalDate.of(year, monthIdx + 1, 1)
            first.toString() to first.withDayOfMonth(first.lengthOfMonth()).toString()
        }
    }

    private fun mm(idx: Int) = (idx + 1).toString().padStart(2, '0')

    /** Вес дня недели (0 = вс): средняя выручка дня к средней по всем. Мало данных — все равны. */
    fun weekdayWeights(days: List<Pair<String, Double>>): DoubleArray {
        val worked = days.filter { it.second > 0 }
        if (worked.size < 14) return DoubleArray(7) { 1.0 }
        val sum = DoubleArray(7); val count = IntArray(7)
        for ((date, v) in worked) { val wd = weekday(date); sum[wd] += v; count[wd]++ }
        val overall = worked.sumOf { it.second } / worked.size
        if (overall <= 0) return DoubleArray(7) { 1.0 }
        return DoubleArray(7) { if (count[it] > 0) sum[it] / count[it] / overall else 1.0 }
    }

    /** Ритм недели за последние 8 недель до последнего дня с фактом. */
    fun rhythm(daily: List<GoalDay>, prior: List<GoalDay>, companyId: String?, factEnd: String): DoubleArray {
        if (factEnd.isEmpty()) return DoubleArray(7) { 1.0 }
        val from = shift(factEnd, -55)
        val byDate = HashMap<String, Double>()
        for (r in prior + daily) {
            if (r.date < from || r.date > factEnd || r.companyId != companyId) continue
            byDate[r.date] = (byDate[r.date] ?: 0.0) + r.revenue
        }
        return weekdayWeights(byDate.entries.map { it.key to it.value })
    }

    fun facts(daily: List<GoalDay>, companyId: String?, start: String, end: String): GoalFacts {
        var revenue = 0.0; var expenses = 0.0
        for (r in daily) {
            if (r.date < start || r.date > end || r.companyId != companyId) continue
            revenue += r.revenue; expenses += r.expenses
        }
        return GoalFacts(revenue.roundToLong().toDouble(), expenses.roundToLong().toDouble())
    }

    fun findPlan(plans: List<GoalPlan>, period: String, monthIdx: Int?, metric: String, companyId: String?): GoalPlan? =
        plans.firstOrNull {
            it.periodKind == period && it.metric == metric && (if (companyId == null) it.companyId.isNullOrEmpty() else it.companyId == companyId) &&
                (period != "month" || (monthIdx != null && it.periodStart.substring(5, 7) == mm(monthIdx)))
        }

    /** Цель месяца: своя; для сети без общей цели — сумма целей точек. */
    fun monthTarget(plans: List<GoalPlan>, monthIdx: Int, metric: String, companyId: String?): GoalTarget {
        findPlan(plans, "month", monthIdx, metric, companyId)?.let { return GoalTarget(it.target, "plan") }
        if (companyId == null && isAdditive(metric)) {
            val sum = plans.filter { it.periodKind == "month" && it.metric == metric && !it.companyId.isNullOrEmpty() && it.periodStart.substring(5, 7) == mm(monthIdx) }.sumOf { it.target }
            if (sum > 0) return GoalTarget(sum, "companies")
        }
        return GoalTarget(0.0, "none")
    }

    /** Цель года/полугодия: явная → сумма месяцев → сумма точек. */
    fun periodTarget(plans: List<GoalPlan>, period: String, metric: String, companyId: String?, monthIdx: Int = 0): GoalTarget {
        if (period == "month") return monthTarget(plans, monthIdx, metric, companyId)
        findPlan(plans, period, null, metric, companyId)?.let { return GoalTarget(it.target, "plan") }
        if (!isAdditive(metric)) return GoalTarget(0.0, "none")
        val months = periodMonths.getValue(period).sumOf { monthTarget(plans, it, metric, companyId).value }
        if (months > 0) return GoalTarget(months, "months")
        if (companyId == null) {
            val sum = plans.filter { it.periodKind == period && it.metric == metric && !it.companyId.isNullOrEmpty() }.sumOf { it.target }
            if (sum > 0) return GoalTarget(sum, "companies")
        }
        return GoalTarget(0.0, "none")
    }

    fun sourceLabel(source: String): String? = when (source) {
        "months" -> "Σ месяцы"
        "companies" -> "Σ точки"
        else -> null
    }

    /** Накопленная цель на каждую дату периода с учётом ритма недели. */
    fun cumulativeTargetCurve(dates: List<String>, target: Double, weights: DoubleArray): List<Long> {
        val w = dates.map { weights[weekday(it)] }
        val total = w.sum().takeIf { it != 0.0 } ?: 1.0
        var acc = 0.0
        return w.map { acc += it; (target * acc / total).roundToLong() }
    }

    /** Темп выполнения плана; [lastFactDate] — последний день с внесённым фактом (обычно вчера). */
    fun pace(target: Double, fact: Double, start: String, end: String, lastFactDate: String, weights: DoubleArray): GoalPace? {
        if (target <= 0) return null
        val dates = datesBetween(start, end)
        if (dates.isEmpty()) return null
        val lastFact = when { lastFactDate < start -> shift(start, -1); lastFactDate > end -> end; else -> lastFactDate }
        val passed = dates.filter { it <= lastFact }
        val left = dates.size - passed.size
        val curve = cumulativeTargetCurve(dates, target, weights)
        val expected = if (passed.isNotEmpty()) curve[passed.size - 1].toDouble() else 0.0
        val totalWeight = dates.sumOf { weights[weekday(it)] }.takeIf { it != 0.0 } ?: 1.0
        val passedWeight = passed.sumOf { weights[weekday(it)] }
        return GoalPace(
            target.roundToLong().toDouble(), fact.roundToLong().toDouble(), expected, (fact - expected).roundToLong().toDouble(),
            passed.size, left,
            if (left > 0) (maxOf(0.0, target - fact) / left).roundToLong().toDouble() else 0.0,
            if (passed.isNotEmpty()) (fact / passed.size).roundToLong().toDouble() else 0.0,
            fact / target, passedWeight / totalWeight,
        )
    }

    /**
     * Успеем ли выполнить план, по прогнозу к концу месяца (коридор из /analysis).
     * safe — даже осторожный сценарий выше плана; likely — реальный; at_risk — только оптимистичный.
     */
    fun verdict(target: Double, fact: Double, outlook: Triple<Double, Double, Double>?): GoalVerdict? {
        if (target <= 0) return null
        if (fact >= target) return GoalVerdict("done", "План выполнен", (target - fact).roundToLong().toDouble())
        val (pessimistic, realistic, optimistic) = outlook ?: return null
        val shortfall = (target - realistic).roundToLong().toDouble()
        return when {
            pessimistic >= target -> GoalVerdict("safe", "Выполним с запасом", shortfall)
            realistic >= target -> GoalVerdict("likely", "Вероятно выполним", shortfall)
            optimistic >= target -> GoalVerdict("at_risk", "Под угрозой", shortfall)
            else -> GoalVerdict("unlikely", "Не успеваем", shortfall)
        }
    }

    /** Прогноз выручки/прибыли к концу месяца из коридора сценариев. */
    fun outlookTriple(o: RunningOutlook?, metric: String): Triple<Double, Double, Double>? {
        if (o == null) return null
        fun pick(t: ForecastTriple) = if (metric == "revenue") t.income else t.profit
        return Triple(pick(o.outlook.pessimistic), pick(o.outlook.realistic), pick(o.outlook.optimistic))
    }

    data class MonthPlanFact(val month: Int, val target: Double?, val fact: Double, val closed: Boolean)
    data class MissedMonth(val month: Int, val target: Double, val fact: Double, val shortfall: Double, val pct: Int)
    data class YearSummary(val closedWithPlan: Int, val hit: Int, val missed: List<MissedMonth>, val totalTarget: Double, val totalFact: Double)

    fun yearSummary(months: List<MonthPlanFact>): YearSummary {
        val withPlan = months.filter { it.closed && (it.target ?: 0.0) > 0 }
        val hit = withPlan.count { it.fact >= (it.target ?: 0.0) }
        return YearSummary(
            withPlan.size, hit,
            withPlan.filter { it.fact < (it.target ?: 0.0) }.map {
                val t = it.target ?: 0.0
                MissedMonth(it.month, t, it.fact, t - it.fact, (it.fact / t * 100).roundToLong().toInt())
            },
            withPlan.sumOf { it.target ?: 0.0 }, withPlan.sumOf { it.fact },
        )
    }

    /** Тот же отрезок дат прошлого года, что уже прошёл в этом (29 февраля → 28-е). */
    fun yearBack(date: String): String {
        val md = if (date.substring(5) == "02-29") "02-28" else date.substring(5)
        return "${date.substring(0, 4).toInt() - 1}-$md"
    }

    /** Год к году для выручки и прибыли; null — сравнивать не с чем. */
    fun yoyBase(prior: List<GoalDay>, companyId: String?, start: String, end: String, factEnd: String, metric: String): Double? {
        if (!isAdditive(metric) || factEnd < start) return null
        val to = if (end < factEnd) end else factEnd
        val from = yearBack(start); val until = yearBack(to)
        var revenue = 0.0; var expenses = 0.0
        for (r in prior) {
            if (r.date < from || r.date > until || r.companyId != companyId) continue
            revenue += r.revenue; expenses += r.expenses
        }
        val value = if (metric == "revenue") revenue else revenue - expenses
        return value.takeIf { it > 0 }
    }
}
