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
import kotlinx.serialization.Serializable
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.feature.business.AnalyticsPeriod
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.HorizontalRow
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerFootnote
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// «AI Разбор расходов» (/expense-analysis) и «AI Разбор команды» (/team-analysis):
// `GET /api/ai/expense-analysis` и `/api/ai/team-analysis`. Метрики считает сервер,
// ИИ добавляет выводы (`insights`) и короткую сводку (`summary`).

@Serializable
data class AiInsight(val verdict: String = "", val reason: String = "", val action: String = "", val severity: String = "medium")

@Serializable
data class ExpenseCategoryMetric(val category: String = "", val amount: Double = 0.0, val prev: Double = 0.0, val sharePct: Double = 0.0, val changePct: Double = 0.0)

@Serializable
data class ExpenseMetrics(val categories: List<ExpenseCategoryMetric> = emptyList(), val total: Double = 0.0, val totalPrevPct: Double = 0.0)

@Serializable
data class ExpenseAnalysis(val metrics: ExpenseMetrics = ExpenseMetrics(), val insights: List<AiInsight> = emptyList(), val summary: String = "")

@Serializable
data class OperatorMetric(
    val id: String = "", val name: String = "", val shifts: Int = 0, val turnover: Double = 0.0, val revenuePerShift: Double = 0.0,
    val gross: Double = 0.0, val net: Double = 0.0, val paid: Double = 0.0, val remaining: Double = 0.0,
    val bonus: Double = 0.0, val fine: Double = 0.0, val debt: Double = 0.0,
)

@Serializable
data class TeamAggregates(val dateFrom: String = "", val dateTo: String = "")

@Serializable
data class TeamMetrics(val operators: List<OperatorMetric> = emptyList(), val aggregates: TeamAggregates? = null)

@Serializable
data class TeamAnalysis(val metrics: TeamMetrics = TeamMetrics(), val insights: List<AiInsight> = emptyList(), val summary: String = "")

/** Период разбора: готовые «дни назад» или свои даты. */
data class AiRange(val days: Int, val from: String = "", val to: String = "") {
    val isCustom get() = days == 0
    val ready get() = !isCustom || (from.isNotEmpty() && to.isNotEmpty() && from <= to)

    fun query(companyId: String?): Map<String, String> = buildMap {
        if (!companyId.isNullOrEmpty()) put("company_id", companyId)
        if (isCustom) { put("from", from); put("to", to) } else put("days", days.toString())
    }
}

class AiAnalysisViewModel<T>(private val path: String, private val api: FinanceApi, private val decode: suspend (ApiRequest) -> T) : LoadViewModel<T>() {
    private var range = AiRange(90)
    private var companyId: String? = null

    override suspend fun fetch(): T = decode(ApiRequest(path, query = range.query(companyId)))

    fun show(r: AiRange, company: String?) {
        if (!r.ready) return
        if (r == range && company == companyId && state.value.hasData) return
        range = r; companyId = company
        reset(); reload()
    }
}

@Composable
private fun RangeBar(presets: List<Pair<Int, String>>, range: AiRange, onRange: (AiRange) -> Unit, companyId: String?, onCompany: (String?) -> Unit) {
    val companies by LocalBusiness.current.vm.companies.collectAsState()
    Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        HorizontalRow {
            presets.forEach { (days, title) -> InsightChip(title, range.days == days) { onRange(AiRange(days)) } }
            InsightChip("Свои даты", range.isCustom) { onRange(range.copy(days = 0)) }
            if (companies.size > 1) InsightMenu(
                companies.firstOrNull { it.id == companyId }?.name ?: "Все точки",
                listOf("" to "Все точки") + companies.map { it.id to it.name },
            ) { onCompany(it.ifEmpty { null }) }
        }
        if (range.isCustom) {
            val today = AnalyticsPeriod.businessToday().toString()
            DateField("С", range.from.ifEmpty { today }, { onRange(range.copy(from = it, to = range.to.ifEmpty { today })) })
            DateField("По", range.to.ifEmpty { today }, { onRange(range.copy(to = it, from = range.from.ifEmpty { it })) })
        }
    }
}

