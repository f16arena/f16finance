package kz.ordaops.android.feature.business.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Storefront
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load
import kz.ordaops.android.feature.business.AnalyticsPeriod
import kz.ordaops.android.feature.business.BizData
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.HorizontalRow
import kz.ordaops.android.feature.business.LetterBadge
import kz.ordaops.android.feature.business.OwnerFootnote
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.operator.money.PeriodStepper
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

/**
 * «Эффективность продавцов магазина». По вебу, а не по iOS: отчёт без `company_id` у сети из
 * нескольких магазинов — только список магазинов, поэтому сначала берём магазин, потом считаем.
 */
class SalesKpiViewModel(private val api: FinanceApi) : ViewModel() {
    private val _report = MutableStateFlow(Loadable<KpiReport>())
    val report: StateFlow<Loadable<KpiReport>> = _report.asStateFlow()
    private val _payout = MutableStateFlow(Loadable<KpiPayout>())
    val payout: StateFlow<Loadable<KpiPayout>> = _payout.asStateFlow()
    private val _plans = MutableStateFlow(Loadable<KpiPlans>())
    val plans: StateFlow<Loadable<KpiPlans>> = _plans.asStateFlow()

    private var month = ""
    private var companyId: String? = null

    /** Выбранный магазин (для подсветки чипа). */
    val selectedCompany: String? get() = companyId

    fun show(month: String, companyId: String?) {
        if (this.month == month && this.companyId == companyId && _report.value.hasData) return
        this.month = month
        this.companyId = companyId
        _report.value = Loadable(isLoading = true)
        _payout.value = Loadable()
        _plans.value = Loadable()
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val (from, to) = SalesKpiLogic.monthBounds(month)
            _report.load(fresh = {
                var r = api.api.send<BizData<KpiReport>>(salesRequest(from, to, companyId)).data
                // Несколько магазинов и ни один не выбран — берём первый, как «по умолчанию».
                if (r.needsCompany && r.stores.isNotEmpty()) {
                    companyId = r.stores.first().id
                    r = api.api.send<BizData<KpiReport>>(salesRequest(from, to, companyId)).data
                }
                if (companyId == null) companyId = r.company?.id
                r
            })
            val id = companyId ?: return@launch
            _payout.load(fresh = {
                api.api.send<BizData<KpiPayout>>(ApiRequest("/api/admin/sales-kpi/payout", query = mapOf("company_id" to id, "month" to month))).data
            })
        }
    }

    fun loadPlans() {
        val id = companyId ?: return
        if (_plans.value.hasData) return
        viewModelScope.launch {
            val today = AnalyticsPeriod.businessToday()
            _plans.load(fresh = {
                api.api.send<BizData<KpiPlans>>(
                    ApiRequest("/api/admin/sales-kpi/plans", query = mapOf("company_id" to id, "from" to today.toString(), "to" to today.plusDays(13).toString())),
                ).data
            })
        }
    }

    private fun salesRequest(from: String, to: String, company: String?) =
        ApiRequest("/api/admin/sales-kpi", query = buildMap { put("from", from); put("to", to); if (company != null) put("company_id", company) })
}

private enum class KpiSection(val title: String) { Payout("Кому доплатить"), Review("Почему касса"), Plans("Цели"), People("По продавцам") }

