package kz.ordaops.android.feature.business.store

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Storefront
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kz.ordaops.android.feature.business.LetterBadge
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

// Закупки: оплата поставщикам, заявки поставщикам, план закупа. Порт по текущему
// вебу (`app/(main)/store/{billing,purchase-orders,purchase-plan}`).

/** Слот файла с загрузкой на сервер: чек оплаты. Принимает JPG, PNG, WEBP, PDF до 10 МБ. */
@Composable
internal fun UploadSlot(title: String, url: String, onUrl: (String) -> Unit, onError: (String) -> Unit) {
    val vm = rememberStoreViewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true
        scope.launch {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val bytes = withContext(Dispatchers.IO) { runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            when {
                bytes == null -> onError("Не удалось прочитать файл")
                bytes.size > 10 * 1024 * 1024 -> onError("Максимальный размер файла — 10 МБ")
                mime !in setOf("image/jpeg", "image/png", "image/webp", "application/pdf") -> onError("Допустимы только JPG, PNG, WEBP и PDF")
                else -> {
                    val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; "application/pdf" -> "pdf"; else -> "jpg" }
                    val (link, e) = vm.uploadReceiptFile(bytes, mime, "document.$ext")
                    if (link != null) onUrl(link) else onError(e ?: "Не удалось загрузить файл")
                }
            }
            uploading = false
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Orda.colors.surfaceRaised)
            .clickable(enabled = !uploading) { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "application/pdf")) }
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
    ) {
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

// ── Оплата поставщикам ───────────────────────────────────────────────────────

/**
 * Долги поставщикам: свод «к оплате / просрочено», список с просроченными первыми,
 * оплата (чек обязателен — расход заводит сервер) и перенос срока. Списание и
 * массовая оплата — на сайте.
 */
@Composable
fun BillingScreen() {
    val env = LocalBusiness.current
    val vm = rememberStoreExtraViewModel()
    val state by vm.debts.collectAsState()
    val toast = rememberToast()
    var status by remember { mutableStateOf("open") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var paying by remember { mutableStateOf<SupplierDebtRow?>(null) }
    var moving by remember { mutableStateOf<SupplierDebtRow?>(null) }
    val context = LocalContext.current
    LaunchedEffect(status) { vm.loadDebts(status) }

    StoreScaffold(toast.message) {
        StateContainer(state, onRetry = { vm.loadDebts(status) }, emptyTitle = "Долгов нет", emptyMessage = "Неоплаченные накладные поставщиков появятся здесь.", isEmpty = { false }) { data ->
            val list = data.debts.sortedWith(compareByDescending<SupplierDebtRow> { it.isOverdue }.thenBy { it.dueDate ?: Long.MAX_VALUE })
            StoreLazy {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        PillSegment(listOf("open" to "К оплате", "paid" to "Оплачены", "all" to "Все"), status) { status = it }
                        StoreCard(accent = if (data.overdueCount > 0) StoreTone.loss else if (data.openCount > 0) StoreTone.waits else null) {
                            StatLine("К оплате", if (data.open > 0) "${money(data.open)} · ${data.openCount}" else "—", if (data.open > 0) StoreTone.waits else StoreTone.neutral)
                            StatLine("Просрочено", if (data.overdue > 0) "${money(data.overdue)} · ${data.overdueCount}" else "—", if (data.overdue > 0) StoreTone.loss else StoreTone.neutral)
                        }
                    }
                }
                if (list.isEmpty()) item { EmptyState(Icons.Filled.CheckCircle, "Долгов нет", "Всё оплачено.") }
                items(list, key = { it.id }) { d ->
                    val tone = when { d.isOverdue -> StoreTone.loss; d.isOpen -> StoreTone.waits; else -> StoreTone.neutral }
                    RowCard {
                        Column(Modifier.fillMaxWidth().clickable { selectedId = if (selectedId == d.id) null else d.id }.padding(vertical = Spacing.sm)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                LetterBadge(d.supplierName, tone)
                                Column(Modifier.weight(1f)) {
                                    Text(d.supplierName, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(
                                        listOfNotNull(
                                            d.invoiceNumber?.let { "№ $it" },
                                            d.receivedAt?.let { fmtDay(it) },
                                            if (d.isConsignment) "реализация" else d.dueDate?.let { "оплатить до ${fmtDay(it)}" },
                                        ).joinToString(" · "),
                                        color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(money(d.total), color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    StatusCaption(d.statusLabel, tone)
                                }
                            }
                            if (selectedId == d.id) {
                                Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    if (d.companyName != null) StatLine("Точка", d.companyName)
                                    if (d.paidAt != null) StatLine("Оплачен", fmtDay(d.paidAt))
                                    if (!d.comment.isNullOrBlank()) Text(d.comment, color = Orda.colors.textMuted, fontSize = 14.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                        if (d.isOpen && env.can("store-billing.pay_debt")) TextButton(onClick = { paying = d }) { Text("Оплатить") }
                                        if (d.isOpen && !d.isConsignment && env.can("store-billing.reschedule_debt")) TextButton(onClick = { moving = d }) { Text("Перенести срок") }
                                        if (!d.invoiceUrl.isNullOrBlank()) TextButton(onClick = {
                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, d.invoiceUrl.toUri())) }
                                        }) { Text("Накладная") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    paying?.let { d -> PayDebtSheet(vm, d, status, onDone = { paying = null; toast.show("Оплата записана") }, onDismiss = { paying = null }) }
    moving?.let { d -> RescheduleSheet(vm, d, status, onDone = { moving = null; toast.show("Срок перенесён") }, onDismiss = { moving = null }) }
}

/** Оплата долга: дата, способ, чек (файл обязателен) и комментарий. Расход под неё заводит сервер. */
@Composable
private fun PayDebtSheet(vm: StoreExtraViewModel, debt: SupplierDebtRow, status: String, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(todayString()) }
    var method by remember { mutableStateOf("cash") }
    var receipt by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val problem = if (receipt.isBlank()) "Загрузите чек об оплате" else null
    OrdaSheet("Оплата · ${debt.supplierName}", onDismiss) {
        StatLine("Сумма", money(debt.total))
        DateInput("Дата оплаты", date, { date = it }, maxToday = true)
        PillSegment(listOf("cash" to "Наличными", "kaspi" to "Kaspi"), method, Modifier.fillMaxWidth()) { method = it }
        UploadSlot("Чек об оплате (обязателен)", receipt, { receipt = it }, { error = it })
        TextInput("Комментарий", comment, { comment = it })
        BlockerHint(problem)
        FormError(error)
        PrimaryButton("Записать оплату", Modifier.fillMaxWidth(), enabled = problem == null, loading = saving) {
            saving = true
            error = null
            scope.launch {
                val e = vm.payDebt(debt.id, date, method, receipt, comment, status)
                saving = false
                if (e == null) onDone() else error = e
            }
        }
    }
}

@Composable
private fun RescheduleSheet(vm: StoreExtraViewModel, debt: SupplierDebtRow, status: String, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(todayString()) }
    var reason by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    OrdaSheet("Перенести срок", onDismiss) {
        Text("${debt.supplierName} · ${money(debt.total)}", color = Orda.colors.textDim, fontSize = 14.sp)
        DateInput("Новый срок оплаты", date, { date = it })
        TextInput("Причина", reason, { reason = it })
        FormError(error)
        PrimaryButton("Перенести", Modifier.fillMaxWidth(), loading = saving) {
            saving = true
            error = null
            scope.launch {
                val e = vm.rescheduleDebt(debt.id, date, reason, status)
                saving = false
                if (e == null) onDone() else error = e
            }
        }
    }
}

// ── Заявки поставщикам ───────────────────────────────────────────────────────

/** Заявки поставщикам: черновик → отправлена → получена, либо отменена. Новые заявки собирает план закупа и сайт. */
@Composable
fun PurchaseOrdersScreen() {
    val env = LocalBusiness.current
    val vm = rememberStoreExtraViewModel()
    val state by vm.orders.collectAsState()
    val toast = rememberToast()
    var filter by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.loadOrders() }
    BackHandler(enabled = selectedId != null) { selectedId = null }

    StoreScaffold(toast.message) {
        if (selectedId != null) {
            OrderDetail(vm, selectedId!!, toast, env.can("store-purchase-orders.edit"), env.can("store-purchase-orders.cancel"), onBack = { selectedId = null })
        } else {
            StateContainer(state, onRetry = vm::loadOrders, emptyTitle = "Заявок поставщикам нет", emptyMessage = "Заявки появятся, когда вы соберёте закуп.", isEmpty = { it.isEmpty() }) { orders ->
                val shown = if (filter == null) orders else orders.filter { it.status == filter }
                StoreLazy {
                    item {
                        ChipRow {
                            StoreChip("Все", on = filter == null) { filter = null }
                            listOf("draft" to "Черновики", "sent" to "Отправлены", "received" to "Получены", "cancelled" to "Отменены").forEach { (k, t) ->
                                StoreChip(t, on = filter == k) { filter = if (filter == k) null else k }
                            }
                        }
                    }
                    items(shown, key = { it.id }) { o ->
                        RowCard {
                            Row(Modifier.fillMaxWidth().clickable { selectedId = o.id }.padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                LetterBadge(o.supplierName, orderTone(o.status))
                                Column(Modifier.weight(1f)) {
                                    Text(o.supplierName, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(listOfNotNull(fmtDay(o.createdAt), positions(o.itemCount), if (o.isAuto) "авто" else null).joinToString(" · "), color = Orda.colors.textDim, fontSize = 13.sp)
                                }
                                StatusCaption(o.statusLabel, orderTone(o.status))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun orderTone(status: String) = when (status) {
    "draft" -> StoreTone.waits
    "sent" -> StoreTone.backroom
    "received" -> StoreTone.good
    else -> StoreTone.neutral
}

@Composable
private fun OrderDetail(vm: StoreExtraViewModel, id: String, toast: ToastState, canEdit: Boolean, canCancel: Boolean, onBack: () -> Unit) {
    val state by vm.orderDetail.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    LaunchedEffect(id) { vm.loadOrderDetail(id) }

    fun move(status: String, done: String) {
        busy = true
        error = null
        scope.launch {
            val e = vm.setOrderStatus(id, status)
            busy = false
            if (e == null) toast.show(done) else error = e
        }
    }

    StateContainer(state, onRetry = { vm.loadOrderDetail(id) }, isEmpty = { false }) { o ->
        StoreLazy {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    TextButton(onClick = onBack) { Text("← К заявкам") }
                    FormError(error)
                    StoreCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(o.supplierName, color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            StatusCaption(o.statusLabel, orderTone(o.status))
                        }
                        StatLine("Создана", fmtDayTime(o.createdAt))
                        if (o.sentAt != null) StatLine("Отправлена", fmtDayTime(o.sentAt))
                        if (o.receivedAt != null) StatLine("Получена", fmtDayTime(o.receivedAt))
                        if (!o.salesRep.isNullOrBlank()) StatLine("Менеджер", o.salesRep)
                        if (!o.comment.isNullOrBlank()) Text(o.comment, color = Orda.colors.textMuted, fontSize = 14.sp)
                        if (!o.cancelReason.isNullOrBlank()) Text("Отменена: ${o.cancelReason}", color = StoreTone.loss, fontSize = 14.sp)
                        if (!o.salesRepPhone.isNullOrBlank()) {
                            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, "tel:${o.salesRepPhone}".toUri())) } }) { Text("Позвонить ${o.salesRepPhone}") }
                        }
                    }
                    StoreCard {
                        SectionTitle("Состав", positions(o.lines.size))
                        if (o.lines.isEmpty()) InlineEmpty(Icons.Filled.Inbox, "Позиции не указаны")
                        o.lines.forEachIndexed { i, l ->
                            if (i > 0) RowDivider()
                            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                ItemBadge(l.name, Orda.colors.brand, 36)
                                Column(Modifier.weight(1f)) {
                                    Text(l.name, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                                    Text("сейчас ${formatQty(l.current)}" + (l.threshold?.let { " · порог ${formatQty(it)}" } ?: ""), color = Orda.colors.textDim, fontSize = 13.sp)
                                }
                                Text(formatQtyUnit(l.suggested, l.unit), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    if (canEdit) {
                        when (o.status) {
                            "draft" -> PrimaryButton("Отметить отправленной", Modifier.fillMaxWidth(), loading = busy) { move("sent", "Заявка отправлена") }
                            "sent" -> PrimaryButton("Отметить полученной", Modifier.fillMaxWidth(), loading = busy) { move("received", "Заявка получена") }
                        }
                    }
                    if (canCancel && (o.status == "draft" || o.status == "sent")) {
                        TextButton(onClick = { cancelling = true }, enabled = !busy) { Text("Отменить заявку", color = StoreTone.loss) }
                    }
                }
            }
        }
    }
    if (cancelling) {
        CancelDocumentSheet(
            title = "Отменить заявку",
            note = "Поставщик не повезёт отменённое. Укажите причину — она останется в истории.",
            onConfirm = { reason -> vm.setOrderStatus(id, "cancelled", reason) },
            onDone = {
                cancelling = false
                toast.show("Заявка отменена")
            },
            onDismiss = { cancelling = false },
        )
    }
}

// ── План закупа ──────────────────────────────────────────────────────────────

/**
 * План закупа на следующую неделю по точке: сколько взять у каждого поставщика
 * исходя из продаж за 28 дней, остатков и последней закупочной цены. Затоваренное —
 * отдельным списком «не покупать».
 */
@Composable
fun PurchasePlanScreen() {
    val vm = rememberStoreExtraViewModel()
    val pick = rememberShopPick(vm)
    val state by vm.plan.collectAsState()
    val companyId = pick.selected?.id
    LaunchedEffect(companyId) { if (companyId != null) vm.loadPlan(companyId) }

    StoreScaffold(null) {
        if (companyId == null) {
            EmptyState(Icons.Filled.Storefront, "Нет точек-магазинов", "Включите точку в настройках магазина.")
        } else {
            StateContainer(state, onRetry = { vm.loadPlan(companyId) }, emptyTitle = "Закупать нечего", emptyMessage = "Запаса хватает по всем позициям.", loadingMessage = "Считаем план…", isEmpty = { false }) { plan ->
                StoreLazy {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            ShopChips(pick)
                            StoreCard {
                                Text("Закуп на неделю с ${plan.weekStart}", color = Orda.colors.textDim, fontSize = 14.sp)
                                Text(money(plan.total), color = Orda.colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                if (plan.revenuePerWeek > 0) StatLine("Выручка в неделю (за 4 недели)", money(plan.revenuePerWeek))
                            }
                            if (plan.groups.isEmpty()) InlineEmpty(Icons.Filled.CheckCircle, "Закупать нечего — запаса хватает.", StoreTone.good)
                        }
                    }
                    items(plan.groups, key = { it.supplier }) { g ->
                        StoreCard {
                            SectionTitle(g.supplier, "${positions(g.items.size)} · ${money(g.total)}")
                            g.items.forEachIndexed { i, l ->
                                if (i > 0) RowDivider()
                                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                    ItemBadge(l.name, if (l.wasOutOfStock) StoreTone.loss else Orda.colors.brand, 36)
                                    Column(Modifier.weight(1f)) {
                                        Text(l.name, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                                        Text(
                                            "на складе ${formatQty(l.stock)} · уходит ${formatQty(Math.round(l.weeklyDemand * 10) / 10.0)} в неделю" +
                                                if (l.wasOutOfStock) " · был в нуле" else "",
                                            color = if (l.wasOutOfStock) StoreTone.waits else Orda.colors.textDim, fontSize = 12.sp,
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            if (l.packSize > 1) "${formatQty(l.packs)} уп." else formatQty(l.order),
                                            color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(money(l.amount), color = Orda.colors.textDim, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                    if (plan.doNotBuy.isNotEmpty()) {
                        item {
                            StoreCard {
                                SectionTitle("Не покупать", "затоварено")
                                plan.doNotBuy.take(30).forEachIndexed { i, s ->
                                    if (i > 0) RowDivider()
                                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                                        Text(s.name, color = Orda.colors.text, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                        Text("хватит на ${formatQty(Math.round(s.coverageWeeks * 10) / 10.0)} нед.", color = StoreTone.waits, fontSize = 13.sp)
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
