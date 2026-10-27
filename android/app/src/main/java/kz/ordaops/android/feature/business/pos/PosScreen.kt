package kz.ordaops.android.feature.business.pos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.business.store.StoreChip
import kz.ordaops.android.feature.business.store.fmtHourMinute
import kz.ordaops.android.feature.operator.sale.PrintStatus
import kz.ordaops.android.feature.operator.sale.PrintState
import kz.ordaops.android.feature.operator.sale.PrintSettingsSheet
import kz.ordaops.android.feature.operator.sale.ReceiptData
import kz.ordaops.android.feature.operator.sale.ReceiptPrinter
import kz.ordaops.android.feature.operator.sale.ScannerPane
import kz.ordaops.android.feature.operator.sale.quantityText
import kz.ordaops.android.feature.operator.sale.rememberSaleSettings
import kz.ordaops.android.feature.operator.shift.AmountField
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.feature.operator.shift.SaleLine
import kz.ordaops.android.feature.operator.shift.ordaFieldColors
import kz.ordaops.android.feature.operator.shift.parseAmount
import kz.ordaops.android.feature.operator.shift.pluralize
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.LoadingView
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.time.LocalTime

/**
 * Касса (`/pos`) — веб-касса сотрудника: точка, смена «от себя», витрина, чек, оплата со сдачей,
 * клиент и баллы, скидка, чек на почту и печать. Те же экраны оплаты и печати, что у кассы
 * оператора, но запросы идут по сессии сотрудника в раздел `/api/admin/pos`.
 */
@Composable
fun PosScreen() {
    val vm = rememberPosViewModel()
    LaunchedEffect(Unit) {
        // Первый вход — точки; возврат на экран — свежая смена (её могли закрыть с другого устройства).
        if (vm.companies.isEmpty()) vm.loadCompanies() else vm.loadShift()
    }
    Box(Modifier.fillMaxSize().background(Orda.colors.background)) {
        when {
            vm.booting && vm.companies.isEmpty() -> LoadingView()
            vm.bootError != null && vm.companies.isEmpty() ->
                EmptyState(Icons.Filled.Storefront, "Не удалось открыть кассу", vm.bootError, "Повторить") { vm.loadCompanies() }
            vm.companies.isEmpty() ->
                EmptyState(Icons.Filled.Storefront, "Нет доступных точек", "Добавьте точку на сайте, чтобы работать с кассой.")
            vm.companyId == null -> CompanyChooser(vm)
            vm.shiftLoading && !vm.hasOpenShift -> LoadingView()
            !vm.hasOpenShift -> OpenShiftView(vm)
            else -> SaleView(vm)
        }
    }
}

// ── Точка и смена ────────────────────────────────────────────────────────────

