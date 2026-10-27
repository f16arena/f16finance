package kz.ordaops.android.feature.business.finance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

// Прогноз и точность (/analysis, он же /forecast) и «успеем ли» для /goals.
// Все цифры считает сервер (`lib/analysis/forecast-*.ts`): здесь только разбор
// ответов `GET /api/admin/monthly-forecast` и `GET /api/admin/kpi-plans/outlook`.

@Serializable
data class ForecastTriple(val income: Double = 0.0, val expense: Double = 0.0, val profit: Double = 0.0)

@Serializable
data class Scenarios(
    val pessimistic: ForecastTriple = ForecastTriple(),
    val realistic: ForecastTriple = ForecastTriple(),
    val optimistic: ForecastTriple = ForecastTriple(),
)

/** Сколько выйдет к концу идущего месяца: факт по вчера + оценка остатка. */
@Serializable
data class RunningOutlook(
    val month: String = "",
    val dayOfMonth: Int = 0,
    val daysInMonth: Int = 0,
    val knownDays: Int = 0,
    val knownShare: Double = 0.0,
    val fact: ForecastTriple = ForecastTriple(),
    val start: Scenarios = Scenarios(),
    val outlook: Scenarios = Scenarios(),
    /** Доход к концу месяца, если остаток пойдёт темпом последних 8 недель по дням недели. */
    val paceIncome: Double? = null,
)

@Serializable
data class ErrorPair(val income: Double? = null, val expense: Double? = null)

@Serializable
data class InsidePair(val income: Boolean = false, val expense: Boolean = false, val profit: Boolean = false)

@Serializable
data class CoverageCount(val inside: Int = 0, val total: Int = 0)

@Serializable
data class Coverage(val income: CoverageCount = CoverageCount(), val profit: CoverageCount = CoverageCount())

@Serializable
data class Accuracy(
    val checks: Int = 0,
    val recentError: ErrorPair = ErrorPair(),
    val coverage: Coverage = Coverage(),
    /** improving / worsening / flat / null. */
    val trend: String? = null,
)

@Serializable
data class BacktestRow(
    val month: String = "",
    val scenarios: Scenarios = Scenarios(),
    val actual: ForecastTriple = ForecastTriple(),
    val error: ErrorPair = ErrorPair(),
    val inside: InsidePair = InsidePair(),
)

@Serializable
data class Calibration(val weights: CalibrationWeights = CalibrationWeights())

@Serializable
data class CalibrationWeights(val income: Map<String, Double> = emptyMap())

@Serializable
data class MidMonthAccuracy(val day: Int = 0, val checks: Int = 0, val error: Double? = null, val closedError: Double? = null)

@Serializable
data class ForecastBasis(
    val provisionalMonth: String? = null,
    val knownDays: Int = 0,
    val midMonth: MidMonthAccuracy? = null,
)

@Serializable
data class NextForecast(
    val targetMonth: String = "",
    val scenarios: Scenarios? = null,
    val calibration: Calibration? = null,
    val backtest: List<BacktestRow> = emptyList(),
    val accuracy: Accuracy = Accuracy(),
    val monthsOfData: Int = 0,
    val explanation: List<String> = emptyList(),
    val basis: ForecastBasis = ForecastBasis(),
)

@Serializable
data class SnapshotView(
    val targetMonth: String = "",
    val scenarios: Scenarios = Scenarios(),
    val actual: ForecastTriple? = null,
    val error: ErrorPair? = null,
    val inside: InsidePair? = null,
    val createdAt: String = "",
    val late: Boolean = false,
)

@Serializable
data class CurrentForecast(
    val month: String = "",
    val snapshot: SnapshotView? = null,
    val outlook: RunningOutlook? = null,
)

@Serializable
data class ForecastMonth(
    val month: String = "",
    val income: Double = 0.0,
    val totalExpense: Double = 0.0,
    val netProfit: Double = 0.0,
    val marginPct: Double = 0.0,
    val isPartial: Boolean = false,
)

@Serializable
data class ExpenseStructure(
    val expected: Double = 0.0,
    val fixed: Double = 0.0,
    val variable: Double = 0.0,
    val variableRatePct: Double = 0.0,
    val oneOffAvg: Double = 0.0,
)

@Serializable
data class ForecastGroup(val label: String = "", val amount: Double = 0.0, val bucket: String = "")

@Serializable
data class Breakeven(val revenue: Double = 0.0, val safetyMarginPct: Double = 0.0)

@Serializable
data class MonthlyForecast(
    val months: List<ForecastMonth> = emptyList(),
    val expense: ExpenseStructure = ExpenseStructure(),
    val breakeven: Breakeven = Breakeven(),
    val expenseByGroup: List<ForecastGroup> = emptyList(),
)

@Serializable
data class ForecastCompany(
    val id: String = "",
    val name: String = "—",
    val scenarios: Scenarios = Scenarios(),
    val recentError: Double? = null,
    val checks: Int = 0,
)

@Serializable
data class IncompleteMonthRow(
    val month: String = "",
    val companyName: String = "—",
    val days: Int = 0,
    val expectedDays: Int = 0,
    val daysInMonth: Int = 0,
)

/** Ответ `/api/admin/monthly-forecast` (без `{data}`-обёртки). */
@Serializable
data class ForecastBoard(
    val forecast: MonthlyForecast = MonthlyForecast(),
    val next: NextForecast = NextForecast(),
    val current: CurrentForecast = CurrentForecast(),
    val snapshots: List<SnapshotView> = emptyList(),
    val snapshotsWarning: String? = null,
    val byCompany: List<ForecastCompany>? = null,
    val dataQuality: List<IncompleteMonthRow> = emptyList(),
) {
    /** Сверки: зафиксированные 1-го числа, а пока их нет — расчёт задним числом. */
    fun accuracyRows(frozen: Boolean): List<BacktestRow> =
        if (frozen) snapshots.filter { it.actual != null && it.inside != null }
            .map { BacktestRow(it.targetMonth, it.scenarios, it.actual!!, it.error ?: ErrorPair(), it.inside!!) }
        else next.backtest
}

// ── Для /goals ──────────────────────────────────────────────────────────────

@Serializable
data class GoalsOutlookScope(val outlook: RunningOutlook? = null, val next: Scenarios? = null)

/** Ответ `/api/admin/kpi-plans/outlook`. */
@Serializable
data class GoalsOutlook(
    val month: String = "",
    val nextMonth: String = "",
    val org: GoalsOutlookScope = GoalsOutlookScope(),
    val companies: Map<String, GoalsOutlookScope> = emptyMap(),
)

object ForecastText {
    val methodLabels = mapOf(
        "avg3" to "Среднее за 3 месяца",
        "trend" to "Среднее с трендом",
        "seasonal" to "Сезонность (как год назад)",
        "last" to "Как прошлый месяц",
    )

    /** Доля → «12%». */
    fun share(v: Double): String = "${(v * 100).roundToInt()}%"

    fun trendText(trend: String?): String? = when (trend) {
        "improving" -> "Ошибка дохода уменьшается — модель учится."
        "worsening" -> "Ошибка дохода растёт — последние месяцы модели даются хуже."
        "flat" -> "Ошибка дохода держится на одном уровне."
        else -> null
    }
}
