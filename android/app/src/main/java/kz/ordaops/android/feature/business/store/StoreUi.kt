package kz.ordaops.android.feature.business.store

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kz.ordaops.android.feature.operator.audit.AuditScannerPane
import kz.ordaops.android.feature.operator.shift.ordaFieldColors
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Общие детали экранов магазина. Живут здесь, а не в ui/*: чужие пакеты не трогаем.
//
// Цвет по смыслу (веб-редизайн 27.10): rose — потеря, amber — ждёт человека,
// emerald — хорошо, sky — подсобка. Нули приглушаем: ноль — не событие.

/** Смысловые цвета магазина. */
object StoreTone {
    val loss: Color @Composable get() = Orda.colors.negative
    val waits: Color @Composable get() = Orda.colors.warning
    val good: Color @Composable get() = Orda.colors.positive
    val backroom: Color @Composable get() = Orda.colors.info
    val neutral: Color @Composable get() = Orda.colors.textDim
}

// ── Даты ─────────────────────────────────────────────────────────────────────

private val ruLocale = Locale("ru", "RU")
private val dayMonth = DateTimeFormatter.ofPattern("d MMM", ruLocale)
private val dayMonthTime = DateTimeFormatter.ofPattern("d MMM, HH:mm", ruLocale)
private val fullDate = DateTimeFormatter.ofPattern("d MMMM yyyy", ruLocale)

private fun zoned(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

fun fmtDay(millis: Long?): String = millis?.let { zoned(it).format(dayMonth) } ?: "—"
fun fmtDayTime(millis: Long?): String = millis?.let { zoned(it).format(dayMonthTime) } ?: "—"
fun fmtFullDate(millis: Long?): String = millis?.let { zoned(it).format(fullDate) } ?: "—"
fun fmtHourMinute(millis: Long?): String = millis?.let { zoned(it).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: ""

// ── Каркас ───────────────────────────────────────────────────────────────────

/** Фон экрана и место для тоста снизу. */
@Composable
fun StoreScaffold(toast: String?, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Orda.colors.background)) {
        content()
        ToastPill(toast, Modifier.align(Alignment.BottomCenter))
    }
}

/** Подтверждение внизу: окно закрылось, а без него не видно, записалось ли. */
@Composable
fun ToastPill(message: String?, modifier: Modifier = Modifier) {
    // Помним последний текст, чтобы пилюля уезжала с текстом, а не пустой.
    var last by remember { mutableStateOf("") }
    if (message != null) last = message
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier.navigationBarsPadding().padding(bottom = Spacing.xl),
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        Row(
            Modifier.background(Orda.colors.text, CircleShape).padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(Icons.Filled.CheckCircle, null, tint = Orda.colors.positive, modifier = Modifier.size(18.dp))
            Text(last, color = Orda.colors.background, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
        }
    }
}

/** Состояние тоста: показать на пару секунд и убрать. */
class ToastState {
    var message by mutableStateOf<String?>(null)
        private set
    private var token by mutableStateOf(0)

    fun show(text: String) {
        message = text
        token++
    }

    @Composable
    fun Effect() {
        LaunchedEffect(token) {
            if (message != null) {
                delay(2600)
                message = null
            }
        }
    }
}

@Composable
fun rememberToast(): ToastState = remember { ToastState() }.also { it.Effect() }

// ── Мелкие детали ────────────────────────────────────────────────────────────

/** Подпись состояния цветом — без плашки, плашка была шире названия и перетягивала взгляд. */
@Composable
fun StatusCaption(text: String, tint: Color) {
    Text(text, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

/** Компактная подсказка внутри карточки вместо полноэкранной пустоты. */
@Composable
fun InlineEmpty(icon: ImageVector, text: String, tint: Color = Orda.colors.textDim) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, color = Orda.colors.textMuted, fontSize = 14.sp)
    }
}

/** Чип фильтра — переключатель без рамки вокруг всего ряда. */
@Composable
fun StoreChip(title: String, on: Boolean, tint: Color = Orda.colors.brand, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Text(
        title,
        color = if (on) tint else Orda.colors.textMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) tint.copy(alpha = 0.16f) else Orda.colors.surfaceRaised, CircleShape)
            .pressScale()
            .clickable {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    )
}

/** Ряд чипов с прокруткой вбок. */
@Composable
fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) { content() }
}

/** Круглая буква товара: заканчивающийся виден по красному кружку издалека. */
@Composable
fun ItemBadge(name: String, tint: Color = Orda.colors.brand, size: Int = 40) {
    Box(Modifier.size(size.dp).background(tint.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), color = tint, fontSize = (size * 0.4f).sp, fontWeight = FontWeight.Bold)
    }
}

