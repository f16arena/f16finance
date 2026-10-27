package kz.ordaops.android.feature.business.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.feature.business.AnalyticsPeriod
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.BizList
import kz.ordaops.android.feature.business.BusinessViewModel
import kz.ordaops.android.feature.business.ExtraCashToggle
import kz.ordaops.android.feature.business.HorizontalRow
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerFootnote
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.operator.money.PeriodStepper
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

/** Сырьё недели: приходы и расходы за 8 недель истории + неделя. Баланс считается из них на экране. */
data class WeekData(val incomes: List<WeekIncome>, val expenses: List<WeekExpense>)

class WeeklyViewModel(private val api: FinanceApi) : LoadViewModel<WeekData>() {
    private var weekStart = ""

    override suspend fun fetch(): WeekData {
        val end = WeeklyLogic.addDays(weekStart, 6)
        val q = { from: String -> mapOf("from" to from, "to" to end, "page_size" to "5000") }
        // День до истории: безнал ночной смены переезжает за полночь.
        val incomes = api.api.send<BizList<WeekIncome>>(ApiRequest("/api/admin/incomes", query = q(WeeklyLogic.addDays(weekStart, -WeeklyLogic.HISTORY_DAYS - 1)))).data
        val expenses = api.api.send<BizList<WeekExpense>>(ApiRequest("/api/admin/expenses", query = q(WeeklyLogic.addDays(weekStart, -WeeklyLogic.HISTORY_DAYS)))).data
        return WeekData(incomes, expenses)
    }

    fun show(week: String) {
        if (week == weekStart && state.value.hasData) return
        weekStart = week
        reset()
        reload()
    }
}

private enum class WeekTab(val title: String) { Summary("Итоги"), Cash("Касса"), Days("Дни"), Points("Точки"), Expenses("Расходы") }

/** «Недельный баланс»: неделя против тех же дней прошлых недель, касса нал/безнал, дни, точки, расходы. */
@Composable
fun WeeklyReportScreen() {
    val env = LocalBusiness.current
    val vm = financeViewModel("weekly") { _, api -> WeeklyViewModel(api) }
    val state by vm.state.collectAsState()
    val companies by env.vm.companies.collectAsState()
    val includeExtra by env.vm.includeExtra.collectAsState()
    val pdf = rememberPdfRunner()
    val scope = rememberCoroutineScope()
    val today = AnalyticsPeriod.businessToday().toString()
    val currentMonday = WeeklyLogic.mondayOf(today)
    var weekStart by rememberSaveable { mutableStateOf(currentMonday) }
    var tab by rememberSaveable { mutableStateOf(WeekTab.Summary.name) }
    var compareKey by rememberSaveable { mutableStateOf(CompareMode.Week.key) }
    val compare = CompareMode.entries.first { it.key == compareKey }
    val weekEnd = WeeklyLogic.addDays(weekStart, 6)

    LaunchedEffect(weekStart) { vm.show(weekStart) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    PeriodStepper(
                        title = "${RuDates.dm(weekStart)} – ${RuDates.dm(weekEnd)}",
                        caption = if (weekStart == currentMonday) "текущая неделя" else null,
                        canGoForward = weekStart < currentMonday,
                        onBack = { weekStart = WeeklyLogic.addDays(weekStart, -7) },
                        onForward = { weekStart = WeeklyLogic.addDays(weekStart, 7) },
                    )
                }
                if (env.can("weekly-report.export_pdf")) {
                    if (pdf.running) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = {
                        scope.launch {
                            val q = mapOf("from" to weekStart, "to" to weekEnd, "plan_week" to WeeklyLogic.addDays(currentMonday, 7))
                            pdf.run(ApiRequest("/api/admin/weekly-act/pdf", query = q), "Недельный акт $weekStart")
                        }
                    }) { Icon(Icons.Filled.PictureAsPdf, "PDF недели", tint = Orda.colors.brand) }
                }
            }
            pdf.error?.let { ErrorBanner(it) }
            HorizontalRow {
                InsightMenu("Сравнить: ${compare.short.lowercase()}", CompareMode.entries.map { it.key to it.short }) { compareKey = it }
                if (weekStart != currentMonday) InsightChip("Текущая неделя", false) { weekStart = currentMonday }
            }
            PillSegment(WeekTab.entries.map { it.name to it.title }, tab) { tab = it }
        }
        StateContainer(state, onRetry = vm::reload, modifier = Modifier.weight(1f)) { data ->
            val weekCompanies = remember(companies) { companies.map { WeekCompany(it.id, it.name, BusinessViewModel.isExtra(it)) } }
            val balance = remember(data, weekCompanies, weekStart, includeExtra, compare, today) {
                WeeklyLogic.build(data.incomes, data.expenses, weekCompanies, weekStart, today, includeExtra, compare)
            }
            OwnerScreenColumn {
                if (balance.extraNames.isNotEmpty()) ExtraCashToggle(env)
                when (WeekTab.valueOf(tab)) {
                    WeekTab.Summary -> SummaryTab(balance)
                    WeekTab.Cash -> CashTab(balance)
                    WeekTab.Days -> DaysTab(balance)
                    WeekTab.Points -> PointsTab(balance)
                    WeekTab.Expenses -> ExpensesTab(balance)
                }
            }
        }
    }
}

