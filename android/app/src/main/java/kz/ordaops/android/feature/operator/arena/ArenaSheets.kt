package kz.ordaops.android.feature.operator.arena

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun java.time.Instant.timeText(): String = timeFormat.format(this)

/** Число из поля ввода: запятая и пробелы не должны ломать сумму. */
private fun String.amount(): Double = replace(',', '.').replace(" ", "").toDoubleOrNull() ?: 0.0

// ── Каркас листа ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Orda.colors.background,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(title, color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            content()
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = Orda.colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ErrorText(text: String?) {
    if (text != null) ErrorBanner(text)
}

@Composable
private fun SubmitButton(title: String, enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        colors = ButtonDefaults.buttonColors(containerColor = Orda.colors.brand),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.padding(vertical = 4.dp).size(18.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = Spacing.xs))
        }
    }
}

@Composable
private fun SecondaryButton(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
    ) {
        Text(title, color = if (enabled) Orda.colors.text else Orda.colors.textDim, modifier = Modifier.padding(vertical = Spacing.xs))
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, big: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
        modifier = modifier,
        placeholder = { Text("0") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = if (big) 24.sp else 18.sp, fontWeight = FontWeight.SemiBold),
        shape = RoundedCornerShape(Radius.md),
    )
}

/** Переключатель из нескольких вариантов — `Picker(.segmented)`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label(option), fontSize = 13.sp) }
        }
    }
}

@Composable
private fun TariffRow(tariff: ArenaTariff, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tariff.name, color = Orda.colors.text)
            Text(subtitle, color = Orda.colors.textMuted, fontSize = 12.sp)
        }
        Text(money(tariff.price), color = Orda.colors.textDim, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = Spacing.sm))
        Icon(
            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) Orda.colors.brand else Orda.colors.textMuted,
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Text(label, color = Orda.colors.textMuted, modifier = Modifier.weight(1f))
        Text(value, color = Orda.colors.text, fontWeight = FontWeight.Medium)
    }
}

// ── Посадить гостя ───────────────────────────────────────────────────────────

/**
 * Тариф и способ оплаты — всё, что нужно спросить у стойки. Сумму по наличным и
 * Kaspi считает сервер по цене тарифа; руками делят только смешанную оплату.
 */
@Composable
internal fun ArenaStartSheet(store: ArenaStore, station: ArenaStation, onDismiss: () -> Unit) {
    val tariffs = store.hall?.tariffs(station).orEmpty()
    var tariffId by remember { mutableStateOf(tariffs.firstOrNull()?.id) }
    var payment by remember { mutableStateOf(ArenaPayment.Cash) }
    var cash by remember { mutableStateOf("") }
    var kaspi by remember { mutableStateOf("") }
    var discount by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val tariff = tariffs.firstOrNull { it.id == tariffId }
    // Та же формула скидки, что на сервере.
    val price = tariff?.let { Math.round(it.price * (1 - discount / 100.0)).toDouble() } ?: 0.0
    val canStart = tariff != null && !saving && (payment != ArenaPayment.Mixed || cash.amount() + kaspi.amount() > 0)

    Sheet("Посадить гостя", onDismiss) {
        OrdaCard {
            FieldLabel("Станция")
            Text(station.name, color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        OrdaCard {
            FieldLabel("Тариф")
            if (tariffs.isEmpty()) {
                Text(
                    "Сейчас нет доступных тарифов. Ночные и дневные пакеты предлагаются только в своё время.",
                    color = Orda.colors.textMuted, fontSize = 14.sp,
                )
            } else {
                tariffs.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = Orda.colors.border)
                    TariffRow(item, item.windowLabel ?: item.durationLabel, tariffId == item.id) { tariffId = item.id }
                }
            }
        }
        if (tariff != null) {
            PaymentCard(payment, { payment = it }, cash, { cash = it }, kaspi, { kaspi = it }, price)
            OrdaCard {
                FieldLabel("Скидка")
                Segmented(listOf(0, 10, 20, 30), discount, { if (it == 0) "нет" else "$it%" }) { discount = it }
            }
        }
        ErrorText(error)
        SubmitButton(if (price > 0) "Запустить · ${money(price)}" else "Запустить", canStart, saving) {
            val t = tariff ?: return@SubmitButton
            scope.launch {
                saving = true
                error = null
                val failure = store.start(station.id, t.id, payment, cash.amount(), kaspi.amount(), discount.toDouble())
                saving = false
                if (failure != null) error = failure else onDismiss()
            }
        }
    }
}

