package kz.ordaops.android.feature.business.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load
import kz.ordaops.android.feature.business.AnalyticsPeriod
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
import kz.ordaops.android.feature.operator.shift.AmountField
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.feature.operator.shift.parseAmount
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

/** Состояние «Движения денег»: отчёт за период + платежи вперёд (грузятся при открытии вкладки). */
class CashflowViewModel(private val api: FinanceApi) : ViewModel() {
    private val _report = MutableStateFlow(Loadable<CashflowReport>())
    val report: StateFlow<Loadable<CashflowReport>> = _report.asStateFlow()
    private val _outlook = MutableStateFlow(Loadable<CashflowOutlook>())
    val outlook: StateFlow<Loadable<CashflowOutlook>> = _outlook.asStateFlow()
    private val _anchors = MutableStateFlow<CashflowAnchorList?>(null)
    val anchors: StateFlow<CashflowAnchorList?> = _anchors.asStateFlow()
    private var job: Job? = null

    var period: AnalyticsPeriod = AnalyticsPeriod.ThisMonth
        private set
    var companyId: String? = null
        private set
    private var includeExtra = false

    fun show(period: AnalyticsPeriod, companyId: String?, includeExtra: Boolean) {
        if (this.period == period && this.companyId == companyId && this.includeExtra == includeExtra && _report.value.hasData) return
        this.period = period
        this.companyId = companyId
        this.includeExtra = includeExtra
        _report.value = Loadable(isLoading = true)
        reload()
    }

    fun reload() {
        job?.cancel()
        job = viewModelScope.launch {
            val (from, to) = period.bounds()
            _report.load(fresh = { api.cashflow(from, to, includeExtra, companyId) })
        }
    }

    fun loadOutlook(force: Boolean = false) {
        if (!force && _outlook.value.hasData) return
        viewModelScope.launch { _outlook.load(fresh = { api.cashflowOutlook(companyId) }) }
    }

    fun loadAnchors() {
        viewModelScope.launch { runCatching { api.balanceAnchors() }.onSuccess { _anchors.value = it } }
    }

    suspend fun saveAnchor(draft: CashflowAnchorDraft): String? = try {
        api.saveBalanceAnchor(draft)
        loadAnchors()
        reload()
        _outlook.value = Loadable()
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }

    suspend fun deleteAnchor(id: String): String? = try {
        api.deleteBalanceAnchor(id)
        loadAnchors()
        reload()
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }
}

private enum class CashflowTab(val title: String) {
    Overview("Обзор"), Channels("Нал и безнал"), Points("Точки"), Expenses("Расходы"), Payments("Платежи")
}

/** «Движение денег»: пять вкладок — обзор, нал/безнал, точки, расходы, платежи вперёд. */
@Composable
fun CashflowScreen() {
    val env = LocalBusiness.current
    val vm = financeViewModel("cashflow") { _, api -> CashflowViewModel(api) }
    val state by vm.report.collectAsState()
    val outlook by vm.outlook.collectAsState()
    val includeExtra by env.vm.includeExtra.collectAsState()
    var period by rememberSaveable(stateSaver = periodSaver) { mutableStateOf<AnalyticsPeriod>(AnalyticsPeriod.ThisMonth) }
    var companyId by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(CashflowTab.Overview.name) }
    var balanceSheet by rememberSaveable { mutableStateOf(false) }
    val current = CashflowTab.valueOf(tab)

    LaunchedEffect(period, companyId, includeExtra) {
        vm.show(period, companyId, includeExtra)
        if (current == CashflowTab.Payments) vm.loadOutlook(force = true)
    }
    LaunchedEffect(current) { if (current == CashflowTab.Payments) vm.loadOutlook() }

    val report = state.data
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PeriodBar(
                selection = period,
                quick = listOf(AnalyticsPeriod.ThisMonth, AnalyticsPeriod.LastMonth, AnalyticsPeriod.Last7Days, AnalyticsPeriod.Last30Days),
                onSelect = { period = it },
                extraCash = if (companyId == null && report?.extra?.names?.isNotEmpty() == true) ({ ExtraCashToggle(env) }) else null,
            )
            val companies = report?.companies.orEmpty()
            if (companies.size > 1) {
                HorizontalRow {
                    Chip("Все точки", companyId == null) { companyId = null }
                    companies.forEach { Chip(it.name, companyId == it.id) { companyId = it.id } }
                }
            }
            PillSegment(CashflowTab.entries.map { it.name to it.title }, tab) { tab = it }
        }
        if (current == CashflowTab.Payments) {
            StateContainer(outlook, onRetry = { vm.loadOutlook(true) }, onRefresh = { vm.loadOutlook(true) }, modifier = Modifier.weight(1f)) { PaymentsTab(it) }
        } else {
            StateContainer(
                state, onRetry = vm::reload, modifier = Modifier.weight(1f),
                isEmpty = { it.days.isEmpty() },
                emptyIcon = Icons.Filled.SwapHoriz, emptyTitle = "Движений нет",
                emptyMessage = "За выбранный период не было ни доходов, ни расходов.",
            ) { r ->
                OwnerScreenColumn {
                    CashflowHero(r)
                    when (current) {
                        CashflowTab.Overview -> Overview(r) { balanceSheet = true }
                        CashflowTab.Channels -> Channels(r)
                        CashflowTab.Points -> Points(r)
                        else -> ExpensesTab(r)
                    }
                }
            }
        }
    }
    if (balanceSheet) BalanceSheet(vm, report?.companies.orEmpty(), companyId, true) { balanceSheet = false }
}

