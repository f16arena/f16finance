package kz.ordaops.android.feature.business.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.feature.business.AnalyticsPeriod
import kz.ordaops.android.feature.business.BizData
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.ChangeText
import kz.ordaops.android.feature.business.ExtraCashToggle
import kz.ordaops.android.feature.business.HorizontalRow
import kz.ordaops.android.feature.business.LetterBadge
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.business.OwnerTint
import kz.ordaops.android.feature.business.PeriodBar
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.operator.money.TintedIcon
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

/** Отчёт за период с фильтрами сайта. Запрос без сырых строк (`rows=0`) — иначе пришли бы все операции. */
class ReportsViewModel(private val api: FinanceApi) : LoadViewModel<ReportBundle>() {
    private var query = ReportQuery("", "")

    override suspend fun fetch(): ReportBundle {
        val asOf = AnalyticsPeriod.businessToday().toString()
        return api.api.send<BizData<ReportBundle>>(ApiRequest("/api/admin/reports/bundle", query = query.queryItems(rows = "0", asOf = asOf))).data
    }

    fun show(q: ReportQuery) {
        if (q == query && state.value.hasData) return
        query = q
        reset()
        reload()
    }
}

private enum class ReportTab(val title: String) { Overview("Обзор"), Expenses("Расходы"), Points("Точки") }

/** «Отчёты»: обзор (прибыль, график, оплаты), статьи расходов, точки. */
@Composable
fun ReportsScreen() {
    val env = LocalBusiness.current
    val vm = financeViewModel("reports") { _, api -> ReportsViewModel(api) }
    val state by vm.state.collectAsState()
    val companies by env.vm.companies.collectAsState()
    val includeExtra by env.vm.includeExtra.collectAsState()
    var period by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<AnalyticsPeriod, String>(
        save = { it.key }, restore = { AnalyticsPeriod.fromKey(it) ?: AnalyticsPeriod.ThisMonth },
    )) { mutableStateOf<AnalyticsPeriod>(AnalyticsPeriod.ThisMonth) }
    var tab by rememberSaveable { mutableStateOf(ReportTab.Overview.name) }
    var companyId by rememberSaveable { mutableStateOf<String?>(null) }
    var shift by rememberSaveable { mutableStateOf("all") }
    var grouping by rememberSaveable { mutableStateOf("auto") }
    var compareYear by rememberSaveable { mutableStateOf(false) }

    val (from, to) = period.bounds()
    LaunchedEffect(from, to, companyId, shift, grouping, compareYear, includeExtra) {
        vm.show(ReportQuery(from, to, companyId, shift, grouping, compareYear, includeExtra))
    }
    val compareWord = if (compareYear) "к прошлому году" else "к прошлому периоду"

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PeriodBar(
                selection = period,
                quick = listOf(AnalyticsPeriod.ThisWeek, AnalyticsPeriod.ThisMonth, AnalyticsPeriod.ThisQuarter, AnalyticsPeriod.ThisYear),
                onSelect = { period = it },
                extraCash = if (companyId == null) ({ ExtraCashToggle(env) }) else null,
            )
            HorizontalRow {
                MenuChip(companies.firstOrNull { it.id == companyId }?.name ?: "Все точки", listOf("" to "Все точки") + companies.map { it.id to it.name }) { companyId = it.ifEmpty { null } }
                MenuChip(ReportQuery.shifts.first { it.first == shift }.second, ReportQuery.shifts) { shift = it }
                MenuChip(if (grouping == "auto") "Шаг: авто" else ReportQuery.groupings.first { it.first == grouping }.second, ReportQuery.groupings) { grouping = it }
                MenuChip(if (compareYear) "С прошлым годом" else "С прошлым периодом", listOf("prev" to "С прошлым периодом", "year" to "С прошлым годом")) { compareYear = it == "year" }
            }
            PillSegment(ReportTab.entries.map { it.name to it.title }, tab) { tab = it }
        }
        StateContainer(state, onRetry = vm::reload, modifier = Modifier.weight(1f)) { bundle ->
            OwnerScreenColumn {
                when (ReportTab.valueOf(tab)) {
                    ReportTab.Overview -> Overview(bundle, compareWord)
                    ReportTab.Expenses -> Articles(bundle, compareWord)
                    ReportTab.Points -> Points(bundle.aggregate)
                }
            }
        }
    }
}

/** Выпадающий фильтр-капсула. */
@Composable
private fun MenuChip(title: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.pressScale().clip(CircleShape).background(Orda.colors.surface).clickable { open = true }.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = Orda.colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Icon(Icons.Filled.ExpandMore, null, tint = Orda.colors.textDim, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onPick(id); open = false }) }
        }
    }
}

