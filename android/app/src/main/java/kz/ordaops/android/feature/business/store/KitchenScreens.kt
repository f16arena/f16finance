package kz.ordaops.android.feature.business.store

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.feature.operator.shift.todayString
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing
import java.time.LocalDate

// Расходники и техкарты. Порт по текущему вебу (`components/store/consumables-page`,
// `app/(main)/production`).

// ── Расходники ───────────────────────────────────────────────────────────────

private enum class ConsumablesTab(val title: String) { Balances("Остатки"), Issues("Выдачи"), Norms("Нормы") }

/**
 * Расходники точек (перчатки, пакеты, чековая лента): остаток против нормы расхода,
 * журнал выдач и нормы. Выдача на точку сама проходит всю цепочку заявки и списывает
 * товар со склада.
 */
@Composable
fun ConsumablesScreen() {
    val env = LocalBusiness.current
    val vm = rememberStoreExtraViewModel()
    val state by vm.consumables.collectAsState()
    val toast = rememberToast()
    var tab by remember { mutableStateOf(ConsumablesTab.Balances) }
    var search by remember { mutableStateOf("") }
    var issuing by remember { mutableStateOf(false) }
    var editNorm by remember { mutableStateOf<Consumables.Balance?>(null) }
    val canWrite = env.can("store-consumables.create")
    LaunchedEffect(Unit) { vm.loadConsumables() }

    StoreScaffold(toast.message) {
        StateContainer(state, onRetry = vm::loadConsumables, emptyTitle = "Расходников нет", emptyMessage = "Заведите расходник в каталоге — вид «Расходник».", isEmpty = { false }) { data ->
            val q = search.trim()
            StoreLazy {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        PillSegment(ConsumablesTab.entries.map { it to it.title }, tab) { tab = it }
                        if (tab != ConsumablesTab.Issues) StoreSearchField(search, { search = it }, placeholder = "Название расходника")
                    }
                }
                when (tab) {
                    ConsumablesTab.Balances -> {
                        val rows = data.balances.filter { q.isEmpty() || it.itemName.contains(q, ignoreCase = true) }
                        if (rows.isEmpty()) item { EmptyState(Icons.Filled.Inbox, "Остатков нет", "Расходники появятся после приёмки.") }
                        items(rows, key = { it.locationId + it.itemId }) { b ->
                            val days = data.stockDays(b)
                            val norm = data.norm(b)
                            // Запас в днях против порога тревоги: красный — пора везти, янтарный — скоро, зелёный — хватает.
                            val tone = when {
                                days == null || norm == null -> StoreTone.neutral
                                days > norm.alertDays * 2 -> StoreTone.good
                                days > norm.alertDays -> StoreTone.waits
                                else -> StoreTone.loss
                            }
                            RowCard {
                                Row(
                                    Modifier.fillMaxWidth().let { if (canWrite) it.clickable { editNorm = b } else it }.padding(vertical = Spacing.sm),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                                ) {
                                    ItemBadge(b.itemName, tone)
                                    Column(Modifier.weight(1f)) {
                                        Text(b.itemName, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                        Text(b.locationName + (norm?.let { " · норма ${formatQty(it.monthlyQty)} в месяц" } ?: " · нет нормы"), color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(formatQty(b.quantity), color = if (b.quantity <= 0) StoreTone.neutral else Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                        if (days != null) Text("$days дн.", color = tone, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                    ConsumablesTab.Issues -> {
                        if (data.issues.isEmpty()) item { EmptyState(Icons.Filled.Inbox, "Выдач не было", "Журнал выдач расходников на точки.") }
                        items(data.issues, key = { it.id }) { i ->
                            RowCard {
                                Column(Modifier.fillMaxWidth().padding(vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(i.companyName ?: "Точка", color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                        Text(fmtDay(i.createdAt), color = Orda.colors.textDim, fontSize = 13.sp)
                                    }
                                    i.lines.forEach { (name, qty) ->
                                        Row { Text(name, color = Orda.colors.textMuted, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text(formatQty(qty), color = Orda.colors.text, fontSize = 14.sp) }
                                    }
                                    if (!i.comment.isNullOrBlank()) Text(i.comment, color = Orda.colors.textDim, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    ConsumablesTab.Norms -> {
                        if (data.norms.isEmpty()) item { EmptyState(Icons.Filled.Inbox, "Норм нет", "Норму задают в остатках: нажмите на расходник.") }
                        items(data.norms.filter { n -> q.isEmpty() || (data.items.firstOrNull { it.id == n.itemId }?.name ?: "").contains(q, ignoreCase = true) }, key = { it.id }) { n ->
                            RowCard {
                                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(data.items.firstOrNull { it.id == n.itemId }?.name ?: "Расходник", color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                        Text(data.points.firstOrNull { it.locationId == n.locationId }?.name ?: data.balances.firstOrNull { it.locationId == n.locationId }?.locationName ?: "Склад", color = Orda.colors.textDim, fontSize = 13.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("${formatQty(n.monthlyQty)} в месяц", color = Orda.colors.text, fontSize = 15.sp)
                                        Text("тревога за ${n.alertDays} дн.", color = Orda.colors.textDim, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                item { Box(Modifier.padding(bottom = 80.dp)) }
            }
            if (canWrite && tab == ConsumablesTab.Issues && data.points.isNotEmpty() && data.items.isNotEmpty()) {
                Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.BottomEnd) {
                    ExtendedFloatingActionButton(
                        onClick = { issuing = true },
                        icon = { Icon(Icons.Filled.Add, null) },
                        text = { Text("Выдать") },
                        containerColor = Orda.colors.brand,
                        contentColor = Color.White,
                    )
                }
            }
            if (issuing) IssueSheet(vm, data, onDone = { issuing = false; toast.show("Выдача записана, склад списан") }, onDismiss = { issuing = false })
            editNorm?.let { b -> NormSheet(vm, data, b, onDone = { editNorm = null; toast.show("Норма сохранена") }, onDismiss = { editNorm = null }) }
        }
    }
}

@Composable
private fun IssueSheet(vm: StoreExtraViewModel, data: Consumables, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var point by remember { mutableStateOf(data.points.firstOrNull()) }
    var item by remember { mutableStateOf<Consumables.Item?>(null) }
    var date by remember { mutableStateOf(todayString()) }
    var packs by remember { mutableStateOf("1") }
    var packSize by remember { mutableStateOf("1") }
    var comment by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val qty = parseNumber(packs) * parseNumber(packSize)
    val problem = when {
        point == null -> "Выберите точку"
        item == null -> "Выберите расходник"
        qty <= 0 -> "Укажите количество"
        else -> null
    }
    OrdaSheet("Выдача расходника", onDismiss) {
        PickerField("Точка", point, data.points, { it.name + (it.companyName?.let { c -> " · $c" } ?: "") }) { point = it }
        PickerField("Расходник", item, data.items, { it.name }) { item = it }
        DateInput("Дата выдачи", date, { date = it }, maxToday = true)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(Modifier.weight(1f)) { NumberInput("Упаковок", packs, { packs = it }) }
            Box(Modifier.weight(1f)) { NumberInput("В упаковке", packSize, { packSize = it }) }
        }
        StatLine("Всего", formatQty(qty) + (item?.let { " ${it.unit}" } ?: ""))
        TextInput("Комментарий", comment, { comment = it })
        Text("Выдача сама пройдёт заявку целиком и спишется со склада точки.", color = Orda.colors.textDim, fontSize = 13.sp)
        BlockerHint(problem)
        FormError(error)
        PrimaryButton("Записать выдачу", Modifier.fillMaxWidth(), enabled = problem == null, loading = saving) {
            saving = true
            error = null
            scope.launch {
                val e = vm.recordIssue(point!!.locationId, date, item!!.id, qty, comment)
                saving = false
                if (e == null) onDone() else error = e
            }
        }
    }
}

@Composable
private fun NormSheet(vm: StoreExtraViewModel, data: Consumables, balance: Consumables.Balance, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val norm = data.norm(balance)
    var monthly by remember { mutableStateOf(norm?.let { formatQty(it.monthlyQty) } ?: "") }
    var alert by remember { mutableStateOf((norm?.alertDays ?: 14).toString()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    OrdaSheet("Норма расхода", onDismiss) {
        Text("${balance.itemName} · ${balance.locationName}", color = Orda.colors.textDim, fontSize = 14.sp)
        NumberInput("Сколько уходит в месяц", monthly, { monthly = it })
        NumberInput("Предупредить за, дней", alert, { alert = it.filter { c -> c.isDigit() } })
        FormError(error)
        PrimaryButton("Сохранить", Modifier.fillMaxWidth(), enabled = parseNumber(monthly) > 0, loading = saving) {
            saving = true
            scope.launch {
                val e = vm.saveNorm(balance.itemId, balance.locationId, parseNumber(monthly), alert.toIntOrNull() ?: 14)
                saving = false
                if (e == null) onDone() else error = e
            }
        }
    }
}

// ── Техкарты ─────────────────────────────────────────────────────────────────

private enum class ProductionTab(val title: String) { Recipes("Техкарты"), Ingredients("Сырьё"), Analysis("Анализ") }

/** Food cost: доля себестоимости в цене. Ниже — лучше; пороги 25 % и 35 %, как на сайте. */
@Composable
private fun foodCostTone(pct: Double) = when {
    pct <= 25 -> StoreTone.good
    pct <= 35 -> StoreTone.waits
    else -> StoreTone.loss
}

/**
 * Производство: техкарты с себестоимостью порции и food cost, остатки сырья с приходом
 * и пересчётом, анализ продаж за период. Правка самих техкарт — на сайте.
 */
@Composable
fun ProductionScreen() {
    val env = LocalBusiness.current
    val vm = rememberStoreExtraViewModel()
    val state by vm.production.collectAsState()
    val analysis by vm.analysis.collectAsState()
    val toast = rememberToast()
    var tab by remember { mutableStateOf(ProductionTab.Recipes) }
    var search by remember { mutableStateOf("") }
    var openRecipe by remember { mutableStateOf<String?>(null) }
    var stockAction by remember { mutableStateOf<Pair<String, Ingredient>?>(null) }
    var from by remember { mutableStateOf(LocalDate.now().minusDays(6).toString()) }
    var to by remember { mutableStateOf(LocalDate.now().toString()) }
    LaunchedEffect(Unit) { vm.loadProduction() }
    LaunchedEffect(tab, from, to) { if (tab == ProductionTab.Analysis) vm.loadAnalysis(from, to) }
    BackHandler(enabled = openRecipe != null) { openRecipe = null }

    StoreScaffold(toast.message) {
        StateContainer(state, onRetry = vm::loadProduction, emptyTitle = "Техкарт нет", emptyMessage = "Заведите их на сайте.", isEmpty = { false }) { data ->
            val q = search.trim()
            val recipe = data.recipes.firstOrNull { it.id == openRecipe }
            if (recipe != null) {
                RecipeDetail(recipe, data.salePrices[recipe.saleItemId.orEmpty()], onBack = { openRecipe = null })
                return@StateContainer
            }
            StoreLazy {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        PillSegment(ProductionTab.entries.map { it to it.title }, tab) { tab = it }
                        if (tab != ProductionTab.Analysis) StoreSearchField(search, { search = it }, placeholder = "Название")
                    }
                }
                when (tab) {
                    ProductionTab.Recipes -> {
                        val rows = data.recipes.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
                        if (rows.isEmpty()) item { EmptyState(Icons.Filled.Restaurant, "Техкарт нет", "Техкарты заводятся на сайте.") }
                        items(rows, key = { it.id }) { r ->
                            val price = data.salePrices[r.saleItemId.orEmpty()]
                            val pct = r.foodCostPct(price)
                            RowCard {
                                Row(Modifier.fillMaxWidth().clickable { openRecipe = r.id }.padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                    ItemBadge(r.name, if (pct != null) foodCostTone(pct) else Orda.colors.brand)
                                    Column(Modifier.weight(1f)) {
                                        Text(r.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                        Text(listOfNotNull(r.category, if (r.isSemiFinished) "полуфабрикат" else null, "выход ${formatQty(r.outputQty)} ${r.outputUnit}").joinToString(" · "), color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(money(r.portionCost), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        if (pct != null) Text("food cost ${BizFormat.percent(pct)}", color = foodCostTone(pct), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                    ProductionTab.Ingredients -> {
                        val rows = data.ingredients.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
                        if (rows.isEmpty()) item { EmptyState(Icons.Filled.Inbox, "Сырья нет") }
                        items(rows, key = { it.id }) { ing ->
                            RowCard {
                                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                    ItemBadge(ing.name, if (ing.stock < 0) StoreTone.loss else Orda.colors.brand)
                                    Column(Modifier.weight(1f)) {
                                        Text(ing.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                        Text("${money(ing.price)} / ${ing.unit}", color = Orda.colors.textDim, fontSize = 13.sp)
                                    }
                                    Text("${formatQty(ing.stock)} ${ing.unit}", color = if (ing.stock < 0) StoreTone.loss else if (ing.stock == 0.0) StoreTone.neutral else Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                    if (env.can("production.stock_receipt")) TextButton(onClick = { stockAction = "receipt" to ing }) { Text("Приход") }
                                    if (env.can("production.stock_count")) TextButton(onClick = { stockAction = "count" to ing }) { Text("Пересчёт") }
                                }
                            }
                        }
                    }
                    ProductionTab.Analysis -> {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                    Box(Modifier.weight(1f)) { DateInput("С", from, { from = it }) }
                                    Box(Modifier.weight(1f)) { DateInput("По", to, { to = it }) }
                                }
                                val a = analysis.data
                                if (a != null) {
                                    StoreCard {
                                        StatLine("Продано порций", formatQty(a.sold))
                                        StatLine("Выручка", money(a.revenue))
                                        StatLine("Себестоимость", money(a.foodCost))
                                        StatLine("Маржа", money(a.margin), if (a.margin > 0) StoreTone.good else StoreTone.neutral)
                                        if (a.revenue > 0) StatLine("Food cost", BizFormat.percent(a.foodCostPct), foodCostTone(a.foodCostPct))
                                    }
                                    if (a.rows.isEmpty()) InlineEmpty(Icons.Filled.Restaurant, "Продаж блюд с техкартой за период нет")
                                } else if (analysis.error != null) {
                                    FormError(analysis.error)
                                }
                            }
                        }
                        items(analysis.data?.rows.orEmpty(), key = { it.name }) { r ->
                            RowCard {
                                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(r.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                        Text("${formatQty(r.sold)} порц. · себестоимость ${money(r.foodCost)}", color = Orda.colors.textDim, fontSize = 13.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(money(r.revenue), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        Text("food cost ${BizFormat.percent(r.foodCostPct)}", color = foodCostTone(r.foodCostPct), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    stockAction?.let { (kind, ing) ->
        var value by remember(ing.id, kind) { mutableStateOf("") }
        var saving by remember(ing.id, kind) { mutableStateOf(false) }
        var error by remember(ing.id, kind) { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        OrdaSheet(if (kind == "receipt") "Приход: ${ing.name}" else "Пересчёт: ${ing.name}", { stockAction = null }) {
            Text("Сейчас ${formatQty(ing.stock)} ${ing.unit}", color = Orda.colors.textDim, fontSize = 14.sp)
            NumberInput(if (kind == "receipt") "Сколько пришло, ${ing.unit}" else "Сколько на самом деле, ${ing.unit}", value, { value = it })
            FormError(error)
            PrimaryButton("Записать", Modifier.fillMaxWidth(), enabled = value.isNotBlank() && (kind == "count" || parseNumber(value) > 0), loading = saving) {
                saving = true
                scope.launch {
                    val e = vm.stockAction(kind, ing.id, parseNumber(value))
                    saving = false
                    if (e == null) {
                        stockAction = null
                        toast.show(if (kind == "receipt") "Приход записан" else "Остаток обновлён")
                    } else {
                        error = e
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeDetail(r: Recipe, salePrice: Double?, onBack: () -> Unit) {
    val pct = r.foodCostPct(salePrice)
    StoreLazy {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                TextButton(onClick = onBack) { Text("← К техкартам") }
                StoreCard {
                    Text(r.name, color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (r.category != null) StatLine("Категория", r.category)
                    StatLine("Выход", "${formatQty(r.outputQty)} ${r.outputUnit}")
                    StatLine("Себестоимость партии", money(r.recipeCost))
                    StatLine("Себестоимость порции", money(r.portionCost))
                    if (salePrice != null && salePrice > 0) StatLine("Цена продажи", money(salePrice))
                    if (pct != null) StatLine("Food cost", BizFormat.percent(pct), foodCostTone(pct))
                    if (salePrice != null && salePrice > 0) StatLine("Маржа с порции", money(salePrice - r.portionCost), if (salePrice > r.portionCost) StoreTone.good else StoreTone.loss)
                }
                StoreCard {
                    SectionTitle("Состав", "${r.components.size}")
                    r.components.forEachIndexed { i, c ->
                        if (i > 0) RowDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, color = Orda.colors.text, fontSize = 15.sp, maxLines = 2)
                                if (c.wastePct > 0) Text("потери ${BizFormat.percent(c.wastePct)}", color = StoreTone.waits, fontSize = 12.sp)
                            }
                            Text("${formatQty(c.qty)} ${c.unit}", color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
