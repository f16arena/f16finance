package kz.ordaops.android.feature.business.finance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.feature.business.AnalyticsPeriod
import kz.ordaops.android.feature.business.BizData
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.ExtraCashToggle
import kz.ordaops.android.feature.business.LetterBadge
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.business.OwnerTint
import kz.ordaops.android.feature.operator.money.PeriodStepper
import kz.ordaops.android.feature.operator.money.TintedIcon
import kz.ordaops.android.feature.operator.money.moneySigned
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

/** ОПиУ за месяц: загрузка с прошлым месяцем для сравнения и учётом отдельной кассы. */
class PnlViewModel(private val api: FinanceApi) : LoadViewModel<PnlReport>() {
    var month = PnlLogic.shift(AnalyticsPeriod.businessToday().toString().take(7), -1)
        private set
    private var includeExtra = false

    override suspend fun fetch(): PnlReport {
        val query = buildMap {
            put("from", month); put("to", month); put("with_previous", "1")
            if (includeExtra) put("include_extra", "1")
        }
        return api.api.send<BizData<PnlReport>>(ApiRequest("/api/admin/profitability/summary", query = query)).data
    }

    fun show(month: String, includeExtra: Boolean) {
        if (this.month == month && this.includeExtra == includeExtra && state.value.hasData) return
        this.month = month
        this.includeExtra = includeExtra
        reset()
        reload()
    }
}

/**
 * ОПиУ: чистая прибыль главной цифрой, цепочка строк «выручка → … → чистая
 * прибыль» с раскрытием статей, сверка с «Доходами» и «Расходами», разрез по точкам.
 */
@Composable
fun PnlScreen() {
    val env = LocalBusiness.current
    val vm = financeViewModel("pnl") { _, api -> PnlViewModel(api) }
    val state by vm.state.collectAsState()
    val includeExtra by env.vm.includeExtra.collectAsState()
    val pdf = rememberPdfRunner()
    val scope = rememberCoroutineScope()
    var month by rememberSaveable { mutableStateOf(vm.month) }
    var companyId by rememberSaveable { mutableStateOf<String?>(null) }
    val current = AnalyticsPeriod.businessToday().toString().take(7)

    LaunchedEffect(month, includeExtra) { vm.show(month, includeExtra) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    PeriodStepper(
                        title = PnlLogic.title(month),
                        caption = if (month == current) "месяц идёт" else null,
                        canGoForward = month < current,
                        onBack = { month = PnlLogic.shift(month, -1) },
                        onForward = { month = PnlLogic.shift(month, 1) },
                    )
                }
                val companies = state.data?.companies.orEmpty()
                val target = companyId?.let { id -> companies.firstOrNull { it.id == id } } ?: companies.singleOrNull()
                if (env.can("profitability.export_pdf") && target != null) {
                    if (pdf.running) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = {
                        scope.launch {
                            val q = buildMap {
                                put("company_id", target.id); put("from", month); put("to", month); put("capex", "1")
                                if (includeExtra) put("include_extra", "1")
                            }
                            pdf.run(ApiRequest("/api/admin/profitability/pdf", query = q), "ОПиУ ${target.name} $month")
                        }
                    }) { Icon(Icons.Filled.PictureAsPdf, "PDF отчёта", tint = Orda.colors.brand) }
                }
            }
            pdf.error?.let { ErrorBanner(it) }
        }
        StateContainer(state, onRetry = vm::reload, modifier = Modifier.weight(1f)) { report ->
            OwnerScreenColumn {
                CompanyChips(report.companies, companyId) { companyId = it }
                if (companyId == null) ExtraCashToggle(env)
                PnlContent(report, companyId) { companyId = it }
            }
        }
    }
}

@Composable
private fun CompanyChips(companies: List<PnlCompany>, selected: String?, onSelect: (String?) -> Unit) {
    if (companies.size <= 1) return
    kz.ordaops.android.feature.business.HorizontalRow {
        Chip("Все точки", selected == null) { onSelect(null) }
        companies.forEach { c -> Chip(c.name, selected == c.id) { onSelect(if (selected == c.id) null else c.id) } }
    }
}

@Composable
private fun Chip(title: String, on: Boolean, onClick: () -> Unit) {
    Text(
        title, color = if (on) Color.White else Orda.colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.pressScale().clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (on) Orda.colors.brand else Orda.colors.surface).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun PnlContent(report: PnlReport, companyId: String?, onPickCompany: (String) -> Unit) {
    val company = companyId?.let { id -> report.companies.firstOrNull { it.id == id } }
    val current = company?.months?.firstOrNull() ?: if (companyId == null) report.months.firstOrNull() else null
    val previous = if (companyId == null) report.previous else company?.previous
    val incomplete = report.incompleteMonths.filter { companyId == null || it.companyId == companyId }

    if (incomplete.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().background(Orda.colors.warning.copy(alpha = 0.12f), RoundedCornerShape(20.dp)).padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            TintedIcon(Icons.Filled.Warning, Orda.colors.warning, 36)
            Text(
                "Месяц внесён не полностью: " + incomplete.joinToString(", ") { "${it.company} — ${it.days} дн. из ${it.expectedDays}" } +
                    ". Выручка и прибыль вырастут, когда внесут отчёты смен.",
                color = Orda.colors.text, fontSize = 14.sp,
            )
        }
    }
    if (current == null) {
        EmptyState(
            Icons.Filled.PieChart, "Данных нет",
            company?.let { "У точки ${it.name} за этот месяц нет ни доходов, ни расходов." } ?: "За этот месяц нет ни доходов, ни расходов.",
        )
        return
    }
    Hero(current, previous)
    Statement(current, previous)
    Reconciliation(current, company?.name, report.extra.names)
    if (companyId == null && report.companies.size > 1) CompaniesCard(report, onPickCompany)
}

