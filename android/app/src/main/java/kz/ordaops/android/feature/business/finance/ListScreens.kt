package kz.ordaops.android.feature.business.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.Company
import kz.ordaops.android.feature.business.LetterBadge
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.operator.money.PeriodStepper
import kz.ordaops.android.feature.operator.money.TintedIcon
import kz.ordaops.android.feature.operator.money.currentWeekStart
import kz.ordaops.android.feature.operator.money.weekTitle
import kz.ordaops.android.feature.operator.shift.ordaFieldColors
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.time.LocalDate

// Небольшие списочные экраны: категории расходов, доверенные поставщики, долги с точки.

// ── Категории ───────────────────────────────────────────────────────────────

/**
 * Статьи расходов: бюджет месяца против факта. Вверху — то, что уже вышло за
 * лимит: о перерасходе надо узнать до конца месяца. Статьи без бюджета отдельно:
 * «0 % израсходовано» там, где лимита просто нет, было бы враньём.
 */
@Composable
fun CategoriesScreen() {
    val vm = financeViewModel("categories") { _, api -> LoadViewModel(fetchFn = { api.categories() }) }
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.reload() }

    StateContainer(
        state, onRetry = vm::reload, modifier = Modifier.fillMaxSize(),
        isEmpty = { it.isEmpty() },
        emptyIcon = Icons.Filled.Label, emptyTitle = "Категорий нет",
        emptyMessage = "Заведите статьи расходов, чтобы видеть структуру трат.",
    ) { all ->
        val budgeted = all.filter { it.hasBudget }
        val over = budgeted.filter { it.isOverBudget }
        val near = budgeted.filter { it.isNearLimit }
        val rest = budgeted.filter { !it.isOverBudget && !it.isNearLimit }
        val free = all.filter { !it.hasBudget }.sortedByDescending { it.spentThisMonth }
        OwnerScreenColumn {
            // Карточка краснеет, если хоть одна статья вышла за лимит.
            HeroSummary(
                title = "Потрачено за месяц",
                value = money(all.sumOf { it.spentThisMonth }),
                caption = if (over.isEmpty()) "все статьи в пределах бюджета"
                else "${over.size} ${LedgerLogic.articlesWord(over.size)} сверх бюджета",
                footer = listOf("Бюджет месяца" to money(budgeted.sumOf { it.monthlyBudget }), "Превышено" to "${over.size}", "Близко к лимиту" to "${near.size}"),
                colors = if (over.isEmpty()) FinanceColors.HeroPositive else FinanceColors.HeroNegative,
            )
            if (over.isNotEmpty()) BudgetCard("Превышен бюджет", over, Orda.colors.negative)
            if (near.isNotEmpty()) BudgetCard("Близко к лимиту", near, Orda.colors.warning)
            if (rest.isNotEmpty()) BudgetCard("В пределах бюджета", rest, null)
            if (free.isNotEmpty()) {
                val maxFree = (free.firstOrNull()?.spentThisMonth ?: 0.0).coerceAtLeast(1.0)
                OwnerSection("Без бюджета", trailing = { Text("сравнивать не с чем", color = Orda.colors.textDim, fontSize = 13.sp) }) {
                    free.forEachIndexed { i, c ->
                        if (i > 0) RowDivider(52)
                        // Доля от самой крупной статьи: без лимита сравнивать больше не с чем.
                        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            LetterBadge(c.name, Orda.colors.textMuted)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth()) {
                                    Text(c.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(money(c.spentThisMonth), color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Text(c.groupLabel, color = Orda.colors.textDim, fontSize = 13.sp)
                                ShareBar(c.spentThisMonth / maxFree, Orda.colors.textMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BudgetCard(title: String, rows: List<ExpenseCategory>, accent: androidx.compose.ui.graphics.Color?) {
    val sorted = rows.sortedByDescending { it.usage ?: 0.0 }
    OwnerSection(title, trailing = { Text("${sorted.size}", color = accent ?: Orda.colors.textDim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }) {
        sorted.forEachIndexed { i, c ->
            if (i > 0) RowDivider(52)
            val tint = when {
                c.isOverBudget -> Orda.colors.negative
                c.isNearLimit -> Orda.colors.warning
                else -> Orda.colors.brand
            }
            // Буква статьи в кружке цвета её состояния: перерасход видно по столбику кружков.
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                LetterBadge(c.name, tint)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.groupLabel, color = Orda.colors.textDim, fontSize = 13.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${money(c.spentThisMonth)} из ${money(c.monthlyBudget)}",
                                color = if (tint == Orda.colors.brand) Orda.colors.text else tint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            )
                            if (c.isOverBudget) Text("сверх на ${money(c.spentThisMonth - c.monthlyBudget)}", color = Orda.colors.negative, fontSize = 12.sp)
                            else Text("осталось ${money(c.remaining)}", color = Orda.colors.textDim, fontSize = 12.sp)
                        }
                    }
                    // Полоса обрезается на единице: перерасход показан текстом.
                    ShareBar(minOf(c.usage ?: 0.0, 1.0), tint)
                }
            }
        }
    }
}

// ── Доверенные поставщики ───────────────────────────────────────────────────

/** Доверенные поставщики: расход в их пользу проводится без чека. Только просмотр, как на айфоне. */
@Composable
fun WhitelistScreen() {
    val env = LocalBusiness.current
    val vm = financeViewModel("whitelist") { _, api -> LoadViewModel(fetchFn = { api.vendors() }) }
    val state by vm.state.collectAsState()
    val companies by env.vm.companies.collectAsState()
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.reload() }

    StateContainer(state, onRetry = vm::reload, modifier = Modifier.fillMaxSize()) { vendors ->
        fun companyName(id: String?) = if (id == null) "Все точки" else companies.firstOrNull { it.id == id }?.name ?: "Точка"
        val scoped = vendors.count { it.companyId != null }
        // Сначала общие на всю организацию: правило «для всех» действует шире и о нём важнее знать.
        val sorted = vendors.sortedWith(compareBy<ExpenseVendor>({ it.companyId != null }, { it.name.lowercase() }))
        val shown = if (query.isBlank()) sorted else sorted.filter {
            it.name.contains(query, true) || (it.notes?.contains(query, true) ?: false) || companyName(it.companyId).contains(query, true)
        }
        OwnerScreenColumn {
            HeroSummary(
                title = "Доверенных поставщиков",
                value = "${vendors.size}",
                caption = "расход в их пользу проводится без чека",
                footer = listOf("На все точки" to "${vendors.size - scoped}", "Ограничены точкой" to "$scoped"),
            )
            OwnerSection("Что это значит") {
                Text(
                    "Расход в пользу такого получателя проводится без фото чека. Удобно для зарплат, аренды, уборки и регулярных услуг — и опасно для всего остального.",
                    color = Orda.colors.textMuted, fontSize = 15.sp,
                )
                Text("Категория из строки подставляется в расход автоматически.", color = Orda.colors.textDim, fontSize = 13.sp)
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Имя, точка или заметка") }, shape = RoundedCornerShape(Radius.md), colors = ordaFieldColors(),
            )
            OwnerSection("Список", trailing = { Text("сначала на всех точках", color = Orda.colors.textDim, fontSize = 13.sp) }) {
                if (shown.isEmpty()) {
                    Text(
                        if (query.isEmpty()) "Доверенных поставщиков нет — каждый расход требует чека" else "Никого не найдено",
                        color = Orda.colors.textDim, fontSize = 14.sp,
                    )
                }
                shown.forEach { v ->
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        TintedIcon(Icons.Filled.GppGood, Orda.colors.positive, 40)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(v.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(
                                    companyName(v.companyId), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    color = if (v.companyId != null) Orda.colors.info else Orda.colors.warning,
                                )
                            }
                            v.notes?.takeIf { it.isNotEmpty() }?.let { Text(it, color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
    }
}

// ── Долги с точки ───────────────────────────────────────────────────────────

/** Долги клиентов, записанные операторами за неделю; погашение — право `point-debts.mark_paid`. */
@Composable
fun PointDebtsScreen() {
    val env = LocalBusiness.current
    var week by remember { mutableStateOf(currentWeekStart(kz.ordaops.android.feature.business.AnalyticsPeriod.businessToday())) }
    val vm = financeViewModel("point-debts") { _, api -> PointDebtsViewModel(api) }
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var settling by remember { mutableStateOf<PointDebt?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val canSettle = env.can("point-debts.mark_paid")
    val current = currentWeekStart(kz.ordaops.android.feature.business.AnalyticsPeriod.businessToday())

    LaunchedEffect(week) { vm.show(week) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Spacing.lg)) {
            PeriodStepper(
                title = weekTitle(week),
                caption = if (week == current) "текущая неделя" else null,
                canGoForward = week < current,
                onBack = { week = LocalDate.parse(week).minusDays(7).toString() },
                onForward = { week = LocalDate.parse(week).plusDays(7).toString() },
            )
        }
        StateContainer(state, onRetry = vm::reload, modifier = Modifier.weight(1f)) { data ->
            OwnerScreenColumn {
                val unpaid = data.unpaidAmount
                HeroSummary(
                    title = if (unpaid > 0) "Не погашено за неделю" else "Все долги погашены",
                    value = money(unpaid),
                    footer = listOf("Всего за неделю" to money(data.totalAmount), "Погашено" to money(data.totalAmount - unpaid), "Позиций" to "${data.totalCount}"),
                    colors = if (unpaid > 0) FinanceColors.HeroNegative else FinanceColors.HeroPositive,
                )
                if (data.items.isEmpty()) {
                    OwnerSection("Долги") { Text("За эту неделю долгов нет", color = Orda.colors.positive, fontSize = 15.sp) }
                } else {
                    // Группируем по точкам: долг спрашивают с конкретной точки, а не со всей сети.
                    val grouped = data.items.groupBy { it.companyId.orEmpty() }
                    val ordered: List<Company> = data.companies + (grouped.keys - data.companies.map { it.id }.toSet()).map { Company(it, "Без точки") }
                    ordered.forEach { company ->
                        val rows = grouped[company.id].orEmpty()
                        if (rows.isNotEmpty()) {
                            OwnerSection(company.name, trailing = { Text(money(rows.sumOf { it.amount }), color = Orda.colors.textDim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }) {
                                rows.forEachIndexed { i, debt ->
                                    if (i > 0) RowDivider(52)
                                    DebtRow(debt, if (canSettle && !debt.isPaid) ({ settling = debt }) else null)
                                }
                            }
                        }
                    }
                }
                error?.let { ErrorBanner(it) }
            }
        }
    }

    settling?.let { debt ->
        AlertDialog(
            onDismissRequest = { settling = null },
            title = { Text("Долг погашен?") },
            text = { Text("${debt.clientName} — ${money(debt.amount)}. Отмечайте, только когда деньги вернули: запись видна в журнале.") },
            confirmButton = {
                TextButton(onClick = {
                    settling = null
                    scope.launch {
                        error = vm.settle(debt)
                        if (error == null) haptics.success() else haptics.error()
                    }
                }) { Text("Погашен") }
            },
            dismissButton = { TextButton(onClick = { settling = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun DebtRow(debt: PointDebt, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        LetterBadge(debt.clientName, if (debt.isPaid) Orda.colors.textDim else Orda.colors.brand)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(debt.clientName, color = if (debt.isPaid) Orda.colors.textDim else Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            debt.comment?.takeIf { it.isNotEmpty() }?.let { Text(it, color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                money(debt.amount), color = if (debt.isPaid) Orda.colors.textDim else Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                textDecoration = if (debt.isPaid) TextDecoration.LineThrough else null,
            )
            // Статус подписью, а не плашкой: плашка съедала полстроки.
            Text(debt.statusLabel.lowercase(), color = if (debt.isPaid) Orda.colors.positive else Orda.colors.warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Недельный список долгов: смена недели перезагружает, погашение — перечитывает. */
class PointDebtsViewModel(private val api: FinanceApi) : LoadViewModel<PointDebtWeek>() {
    private var week = ""

    override suspend fun fetch(): PointDebtWeek = api.pointDebts(week)

    fun show(weekStart: String) {
        if (week == weekStart && state.value.hasData) return
        week = weekStart
        reset()
        reload()
    }

    /** Текст ошибки или `null`. */
    suspend fun settle(debt: PointDebt): String? = try {
        api.markDebtsPaid(listOf(debt.id))
        reload()
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }
}
