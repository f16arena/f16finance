package kz.ordaops.android.feature.business.store

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.LetterBadge
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.feature.operator.shift.todayString
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// Складские документы: приёмки (supplier), оприходования (posting), списания.
// Порт ReceiptsScreen / WritoffsScreen / AddReceiptSheet / AddWriteoffSheet /
// CancelDocumentSheet. Документы создаются у машины и у полки: пока это было
// только на сайте, между «приехало» и записью проходил день, а остатки врали.

// ── Приёмки и оприходования ──────────────────────────────────────────────────

@Composable
fun ReceiptsScreen(posting: Boolean) {
    val env = LocalBusiness.current
    val vm = rememberStoreViewModel()
    val state by vm.receipts.collectAsState()
    val toast = rememberToast()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    // Предупреждение сервера после проводки: нельзя пропустить, иначе приёмку проведут дважды.
    var warning by remember { mutableStateOf<String?>(null) }
    val canCreate = env.can(if (posting) "store-postings.create" else "store-receipts.create")
    LaunchedEffect(Unit) {
        vm.loadReceipts()
        vm.loadOverview()
    }
    BackHandler(enabled = selectedId != null) { selectedId = null }

    StoreScaffold(toast.message) {
        val selected = state.data?.receipts?.firstOrNull { it.id == selectedId }
        if (selected != null) {
            ReceiptDetail(vm, selected, toast, onBack = { selectedId = null })
        } else {
            StateContainer(state, onRetry = vm::loadReceipts, emptyTitle = if (posting) "Оприходований нет" else "Приёмок нет", isEmpty = { false }) { receiptsData ->
                val list = receiptsData.receipts.filter { it.isPosting == posting }
                val total = list.filter { !it.isCancelled }.sumOf { it.totalAmount }
                StoreLazy {
                    if (!posting && list.isNotEmpty()) {
                        item {
                            StoreCard {
                                StatLine("Принято товара на", money(total))
                                StatLine("Документов", "${list.count { !it.isCancelled }}")
                                // Черновики проводятся на сайте: там же их накладные и категории.
                                if (receiptsData.draftCount > 0) StatLine("Черновиков (на сайте)", "${receiptsData.draftCount}", StoreTone.waits)
                            }
                        }
                    }
                    if (list.isEmpty()) {
                        item {
                            EmptyState(
                                Icons.Filled.Inbox,
                                if (posting) "Оприходований нет" else "Приёмок нет",
                                if (posting) "Излишки, найденные на месте, появятся здесь." else "Поставки от поставщиков появятся здесь.",
                            )
                        }
                    }
                    items(list, key = { it.id }) { r -> RowCard { ReceiptRow(r) { selectedId = r.id } } }
                    item { Box(Modifier.padding(bottom = 80.dp)) }
                }
                if (canCreate) AddFab(if (posting) "Оприходовать" else "Приёмка") { adding = true }
            }
        }
    }
    if (adding) {
        ReceiptSheet(vm, posting, onDone = { warn ->
            adding = false
            if (warn != null) warning = warn else toast.show(if (posting) "Оприходовано" else "Приёмка проведена")
        }, onDismiss = { adding = false })
    }
    warning?.let { text ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { warning = null },
            title = { Text("Проверьте расход") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { warning = null }) { Text("Понятно") } },
        )
    }
}

@Composable
private fun AddFab(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.BottomEnd) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(text) },
            containerColor = Orda.colors.brand,
            contentColor = Color.White,
        )
    }
}

