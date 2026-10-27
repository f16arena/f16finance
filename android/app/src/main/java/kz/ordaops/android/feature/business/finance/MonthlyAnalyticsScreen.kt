package kz.ordaops.android.feature.business.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.feature.business.AnalyticsPeriod
import kz.ordaops.android.feature.business.BizData
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerFootnote
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.operator.money.PeriodStepper
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// «Аналитика доходов» (/analytics): 12 месяцев года, `GET /api/admin/analytics/monthly?year=`.

@Serializable
data class MonthCompanyCell(
    val cash: Double = 0.0, val kaspi: Double = 0.0, val card: Double = 0.0, val online: Double = 0.0,
    val revenue: Double = 0.0,
    @SerialName("checks_count") val checks: Int = 0,
)

@Serializable
data class MonthAggregate(
    val month: String = "",
    val cash: Double = 0.0, val kaspi: Double = 0.0, val card: Double = 0.0, val online: Double = 0.0,
    val revenue: Double = 0.0, val expenses: Double = 0.0, val profit: Double = 0.0,
    @SerialName("margin_pct") val marginPct: Double = 0.0,
    @SerialName("checks_count") val checks: Int = 0,
    @SerialName("avg_check") val avgCheck: Double = 0.0,
    @SerialName("by_company") val byCompany: Map<String, MonthCompanyCell> = emptyMap(),
)

@Serializable
data class PreviousMonth(val month: String = "", val revenue: Double = 0.0)

@Serializable
data class MonthlyBoard(
    val year: Int = 0,
    val companies: List<GoalCompany> = emptyList(),
    val months: List<MonthAggregate> = emptyList(),
    val previousYear: List<PreviousMonth> = emptyList(),
)

/** Итоги года. */
data class YearTotals(
    val revenue: Double, val expenses: Double, val checks: Int, val prevRevenue: Double,
    val bestMonth: MonthAggregate?, val worstMonth: MonthAggregate?,
) {
    val profit get() = revenue - expenses
    val margin get() = if (revenue > 0) profit / revenue * 100 else 0.0
    val avgCheck get() = if (checks > 0) revenue / checks else 0.0
    val yoy get() = if (prevRevenue > 0) (revenue - prevRevenue) / prevRevenue * 100 else null
}

object MonthlyLogic {
    fun totals(months: List<MonthAggregate>, previous: List<PreviousMonth>): YearTotals {
        val revenue = months.sumOf { it.revenue }
        return YearTotals(
            revenue, months.sumOf { it.expenses }, months.sumOf { it.checks }, previous.sumOf { it.revenue },
            months.filter { it.revenue > 0 }.maxByOrNull { it.revenue },
            months.filter { it.revenue > 0 }.minByOrNull { it.revenue },
        )
    }

    fun monthIndex(ym: String): Int = (ym.substring(5, 7).toIntOrNull() ?: 1) - 1
}

class MonthlyViewModel(private val api: FinanceApi) : LoadViewModel<MonthlyBoard>() {
    private var year = AnalyticsPeriod.businessToday().year

    override suspend fun fetch(): MonthlyBoard =
        api.api.send<BizData<MonthlyBoard>>(ApiRequest("/api/admin/analytics/monthly", query = mapOf("year" to year.toString()))).data

    fun show(y: Int) {
        if (y == year && state.value.hasData) return
        year = y
        reset(); reload()
    }
}

@Composable
fun MonthlyAnalyticsScreen() {
    val vm = financeViewModel("monthly") { _, api -> MonthlyViewModel(api) }
    val state by vm.state.collectAsState()
    val currentYear = AnalyticsPeriod.businessToday().year
    var year by rememberSaveable { mutableStateOf(currentYear) }
    LaunchedEffect(year) { vm.show(year) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Spacing.lg)) {
            PeriodStepper(
                title = "$year", canGoForward = year < currentYear + 1,
                onBack = { year -= 1 }, onForward = { year += 1 },
            )
        }
        StateContainer(state, onRetry = vm::reload, modifier = Modifier.weight(1f)) { board ->
            OwnerScreenColumn { MonthlyContent(board, year) }
        }
    }
}

