package kz.ordaops.android.feature.business

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.ordaops.android.feature.operator.money.TintedIcon
import kz.ordaops.android.ui.components.AnimatedText
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

/**
 * Аналитика владельца. Период — одним касанием: день, неделя, месяц, год, остальное
 * — под календарём. Сверху одна большая цифра выбранного показателя с графиком,
 * ниже — из чего она сложилась: точки, статьи, оплаты, часы, товары, люди.
 * Порт `OwnerAnalyticsScreen.swift`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerAnalyticsScreen(env: BusinessEnv) {
    val vm = env.vm
    val state by vm.analytics.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val options by vm.companyOptions.collectAsStateWithLifecycle()
    var metric by remember { mutableStateOf(OverviewMetric.Revenue) }
    var showCompanies by remember { mutableStateOf(false) }
    var showAllExpenses by remember { mutableStateOf(false) }
    val c = Orda.colors

    LaunchedEffect(Unit) { if (!vm.analytics.value.hasData) vm.reloadAnalytics() }

    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = vm::reloadAnalytics) {
        OwnerScreenColumn {
            // Шапка
            Row(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("Аналитика", color = c.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if ((state.isLoading || state.isRefreshing) && state.hasData) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.brand)
                }
                if (options.size > 1) {
                    val all = filter.isAllCompanies
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(if (all) c.surface else c.brand, CircleShape)
                            .clickable { showCompanies = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val fg = if (all) c.text else Color.White
                        Icon(Icons.Filled.Apartment, null, tint = fg, modifier = Modifier.size(16.dp))
                        Text(BusinessViewModel.companiesTitle(filter.companyIds, options), color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                        Icon(Icons.Filled.KeyboardArrowDown, null, tint = fg, modifier = Modifier.size(14.dp))
                    }
                    if (!all) {
                        Icon(
                            Icons.Filled.Cancel, "Показать все точки", tint = c.textDim,
                            modifier = Modifier.size(26.dp).clickable { vm.setCompanies(emptySet()) },
                        )
                    }
                }
            }
            PeriodBar(
                selection = filter.period,
                quick = listOf(AnalyticsPeriod.Today, AnalyticsPeriod.ThisWeek, AnalyticsPeriod.ThisMonth, AnalyticsPeriod.ThisYear),
                onSelect = vm::setPeriod,
                trailing = { CompareMenu(filter.compare, vm::setCompare) },
                extraCash = { ExtraCashToggle(env) },
            )

            val data = state.data
            when {
                data != null -> {
                    MainCard(data, metric) { metric = it }
                    StatsCard(data)
                    CompaniesCard(data, metric) { vm.setCompanies(setOf(it)) }
                    ExpensesCard(data, showAllExpenses) { showAllExpenses = !showAllExpenses }
                    PaymentsCard(data)
                    HoursCard(data)
                    WeekdaysCard(data)
                    ProductsCard(data)
                    OperatorsCard(data)
                    OwnerFootnote("Выручка и расходы — по отчётам смен, как на сайте. Чеки, часы и товары — по кассе.")
                }
                state.error != null && !state.isLoading -> {
                    EmptyState(
                        icon = if (state.isOffline) Icons.Filled.CloudOff else Icons.Filled.ErrorOutline,
                        title = if (state.isOffline) "Нет связи" else "Не удалось загрузить",
                        message = state.error,
                        actionTitle = "Повторить",
                        onAction = vm::reloadAnalytics,
                    )
                }
                else -> {
                    // Скелет: места под карточки, чтобы экран не прыгал, когда придёт ответ.
                    listOf(330, 90, 220).forEach { h ->
                        Box(Modifier.fillMaxWidth().height(h.dp).background(c.surface, RoundedCornerShape(24.dp)))
                    }
                }
            }
        }
    }
    if (showCompanies) {
        CompanyPickerSheet(options, filter.companyIds, onDismiss = { showCompanies = false }) {
            vm.setCompanies(it)
            showCompanies = false
        }
    }
}

// ── Главная цифра ────────────────────────────────────────────────────────────

@Composable
private fun MainCard(data: OwnerAnalytics, metric: OverviewMetric, onMetric: (OverviewMetric) -> Unit) {
    val c = Orda.colors
    val cur = data.kpi.current
    val prev = data.kpi.previous
    // Один день: отчёты смен приходят в конце смены, и днём по ним выручка почти ноль против целого вчерашнего
    // дня. Выручку дня берём по кассе к этому часу, а вместо графика из одного столбика — часы.
    val isDay = data.period.from == data.period.to
    val livePos = if (isDay && metric == OverviewMetric.Revenue) data.pos else null
    val (value, previous) = when {
        livePos != null -> livePos.amount to livePos.previous.amount
        metric == OverviewMetric.Revenue -> cur.revenue to prev.revenue
        metric == OverviewMetric.Expense -> cur.expense to prev.expense
        else -> cur.profit to prev.profit
    }
    val change = BizFormat.change(value, previous)
    val color = ChartColors.of(metric)
    Column(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(24.dp)).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            OverviewMetric.entries.forEach { item ->
                val on = metric == item
                Column(Modifier.clickable { onMetric(item) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.title, color = if (on) c.text else c.textDim, fontSize = 16.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
                    Box(Modifier.width(36.dp).height(3.dp).background(if (on) ChartColors.of(item) else Color.Transparent, CircleShape))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AnimatedText(
                money(value),
                androidx.compose.ui.text.TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold),
                if (value < 0) c.negative else c.text,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                if (change != null) ChangeText(change, metric.higherIsBetter)
                Text(
                    if (livePos?.previous?.sameTime == true) "в это время было ${money(previous)}" else "было ${money(previous)}",
                    color = c.textDim, fontSize = 13.sp,
                )
            }
            if (isDay) {
                Text(if (livePos != null) "по кассе" else "по отчётам смен — они закрываются в конце смены", color = c.textDim, fontSize = 12.sp)
            }
        }
        if (livePos != null) {
            HourBars(livePos.byHour, color)
        } else if (data.series.size > 1) {
            ComparisonChart(
                points = AnalyticsChart.points(data, metric),
                color = color,
                asBars = data.period.group != "day" || data.series.size <= 7,
            )
        }
    }
}

// ── Короткие показатели ──────────────────────────────────────────────────────

private data class Stat(val title: String, val value: String, val change: Double?, val isPoints: Boolean)

@Composable
private fun StatsCard(data: OwnerAnalytics) {
    val c = Orda.colors
    val cur = data.kpi.current
    val prev = data.kpi.previous
    val stats = buildList {
        val pos = data.pos
        if (pos != null) {
            add(Stat("Средний чек", money(pos.avgCheck), BizFormat.change(pos.avgCheck, pos.previous.avgCheck), false))
            add(Stat("Чеков", pos.receipts.toString(), BizFormat.change(pos.receipts.toDouble(), pos.previous.receipts.toDouble()), false))
        } else {
            val perShift = if (cur.shifts > 0) cur.revenue / cur.shifts else 0.0
            val prevPerShift = if (prev.shifts > 0) prev.revenue / prev.shifts else 0.0
            add(Stat("За смену", money(perShift), BizFormat.change(perShift, prevPerShift), false))
            add(Stat("Смен", cur.shifts.toString(), BizFormat.change(cur.shifts.toDouble(), prev.shifts.toDouble()), false))
        }
        val marginChange = cur.marginPercent?.let { now -> prev.marginPercent?.let { now - it } }
        add(Stat("Маржа", BizFormat.percent(cur.marginPercent), marginChange, true))
    }
    Row(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(24.dp)).padding(vertical = Spacing.lg, horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stats.forEachIndexed { index, stat ->
            if (index > 0) Box(Modifier.width(1.dp).height(40.dp).background(c.border))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stat.title, color = c.textDim, fontSize = 12.sp)
                Text(stat.value, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                stat.change?.let { change ->
                    if (stat.isPoints) {
                        // Для маржи разница — в процентных пунктах, а не в процентах.
                        Text(
                            BizFormat.percent(change, signed = true).replace(" %", " п.п."),
                            color = if (change >= 0) c.positive else c.negative, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        ChangeText(change)
                    }
                }
            }
        }
    }
}

// ── Точки ────────────────────────────────────────────────────────────────────

@Composable
private fun CompaniesCard(data: OwnerAnalytics, metric: OverviewMetric, onPick: (String) -> Unit) {
    if (data.byCompany.size <= 1) return
    val total = maxOf(data.byCompany.sumOf { maxOf(metric.value(it), 0.0) }, 1.0)
    OwnerSection("По точкам") {
        data.byCompany.forEachIndexed { index, row ->
            val v = metric.value(row)
            AmountRow(
                title = row.name,
                amount = money(v),
                subtitle = BizFormat.percent(maxOf(v, 0.0) / total * 100) + " от всех",
                change = BizFormat.change(v, metric.previous(row)),
                higherIsBetter = metric.higherIsBetter,
                share = maxOf(v, 0.0) / total,
                tint = OwnerTint.point(index),
                onClick = { onPick(row.id) },
                leading = { LetterBadge(row.name, OwnerTint.point(index)) },
            )
        }
    }
}

// ── Расходы ──────────────────────────────────────────────────────────────────

private val sharePalette = listOf(Color(0xFF2563EB), Color(0xFF0D9488), Color(0xFFD97706), Color(0xFF4F46E5))

private fun expenseIcon(name: String): ImageVector = when (BizFormat.expenseIconKey(name)) {
    "rent" -> Icons.Filled.Home
    "salary" -> Icons.Filled.People
    "goods" -> Icons.Filled.ShoppingCart
    "tax" -> Icons.Filled.AccountBalance
    "utilities" -> Icons.Filled.Bolt
    "internet" -> Icons.Filled.Wifi
    "ads" -> Icons.Filled.Campaign
    "repair" -> Icons.Filled.Construction
    "equipment" -> Icons.Filled.Computer
    "transport" -> Icons.Filled.LocalTaxi
    "cleaning" -> Icons.Filled.CleaningServices
    "bank" -> Icons.Filled.CreditCard
    else -> Icons.Filled.LocalOffer
}

@Composable
private fun ExpensesCard(data: OwnerAnalytics, showAll: Boolean, onToggle: () -> Unit) {
    val categories = data.expenseCategories
    if (categories.isNullOrEmpty()) return
    val total = maxOf(categories.sumOf { it.amount }, 1.0)
    val shown = if (showAll) categories else categories.take(6)
    OwnerSection(
        "Куда ушли деньги",
        trailing = { Text(money(data.kpi.current.expense), color = Orda.colors.textMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
    ) {
        shown.forEachIndexed { index, row ->
            val tint = sharePalette[index % sharePalette.size]
            AmountRow(
                title = row.name,
                amount = money(row.amount),
                subtitle = BizFormat.percent(row.amount / total * 100),
                change = BizFormat.change(row.amount, row.prevAmount),
                higherIsBetter = false,
                share = row.amount / total,
                tint = tint,
                leading = { TintedIcon(expenseIcon(row.name), tint, 40, corner = 20.dp) },
            )
        }
        if (categories.size > 6) {
            Text(
                if (showAll) "Свернуть" else "Все статьи (${categories.size})",
                color = Orda.colors.brand, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().pressScale().clickable(onClick = onToggle).padding(top = Spacing.xs),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ── Оплаты ───────────────────────────────────────────────────────────────────

private data class PayRow(val title: String, val icon: ImageVector, val amount: Double, val tint: Color)

@Composable
private fun PaymentsCard(data: OwnerAnalytics) {
    val k = data.kpi.current
    val rows = listOf(
        PayRow("Наличные", Icons.Filled.Payments, k.cash, Color(0xFF10B981)),
        PayRow("Kaspi", Icons.Filled.Payments, k.kaspi, Color(0xFFEF4444)),
        PayRow("Карта", Icons.Filled.CreditCard, k.card, Color(0xFF3B82F6)),
        PayRow("Онлайн", Icons.Filled.Language, k.online, Color(0xFF8B5CF6)),
    ).filter { it.amount > 0 }
    if (rows.isEmpty()) return
    val total = maxOf(rows.sumOf { it.amount }, 1.0)
    OwnerSection("Чем платили") {
        rows.forEach { row ->
            AmountRow(
                title = row.title,
                amount = money(row.amount),
                subtitle = BizFormat.percent(row.amount / total * 100),
                share = row.amount / total,
                tint = row.tint,
                leading = { TintedIcon(row.icon, row.tint, 40, corner = 20.dp) },
            )
        }
    }
}

// ── Часы пик, дни недели, товары, операторы ──────────────────────────────────

@Composable
private fun HoursCard(data: OwnerAnalytics) {
    val pos = data.pos ?: return
    if (pos.receipts <= 0) return
    val peak = pos.byHour.maxByOrNull { it.amount }
    OwnerSection(
        "Часы пик",
        trailing = {
            if (peak != null && peak.amount > 0) {
                Text("пик ${peak.hour}:00–${peak.hour + 1}:00", color = Orda.colors.brand, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        HeatmapGrid(pos.heatmap, ChartColors.Revenue)
    }
}

@Composable
private fun WeekdaysCard(data: OwnerAnalytics) {
    if (data.weekdays.sumOf { it.days } < 7) return
    OwnerSection("Дни недели", trailing = { Text("средняя выручка", color = Orda.colors.textDim, fontSize = 13.sp) }) {
        WeekdayBars(data.weekdays)
    }
}

@Composable
private fun ProductsCard(data: OwnerAnalytics) {
    val items = data.pos?.topItems.orEmpty().take(8)
    if (items.isEmpty()) return
    val top = maxOf(items.first().revenue, 1.0)
    val gold = Color(0xFFF59E0B)
    OwnerSection("Топ товаров") {
        items.forEachIndexed { index, item ->
            AmountRow(
                title = item.name,
                amount = money(item.revenue),
                subtitle = "${quantity(item.qty)} шт" + (item.profit?.let { " · прибыль ${money(it)}" } ?: ""),
                share = item.revenue / top,
                tint = gold,
                leading = {
                    Box(Modifier.size(40.dp).background(Orda.colors.surfaceRaised, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${index + 1}", color = if (index < 3) gold else Orda.colors.textDim, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                },
            )
        }
    }
}

/** Целое — без дробной части, иначе ревизия пестрит бессмысленными `12,000`. */
private fun quantity(value: Double): String =
    if (value == Math.rint(value)) value.toLong().toString() else "%.3f".format(java.util.Locale.US, value).trimEnd('0').replace('.', ',')

@Composable
private fun OperatorsCard(data: OwnerAnalytics) {
    val ops = data.operators ?: return
    if (ops.rows.isEmpty()) return
    val top = maxOf(ops.rows.first().revenue, 1.0)
    OwnerSection("Операторы", trailing = { Text("выручка за смены", color = Orda.colors.textDim, fontSize = 13.sp) }) {
        ops.rows.take(10).forEachIndexed { index, row ->
            AmountRow(
                title = row.name,
                amount = money(row.revenue),
                subtitle = "${row.shifts} смен · ${money(row.perShift)} за смену",
                change = BizFormat.change(row.perShift, row.prevPerShift),
                share = row.revenue / top,
                tint = OwnerTint.point(index + 1),
                leading = { LetterBadge(row.name, OwnerTint.point(index + 1)) },
            )
        }
    }
}
