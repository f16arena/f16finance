package kz.ordaops.android.feature.business

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.ordaops.android.core.access.StaffRolePlaybook
import kz.ordaops.android.core.access.playbook
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Главная сотрудника — своя у каждой должности: карточка должности, кнопки того,
 * что она делает каждый день, что ждёт решения, сервисы в её порядке. Порядок и
 * подача — из [StaffRolePlaybook], а что показать — только из выданного на
 * `/access`. Порт `StaffHomeScreen.swift` (без карточек «Мои задачи» и «Моя зарплата»).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffHomeScreen(env: BusinessEnv) {
    val vm = env.vm
    val playbook = env.resolver.playbook
    val dashboard by vm.dashboard.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val c = Orda.colors
    val name = env.resolver.session.displayName
    val reachable = remember(env.access) { Services.groups(env.access).flatMap { it.items }.map { it.pageId }.toSet() }

    // Сводка обновляется сама, пока открыта: продажи идут на точке.
    LaunchedEffect(vm) { vm.pollDashboard() }

    PullToRefreshBox(isRefreshing = dashboard.isRefreshing, onRefresh = vm::refreshAll) {
        OwnerScreenColumn {
            // Шапка
            Row(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Box(Modifier.size(44.dp).background(Brush.linearGradient(HeroColors.Accent), CircleShape), contentAlignment = Alignment.Center) {
                    Text(BizFormat.initials(name, playbook.title.take(1)), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text(BizFormat.greeting(LocalTime.now().hour), color = c.textDim, fontSize = 13.sp)
                    Text(BizFormat.firstName(name) ?: playbook.title, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Название должности: с сервера, если он его прислал, иначе из набора.
                Text(
                    env.resolver.session.roleLabel?.takeIf { it.isNotEmpty() } ?: playbook.title,
                    color = c.brand, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.background(c.brand.copy(alpha = 0.12f), CircleShape).padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }

            Hero(env, playbook, dashboard.data, pending.size)

            // Быстрые кнопки: первые четыре из набора должности + «Сервисы».
            val quick = quickItems(env, playbook, reachable)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
                quick.forEach { item -> RoundAction(item.icon, item.title, Modifier.weight(1f), item.run) }
                RoundAction(Icons.Filled.GridView, "Сервисы", Modifier.weight(1f)) { env.goTab(BusinessTab.Services) }
            }

            // Что требует внимания
            val shownPending = if (env.can("expenses-pending.view")) pending else emptyList()
            val canStock = env.can("store.view") || env.can("store-warehouse.view")
            val lowStock = if (canStock) dashboard.data?.lowStock.orEmpty() else emptyList()
            if (shownPending.isNotEmpty() || lowStock.isNotEmpty()) {
                HorizontalRow {
                    if (shownPending.isNotEmpty()) {
                        AttentionCard(
                            Icons.Filled.PendingActions, c.warning,
                            "${shownPending.size} ${BizFormat.expensesWord(shownPending.size)} ждут решения",
                            money(shownPending.sumOf { it.total }),
                        ) { env.open("expenses-pending") }
                    }
                    if (lowStock.isNotEmpty()) {
                        AttentionCard(
                            Icons.Filled.Inventory2, c.negative,
                            "Заканчивается ${lowStock.size} ${BizFormat.itemsWord(lowStock.size)}",
                            lowStock.take(2).joinToString(", ") { it.name },
                        ) { env.open("store-warehouse") }
                    }
                }
            }

            // Сервисы должности
            val favorites = remember(env.access) { Services.favorites(env.access) }
            if (favorites.isNotEmpty()) {
                OwnerSection(
                    "Мои сервисы",
                    trailing = {
                        Text("Все", color = c.brand, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.pressScale().clickable { env.goTab(BusinessTab.Services) })
                    },
                ) {
                    TileGrid(favorites, columns = 4) { item ->
                        ServiceTile(item, OwnerTint.forGroup(item.groupId), badge = if (item.pageId == "expenses-pending") pending.size else 0) { env.open(item.pageId) }
                    }
                }
            }
        }
    }
}

private class QuickItem(val icon: ImageVector, val title: String, val run: () -> Unit)

/** Денежные показатели — по праву на доходы, как в прежней сводке: главная не обходной путь мимо `/access`. */
private fun quickItems(env: BusinessEnv, playbook: StaffRolePlaybook, reachable: Set<String>): List<QuickItem> {
    val items = mutableListOf<QuickItem>()
    for (action in playbook.actions) {
        when (action) {
            StaffRolePlaybook.Action.AddExpense -> if (env.can("expenses.create")) items += QuickItem(Icons.Filled.Remove, "Расход") { env.open("expenses") }
            StaffRolePlaybook.Action.AddIncome -> if (env.can("income.create")) items += QuickItem(Icons.Filled.Add, "Доход") { env.open("income") }
            StaffRolePlaybook.Action.AddTask -> if (env.can("tasks.create")) items += QuickItem(Icons.Filled.Checklist, "Задача") { env.open("tasks") }
            is StaffRolePlaybook.Action.Page -> if (action.id in reachable) {
                items += QuickItem(pageIcon(action.id), Services.quickTitle(action.id, env.access.catalog.page(action.id)?.label)) { env.open(action.id) }
            }
        }
    }
    return items.take(4)
}

@Composable
private fun Hero(env: BusinessEnv, playbook: StaffRolePlaybook, dashboard: BusinessDashboard?, pendingCount: Int) {
    val canSeeRevenue = env.can("income.view") || env.can("reports.view")
    val data = if (canSeeRevenue) dashboard else null
    val name = env.resolver.session.displayName
    val greeting = BizFormat.greeting(LocalTime.now().hour)
    val stats = buildList {
        if (canSeeRevenue && dashboard != null) {
            add("Чеков" to dashboard.today.count.toString())
            add("За месяц" to money(dashboard.monthTotal))
        }
        if (env.can("expenses-pending.view")) add("На решении" to pendingCount.toString())
        if (size < 2) {
            val today = LocalDate.now()
            val weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru"))
            add(0, "Сегодня" to "$weekday, ${today.dayOfMonth}")
        }
    }.take(3)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(HeroColors.Deep), RoundedCornerShape(Radius.xl))
            .padding(Spacing.xl),
    ) {
        Text(playbook.focus, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (data != null) {
            Text("Выручка сегодня", color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, modifier = Modifier.padding(top = Spacing.md))
            Text(money(data.today.total), color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        } else {
            val line = BizFormat.firstName(name)?.let { "$greeting, $it" } ?: greeting
            Text(line, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.padding(top = Spacing.sm))
        }
        Row(Modifier.padding(top = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            stats.forEach { (label, value) ->
                Column {
                    Text(label, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
                    Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}