@Composable
private fun Chip(title: String, on: Boolean, onClick: () -> Unit) {
    Text(
        title, color = if (on) Color.White else Orda.colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.pressScale().clip(CircleShape).background(if (on) Orda.colors.brand else Orda.colors.surface).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun CashflowHero(r: CashflowReport) {
    val f = r.headlineFlow
    val footer = buildList {
        add("Пришло" to money(f.inflow))
        add("Ушло" to money(f.outflow))
        val end = r.balance?.end
        add(if (end != null) "Остаток на конец" to money(end.sum) else "Накоплено" to money(r.totals.endingBalance))
    }
    var caption: String? = CashflowLogic.netChange(r)?.let { (if (it >= 0) "+" else "") + BizFormat.percent(it) + " к прошлому периоду" }
    if (r.pending.count > 0) {
        val pending = "ещё ${money(r.pending.total)} ждут согласования"
        caption = caption?.let { "$it · $pending" } ?: pending
    }
    HeroSummary(
        title = if (f.delta >= 0) "Чистый поток за период" else "Ушло больше, чем пришло",
        value = CashflowLogic.signedText(f.delta, ::money),
        caption = caption, footer = footer,
        colors = if (f.delta >= 0) FinanceColors.HeroPositive else FinanceColors.HeroNegative,
    )
}

private fun activityIcon(key: String) = when (key) {
    "operating" -> Icons.Filled.ShoppingCart
    "investing" -> Icons.Filled.Handyman
    "owners" -> Icons.Filled.People
    "taxes" -> Icons.Filled.AccountBalance
    else -> Icons.Filled.MoreHoriz
}

private fun dayLabel(iso: String) = AnalyticsPeriod.rangeLabel(iso, iso)

@Composable
private fun Overview(r: CashflowReport, onBalance: () -> Unit) {
    // Остаток денег: отметка владельца; без неё «остаток» показывать нельзя.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Orda.colors.surface).clickable(onClick = onBalance).padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        TintedIcon(Icons.Filled.Payments, Orda.colors.brand, 42)
        Column(Modifier.weight(1f)) {
            Text("Остаток денег", color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                r.balance?.anchor?.let { "отметка на ${dayLabel(it.asOfDate)}" } ?: "не указан — нажмите, чтобы указать",
                color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1,
            )
        }
        r.balance?.end?.let { Text(money(it.sum), color = if (it.sum < 0) Orda.colors.negative else Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        Icon(Icons.Filled.ChevronRight, null, tint = Orda.colors.textDim, modifier = Modifier.size(18.dp))
    }

    val hasOnHand = r.days.any { it.onHand != null }
    OwnerSection(
        if (hasOnHand) "Остаток по дням" else "Накоплено с начала периода",
        trailing = { Text(if (hasOnHand) "деньги на конец каждого дня" else "нарастающим итогом, не остаток кассы", color = Orda.colors.textDim, fontSize = 12.sp) },
    ) {
        TrendLine(r.days.map { Bar(dayLabel(it.date), it.onHand?.sum ?: it.balance) }, Orda.colors.brand)
    }

    val activities = r.activities.filter { it.amount > 0 || it.previous > 0 }
    if (activities.isNotEmpty()) {
        val total = activities.sumOf { it.amount }.coerceAtLeast(1.0)
        OwnerSection("Куда ушли деньги", trailing = { Text("к прошлому периоду", color = Orda.colors.textDim, fontSize = 13.sp) }) {
            activities.forEachIndexed { i, a ->
                val tint = OwnerTint.point(i)
                val change = BizFormat.change(a.amount, a.previous)
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    TintedIcon(activityIcon(a.key), tint, 40)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(a.label, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(money(a.amount), color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        Row(Modifier.fillMaxWidth()) {
                            Text("нал ${money(a.cash)} · безнал ${money(a.cashless)}", color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            // Расходы: рост — не зелёный.
                            if (change != null) ChangeText(change, higherIsBetter = false)
                        }
                        ShareBar(a.amount / total, tint)
                    }
                }
            }
        }
    }

    OwnerSection("Чем запомнился период") {
        r.bestDay?.let { LineRow("Лучший день", "${dayLabel(it.date)} · ${CashflowLogic.signedText(it.net, ::money)}", Orda.colors.positive) }
        r.worstDay?.let { LineRow("Худший день", "${dayLabel(it.date)} · ${CashflowLogic.signedText(it.net, ::money)}", Orda.colors.negative) }
        if (r.totals.negativeDays > 0) LineRow("Дней в минусе", "${r.totals.negativeDays} из ${r.totals.daysCount}")
        r.balance?.lowest?.let { LineRow("Меньше всего денег", "${dayLabel(it.date)} · ${money(it.total)}", if (it.total < 0) Orda.colors.negative else Orda.colors.text) }
    }
}

@Composable
private fun Channels(r: CashflowReport) {
    ChannelCard("Наличные", Orda.colors.positive, r.flows.cash, r.previous.cash, r.balance?.start?.cash, r.balance?.end?.cash)
    ChannelCard("Безналичный", Orda.colors.brand, r.flows.cashless, r.previous.cashless, r.balance?.start?.cashless, r.balance?.end?.cashless)
    if (r.cashDeficitDays.isNotEmpty()) {
        OwnerSection(
            "Наличных ушло больше, чем пришло",
            trailing = { Text("${r.cashDeficitDays.size} ${LedgerLogic.plural(r.cashDeficitDays.size, "день", "дня", "дней")}", color = Orda.colors.warning, fontSize = 13.sp) },
        ) {
            r.cashDeficitDays.take(10).forEach { LineRow(dayLabel(it.date), CashflowLogic.signedText(it.net, ::money), Orda.colors.negative) }
        }
    }
    OwnerSection("По дням", trailing = { Text("нал · безнал", color = Orda.colors.textDim, fontSize = 13.sp) }) {
        r.days.asReversed().take(31).forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(dayLabel(d.date), color = Orda.colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(CashflowLogic.signedText(d.cashNet, ::money), color = if (d.cashNet < 0) Orda.colors.negative else Orda.colors.text, fontSize = 14.sp)
                Text(" · " + CashflowLogic.signedText(d.cashlessNet, ::money), color = Orda.colors.textMuted, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ChannelCard(title: String, tint: Color, flow: CashflowFlow, previous: CashflowFlow, start: Double?, end: Double?) {
    OwnerSection(title, trailing = { Text(CashflowLogic.signedText(flow.delta, ::money), color = if (flow.delta < 0) Orda.colors.negative else tint, fontSize = 15.sp, fontWeight = FontWeight.Bold) }) {
        LineRow("Пришло", money(flow.inflow))
        LineRow("Ушло", money(flow.outflow))
        if (previous.inflow > 0 || previous.outflow > 0) LineRow("В прошлом периоде", "+${money(previous.inflow)} −${money(previous.outflow)}", Orda.colors.textDim)
        if (start != null) LineRow("Было на начало", money(start))
        if (end != null) LineRow("Стало на конец", money(end), bold = true)
    }
}

@Composable
private fun Points(r: CashflowReport) {
    val rows = r.companies.filter { it.flows.total.inflow != 0.0 || it.flows.total.outflow != 0.0 }
    if (rows.isEmpty()) {
        EmptyState(Icons.Filled.Apartment, "По точкам пусто", "За период у точек не было движений.")
        return
    }
    val top = rows.maxOf { maxOf(it.flows.total.inflow, it.flows.total.outflow) }.coerceAtLeast(1.0)
    OwnerSection("Точки", trailing = { Text("пришло · ушло", color = Orda.colors.textDim, fontSize = 13.sp) }) {
        rows.forEachIndexed { i, c ->
            val tint = OwnerTint.point(i)
            val change = BizFormat.change(c.flows.total.delta, c.previousNet)
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                LetterBadge(c.name, tint)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(c.name + if (c.inTotals) "" else " · вне итогов", color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(CashflowLogic.signedText(c.flows.total.delta, ::money), color = if (c.flows.total.delta < 0) Orda.colors.negative else Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text("пришло ${money(c.flows.total.inflow)} · ушло ${money(c.flows.total.outflow)}", color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (change != null) ChangeText(change)
                    }
                    ShareBar(c.flows.total.inflow / top, tint)
                }
            }
        }
    }
}

@Composable
private fun ExpensesTab(r: CashflowReport) {
    if (r.pending.count > 0) {
        Row(
            Modifier.fillMaxWidth().background(Orda.colors.warning.copy(alpha = 0.12f), RoundedCornerShape(20.dp)).padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            TintedIcon(Icons.Filled.Warning, Orda.colors.warning, 36)
            Column {
                Text("${r.pending.count} ${LedgerLogic.plural(r.pending.count, "расход ждёт", "расхода ждут", "расходов ждут")} согласования", color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("${money(r.pending.total)} — в «ушло» пока не входят", color = Orda.colors.textMuted, fontSize = 13.sp)
            }
        }
    }
    val categories = r.categories.filter { it.amount > 0 || it.previous > 0 }
    if (categories.isEmpty()) {
        EmptyState(Icons.Filled.ShoppingCart, "Расходов нет", "За период ничего не потратили.")
        return
    }
    val total = categories.sumOf { it.amount }.coerceAtLeast(1.0)
    OwnerSection("Статьи", trailing = { Text("${categories.size}", color = Orda.colors.textDim, fontSize = 13.sp) }) {
        categories.forEach { c ->
            val tint = FinanceColors.categoryTint(c.name)
            val change = BizFormat.change(c.amount, c.previous)
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                TintedIcon(expenseIcon(c.name), tint, 40, corner = 20.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(c.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(money(c.amount), color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "нал ${money(c.cash)} · безнал ${money(c.cashless)}" + if (c.previous > 0) " · было ${money(c.previous)}" else "",
                            color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (change != null) ChangeText(change, higherIsBetter = false)
                    }
                    ShareBar(c.amount / total, tint)
                }
            }
        }
    }
    if (r.largestExpenses.isNotEmpty()) {
        OwnerSection("Крупные расходы") {
            r.largestExpenses.take(10).forEachIndexed { i, e ->
                if (i > 0) RowDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.category.ifEmpty { "Без статьи" }, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(dayLabel(e.date), e.company, e.payee).filter { it.isNotEmpty() }.joinToString(" · "), color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (e.pending) Text("на согласовании", color = Orda.colors.warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("−" + money(e.amount), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Платежи вперёд: деньги до конца месяца и регулярные платежи из шаблонов. */
@Composable
private fun PaymentsTab(o: CashflowOutlook) {
    OwnerScreenColumn {
        o.projection?.let { p ->
            val end = p.balanceEnd
            HeroSummary(
                title = if (end != null) "Останется к ${dayLabel(p.monthEnd)}" else "Поток до ${dayLabel(p.monthEnd)}",
                value = money(end ?: p.netLeft),
                caption = if (p.source == "model") "по модели прогноза" else "по среднему дню",
                footer = listOf("Придёт" to money(p.incomeLeft), "Платежи" to money(p.paymentsLeft), "Прочее" to money(p.otherSpendLeft)),
                colors = if ((end ?: p.netLeft) >= 0) FinanceColors.HeroPositive else FinanceColors.HeroNegative,
            )
            val lowest = p.lowest?.balance
            if (lowest != null && lowest < 0) {
                Column(Modifier.fillMaxWidth().background(Orda.colors.negative.copy(alpha = 0.12f), RoundedCornerShape(20.dp)).padding(Spacing.md)) {
                    Text("${p.lowest?.date?.let { dayLabel(it) } ?: "—"} денег не хватит", color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("минимум ${money(lowest)} — перенесите платёж или отложите заранее", color = Orda.colors.textMuted, fontSize = 13.sp)
                }
            }
            val series = p.days.mapNotNull { d -> d.balance?.let { Bar(dayLabel(d.date), it) } }
            if (series.size > 1) {
                OwnerSection("Деньги до конца месяца", trailing = { Text("остаток по дням с учётом платежей", color = Orda.colors.textDim, fontSize = 12.sp) }) {
                    TrendLine(series, Orda.colors.brand)
                }
            }
        }
        if (o.payments.isEmpty()) {
            EmptyState(Icons.Filled.Payments, "Регулярных платежей нет", "Шаблоны расходов на ближайший месяц не заведены.")
        } else {
            OwnerSection("Регулярные платежи", trailing = { Text("на 31 день · ${money(o.payments.sumOf { it.amount })}", color = Orda.colors.textDim, fontSize = 13.sp) }) {
                o.payments.forEachIndexed { i, pay ->
                    if (i > 0) RowDivider()
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pay.name.ifEmpty { pay.category }, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${dayLabel(pay.date)} · ${pay.company} · ${if (pay.cashless) "безнал" else "наличные"}", color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("−" + money(pay.amount), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Отметка остатка: сколько денег было на утро даты. Список прежних отметок — с удалением. */
@Composable
private fun BalanceSheet(vm: CashflowViewModel, companies: List<CashflowCompany>, defaultCompany: String?, canEdit: Boolean, onDismiss: () -> Unit) {
    val anchors by vm.anchors.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var date by remember { mutableStateOf(AnalyticsPeriod.businessToday().toString()) }
    var companyId by remember { mutableStateOf(defaultCompany.orEmpty()) }
    var cash by remember { mutableStateOf("") }
    var cashless by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadAnchors() }
    val editable = canEdit && anchors?.canEdit != false

    OrdaSheet("Остаток денег", onDismiss) {
        Text("Сколько денег было на утро выбранного дня — до движений этого дня. От этой отметки считается остаток по дням.", color = Orda.colors.textMuted, fontSize = 14.sp)
        anchors?.let { if (!it.available) ErrorBanner(it.hint ?: "Функция ещё не включена на сервере.") }
        if (editable && anchors?.available != false) {
            DateField("На утро", date, { date = it })
            PickerField("Точка", companies.map { Option(it.id, it.name) }, companyId, { companyId = it }, placeholder = "Вся организация")
            AmountField("Наличные", cash, { cash = it })
            AmountField("Безналичный", cashless, { cashless = it })
            TextLineField("Заметка", note, { note = it }, placeholder = "Необязательно")
            error?.let { ErrorBanner(it) }
            PrimaryButton("Сохранить отметку", Modifier.fillMaxWidth(), loading = saving) {
                saving = true
                error = null
                scope.launch {
                    val failure = vm.saveAnchor(CashflowAnchorDraft(companyId.ifEmpty { null }, date, parseAmount(cash), parseAmount(cashless), note))
                    saving = false
                    if (failure == null) { haptics.success(); onDismiss() } else { error = failure; haptics.error() }
                }
            }
        } else {
            Text("Указывать остаток может владелец или управляющий.", color = Orda.colors.textDim, fontSize = 13.sp)
        }
        anchors?.anchors?.takeIf { it.isNotEmpty() }?.let { list ->
            Text("Прежние отметки", color = Orda.colors.textDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            list.forEach { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${dayLabel(a.asOfDate)} · ${companies.firstOrNull { it.id == a.companyId }?.name ?: "вся организация"}", color = Orda.colors.text, fontSize = 14.sp)
                        Text("нал ${money(a.cash)} · безнал ${money(a.cashless)}", color = Orda.colors.textDim, fontSize = 13.sp)
                    }
                    if (editable) {
                        IconButton(onClick = { scope.launch { error = vm.deleteAnchor(a.id) } }) {
                            Icon(Icons.Filled.Delete, "Удалить отметку", tint = Orda.colors.negative)
                        }
                    }
                }
            }
        }
    }
}

/** Сохранение периода при повороте: ключ + границы для своих дат. */
private val periodSaver = androidx.compose.runtime.saveable.Saver<AnalyticsPeriod, String>(
    save = { it.key },
    restore = { AnalyticsPeriod.fromKey(it) ?: AnalyticsPeriod.ThisMonth },
)