@Composable
private fun ReceiptRow(r: Receipt, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickableRow(onClick).padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        val tint = if (r.isCancelled) StoreTone.neutral else StoreTone.good
        LetterBadge(r.supplierName ?: r.locationName ?: "Приёмка", tint)
        Column(Modifier.weight(1f)) {
            Text(r.supplierName ?: if (r.isPosting) "Оприходование" else "Приёмка", color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(listOfNotNull(fmtDay(r.receivedAt), r.invoiceNumber?.let { "№ $it" }, r.locationName).joinToString(" · "), color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (r.totalAmount == 0.0) "—" else money(r.totalAmount), color = if (r.isCancelled) StoreTone.neutral else Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (r.isCancelled) StatusCaption(r.statusLabel, StoreTone.loss) else if (r.status == "draft") StatusCaption(r.statusLabel, StoreTone.waits)
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))

@Composable
private fun ReceiptDetail(vm: StoreViewModel, r: Receipt, toast: ToastState, onBack: () -> Unit) {
    val env = LocalBusiness.current
    var cancelling by remember { mutableStateOf(false) }
    val canCancel = env.can("store-receipts.cancel") && !r.isCancelled && r.status != "draft"
    StoreLazy {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                TextButton(onClick = onBack) { Text("← К списку") }
                StoreCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.supplierName ?: if (r.isPosting) "Оприходование" else "Приёмка", color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        StatusCaption(r.statusLabel, if (r.isCancelled) StoreTone.loss else StoreTone.good)
                    }
                    StatLine("Дата", fmtFullDate(r.receivedAt))
                    if (!r.locationName.isNullOrBlank()) StatLine("Куда", r.locationName)
                    if (!r.invoiceNumber.isNullOrBlank()) StatLine("Накладная", "№ ${r.invoiceNumber}")
                    StatLine("Сумма", money(r.totalAmount))
                    if (!r.comment.isNullOrBlank()) Text(r.comment, color = Orda.colors.textMuted, fontSize = 14.sp)
                    if (r.isCancelled && !r.cancelReason.isNullOrBlank()) Text("Отменена: ${r.cancelReason}", color = StoreTone.loss, fontSize = 14.sp)
                }
                StoreCard { DocumentLines(r.items) }
                if (!r.invoiceUrl.isNullOrBlank()) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    TextButton(onClick = {
                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, r.invoiceUrl.toUri())) }
                    }) { Text("Открыть накладную") }
                }
                if (canCancel) TextButton(onClick = { cancelling = true }) { Text("Отменить приёмку", color = StoreTone.loss) }
            }
        }
    }
    if (cancelling) {
        CancelDocumentSheet(
            title = "Отменить приёмку",
            note = "Остатки вернутся как были, документ останется в истории. Причина обязательна: через месяц «отменено» без объяснения выглядит как воровство.",
            onConfirm = { reason -> vm.cancelReceipt(r.id, reason) },
            onDone = {
                cancelling = false
                toast.show("Приёмка отменена")
                onBack()
            },
            onDismiss = { cancelling = false },
        )
    }
}

@Composable
internal fun DocumentLines(lines: List<DocumentLine>) {
    SectionTitle("Состав", positions(lines.size))
    if (lines.isEmpty()) InlineEmpty(Icons.Filled.Inbox, "Позиции не указаны")
    lines.forEachIndexed { i, l ->
        if (i > 0) RowDivider()
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            ItemBadge(l.name, if (l.isBonus) StoreTone.good else Orda.colors.brand, 36)
            Column(Modifier.weight(1f)) {
                Text(l.name, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                val sub = buildList {
                    if (l.unitCost > 0) add("${money(l.unitCost)} × ${formatQty(l.quantity)}")
                    if (l.isBonus) add("бонус")
                    if (l.expiresSoon()) add("срок: ${fmtDay(l.expiryDate)}")
                    if (!l.comment.isNullOrBlank()) add(l.comment)
                }.joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, color = if (l.expiresSoon()) StoreTone.waits else Orda.colors.textDim, fontSize = 13.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatQtyUnit(l.quantity, l.unit), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (l.totalCost > 0) Text(money(l.totalCost), color = Orda.colors.textDim, fontSize = 12.sp)
            }
        }
    }
}

