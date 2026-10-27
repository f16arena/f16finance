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
import androidx.compose.material.icons.filled.Inbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.business.LetterBadge
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// Заявки точек на товар со склада. Порт RequestsScreen / RequestDetail /
// NewRequestSheet.
//
// Решение — ОДИН запрос на сервер (`decideRequest`): он вызывает
// `inventory_decide_request`, которая атомарно минусует склад и плюсует витрину.
// Два клиентских шага дали бы расхождение остатков.

/** «Заявки» — ждущие решения; «Журнал заявок» — вся история. */
@Composable
fun RequestsScreen(journal: Boolean) {
    val env = LocalBusiness.current
    val vm = rememberStoreViewModel()
    val state by vm.requests.collectAsState()
    val toast = rememberToast()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<String?>(null) }
    val canCreate = env.can("store-requests.create")
    LaunchedEffect(Unit) {
        vm.loadRequests()
        vm.loadOverview()
    }
    BackHandler(enabled = selectedId != null) { selectedId = null }

    StoreScaffold(toast.message) {
        val selected = state.data?.firstOrNull { it.id == selectedId }
        if (selected != null) {
            RequestDetail(vm, selected, toast, onBack = { selectedId = null })
        } else {
            StateContainer(state, onRetry = vm::loadRequests, emptyTitle = "Заявок нет", isEmpty = { false }) { requests ->
                val all = if (journal) requests else requests.filter { it.isPending }
                val shown = if (filter == null) all else all.filter { it.statusLabel == filter }
                val statuses = all.map { it.statusLabel }.distinct()
                StoreLazy {
                    if (journal && statuses.size > 1) {
                        item {
                            ChipRow {
                                StoreChip("Все", on = filter == null) { filter = null }
                                statuses.forEach { s -> StoreChip(s, on = filter == s) { filter = if (filter == s) null else s } }
                            }
                        }
                    }
                    if (shown.isEmpty()) {
                        item {
                            EmptyState(
                                Icons.Filled.Inbox,
                                if (journal) "Заявок не было" else "Нет заявок в ожидании",
                                if (journal) "Точки ещё не отправляли заявки." else "Всё разобрано. История — в разделе «Журнал заявок».",
                            )
                        }
                    }
                    items(shown, key = { it.id }) { r -> RowCard { RequestRow(r) { selectedId = r.id } } }
                    item { Box(Modifier.padding(bottom = 80.dp)) }
                }
                if (canCreate) {
                    Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.BottomEnd) {
                        ExtendedFloatingActionButton(
                            onClick = { adding = true },
                            icon = { Icon(Icons.Filled.Add, null) },
                            text = { Text("Заявка") },
                            containerColor = Orda.colors.brand,
                            contentColor = androidx.compose.ui.graphics.Color.White,
                        )
                    }
                }
            }
        }
    }
    if (adding) {
        NewRequestSheet(vm, onDone = { adding = false; toast.show("Заявка создана") }, onDismiss = { adding = false })
    }
}