@Composable
private fun InsightCards(insights: List<AiInsight>, summary: String, title: String) {
    if (summary.isNotBlank()) OwnerSection(title) { Text(summary, color = Orda.colors.text, fontSize = 14.sp) }
    insights.forEach { it ->
        val tone = when (it.severity) { "high" -> "danger"; "low" -> "success"; else -> "warning" }
        ToneCard(tone, it.verdict, buildString {
            if (it.reason.isNotBlank()) append("Почему: ${it.reason}")
            if (it.action.isNotBlank()) { if (isNotEmpty()) append("\n"); append("Что делать: ${it.action}") }
        })
    }
    if (insights.isEmpty() && summary.isBlank()) OwnerFootnote("ИИ-разбор недоступен — показаны только цифры.")
}

@Composable
fun ExpenseAnalysisScreen() {
    val vm = financeViewModel("expenseAnalysis") { _, api ->
        AiAnalysisViewModel("/api/ai/expense-analysis", api) { api.api.send<ExpenseAnalysis>(it) }
    }
    val state by vm.state.collectAsState()
    var range by rememberSaveable(stateSaver = rangeSaver) { mutableStateOf(AiRange(90)) }
    var companyId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(range, companyId) { vm.show(range, companyId) }
    Column(Modifier.fillMaxSize()) {
        RangeBar(listOf(30 to "Месяц", 90 to "Квартал", 180 to "Полгода", 365 to "Год"), range, { range = it }, companyId) { companyId = it }
        StateContainer(state, onRetry = vm::reload, modifier = Modifier.weight(1f), loadingMessage = "ИИ разбирает расходы…") { r ->
            OwnerScreenColumn {
                val m = r.metrics
                HeroSummary(
                    title = "Расходы за период", value = money(m.total),
                    caption = "${BizFormat.percent(m.totalPrevPct, signed = true)} к прошлому периоду",
                    colors = FinanceColors.HeroNegative,
                )
                InsightCards(r.insights, r.summary, "Главное по расходам")
                if (m.categories.isEmpty()) OwnerFootnote("За период расходов нет.")
                else OwnerSection("По статьям") {
                    m.categories.forEach { c ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(c.category, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text(money(c.amount), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                            ShareBar(c.sharePct / 100, FinanceColors.categoryTint(c.category))
                            // Рост расхода — не зелёный: цвет по смыслу.
                            WasDelta(c.amount, c.prev, higherIsBetter = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TeamAnalysisScreen() {
    val vm = financeViewModel("teamAnalysis") { _, api ->
        AiAnalysisViewModel("/api/ai/team-analysis", api) { api.api.send<TeamAnalysis>(it) }
    }
    val state by vm.state.collectAsState()
    var range by rememberSaveable(stateSaver = rangeSaver) { mutableStateOf(AiRange(30)) }
    var companyId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(range, companyId) { vm.show(range, companyId) }
    Column(Modifier.fillMaxSize()) {
        RangeBar(listOf(7 to "Неделя", 14 to "2 недели", 30 to "Месяц", 60 to "2 месяца", 90 to "Квартал"), range, { range = it }, companyId) { companyId = it }
        StateContainer(state, onRetry = vm::reload, modifier = Modifier.weight(1f), loadingMessage = "ИИ разбирает команду…") { r ->
            OwnerScreenColumn {
                val ops = r.metrics.operators
                if (ops.isEmpty()) { OwnerFootnote("Нет данных по операторам за выбранный период."); return@OwnerScreenColumn }
                InsightCards(r.insights, r.summary, "Главное по команде")
                OwnerSection("Операторы за период") {
                    r.metrics.aggregates?.let { OwnerFootnote("${it.dateFrom} — ${it.dateTo} · ${ops.size} ${LedgerLogic.plural(ops.size, "оператор", "оператора", "операторов")}") }
                    ops.sortedByDescending { it.turnover }.forEach { o ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(o.name, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text(money(o.turnover), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text("смен ${o.shifts} · выручка за смену ${money(o.revenuePerShift)}", color = Orda.colors.textDim, fontSize = 12.sp)
                            Text("начислено ${money(o.gross)} · к выплате ${money(o.remaining)}", color = Orda.colors.textDim, fontSize = 12.sp)
                            if (o.bonus != 0.0 || o.fine != 0.0 || o.debt != 0.0) {
                                Text("бонусы ${money(o.bonus)} · штрафы ${money(o.fine)} · долги ${money(o.debt)}", color = Orda.colors.textDim, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val rangeSaver = androidx.compose.runtime.saveable.Saver<AiRange, String>(
    save = { "${it.days}|${it.from}|${it.to}" },
    restore = { s -> s.split('|').let { AiRange(it.getOrNull(0)?.toIntOrNull() ?: 90, it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) } },
)