private fun compareNote(b: WeeklyBalance): String? {
    if (b.compareUntil == null) return null
    return if (b.comparedDays == 7) "Сравнение: ${b.compare.label.removePrefix("те же дни ")}"
    else "Сравнение пн–${WeeklyLogic.DAY_LONG[b.comparedDays - 1].take(2).lowercase()}: ${b.compare.label}"
}

@Composable
private fun SummaryTab(b: WeeklyBalance) {
    val cur = b.current
    HeroSummary(
        title = "Прибыль недели",
        value = money(cur.profit),
        caption = compareNote(b),
        footer = listOf("Выручка" to money(cur.income), "Расходы" to money(cur.expense), "Маржа" to BizFormat.percent(cur.margin)),
        colors = if (cur.profit >= 0) FinanceColors.HeroPositive else FinanceColors.HeroNegative,
    )
    b.alerts.forEach { ToneCard(it.tone, it.title, it.text) }
    if (b.compareUntil != null) {
        OwnerSection("К ${b.compare.short.lowercase()}", ) {
            TileRow {
                StatTile("Выручка", money(b.compared.income), Modifier.weight(1f)) { WasDelta(b.compared.income, b.previous.income, true) }
                StatTile("Расходы", money(b.compared.expense), Modifier.weight(1f)) { WasDelta(b.compared.expense, b.previous.expense, false) }
            }
            TileRow {
                StatTile("Прибыль", money(b.compared.profit), Modifier.weight(1f)) { WasDelta(b.compared.profit, b.previous.profit, true) }
                StatTile("Безналичный", money(b.compared.incomeCashless), Modifier.weight(1f)) { WasDelta(b.compared.incomeCashless, b.previous.incomeCashless, true) }
            }
        }
    }
    if (b.changes.isNotEmpty()) {
        OwnerSection("Что изменилось в прибыли") {
            b.changes.forEach { c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.label, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("было ${money(c.previous)} → стало ${money(c.current)}", color = Orda.colors.textDim, fontSize = 12.sp)
                    }
                    // Эффект на прибыль: рост выручки и падение расхода — хорошо, и наоборот.
                    Text(
                        signedMoney(c.effect), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = if (c.effect >= 0) Orda.colors.positive else Orda.colors.negative,
                    )
                }
            }
            OwnerFootnote("Показано влияние на прибыль недели против базы сравнения.")
        }
    }
    if (b.excludedIncome > 0) OwnerFootnote("Не в общих итогах: ${b.extraNames.joinToString(", ")} — ${money(b.excludedIncome)} выручки.")
}

