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
import androidx.compose.material3.Text
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiJson
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.BusinessViewModel
import kz.ordaops.android.feature.business.ExtraCashToggle
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

class AnalysisViewModel(private val api: FinanceApi) : LoadViewModel<ForecastBoard>() {
    private var companyId: String? = null
    private var includeExtra = false

    override suspend fun fetch(): ForecastBoard {
        val query = buildMap {
            if (!companyId.isNullOrEmpty()) put("company_id", companyId!!)
            if (includeExtra) put("include_extra", "1")
        }
        return api.api.send(ApiRequest("/api/admin/monthly-forecast", query = query))
    }

    fun show(company: String?, extra: Boolean) {
        if (company == companyId && extra == includeExtra && state.value.hasData) return
        companyId = company; includeExtra = extra
        reset(); reload()
    }

    /** Короткий вывод ИИ по кнопке (числа посчитаны кодом, ИИ только объясняет). */
    suspend fun askAi(board: ForecastBoard, rows: List<BacktestRow>): String {
        val scenarios = board.next.scenarios ?: return "Прогноза нет."
        val body = buildJsonObject {
            put("targetMonthLabel", RuDates.monthTitle(board.next.targetMonth).lowercase())
            put("scenarios", ApiJson.encodeToJsonElement(Scenarios.serializer(), scenarios))
            put("accuracy", ApiJson.encodeToJsonElement(Accuracy.serializer(), board.next.accuracy))
            put("explanation", JsonArray(board.next.explanation.map { JsonPrimitive(it) }))
            put("misses", JsonArray(rows.map { r ->
                buildJsonObject {
                    put("month", RuDates.monthTitle(r.month).lowercase())
                    put("forecast", r.scenarios.realistic.income)
                    put("actual", r.actual.income)
                    r.error.income?.let { put("error", it) }
                    put("inside", r.inside.income)
                }
            }))
            put("expense", ApiJson.encodeToJsonElement(ExpenseStructure.serializer(), board.forecast.expense))
            put("breakeven", board.forecast.breakeven.revenue)
        }
        val answer = api.api.send<JsonObject>(ApiRequest.json("/api/admin/monthly-forecast/ai", body))
        return answer["text"]?.jsonPrimitive?.contentOrNull ?: "Пусто."
    }
}

/** «Прогноз и точность»: три сценария, сверка с фактом, как модель учится. */
@Composable
fun AnalysisScreen() {
    val env = LocalBusiness.current
    val vm = financeViewModel("analysis") { _, api -> AnalysisViewModel(api) }
    val state by vm.state.collectAsState()
    val companies by env.vm.companies.collectAsState()
    val includeExtra by env.vm.includeExtra.collectAsState()
    var companyId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(companyId, includeExtra) { vm.show(companyId, includeExtra) }

    Column(Modifier.fillMaxSize()) {
        if (companies.size > 1) {
            Column(Modifier.padding(horizontal = Spacing.lg)) {
                HorizontalRow {
                    InsightMenu(
                        companies.firstOrNull { it.id == companyId }?.name ?: "Все точки",
                        listOf("" to "Все точки") + companies.map { it.id to it.name },
                    ) { companyId = it.ifEmpty { null } }
                }
            }
        }
        StateContainer(state, onRetry = vm::reload, modifier = Modifier.weight(1f)) { board ->
            OwnerScreenColumn {
                if (companyId == null) ExtraCashToggle(env)
                ForecastContent(board, vm)
            }
        }
    }
}