/** Отмена документа с обязательной причиной — отмена меняет остатки. */
@Composable
internal fun CancelDocumentSheet(title: String, note: String, onConfirm: suspend (String) -> String?, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    OrdaSheet(title, onDismiss) {
        Text(note, color = Orda.colors.textDim, fontSize = 14.sp)
        TextInput("Причина", reason, { reason = it }, singleLine = false)
        FormError(error)
        PrimaryButton("Отменить документ", Modifier.fillMaxWidth(), enabled = reason.isNotBlank(), loading = saving) {
            saving = true
            scope.launch {
                val e = onConfirm(reason)
                saving = false
                if (e == null) onDone() else error = e
            }
        }
    }
}

// ── Форма приёмки / оприходования ────────────────────────────────────────────

/** Ссылка на позицию в выпадающем поиске: id, название, единица. */
internal data class PickItem(val id: String, val name: String, val unit: String, val barcode: String?)

/** Позиции для поиска: весь перечень, а не остатки места — принимать можно и то, чего сейчас нет. */
internal fun pickItems(overview: StoreOverview?, locationId: String?, onlyAtLocation: Boolean): List<PickItem> {
    val balances = overview?.balances.orEmpty().filter { !onlyAtLocation || it.locationId == locationId }
    return balances.distinctBy { it.itemId }.map { PickItem(it.itemId, it.name, it.unit, it.barcode) }
}

/**
 * Форма приёмки / оприходования. С 27.10 сервер требует: файл накладной, категорию
 * расхода COGS (под неё он сам заведёт расход или долг), чек оплаты при оплате сразу
 * и «годен до» у товаров со сроком. Справочники приходят тем же ответом, что и
 * список документов. Все проверки идут до проводки — после неё остаток уже вырос.
 */