@Composable
private fun CompanyChooser(vm: PosViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text("Выберите точку", color = Orda.colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Касса сотрудника. Выберите точку, чтобы войти в смену.", color = Orda.colors.textDim, fontSize = 14.sp)
        vm.companies.forEach { company ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Orda.colors.surface, RoundedCornerShape(Radius.lg))
                    .pressScale()
                    .clickable { vm.selectCompany(company.id) }
                    .padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Icon(Icons.Filled.Storefront, null, tint = Orda.colors.brand)
                Text(company.name, color = Orda.colors.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Смена не открыта: продавать нельзя, пока не указан старт кассы (даже ноль). */
@Composable
private fun OpenShiftView(vm: PosViewModel) {
    var cash by rememberSaveable { mutableStateOf("") }
    var night by rememberSaveable { mutableStateOf(LocalTime.now().hour !in 8..19) }
    var error by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.LockOpen, null, tint = Orda.colors.brand, modifier = Modifier.size(56.dp).padding(top = Spacing.lg))
        Text("Открытие смены", color = Orda.colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Продавать можно только при открытой смене. Укажите старт кассы.",
            color = Orda.colors.textDim,
            fontSize = 14.sp,
        )
        if (vm.companies.size > 1) CompanyMenu(vm)
        PillSegment(listOf(false to "День", true to "Ночь"), night) { night = it }
        AmountField("Старт кассы", cash, { cash = it })
        error?.let { ErrorBanner(it) }
        PrimaryButton("Открыть смену", Modifier.fillMaxWidth(), loading = opening) {
            if (cash.isBlank()) {
                error = "Укажите старт кассы. Если мелочи нет — 0."
                return@PrimaryButton
            }
            opening = true
            error = null
            scope.launch {
                val failure = vm.openShift(parseAmount(cash), night)
                opening = false
                if (failure != null) {
                    error = failure
                    haptics.error()
                } else haptics.success()
            }
        }
    }
}

/** Выбор точки выпадающим списком; пока в чеке что-то лежит, точку менять нельзя. */
@Composable
private fun CompanyMenu(vm: PosViewModel, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .background(Orda.colors.surfaceRaised, RoundedCornerShape(Radius.md))
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(vm.companyName, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (enabled) Icon(Icons.Filled.ExpandMore, null, tint = Orda.colors.textDim, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            vm.companies.forEach { company ->
                DropdownMenuItem(
                    text = { Text(company.name) },
                    onClick = {
                        open = false
                        vm.selectCompany(company.id)
                    },
                )
            }
        }
    }
}

// ── Продажа ──────────────────────────────────────────────────────────────────

@Composable
private fun SaleView(vm: PosViewModel) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val context = LocalContext.current
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var showScanner by rememberSaveable { mutableStateOf(false) }
    var showCart by rememberSaveable { mutableStateOf(false) }
    var showCheckout by rememberSaveable { mutableStateOf(false) }
    var showUniversal by rememberSaveable { mutableStateOf(false) }
    var showClose by rememberSaveable { mutableStateOf(false) }
    var showPrintSettings by rememberSaveable { mutableStateOf(false) }
    var note by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val settings = rememberSaleSettings()
    val printer = remember { ReceiptPrinter(context) }
    val printState = remember { PrintState() }

    fun printReceipt(sale: PosReceipt) {
        scope.launch {
            printState.status = PrintStatus.Printing
            printState.error = null
            val failure = printer.print(
                ReceiptData(
                    companyName = sale.companyName,
                    soldAtMillis = sale.soldAtMillis,
                    lines = sale.lines.map { SaleLine(it.itemId.orEmpty(), it.name, it.quantity, it.unitPrice) },
                    total = sale.total,
                    method = sale.method,
                    cashAmount = sale.cash,
                    kaspiAmount = sale.kaspi,
                    cashReceived = sale.cashReceived,
                    change = sale.change,
                    discount = sale.totalDiscount,
                ),
                settings,
            )
            printState.status = if (failure == null) PrintStatus.Done else PrintStatus.Failed
            printState.error = failure
            if (failure != null) haptics.error()
        }
    }
    // Новая продажа: сбросить прошлый статус печати и, если включено, печатать сразу.
    LaunchedEffect(vm.lastSale?.id) {
        printState.status = PrintStatus.Idle
        printState.error = null
        val sale = vm.lastSale ?: return@LaunchedEffect
        if (settings.autoPrint && settings.canPrint) printReceipt(sale)
    }
    LaunchedEffect(note) {
        val current = note ?: return@LaunchedEffect
        delay(if (current.second) 2500 else 1400)
        if (note == current) note = null
    }

    fun scan(code: String): Boolean {
        val item = vm.itemByBarcode(code)
        return when {
            item == null -> {
                haptics.error()
                note = "Штрихкод $code не найден на витрине" to true
                false
            }
            !item.isInStock -> {
                haptics.error()
                note = "«${item.name}» — нет на витрине" to true
                false
            }
            else -> {
                vm.add(item)
                haptics.success()
                note = "${item.name} · ${money(item.salePrice)}" to false
                true
            }
        }
    }

    val catalog = vm.catalog
    val categories = remember(catalog?.items) { PosMath.categories(catalog?.items.orEmpty()) }
    val items = remember(catalog?.items, category, search) { PosMath.visible(catalog?.items.orEmpty(), category, search) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Шапка: точка, смена, обновление, закрытие.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Column(Modifier.weight(1f)) {
                    if (vm.companies.size > 1) CompanyMenu(vm, enabled = vm.cart.isEmpty())
                    else Text(vm.companyName, color = Orda.colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val opened = fmtHourMinute(vm.shift?.openedAtMillis)
                    Text(
                        "Касса сотрудника · смена ${if (vm.isNight) "ночь" else "день"}" + if (opened.isNotEmpty()) " · с $opened" else "",
                        color = Orda.colors.textDim,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                IconButton(onClick = { showPrintSettings = true }) { Icon(Icons.Filled.Print, "Принтер", tint = Orda.colors.textDim) }
                IconButton(onClick = { vm.loadCatalog() }) { Icon(Icons.Filled.Refresh, "Обновить витрину", tint = Orda.colors.textDim) }
                TextButton(onClick = { showClose = true }) { Text("Закрыть смену", color = Orda.colors.warning, fontWeight = FontWeight.SemiBold) }
            }

            // Поиск по названию и штрихкоду, сканер камерой и универсальная позиция.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Название или штрихкод") },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = Orda.colors.textDim) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // Как Enter в вебе: точный штрихкод кладёт товар в чек, единственный найденный — тоже.
                    keyboardActions = KeyboardActions(onSearch = {
                        val code = search.trim()
                        val byCode = vm.catalog?.items?.firstOrNull { it.barcode == code && it.isInStock }
                        when {
                            code.isEmpty() -> Unit
                            byCode != null -> { vm.add(byCode); search = "" }
                            items.size == 1 && items[0].isInStock -> { vm.add(items[0]); search = "" }
                        }
                    }),
                    shape = RoundedCornerShape(Radius.md),
                    colors = ordaFieldColors(),
                )
                IconButton(onClick = { showScanner = true }) { Icon(Icons.Filled.QrCodeScanner, "Сканер", tint = Orda.colors.brand) }
                IconButton(onClick = { showUniversal = true }) { Icon(Icons.Filled.Add, "Универсальная позиция", tint = Orda.colors.brand) }
            }

            if (categories.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    StoreChip("Все", category == null) { category = null }
                    categories.forEach { name -> StoreChip(name, category == name) { category = if (category == name) null else name } }
                }
            }

            vm.catalogError?.let { ErrorBanner(it, Modifier.padding(horizontal = Spacing.lg)) }
            note?.let { (text, isError) ->
                Text(
                    text,
                    color = if (isError) Orda.colors.negative else Orda.colors.positive,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }

            when {
                catalog == null && vm.catalogLoading -> LoadingView()
                items.isEmpty() -> EmptyState(
                    Icons.Filled.Search,
                    if (catalog?.items.isNullOrEmpty()) "На витрине пусто" else "Ничего не нашлось",
                    if (catalog?.items.isNullOrEmpty()) "Товары появятся после оприходования на витрину." else "Проверьте название или штрихкод.",
                )
                else -> LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.xs, bottom = if (vm.cart.isEmpty()) Spacing.lg else 96.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(items, key = { it.id }) { item ->
                        val inCart = vm.cart.firstOrNull { it.itemId == item.id }?.quantity ?: 0.0
                        PosItemRow(
                            item = item,
                            inCart = inCart,
                            onAdd = { vm.add(item); haptics.tick() },
                            onRemove = { vm.cart.firstOrNull { it.itemId == item.id }?.let { l -> vm.setQuantity(l.id, l.quantity - 1) } },
                        )
                    }
                }
            }
        }

        // Плашка чека над нижним краем.
        AnimatedVisibility(
            visible = vm.cart.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Row(
                Modifier
                    .navigationBarsPadding()
                    .padding(Spacing.lg)
                    .fillMaxWidth()
                    .background(Orda.colors.brand, RoundedCornerShape(Radius.lg))
                    .clickable { showCart = true }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "В чеке ${vm.cartCount} ${pluralize(vm.cartCount, "позиция", "позиции", "позиций")}",
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                    )
                    Text(money(vm.cartTotal), color = androidx.compose.ui.graphics.Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "К оплате",
                    color = Orda.colors.brand,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(Radius.md))
                        .clickable { showCheckout = true }
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
            }
        }
    }

    if (showScanner) {
        OrdaSheet("Сканер", { showScanner = false }) {
            ScannerPane(onCode = { code -> scan(code) })
            note?.let { (text, isError) ->
                Text(text, color = if (isError) Orda.colors.negative else Orda.colors.positive, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "В чеке ${vm.cartCount} ${pluralize(vm.cartCount, "позиция", "позиции", "позиций")} · ${money(vm.cartTotal)}",
                color = Orda.colors.textMuted,
                fontSize = 14.sp,
            )
            PrimaryButton("Готово", Modifier.fillMaxWidth()) { showScanner = false }
        }
    }
    if (showCart) {
        PosCartSheet(vm, onDismiss = { showCart = false }, onCheckout = { showCart = false; showCheckout = true })
    }
    if (showCheckout && vm.cart.isNotEmpty()) {
        PosCheckoutSheet(vm, onDismiss = { showCheckout = false })
    }
    if (showUniversal) PosUniversalSheet(vm) { showUniversal = false }
    if (showClose) PosCloseShiftSheet(vm) { showClose = false }
    if (showPrintSettings) PrintSettingsSheet(settings, printer, vm.companyName) { showPrintSettings = false }
    vm.lastSale?.let { sale ->
        PosReceiptSheet(vm, sale, settings.canPrint, printState, onPrint = { printReceipt(sale) }) { vm.dismissLastSale() }
    }
}