@Composable
private fun Hero(current: MonthlyPnl, previous: MonthlyPnl?) {
    val change = previous?.let { BizFormat.change(current.netProfit, it.netProfit) }
    HeroSummary(
        title = "Чистая прибыль",
        value = money(current.netProfit),
        caption = listOfNotNull(
            "маржа ${BizFormat.percent(current.netMargin)}",
            change?.let { "${BizFormat.percent(it, signed = true)} к ${PnlLogic.title(previous.month).lowercase()}" },
        ).joinToString(" · "),
        footer = listOf(
            "Выручка" to money(current.revenue),
            "EBITDA · ${BizFormat.percent(current.ebitdaMargin)}" to money(current.ebitda),
            "Валовая" to money(current.grossProfit),
        ),
        colors = if (current.netProfit >= 0) FinanceColors.HeroPositive else FinanceColors.HeroNegative,
    )
}

@Composable
private fun Statement(current: MonthlyPnl, previous: MonthlyPnl?) {
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    val lines = PnlLogic.visibleLines(current, previous)
    val off = PnlLine.offChain.filter { Math.round(current.value(it)) != 0L || (previous?.let { p -> Math.round(p.value(it)) } ?: 0L) != 0L }
    OwnerSection("Отчёт", trailing = {
        if (previous != null) Text("к ${PnlLogic.title(previous.month).lowercase()}", color = Orda.colors.textDim, fontSize = 13.sp)
    }) {
        Column(Modifier.animateContentSize()) {
            lines.forEach { line ->
                if (line.isTotal) {
                    TotalRow(line.title, current.value(line), previous?.value(line), current.revenue, isFinal = line == PnlLine.NetProfit)
                } else {
                    val parts = PnlLogic.mergedParts(line, current, previous)
                    LineRowExpandable(line, current, previous, parts, line.key in expanded) {
                        expanded = if (line.key in expanded) expanded - line.key else expanded + line.key
                    }
                }
            }
            if (off.isNotEmpty()) {
                Text(
                    "После чистой прибыли — в ОПиУ не входят", color = Orda.colors.warning, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs),
                )
                off.forEach { line ->
                    LineRowExpandable(line, current, previous, PnlLogic.mergedParts(line, current, previous), line.key in expanded) {
                        expanded = if (line.key in expanded) expanded - line.key else expanded + line.key
                    }
                }
                TotalRow("Остаётся после покупок и выплат", current.leftover, previous?.leftover, current.revenue, isFinal = false)
            }
            Text("Нажмите на строку, чтобы увидеть статьи", color = Orda.colors.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = Spacing.md))
        }
    }
}

@Composable
private fun LineRowExpandable(
    line: PnlLine,
    current: MonthlyPnl,
    previous: MonthlyPnl?,
    parts: List<Triple<String, Double, Double>>,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val value = current.value(line)
    val prev = previous?.value(line)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = parts.isNotEmpty(), onClick = onToggle).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                Icons.Filled.ChevronRight, null, tint = if (parts.isEmpty()) Color.Transparent else Orda.colors.textDim,
                modifier = Modifier.size(16.dp).rotate(if (open) 90f else 0f),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(line.title, color = Orda.colors.text, fontSize = 15.sp, fontWeight = if (line == PnlLine.Revenue) FontWeight.SemiBold else FontWeight.Normal)
                Text(PnlLogic.shareText(value, current.revenue), color = Orda.colors.textDim, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    (if (line.isExpense && Math.round(value) != 0L) "−" else "") + money(value),
                    color = if (line.isExpense) Orda.colors.textMuted else Orda.colors.text,
                    fontSize = 15.sp, fontWeight = if (line == PnlLine.Revenue) FontWeight.SemiBold else FontWeight.Medium,
                )
                if (prev != null) Delta(value - prev, line.isExpense)
            }
        }
        AnimatedVisibility(open) {
            Column(
                Modifier.padding(start = 18.dp, bottom = Spacing.sm).fillMaxWidth().background(Orda.colors.surfaceRaised, RoundedCornerShape(14.dp)).padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                parts.forEach { (name, cur, prevAmount) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, color = Orda.colors.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(money(cur), color = Orda.colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        if (previous != null) {
                            Box(Modifier.widthIn(min = 80.dp), contentAlignment = Alignment.CenterEnd) { Delta(cur - prevAmount, line.isExpense) }
                        }
                    }
                }
            }
        }
        RowDivider()
    }
}

