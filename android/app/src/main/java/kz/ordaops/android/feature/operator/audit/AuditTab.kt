package kz.ordaops.android.feature.operator.audit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.feature.operator.common.operatorViewModel
import kz.ordaops.android.ui.components.StateContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.LoadingView
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

/** Коробка, полкоробки, блок — то, чем считают на самом деле: нажать дважды быстрее, чем набрать «144». */
private val quickAdd = listOf(1, 6, 12, 24, 96, 144)

/** От какой ширины список актов и подсчёт стоят рядом. */
private val WideBreakpoint = 600.dp

/** Формы не растягиваем на весь планшет: длинные строки неудобно читать и целиться в них. */
private val FormMaxWidth = 840.dp

/**
 * Ревизия: список назначенных актов и подсчёт позиций — `AuditScreen.swift`.
 *
 * Оператор не начинает ревизию сам — акт открывает управляющий и назначает
 * кассиров на секции. Поэтому пустой список здесь нормальное состояние.
 * На планшете список слева, подсчёт справа; на телефоне — один за другим.
 */
@Composable
fun AuditTab() {
    val vm = operatorViewModel { env -> AuditViewModel(env.api, env.outbox) }
    val actsState by vm.acts.collectAsStateWithLifecycle()
    // Выбранный акт держим по id: после пересоздания процесса объект восстановить нечем.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = actsState.data?.firstOrNull { it.actId == selectedId }

    BoxWithConstraints(Modifier.fillMaxSize().background(Orda.colors.background)) {
        if (maxWidth >= WideBreakpoint) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(360.dp).fillMaxHeight()) {
                    AuditListScreen(vm, actsState, selectedId, onOpen = { selectedId = it.actId })
                }
                Box(Modifier.weight(1f).fillMaxHeight().background(Orda.colors.surface.copy(alpha = 0.4f))) {
                    if (selected != null) {
                        key(selected.actId) { AuditCountScreen(vm, selected, onBack = null) }
                    } else {
                        EmptyState(
                            icon = Icons.Filled.FactCheck,
                            title = "Выберите акт",
                            message = "Подсчёт откроется здесь.",
                        )
                    }
                }
            }
        } else if (selected != null) {
            BackHandler { selectedId = null }
            key(selected.actId) { AuditCountScreen(vm, selected, onBack = { selectedId = null }) }
        } else {
            AuditListScreen(vm, actsState, selectedId = null, onOpen = { selectedId = it.actId })
        }
    }
}

// ── Список актов ─────────────────────────────────────────────────────────────

@Composable
private fun AuditListScreen(
    vm: AuditViewModel,
    actsState: Loadable<List<AuditAct>>,
    selectedId: String?,
    onOpen: (AuditAct) -> Unit,
) {
    val saveError by vm.saveError.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Text(
            "Ревизия", color = Orda.colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg),
        )
        // Пересчёт, не сохранившийся при уходе с экрана: сказать об этом больше негде, а считать заново придётся.
        saveError?.let { message ->
            Box(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                OrdaCard {
                    Text("Последний пересчёт не сохранился", color = Orda.colors.text, fontWeight = FontWeight.SemiBold)
                    Text(message, color = Orda.colors.textMuted, fontSize = 12.sp)
                    Text(
                        "Понятно", color = Orda.colors.brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { vm.dismissSaveError() }.padding(vertical = Spacing.xs),
                    )
                }
            }
        }
        StateContainer(
            state = actsState,
            onRetry = vm::refresh,
            isEmpty = { it.isEmpty() },
            emptyIcon = Icons.Filled.Inventory,
            emptyTitle = "Ревизий нет",
            emptyMessage = "Пока вас не назначили ни на один акт. Ревизию открывает управляющий.",
        ) { acts ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                items(acts, key = { it.actId }, contentType = { "act" }) { act ->
                    ActCard(act, selected = act.actId == selectedId) { onOpen(act) }
                }
            }
        }
    }
}

