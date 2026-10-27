package kz.ordaops.android.feature.business.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.AnalyticsPeriod
import kz.ordaops.android.feature.business.BizData
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.HorizontalRow
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerFootnote
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.operator.shift.AmountField
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.feature.operator.shift.parseAmount
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

/** Планы + факт за год и прогноз идущего месяца («успеем ли»). Прогноз необязателен: без права на него цели всё равно видны. */
data class GoalsBoard(val data: GoalsData, val outlook: GoalsOutlook?)

class GoalsViewModel(private val api: FinanceApi) : LoadViewModel<GoalsBoard>() {
    private var year = AnalyticsPeriod.businessToday().year

    override suspend fun fetch(): GoalsBoard = coroutineScope {
        val data = async { api.api.send<BizData<GoalsData>>(ApiRequest("/api/admin/kpi-plans", query = mapOf("year" to year.toString()))).data }
        val outlook = async { runCatching { api.api.send<GoalsOutlook>(ApiRequest("/api/admin/kpi-plans/outlook")) }.getOrNull() }
        GoalsBoard(data.await(), outlook.await())
    }

    fun show(y: Int) {
        if (y == year && state.value.hasData) return
        year = y
        reset(); reload()
    }

    /** Задать цель (создать или обновить). Возвращает текст ошибки или null. */
    suspend fun save(companyId: String?, period: String, metric: String, target: Double, y: Int, monthIdx: Int): String? = try {
        api.api.sendUnit(
            ApiRequest.json(
                "/api/admin/kpi-plans",
                buildJsonObject {
                    if (companyId == null) put("company_id", JsonNull) else put("company_id", companyId)
                    put("period_kind", period); put("metric", metric); put("target_amount", target)
                    put("year", y)
                    if (period == "month") put("month_idx", monthIdx)
                },
            ),
        )
        reload(); null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.userMessage() }

    suspend fun delete(id: String): String? = try {
        api.api.sendUnit(ApiRequest("/api/admin/kpi-plans", HttpMethod.DELETE, query = mapOf("id" to id)))
        reload(); null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.userMessage() }
}

/** «Цели и планы»: общая цель организации, темп по ритму недели, «успеем ли» и цели по точкам. */
@Composable
fun GoalsScreen() {
    val env = LocalBusiness.current
    val vm = financeViewModel("goals") { _, api -> GoalsViewModel(api) }
    val state by vm.state.collectAsState()
    val today = AnalyticsPeriod.businessToday()
    var year by rememberSaveable { mutableStateOf(today.year) }
    var period by rememberSaveable { mutableStateOf(if (year == today.year) "month" else "year") }
    var monthIdx by rememberSaveable { mutableStateOf(today.monthValue - 1) }
    var companyId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(year) { vm.show(year) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PillSegment(listOf(today.year - 1, today.year).map { it to it.toString() }, year) { year = it }
            PillSegment(GoalsLogic.periods, period) { period = it }
            state.data?.data?.companies?.takeIf { it.size > 1 }?.let { list ->
                HorizontalRow {
                    InsightMenu(list.firstOrNull { it.id == companyId }?.name ?: "Вся сеть", listOf("" to "Вся сеть") + list.map { it.id to it.name }) { companyId = it.ifEmpty { null } }
                }
            }
        }
        StateContainer(state, onRetry = vm::reload, modifier = Modifier.weight(1f)) { board ->
            OwnerScreenColumn {
                GoalsContent(board, vm, year, period, monthIdx, { monthIdx = it }, companyId, env.can("goals.create"), env.can("goals.delete"))
            }
        }
    }
}