/** Строка товара: миниатюра, цена, остаток, «+» и счётчик в чеке. */
@Composable
private fun PosItemRow(item: PosItem, inCart: Double, onAdd: () -> Unit, onRemove: () -> Unit) {
    val dim = !item.isInStock
    Row(
        Modifier
            .fillMaxWidth()
            .background(Orda.colors.surface, RoundedCornerShape(Radius.lg))
            .clickable(enabled = !dim, onClick = onAdd)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Thumbnail(item.imageUrl, item.name)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.name,
                color = if (dim) Orda.colors.textDim else Orda.colors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (dim) "Нет на витрине" else "На витрине: ${quantityText(item.displayQty)} ${item.unit.orEmpty()}".trim(),
                color = if (dim) Orda.colors.negative else Orda.colors.textDim,
                fontSize = 13.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(money(item.salePrice), color = if (dim) Orda.colors.textDim else Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (inCart > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Remove, "Меньше", tint = Orda.colors.text, modifier = Modifier.size(28.dp).clickable(onClick = onRemove).padding(4.dp))
                    Text(quantityText(inCart), color = Orda.colors.brand, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Icon(Icons.Filled.Add, "Больше", tint = Orda.colors.text, modifier = Modifier.size(28.dp).clickable(enabled = !dim, onClick = onAdd).padding(4.dp))
                }
            }
        }
    }
}