@Composable
private fun ReceiptSheet(vm: StoreViewModel, posting: Boolean, onDone: (String?) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val receiptsState by vm.receipts.collectAsState()
    val data = receiptsState.data
    val shopIds = vm.shopIds
    // Оприходовать сервер разрешает только на склад или витрину; закрытые точки не предлагаем.
    val locations = data?.locations.orEmpty()
        .filter { if (posting) it.kind == "warehouse" || it.kind == "point_display" else it.kind != "catalog" }
        .filter { shopIds == null || it.companyId == null || it.companyId in shopIds }
    val categories = data?.categories.orEmpty().filter { it.isCogs }
    var draft by remember { mutableStateOf(ReceiptDraft(receivedAt = todayString())) }
    var search by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(locations.size) {
        if (draft.locationId.isEmpty() && locations.isNotEmpty()) draft = draft.copy(locationId = locations.first().id)
    }
    // Категория по умолчанию — первая COGS, как на сайте.
    LaunchedEffect(categories.size) {
        if (draft.expenseCategoryId.isEmpty() && categories.isNotEmpty()) draft = draft.copy(expenseCategoryId = categories.first().id)
    }

    /** Прочитать выбранный файл и отправить на сервер; допустимы JPG, PNG, WEBP, PDF до 10 МБ. */
    fun upload(uri: android.net.Uri?, onUrl: (String) -> Unit) {
        if (uri == null) return
        uploading = true
        error = null
        scope.launch {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val bytes = withContext(Dispatchers.IO) { runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            when {
                bytes == null -> error = "Не удалось прочитать файл"
                bytes.size > 10 * 1024 * 1024 -> error = "Максимальный размер файла — 10 МБ"
                mime !in setOf("image/jpeg", "image/png", "image/webp", "application/pdf") -> error = "Допустимы только JPG, PNG, WEBP и PDF"
                else -> {
                    val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; "application/pdf" -> "pdf"; else -> "jpg" }
                    val (url, e) = vm.uploadReceiptFile(bytes, mime, "document.$ext")
                    if (url != null) onUrl(url) else error = e
                }
            }
            uploading = false
        }
    }
    val invoicePicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        upload(uri) { draft = draft.copy(invoiceFileUrl = it) }
    }
    val receiptPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        upload(uri) { draft = draft.copy(paymentReceiptUrl = it) }
    }
    val fileTypes = arrayOf("image/jpeg", "image/png", "image/webp", "application/pdf")

    val location = locations.firstOrNull { it.id == draft.locationId }
    val blocker = if (posting) draft.postingValidationMessage else draft.validationMessage
    val suppliers = data?.suppliers.orEmpty()

    OrdaSheet(if (posting) "Оприходование" else "Приёмка товара", onDismiss) {
        PickerField("Куда принимаем", location, locations, { "${it.name} · ${it.kindLabel}" }) { draft = draft.copy(locationId = it.id) }
        if (!posting) {
            PickerField("Поставщик", suppliers.firstOrNull { it.first == draft.supplierId }, suppliers, { it.second }) { draft = draft.copy(supplierId = it.first) }
        }
        DateInput(if (posting) "Дата" else "Дата поставки", draft.receivedAt, { draft = draft.copy(receivedAt = it) }, maxToday = true)
        if (!posting) {
            TextInput("Номер накладной", draft.invoiceNumber, { draft = draft.copy(invoiceNumber = it) })
            FormSection("Документы") {
                FileSlot("Накладная (обязательна)", draft.invoiceFileUrl, uploading) { invoicePicker.launch(fileTypes) }
                PickerField(
                    "Категория расхода (COGS)", categories.firstOrNull { it.id == draft.expenseCategoryId }, categories, { it.name },
                    placeholder = if (categories.isEmpty()) "Нет категорий COGS — заведите на сайте" else "Не выбрана",
                ) { draft = draft.copy(expenseCategoryId = it.id) }
            }
            FormSection("Оплата") {
                PillSegmentSimple(ReceiptPayment.entries.map { it to it.title }, draft.payment) {
                    draft = draft.copy(payment = it, dueDate = if (it == ReceiptPayment.Deferred && draft.dueDate == null) todayString() else draft.dueDate)
                }
                if (draft.payment == ReceiptPayment.Now) {
                    PillSegmentSimple(listOf("cash" to "Наличными", "kaspi" to "Kaspi"), draft.paymentMethod) { draft = draft.copy(paymentMethod = it) }
                    FileSlot("Чек об оплате (обязателен)", draft.paymentReceiptUrl, uploading) { receiptPicker.launch(fileTypes) }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Реализация", color = Orda.colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Switch(checked = draft.isConsignment, onCheckedChange = { draft = draft.copy(isConsignment = it) })
                    }
                    if (draft.isConsignment) {
                        Text("Платим по мере продажи — у такой поставки срока нет.", color = Orda.colors.textDim, fontSize = 13.sp)
                    } else {
                        DateInput("Оплатить до", draft.dueDate ?: todayString(), { draft = draft.copy(dueDate = it) })
                    }
                }
            }
        }
        TextInput("Комментарий", draft.comment, { draft = draft.copy(comment = it) }, singleLine = false)

        // Позиции
        if (draft.lines.isNotEmpty()) {
            SectionTitle(if (posting) "Позиции" else "В накладной", positions(draft.lines.size))
            draft.lines.forEachIndexed { index, line ->
                ReceiptLineEditor(line, posting, onChange = { new -> draft = draft.copy(lines = draft.lines.toMutableList().also { it[index] = new }) }, onDelete = {
                    draft = draft.copy(lines = draft.lines.toMutableList().also { it.removeAt(index) })
                })
                RowDivider()
            }
            if (!posting) StatLine("Итого по накладной", money(draft.total))
        }
        val catalog = data?.items.orEmpty()
        ItemPicker(
            items = catalog.filter { p -> draft.lines.none { it.itemId == p.id } }.map { PickItem(it.id, it.name, it.unit, it.barcode) },
            search = search, onSearch = { search = it }, onScan = { scanning = true },
            onPick = { p ->
                val ref = catalog.firstOrNull { it.id == p.id }
                draft = draft.copy(
                    lines = draft.lines + ReceiptLine(
                        itemId = p.id, name = p.name, unit = p.unit,
                        // Закупочная цена из каталога — подсказка, правится строкой.
                        unitCost = ref?.purchasePrice ?: 0.0,
                        requiresExpiry = ref?.requiresExpiry ?: false,
                    ),
                )
                search = ""
            },
        )
        BlockerHint(blocker)
        FormError(error)
        PrimaryButton(if (posting) "Оприходовать" else "Принять", Modifier.fillMaxWidth(), enabled = blocker == null && !uploading, loading = saving) {
            saving = true
            error = null
            scope.launch {
                val outcome = vm.createReceipt(draft, location?.companyId, posting)
                saving = false
                if (outcome.error == null) onDone(outcome.warning) else error = outcome.error
            }
        }
    }
    if (scanning) ScanDialog(onCode = { search = it }, onDismiss = { scanning = false })
}

