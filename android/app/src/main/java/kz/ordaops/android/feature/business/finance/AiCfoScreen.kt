package kz.ordaops.android.feature.business.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.AnalyticsPeriod
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.HorizontalRow
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerFootnote
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing
import java.time.LocalDate
import kotlin.math.roundToLong

// AI Финдиректор (/ai-cfo). Цифры считает сервер кодом (`lib/analysis/cfo-review`),
// ИИ — только по кнопке и только объясняет готовый разбор.

@Serializable
data class CfoExecutive(
    val revenue: Double = 0.0, val revenueDeltaPct: Double = 0.0,
    val expenses: Double = 0.0, val expensesDeltaPct: Double = 0.0,
    val profit: Double = 0.0, val profitDeltaPct: Double = 0.0,
    val margin: Double = 0.0, val marginDeltaPp: Double = 0.0,
)

@Serializable
data class CfoPrevious(val revenue: Double = 0.0, val expenses: Double = 0.0, val profit: Double = 0.0)

@Serializable
data class CfoBridgeLine(val key: String = "", val label: String = "", val kind: String = "", val current: Double = 0.0, val previous: Double = 0.0, val effect: Double = 0.0)

@Serializable
data class CfoBridge(val startProfit: Double = 0.0, val endProfit: Double = 0.0, val lines: List<CfoBridgeLine> = emptyList())

@Serializable
data class CfoCompany(
    val companyId: String = "", val name: String = "Точка", val revenue: Double = 0.0, val expenses: Double = 0.0,
    val profit: Double = 0.0, val margin: Double = 0.0, val profitShare: Double = 0.0, val profitDelta: Double = 0.0, val previousProfit: Double = 0.0,
)

@Serializable
data class CfoCosts(
    val variableExpenses: Double = 0.0, val fixedExpenses: Double = 0.0, val oneOffExpenses: Double = 0.0,
    val incomeTax: Double = 0.0, val profitDistribution: Double = 0.0, val payroll: Double = 0.0,
    val breakevenRevenue: Double = 0.0, val safetyMarginPct: Double = 0.0, val operatingProfit: Double = 0.0,
)

@Serializable
data class CfoGap(val name: String = "", val daysWithSales: Int = 0, val missingDays: Int = 0)

@Serializable
data class CfoQuality(val percent: Double = 100.0, val daysInPeriod: Int = 0, val daysWithSales: Int = 0, val gaps: List<CfoGap> = emptyList())

@Serializable
data class CfoHealthItem(val label: String = "", val points: Double = 0.0, val max: Double = 0.0, val note: String = "")

@Serializable
data class CfoHealth(val score: Double = 0.0, val band: String = "", val items: List<CfoHealthItem> = emptyList(), val missing: List<String> = emptyList())

@Serializable data class Tagged(val text: String = "", val status: String = "")
@Serializable data class AiRisk(val risk: String = "", val probability: String = "", val impact: String = "", val level: String = "")
@Serializable data class AiLoss(val text: String = "", val amount: String = "", val status: String = "")
@Serializable data class AiMissed(val text: String = "", val potential: String = "", val status: String = "")
@Serializable data class AiOpportunity(val title: String = "", val action: String = "", val effect: String = "", val status: String = "")
@Serializable data class AiPlan(val today: List<String> = emptyList(), val week: List<String> = emptyList(), val month: List<String> = emptyList())

@Serializable
data class AiSummary(
    @kotlinx.serialization.SerialName("where_losing") val whereLosing: String = "",
    @kotlinx.serialization.SerialName("where_earn") val whereEarn: String = "",
    @kotlinx.serialization.SerialName("main_risk") val mainRisk: String = "",
    @kotlinx.serialization.SerialName("main_opportunity") val mainOpportunity: String = "",
    @kotlinx.serialization.SerialName("extra_profit") val extraProfit: String = "",
    @kotlinx.serialization.SerialName("three_actions") val threeActions: List<String> = emptyList(),
)

@Serializable
data class CfoAi(
    val state: String? = null,
    val changes: List<Tagged> = emptyList(),
    val rootCauses: List<Tagged> = emptyList(),
    val risks: List<AiRisk> = emptyList(),
    val losses: List<AiLoss> = emptyList(),
    val missedProfit: List<AiMissed> = emptyList(),
    val opportunities: List<AiOpportunity> = emptyList(),
    val actionPlan: AiPlan? = null,
    val summary: AiSummary? = null,
    val error: String? = null,
)