/** Миниатюра товара; нет картинки или не загрузилась — буква названия. */
@Composable
private fun Thumbnail(url: String?, name: String) {
    val shape = RoundedCornerShape(10.dp)
    val context = LocalContext.current
    val letter: @Composable () -> Unit = {
        Text(name.take(1).uppercase(), color = Orda.colors.textMuted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
    Box(
        Modifier.size(48.dp).clip(shape).background(Orda.colors.surfaceRaised, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            letter()
        } else {
            val request = remember(url) { ImageRequest.Builder(context).data(url).crossfade(true).size(200).build() }
            SubcomposeAsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { letter() },
                error = { letter() },
            )
        }
    }
}

// ── Чек ──────────────────────────────────────────────────────────────────────

@Composable
private fun PosCartSheet(vm: PosViewModel, onDismiss: () -> Unit, onCheckout: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    OrdaSheet("Чек", onDismiss) {
        if (vm.cart.isEmpty()) {
            Text("Чек пуст.", color = Orda.colors.textDim)
        }
        vm.cart.forEach { line ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Column(Modifier.weight(1f)) {
                    Text(line.name, color = Orda.colors.text, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${money(line.unitPrice)} × ${quantityText(line.quantity)}", color = Orda.colors.textDim, fontSize = 13.sp)
                }
                Icon(Icons.Filled.Remove, "Меньше", tint = Orda.colors.text, modifier = Modifier.size(36.dp).clickable { vm.setQuantity(line.id, line.quantity - 1) }.padding(6.dp))
                Text(quantityText(line.quantity), color = Orda.colors.text, fontWeight = FontWeight.Bold)
                Icon(Icons.Filled.Add, "Больше", tint = Orda.colors.text, modifier = Modifier.size(36.dp).clickable { vm.setQuantity(line.id, line.quantity + 1) }.padding(6.dp))
                Text(money(line.total), color = Orda.colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = Spacing.xs))
                Icon(Icons.Filled.Delete, "Убрать", tint = Orda.colors.negative, modifier = Modifier.size(32.dp).clickable { vm.remove(line.id) }.padding(4.dp))
            }
        }
        if (vm.cart.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Итого", color = Orda.colors.textMuted, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(money(vm.cartTotal), color = Orda.colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            PrimaryButton("К оплате", Modifier.fillMaxWidth(), onClick = onCheckout)
            if (confirmClear) {
                TextButton(onClick = { vm.clearCart(); onDismiss() }) { Text("Убрать все позиции", color = Orda.colors.negative) }
            } else {
                TextButton(onClick = { confirmClear = true }) { Text("Очистить чек", color = Orda.colors.textDim) }
            }
        }
    }
}