@Composable
private fun TotalRow(title: String, value: Double, previous: Double?, revenue: Double, isFinal: Boolean) {
    Row(
        Modifier.padding(vertical = 6.dp).fillMaxWidth()
            .background(if (isFinal) Orda.colors.positive.copy(alpha = 0.10f) else Orda.colors.surfaceRaised, RoundedCornerShape(14.dp))
            .padding(horizontal = Spacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Orda.colors.text, fontSize = if (isFinal) 17.sp else 15.sp, fontWeight = FontWeight.Bold)
            Text(PnlLogic.shareText(value, revenue), color = Orda.colors.textDim, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                money(value), fontSize = if (isFinal) 18.sp else 15.sp, fontWeight = FontWeight.Bold,
                color = if (isFinal) (if (value < 0) Orda.colors.negative else Orda.colors.positive) else Orda.colors.text,
            )
            if (previous != null) Delta(value - previous, expense = false)
        }
    }
}

/** Изменение к прошлому месяцу: цвет по смыслу строки — рост расхода не зелёный. */
@Composable
private fun Delta(delta: Double, expense: Boolean) {
    val rounded = Math.round(delta)
    val color = when {
        rounded == 0L -> Orda.colors.textDim
        (rounded > 0) != expense -> Orda.colors.positive
        else -> Orda.colors.negative
    }
    Text(moneySigned(rounded.toDouble()), color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

/** Сверка с «Доходами» и «Расходами»: ОПиУ строится из тех же журналов. */
@Composable
private fun Reconciliation(current: MonthlyPnl, companyName: String?, extraNames: List<String>) {
    val pnlExpenses = current.revenue - current.netProfit
    val filter = when {
        companyName != null -> "фильтр точки — $companyName"
        extraNames.isNotEmpty() -> "все точки, ${extraNames.joinToString(", ")} (см. переключатель выше)"
        else -> "все точки"
    }
    OwnerSection("Сверка", trailing = { Text("с «Доходами» и «Расходами»", color = Orda.colors.textDim, fontSize = 13.sp) }) {
        ReconRow("Итого в «Доходах»", money(current.revenue))
        ReconRow("= Выручка в отчёте", money(current.revenue), bold = true)
        RowDivider()
        ReconRow("Итого в «Расходах» (все статусы)", money(current.check.expensesAll))
        if (Math.round(current.check.declined) != 0L) {
            ReconRow("− отклонённые (${current.check.declinedCount} шт.)", "−" + money(current.check.declined), dim = true)
        }
        if (Math.round(current.capex) != 0L) ReconRow("− покупка оборудования", "−" + money(current.capex), dim = true)
        if (Math.round(current.profitDistribution) != 0L) ReconRow("− выплаты партнёрам", "−" + money(current.profitDistribution), dim = true)
        ReconRow("= Расходы в отчёте (с налогом)", money(pnlExpenses), bold = true)
        Text(
            "На тех страницах выберите весь месяц и тот же фильтр: $filter. Выручка ${money(current.revenue)} − расходы ${money(pnlExpenses)} = чистая прибыль ${money(current.netProfit)}.",
            color = Orda.colors.textDim, fontSize = 12.sp,
        )
    }
}

@Composable
private fun ReconRow(label: String, value: String, bold: Boolean = false, dim: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = if (dim) Orda.colors.textDim else Orda.colors.text, fontSize = 14.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        Text(value, color = if (dim) Orda.colors.textDim else Orda.colors.text, fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun CompaniesCard(report: PnlReport, onPick: (String) -> Unit) {
    val rows = report.companies.mapNotNull { c ->
        val m = c.months.firstOrNull() ?: return@mapNotNull null
        if (Math.round(m.revenue) == 0L && Math.round(m.netProfit) == 0L) null else c to m
    }.sortedByDescending { it.second.revenue }
    val top = (rows.maxOfOrNull { it.second.revenue } ?: 1.0).coerceAtLeast(1.0)
    OwnerSection("По точкам", trailing = { Text("нажмите — отчёт точки", color = Orda.colors.textDim, fontSize = 13.sp) }) {
        rows.forEachIndexed { index, (company, month) ->
            val tint = OwnerTint.point(index)
            val change = company.previous?.let { BizFormat.change(month.revenue, it.revenue) }
            Row(
                Modifier.fillMaxWidth().pressScale().clickable { onPick(company.id) }.padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                LetterBadge(company.name, tint)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(company.name + if (company.isExtra) " · вне итогов" else "", color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(money(month.revenue), color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text("прибыль ${money(month.netProfit)} · маржа ${BizFormat.percent(month.netMargin)}", color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (change != null) kz.ordaops.android.feature.business.ChangeText(change)
                    }
                    ShareBar(month.revenue / top, tint)
                }
            }
        }
    }
}