// ── Открытая сессия ──────────────────────────────────────────────────────────

/** Сколько осталось и что можно сделать. Деньги двигаем только со своей открытой сменой. */
@Composable
internal fun ArenaSessionSheet(store: ArenaStore, station: ArenaStation, onDismiss: () -> Unit) {
    val session = store.hall?.session(station.id)
    val now = store.nowMillis
    val canSell = store.isMyShift
    val scope = rememberCoroutineScope()
    var extending by remember { mutableStateOf(false) }
    var confirmingEnd by remember { mutableStateOf(false) }
    var confirmingRefund by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun finish(refund: Boolean) {
        val id = session?.id ?: return
        scope.launch {
            saving = true
            error = null
            val failure = if (refund) store.refund(id) else store.end(id)
            saving = false
            if (failure != null) error = failure else onDismiss()
        }
    }

    Sheet("Сессия", onDismiss) {
        if (session == null) {
            OrdaCard { Text("Сессия уже закрыта.", color = Orda.colors.textMuted, fontSize = 14.sp) }
            return@Sheet
        }
        val expired = session.isExpired(now)
        val shape = RoundedCornerShape(Radius.lg)
        Column(
            Modifier.fillMaxWidth()
                .background(Orda.colors.surface, shape)
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(station.name, color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                countdownText(session.remaining(now)),
                color = if (expired) Orda.colors.negative else Orda.colors.text,
                fontSize = 36.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            )
            Text(if (expired) "время вышло" else "осталось", color = Orda.colors.textMuted, fontSize = 12.sp)
        }
        OrdaCard {
            session.startedAt?.let { StatRow("Начало", it.timeText()) }
            session.endsAt?.let { StatRow("Конец", it.timeText()) }
            StatRow("Оплачено", money(session.amount))
            if (session.cashAmount > 0 && session.kaspiAmount > 0) {
                StatRow("Чем", "нал ${money(session.cashAmount)} · Kaspi ${money(session.kaspiAmount)}")
            }
        }
        ErrorText(error)
        if (!canSell) {
            AccentCard(Orda.colors.warning) {
                Text(
                    if (store.isSomeoneElsesShift) {
                        "Смену на точке ведёт другой оператор — продлевает и закрывает он."
                    } else {
                        "Смена не открыта: продлить и завершить нельзя, деньги некуда записать."
                    },
                    color = Orda.colors.textMuted, fontSize = 14.sp,
                )
            }
        }
        SubmitButton("Продлить", canSell && !saving, false) { extending = true }
        SecondaryButton("Завершить", enabled = canSell && !saving) { confirmingEnd = true }
        // Возврат отдельной кнопкой и с подтверждением: он списывает деньги из
        // кассы, и промахнуться по нему вместо «завершить» никто не должен.
        if (!expired) {
            SecondaryButton("Завершить с возвратом", enabled = canSell && !saving) { confirmingRefund = true }
        }

        if (extending) ArenaExtendSheet(store, station, session, onDismiss = { extending = false })
        if (confirmingEnd) {
            AlertDialog(
                onDismissRequest = { confirmingEnd = false },
                title = { Text("Завершить сессию?") },
                text = { Text("Станция освободится. Деньги за оплаченное время останутся в кассе.") },
                confirmButton = { TextButton({ confirmingEnd = false; finish(false) }) { Text("Завершить", color = Orda.colors.negative) } },
                dismissButton = { TextButton({ confirmingEnd = false }) { Text("Отмена") } },
            )
        }
        if (confirmingRefund) {
            AlertDialog(
                onDismissRequest = { confirmingRefund = false },
                title = { Text("Вернуть за неиспользованное?") },
                text = { Text("Сервер посчитает долю неотсиженного времени и вычтет её из кассы.") },
                confirmButton = { TextButton({ confirmingRefund = false; finish(true) }) { Text("Вернуть", color = Orda.colors.negative) } },
                dismissButton = { TextButton({ confirmingRefund = false }) { Text("Отмена") } },
            )
        }
    }
}

// ── Продление ────────────────────────────────────────────────────────────────