@Composable
private fun RequestDetail(vm: StoreViewModel, request: StockRequest, toast: ToastState, onBack: () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val canApprove = env.can("store-requests.approve")
    val canReject = env.can("store-requests.reject")
    // Выдача и приёмка — физические действия у товара; отмечать их можно с телефона.
    val canMove = env.can("store-requests.transition_status")
    val canUndo = env.can("store-requests.undecide")
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var rejecting by remember { mutableStateOf(false) }
    var undoing by remember { mutableStateOf(false) }

    fun run(done: String, block: suspend () -> String?) {
        busy = true
        error = null
        scope.launch {
            val e = block()
            busy = false
            if (e == null) toast.show(done) else error = e
        }
    }

    StoreLazy {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                TextButton(onClick = onBack) { Text("← К списку") }
                FormError(error)
                if (request.isPending && (canApprove || canReject)) {
                    StoreCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            if (canReject) {
                                kz.ordaops.android.ui.components.PrimaryButton("Отклонить", Modifier.weight(1f), enabled = !busy) { rejecting = true }
                            }
                            if (canApprove) {
                                kz.ordaops.android.ui.components.PrimaryButton("Одобрить", Modifier.weight(1f), loading = busy) {
                                    run("Заявка одобрена") { vm.decideRequest(request, approved = true) }
                                }
                            }
                        }
                    }
                }
                val stage = request.nextStage
                if (stage != null && canMove) {
                    StoreCard {
                        PrimaryButton(if (busy) "Отмечаем…" else stage.actionLabel, Modifier.fillMaxWidth(), enabled = !busy) {
                            run(if (stage == RequestStage.Issued) "Выдача отмечена" else "Получение отмечено") { vm.moveRequest(request.id, stage) }
                        }
                        if (request.isApproved && canUndo) {
                            TextButton(onClick = { undoing = true }, enabled = !busy) { Text("Откатить одобрение", color = StoreTone.loss) }
                        }
                    }
                }
                if (request.isPending && request.hasShortage) {
                    StoreCard(accent = StoreTone.waits) {
                        Text(
                            "Не хватает на складе: " + request.lines.filter { !it.enough }.joinToString(", ") { it.name } + ". Одобрение сервер не пропустит, пока остаток не пополнен.",
                            color = StoreTone.waits, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                }
                StoreCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(request.companyName ?: "Точка", color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        StatusCaption(request.statusLabel, if (request.isPending) StoreTone.waits else StoreTone.good)
                    }
                    if (request.sourceName != null && request.targetName != null) StatLine("Маршрут", "${request.sourceName} → ${request.targetName}")
                    if (request.createdAt != null) StatLine("Создана", fmtDayTime(request.createdAt))
                    if (request.purchaseTotal > 0) StatLine("По закупке", kz.ordaops.android.ui.components.money(request.purchaseTotal))
                    if (request.saleTotal > 0) StatLine("В продаже", kz.ordaops.android.ui.components.money(request.saleTotal))
                    if (!request.comment.isNullOrBlank()) Text(request.comment, color = Orda.colors.textMuted, fontSize = 14.sp)
                }
                StoreCard {
                    SectionTitle("Состав", subtitle = positions(request.lines.size))
                    if (request.lines.isEmpty()) InlineEmpty(Icons.Filled.Inbox, "Позиции не указаны")
                    request.lines.forEachIndexed { i, line ->
                        if (i > 0) RowDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            LetterBadge(line.name, Orda.colors.brand, 36)
                            Text(line.name, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, modifier = Modifier.weight(1f))
                            val approved = line.approved
                            // Одобренное часто меньше запрошенного — показываем оба, иначе не видно, что урезали.
                            if (approved != null && approved != line.requested) {
                                Text(formatQty(line.requested), color = Orda.colors.textDim, fontSize = 13.sp, textDecoration = TextDecoration.LineThrough)
                                Text(formatQtyUnit(approved, line.unit), color = StoreTone.good, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            } else {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(formatQtyUnit(line.requested, line.unit), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    // Сколько реально есть на складе — чтобы не одобрять вслепую.
                                    if (request.isPending && line.available != null) {
                                        Text("на складе ${formatQty(line.available)}", color = if (line.enough) Orda.colors.textDim else StoreTone.waits, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (rejecting) {
        var comment by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejecting = false },
            title = { Text("Отклонить заявку?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("Точка увидит отказ. Остатки не изменятся.", fontSize = 14.sp)
                    TextInput("Причина (необязательно)", comment, { comment = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    rejecting = false
                    run("Заявка отклонена") { vm.decideRequest(request, approved = false, comment = comment) }
                }) { Text("Отклонить", color = StoreTone.loss) }
            },
            dismissButton = { TextButton(onClick = { rejecting = false }) { Text("Отмена") } },
        )
    }
    if (undoing) {
        var reason by remember { mutableStateOf("") }
        // Откат меняет остатки: товар уходит обратно на склад. Причина обязательна.
        AlertDialog(
            onDismissRequest = { undoing = false },
            title = { Text("Откатить одобрение?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("Товар вернётся на склад, а заявка снова станет новой.", fontSize = 14.sp)
                    TextInput("Причина", reason, { reason = it })
                }
            },
            confirmButton = {
                TextButton(enabled = reason.isNotBlank(), onClick = {
                    undoing = false
                    run("Одобрение откачено") { vm.undoRequest(request.id, reason) }
                }) { Text("Откатить", color = StoreTone.loss) }
            },
            dismissButton = { TextButton(onClick = { undoing = false }) { Text("Отмена") } },
        )
    }
}

/**
 * Новая заявка на товар со склада. Товар выбирается из остатков склада: просить
 * то, чего на складе нет, бессмысленно — заявка повиснет.
 */
@Composable
private fun NewRequestSheet(vm: StoreViewModel, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val overview by vm.overview.collectAsState()
    val ov = overview.data
    val stockroomIds = ov?.locations?.filter { it.kind == "warehouse" || it.kind == "backroom" }?.map { it.id }?.toSet().orEmpty()
    val available = ov?.balances?.filter { it.quantity > 0 && (stockroomIds.isEmpty() || it.locationId in stockroomIds) }.orEmpty()
    val companies = ov?.locations?.mapNotNull { l -> l.companyId?.let { StoreCompany(it, l.companyName ?: "Точка") } }?.distinctBy { it.id }.orEmpty()
    var company by remember { mutableStateOf(companies.firstOrNull()) }
    var search by remember { mutableStateOf("") }
    var item by remember { mutableStateOf<StockBalance?>(null) }
    var qty by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val q = parseNumber(qty)
    val problem = when {
        company == null -> "Не определена точка"
        item == null -> "Выберите товар"
        q <= 0 -> "Укажите количество"
        else -> null
    }
    OrdaSheet("Заявка на товар", onDismiss) {
        if (companies.size > 1) PickerField("Для точки", company, companies, { it.name }) { company = it }
        if (item == null) {
            StoreSearchField(search, { search = it }, placeholder = "Что просим со склада")
            val found = available.filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) }.distinctBy { it.itemId }.take(12)
            found.forEach { b ->
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { item = b }, modifier = Modifier.fillMaxWidth()) {
                        Text(b.name, color = Orda.colors.text, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(formatQtyUnit(b.quantity, b.unit), color = Orda.colors.textDim, fontSize = 13.sp)
                    }
                }
            }
        } else {
            StatLine("Товар", item!!.name)
            TextButton(onClick = { item = null }) { Text("Выбрать другой") }
            NumberInput("Количество, ${item!!.unit}", qty, { qty = it })
            TextInput("Комментарий", comment, { comment = it })
        }
        BlockerHint(problem)
        FormError(error)
        PrimaryButton("Отправить заявку", Modifier.fillMaxWidth(), enabled = problem == null, loading = saving) {
            saving = true
            scope.launch {
                val e = vm.requestToShowcase(company!!.id, item!!.itemId, q, comment.takeIf { it.isNotBlank() })
                saving = false
                if (e == null) onDone() else error = e
            }
        }
    }
}