/** Слот файла: «прикрепить» или «загружен»; сам файл на телефоне не хранится, уходит на сервер. */
@Composable
private fun FileSlot(title: String, url: String, uploading: Boolean, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(Orda.colors.surfaceRaised)
            .clickable(enabled = !uploading, onClick = onPick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Orda.colors.textDim, fontSize = 12.sp)
            Text(
                when {
                    uploading -> "Загружаем…"
                    url.isNotBlank() -> "Загружен — нажмите, чтобы заменить"
                    else -> "Прикрепить фото или PDF"
                },
                color = if (url.isNotBlank()) StoreTone.good else Orda.colors.text,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun ReceiptLineEditor(line: ReceiptLine, posting: Boolean, onChange: (ReceiptLine) -> Unit, onDelete: () -> Unit) {
    var qty by remember(line.itemId) { mutableStateOf(if (line.quantity > 0) formatQty(line.quantity) else "") }
    var cost by remember(line.itemId) { mutableStateOf(if (line.unitCost > 0) formatQty(line.unitCost) else "") }
    var sale by remember(line.itemId) { mutableStateOf(line.salePrice?.let { formatQty(it) } ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line.name, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Убрать", tint = StoreTone.loss) }
        }
        NumberInput("Количество, ${line.unit}", qty, { qty = it; onChange(line.copy(quantity = parseNumber(it))) })
        if (!posting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Бонус от поставщика", color = Orda.colors.textMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = line.isBonus, onCheckedChange = { onChange(line.copy(isBonus = it)) })
            }
        }
        // У бонуса цены нет по определению — поле только мешало бы.
        if (!line.isBonus) {
            NumberInput(if (posting) "Себестоимость (необязательно)" else "Цена закупки", cost, { cost = it; onChange(line.copy(unitCost = parseNumber(it))) }, suffix = "₸")
            NumberInput("Новая цена продажи", sale, { sale = it; onChange(line.copy(salePrice = parseNumber(it).takeIf { v -> it.isNotBlank() && v > 0 })) }, suffix = "₸")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (line.salePrice == null) "Цена продажи не изменится" else "Наценка", color = Orda.colors.textDim, fontSize = 13.sp)
                line.markup?.let { Text(BizFormat.percent(it), color = if (it > 0) StoreTone.good else StoreTone.loss, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
            if (line.total > 0) StatLine("Строка", money(line.total))
        }
        // Срок годности обязателен для товаров со сроком (бургеры/хотдоги снимают галочку в каталоге).
        if (line.requiresExpiry) {
            DateInput("Годен до", line.expiryDate.orEmpty(), { onChange(line.copy(expiryDate = it)) })
        }
    }
}

/** Поиск позиции: до двадцати совпадений, список в тысячу строк на телефоне бесполезен. */
@Composable
internal fun ItemPicker(items: List<PickItem>, search: String, onSearch: (String) -> Unit, onScan: () -> Unit, onPick: (PickItem) -> Unit, hint: String? = null) {
    SectionTitle("Добавить позицию")
    StoreSearchField(search, onSearch, onScan = onScan)
    val q = search.trim()
    val found = if (q.isEmpty()) emptyList() else items.filter { it.name.contains(q, ignoreCase = true) || (it.barcode?.contains(q) == true) }.take(20)
    if (q.isNotEmpty() && found.isEmpty()) Text("Ничего не нашлось.", color = Orda.colors.textDim, fontSize = 13.sp)
    if (q.isEmpty() && hint != null) Text(hint, color = Orda.colors.textDim, fontSize = 13.sp)
    found.forEach { p ->
        Row(Modifier.fillMaxWidth().clickableRow { onPick(p) }.padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, color = Orda.colors.text, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Add, null, tint = Orda.colors.brand)
        }
    }
}

// ── Списания ─────────────────────────────────────────────────────────────────

@Composable
fun WriteoffsScreen() {
    val env = LocalBusiness.current
    val vm = rememberStoreViewModel()
    val state by vm.writeoffs.collectAsState()
    val toast = rememberToast()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val canCreate = env.can("store-writeoffs.create")
    LaunchedEffect(Unit) {
        vm.loadWriteoffs()
        vm.loadOverview()
    }
    BackHandler(enabled = selectedId != null) { selectedId = null }

    StoreScaffold(toast.message) {
        val selected = state.data?.firstOrNull { it.id == selectedId }
        if (selected != null) {
            WriteoffDetail(vm, selected, toast, onBack = { selectedId = null })
        } else {
            StateContainer(state, onRetry = vm::loadWriteoffs, emptyTitle = "Списаний нет", emptyMessage = "Разбитое, испорченное и просроченное появится здесь.", isEmpty = { it.isEmpty() }) { list ->
                val total = list.filter { !it.isCancelled }.sumOf { it.totalAmount }
                StoreLazy {
                    item { StoreCard(accent = StoreTone.loss) { StatLine("Списано на", money(total), if (total > 0) StoreTone.loss else StoreTone.neutral); StatLine("Актов", "${list.count { !it.isCancelled }}") } }
                    items(list, key = { it.id }) { w ->
                        RowCard {
                            Row(Modifier.fillMaxWidth().clickableRow { selectedId = w.id }.padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                LetterBadge(w.reasonLabel, if (w.isCancelled) StoreTone.neutral else StoreTone.loss)
                                Column(Modifier.weight(1f)) {
                                    Text(w.reasonLabel, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(listOfNotNull(fmtDay(w.writtenAt), w.locationName, positions(w.items.size)).joinToString(" · "), color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(if (w.totalAmount == 0.0) "—" else money(w.totalAmount), color = if (w.isCancelled || w.totalAmount == 0.0) StoreTone.neutral else StoreTone.loss, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    if (w.isCancelled) StatusCaption("Отменено", StoreTone.neutral)
                                }
                            }
                        }
                    }
                    item { Box(Modifier.padding(bottom = 80.dp)) }
                }
                if (canCreate) AddFab("Списать") { adding = true }
            }
        }
    }
    if (adding) WriteoffSheet(vm, onDone = { adding = false; toast.show("Списано") }, onDismiss = { adding = false })
}

@Composable
private fun WriteoffDetail(vm: StoreViewModel, w: Writeoff, toast: ToastState, onBack: () -> Unit) {
    val env = LocalBusiness.current
    var cancelling by remember { mutableStateOf(false) }
    val canCancel = env.can("store-writeoffs.cancel") && !w.isCancelled
    StoreLazy {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                TextButton(onClick = onBack) { Text("← К списку") }
                StoreCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(w.reasonLabel, color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (w.isCancelled) StatusCaption("Отменено", StoreTone.neutral)
                    }
                    StatLine("Дата", fmtFullDate(w.writtenAt))
                    if (!w.locationName.isNullOrBlank()) StatLine("Откуда", w.locationName)
                    StatLine("Сумма", money(w.totalAmount), if (w.totalAmount > 0 && !w.isCancelled) StoreTone.loss else StoreTone.neutral)
                    if (!w.comment.isNullOrBlank()) Text(w.comment, color = Orda.colors.textMuted, fontSize = 14.sp)
                }
                StoreCard { DocumentLines(w.items) }
                if (canCancel) TextButton(onClick = { cancelling = true }) { Text("Отменить списание", color = StoreTone.loss) }
            }
        }
    }
    if (cancelling) {
        CancelDocumentSheet(
            title = "Отменить списание",
            note = "Товар вернётся на остаток, пока расхождение свежее. Документ останется в истории.",
            onConfirm = { vm.cancelWriteoff(w.id, it) },
            onDone = {
                cancelling = false
                toast.show("Списание отменено")
                onBack()
            },
            onDismiss = { cancelling = false },
        )
    }
}

/**
 * Акт списания, построенный вокруг поиска: человек стоит с телефоном и осколком
 * в руках, ему нужно найти позицию и вписать количество, а не заполнять шапку.
 */
@Composable
private fun WriteoffSheet(vm: StoreViewModel, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val overview by vm.overview.collectAsState()
    val ov = overview.data
    val locations = ov?.locations.orEmpty().filter { it.kind != "catalog" }
    var draft by remember { mutableStateOf(WriteoffDraft(writtenAt = todayString())) }
    var search by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(locations.size) {
        if (draft.locationId.isEmpty() && locations.isNotEmpty()) draft = draft.copy(locationId = locations.first().id)
    }
    val location = locations.firstOrNull { it.id == draft.locationId }
    val atLocation = ov?.balances.orEmpty().filter { it.locationId == draft.locationId }

    OrdaSheet("Списание", onDismiss) {
        PickerField("Откуда списываем", location, locations, { "${it.name} · ${it.kindLabel}" }) { draft = draft.copy(locationId = it.id, lines = emptyList()) }
        DateInput("Дата", draft.writtenAt, { draft = draft.copy(writtenAt = it) }, maxToday = true)
        TextInput("Причина", draft.reason, { draft = draft.copy(reason = it) })
        TextInput("Комментарий", draft.comment, { draft = draft.copy(comment = it) }, singleLine = false)
        if (draft.lines.isNotEmpty()) {
            SectionTitle("В акте", positions(draft.lines.size))
            draft.lines.forEachIndexed { index, line ->
                var qty by remember(line.itemId) { mutableStateOf(if (line.quantity > 0) formatQty(line.quantity) else "") }
                val available = atLocation.firstOrNull { it.itemId == line.itemId }?.quantity
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(line.name, color = Orda.colors.text, fontSize = 15.sp, maxLines = 2, modifier = Modifier.weight(1f))
                        IconButton(onClick = { draft = draft.copy(lines = draft.lines.toMutableList().also { it.removeAt(index) }) }) { Icon(Icons.Filled.Delete, "Убрать", tint = StoreTone.loss) }
                    }
                    NumberInput("Количество", qty, { qty = it; draft = draft.copy(lines = draft.lines.toMutableList().also { l -> l[index] = line.copy(quantity = parseNumber(it)) }) })
                    if (available != null) Text("на месте: ${formatQty(available)}", color = if (line.quantity > available) StoreTone.waits else Orda.colors.textDim, fontSize = 13.sp)
                }
                RowDivider()
            }
        }
        ItemPicker(
            items = atLocation.filter { b -> draft.lines.none { it.itemId == b.itemId } }.map { PickItem(it.itemId, it.name, it.unit, it.barcode) },
            search = search, onSearch = { search = it }, onScan = { scanning = true },
            onPick = { p ->
                draft = draft.copy(lines = draft.lines + WriteoffLine(p.id, p.name))
                search = ""
            },
            hint = if (draft.locationId.isEmpty()) "Сначала выберите, откуда списываем." else null,
        )
        BlockerHint(draft.validationMessage)
        FormError(error)
        PrimaryButton("Списать", Modifier.fillMaxWidth(), enabled = draft.validationMessage == null, loading = saving) {
            saving = true
            scope.launch {
                val e = vm.createWriteoff(draft, location?.companyId)
                saving = false
                if (e == null) onDone() else error = e
            }
        }
    }
    if (scanning) ScanDialog(onCode = { search = it }, onDismiss = { scanning = false })
}

/** Два-три варианта в строку. */
@Composable
internal fun <T> PillSegmentSimple(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    kz.ordaops.android.feature.business.PillSegment(options, selected, Modifier.fillMaxWidth(), onSelect)
}

@Composable
internal fun FormSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, color = Orda.colors.textDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}
