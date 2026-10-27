package kz.ordaops.android.feature.business.store

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
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
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// Каталог товаров: номенклатура с ценой, штрихкодом и категорией. Порт
// CatalogScreen / CatalogEditScreens. Правка на сервере — полная замена полей
// карточки, поэтому форма стартует с того, что уже записано (CatalogItemDraft.of).

@Composable
fun CatalogScreen() {
    val env = LocalBusiness.current
    val vm = rememberStoreViewModel()
    val state by vm.catalog.collectAsState()
    val overview by vm.overview.collectAsState()
    val toast = rememberToast()
    var search by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(CatalogFilter.All) }
    var editing by remember { mutableStateOf<CatalogItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    val canCreate = env.can("store-catalog.create")
    LaunchedEffect(Unit) {
        vm.loadCatalog()
        vm.loadOverview()
    }
    BackHandler(enabled = selectedId != null) { selectedId = null }

    val companies = overview.data?.locations?.mapNotNull { l -> l.companyId?.let { StoreCompany(it, l.companyName ?: "Точка") } }?.distinctBy { it.id }.orEmpty()

    StoreScaffold(toast.message) {
        val selected = state.data?.firstOrNull { it.id == selectedId }
        if (selected != null) {
            CatalogDetail(vm, selected, toast, onEdit = { editing = selected }, onBack = { selectedId = null })
        } else {
            StateContainer(state, onRetry = { vm.loadCatalog() }, emptyTitle = "Каталог пуст", emptyMessage = "Товары появятся после заведения.", isEmpty = { it.isEmpty() }) { items ->
                val q = search.trim()
                val shown = items.filter { item ->
                    (q.isEmpty() || item.name.contains(q, ignoreCase = true) || (item.barcode?.contains(q) == true)) && when (filter) {
                        CatalogFilter.All -> item.isActive
                        CatalogFilter.OutOfStock -> item.isActive && item.isOutOfStock
                        CatalogFilter.Archive -> !item.isActive
                    }
                }
                StoreLazy {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            StoreSearchField(search, { search = it }, onScan = { scanning = true })
                            ChipRow {
                                CatalogFilter.entries.forEach { f ->
                                    StoreChip(f.title, on = filter == f, tint = if (f == CatalogFilter.OutOfStock) StoreTone.loss else Orda.colors.brand) { filter = f }
                                }
                            }
                        }
                    }
                    if (shown.isEmpty()) item { EmptyState(Icons.Filled.Inventory2, "Ничего не найдено", "Попробуйте другой запрос или фильтр.") }
                    items(shown, key = { it.id }) { it2 ->
                        RowCard {
                            StockRow(
                                name = it2.name,
                                quantity = it2.catalogQty,
                                unit = it2.unit ?: "шт",
                                threshold = it2.lowStockThreshold,
                                caption = listOfNotNull(if (it2.isService) "услуга" else null, it2.categoryName, it2.salePrice?.let { p -> money(p) }).joinToString(" · ").ifEmpty { null },
                                onClick = { selectedId = it2.id },
                            )
                        }
                    }
                    item { Box(Modifier.padding(bottom = 80.dp)) }
                }
                if (canCreate && companies.isNotEmpty()) {
                    Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.BottomEnd) {
                        ExtendedFloatingActionButton(
                            onClick = { creating = true },
                            icon = { Icon(Icons.Filled.Add, null) },
                            text = { Text("Товар") },
                            containerColor = Orda.colors.brand,
                            contentColor = Color.White,
                        )
                    }
                }
            }
        }
    }
    if (scanning) ScanDialog(onCode = { search = it }, onDismiss = { scanning = false })
    if (creating) {
        CatalogEditSheet(vm, item = null, companies = companies, onDone = {
            creating = false
            toast.show("Товар заведён")
        }, onDismiss = { creating = false })
    }
    editing?.let { item ->
        CatalogEditSheet(vm, item = item, companies = companies, onDone = {
            editing = null
            toast.show("Сохранено")
        }, onDismiss = { editing = null })
    }
}

private enum class CatalogFilter(val title: String) { All("Все"), OutOfStock("Нет в наличии"), Archive("Архив") }