/** Пакетом тарифа или на сумму; минуты по сумме считает сервер по цене часа. */
@Composable
private fun ArenaExtendSheet(store: ArenaStore, station: ArenaStation, session: ArenaSession, onDismiss: () -> Unit) {
    val tariffs = store.hall?.tariffs(station).orEmpty()
    var byTariff by remember { mutableStateOf(true) }
    var tariffId by remember { mutableStateOf(tariffs.firstOrNull()?.id) }
    var payment by remember { mutableStateOf(ArenaPayment.Cash) }
    var cash by remember { mutableStateOf("") }
    var kaspi by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val tariff = tariffs.firstOrNull { it.id == tariffId }
    val total = if (byTariff) tariff?.price ?: 0.0 else amount.amount()
    val canSubmit = !saving && if (byTariff) tariff != null else amount.amount() > 0

    Sheet("Продление", onDismiss) {
        OrdaCard {
            FieldLabel("Как продлеваем")
            Segmented(listOf(true, false), byTariff, { if (it) "Пакетом" else "На сумму" }) { byTariff = it }
            Text(
                if (byTariff) "Добавит время пакета к текущему окончанию." else "Минуты сервер посчитает сам по цене часа этой зоны.",
                color = Orda.colors.textMuted, fontSize = 12.sp,
            )
        }
        if (byTariff) {
            OrdaCard {
                FieldLabel("Пакет")
                tariffs.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = Orda.colors.border)
                    TariffRow(item, item.durationLabel, tariffId == item.id) { tariffId = item.id }
                }
            }
        } else {
            OrdaCard {
                FieldLabel("Сумма")
                NumberField(amount, { amount = it }, Modifier.fillMaxWidth(), big = true)
            }
        }
        PaymentCard(payment, { payment = it }, cash, { cash = it }, kaspi, { kaspi = it }, total)
        ErrorText(error)
        SubmitButton(if (total > 0) "Продлить · ${money(total)}" else "Продлить", canSubmit, saving) {
            scope.launch {
                saving = true
                error = null
                // Продление на сумму: сервер делит именно оплаченное, поэтому при
                // наличных и Kaspi кладём сумму в нужное поле сами.
                val failure = if (byTariff && tariff != null) {
                    store.extend(session.id, tariff.id, payment, cash.amount(), kaspi.amount())
                } else {
                    val value = amount.amount()
                    store.extendByAmount(
                        session.id, payment,
                        cash = when (payment) { ArenaPayment.Kaspi -> 0.0; ArenaPayment.Cash -> value; else -> cash.amount() },
                        kaspi = when (payment) { ArenaPayment.Cash -> 0.0; ArenaPayment.Kaspi -> value; else -> kaspi.amount() },
                    )
                }
                saving = false
                if (failure != null) error = failure else onDismiss()
            }
        }
    }
}

// ── Техпроблема ──────────────────────────────────────────────────────────────

private val techPresets = listOf("Не работает мышь", "Не работает клавиатура", "Нет звука", "Не включается")

/** Мышь, наушники, сгоревший монитор — это расход и повод не сажать сюда гостя. */
@Composable
internal fun ArenaTechSheet(store: ArenaStore, stations: List<ArenaStation>, onDismiss: () -> Unit) {
    var stationId by remember { mutableStateOf<String?>(null) }
    var reason by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val canSubmit = reason.trim().length >= 3 && !saving

    Sheet("Техпроблема", onDismiss) {
        OrdaCard {
            FieldLabel("Станция")
            Column {
                OutlinedButton({ menu = true }, shape = RoundedCornerShape(Radius.md)) {
                    Text(stations.firstOrNull { it.id == stationId }?.name ?: "Не про станцию", color = Orda.colors.text)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Не про станцию") }, onClick = { stationId = null; menu = false })
                    stations.forEach { s ->
                        DropdownMenuItem(text = { Text(s.name) }, onClick = { stationId = s.id; menu = false })
                    }
                }
            }
        }
        OrdaCard {
            FieldLabel("Что случилось")
            // Заготовки — частые поломки одним нажатием.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                techPresets.forEach { preset ->
                    val on = reason == preset
                    Text(
                        preset,
                        color = if (on) Orda.colors.brand else Orda.colors.textDim,
                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .background(if (on) Orda.colors.brand.copy(alpha = 0.16f) else Orda.colors.surfaceRaised, RoundedCornerShape(50))
                            .clickable { reason = preset }
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    )
                }
            }
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Опишите") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(Radius.md),
            )
        }
        OrdaCard {
            FieldLabel("Расход, если был")
            NumberField(amount, { amount = it }, Modifier.fillMaxWidth(), big = true)
        }
        ErrorText(error)
        SubmitButton("Записать", canSubmit, saving) {
            scope.launch {
                saving = true
                error = null
                val failure = store.logTech(stations.firstOrNull { it.id == stationId }, reason.trim(), amount.amount())
                saving = false
                if (failure != null) error = failure else onDismiss()
            }
        }
    }
}

