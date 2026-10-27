package kz.ordaops.android.feature.business

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing
import java.time.LocalTime

/**
 * Главная владельца — как главная банковского приложения. Сверху карточка с одной
 * большой цифрой: выручка сегодня, листается на месяц. Под ней — круглые кнопки
 * того, что делают каждый день. Дальше — что требует решения, точки и сервисы.
 * Порт `OwnerHomeScreen.swift`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun OwnerHomeScreen(env: BusinessEnv) {
    val vm = env.vm
    val today by vm.homeToday.collectAsStateWithLifecycle()
    val month by vm.homeMonth.collectAsStateWithLifecycle()
    val loading by vm.homeLoading.collectAsStateWithLifecycle()
    val error by vm.homeError.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val extra by vm.includeExtra.collectAsStateWithLifecycle()
    val dashboard by vm.dashboard.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val options by vm.companyOptions.collectAsStateWithLifecycle()
    var showCompanies by remember { mutableStateOf(false) }
    val pager = rememberPagerState { 2 }

    // Точки или касса сменились — главная перечитывается (на айфоне это `.task(id:)`).
    LaunchedEffect(filter.companyIds, extra) { vm.reloadHome() }

    PullToRefreshBox(isRefreshing = loading && today != null, onRefresh = vm::refreshAll) {
        OwnerScreenColumn {
            val companyOptions = options.ifEmpty { month?.companies.orEmpty() }
            HomeHeader(env, filter.companyIds, companyOptions, onPick = { showCompanies = true }, onReset = { vm.setCompanies(emptySet()) })

            // Две карточки листаются вбок; под ними — точки позиции.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                HorizontalPager(pager, pageSpacing = Spacing.sm, modifier = Modifier.fillMaxWidth()) { page ->
                    if (page == 0) TodayCard(today) { env.goTab(BusinessTab.Analytics) } else MonthCard(month) { env.goTab(BusinessTab.Analytics) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    repeat(2) { index ->
                        val on = index == pager.currentPage
                        Box(
                            Modifier
                                .padding(horizontal = 3.dp)
                                .size(width = if (on) 18.dp else 6.dp, height = 6.dp)
                                .background(if (on) Orda.colors.text else Orda.colors.textDim.copy(alpha = 0.35f), CircleShape),
                        )
                    }
                }
            }

            QuickActions(env)
            Attention(env, pending, dashboard.data)
            PointsCard(env, month)
            ServicesCard(env, pending.size)
            if (error != null && today == null) ErrorBanner(error.orEmpty())
        }
    }
    if (showCompanies) {
        CompanyPickerSheet(
            companies = options.ifEmpty { month?.companies.orEmpty() },
            selection = filter.companyIds,
            onDismiss = { showCompanies = false },
            onDone = {
                vm.setCompanies(it)
                showCompanies = false
            },
        )
    }
}

/** Шапка: слева аватар и приветствие, справа — точки капсулой. */
@Composable
private fun HomeHeader(
    env: BusinessEnv,
    selected: Set<String>,
    options: List<OwnerAnalytics.CompanyOption>,
    onPick: () -> Unit,
    onReset: () -> Unit,
) {
    val c = Orda.colors
    val name = env.resolver.session.displayName
    val filtered = selected.isNotEmpty()
    Row(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Box(
            Modifier.size(44.dp).background(Brush.linearGradient(HeroColors.Accent), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(BizFormat.initials(name, "O"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(BizFormat.greeting(LocalTime.now().hour), color = c.textDim, fontSize = 13.sp)
            Text(BizFormat.firstName(name) ?: "ORDA CONTROL", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Выбрана не вся сеть — капсула синяя и с крестиком: цифры на экране не за всё, и это должно бросаться в глаза.
        Row(
            Modifier
                .height(36.dp)
                .clip(CircleShape)
                .background(if (filtered) c.brand else c.surface, CircleShape)
                .clickable(onClick = onPick)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val fg = if (filtered) Color.White else c.text
            Icon(Icons.Filled.Apartment, null, tint = if (filtered) Color.White else c.brand, modifier = Modifier.size(16.dp))
            Text(BusinessViewModel.companiesTitle(selected, options), color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (filtered) {
                Icon(Icons.Filled.Close, "Показать все точки", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp).clickable(onClick = onReset))
            } else {
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = c.textDim, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun TodayCard(data: OwnerAnalytics?, onClick: () -> Unit) {
    val pos = data?.pos
    // Живая выручка — по кассе: отчёты смен приходят в конце смены, и днём по ним «сегодня» почти всегда ноль.
    val value = pos?.amount ?: data?.kpi?.current?.revenue
    val previous = pos?.previous?.amount ?: data?.kpi?.previous?.revenue
    val change = if (value != null && previous != null) BizFormat.change(value, previous) else null
    val footer = if (pos != null) {
        listOf("Чеков" to pos.receipts.toString(), "Средний чек" to money(pos.avgCheck))
    } else {
        listOf("Смен закрыто" to (data?.kpi?.current?.shifts ?: 0).toString(), "Наличные" to money(data?.kpi?.current?.cash ?: 0.0))
    }
    HeroNumberCard(
        title = "Выручка сегодня",
        value = value,
        change = change,
        changeCaption = if (pos?.previous?.sameTime == true) "к этому часу вчера" else "к вчера",
        footer = footer,
        colors = HeroColors.Accent,
        onClick = onClick,
    )
}

@Composable
private fun MonthCard(data: OwnerAnalytics?, onClick: () -> Unit) {
    val cur = data?.kpi?.current
    val prev = data?.kpi?.previous
    HeroNumberCard(
        title = "Выручка за месяц",
        value = cur?.revenue,
        change = if (cur != null && prev != null) BizFormat.change(cur.revenue, prev.revenue) else null,
        changeCaption = "к тем же дням прошлого месяца",
        footer = listOf("Прибыль" to (cur?.let { money(it.profit) } ?: "—"), "Расходы" to (cur?.let { money(it.expense) } ?: "—")),
        colors = HeroColors.Deep,
        onClick = onClick,
    )
}

/** Круглые кнопки: расход, доход и задача пока ведут в раздел (форм добавления в приложении ещё нет). */
@Composable
private fun QuickActions(env: BusinessEnv) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
        if (env.can("expenses.create")) RoundAction(Icons.Filled.Remove, "Расход", Modifier.weight(1f)) { kz.ordaops.android.feature.business.finance.FinanceQuickAdd.pending.value = "expenses"; env.open("expenses") }
        if (env.can("income.create")) RoundAction(Icons.Filled.Add, "Доход", Modifier.weight(1f)) { kz.ordaops.android.feature.business.finance.FinanceQuickAdd.pending.value = "income"; env.open("income") }
        if (env.can("tasks.create")) RoundAction(Icons.Filled.Checklist, "Задача", Modifier.weight(1f)) { env.open("tasks") }
        RoundAction(Icons.Filled.PieChart, "Аналитика", Modifier.weight(1f)) { env.goTab(BusinessTab.Analytics) }
        RoundAction(Icons.Filled.GridView, "Сервисы", Modifier.weight(1f)) { env.goTab(BusinessTab.Services) }
    }
}

/** Что требует решения: расходы в очереди и товар на исходе. */
@Composable
private fun Attention(env: BusinessEnv, pending: List<PendingExpense>, dashboard: BusinessDashboard?) {
    val canStock = env.can("store.view") || env.can("store-warehouse.view")
    val lowStock = if (canStock) dashboard?.lowStock.orEmpty() else emptyList()
    if (pending.isEmpty() && lowStock.isEmpty()) return
    HorizontalRow {
        if (pending.isNotEmpty()) {
            AttentionCard(
                Icons.Filled.PendingActions, Color(0xFFF59E0B),
                "${pending.size} ${BizFormat.expensesWord(pending.size)} ждут решения",
                money(pending.sumOf { it.total }),
            ) { env.open("expenses-pending") }
        }
        if (lowStock.isNotEmpty()) {
            AttentionCard(
                Icons.Filled.Inventory2, Color(0xFFEF4444),
                "Заканчивается ${lowStock.size} ${BizFormat.itemsWord(lowStock.size)}",
                lowStock.take(2).joinToString(", ") { it.name },
            ) { env.open("store-warehouse") }
        }
    }
}

/** Точки за месяц; точки без движения (здание, склад) — не строка с нулём. */
@Composable
private fun PointsCard(env: BusinessEnv, month: OwnerAnalytics?) {
    val rows = month?.byCompany.orEmpty().filter { it.revenue != 0.0 || it.expense != 0.0 }
    if (rows.size <= 1) return
    val total = maxOf(rows.sumOf { it.revenue }, 1.0)
    OwnerSection("Точки", trailing = { Text("за месяц", color = Orda.colors.textDim, fontSize = 13.sp) }) {
        rows.forEachIndexed { index, row ->
            AmountRow(
                title = row.name,
                amount = money(row.revenue),
                subtitle = "прибыль ${money(row.profit)}",
                change = BizFormat.change(row.revenue, row.prevRevenue),
                share = row.revenue / total,
                tint = OwnerTint.point(index),
                onClick = {
                    env.vm.setCompanies(setOf(row.id))
                    env.goTab(BusinessTab.Analytics)
                },
                leading = { LetterBadge(row.name, OwnerTint.point(index)) },
            )
        }
    }
}

@Composable
private fun ServicesCard(env: BusinessEnv, pendingCount: Int) {
    val favorites = remember(env.access) { Services.favorites(env.access) }
    if (favorites.isEmpty()) return
    OwnerSection(
        "Сервисы",
        trailing = {
            Text("Все", color = Orda.colors.brand, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.pressScale().clickable { env.goTab(BusinessTab.Services) })
        },
    ) {
        TileGrid(favorites, columns = 4) { item ->
            ServiceTile(item, OwnerTint.forGroup(item.groupId), badge = if (item.pageId == "expenses-pending") pendingCount else 0) { env.open(item.pageId) }
        }
    }
}