@Serializable
data class CfoReport(
    val dateFrom: String = "", val dateTo: String = "", val prevFrom: String = "", val prevTo: String = "",
    val hasExtra: Boolean = false, val extraNames: List<String> = emptyList(),
    val executive: CfoExecutive = CfoExecutive(),
    val previous: CfoPrevious = CfoPrevious(),
    val bridge: CfoBridge = CfoBridge(),
    val companies: List<CfoCompany> = emptyList(),
    val costStructure: CfoCosts = CfoCosts(),
    val fotShare: Double = 0.0,
    val dataQuality: CfoQuality = CfoQuality(),
    val health: CfoHealth = CfoHealth(),
    val ai: CfoAi? = null,
    val error: String? = null,
)

object CfoLogic {
    /** Операционная прибыль при изменениях — как `whatIf` на сервере: цена трогает выручку, поток — выручку и переменные, постоянные — сами. */
    data class WhatIf(val revenue: Double, val operatingProfit: Double, val delta: Double)

    fun whatIf(revenue: Double, variable: Double, fixed: Double, pricePct: Double, volumePct: Double, fixedPct: Double): WhatIf {
        val newRevenue = revenue * (1 + pricePct / 100) * (1 + volumePct / 100)
        val newVariable = variable * (1 + volumePct / 100)
        val newFixed = fixed * (1 + fixedPct / 100)
        val profit = newRevenue - newVariable - newFixed
        val base = revenue - variable - fixed
        return WhatIf(newRevenue.roundToLong().toDouble(), profit.roundToLong().toDouble(), (profit - base).roundToLong().toDouble())
    }

    /** Модель иногда оставляет теги статуса в тексте. */
    fun clean(s: String?): String = (s ?: "").replace(Regex("\\[(ФАКТ|ОЦЕНКА|ГИПОТЕЗА)]\\s*", RegexOption.IGNORE_CASE), "").trim()

    /** Месяц `YYYY-MM` → границы. */
    fun monthRange(ym: String): Pair<String, String> {
        val first = LocalDate.parse("$ym-01")
        return first.toString() to first.withDayOfMonth(first.lengthOfMonth()).toString()
    }

    fun healthWord(band: String) = when (band) { "healthy" -> "здоровый бизнес"; "attention" -> "нужно внимание"; else -> "есть проблемы" }
}

class CfoViewModel(private val api: FinanceApi) : LoadViewModel<CfoReport>() {
    private var from = ""
    private var to = ""
    private var companyId: String? = null
    private var includeExtra = false

    private fun body(ai: Boolean): JsonObject = buildJsonObject {
        put("dateFrom", from); put("dateTo", to)
        if (!companyId.isNullOrEmpty()) put("company_id", companyId!!) else if (includeExtra) put("include_extra", true)
        put("ai", ai)
    }

    /** Цифры — без ИИ (`ai: false`), как на сайте. */
    override suspend fun fetch(): CfoReport = api.api.send(ApiRequest.json("/api/ai/cfo", body(false)))

    fun show(f: String, t: String, company: String?, extra: Boolean) {
        if (f == from && t == to && company == companyId && extra == includeExtra && state.value.hasData) return
        from = f; to = t; companyId = company; includeExtra = extra
        reset(); reload()
    }

    /** Разбор ИИ — отдельным запросом. */
    suspend fun askAi(): CfoAi {
        val answer = api.api.send<CfoReport>(ApiRequest.json("/api/ai/cfo", body(true)))
        return answer.ai ?: CfoAi(error = answer.error ?: "ИИ недоступен")
    }
}