@Composable
private fun Overview(bundle: ReportBundle, compareWord: String) {
    val a = bundle.aggregate
    val cur = a.totalsCur
    if (cur.totalIncome == 0.0 && cur.totalExpense == 0.0) {
        EmptyState(Icons.Filled.BarChart, "Данных нет", "За выбранный период нет ни доходов, ни расходов.")
        return
    }
    // Прибыль — разница журналов «Доходы» и «Расходы»; отклонённые расходы сюда не входят.
    HeroSummary(
        title = "Прибыль за период",
        value = money(cur.profit),
        caption = a.profitChange?.let { "${BizFormat.percent(it, signed = true)} $compareWord" },
        footer = listOf("Доходы" to money(cur.totalIncome), "Расходы" to money(cur.totalExpense), "Операций" to "${cur.transactionCount}"),
        colors = if (cur.profit >= 0) FinanceColors.HeroPositive else FinanceColors.HeroNegative,
    )
    if (bundle.impreciseNightKaspiCount > 0) {
        val n = bundle.impreciseNightKaspiCount
        Text(
            "В $n ${LedgerLogic.plural(n, "ночной смене", "ночных сменах", "ночных сменах")} безналичный не разделён по полуночи — суммы по дням приблизительные, итог точный.",
            color = Orda.colors.textDim, fontSize = 12.sp,
        )
    }
    OwnerSection("Доходы и расходы") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Metric("Доходы", cur.totalIncome, a.incomeChange, higherIsBetter = true, Modifier.weight(1f))
            Metric("Расходы", cur.totalExpense, a.expenseChange, higherIsBetter = false, Modifier.weight(1f))
        }
        if (a.chartData.size > 1) {
            Text("Доходы по периодам", color = Orda.colors.textDim, fontSize = 13.sp)
            DayBars(a.chartData.map { Bar(it.label, it.income) }, Orda.colors.positive)
            Text("Прибыль по периодам", color = Orda.colors.textDim, fontSize = 13.sp)
            TrendLine(a.chartData.map { Bar(it.label, it.profit) }, Orda.colors.brand)
        }
    }
    val methods = listOf(Methods.Cash to cur.incomeCash, Methods.Kaspi to cur.incomeKaspi, Methods.Card to cur.incomeCard, Methods.Online to cur.incomeOnline)
        .filter { it.second > 0 }
    if (methods.isNotEmpty()) {
        OwnerSection("Чем платили", trailing = { Text("средний чек ${money(cur.avgTransaction)}", color = Orda.colors.textDim, fontSize = 13.sp) }) {
            val total = methods.sumOf { it.second }.coerceAtLeast(1.0)
            methods.forEach { (name, amount) ->
                val tint = FinanceColors.methodTint(name)
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    TintedIcon(methodIcon(name), tint, 40, corner = 20.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text(money(amount), color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(BizFormat.percent(amount / total * 100), color = Orda.colors.textDim, fontSize = 13.sp)
                        ShareBar(amount / total, tint)
                    }
                }
            }
        }
    }
    val cats = a.expenseCategories
    if (cats.isNotEmpty()) {
        val total = cats.sumOf { it.second }.coerceAtLeast(1.0)
        OwnerSection("Куда ушли деньги") {
            cats.take(6).forEach { (name, amount) ->
                val tint = FinanceColors.categoryTint(name)
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    TintedIcon(expenseIcon(name), tint, 40, corner = 20.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(money(amount), color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        ShareBar(amount / total, tint)
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(title: String, value: Double, change: Double?, higherIsBetter: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Orda.colors.textDim, fontSize = 13.sp)
        Text(money(value), color = Orda.colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        // Цвет по смыслу метрики: рост расходов — не зелёный.
        if (change != null) ChangeText(change, higherIsBetter)
    }
}

/** Статьи расходов (группы ОПиУ) с раскрытием по категориям. */
@Composable
private fun Articles(bundle: ReportBundle, compareWord: String) {
    val articles = bundle.expenseByGroup.filter { it.amount > 0 || it.prevAmount > 0 }
    if (articles.isEmpty()) {
        EmptyState(Icons.Filled.BarChart, "Расходов нет", "За период ничего не потратили.")
        return
    }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    val total = articles.filter { !it.offChain }.sumOf { it.amount }.coerceAtLeast(1.0)
    OwnerSection("Статьи расходов", trailing = { Text(compareWord, color = Orda.colors.textDim, fontSize = 13.sp) }) {
        articles.forEachIndexed { i, art ->
            if (i > 0) RowDivider()
            val open = art.group in expanded
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = art.categories.isNotEmpty()) {
                        expanded = if (open) expanded - art.group else expanded + art.group
                    }.padding(vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(art.label, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        if (art.offChain) Text("не входит в ОПиУ", color = Orda.colors.warning, fontSize = 12.sp)
                        else Text(BizFormat.percent(art.amount / total * 100), color = Orda.colors.textDim, fontSize = 13.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(money(art.amount), color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        art.change?.let { ChangeText(it, higherIsBetter = false) }
                    }
                }
                if (open) {
                    Column(
                        Modifier.fillMaxWidth().background(Orda.colors.surfaceRaised, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        art.categories.sortedByDescending { it.amount }.forEach {
                            Row {
                                Text(it.name, color = Orda.colors.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(money(it.amount), color = Orda.colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Точки: выручка, прибыль и маржа каждой, с долей. */
@Composable
private fun Points(a: ReportAggregate) {
    val rows = a.companyIncome
    if (rows.isEmpty()) {
        EmptyState(Icons.Filled.Apartment, "По точкам пусто", "За период у точек не было доходов.")
        return
    }
    val top = rows.maxOf { it.amount }.coerceAtLeast(1.0)
    OwnerSection("Точки") {
        rows.forEachIndexed { i, c ->
            val tint = OwnerTint.point(i)
            val stat = a.companyStats[c.companyId]
            val change = a.companyStatsPrev[c.companyId]?.let { BizFormat.change(c.amount, it.income) }
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                LetterBadge(c.name, tint)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(c.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(money(c.amount), color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            stat?.let { "прибыль ${money(it.net)}" + (it.margin?.let { m -> " · маржа ${BizFormat.percent(m * 100)}" } ?: "") } ?: "${c.count} операций",
                            color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (change != null) ChangeText(change)
                    }
                    ShareBar(c.amount / top, tint)
                }
            }
        }
    }
}