@Composable
private fun ActCard(act: AuditAct, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radius.lg)
    Column(
        Modifier.fillMaxWidth()
            .background(Orda.colors.surface, shape)
            .border(if (selected) 2.dp else 1.dp, Orda.colors.accent.copy(alpha = if (selected) 1f else 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(act.locationName, color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(Icons.Filled.FactCheck, contentDescription = null, tint = Orda.colors.textMuted, modifier = Modifier.size(16.dp))
            Text(act.sectionLabel, color = Orda.colors.textMuted, fontSize = 14.sp)
        }
        if (!act.comment.isNullOrEmpty()) {
            Text(act.comment, color = Orda.colors.textDim, fontSize = 12.sp)
        }
    }
}

// ── Подсчёт ──────────────────────────────────────────────────────────────────

/**
 * Подсчёт позиций акта. Системного остатка на экране нет намеренно: счёт слепой,
 * иначе кассир начнёт подгонять под ожидаемое число.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuditCountScreen(listVm: AuditViewModel, act: AuditAct, onBack: (() -> Unit)?) {
    val state = operatorViewModel(key = act.actId) { _ -> AuditCountViewModel(listVm, act.actId) }
    val sheetState by state.sheet.collectAsStateWithLifecycle()
    val counts by state.counts.collectAsStateWithLifecycle()
    val unsaved by state.unsaved.collectAsStateWithLifecycle()
    val isSaving by state.isSaving.collectAsStateWithLifecycle()
    val savedMessage by state.savedMessage.collectAsStateWithLifecycle()
    val toast by state.toast.collectAsStateWithLifecycle()

    // Ввод переживает поворот и пересоздание: набранное число и открытая позиция не пропадают.
    var search by rememberSaveable { mutableStateOf("") }
    var onlyUncounted by rememberSaveable { mutableStateOf(false) }
    var showScanner by rememberSaveable { mutableStateOf(false) }
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var quantityText by rememberSaveable { mutableStateOf("") }
    // Что отсканировали и ждёт количества: скан открывает поле, как на сайте —
    // навёл, вписал число, «Готово», следующий товар.
    var scannedId by rememberSaveable { mutableStateOf<String?>(null) }
    var scanQuantity by rememberSaveable { mutableStateOf("") }
    val haptics = rememberHaptics()

    val sheet = sheetState.data
    val itemsById = remember(sheet) { sheet?.items?.associateBy { it.itemId }.orEmpty() }
    val pendingItem = pendingId?.let { itemsById[it] }
    val scannedItem = scannedId?.let { itemsById[it] }

    LaunchedEffect(toast) {
        val current = toast ?: return@LaunchedEffect
        delay(if (current.isError) 2500 else 1200)
        state.clearToast()
    }
    // Ушли с экрана — досылаем последнее число, не дожидаясь таймера.
    DisposableEffect(state) { onDispose { state.onLeave() } }

    fun closeScanned() {
        scannedId = null
        scanQuantity = ""
    }

    fun commitScanned(item: AuditItem) {
        val value = parseQuantity(scanQuantity)
        state.record(item.itemId, value)
        closeScanned()
        haptics.success()
        state.showToast("${item.name} — ${formatQuantity(value)}", false)
    }

    fun handleScan(code: String) {
        val needle = code.trim()
        val item = sheet?.items?.firstOrNull { (it.barcode ?: "") == needle } ?: run {
            if (sheet != null) {
                haptics.error()
                state.showToast("Штрихкод $needle не в вашей секции", true)
            }
            return
        }
        // Скан не прибавляет единицу сам, а открывает поле: для полки с двадцатью
        // четырьмя бутылками это единственный удобный способ. Введённое подставляем.
        haptics.tick()
        scannedId = item.itemId
        scanQuantity = counts[item.itemId]?.let { formatQuantity(it) } ?: ""
    }

    Box(Modifier.fillMaxSize().background(Orda.colors.background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().widthIn(max = FormMaxWidth).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Orda.colors.text) }
                } else {
                    Spacer(Modifier.width(Spacing.md))
                }
                Text(
                    if (onBack == null) act.locationName else "Подсчёт",
                    color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                )
            }

            StateContainer(
                state = sheetState,
                onRetry = state::retry,
                modifier = Modifier.weight(1f),
                isEmpty = { it.items.isEmpty() },
                emptyIcon = Icons.Filled.Inventory,
                emptyTitle = "В акте нет позиций",
            ) { loaded ->
                Column(Modifier.fillMaxSize()) {
                    val total = loaded.items.size
                    val counted = remember(loaded, counts) { loaded.items.count { counts[it.itemId] != null } }
                    ProgressHeader(act.sectionLabel, counted, total, loaded.isBlind, onlyUncounted) { onlyUncounted = it }

                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                        placeholder = { Text("Название или штрихкод") },
                        singleLine = true,
                        shape = RoundedCornerShape(Radius.md),
                    )

                    val text = search.trim().lowercase()
                    // Фильтр по названию не зависит от подсчётов; их учитываем лишь когда включён «непосчитанные».
                    val byText = remember(loaded, text) {
                        if (text.isEmpty()) loaded.items
                        else loaded.items.filter { it.name.lowercase().contains(text) || (it.barcode?.contains(text) ?: false) }
                    }
                    val visible = if (onlyUncounted) byText.filter { counts[it.itemId] == null } else byText
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                        items(visible, key = { it.itemId }, contentType = { "item" }) { item ->
                            ItemRow(item, counts[item.itemId], loaded.isBlind) {
                                pendingId = item.itemId
                                quantityText = counts[item.itemId]?.let { formatQuantity(it) } ?: ""
                            }
                            HorizontalDivider(color = Orda.colors.border)
                        }
                    }

                    Column(
                        Modifier.fillMaxWidth().background(Orda.colors.elevated).navigationBarsPadding().padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        if (unsaved.isNotEmpty()) {
                            Text("не сохранено ${unsaved.size} — уйдёт само или по кнопке", color = Orda.colors.warning, fontSize = 12.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                { showScanner = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(Radius.md),
                                border = BorderStroke(1.dp, Orda.colors.border),
                            ) {
                                Icon(Icons.Filled.QrCodeScanner, null, tint = Orda.colors.brandBright, modifier = Modifier.size(18.dp))
                                Text("Сканировать", color = Orda.colors.text, modifier = Modifier.padding(start = Spacing.xs))
                            }
                            Button(
                                onClick = { state.save() },
                                enabled = !isSaving && counts.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(Radius.md),
                                colors = ButtonDefaults.buttonColors(containerColor = Orda.colors.accent),
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                                } else {
                                    Text(savedMessage ?: "Сохранить", fontWeight = FontWeight.SemiBold, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
        ToastBanner(toast, Modifier.align(Alignment.TopCenter))
    }

    // Ввод количества вручную по нажатию на позицию.
    pendingItem?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { pendingId = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Orda.colors.background,
        ) {
            Column(
                Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(item.name, color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                item.barcode?.let { Text(it, color = Orda.colors.textDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                QuantityField(quantityText, { quantityText = it }, item.unit, Modifier.fillMaxWidth(), autoFocus = true)
                QuickAddRow(quantityText) { quantityText = it }
                PrimaryAccentButton("Записать") {
                    state.record(item.itemId, parseQuantity(quantityText))
                    pendingId = null
                }
                Spacer(Modifier.height(Spacing.lg))
            }
        }
    }

    // Сканер: камера, счётчик и поле количества поверх неё. Пока поле открыто, камера не ищет коды.
    if (showScanner) {
        Dialog(
            onDismissRequest = { closeScanned(); showScanner = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(Modifier.fillMaxSize().background(Orda.colors.background), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.fillMaxSize().widthIn(max = FormMaxWidth).imePadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                        Text("Сканирование", color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton({ closeScanned(); showScanner = false }) { Text("Готово", fontWeight = FontWeight.SemiBold) }
                    }
                    Column(Modifier.padding(horizontal = Spacing.lg)) {
                        AuditScannerPane(paused = scannedItem != null, onCode = ::handleScan)
                    }
                    sheet?.let { loaded ->
                        // Сколько осталось — единственное, что хочется знать, не выходя из камеры.
                        val counted = remember(loaded, counts) { loaded.items.count { counts[it.itemId] != null } }
                        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                            Text("посчитано $counted из ${loaded.items.size}", color = Orda.colors.textMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            if (unsaved.isNotEmpty()) Text("не сохранено ${unsaved.size}", color = Orda.colors.warning, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (scannedItem == null) {
                        Text(
                            "Наведите камеру на штрихкод — откроется поле для количества.",
                            color = Orda.colors.textDim, fontSize = 12.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.xl),
                        )
                    } else {
                        ScanQuantityPad(
                            item = scannedItem,
                            blind = sheet?.isBlind ?: false,
                            text = scanQuantity,
                            onText = { scanQuantity = it },
                            onClose = ::closeScanned,
                            onDone = { commitScanned(scannedItem) },
                        )
                    }
                }
                ToastBanner(toast, Modifier.align(Alignment.TopCenter).padding(top = Spacing.xxlTop))
            }
        }
    }
}

private val Spacing.xxlTop get() = 56.dp

@Composable
private fun ToastBanner(toast: AuditToast?, modifier: Modifier) {
    toast ?: return
    val tint = if (toast.isError) Orda.colors.negative else Orda.colors.positive
    Text(
        toast.text,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            .background(tint, RoundedCornerShape(Radius.md))
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    )
}

@Composable
private fun ProgressHeader(section: String, counted: Int, total: Int, blind: Boolean, onlyUncounted: Boolean, onOnly: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(section, color = Orda.colors.textMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("$counted из $total", color = Orda.colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else counted.toFloat() / total },
            modifier = Modifier.fillMaxWidth(),
            color = Orda.colors.accent,
            trackColor = Orda.colors.surfaceRaised,
        )
        // Ближе к концу вопрос один: что ещё осталось. Листать весь список — то же, что считать заново.
        if (counted in 1 until total) {
            FilterChip(
                selected = onlyUncounted,
                onClick = { onOnly(!onlyUncounted) },
                label = { Text("Только непосчитанные — ${total - counted}", fontSize = 12.sp) },
            )
        }
        if (blind) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Icon(Icons.Filled.VisibilityOff, null, tint = Orda.colors.textDim, modifier = Modifier.size(14.dp))
                Text("Слепой счёт: чужие цифры скрыты", color = Orda.colors.textDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ItemRow(item: AuditItem, counted: Double?, blind: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name, color = Orda.colors.text, maxLines = 2)
            if (!blind && item.otherQuantity != null) {
                Text("посчитал ${item.otherBy ?: "коллега"}: ${formatQuantity(item.otherQuantity)}", color = Orda.colors.info, fontSize = 12.sp)
            } else if (item.barcode != null) {
                Text(item.barcode, color = Orda.colors.textDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
        if (counted != null) {
            Text(formatQuantity(counted), color = Orda.colors.positive, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        } else {
            Icon(Icons.Filled.RadioButtonUnchecked, null, tint = Orda.colors.textDim)
        }
    }
}

/** Поле количества поверх камеры: название, крупное поле, коробки, «Готово». Остаток не показываем — счёт слепой. */
@Composable
private fun ScanQuantityPad(
    item: AuditItem,
    blind: Boolean,
    text: String,
    onText: (String) -> Unit,
    onClose: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(Spacing.md)
            .background(Orda.colors.surface, RoundedCornerShape(Radius.lg))
            .border(1.dp, Orda.colors.border, RoundedCornerShape(Radius.lg))
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(item.name, color = Orda.colors.text, fontWeight = FontWeight.SemiBold, maxLines = 2)
                item.barcode?.let { Text(it, color = Orda.colors.textDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }
            IconButton(onClose, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, "Закрыть", tint = Orda.colors.textDim) }
        }
        if (!blind && item.otherQuantity != null) {
            Text("уже посчитал ${item.otherBy ?: "коллега"}: ${formatQuantity(item.otherQuantity)}", color = Orda.colors.info, fontSize = 12.sp)
        }
        QuantityField(text, onText, item.unit, Modifier.fillMaxWidth(), autoFocus = true, onDone = onDone)
        QuickAddRow(text, onText)
        PrimaryAccentButton("Готово", onDone)
    }
}