@Composable
private fun ForecastContent(board: ForecastBoard, vm: AnalysisViewModel) {
    val env = LocalBusiness.current
    val next = board.next
    val outlook = board.current.outlook
    var tab by rememberSaveable { mutableStateOf(if (outlook != null) "current" else "next") }
    var frozen by rememberSaveable { mutableStateOf(true) }
    val frozenRows = board.accuracyRows(true)
    // Пока зафиксированных сверок нет — показываем расчёт задним числом.
    val useFrozen = frozen && frozenRows.isNotEmpty()
    val rows = board.accuracyRows(useFrozen)

    if (next.scenarios == null && outlook == null) {
        ToneCard("info", "Закрытых месяцев с выручкой пока нет", "Прогнозу не на чем строиться.")
        return
    }
    OwnerSection("Прогноз") {
        HorizontalRow {
            if (outlook != null) InsightChip("${RuDates.monthTitle(outlook.month).substringBefore(' ')} · идёт", tab == "current") { tab = "current" }
            if (next.scenarios != null) InsightChip("${RuDates.monthTitle(next.targetMonth).substringBefore(' ')} · следующий", tab == "next") { tab = "next" }
        }
        val recent = next.accuracy.recentError.income
        val cover = next.accuracy.coverage.income
        Text(
            if (recent != null) "Средняя ошибка модели ${ForecastText.share(recent)} · факт в коридоре ${cover.inside} из ${cover.total}"
            else "Точность появится после первых сверок.",
            color = Orda.colors.textDim, fontSize = 12.sp,
        )
        if (tab == "current" && outlook != null) CurrentOutlook(outlook, board.current.snapshot)
        else next.scenarios?.let { s ->
            val mid = next.basis.midMonth
            Text(
                (if (next.basis.provisionalMonth != null)
                    "Прогноз на ${RuDates.monthTitle(next.targetMonth).lowercase()} с учётом идущего месяца (факт за ${next.basis.knownDays} дн. и оценка остатка): пока месяц не закрыт, коридор шире."
                else "Прогноз на ${RuDates.monthTitle(next.targetMonth).lowercase()} по закрытым месяцам.") +
                    (if (mid?.error != null) " Прогнозы, сделанные ${mid.day}-го числа, раньше ошибались в среднем на ${ForecastText.share(mid.error)}." else ""),
                color = Orda.colors.textMuted, fontSize = 13.sp,
            )
            ScenarioCards(s)
        }
    }
    board.snapshotsWarning?.let { ToneCard("warning", "Фиксация прогнозов", it) }
    if (board.dataQuality.isNotEmpty()) {
        ToneCard(
            "warning", "Неполные месяцы — в обучение не идут",
            board.dataQuality.sortedByDescending { it.month }.joinToString("\n") {
                "${RuDates.monthTitle(it.month)}: ${it.companyName} — " +
                    if (it.days == 0) "нет ни одной записи дохода" else "доходы за ${it.days} из ${it.daysInMonth} дн. (обычно ${it.expectedDays})"
            },
        )
    }
    Learning(board)
    AccuracyBlock(board, rows, useFrozen, frozenRows.isNotEmpty()) { frozen = it }
    History(board)
    board.byCompany?.takeIf { it.isNotEmpty() }?.let { list ->
        OwnerSection("По точкам · ${RuDates.monthTitle(next.targetMonth).lowercase()}") {
            list.forEach { c ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(c.name, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "доход ${money(c.scenarios.realistic.income)} (${BizFormat.axisTick(c.scenarios.pessimistic.income)} … ${BizFormat.axisTick(c.scenarios.optimistic.income)})",
                        color = Orda.colors.textDim, fontSize = 13.sp,
                    )
                    c.recentError?.let { Text("ошибка модели ${ForecastText.share(it)} · сверок ${c.checks}", color = Orda.colors.textDim, fontSize = 12.sp) }
                }
            }
        }
    }
    ExpenseBlock(board)
    if (next.scenarios != null && (env.can("analysis.refresh") || env.can("forecast.generate"))) AiBlock(board, rows, vm)
}

@Composable
private fun CurrentOutlook(o: RunningOutlook, snapshot: SnapshotView?) {
    val remaining = o.daysInMonth - o.knownDays
    Text(
        "Сколько выйдет к концу месяца: факт за ${o.knownDays} дн. плюс оценка оставшихся $remaining дн. Коридор сужается с каждым днём.",
        color = Orda.colors.textMuted, fontSize = 13.sp,
    )
    ScenarioCards(o.outlook)
    Text("Прошло ${o.knownDays} из ${o.daysInMonth} дней · ${ForecastText.share(o.knownShare)}", color = Orda.colors.textDim, fontSize = 12.sp)
    ShareBar(o.knownShare, Orda.colors.brand)
    LineRow("Факт на вчера: доход", money(o.fact.income))
    LineRow("расход", money(o.fact.expense))
    LineRow("прибыль", money(o.fact.profit), if (o.fact.profit >= 0) Orda.colors.positive else Orda.colors.negative, bold = true)
    Text(
        "На начало месяца прогнозировали доход ${money(o.start.realistic.income)} (${BizFormat.axisTick(o.start.pessimistic.income)} … ${BizFormat.axisTick(o.start.optimistic.income)})" +
            (if (snapshot != null) " — зафиксирован ${snapshot.createdAt.take(10).let { RuDates.dm(it) }}${if (snapshot.late) ", не 1-го числа" else ""}."
            else " — расчёт модели, фиксация на этот месяц ещё не велась."),
        color = Orda.colors.textMuted, fontSize = 13.sp,
    )
    o.paceIncome?.let {
        Text("Если остаток месяца пойдёт темпом последних 8 недель — доход около ${money(it)}.", color = Orda.colors.textMuted, fontSize = 13.sp)
    }
}