@Composable
private fun CatalogDetail(vm: StoreViewModel, item: CatalogItem, toast: ToastState, onEdit: () -> Unit, onBack: () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val canEdit = env.can("store-catalog.edit") && !item.isService
    val canDelete = env.can("store-catalog.delete")
    StoreLazy {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                TextButton(onClick = onBack) { Text("← К каталогу") }
                FormError(error)
                StoreCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        ItemBadge(item.name, size = 48)
                        Column(Modifier.weight(1f)) {
                            Text(item.name, color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(listOfNotNull(item.categoryName, if (item.isConsumable) "расходник" else null).joinToString(" · ").ifEmpty { "Без категории" }, color = Orda.colors.textDim, fontSize = 13.sp)
                        }
                    }
                    if (!item.isActive) Text("В архиве", color = StoreTone.waits, fontWeight = FontWeight.SemiBold)
                    if (!item.barcode.isNullOrBlank()) StatLine("Штрихкод", item.barcode)
                    StatLine("Цена продажи", item.salePrice?.let { money(it) } ?: "—")
                    StatLine("Цена закупки", item.purchasePrice?.let { money(it) } ?: "—")
                    markupPercent(item.purchasePrice ?: 0.0, item.salePrice)?.let {
                        StatLine("Наценка", kz.ordaops.android.feature.business.BizFormat.percent(it), if (it > 0) StoreTone.good else StoreTone.loss)
                    }
                    if (item.lowStockThreshold != null) StatLine("Порог остатка", formatQtyUnit(item.lowStockThreshold, item.unit))
                    if (item.requiresExpiry) StatLine("Срок годности", "учитывается")
                    if (!item.notes.isNullOrBlank()) Text(item.notes, color = Orda.colors.textMuted, fontSize = 14.sp)
                }
                // Услуга — позиция без остатка: цены, варианты и время правятся на сайте (Склад → Услуги).
                if (item.isService) {
                    StoreCard { Text("Услуга: остатка нет. Варианты цены и время настраиваются на сайте, в разделе «Услуги».", color = Orda.colors.textMuted, fontSize = 14.sp) }
                } else StoreCard {
                    SectionTitle("Остатки")
                    // Нули приглушаем: ноль — не событие.
                    StatLine("Всего", formatQtyUnit(item.catalogQty, item.unit), if (item.catalogQty <= 0) StoreTone.neutral else Orda.colors.text)
                    StatLine("Склад", formatQtyUnit(item.warehouseQty, item.unit), if (item.warehouseQty <= 0) StoreTone.neutral else StoreTone.backroom)
                    StatLine("Витрина", formatQtyUnit(item.showcaseQty, item.unit), if (item.showcaseQty <= 0) StoreTone.neutral else Orda.colors.text)
                }
                if (canEdit && item.isActive) PrimaryButton("Изменить", Modifier.fillMaxWidth()) { onEdit() }
                if (canDelete && item.isActive) TextButton(onClick = { confirmDelete = true }) { Text("Удалить товар", color = StoreTone.loss) }
                if (canDelete && !item.isActive) {
                    PrimaryButton("Вернуть из архива", Modifier.fillMaxWidth()) {
                        scope.launch {
                            val e = vm.restoreCatalogItem(item.id)
                            if (e == null) {
                                toast.show("Товар возвращён")
                                onBack()
                            } else {
                                error = e
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить «${item.name}»?") },
            text = { Text("Товар с историей движений уйдёт в архив, а с остатком на складе удалить не получится.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val e = vm.deleteCatalogItem(item.id)
                        if (e == null) {
                            toast.show("Товар удалён")
                            onBack()
                        } else {
                            error = e
                        }
                    }
                }) { Text("Удалить", color = StoreTone.loss) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

/** Карточка товара: новая или правка. Расходник отличается только видом — он списывается, а не продаётся. */
@Composable
private fun CatalogEditSheet(vm: StoreViewModel, item: CatalogItem?, companies: List<StoreCompany>, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(item?.let { CatalogItemDraft.of(it) } ?: CatalogItemDraft()) }
    var sale by remember { mutableStateOf(if (draft.salePrice > 0) formatQty(draft.salePrice) else "") }
    var purchase by remember { mutableStateOf(if (draft.purchasePrice > 0) formatQty(draft.purchasePrice) else "") }
    var threshold by remember { mutableStateOf(draft.lowStockThreshold?.let { formatQty(it) } ?: "") }
    var company by remember { mutableStateOf(companies.firstOrNull { it.id == item?.companyId } ?: companies.firstOrNull()) }
    var categories by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(company?.id) { categories = runCatching { vm.categories(company?.id) }.getOrDefault(emptyList()) }

    val current = draft.copy(salePrice = parseNumber(sale), purchasePrice = parseNumber(purchase), lowStockThreshold = threshold.takeIf { it.isNotBlank() }?.let { parseNumber(it) })
    val problem = current.problem ?: if (item == null && company == null) "Не определена точка" else null

    OrdaSheet(if (item == null) "Новый товар" else "Изменить товар", onDismiss) {
        if (item == null && companies.size > 1) PickerField("Точка", company, companies, { it.name }) { company = it }
        TextInput("Название", draft.name, { draft = draft.copy(name = it) })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(Modifier.weight(1f)) { TextInput("Штрихкод", draft.barcode, { draft = draft.copy(barcode = it) }, keyboard = androidx.compose.ui.text.input.KeyboardType.Number) }
            TextButton(onClick = { scanning = true }) { Text("Скан") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(Modifier.weight(1f)) { NumberInput("Цена продажи", sale, { sale = it }, suffix = "₸") }
            Box(Modifier.weight(1f)) { NumberInput("Цена закупки", purchase, { purchase = it }, suffix = "₸") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(Modifier.weight(1f)) { TextInput("Единица", draft.unit, { draft = draft.copy(unit = it) }) }
            Box(Modifier.weight(1f)) { NumberInput("Порог остатка", threshold, { threshold = it }) }
        }
        if (categories.isNotEmpty()) {
            PickerField("Категория", categories.firstOrNull { it.first == draft.categoryId }, listOf<Pair<String, String>?>(null) + categories, { it?.second ?: "Без категории" }) { draft = draft.copy(categoryId = it?.first) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Расходник (списывается, не продаётся)", color = Orda.colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = draft.itemType == "consumable", onCheckedChange = { draft = draft.copy(itemType = if (it) "consumable" else "product") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Есть срок годности", color = Orda.colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = draft.requiresExpiry, onCheckedChange = { draft = draft.copy(requiresExpiry = it) })
        }
        TextInput("Фото (ссылка)", draft.imageUrl, { draft = draft.copy(imageUrl = it) }, keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
        TextInput("Заметка", draft.notes, { draft = draft.copy(notes = it) }, singleLine = false)
        BlockerHint(problem)
        FormError(error)
        PrimaryButton(if (item == null) "Завести" else "Сохранить", Modifier.fillMaxWidth(), enabled = problem == null, loading = saving) {
            saving = true
            scope.launch {
                val e = if (item == null) vm.createCatalogItem(current, company!!.id) else vm.updateCatalogItem(item.id, current, item.imageUrl)
                saving = false
                if (e == null) onDone() else error = e
            }
        }
    }
    if (scanning) ScanDialog(onCode = { draft = draft.copy(barcode = it) }, onDismiss = { scanning = false })
}