/** «AI Финдиректор»: почему прибыль такая, что на неё повлияло и что с этим делать. */
@Composable
fun AiCfoScreen() {
    val env = LocalBusiness.current
    val vm = financeViewModel("aicfo") { _, api -> CfoViewModel(api) }
    val state by vm.state.collectAsState()
    val companies by env.vm.companies.collectAsState()
    val today = AnalyticsPeriod.businessToday()
    // По умолчанию — текущий месяц против тех же дней прошлого; 1-го числа он ещё пуст — тогда прошлый целиком.
    val defaultMonth = (if (today.dayOfMonth == 1) today.minusMonths(1) else today).toString().take(7)
    var period by rememberSaveable { mutableStateOf("m:$defaultMonth") }
    var companyId by rememberSaveable { mutableStateOf<String?>(null) }
    var includeExtra by rememberSaveable { mutableStateOf(false) }
    val months = remember(today) { (0..11).map { today.minusMonths(it.toLong()).toString().take(7) } }
    val (from, to) = remember(period) {
        if (period.startsWith("m:")) CfoLogic.monthRange(period.removePrefix("m:"))
        else {
            val days = period.removePrefix("d").toLongOrNull() ?: 30
            val end = today.minusDays(1)
            end.minusDays(days - 1).toString() to end.toString()
        }
    }
    LaunchedEffect(from, to, companyId, includeExtra) { vm.show(from, to, companyId, includeExtra) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Spacing.lg)) {
            HorizontalRow {
                InsightMenu(
                    if (period.startsWith("m:")) RuDates.monthTitle(period.removePrefix("m:")) else "${period.removePrefix("d")} дн.",
                    months.mapIndexed { i, m -> "m:$m" to (RuDates.monthTitle(m) + if (i == 0) " (идёт)" else "") } +
                        listOf("d7" to "7 дней", "d30" to "30 дней", "d90" to "90 дней", "d365" to "Год"),
                ) { period = it }
                if (companies.size > 1) InsightMenu(
                    companies.firstOrNull { it.id == companyId }?.name ?: "Все точки",
                    listOf("" to "Все точки") + companies.map { it.id to it.name },
                ) { companyId = it.ifEmpty { null } }
                if (state.data?.hasExtra == true && companyId == null) {
                    InsightChip(if (includeExtra) "С ${state.data?.extraNames?.joinToString(", ")}" else "Без ${state.data?.extraNames?.joinToString(", ")}", includeExtra) { includeExtra = !includeExtra }
                }
            }
        }
        StateContainer(state, onRetry = vm::reload, modifier = Modifier.weight(1f)) { r ->
            OwnerScreenColumn { CfoContent(r, vm, env.can("ai-cfo.generate"), from, to) }
        }
    }
}

