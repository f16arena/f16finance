package kz.ordaops.android.feature.business.finance

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.business.AnalyticsPeriod
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.LetterBadge
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.business.OwnerTint
import kz.ordaops.android.feature.business.PeriodBar
import kz.ordaops.android.feature.operator.money.TintedIcon
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip

/**
 * Быстрое добавление с главной: кнопки «Расход» / «Доход» открывают раздел и
 * сразу форму. Значение — `"income"` или `"expenses"`; экран выписки забирает его при показе.
 */
object FinanceQuickAdd {
    val pending = MutableStateFlow<String?>(null)
}

/**
 * Доходы или расходы — выписка как в банковском приложении: сверху сумма за
 * период, под ней статьи (нажатие фильтрует), операции по дням с итогом дня.
 * Нажатие на операцию открывает «чек» с правкой и удалением.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LedgerScreen(kind: LedgerKind) {
    val env = LocalBusiness.current
    val vm = financeViewModel(key = kind.name) { e, api -> LedgerViewModel(kind, api, e.vm.companies) }
    val isIncome = kind == LedgerKind.Income
    val state by vm.state.collectAsState()
    val period by vm.period.collectAsState()
    val companies by vm.companies.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var bucket by rememberSaveable { mutableStateOf<String?>(null) }
    var companyId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAllBuckets by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    val canCreate = env.can(if (isIncome) "income.create" else "expenses.create")
    val canEdit = env.can(if (isIncome) "income.edit" else "expenses.edit")
    val canDelete = env.can(if (isIncome) "income.delete" else "expenses.delete")

    LaunchedEffect(Unit) {
        vm.reload()
        vm.loadOperators()
    }
    // Кнопка «Расход» / «Доход» на главной: открыли раздел — сразу форма.
    LaunchedEffect(Unit) {
        FinanceQuickAdd.pending.collect { target ->
            if (target == (if (isIncome) "income" else "expenses") && canCreate) {
                adding = true
                FinanceQuickAdd.pending.value = null
            }
        }
    }

    val today = AnalyticsPeriod.businessToday()
    val companyName: (String?) -> String? = { id -> companies.firstOrNull { it.id == id }?.name }
    val data = state.data
    val ops = remember(data, companies) {
        if (data == null) emptyList()
        else if (isIncome) LedgerLogic.incomeOps(data.incomes, companyName)
        else LedgerLogic.expenseOps(data.expenses, companyName)
    }
    val filtered = remember(ops, bucket, companyId, query) { LedgerLogic.filter(ops, bucket, companyId, query) }
    val tint = if (isIncome) Orda.colors.positive else Orda.colors.negative
    val sign = if (isIncome) "+" else "−"

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                PeriodBar(
                    selection = period,
                    quick = listOf(AnalyticsPeriod.ThisWeek, AnalyticsPeriod.ThisMonth, AnalyticsPeriod.ThisQuarter, AnalyticsPeriod.ThisYear),
                    onSelect = {
                        bucket = null
                        companyId = null
                        vm.setPeriod(it)
                    },
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            IconButton(onClick = { searching = !searching; if (!searching) query = "" }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Search, "Поиск", tint = if (searching) Orda.colors.brand else Orda.colors.textDim)
                            }
                            if (canCreate) {
                                IconButton(onClick = { adding = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Add, "Добавить", tint = Orda.colors.brand)
                                }
                            }
                        }
                    },
                )
                if (searching) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(if (isIncome) "Точка, смена, комментарий" else "Статья, точка, сумма") },
                        singleLine = true,
                        shape = RoundedCornerShape(Radius.md),
                        colors = kz.ordaops.android.feature.operator.shift.ordaFieldColors(),
                        keyboardOptions = KeyboardOptions.Default,
                    )
                }
            }
        }

        StateContainer(
            state = state,
            onRetry = vm::reload,
            modifier = Modifier.weight(1f),
            isEmpty = { ops.isEmpty() },
            emptyIcon = if (isIncome) Icons.Filled.ArrowCircleDown else Icons.Filled.ArrowCircleUp,
            emptyTitle = if (isIncome) "Доходов нет" else "Расходов нет",
            emptyMessage = "За выбранный период записей не заведено.",
        ) {
            val total = filtered.sumOf { LedgerLogic.shownAmount(it, isIncome, bucket) }
            val pending = if (isIncome) emptyList() else filtered.filter { it.isPending }
            val days = remember(filtered, bucket) { LedgerLogic.days(filtered, isIncome, bucket) }
            val buckets = remember(ops, companyId) { LedgerLogic.buckets(ops, isIncome, companyId) }
            val companyTotals = remember(ops, bucket) { LedgerLogic.companyTotals(ops, isIncome, bucket) }
            val (from, to) = period.bounds()
            val daily = remember(filtered, bucket, period) {
                LedgerLogic.dailySums(from, to, today.toString(), filtered, isIncome, bucket)
                    .map { (d, v) -> Bar(AnalyticsPeriod.rangeLabel(d, d), v) }
            }

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    Modifier.widthIn(max = 720.dp).fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xxl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    item("hero") {
                        HeroSummary(
                            title = bucket ?: companyId?.let { companyName(it) }?.let { (if (isIncome) "Пришло · " else "Потрачено · ") + it }
                                ?: if (isIncome) "Пришло за период" else "Потрачено за период",
                            value = sign + money(total),
                            caption = "${filtered.size} ${LedgerLogic.operationsWord(filtered.size)}" +
                                if (pending.isEmpty()) "" else " · ${pending.size} на согласовании",
                            footer = summaryFooter(filtered, isIncome, bucket, pending),
                            colors = if (isIncome) FinanceColors.HeroPositive else FinanceColors.HeroNegative,
                        )
                    }
                    if (bucket != null || companyId != null) {
                        item("filters") {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                bucket?.let { FilterPill(it, if (isIncome) methodIcon(it) else expenseIcon(it)) { bucket = null } }
                                companyId?.let { FilterPill(companyName(it) ?: "Точка", Icons.Filled.Business) { companyId = null } }
                                Text(
                                    "Сбросить", color = Orda.colors.brand, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { bucket = null; companyId = null }.padding(Spacing.xs),
                                )
                            }
                        }
                    }
                    if (buckets.isNotEmpty()) {
                        item("buckets") {
                            BreakdownCard(buckets, isIncome, bucket, showAllBuckets, { showAllBuckets = !showAllBuckets }) {
                                bucket = if (bucket == it) null else it
                            }
                        }
                    }
                    if (companyTotals.size > 1) {
                        item("companies") {
                            CompaniesCard(companyTotals, companyName) { companyId = if (companyId == it) null else it }
                        }
                    }
                    if (daily.size > 1) {
                        item("daily") {
                            val peak = daily.maxByOrNull { it.value }
                            OwnerSection(
                                "По дням",
                                trailing = {
                                    if (peak != null && peak.value > 0) Text("больше всего ${peak.label}", color = Orda.colors.textDim, fontSize = 13.sp)
                                },
                            ) { DayBars(daily, tint) }
                        }
                    }
                    if (filtered.isEmpty()) {
                        item("nothing") { EmptyState(Icons.Filled.Search, "Ничего не нашлось", "Снимите фильтр или измените поиск.") }
                    }
                    days.forEach { day ->
                        item("day-${day.date}") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                                Text(LedgerLogic.dayTitle(day.date, today), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(sign + money(day.total), color = Orda.colors.textDim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        item("block-${day.date}") {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Orda.colors.surface)) {
                                day.items.forEachIndexed { i, op ->
                                    if (i > 0) RowDivider(startPadding = 68)
                                    OpRow(op, isIncome, bucket) { openedId = op.id }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        if (isIncome) AddIncomeSheet(vm) { adding = false } else AddExpenseSheet(vm) { adding = false }
    }

    val opened = ops.firstOrNull { it.id == openedId }
    if (opened != null) {
        ReceiptSheet(
            op = opened, isIncome = isIncome, companyName = companyName(opened.companyId), today = today,
            canEdit = canEdit, canDelete = canDelete,
            onEdit = { editingId = opened.id; openedId = null },
            onDelete = { vm.delete(opened.id) },
            onDismiss = { openedId = null },
        )
    }
    val editingIncome = if (isIncome) data?.incomes?.firstOrNull { it.id == editingId } else null
    val editingExpense = if (!isIncome) data?.expenses?.firstOrNull { it.id == editingId } else null
    if (editingIncome != null) EditIncomeSheet(vm, editingIncome) { editingId = null }
    if (editingExpense != null) EditExpenseSheet(vm, editingExpense) { editingId = null }
}

/** Чем платили: наличные, Kaspi, у доходов — ещё «карта и онлайн» вместе. С фильтрами. */
private fun summaryFooter(filtered: List<LedgerOp>, isIncome: Boolean, bucket: String?, pending: List<LedgerOp>): List<Pair<String, String>> {
    val sums = HashMap<String, Double>()
    for (op in filtered) for ((name, value) in op.parts) {
        if (bucket != null && isIncome && name != bucket) continue
        sums[name] = (sums[name] ?: 0.0) + value
    }
    val rows = mutableListOf(Methods.Cash to money(sums[Methods.Cash] ?: 0.0), Methods.Kaspi to money(sums[Methods.Kaspi] ?: 0.0))
    if (isIncome) {
        val other = (sums[Methods.Card] ?: 0.0) + (sums[Methods.Online] ?: 0.0)
        if (other > 0) rows += "Карта и онлайн" to money(other)
    } else if (pending.isNotEmpty()) {
        rows += "Ждёт решения" to money(pending.sumOf { it.amount })
    }
    return rows
}