@Composable
private fun CashTab(b: WeeklyBalance) {
    val cur = b.current
    OwnerSection("Остаток недели") {
        TileRow {
            StatTile("Наличные", signedMoney(cur.netCash), Modifier.weight(1f), if (cur.netCash >= 0) Orda.colors.positive else Orda.colors.negative) {
                Text("пришло ${money(cur.cash)} · ушло ${money(cur.expCash)}", color = Orda.colors.textDim, fontSize = 12.sp)
            }
            StatTile("Безналичный", signedMoney(cur.netCashless), Modifier.weight(1f), if (cur.netCashless >= 0) Orda.colors.positive else Orda.colors.negative) {
                Text("пришло ${money(cur.incomeCashless)} · ушло ${money(cur.expCashless)}", color = Orda.colors.textDim, fontSize = 12.sp)
            }
        }
        OwnerFootnote("Безналичный = терминал и переводы + онлайн + карта. Расход безналом — оплата со счёта.")
    }
    OwnerSection("Чем платили") {
        listOf("Наличные" to cur.cash, "Терминал и переводы" to cur.terminal, "Онлайн" to cur.online, "Карта" to cur.card)
            .filter { it.second != 0.0 }
            .forEach { (name, v) ->
                LineRow(name, money(v))
                ShareBar(if (cur.income > 0) v / cur.income else 0.0, FinanceColors.methodTint(if (name == "Наличные") Methods.Cash else if (name == "Карта") Methods.Card else Methods.Kaspi))
            }
    }
    OwnerSection("Смены") {
        LineRow("День", money(cur.day))
        LineRow("Ночь", money(cur.night))
        OwnerFootnote("Безналичный ночной смены после полуночи относится к следующему дню.")
    }
}

@Composable
private fun DaysTab(b: WeeklyBalance) {
    OwnerSection("Выручка по дням") {
        DayBars(b.days.map { Bar(WeeklyLogic.DAY_LONG[it.weekday].take(2), it.current.income) }, Orda.colors.positive)
    }
    OwnerSection("По дням недели") {
        b.days.forEach { d ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("${WeeklyLogic.DAY_LONG[d.weekday]} ${RuDates.dm(d.date)}", color = if (d.future) Orda.colors.textDim else Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (d.hasData) Text(signedMoney(d.current.profit), color = if (d.current.profit >= 0) Orda.colors.positive else Orda.colors.negative, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                if (d.hasData) {
                    Text("выручка ${money(d.current.income)} · расходы ${money(d.current.expense)}", color = Orda.colors.textDim, fontSize = 12.sp)
                    if (d.previous.income != 0.0) WasDelta(d.current.income, d.previous.income, true)
                } else if (!d.future) Text("данных нет", color = Orda.colors.textDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PointsTab(b: WeeklyBalance) {
    if (b.companies.isEmpty()) { OwnerFootnote("Точек нет."); return }
    b.companies.forEach { c ->
        OwnerSection(c.name, trailing = { if (!c.inTotals) Text("вне итогов", color = Orda.colors.textDim, fontSize = 12.sp) }) {
            TileRow {
                StatTile("Выручка", money(c.current.income), Modifier.weight(1f))
                StatTile("Расходы", money(c.current.expense), Modifier.weight(1f))
            }
            LineRow("Прибыль", signedMoney(c.current.profit), if (c.current.profit >= 0) Orda.colors.positive else Orda.colors.negative, bold = true)
            if (b.compareUntil != null && c.previous.income != 0.0) {
                Text("К ${b.compare.short.lowercase()}", color = Orda.colors.textDim, fontSize = 12.sp)
                WasDelta(c.compared.income, c.previous.income, true)
            }
        }
    }
}

@Composable
private fun ExpensesTab(b: WeeklyBalance) {
    if (b.categories.isEmpty()) { OwnerFootnote("Расходов за неделю нет."); return }
    OwnerSection("Статьи расходов", trailing = { Text(money(b.current.expense), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }) {
        b.categories.forEach { c ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(c.name, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(money(c.total), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                ShareBar(c.share / 100, FinanceColors.categoryTint(c.name))
                if (b.compareUntil != null && (c.compared != 0.0 || c.previous != 0.0)) WasDelta(c.compared, c.previous, higherIsBetter = false)
            }
        }
    }
    if (b.largest.isNotEmpty()) {
        OwnerSection("Крупные расходы") {
            b.largest.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(e.category + if (e.pending) " · на согласовании" else "", color = Orda.colors.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(RuDates.dm(e.date), e.company, e.payee).filter { it.isNotBlank() }.joinToString(" · "), color = Orda.colors.textDim, fontSize = 12.sp, maxLines = 2)
                    }
                    Text(money(e.total), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = Spacing.md))
                }
            }
        }
    }
}