@Composable
private fun MonthlyContent(board: MonthlyBoard, year: Int) {
    val t = MonthlyLogic.totals(board.months, board.previousYear)
    if (t.revenue == 0.0 && t.expenses == 0.0) {
        OwnerFootnote("За $year год данных нет.")
        return
    }
    HeroSummary(
        title = "Выручка за $year",
        value = money(t.revenue),
        caption = t.yoy?.let { "${BizFormat.percent(it, signed = true)} к ${year - 1} году" },
        footer = listOf("Расходы" to money(t.expenses), "Прибыль" to money(t.profit), "Маржа" to BizFormat.percent(t.margin)),
        colors = if (t.profit >= 0) FinanceColors.HeroPositive else FinanceColors.HeroNegative,
    )
    OwnerSection("Чеки и лучшие месяцы") {
        LineRow("Чеков", "${t.checks}")
        LineRow("Средний чек", money(t.avgCheck))
        t.bestMonth?.let { LineRow("Лучший месяц", "${RuDates.monthName(MonthlyLogic.monthIndex(it.month))} · ${money(it.revenue)}", Orda.colors.positive) }
        t.worstMonth?.let { LineRow("Худший месяц", "${RuDates.monthName(MonthlyLogic.monthIndex(it.month))} · ${money(it.revenue)}") }
    }
    OwnerSection("Выручка по месяцам") {
        DayBars(board.months.map { Bar(RuDates.monthShort(MonthlyLogic.monthIndex(it.month)), it.revenue) }, Orda.colors.positive)
        if (board.previousYear.any { it.revenue > 0 }) {
            Text("Прошлый год", color = Orda.colors.textDim, fontSize = 13.sp)
            TrendLine(board.previousYear.map { Bar(RuDates.monthShort(MonthlyLogic.monthIndex(it.month)), it.revenue) }, Orda.colors.textDim)
        }
    }
    OwnerSection("Прибыль по месяцам") {
        TrendLine(board.months.map { Bar(RuDates.monthShort(MonthlyLogic.monthIndex(it.month)), it.profit) }, Orda.colors.brand)
    }
    OwnerSection("Способы оплаты") {
        val cash = board.months.sumOf { it.cash }
        val non = board.months.sumOf { it.kaspi + it.card + it.online }
        val total = (cash + non).coerceAtLeast(1.0)
        LineRow("Наличные", money(cash)); ShareBar(cash / total, FinanceColors.methodTint(Methods.Cash))
        LineRow("Безналичные", money(non)); ShareBar(non / total, FinanceColors.methodTint(Methods.Kaspi))
    }
    OwnerSection("По месяцам") {
        board.months.filter { it.revenue > 0 || it.expenses > 0 }.reversed().forEach { m ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(RuDates.monthName(MonthlyLogic.monthIndex(m.month)), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(money(m.revenue), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("расходы ${money(m.expenses)} · прибыль ${money(m.profit)} · маржа ${BizFormat.percent(m.marginPct)}", color = Orda.colors.textDim, fontSize = 12.sp)
                Text("чеков ${m.checks} · средний ${money(m.avgCheck)}", color = Orda.colors.textDim, fontSize = 12.sp)
            }
        }
    }
    if (board.companies.size > 1) {
        OwnerSection("Выручка по точкам · $year") {
            board.companies.forEach { c ->
                val sum = board.months.sumOf { it.byCompany[c.id]?.revenue ?: 0.0 }
                LineRow(c.name, money(sum))
                ShareBar(if (t.revenue > 0) sum / t.revenue else 0.0, FinanceColors.categoryTint(c.name))
            }
        }
    }
}
