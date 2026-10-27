package kz.ordaops.android.feature.business.store

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerContentWidth
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// Склад, витрина, движения, поиск. Порт StockScreen / ShowcaseScreen /
// MovementsScreen / StoreSearchScreen.

/** Ленивая колонка по центру с потолком ширины: тысяча строк склада не должна строиться разом. */
@Composable
internal fun StoreLazy(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = OwnerContentWidth).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            content = content,
        )
    }
}

/** Строка списка на белой подложке. */
@Composable
internal fun RowCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Orda.colors.surface, RoundedCornerShape(18.dp)).padding(horizontal = Spacing.md),
    ) { content() }
}

// ── Склад ────────────────────────────────────────────────────────────────────

private enum class StockMode(val label: String) { ByItem("По товару"), ByLocation("По точкам") }

/**
 * Полный склад с поиском, сканером и разрезом по точкам. Два взгляда на одни
 * данные: «по товару» — сколько всего, «по точкам» — где лежит. Витрина сюда не
 * входит: у неё свой раздел, иначе цифры не сходились бы с соседним.
 */
@Composable
fun StockScreen() {
    val env = LocalBusiness.current
    val vm = rememberStoreViewModel()
    val state by vm.overview.collectAsState()
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(StockMode.ByItem) }
    var onlyLow by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<StockBalance, StoreLocation>?>(null) }
    var creating by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val canEdit = env.can("store-warehouse.edit")
    val canCreate = env.can("store-warehouse.create_item")
    LaunchedEffect(Unit) { vm.loadOverview() }

    StoreScaffold(toast.message) {
        StateContainer(state, onRetry = vm::loadOverview, emptyTitle = "Склад пуст", emptyMessage = "Остатков пока нет.", isEmpty = { it.balances.isEmpty() && it.locations.isEmpty() }) { overview ->
            val stockroomIds = overview.locations.filter { it.kind == "warehouse" || it.kind == "backroom" }.map { it.id }.toSet()
            // Пустой список мест — данные ещё не пришли: показать всё честнее, чем пустой склад при полных полках.
            val balances = overview.balances.filter { stockroomIds.isEmpty() || it.locationId in stockroomIds }
            val query = search.trim()
            fun matches(b: StockBalance) = query.isEmpty() || b.name.contains(query, ignoreCase = true) || (b.barcode?.contains(query) == true)
            val filtered = balances.filter { (!onlyLow || it.isLow) && matches(it) }
            val totals = totalsOf(balances)
            val low = totals.count { it.isLow }

            StoreLazy {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        StoreSearchField(search, { search = it }, onScan = { scanning = true })
                        PillSegment(StockMode.entries.map { it to it.label }, mode) { mode = it }
                        ChipRow {
                            StoreChip("${totals.size} ${plural(totals.size, "позиция", "позиции", "позиций")}", on = false) {}
                            if (low > 0) StoreChip("Заканчивается: $low", on = onlyLow, tint = StoreTone.loss) { onlyLow = !onlyLow }
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth()) {
                            EmptyState(Icons.Filled.Inventory2, if (query.isEmpty()) "Склад пуст" else "Ничего не найдено", if (query.isEmpty()) "Остатков пока нет." else "Попробуйте другой запрос.")
                            // Отсканировали код, которого нет, — заводят товар прямо у коробки.
                            if (canCreate && query.isNotEmpty() && query.all { it.isDigit() } && query.length >= 6) {
                                PrimaryButton("Завести товар со штрихкодом $query", Modifier.fillMaxWidth()) { creating = query }
                            }
                        }
                    }
                } else if (mode == StockMode.ByItem) {
                    val rows = totalsOf(filtered)
                    items(rows, key = { it.id }) { t ->
                        RowCard { StockRow(t.name, t.quantity, t.unit, t.threshold, caption = if (t.locationCount > 1) "в ${t.locationCount} точках" else null) }
                    }
                } else {
                    val grouped = filtered.groupBy { it.locationId }
                    overview.locations.filter { grouped[it.id]?.isNotEmpty() == true }.forEach { loc ->
                        item(key = "loc-${loc.id}") {
                            RowCard {
                                Row(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                    Icon(locationIcon(loc.kind), null, tint = Orda.colors.brand, modifier = Modifier.padding(end = 0.dp))
                                    Text(loc.name, color = Orda.colors.textDim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text("${grouped[loc.id]?.size ?: 0}", color = Orda.colors.textDim, fontSize = 13.sp)
                                }
                                grouped[loc.id].orEmpty().forEachIndexed { i, b ->
                                    if (i > 0) RowDivider()
                                    // Править можно только склад: товар на витрину попадает заявкой, а не рукой.
                                    StockRow(b.name, b.quantity, b.unit, b.threshold) {
                                        if (canEdit && loc.kind == "warehouse" && loc.companyId != null) {
                                            IconButton(onClick = { editing = b to loc }) { Icon(Icons.Filled.Edit, "Поправить остаток", tint = Orda.colors.brand) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (scanning) ScanDialog(onCode = { search = it }, onDismiss = { scanning = false })

    editing?.let { (balance, loc) ->
        var value by remember(balance.id) { mutableStateOf(formatQty(balance.quantity)) }
        var saving by remember(balance.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!saving) editing = null },
            title = { Text("Поправить остаток") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(
                        "${balance.name}. Сейчас числится ${formatQtyUnit(balance.quantity, balance.unit)}. Введите столько, сколько лежит на самом деле — остаток станет равен этому числу.",
                        fontSize = 14.sp,
                    )
                    NumberInput("Сколько на полке", value, { value = it })
                }
            },
            confirmButton = {
                TextButton(enabled = !saving && value.isNotBlank(), onClick = {
                    saving = true
                    scope.launch {
                        // Ставим остаток равным числу, а не прибавляем разницу: у полки знают количество, а не величину ошибки.
                        val e = vm.setWarehouseQuantity(loc.companyId.orEmpty(), balance.itemId, parseNumber(value))
                        saving = false
                        if (e == null) {
                            toast.show("${balance.name} — ${formatQtyUnit(parseNumber(value), balance.unit)}")
                            editing = null
                        } else {
                            error = e
                        }
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Отмена") } },
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Не удалось") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("Понятно") } },
        )
    }

    creating?.let { barcode ->
        val state2 = vm.overview.value.data
        CreateItemSheet(vm, barcode, state2?.locations.orEmpty(), onDone = { name ->
            creating = null
            toast.show("Товар «$name» заведён")
        }, onDismiss = { creating = null })
    }
}

/** Заведение товара по штрихкоду: имя, штрихкод, цены — остальное дописывают позже на сайте. */
@Composable
private fun CreateItemSheet(vm: StoreViewModel, barcode: String, locations: List<StoreLocation>, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val companies = locations.mapNotNull { l -> l.companyId?.let { StoreCompany(it, l.companyName ?: "Точка") } }.distinctBy { it.id }
    var company by remember { mutableStateOf(companies.firstOrNull()) }
    var name by remember { mutableStateOf("") }
    var sale by remember { mutableStateOf("") }
    var purchase by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val problem = when {
        company == null -> "Не определена точка"
        name.isBlank() -> "Укажите название"
        else -> null
    }
    OrdaSheet("Новый товар", onDismiss) {
        Text("Штрихкод $barcode", color = Orda.colors.textDim, fontSize = 14.sp)
        if (companies.size > 1) PickerField("Точка", company, companies, { it.name }) { company = it }
        TextInput("Название", name, { name = it })
        NumberInput("Цена продажи", sale, { sale = it }, suffix = "₸")
        NumberInput("Цена закупки", purchase, { purchase = it }, suffix = "₸")
        BlockerHint(problem)
        FormError(error)
        PrimaryButton("Завести", Modifier.fillMaxWidth(), enabled = problem == null, loading = saving) {
            saving = true
            scope.launch {
                val e = vm.createStoreItem(company!!.id, name, barcode, parseNumber(sale), parseNumber(purchase))
                saving = false
                if (e == null) onDone(name.trim()) else error = e
            }
        }
    }
}

// ── Витрина ──────────────────────────────────────────────────────────────────

/**
 * Витрина выбранной точки. Наверх со склада идёт заявка, а не перенос: остаток
 * уходит только после одобрения. Вниз — сразу: это возврат своего же товара.
 */
@Composable
fun ShowcaseScreen() {
    val env = LocalBusiness.current
    val vm = rememberStoreViewModel()
    val state by vm.showcase.collectAsState()
    val toast = rememberToast()
    var search by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<ShowcaseRow?>(null) }
    var onlyRefill by remember { mutableStateOf(false) }
    val canMove = env.can("store-showcase.move") || env.can("store-showcase.return_to_warehouse")
    LaunchedEffect(Unit) { vm.loadShowcase() }

    StoreScaffold(toast.message) {
        StateContainer(state, onRetry = { vm.loadShowcase(state.data?.selectedCompanyId) }, emptyTitle = "Витрина пуста", isEmpty = { false }) { page ->
            val query = search.trim()
            val refill = page.rows.count { it.needsRefill }
            val rows = page.rows.filter { (!onlyRefill || it.needsRefill) && (query.isEmpty() || it.name.contains(query, ignoreCase = true)) }
            StoreLazy {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        if (page.companies.size > 1) {
                            ChipRow {
                                page.companies.forEach { c ->
                                    StoreChip(c.name, on = c.id == page.selectedCompanyId) { vm.loadShowcase(c.id) }
                                }
                            }
                        }
                        StoreSearchField(search, { search = it }, placeholder = "Название товара", onScan = { scanning = true })
                        if (refill > 0) {
                            ChipRow { StoreChip("Пусто на витрине: $refill", on = onlyRefill, tint = StoreTone.waits) { onlyRefill = !onlyRefill } }
                        }
                    }
                }
                if (rows.isEmpty()) {
                    item { EmptyState(Icons.Filled.Inventory2, if (query.isEmpty()) "На витрине пусто" else "Ничего не найдено", if (query.isEmpty()) "Здесь появятся товары, которые стоят перед покупателем." else null) }
                } else {
                    items(rows, key = { it.itemId }) { row ->
                        RowCard {
                            StockRow(
                                row.name, row.showcase, row.unit ?: "шт", threshold = null,
                                caption = if (row.needsRefill) "пусто · на складе ${formatQtyUnit(row.warehouse, row.unit)}" else "на складе ${formatQtyUnit(row.warehouse, row.unit)}",
                                onClick = if (canMove) ({ moving = row }) else null,
                            )
                        }
                    }
                }
            }
        }
    }
    if (scanning) ScanDialog(onCode = { search = it }, onDismiss = { scanning = false })

    moving?.let { row ->
        val companyId = state.data?.selectedCompanyId ?: state.data?.companies?.firstOrNull()?.id
        if (companyId != null) {
            ShowcaseMoveSheet(vm, row, companyId, env.can("store-showcase.move"), env.can("store-showcase.return_to_warehouse"), onDone = { text ->
                moving = null
                toast.show(text)
                vm.loadShowcase(companyId)
            }, onDismiss = { moving = null })
        }
    }
}

private enum class MoveDirection(val title: String, val action: String, val note: String) {
    ToShowcase("На витрину", "Создать заявку", "Заявку одобряет управляющий — до этого остаток со склада не уйдёт."),
    ToWarehouse("На склад", "Вернуть на склад", "Товар вернётся на склад сразу: это ваш же остаток, согласовывать нечего."),
}

@Composable
private fun ShowcaseMoveSheet(vm: StoreViewModel, row: ShowcaseRow, companyId: String, canMove: Boolean, canReturn: Boolean, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val directions = buildList {
        if (canMove) add(MoveDirection.ToShowcase)
        if (canReturn) add(MoveDirection.ToWarehouse)
    }
    var direction by remember { mutableStateOf(directions.first()) }
    var amount by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val qty = parseNumber(amount)
    val limit = if (direction == MoveDirection.ToShowcase) row.warehouse else row.showcase
    val problem = when {
        qty <= 0 -> "Укажите количество"
        qty > limit -> "Больше, чем есть: ${formatQtyUnit(limit, row.unit)}"
        else -> null
    }
    OrdaSheet(row.name, onDismiss) {
        Text("На витрине ${formatQtyUnit(row.showcase, row.unit)} · на складе ${formatQtyUnit(row.warehouse, row.unit)}", color = Orda.colors.textDim, fontSize = 14.sp)
        if (directions.size > 1) PillSegment(directions.map { it to it.title }, direction) { direction = it }
        NumberInput("Количество, ${row.unit ?: "шт"}", amount, { amount = it })
        if (direction == MoveDirection.ToShowcase) TextInput("Комментарий", comment, { comment = it })
        Text(direction.note, color = Orda.colors.textDim, fontSize = 13.sp)
        BlockerHint(problem)
        FormError(error)
        PrimaryButton(direction.action, Modifier.fillMaxWidth(), enabled = problem == null, loading = saving) {
            saving = true
            scope.launch {
                val e = if (direction == MoveDirection.ToShowcase) {
                    vm.requestToShowcase(companyId, row.itemId, qty, comment.takeIf { it.isNotBlank() })
                } else {
                    vm.returnFromShowcase(companyId, row.itemId, qty)
                }
                saving = false
                if (e == null) {
                    onDone(if (direction == MoveDirection.ToShowcase) "Заявка создана" else "Вернули на склад: ${row.name}")
                } else {
                    error = e
                }
            }
        }
    }
}

// ── Движения ─────────────────────────────────────────────────────────────────

/** Полная история движений с фильтром по складу и витрине. */
@Composable
fun MovementsScreen() {
    val vm = rememberStoreViewModel()
    val state by vm.movements.collectAsState()
    var scope by remember { mutableStateOf("all") }
    var kind by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scope) { vm.loadMovements(scope) }

    StoreScaffold(null) {
        StateContainer(state, onRetry = { vm.loadMovements(scope) }, emptyTitle = "Движений нет", emptyMessage = "Здесь появятся приёмки, списания и продажи.", isEmpty = { it.isEmpty() }) { all ->
            val kinds = all.map { it.kind }.distinct()
            val shown = if (kind == null) all else all.filter { it.kind == kind }
            StoreLazy {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        PillSegment(listOf("all" to "Все", "warehouse" to "Склад", "showcase" to "Витрина"), scope) { scope = it; kind = null }
                        ChipRow {
                            StoreChip("Все виды", on = kind == null) { kind = null }
                            kinds.forEach { k -> StoreChip(movementLabel(k), on = kind == k) { kind = if (kind == k) null else k } }
                        }
                    }
                }
                items(shown, key = { it.id.ifEmpty { it.hashCode().toString() } }) { m -> RowCard { MovementRow(m) } }
            }
        }
    }
}

// ── Поиск по складу ──────────────────────────────────────────────────────────

/**
 * Глобальный поиск: товары, приёмки, списания, заявки. «Где эта банка» — вопрос
 * стоя у полки. Запрос уходит с задержкой, пока человек печатает.
 */
@Composable
fun StoreSearchScreen(vm: StoreViewModel) {
    var query by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<StoreSearchHit>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            hits = emptyList()
            error = null
            return@LaunchedEffect
        }
        delay(350)
        loading = true
        try {
            hits = vm.search(q, null)
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        }
        loading = false
    }

    StoreScaffold(null) {
        StoreLazy {
            item { StoreSearchField(query, { query = it }, placeholder = "Что ищем на складе", onScan = { scanning = true }) }
            if (error != null) item { FormError(error) }
            if (query.trim().length >= 2 && hits.isEmpty() && !loading && error == null) {
                item { EmptyState(Icons.Filled.Search, "Ничего не нашлось", "Попробуйте название или штрихкод.") }
            }
            items(hits, key = { it.type + "|" + it.title + "|" + it.subtitle }) { hit ->
                RowCard {
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        ItemBadge(hit.title)
                        Column(Modifier.weight(1f)) {
                            Text(hit.title, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                            if (!hit.subtitle.isNullOrBlank()) Text(hit.subtitle, color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 2)
                        }
                        Text(hit.kindLabel, color = Orda.colors.brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    if (scanning) ScanDialog(onCode = { query = it }, onDismiss = { scanning = false })
}