@Composable
fun SalesKpiScreen() {
    val vm = financeViewModel("sales-kpi") { _, api -> SalesKpiViewModel(api) }
    val report by vm.report.collectAsState()
    val payout by vm.payout.collectAsState()
    val plans by vm.plans.collectAsState()
    var month by rememberSaveable { mutableStateOf(AnalyticsPeriod.businessToday().toString().take(7)) }
    var store by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf(KpiSection.Payout.name) }
    val current = AnalyticsPeriod.businessToday().toString().take(7)

    LaunchedEffect(month, store) { vm.show(month, store) }
    LaunchedEffect(section, report.data) { if (section == KpiSection.Plans.name) vm.loadPlans() }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PeriodStepper(
                title = PnlLogic.title(month),
                canGoForward = month < current,
                onBack = { month = PnlLogic.shift(month, -1) },
                onForward = { month = PnlLogic.shift(month, 1) },
            )
            val stores = report.data?.stores.orEmpty()
            if (stores.size > 1) {
                HorizontalRow {
                    stores.forEach { s ->
                        val on = (store ?: report.data?.company?.id) == s.id
                        Text(
                            s.name, color = if (on) Color.White else Orda.colors.textMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            modifier = Modifier.pressScale().clip(CircleShape).background(if (on) Orda.colors.brand else Orda.colors.surface)
                                .clickable { store = s.id }.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (report.data?.let { !it.noStore && it.cashiers.isNotEmpty() || it.totals.shifts > 0 || it.company != null } == true) {
                PillSegment(KpiSection.entries.map { it.name to it.title }, section) { section = it }
            }
        }
        StateContainer(
            report, onRetry = vm::reload, modifier = Modifier.weight(1f),
            isEmpty = { it.noStore },
            emptyIcon = Icons.Filled.Storefront, emptyTitle = "Магазина нет",
            emptyMessage = "Модуль считает работу за прилавком. Отметьте точку как магазин в настройках — и оценка появится.",
        ) { r ->
            OwnerScreenColumn {
                when (KpiSection.valueOf(section)) {
                    KpiSection.Payout -> PayoutTab(payout)
                    KpiSection.Review -> ReviewTab(r)
                    KpiSection.Plans -> PlansTab(plans)
                    KpiSection.People -> PeopleTab(r)
                }
            }
        }
    }
}

private fun statusColor(status: String, c: kz.ordaops.android.ui.theme.OrdaColors): Color = when (status) {
    "TOP", "STRONG" -> c.positive
    "WEAK" -> c.warning
    else -> c.textDim
}

@Composable
private fun PayoutTab(state: Loadable<KpiPayout>) {
    val p = state.data
    if (p == null) {
        if (state.error != null) Text(state.error, color = Orda.colors.negative, fontSize = 14.sp) else Text("Считаем…", color = Orda.colors.textDim)
        return
    }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    HeroSummary(
        title = "К доплате",
        value = money(p.totals.toPay),
        caption = if (p.totals.toPayPeople > 0) "${p.totals.toPayPeople} ${LedgerLogic.plural(p.totals.toPayPeople, "продавец ждёт", "продавца ждут", "продавцов ждут")} начисления" else "Никто не ждёт начисления",
        footer = listOf("Уже выплачено" to money(p.totals.alreadyPaid), "Продавцов" to "${p.totals.people}"),
    )
    if (p.rows.isEmpty()) {
        EmptyState(Icons.Filled.Groups, "Продаж в этом месяце нет", "Оценка появится, когда за прилавком начнут пробивать чеки.")
        return
    }
    OwnerSection("Продавцы", trailing = { Text("нажмите, чтобы раскрыть", color = Orda.colors.textDim, fontSize = 13.sp) }) {
        p.rows.forEach { row ->
            val open = row.cashierId in expanded
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = if (open) expanded - row.cashierId else expanded + row.cashierId }.padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    LetterBadge(row.name, Orda.colors.brand)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(row.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(money(row.amount), color = if (row.amount > 0) Orda.colors.positive else Orda.colors.textDim, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(row.statusLabel.ifEmpty { SalesKpiLogic.statusLabel(row.status) }, color = statusColor(row.status, Orda.colors), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "· ${row.shifts} ${LedgerLogic.plural(row.shifts, "смена", "смены", "смен")}, ${row.receipts} ${LedgerLogic.plural(row.receipts, "чек", "чека", "чеков")}",
                                color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            if (row.amount > 0) Text(if (row.paid) "выплачено" else "к выплате", color = if (row.paid) Orda.colors.positive else Orda.colors.warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (open) {
                    Column(Modifier.fillMaxWidth().background(Orda.colors.surfaceRaised, RoundedCornerShape(16.dp)).padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        LineRow("Выручка за месяц", money(row.revenue))
                        row.zeroReason?.let { Text(it, color = Orda.colors.textMuted, fontSize = 12.sp) }
                        if (row.strengths.isNotEmpty()) Text("Получается: " + row.strengths.joinToString(", ") { SalesKpiLogic.metricLabel(it) }, color = Orda.colors.positive, fontSize = 14.sp)
                        if (row.weaknesses.isNotEmpty()) Text("Проседает: " + row.weaknesses.joinToString(", ") { SalesKpiLogic.metricLabel(it) }, color = Orda.colors.warning, fontSize = 14.sp)
                    }
                }
            }
        }
    }
    OwnerFootnote(
        "Доплата начисляется от статуса «Сильный» — ${money(p.settings.strong)}, за «Топ» — ${money(p.settings.top)}. " +
            "Считается от ${p.settings.minQualifyingShifts} смен за месяц. Начисление в зарплату делается на сайте.",
    )
}

private fun verdict(v: String, c: kz.ordaops.android.ui.theme.OrdaColors): Pair<String, Color> = when (v) {
    "LOW_DEMAND" -> "Мало покупателей" to c.info
    "POSSIBLE_CASHIER_ISSUE" -> "Вопрос к продавцу" to c.warning
    "HIGH_DEMAND" -> "Вытянул поток" to c.info
    "STRONG_CASHIER" -> "Сильная смена" to c.positive
    "INSUFFICIENT_DATA" -> "Мало данных" to c.textDim
    else -> "Норма" to c.textDim
}

@Composable
private fun ReviewTab(r: KpiReport) {
    if (r.shifts.isEmpty()) {
        EmptyState(Icons.Filled.Event, "Смен за месяц нет", "Разбор появится, когда за прилавком отработают хотя бы одну смену.")
        return
    }
    OwnerSection("Разбор смен", trailing = { Text("${r.shifts.size} ${LedgerLogic.plural(r.shifts.size, "смена", "смены", "смен")}", color = Orda.colors.textDim, fontSize = 13.sp) }) {
        SalesKpiLogic.reviewOrder(r.shifts).forEach { s ->
            val (label, tint) = verdict(s.verdict, Orda.colors)
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("${AnalyticsPeriod.rangeLabel(s.date, s.date)} · ${s.shiftLabel}", color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(money(s.revenue), color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    s.deviation?.let { Text(BizFormat.percent(it * 100, signed = true), color = if (it >= 0) Orda.colors.positive else Orda.colors.warning, fontSize = 13.sp) }
                    Text("${s.receipts} чек. · средний ${money(s.averageReceipt)}", color = Orda.colors.textDim, fontSize = 13.sp)
                }
                s.cashierName?.takeIf { it.isNotEmpty() }?.let { Text(it, color = Orda.colors.textDim, fontSize = 13.sp) }
                s.evidence.take(3).forEach { Text("· $it", color = Orda.colors.textDim, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun PlansTab(state: Loadable<KpiPlans>) {
    val plans = state.data
    if (plans == null || plans.plans.isEmpty()) {
        if (state.error != null) Text(state.error, color = Orda.colors.negative, fontSize = 14.sp)
        else EmptyState(Icons.Filled.Flag, "Целей пока нет", "Пороги считаются по истории смен. Как только её наберётся достаточно, цели появятся здесь.")
        return
    }
    OwnerSection("Ближайшие смены") {
        plans.monthlyText?.let { Text(it, color = Orda.colors.textDim, fontSize = 13.sp) }
        plans.plans.forEach { plan ->
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("${AnalyticsPeriod.rangeLabel(plan.date, plan.date)} · ${plan.shiftLabel}", color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(if (plan.locked) "Зафиксирован" else "Предварительный", color = if (plan.locked) Orda.colors.positive else Orda.colors.textDim, fontSize = 12.sp)
                }
                if (plan.hasTargets) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Target("Порог", plan.control, Orda.colors.textDim)
                        Target("Бонус", plan.b1, Orda.colors.text)
                        Target("Выше", plan.b2, Orda.colors.brand)
                        Target("Рекорд", plan.b3, Orda.colors.positive)
                    }
                } else {
                    Text("Мало истории — пороги не посчитаны", color = Orda.colors.textDim, fontSize = 12.sp)
                }
                val extra = listOfNotNull(
                    plan.expectedRevenue?.let { "прогноз ${money(it)}" },
                    plan.expectedReceipts?.let { "${Math.round(it)} чек." },
                    plan.weatherLabel,
                ).joinToString(" · ")
                if (extra.isNotEmpty()) Text(extra, color = Orda.colors.textDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Target(label: String, value: Double?, tint: Color) {
    Column {
        Text(label, color = Orda.colors.textDim, fontSize = 11.sp)
        Text(value?.let { money(it) } ?: "—", color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PeopleTab(r: KpiReport) {
    if (r.cashiers.isEmpty()) {
        EmptyState(Icons.Filled.Groups, "Разбора пока нет", "Он появится, когда за прилавком наберутся смены с чеками.")
        return
    }
    HeroSummary(
        title = "Средний чек за месяц",
        value = money(r.totals.averageReceipt),
        footer = listOf("Чеков" to "${r.totals.receipts}", "Смен" to "${r.totals.shifts}"),
    )
    OwnerSection("Продавцы", trailing = { Text("${r.cashiers.size}", color = Orda.colors.textDim, fontSize = 13.sp) }) {
        r.cashiers.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                LetterBadge(c.name, Orda.colors.brand)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(c.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(c.statusText, color = statusColor(c.status, Orda.colors), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(c.scoreText, color = Orda.colors.textMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("${c.shifts} ${LedgerLogic.plural(c.shifts, "смена", "смены", "смен")} · ${c.receipts} чек. · средний ${money(c.averageReceipt)}", color = Orda.colors.textDim, fontSize = 13.sp)
                    if (c.strengths.isNotEmpty()) Text("Получается: " + c.strengths.joinToString(", ") { SalesKpiLogic.metricLabel(it) }, color = Orda.colors.positive, fontSize = 13.sp)
                    if (c.weaknesses.isNotEmpty()) Text("Проседает: " + c.weaknesses.joinToString(", ") { SalesKpiLogic.metricLabel(it) }, color = Orda.colors.warning, fontSize = 13.sp)
                    if (c.trainingFlag) Text(c.trainingReason ?: "Стоит подтянуть обучением", color = Orda.colors.warning, fontSize = 12.sp)
                }
            }
        }
    }
    OwnerFootnote("Статус ставится от ${r.minQualifyingShifts} смен: по паре смен человека не оценивают. Выручка зависит от потока, а не только от продавца, — поэтому она здесь справка.")
}