@Composable
private fun CfoContent(r: CfoReport, vm: CfoViewModel, canGenerate: Boolean, from: String, to: String) {
    val env = LocalBusiness.current
    val ex = r.executive
    val noPrev = r.previous.revenue == 0.0 && r.previous.expenses == 0.0
    HeroSummary(
        title = "Прибыль за период",
        value = money(ex.profit),
        caption = "${RuDates.dm(r.dateFrom)} – ${RuDates.dm(r.dateTo)} · против ${RuDates.dm(r.prevFrom)} – ${RuDates.dm(r.prevTo)}",
        footer = listOf("Выручка" to money(ex.revenue), "Расходы" to money(ex.expenses), "Маржа" to BizFormat.percent(ex.margin)),
        colors = if (ex.profit >= 0) FinanceColors.HeroPositive else FinanceColors.HeroNegative,
    )
    if (!noPrev) {
        OwnerSection("К прошлому периоду") {
            TileRow {
                StatTile("Выручка", money(ex.revenue), Modifier.weight(1f)) { WasDelta(ex.revenue, r.previous.revenue, true) }
                StatTile("Расходы", money(ex.expenses), Modifier.weight(1f)) { WasDelta(ex.expenses, r.previous.expenses, false) }
            }
            WasDelta(ex.profit, r.previous.profit, true)
        }
    } else OwnerFootnote("Прошлого периода для сравнения нет.")
    val q = r.dataQuality
    if (q.percent < 80 || q.gaps.isNotEmpty()) {
        ToneCard("warning", "Данные внесены на ${BizFormat.percent(q.percent)}", q.gaps.joinToString("\n") { "${it.name}: не хватает ${it.missingDays} дн. с продажами" })
    }
    if (r.bridge.lines.isNotEmpty()) {
        OwnerSection("Почему изменилась прибыль") {
            LineRow("Было", money(r.bridge.startProfit))
            r.bridge.lines.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.label, color = Orda.colors.text, fontSize = 15.sp)
                        Text("было ${money(l.previous)} → стало ${money(l.current)}", color = Orda.colors.textDim, fontSize = 12.sp)
                    }
                    // Влияние на прибыль: плюс добавило, минус отняло.
                    Text(signedMoney(l.effect), color = if (l.effect >= 0) Orda.colors.positive else Orda.colors.negative, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            LineRow("Стало", money(r.bridge.endProfit), bold = true)
        }
    }
    if (r.companies.size > 1) {
        OwnerSection("Точки") {
            r.companies.forEach { c ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(c.name, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(money(c.profit), color = if (c.profit >= 0) Orda.colors.text else Orda.colors.negative, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("выручка ${money(c.revenue)} · расходы ${money(c.expenses)} · маржа ${BizFormat.percent(c.margin)}", color = Orda.colors.textDim, fontSize = 12.sp)
                    if (!noPrev) WasDelta(c.profit, c.previousProfit, true)
                }
            }
        }
    }
    val cs = r.costStructure
    OwnerSection("Структура расходов") {
        LineRow("Постоянные", money(cs.fixedExpenses))
        LineRow("Переменные", money(cs.variableExpenses))
        LineRow("Разовые и оборудование", money(cs.oneOffExpenses))
        if (cs.incomeTax != 0.0) LineRow("Налог на прибыль", money(cs.incomeTax))
        if (cs.profitDistribution != 0.0) LineRow("Выплаты партнёрам (не расход бизнеса)", money(cs.profitDistribution))
        LineRow("Зарплаты в выручке", BizFormat.percent(r.fotShare))
        LineRow("Операционная прибыль", money(cs.operatingProfit), bold = true)
    }
    OwnerSection("Запас прочности") {
        if (cs.breakevenRevenue > 0) {
            LineRow("Безубыточность при выручке", money(cs.breakevenRevenue), bold = true)
            LineRow("Запас прочности", BizFormat.percent(cs.safetyMarginPct), if (cs.safetyMarginPct >= 20) Orda.colors.positive else Orda.colors.warning)
            OwnerFootnote("Насколько может упасть выручка, прежде чем операционная прибыль уйдёт в минус.")
        } else OwnerFootnote("Данных для расчёта безубыточности пока нет.")
    }
    WhatIfBlock(r)
    OwnerSection("Здоровье бизнеса", trailing = { Text("${r.health.score.roundToLong()} / 100", color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold) }) {
        Text(CfoLogic.healthWord(r.health.band), color = Orda.colors.textMuted, fontSize = 13.sp)
        r.health.items.forEach { i ->
            Row(Modifier.fillMaxWidth()) {
                Text(i.label, color = Orda.colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("${i.points.roundToLong()} из ${i.max.roundToLong()}", color = Orda.colors.textDim, fontSize = 13.sp)
            }
            ShareBar(if (i.max > 0) i.points / i.max else 0.0, Orda.colors.brand)
            if (i.note.isNotBlank()) Text(i.note, color = Orda.colors.textDim, fontSize = 12.sp)
        }
        OwnerFootnote("Оценка — формула кода, а не мнение модели.")
    }
    OwnerSection("Прогноз прибыли") {
        Text("Из раздела «Прогноз и точность» — модель сверяется с фактом каждый месяц.", color = Orda.colors.textMuted, fontSize = 13.sp)
        TextButton(onClick = { env.open("analysis") }) { Text("Подробнее") }
    }
    AiBlock(vm, canGenerate)
}

@Composable
private fun WhatIfBlock(r: CfoReport) {
    var price by remember { mutableStateOf(0f) }
    var volume by remember { mutableStateOf(0f) }
    var fixed by remember { mutableStateOf(0f) }
    val cs = r.costStructure
    val res = CfoLogic.whatIf(r.executive.revenue, cs.variableExpenses, cs.fixedExpenses, price.toDouble(), volume.toDouble(), fixed.toDouble())
    OwnerSection("Что если", trailing = { if (price != 0f || volume != 0f || fixed != 0f) TextButton(onClick = { price = 0f; volume = 0f; fixed = 0f }) { Text("Сбросить") } }) {
        Text("Подвигайте и посмотрите, как изменится прибыль за такой же период.", color = Orda.colors.textDim, fontSize = 13.sp)
        PctSlider("Цены", price, true) { price = it }
        PctSlider("Гостей и продаж", volume, true) { volume = it }
        // Цвет по смыслу: рост постоянных расходов — плохо.
        PctSlider("Постоянные расходы", fixed, false) { fixed = it }
        LineRow("Операционная прибыль", money(res.operatingProfit), bold = true)
        Text(signedMoney(res.delta), color = if (res.delta >= 0) Orda.colors.positive else Orda.colors.negative, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("сейчас ${money(cs.operatingProfit)} · выручка ${money(res.revenue)}", color = Orda.colors.textDim, fontSize = 12.sp)
    }
}

@Composable
private fun PctSlider(label: String, value: Float, goodWhenUp: Boolean, onChange: (Float) -> Unit) {
    val v = value.toInt()
    val color = when { v == 0 -> Orda.colors.textDim; (v > 0) == goodWhenUp -> Orda.colors.positive; else -> Orda.colors.negative }
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Orda.colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("${if (v > 0) "+" else ""}$v%", color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = { onChange(Math.round(it).toFloat()) }, valueRange = -30f..30f, steps = 59)
    }
}

@Composable
private fun AiBlock(vm: CfoViewModel, canGenerate: Boolean) {
    val scope = rememberCoroutineScope()
    var ai by remember { mutableStateOf<CfoAi?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    OwnerSection("Разбор ИИ") {
        if (ai == null) Text("ИИ объяснит готовый разбор простыми словами и предложит шаги. Цифры в нём посчитаны кодом.", color = Orda.colors.textDim, fontSize = 13.sp)
        failure?.let { ErrorBanner(it) }
        ai?.error?.let { ErrorBanner(it) }
        ai?.takeIf { it.error == null }?.let { AiResult(it) }
        if (canGenerate) OutlinedButton(
            onClick = {
                loading = true; failure = null
                scope.launch {
                    try { ai = vm.askAi() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failure = e.userMessage() }
                    loading = false
                }
            },
            enabled = !loading,
        ) { if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(if (ai == null) "Получить разбор" else "Обновить разбор") }
        else OwnerFootnote("Запуск разбора ИИ — по праву «ai-cfo.generate».")
    }
}

@Composable
private fun AiResult(ai: CfoAi) {
    val c = CfoLogic::clean
    ai.state?.let { Text(c(it), color = Orda.colors.text, fontSize = 14.sp) }
    if (ai.changes.isNotEmpty()) { SubTitle("Что изменилось"); ai.changes.forEach { Bullet(c(it.text), it.status) } }
    if (ai.rootCauses.isNotEmpty()) { SubTitle("Почему"); ai.rootCauses.forEach { Bullet(c(it.text), it.status) } }
    if (ai.risks.isNotEmpty()) { SubTitle("Риски"); ai.risks.forEach { Bullet(c(it.risk), "${it.probability} / ${it.impact}") } }
    if (ai.losses.isNotEmpty()) { SubTitle("Где утекают деньги"); ai.losses.forEach { Bullet(c(it.text) + if (it.amount.isNotBlank()) " — ${it.amount}" else "", it.status) } }
    if (ai.missedProfit.isNotEmpty()) { SubTitle("Упущенная прибыль"); ai.missedProfit.forEach { Bullet(c(it.text) + if (it.potential.isNotBlank()) " — ${it.potential}" else "", it.status) } }
    if (ai.opportunities.isNotEmpty()) { SubTitle("Возможности"); ai.opportunities.forEach { Bullet("${c(it.title)}: ${c(it.action)}" + if (it.effect.isNotBlank()) " (${it.effect})" else "", it.status) } }
    ai.actionPlan?.let { p ->
        SubTitle("План действий")
        p.today.forEach { Bullet(c(it), "сегодня") }
        p.week.forEach { Bullet(c(it), "неделя") }
        p.month.forEach { Bullet(c(it), "месяц") }
    }
    ai.summary?.let { s ->
        SubTitle("Коротко")
        if (s.whereLosing.isNotBlank()) LineRow("Где теряем", c(s.whereLosing))
        if (s.whereEarn.isNotBlank()) LineRow("Где зарабатываем", c(s.whereEarn))
        if (s.mainRisk.isNotBlank()) LineRow("Главный риск", c(s.mainRisk))
        if (s.mainOpportunity.isNotBlank()) LineRow("Главная возможность", c(s.mainOpportunity))
        if (s.extraProfit.isNotBlank()) LineRow("Дополнительная прибыль", c(s.extraProfit))
        s.threeActions.forEachIndexed { i, a -> Bullet("${i + 1}. ${c(a)}", "") }
    }
}

@Composable
private fun SubTitle(text: String) = Text(text, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Bullet(text: String, tag: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("• $text", color = Orda.colors.textMuted, fontSize = 13.sp)
        if (tag.isNotBlank()) Text(tag, color = Orda.colors.textDim, fontSize = 11.sp)
    }
}