@Composable
private fun BreakdownCard(
    items: List<Pair<String, Double>>,
    isIncome: Boolean,
    selected: String?,
    showAll: Boolean,
    onToggleAll: () -> Unit,
    onPick: (String) -> Unit,
) {
    val total = items.sumOf { it.second }.coerceAtLeast(1.0)
    val shown = if (showAll) items else items.take(5)
    fun tintOf(name: String) = if (isIncome) FinanceColors.methodTint(name) else FinanceColors.categoryTint(name)
    OwnerSection(
        if (isIncome) "Чем платили" else "Куда ушли деньги",
        trailing = {
            Text(
                "${items.size} ${if (isIncome) "способа" else LedgerLogic.articlesWord(items.size)}",
                color = Orda.colors.textDim, fontSize = 13.sp,
            )
        },
    ) {
        val slices = items.take(5).map { Slice(it.first, it.second, tintOf(it.first)) } +
            (items.drop(5).sumOf { it.second }.takeIf { it > 0 }?.let { listOf(Slice("Прочее", it, OtherSliceColor)) } ?: emptyList())
        DonutChart(slices, centerTitle = if (isIncome) "Пришло" else "Ушло", centerValue = money(total))
        Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            shown.forEach { (name, amount) ->
                val on = selected == name
                val color = tintOf(name)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressScale()
                        .clickable { onPick(name) }
                        .padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Box(Modifier.background(if (on) color else Color.Transparent, CircleShape)) {
                        TintedIcon(if (isIncome) methodIcon(name) else expenseIcon(name), if (on) Color.White else color, 40, corner = 20.dp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(money(amount), color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        Text(BizFormat.percent(amount / total * 100), color = Orda.colors.textDim, fontSize = 13.sp)
                        ShareBar(amount / total, color)
                    }
                }
            }
            if (items.size > 5) {
                Text(
                    if (showAll) "Свернуть" else "Все ${if (isIncome) "способы" else "статьи"} (${items.size})",
                    color = Orda.colors.brand, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleAll).padding(vertical = Spacing.xs),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CompaniesCard(rows: List<Pair<String, Double>>, name: (String?) -> String?, onPick: (String) -> Unit) {
    val total = rows.sumOf { it.second }.coerceAtLeast(1.0)
    OwnerSection("По точкам") {
        rows.forEachIndexed { index, (id, amount) ->
            val title = name(id) ?: "Точка"
            val color = OwnerTint.point(index)
            Row(
                Modifier.fillMaxWidth().pressScale().clickable { onPick(id) }.padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                LetterBadge(title, color)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(title, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(money(amount), color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Text(BizFormat.percent(amount / total * 100), color = Orda.colors.textDim, fontSize = 13.sp)
                    ShareBar(amount / total, color)
                }
            }
        }
    }
}

@Composable
private fun OpRow(op: LedgerOp, isIncome: Boolean, bucket: String?, onClick: () -> Unit) {
    val (icon, tint) = if (isIncome) {
        if (op.shift == "night") Icons.Filled.DarkMode to FinanceColors.Night else Icons.Filled.LightMode to FinanceColors.Day
    } else {
        expenseIcon(op.title) to FinanceColors.categoryTint(op.title)
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        TintedIcon(icon, tint, 42, corner = 21.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(op.title, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (op.isPending) {
                Text("На согласовании", color = Orda.colors.warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            } else if (!op.subtitle.isNullOrEmpty()) {
                Text(op.subtitle, color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            (if (isIncome) "+" else "−") + money(LedgerLogic.shownAmount(op, isIncome, bucket)),
            color = if (isIncome) Orda.colors.positive else Orda.colors.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** «Чек» операции: большая сумма, откуда и чем, правка и удаление. */
@Composable
private fun ReceiptSheet(
    op: LedgerOp,
    isIncome: Boolean,
    companyName: String?,
    today: java.time.LocalDate,
    canEdit: Boolean,
    canDelete: Boolean,
    onEdit: () -> Unit,
    onDelete: suspend () -> String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var confirming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val (icon, tint) = if (isIncome) {
        if (op.shift == "night") Icons.Filled.DarkMode to FinanceColors.Night else Icons.Filled.LightMode to FinanceColors.Day
    } else expenseIcon(op.title) to FinanceColors.categoryTint(op.title)

    OrdaSheet(op.title, onDismiss) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TintedIcon(icon, tint, 64, corner = 32.dp)
            Text(
                (if (isIncome) "+" else "−") + money(op.amount),
                color = if (isIncome) Orda.colors.positive else Orda.colors.text,
                fontSize = 34.sp, fontWeight = FontWeight.Bold,
            )
            if (op.isPending) {
                Row(
                    Modifier.background(Orda.colors.warning.copy(alpha = 0.14f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.AccessTime, null, tint = Orda.colors.warning, modifier = Modifier.size(14.dp))
                    Text("Ждёт согласования", color = Orda.colors.warning, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Orda.colors.surface).padding(horizontal = Spacing.lg)) {
            LineRow("Дата", LedgerLogic.dayTitle(op.date, today))
            companyName?.let { LineRow("Точка", it) }
            op.parts.forEach { (name, value) -> LineRow(name, money(value)) }
            op.comment?.takeIf { it.isNotEmpty() }?.let { LineRow("Комментарий", it) }
        }
        if (canEdit) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Orda.colors.brand).clickable(onClick = onEdit).padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Edit, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text("  Изменить", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (canDelete) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Orda.colors.negative.copy(alpha = 0.10f))
                    .clickable(enabled = !deleting) { confirming = true }.padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Delete, null, tint = Orda.colors.negative, modifier = Modifier.size(18.dp))
                Text(if (deleting) "  Удаляем…" else "  Удалить", color = Orda.colors.negative, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        error?.let { Text(it, color = Orda.colors.negative, fontSize = 13.sp) }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(if (isIncome) "Удалить доход?" else "Удалить расход?") },
            text = { Text("Запись исчезнет из отчётов и ОПиУ. Удаление попадёт в журнал событий.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    deleting = true
                    scope.launch {
                        val failure = onDelete()
                        deleting = false
                        if (failure == null) { haptics.success(); onDismiss() } else { error = failure; haptics.error() }
                    }
                }) { Text("Удалить ${money(op.amount)}", color = Orda.colors.negative) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Отмена") } },
        )
    }
}