@Composable
private fun ScenarioCards(s: Scenarios) {
    listOf(
        Triple("Пессимистичный", "если пойдёт хуже обычного", s.pessimistic),
        Triple("Реальный", "самый вероятный исход", s.realistic),
        Triple("Оптимистичный", "если пойдёт лучше обычного", s.optimistic),
    ).forEachIndexed { i, (title, hint, v) ->
        val tint = when (i) { 0 -> Orda.colors.negative; 1 -> Orda.colors.brand; else -> Orda.colors.positive }
        Column(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(title, color = tint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(hint, color = Orda.colors.textDim, fontSize = 12.sp)
            LineRow("Доход", money(v.income))
            LineRow("Расход", money(v.expense))
            LineRow("Прибыль", money(v.profit), if (v.profit >= 0) Orda.colors.text else Orda.colors.negative, bold = true)
        }
    }
}

@Composable
private fun Learning(board: ForecastBoard) {
    val weights = board.next.calibration?.weights?.income.orEmpty().entries.sortedByDescending { it.value }
    OwnerSection("Как модель учится") {
        if (weights.isNotEmpty()) {
            Text("Вес способов прогноза дохода — больше у того, кто меньше ошибался", color = Orda.colors.textDim, fontSize = 12.sp)
            weights.forEach { (method, w) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(ForecastText.methodLabels[method] ?: method, color = Orda.colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(ForecastText.share(w), color = Orda.colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                ShareBar(w, Orda.colors.brand)
            }
        }
        board.next.explanation.forEach { Text("• $it", color = Orda.colors.textMuted, fontSize = 13.sp) }
    }
}

@Composable
private fun AccuracyBlock(board: ForecastBoard, rows: List<BacktestRow>, frozen: Boolean, hasFrozen: Boolean, onSource: (Boolean) -> Unit) {
    OwnerSection("Точность: прогноз против факта") {
        if (hasFrozen) HorizontalRow {
            InsightChip("Зафиксированные", frozen) { onSource(true) }
            InsightChip("Задним числом", !frozen) { onSource(false) }
        }
        ForecastText.trendText(board.next.accuracy.trend)?.let { Text(it, color = Orda.colors.textMuted, fontSize = 13.sp) }
        if (rows.isEmpty()) Text("Сверок пока нет — они появятся, когда закроются первые месяцы с прогнозом.", color = Orda.colors.textDim, fontSize = 13.sp)
        val incomplete = board.dataQuality.map { it.month }.toSet()
        rows.sortedByDescending { it.month }.take(12).forEach { r ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(RuDates.monthTitle(r.month) + if (r.month in incomplete) " · неполный" else "", color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(
                        if (r.inside.income) "в коридоре" else "вне коридора",
                        color = if (r.inside.income) Orda.colors.positive else Orda.colors.negative, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Text("прогноз ${money(r.scenarios.realistic.income)} · факт ${money(r.actual.income)}", color = Orda.colors.textDim, fontSize = 13.sp)
                r.error.income?.let { Text("ошибка ${ForecastText.share(it)}", color = Orda.colors.textDim, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun History(board: ForecastBoard) {
    val months = board.forecast.months.takeLast(12).reversed()
    if (months.isEmpty()) return
    OwnerSection("История по месяцам") {
        months.forEach { m ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(RuDates.monthTitle(m.month) + if (m.isPartial) " · идёт" else "", color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(signedMoney(m.netProfit), color = if (m.netProfit >= 0) Orda.colors.positive else Orda.colors.negative, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("доход ${money(m.income)} · расход ${money(m.totalExpense)}", color = Orda.colors.textDim, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ExpenseBlock(board: ForecastBoard) {
    val e = board.forecast.expense
    if (e.expected == 0.0 && board.forecast.expenseByGroup.isEmpty()) return
    OwnerSection("Структура расхода") {
        LineRow("Постоянные в месяц", money(e.fixed))
        LineRow("Переменные", "${BizFormat.percent(e.variableRatePct)} от дохода")
        LineRow("Разовые в среднем", money(e.oneOffAvg))
        if (board.forecast.breakeven.revenue > 0) LineRow("Безубыточность при доходе", money(board.forecast.breakeven.revenue), bold = true)
        OwnerFootnote("Разовые платежи в прогноз не входят.")
        board.forecast.expenseByGroup.sortedByDescending { it.amount }.forEach { g ->
            LineRow(g.label + if (g.bucket == "fixed") " · постоянные" else " · переменные", money(g.amount))
        }
    }
}

@Composable
private fun AiBlock(board: ForecastBoard, rows: List<BacktestRow>, vm: AnalysisViewModel) {
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    OwnerSection("ИИ-вывод") {
        Text(
            text ?: "ИИ объяснит, насколько верить прогнозу по истории промахов, и подскажет, как приблизить месяц к оптимистичному сценарию.",
            color = if (text != null) Orda.colors.text else Orda.colors.textDim, fontSize = 14.sp,
        )
        error?.let { ErrorBanner(it) }
        OutlinedButton(
            onClick = {
                loading = true; error = null
                scope.launch {
                    try { text = vm.askAi(board, rows) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.userMessage() }
                    loading = false
                }
            },
            enabled = !loading,
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Получить вывод")
        }
    }
}