@Composable
private fun GoalsContent(
    board: GoalsBoard, vm: GoalsViewModel, year: Int, period: String, monthIdx: Int, onMonth: (Int) -> Unit,
    companyId: String?, canEdit: Boolean, canDelete: Boolean,
) {
    val data = board.data
    val (start, end) = GoalsLogic.periodBounds(period, year, monthIdx)
    val facts = remember(data, companyId, start, end) { GoalsLogic.facts(data.dailyAggregates, companyId, start, end) }
    val weights = remember(data, companyId) { GoalsLogic.rhythm(data.dailyAggregates, data.priorYearDaily, companyId, data.factEnd) }
    var editing by remember { mutableStateOf<String?>(null) }
    val orgScope = companyId == null

    if (period == "month") {
        HorizontalRow {
            (0..11).forEach { m -> InsightChip(RuDates.monthShort(m), m == monthIdx) { onMonth(m) } }
        }
    }
    if (orgScope && !data.orgPlansAvailable) data.orgPlansHint?.let { ToneCard("warning", "Общие цели", it) }
    if (orgScope && data.excludedCompanies.isNotEmpty()) OwnerFootnote("Не в общих итогах: ${data.excludedCompanies.joinToString(", ")} — смотрите их отдельно по точке.")

    GoalsLogic.metrics.forEach { (metric, title) ->
        val target = GoalsLogic.periodTarget(data.plans, period, metric, companyId, monthIdx)
        val fact = facts.of(metric)
        val plan = if (period == "month") GoalsLogic.findPlan(data.plans, "month", monthIdx, metric, companyId) else GoalsLogic.findPlan(data.plans, period, null, metric, companyId)
        val unit = if (metric == "margin") "%" else "₸"
        fun show(v: Double) = if (metric == "margin") BizFormat.percent(v) else money(v)
        OwnerSection(
            title,
            trailing = {
                if (canEdit) TextButton(onClick = { editing = metric }) { Text(if (target.value > 0 && target.source == "plan") "Изменить" else "Задать цель") }
            },
        ) {
            Text(show(fact), color = Orda.colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (target.value > 0) {
                val share = if (target.value > 0) fact / target.value else 0.0
                Text(
                    "Цель ${show(target.value)}${GoalsLogic.sourceLabel(target.source)?.let { " · $it" }.orEmpty()} · выполнено ${BizFormat.percent(share * 100)}",
                    color = Orda.colors.textMuted, fontSize = 14.sp,
                )
                ShareBar(share, if (share >= 1) Orda.colors.positive else Orda.colors.brand)
            } else Text("Цель не задана", color = Orda.colors.textDim, fontSize = 14.sp)
            if (metric == "margin") Text("Выручка ${money(facts.revenue)} · расходы ${money(facts.expenses)}", color = Orda.colors.textDim, fontSize = 12.sp)

            if (GoalsLogic.isAdditive(metric) && target.value > 0) {
                GoalsLogic.pace(target.value, fact, start, end, data.factEnd, weights)?.let { PaceBlock(it, unit) }
                // Прогноз к концу идущего месяца есть только у текущего месяца.
                val scope = board.outlook?.takeIf { period == "month" && it.month == "$year-${(monthIdx + 1).toString().padStart(2, '0')}" }
                    ?.let { if (companyId == null) it.org else it.companies[companyId] }
                val outlook = scope?.outlook
                val verdict = GoalsLogic.verdict(target.value, fact, GoalsLogic.outlookTriple(outlook, metric))
                if (outlook != null && verdict != null) ForecastBlock(verdict, GoalsLogic.outlookTriple(outlook, metric)!!)
            }
            GoalsLogic.yoyBase(data.priorYearDaily, companyId, start, end, data.factEnd, metric)?.let { base ->
                val pct = (fact - base) / base * 100
                Text(
                    "К прошлому году: ${BizFormat.percent(pct, signed = true)} · было ${money(base)} за тот же отрезок",
                    color = if (pct >= 0) Orda.colors.positive else Orda.colors.negative, fontSize = 12.sp,
                )
            }
            if (canDelete && plan != null) {
                var confirm by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                var error by remember { mutableStateOf<String?>(null) }
                TextButton(onClick = { if (confirm) scope.launch { error = vm.delete(plan.id); confirm = false } else confirm = true }) {
                    Text(if (confirm) "Нажмите ещё раз — удалить цель" else "Удалить цель", color = Orda.colors.negative)
                }
                error?.let { ErrorBanner(it) }
            }
        }
    }

    if (period != "month") MonthGrid(data, year, companyId, canEdit) { m -> onMonth(m); editing = "month:$m" }
    if (period != "month") YearSummaryBlock(data, year, period, companyId)

    editing?.let { key ->
        val m = key.substringBefore(':')
        val targetMonth = if (key.contains(':')) key.substringAfter(':').toInt() else monthIdx
        val editPeriod = if (key.contains(':')) "month" else period
        val metric = if (key.contains(':')) "revenue" else m
        GoalSheet(
            vm = vm, year = year, period = editPeriod, monthIdx = targetMonth, metric = metric, companyId = companyId,
            current = GoalsLogic.findPlan(data.plans, editPeriod, if (editPeriod == "month") targetMonth else null, metric, companyId)?.target,
            outlook = board.outlook,
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun PaceBlock(p: GoalPace, unit: String) {
    val ahead = p.gap >= 0
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (p.daysLeft > 0) {
            Text("Темп: нужно ${money(p.requiredPerDay)} в день", color = Orda.colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("на оставшиеся ${p.daysLeft} дн. · сейчас ${money(p.currentPerDay)} в день", color = Orda.colors.textDim, fontSize = 12.sp)
        } else Text("Период закончился.", color = Orda.colors.textDim, fontSize = 13.sp)
        Text(
            "К этому дню по ритму недели должно быть ${money(p.expectedByNow)} — " + if (ahead) "опережаем на ${money(p.gap)}" else "отстаём на ${money(-p.gap)}",
            color = if (ahead) Orda.colors.positive else Orda.colors.warning, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ForecastBlock(v: GoalVerdict, outlook: Triple<Double, Double, Double>) {
    val tone = when (v.status) { "done", "safe", "likely" -> "success"; "at_risk" -> "warning"; else -> "danger" }
    ToneCard(
        tone, "${v.label} · прогноз ~ ${money(outlook.second)}",
        "от ${money(outlook.first)} до ${money(outlook.third)} · модель из «Прогноз и точность»" +
            if (v.status != "done") (if (v.shortfall > 0) "\nНе хватит около ${money(v.shortfall)}" else "\nЗапас около ${money(-v.shortfall)}") else "",
    )
}

@Composable
private fun MonthGrid(data: GoalsData, year: Int, companyId: String?, canEdit: Boolean, onEdit: (Int) -> Unit) {
    OwnerSection("По месяцам · выручка") {
        (0..11).forEach { m ->
            val (s, e) = GoalsLogic.periodBounds("month", year, m)
            val fact = GoalsLogic.facts(data.dailyAggregates, companyId, s, e).revenue
            val target = GoalsLogic.monthTarget(data.plans, m, "revenue", companyId).value
            val closed = e <= data.factEnd
            Row(
                Modifier.fillMaxWidth().then(if (canEdit) Modifier.clickable { onEdit(m) } else Modifier).padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(RuDates.monthName(m), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(if (target > 0) "цель ${money(target)}" else "цели нет", color = Orda.colors.textDim, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (fact > 0 || closed) money(fact) else "—", color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (target > 0 && (fact > 0 || closed)) {
                        val pct = fact / target * 100
                        Text(BizFormat.percent(pct), color = if (fact >= target) Orda.colors.positive else if (closed) Orda.colors.negative else Orda.colors.textDim, fontSize = 12.sp)
                    }
                }
            }
        }
        if (canEdit) OwnerFootnote("Нажмите на месяц, чтобы задать цель выручки.")
    }
}

@Composable
private fun YearSummaryBlock(data: GoalsData, year: Int, period: String, companyId: String?) {
    val months = when (period) { "h1" -> 0..5; "h2" -> 6..11; else -> 0..11 }
    val summary = GoalsLogic.yearSummary(months.map { m ->
        val (s, e) = GoalsLogic.periodBounds("month", year, m)
        val t = GoalsLogic.monthTarget(data.plans, m, "revenue", companyId).value
        GoalsLogic.MonthPlanFact(m + 1, t.takeIf { it > 0 }, GoalsLogic.facts(data.dailyAggregates, companyId, s, e).revenue, e <= data.factEnd)
    })
    if (summary.closedWithPlan == 0) return
    OwnerSection("Итог по месячным целям") {
        Text(
            "План выручки выполнен в ${summary.hit} из ${summary.closedWithPlan} закрытых месяцев · факт ${money(summary.totalFact)} из ${money(summary.totalTarget)} (${BizFormat.percent(summary.totalFact / maxOf(1.0, summary.totalTarget) * 100)})",
            color = Orda.colors.text, fontSize = 14.sp,
        )
        summary.missed.forEach { Text("${RuDates.monthShort(it.month - 1)} ${it.pct}% · не хватило ${money(it.shortfall)}", color = Orda.colors.negative, fontSize = 13.sp) }
    }
}

@Composable
private fun GoalSheet(
    vm: GoalsViewModel, year: Int, period: String, monthIdx: Int, metric: String, companyId: String?,
    current: Double?, outlook: GoalsOutlook?, onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var value by remember { mutableStateOf(current?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val title = (if (period == "month") RuDates.monthName(monthIdx) else GoalsLogic.periods.first { it.first == period }.second) + " $year · " +
        GoalsLogic.metrics.first { it.first == metric }.second
    OrdaSheet(title, onDismiss) {
        if (metric == "margin") Text("Маржа — в процентах.", color = Orda.colors.textDim, fontSize = 13.sp)
        AmountField(if (metric == "margin") "Цель, %" else "Цель", value, { value = it })
        // Подсказка из прогноза: для идущего месяца — сценарии, остальное задаёт владелец.
        val hint = outlook?.takeIf { period == "month" && it.month == "$year-${(monthIdx + 1).toString().padStart(2, '0')}" && metric == "revenue" }
            ?.let { if (companyId == null) it.org else it.companies[companyId] }?.outlook?.outlook
        if (hint != null) {
            Text("Подсказка по прогнозу", color = Orda.colors.textDim, fontSize = 12.sp)
            HorizontalRow {
                listOf("Осторожно" to hint.pessimistic.income, "Реально" to hint.realistic.income, "Амбициозно" to hint.optimistic.income).forEach { (label, v) ->
                    val rounded = (Math.round(v / 10_000.0) * 10_000L).coerceAtLeast(0)
                    InsightChip("$label ${money(rounded.toDouble())}", false) { value = rounded.toString() }
                }
            }
        }
        error?.let { ErrorBanner(it) }
        PrimaryButton("Сохранить цель", Modifier.fillMaxWidth(), loading = saving) {
            val amount = parseAmount(value)
            if (amount <= 0) { error = "Введите сумму больше нуля."; return@PrimaryButton }
            saving = true; error = null
            scope.launch {
                val failure = vm.save(companyId, period, metric, amount, year, monthIdx)
                saving = false
                if (failure == null) { haptics.success(); onDismiss() } else { error = failure; haptics.error() }
            }
        }
    }
}
