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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.operator.audit.AuditScannerPane
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.feature.operator.shift.todayString
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// Ревизии: проведённые пересчёты, акты пересчёта и новый пересчёт с телефона.
// Порт RevisionsScreen / RevisionActScreen / StocktakeSheet.

@Composable
fun RevisionsScreen() {
    val env = LocalBusiness.current
    val vm = rememberStoreViewModel()
    val state by vm.stocktakes.collectAsState()
    val acts by vm.acts.collectAsState()
    val toast = rememberToast()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showActs by remember { mutableStateOf(false) }
    var counting by remember { mutableStateOf(false) }
    // Право `store-revisions.commit` проверяет и сервер; отмена и откат закрыты `cancel`.
    val canCount = env.can("store-revisions.commit")
    val canCancel = env.can("store-revisions.cancel")
    LaunchedEffect(Unit) {
        vm.loadStocktakes()
        vm.loadActs()
        vm.loadOverview()
    }
    BackHandler(enabled = selectedId != null || showActs) {
        if (selectedId != null) selectedId = null else showActs = false
    }

    StoreScaffold(toast.message) {
        val selected = state.data?.firstOrNull { it.id == selectedId }
        when {
            selected != null -> StocktakeDetail(selected, onBack = { selectedId = null })
            showActs -> ActsScreen(vm, toast, onBack = { showActs = false })
            else -> StateContainer(state, onRetry = vm::loadStocktakes, emptyTitle = "Ревизий нет", emptyMessage = "Здесь появятся пересчёты склада и витрин.", isEmpty = { false }) { list ->
                val openActs = acts.data.orEmpty().filter { it.isOpen }
                StoreLazy {
                    // Идущий пересчёт — выше готовых: он ещё живой, и именно с ним что-то делают.
                    if (openActs.isNotEmpty() || canCancel || acts.data?.isNotEmpty() == true) {
                        item {
                            StoreCard(Modifier.clickable { showActs = true }) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                    kz.ordaops.android.feature.operator.money.TintedIcon(Icons.Filled.Checklist, Orda.colors.info, 42, corner = 21.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text("Акты пересчёта", color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (openActs.isEmpty()) "Открытых нет" else "Идёт пересчёт: ${openActs.size}",
                                            color = if (openActs.isEmpty()) Orda.colors.textDim else StoreTone.waits,
                                            fontSize = 13.sp,
                                        )
                                    }
                                    Text("›", color = Orda.colors.textDim, fontSize = 22.sp)
                                }
                            }
                        }
                    }
                    if (list.isEmpty()) item { EmptyState(Icons.Filled.Checklist, "Ревизий нет", "Здесь появятся пересчёты склада и витрин.") }
                    items(list.sortedByDescending { it.countedAt ?: 0L }, key = { it.id }) { s ->
                        RowCard {
                            val shortage = s.shortageAmount
                            Row(Modifier.fillMaxWidth().clickable { selectedId = s.id }.padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                kz.ordaops.android.feature.operator.money.TintedIcon(Icons.Filled.Checklist, if (shortage > 0) StoreTone.loss else StoreTone.good, 40, corner = 20.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(s.locationName ?: "Ревизия", color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(listOfNotNull(fmtDay(s.countedAt), s.authorName, positions(s.lines.size)).joinToString(" · "), color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1)
                                }
                                // Недостача красным, «сошлось» — зелёным, ноль не кричит.
                                if (shortage > 0) Text("−${money(shortage)}", color = StoreTone.loss, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                else Text("сошлось", color = StoreTone.good, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    item { Box(Modifier.padding(bottom = 80.dp)) }
                }
                if (canCount) {
                    Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.BottomEnd) {
                        ExtendedFloatingActionButton(
                            onClick = { counting = true },
                            icon = { Icon(Icons.Filled.Add, null) },
                            text = { Text("Пересчёт") },
                            containerColor = Orda.colors.brand,
                            contentColor = Color.White,
                        )
                    }
                }
            }
        }
    }
    if (counting) StocktakeDialog(vm, onDone = { counting = false; toast.show("Ревизия проведена") }, onDismiss = { counting = false })
}

@Composable
private fun StocktakeDetail(s: Stocktake, onBack: () -> Unit) {
    val mismatches = s.lines.filter { !it.isMatch }.sortedByDescending { kotlin.math.abs(it.amount) }
    StoreLazy {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                TextButton(onClick = onBack) { Text("← К ревизиям") }
                StoreCard {
                    Text(s.locationName ?: "Ревизия", color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    StatLine("Дата", fmtFullDate(s.countedAt))
                    if (s.companyName != null) StatLine("Точка", s.companyName)
                    if (s.authorName != null) StatLine("Считал", s.authorName)
                    StatLine("Позиций", "${s.lines.size}")
                    StatLine("Расхождений", "${mismatches.size}", if (mismatches.isEmpty()) StoreTone.neutral else StoreTone.waits)
                    StatLine("Недостача", if (s.shortageAmount > 0) "−${money(s.shortageAmount)}" else "—", if (s.shortageAmount > 0) StoreTone.loss else StoreTone.neutral)
                    if (!s.comment.isNullOrBlank()) Text(s.comment, color = Orda.colors.textMuted, fontSize = 14.sp)
                }
                StoreCard {
                    SectionTitle(if (mismatches.isEmpty()) "Расхождений нет" else "Расхождения", if (mismatches.isEmpty()) null else positions(mismatches.size))
                    if (mismatches.isEmpty()) InlineEmpty(Icons.Filled.Checklist, "Всё сошлось с учётом", StoreTone.good)
                    mismatches.forEachIndexed { i, l ->
                        if (i > 0) RowDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            ItemBadge(l.name, if (l.isShortage) StoreTone.loss else StoreTone.good, 36)
                            Column(Modifier.weight(1f)) {
                                Text(l.name, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                                Text("числилось ${formatQty(l.expected)} · насчитали ${formatQty(l.actual)} ${l.unit}", color = Orda.colors.textDim, fontSize = 13.sp)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                val sign = if (l.delta > 0) "+" else "−"
                                Text("$sign${formatQty(kotlin.math.abs(l.delta))}", color = if (l.isShortage) StoreTone.loss else StoreTone.good, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                if (l.price > 0) Text(money(kotlin.math.abs(l.amount)), color = Orda.colors.textDim, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Акты пересчёта ───────────────────────────────────────────────────────────

@Composable
private fun ActsScreen(vm: StoreViewModel, toast: ToastState, onBack: () -> Unit) {
    val state by vm.acts.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = selectedId != null) { selectedId = null }
    val selected = state.data?.firstOrNull { it.id == selectedId }
    if (selected != null) {
        ActDetailScreen(vm, selected, toast, onBack = { selectedId = null })
        return
    }
    StateContainer(state, onRetry = vm::loadActs, emptyTitle = "Актов нет", emptyMessage = "Открытые и проведённые пересчёты появятся здесь.", isEmpty = { it.isEmpty() }) { list ->
        StoreLazy {
            item { TextButton(onClick = onBack) { Text("← К ревизиям") } }
            items(list, key = { it.id }) { a ->
                RowCard {
                    Column(Modifier.fillMaxWidth().clickable { selectedId = a.id }.padding(vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.locationName, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                            StatusCaption(a.statusLabel, when { a.isOpen -> StoreTone.waits; a.isClosed -> StoreTone.good; else -> StoreTone.neutral })
                        }
                        Text(listOfNotNull("с ${fmtDayTime(a.openedAt)}", a.closedAt?.let { "до ${fmtDayTime(it)}" }).joinToString(" "), color = Orda.colors.textDim, fontSize = 13.sp)
                        a.progress?.let { p ->
                            Box(Modifier.fillMaxWidth().height(4.dp).background(Orda.colors.surfaceRaised, CircleShape)) {
                                Box(Modifier.fillMaxWidth(p.toFloat().coerceIn(0f, 1f)).height(4.dp).background(if (a.isOpen) StoreTone.waits else StoreTone.good, CircleShape))
                            }
                            Text("${a.countedItems} из ${a.totalItems}", color = Orda.colors.textDim, fontSize = 12.sp)
                        }
                        // Итог проведённого акта по закупу: минус — недостача; обнулённое непосчитанное отдельно.
                        if (a.resultValue != null) {
                            StatLine("Итог по закупу", money(kotlin.math.round(a.resultValue)), if (a.resultValue < -0.5) StoreTone.loss else Orda.colors.text)
                            if (a.zeroedValue != null && kotlin.math.abs(a.zeroedValue) >= 1) StatLine("из них обнулено непосчитанное", money(kotlin.math.round(a.zeroedValue)), Orda.colors.textMuted)
                            // Склад и витрина той же точки читаем вместе: товар не на своём месте даёт минус тут и плюс там.
                            val pair = pairedAct(a, list)
                            if (pair?.resultValue != null) {
                                StatLine(
                                    "по точке с ${if (pair.locationType == "warehouse") "складом" else "витриной"} ${fmtDay(pair.closedAt)}",
                                    money(kotlin.math.round(a.resultValue + pair.resultValue)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActDetailScreen(vm: StoreViewModel, act: RevisionAct, toast: ToastState, onBack: () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val state by vm.actDetail.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var recounting by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var closing by remember { mutableStateOf<RevisionActDetail?>(null) }
    var summary by remember { mutableStateOf<AuditCloseSummary?>(null) }
    val canEdit = env.can("store-revisions.edit") && act.isOpen
    val canCancel = env.can("store-revisions.cancel")
    val canCommit = env.can("store-revisions.commit")
    LaunchedEffect(act.id) { vm.loadActDetail(act.id) }

    fun run(done: String, block: suspend () -> String?) {
        busy = true
        error = null
        scope.launch {
            val e = block()
            busy = false
            if (e == null) {
                toast.show(done)
                onBack()
            } else {
                error = e
            }
        }
    }

    StateContainer(state, onRetry = { vm.loadActDetail(act.id) }, isEmpty = { false }) { detail ->
        StoreLazy {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    TextButton(onClick = onBack) { Text("← К актам") }
                    FormError(error)
                    StoreCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(act.locationName, color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            StatusCaption(act.statusLabel, when { act.isOpen -> StoreTone.waits; act.isClosed -> StoreTone.good; else -> StoreTone.neutral })
                        }
                        StatLine("Посчитано", "${detail.countedItems} из ${detail.totalItems}")
                        val total = detail.report.sumOf { it.varianceCost }
                        StatLine("Расхождение", if (total == 0.0) "—" else (if (total < 0) "−" else "+") + money(kotlin.math.abs(total)), if (total < 0) StoreTone.loss else if (total > 0) StoreTone.good else StoreTone.neutral)
                        if (!act.comment.isNullOrBlank()) Text(act.comment, color = Orda.colors.textMuted, fontSize = 14.sp)
                    }
                    // Конфликт: двое считали и получили разное — решает человек.
                    val conflicts = detail.report.filter { it.conflict }
                    if (conflicts.isNotEmpty()) {
                        StoreCard(accent = StoreTone.waits) { Text("Спорных позиций: ${conflicts.size} — насчитали по-разному. Укажите итоговое число.", color = StoreTone.waits, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                    }
                    StoreCard {
                        SectionTitle("Пересчитано", positions(detail.report.size))
                        if (detail.report.isEmpty()) InlineEmpty(Icons.Filled.Checklist, "Пока ничего не посчитано")
                        detail.report.sortedByDescending { kotlin.math.abs(it.varianceCost) }.forEachIndexed { i, l ->
                            if (i > 0) RowDivider()
                            Row(
                                Modifier.fillMaxWidth().let { if (canEdit) it.clickable { recounting = l.itemId to l.name } else it }.padding(vertical = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            ) {
                                ItemBadge(l.name, if (l.conflict) StoreTone.waits else if (l.variance < 0) StoreTone.loss else if (l.variance > 0) StoreTone.good else Orda.colors.brand, 36)
                                Column(Modifier.weight(1f)) {
                                    Text(l.name, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                                    Text("числилось ${formatQty(l.expected)} · насчитали ${formatQty(l.counted)}" + (l.countedBy?.let { " · $it" } ?: ""), color = Orda.colors.textDim, fontSize = 13.sp)
                                }
                                if (l.variance != 0.0) Text((if (l.variance > 0) "+" else "−") + formatQty(kotlin.math.abs(l.variance)), color = if (l.variance < 0) StoreTone.loss else StoreTone.good, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                else Text("ок", color = StoreTone.good, fontSize = 13.sp)
                            }
                        }
                    }
                    if (detail.uncounted.isNotEmpty()) {
                        StoreCard {
                            SectionTitle("Не дошли", positions(detail.uncounted.size))
                            detail.uncounted.take(40).forEachIndexed { i, p ->
                                if (i > 0) RowDivider()
                                Row(
                                    Modifier.fillMaxWidth().let { if (canEdit) it.clickable { recounting = p.itemId to p.name } else it }.padding(vertical = Spacing.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(p.name, color = Orda.colors.text, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                    Text("числится ${formatQty(p.expected)}", color = Orda.colors.textDim, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    if (act.isOpen && canCommit) PrimaryButton("Провести акт", Modifier.fillMaxWidth(), loading = busy) { closing = detail }
                    if (act.isOpen && canCancel) TextButton(onClick = { confirm = "cancel" }, enabled = !busy) { Text("Отменить акт", color = StoreTone.loss) }
                    if (act.isClosed && canCancel) TextButton(onClick = { confirm = "revert" }, enabled = !busy) { Text("Откатить проведённый акт", color = StoreTone.loss) }
                }
            }
        }
    }

    recounting?.let { (itemId, name) ->
        var value by remember(itemId) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { recounting = null },
            title = { Text("Итоговое количество") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("$name. Число заменит все прежние подсчёты по позиции.", fontSize = 14.sp)
                    NumberInput("Сколько на самом деле", value, { value = it })
                }
            },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = {
                    recounting = null
                    scope.launch {
                        val e = vm.recountAct(act.id, itemId, parseNumber(value))
                        if (e == null) toast.show("Записали: $name") else error = e
                    }
                }) { Text("Записать") }
            },
            dismissButton = {
                Row {
                    // Сбросить подсчёты: операторы посчитают позицию заново.
                    TextButton(onClick = {
                        recounting = null
                        scope.launch {
                            val e = vm.resetActItem(act.id, itemId)
                            if (e == null) toast.show("На пересчёт: $name") else error = e
                        }
                    }) { Text("На пересчёт") }
                    TextButton(onClick = { recounting = null }) { Text("Отмена") }
                }
            },
        )
    }
    closing?.let { detail ->
        CloseActSheet(
            vm = vm,
            act = act,
            detail = detail,
            ownerLike = env.access.resolver.session.let { it.isSuperAdmin || it.staffRole == "owner" },
            onClosed = { result ->
                closing = null
                summary = result
                toast.show("Акт проведён")
            },
            onDismiss = { closing = null },
        )
    }
    summary?.let { r ->
        AlertDialog(
            onDismissRequest = { summary = null; onBack() },
            title = { Text("Результат ревизии") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    StatLine("Недостача", "${money(r.shrinkageValue)} · ${r.shrinkageItems} поз.", if (r.shrinkageValue > 0) StoreTone.loss else StoreTone.neutral)
                    StatLine("Излишек", "${money(r.surplusValue)} · ${r.surplusItems} поз.", if (r.surplusValue > 0) StoreTone.good else StoreTone.neutral)
                    if (r.zeroedItems > 0) StatLine("Обнулено (не посчитано)", "${money(r.zeroedValue)} · ${r.zeroedItems} поз.")
                    if (r.debtsCreated > 0) StatLine("Долгов создано", "${r.debtsCreated}", StoreTone.waits)
                }
            },
            confirmButton = { TextButton(onClick = { summary = null; onBack() }) { Text("Готово") } },
        )
    }
    confirm?.let { kind ->
        val (title, text, done) = when (kind) {
            "close" -> Triple("Провести акт?", "Остатки станут такими, какими их пересчитали.", "Акт проведён")
            "cancel" -> Triple("Отменить акт?", "Остатки не изменятся: снимок и подсчёты отбросятся.", "Акт отменён")
            else -> Triple("Откатить акт?", "Остатки вернутся к тому, что было до акта, а созданные им долги удалятся. Сервер пускает сюда только владельца.", "Акт откачен")
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    run(done) {
                        when (kind) {
                            "cancel" -> vm.cancelAct(act.id)
                            else -> vm.revertAct(act.id)
                        }
                    }
                }) { Text(if (kind == "close") "Провести" else "Подтвердить", color = if (kind == "close") Orda.colors.brand else StoreTone.loss) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Назад") } },
        )
    }
}

// ── Новый пересчёт ───────────────────────────────────────────────────────────

/**
 * Пересчёт склада с телефона: камера не закрывается между товарами. «Сколько
 * числится» по умолчанию скрыто — увидев цифру, человек пишет её же, не считая.
 * Непосчитанная позиция в акт не уходит (не «ноль на полке», а «не дошли»).
 */
@Composable
private fun StocktakeDialog(vm: StoreViewModel, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val overview by vm.overview.collectAsState()
    val ov = overview.data
    val locations = ov?.locations.orEmpty().filter { it.kind != "catalog" }
    var draft by remember { mutableStateOf(StocktakeDraft(countedAt = todayString())) }
    var search by remember { mutableStateOf("") }
    var scanOpen by remember { mutableStateOf(false) }
    var showsExpected by remember { mutableStateOf(false) }
    var onlyRemaining by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload(locationId: String) {
        val lines = ov?.balances.orEmpty().filter { it.locationId == locationId }
            .map { StocktakeLine(it.itemId, it.name, it.unit, it.barcode, it.quantity) }
            .sortedBy { it.name.lowercase() }
        draft = draft.copy(locationId = locationId, lines = lines)
    }
    LaunchedEffect(locations.size) {
        if (draft.locationId.isEmpty() && locations.isNotEmpty()) reload(locations.first().id)
    }
    val location = locations.firstOrNull { it.id == draft.locationId }
    val q = search.trim()
    val visible = draft.lines.filter { l ->
        (!onlyRemaining || !l.isCounted) && (q.isEmpty() || l.name.contains(q, ignoreCase = true) || (l.barcode?.contains(q) == true))
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Orda.colors.background).navigationBarsPadding().imePadding().padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Ревизия", color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Закрыть", tint = Orda.colors.text) }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        PickerField("Что пересчитываем", location, locations, { "${it.name} · ${it.kindLabel}" }) { reload(it.id) }
                        DateInput("Дата", draft.countedAt, { draft = draft.copy(countedAt = it) }, maxToday = true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Показывать, сколько числится", color = Orda.colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Switch(checked = showsExpected, onCheckedChange = { showsExpected = it })
                        }
                        Text("По умолчанию скрыто: увидев цифру, человек пишет её же, не считая.", color = Orda.colors.textDim, fontSize = 12.sp)
                        StoreCard(accent = if (draft.remaining == 0 && draft.lines.isNotEmpty()) StoreTone.good else null) {
                            StatLine("Пересчитано", "${draft.countedLines.size} из ${draft.lines.size}")
                            if (draft.mismatchedLines.isNotEmpty()) StatLine("Расхождений", "${draft.mismatchedLines.size}", StoreTone.waits)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Только непосчитанные", color = Orda.colors.textMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Switch(checked = onlyRemaining, onCheckedChange = { onlyRemaining = it })
                            }
                        }
                        StoreSearchField(search, { search = it }, onScan = { scanOpen = !scanOpen })
                        if (scanOpen) AuditScannerPane(paused = false, onCode = { search = it })
                    }
                }
                items(visible, key = { it.itemId }) { line ->
                    var text by remember(line.itemId) { mutableStateOf(line.actual?.let { formatQty(it) } ?: "") }
                    RowCard {
                        Column(Modifier.padding(vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(line.name, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                            if (showsExpected) Text("числится ${formatQtyUnit(line.expected, line.unit)}", color = Orda.colors.textDim, fontSize = 13.sp)
                            NumberInput("Насчитали, ${line.unit}", text, { v ->
                                text = v
                                val actual = if (v.isBlank()) null else parseNumber(v)
                                draft = draft.copy(lines = draft.lines.map { if (it.itemId == line.itemId) it.copy(actual = actual) else it })
                            })
                            val diff = draft.lines.firstOrNull { it.itemId == line.itemId }?.takeIf { showsExpected }?.difference
                            if (diff != null && kotlin.math.abs(diff) > 0.0005) {
                                Text("расхождение ${if (diff > 0) "+" else "−"}${formatQty(kotlin.math.abs(diff))}", color = if (diff < 0) StoreTone.loss else StoreTone.good, fontSize = 13.sp)
                            }
                        }
                    }
                }
                item { TextInput("Комментарий к ревизии", draft.comment, { draft = draft.copy(comment = it) }, singleLine = false) }
            }
            BlockerHint(draft.validationMessage)
            FormError(error)
            PrimaryButton("Провести ревизию", Modifier.fillMaxWidth(), enabled = draft.validationMessage == null, loading = saving) {
                saving = true
                scope.launch {
                    val e = vm.createStocktake(draft, location?.companyId)
                    saving = false
                    if (e == null) onDone() else error = e
                }
            }
        }
    }
}

/**
 * Проведение акта. Остатки станут пересчитанными. «Полная ревизия» — обнулить
 * непосчитанное — только владельцу и выборочно: отмеченным остаток станет 0, остальные
 * останутся как есть. Проданное за 7 дней по умолчанию не отмечено — скорее всего оно
 * есть, его просто не досчитали (из-за этого ревизия «всегда в минус»).
 */
@Composable
private fun CloseActSheet(vm: StoreViewModel, act: RevisionAct, detail: RevisionActDetail, ownerLike: Boolean, onClosed: (AuditCloseSummary) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val rows = detail.uncounted
    val zeroSum = rows.sumOf { it.value }
    var zero by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(defaultZeroSelection(rows)) }
    var assignDebt by remember { mutableStateOf(false) }
    var assignDebtZeroed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val otherLabel = if (detail.otherLocationType == "warehouse") "на складе" else "на витрине"
    val selectedRows = rows.filter { it.itemId in selected }
    val selectedSum = selectedRows.sumOf { it.value }
    val keptSold = rows.count { it.soldRecent > 0 && it.itemId !in selected }

    OrdaSheet("Провести акт", onDismiss) {
        Text("Остатки станут такими, какими их пересчитали. Расхождения превратятся в движения.", color = Orda.colors.textDim, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Повесить недостачу долгом на ответственных (удержится из зарплаты)", color = Orda.colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = assignDebt, onCheckedChange = { assignDebt = it })
        }
        if (rows.isEmpty()) {
            InlineEmpty(Icons.Filled.Checklist, "Все позиции с остатком посчитаны — обнулять нечего.", StoreTone.good)
        } else if (ownerLike) {
            StoreCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Полная ревизия: обнулить непосчитанное", color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("Не посчитано ${rows.size} поз. на ${money(zeroSum)} по закупу — «чего не посчитали, того нет».", color = Orda.colors.textDim, fontSize = 13.sp)
                    }
                    Switch(checked = zero, onCheckedChange = { zero = it })
                }
                // Обнуление такого объёма почти всегда ложная недостача: товар есть, его не досчитали.
                if (isBigZeroing(rows, detail.totalItems)) {
                    val sold = rows.count { it.soldRecent > 0 }
                    Text(
                        "Не посчитано ${rows.size} из ${detail.totalItems} поз. на ${money(zeroSum)}. Обнуление такого объёма почти всегда даёт ложную недостачу: товар есть, его просто не досчитали." +
                            (if (sold > 0) " $sold из них продавались за последнюю неделю." else "") + " Лучше досчитать.",
                        color = StoreTone.loss, fontSize = 13.sp,
                    )
                }
            }
            if (zero) {
                Text("У отмеченных остаток станет 0, остальные останутся как есть. Будет обнулено ${selectedRows.size} из ${rows.size} поз. на ${money(selectedSum)}.", color = Orda.colors.textMuted, fontSize = 13.sp)
                if (keptSold > 0) Text("$keptSold поз. продавались за последние 7 дней — отметка с них снята. Лучше досчитать их.", color = StoreTone.waits, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    TextButton(onClick = { selected = rows.map { it.itemId }.toSet() }) { Text("Отметить все") }
                    TextButton(onClick = { selected = emptySet() }) { Text("Снять все") }
                }
                rows.take(100).forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { selected = if (r.itemId in selected) selected - r.itemId else selected + r.itemId }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = r.itemId in selected, onCheckedChange = { selected = if (it) selected + r.itemId else selected - r.itemId })
                        Column(Modifier.weight(1f)) {
                            Text(r.name, color = Orda.colors.text, fontSize = 14.sp, maxLines = 1)
                            val hint = listOfNotNull(
                                r.soldRecent.takeIf { it > 0 }?.let { "продано ${formatQty(it)} шт за 7 дней" },
                                r.otherLocationQty.takeIf { it > 0 }?.let { "$otherLabel ${formatQty(it)} шт" },
                            ).joinToString(" · ")
                            if (hint.isNotEmpty()) Text(hint, color = StoreTone.waits, fontSize = 12.sp)
                        }
                        Text(formatQty(r.expected), color = Orda.colors.textDim, fontSize = 13.sp)
                    }
                }
                if (rows.size > 100) Text("…и ещё ${rows.size - 100} поз. (обнуляются по отметке «Отметить все»)", color = Orda.colors.textDim, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Обнулённое тоже повесить долгом — поровну на операторов акта. Для первой полной ревизии не рекомендуется.", color = Orda.colors.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = assignDebtZeroed, onCheckedChange = { assignDebtZeroed = it })
                }
            }
        } else {
            Text("Не посчитано ${rows.size} поз. Обнулить их при проведении может только владелец.", color = Orda.colors.textDim, fontSize = 13.sp)
        }
        FormError(error)
        PrimaryButton(
            if (zero && selectedRows.isNotEmpty()) "Обнулить ${selectedRows.size} поз. и провести" else "Провести акт",
            Modifier.fillMaxWidth(), loading = saving,
        ) {
            saving = true
            scope.launch {
                val (result, e) = vm.closeAct(act.id, if (zero && ownerLike) selected else null, assignDebt, zero && assignDebtZeroed)
                saving = false
                if (result != null) onClosed(result) else error = e
            }
        }
    }
}
