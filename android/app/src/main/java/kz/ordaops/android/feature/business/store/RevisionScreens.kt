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
                    if (act.isOpen && canCommit) PrimaryButton("Провести акт", Modifier.fillMaxWidth(), loading = busy) { confirm = "close" }
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
            dismissButton = { TextButton(onClick = { recounting = null }) { Text("Отмена") } },
        )
    }
    confirm?.let { kind ->
        val (title, text, done) = when (kind) {
            "close" -> Triple("Провести акт?", "Остатки станут такими, какими их пересчитали. Расхождения превратятся в движения, а недостача — в долг.", "Акт проведён")
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
                            "close" -> vm.closeAct(act.id)
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