@Composable
private fun QuantityField(
    value: String,
    onChange: (String) -> Unit,
    unit: String?,
    modifier: Modifier,
    autoFocus: Boolean,
    onDone: (() -> Unit)? = null,
) {
    val focus = remember { FocusRequester() }
    // Курсор ставим после появления поля, а не в момент скана: раньше его там нет.
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
        modifier = modifier.focusRequester(focus),
        placeholder = { Text("0", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), fontSize = 28.sp) },
        suffix = { Text(unit?.takeIf { it.isNotBlank() } ?: "шт", color = Orda.colors.textDim) },
        singleLine = true,
        textStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        shape = RoundedCornerShape(Radius.md),
    )
}

/** Коробки прибавляют к набранному: полку считают по частям. */
@Composable
private fun QuickAddRow(text: String, onText: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        quickAdd.forEach { step ->
            ChipButton("+$step") {
                onText(formatQuantity(maxOf(0.0, text.replace(',', '.').toDoubleOrNull() ?: 0.0) + step))
            }
        }
        ChipButton("сброс") { onText("") }
    }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

@Composable
private fun ChipButton(title: String, onClick: () -> Unit) {
    Text(
        title,
        color = Orda.colors.text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(Orda.colors.surfaceRaised, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    )
}

@Composable
private fun PrimaryAccentButton(title: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        colors = ButtonDefaults.buttonColors(containerColor = Orda.colors.accent),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(vertical = Spacing.xs))
    }
}