/** «Универсальная позиция»: название и цена от руки (товара нет в каталоге, остаток не списывается). */
@Composable
private fun PosUniversalSheet(vm: PosViewModel, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    OrdaSheet("Универсальная позиция", onDismiss) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Название") },
            singleLine = true,
            shape = RoundedCornerShape(Radius.md),
            colors = ordaFieldColors(),
        )
        AmountField("Цена", price, { price = it })
        PrimaryButton("В чек", Modifier.fillMaxWidth(), enabled = name.isNotBlank() && parseAmount(price) > 0) {
            if (vm.addUniversal(name, parseAmount(price))) onDismiss()
        }
    }
}

/** Закрытие смены: пересчитанные наличные и безнал за смену. */
@Composable
private fun PosCloseShiftSheet(vm: PosViewModel, onDismiss: () -> Unit) {
    var cash by rememberSaveable { mutableStateOf("") }
    var kaspi by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    OrdaSheet("Закрытие смены", onDismiss) {
        AmountField("Наличные в кассе", cash, { cash = it })
        AmountField("Безналичный за смену", kaspi, { kaspi = it })
        error?.let { ErrorBanner(it) }
        PrimaryButton("Закрыть смену", Modifier.fillMaxWidth(), loading = closing) {
            closing = true
            error = null
            scope.launch {
                val failure = vm.closeShift(parseAmount(cash), parseAmount(kaspi))
                closing = false
                if (failure != null) {
                    error = failure
                    haptics.error()
                } else {
                    haptics.success()
                    onDismiss()
                }
            }
        }
    }
}