/** Строка остатка: имя слева, количество справа, порог подсказкой. */
@Composable
fun StockRow(
    name: String,
    quantity: Double,
    unit: String,
    threshold: Double?,
    caption: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val low = threshold != null && threshold > 0 && quantity <= threshold
    val tint = if (low) StoreTone.loss else Orda.colors.brand
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        ItemBadge(name, tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (low && threshold != null) {
                Text("заканчивается · порог ${formatQty(threshold)} $unit", color = StoreTone.loss, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            } else if (caption != null) {
                Text(caption, color = Orda.colors.textDim, fontSize = 13.sp)
            }
        }
        // Ноль приглушаем: ноль — не событие, а красным его красят только когда он потеря.
        Text(
            "${formatQty(quantity)} ${unit.lowercase()}",
            color = if (low) StoreTone.loss else if (quantity == 0.0) Orda.colors.textDim else Orda.colors.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        trailing()
    }
}

/** Тонкий разделитель строк. */
@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 52.dp).height(1.dp).background(Orda.colors.border.copy(alpha = 0.6f)))
}

/** Белый блок-карточка со скруглением. */
@Composable
fun StoreCard(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Orda.colors.surface, RoundedCornerShape(24.dp))
            .let { if (accent != null) it.background(accent.copy(alpha = 0.05f), RoundedCornerShape(24.dp)) else it }
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) { content() }
}

/** Пара «подпись — значение» в карточке. */
@Composable
fun StatLine(label: String, value: String, valueColor: Color = Orda.colors.text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Orda.colors.textDim, fontSize = 14.sp)
        Text(value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Заголовок секции внутри экрана со ссылкой справа. */
@Composable
fun SectionTitle(title: String, subtitle: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Orda.colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = Orda.colors.textDim, fontSize = 13.sp)
        }
        if (action != null && onAction != null) {
            Text(
                action,
                color = Orda.colors.brand,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onAction).padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            )
        }
    }
}

// ── Поля ─────────────────────────────────────────────────────────────────────

/** Поиск по названию или штрихкоду; с камерой, если передан [onScan]. */
@Composable
fun StoreSearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Название или штрихкод", onScan: (() -> Unit)? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = Orda.colors.textDim) },
        trailingIcon = {
            Row {
                if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, "Очистить", tint = Orda.colors.textDim) }
                if (onScan != null) IconButton(onClick = onScan) { Icon(Icons.Filled.QrCodeScanner, "Сканировать", tint = Orda.colors.brand) }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(),
        shape = RoundedCornerShape(Radius.md),
        colors = ordaFieldColors(),
    )
}

/** Число (количество, цена): цифры и запятая; пустое поле — не ноль. */
@Composable
fun NumberInput(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, suffix: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() || it == ',' || it == '.' }) },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        suffix = suffix?.let { { Text(it, color = Orda.colors.textDim) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = RoundedCornerShape(Radius.md),
        colors = ordaFieldColors(),
    )
}

/** Текстовое поле формы. */
@Composable
fun TextInput(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(Radius.md),
        colors = ordaFieldColors(),
    )
}

/** Выбор из списка: поле-кнопка и выпадающее меню. */
@Composable
fun <T> PickerField(label: String, selected: T?, options: List<T>, title: (T) -> String, placeholder: String = "Не выбрано", onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(Orda.colors.surfaceRaised)
                .clickable { open = true }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            Text(label, color = Orda.colors.textDim, fontSize = 12.sp)
            Text(
                selected?.let(title) ?: placeholder,
                color = if (selected == null) Orda.colors.textDim else Orda.colors.text,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(title(option)) }, onClick = {
                    open = false
                    onSelect(option)
                })
            }
        }
    }
}

/** Дата `yyyy-MM-dd` с выбором в календаре. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateInput(label: String, value: String, onChange: (String) -> Unit, maxToday: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val parsed = runCatching { LocalDate.parse(value) }.getOrNull()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(Orda.colors.surfaceRaised)
            .clickable { open = true }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Text(label, color = Orda.colors.textDim, fontSize = 12.sp)
        Text(parsed?.format(fullDate) ?: "Выберите дату", color = Orda.colors.text, fontSize = 16.sp)
    }
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (parsed ?: LocalDate.now()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !maxToday || Instant.ofEpochMilli(utcTimeMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate() <= LocalDate.now()
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } },
        ) { DatePicker(state = state) }
    }
}

// ── Камера ───────────────────────────────────────────────────────────────────

/**
 * Окно со сканером штрихкодов. Код принят — окно закрывается: одно сканирование
 * на один вопрос («где этот товар», «добавить в акт»). Непрерывное сканирование
 * ревизии устроено отдельно (`StocktakeScreen`).
 */
@Composable
fun ScanDialog(onCode: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Orda.colors.background).navigationBarsPadding().padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Сканер штрихкода", color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Закрыть", tint = Orda.colors.text) }
            }
            AuditScannerPane(paused = false, onCode = { code ->
                onCode(code)
                onDismiss()
            })
        }
    }
}

/** Ошибка над формой. */
@Composable
fun FormError(message: String?) {
    if (message != null) kz.ordaops.android.ui.components.ErrorBanner(message)
}

/** Подсказка «что мешает отправить» — жёлтым, а не красным: это не ошибка, а недозаполненность. */
@Composable
fun BlockerHint(message: String?) {
    if (message != null) Text(message, color = StoreTone.waits, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
}