// ── Оплата ───────────────────────────────────────────────────────────────────

/**
 * Способ оплаты и — только для смешанной — разбивка сумм. При наличных и Kaspi
 * суммы не спрашиваем: их считает сервер по цене тарифа, лишнее поле означало бы
 * возможность разойтись с прайсом.
 */
@Composable
private fun PaymentCard(
    payment: ArenaPayment,
    onPayment: (ArenaPayment) -> Unit,
    cash: String,
    onCash: (String) -> Unit,
    kaspi: String,
    onKaspi: (String) -> Unit,
    total: Double,
) {
    OrdaCard {
        FieldLabel("Оплата")
        Segmented(ArenaPayment.entries, payment, { it.title }, onPayment)
        if (payment == ArenaPayment.Mixed) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Column(Modifier.weight(1f)) {
                    Text("Наличные", color = Orda.colors.textMuted, fontSize = 12.sp)
                    NumberField(cash, onCash, Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1f)) {
                    Text("Kaspi", color = Orda.colors.textMuted, fontSize = 12.sp)
                    NumberField(kaspi, onKaspi, Modifier.fillMaxWidth())
                }
            }
            val split = cash.amount() + kaspi.amount()
            if (total > 0) {
                Text(
                    if (split == total) "Сходится: ${money(split)}" else "Введено ${money(split)} из ${money(total)}",
                    color = if (split == total) Orda.colors.positive else Orda.colors.warning,
                    fontSize = 12.sp,
                )
            }
        } else if (total > 0) {
            Text(
                "К оплате ${money(total)} — сумму запишет сервер по цене тарифа.",
                color = Orda.colors.textMuted, fontSize = 12.sp,
            )
        }
    }
}

// ── Что было сегодня ─────────────────────────────────────────────────────────

/**
 * Оператор заступает в середине дня: сколько сдали до него, какую станцию
 * чинили — он не знает, а спрашивают с него.
 */
@Composable
internal fun ArenaHistorySheet(hall: ArenaHall, onDismiss: () -> Unit) {
    Sheet("Сегодня в зале", onDismiss) {
        OrdaCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Касса зала за сегодня", color = Orda.colors.textMuted, fontSize = 12.sp)
                    Text(money(hall.todayTotal), color = Orda.colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("нал ${money(hall.todayCash)}", color = Orda.colors.textDim, fontSize = 12.sp)
                    Text("Kaspi ${money(hall.todayKaspi)}", color = Orda.colors.textDim, fontSize = 12.sp)
                }
            }
        }

        if (hall.todayRows.isEmpty()) {
            OrdaCard { Text("Оплат сегодня ещё не было.", color = Orda.colors.textMuted, fontSize = 14.sp) }
        } else {
            FieldLabel("Оплаты · ${hall.todayRows.size}")
            OrdaCard {
                // Свежие сверху: спрашивают почти всегда про последние.
                hall.todayRows.sortedByDescending { it.at?.toEpochMilli() ?: 0L }.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = Orda.colors.border)
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(row.comment, color = Orda.colors.text, fontSize = 14.sp)
                            row.at?.let { Text(it.timeText(), color = Orda.colors.textDim, fontSize = 12.sp) }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(money(row.total), color = Orda.colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            // Чем платили — видно только когда делили: иначе строка шумит.
                            if (row.cash > 0 && row.kaspi > 0) {
                                Text("нал ${money(row.cash)} · Kaspi ${money(row.kaspi)}", color = Orda.colors.textDim, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        if (hall.techLogs.isNotEmpty()) {
            FieldLabel("Поломки · ${hall.techLogs.size}")
            AccentCard(Orda.colors.warning) {
                hall.techLogs.forEachIndexed { index, log ->
                    if (index > 0) HorizontalDivider(color = Orda.colors.border)
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(log.reason, color = Orda.colors.text, fontSize = 14.sp)
                            Text(
                                listOfNotNull(log.stationName, log.at?.timeText()).joinToString(" · "),
                                color = Orda.colors.textDim, fontSize = 12.sp,
                            )
                        }
                        if (log.amount > 0) {
                            Text(money(log.amount), color = Orda.colors.warning, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
