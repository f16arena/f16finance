package kz.ordaops.android.feature.business.ops

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.operator.money.SectionLabel
import kz.ordaops.android.feature.operator.money.TintedIcon
import kz.ordaops.android.feature.operator.shift.ordaFieldColors
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// Общие детали разделов «Операции / Касса / Общение / Система»: загрузчик
// списка, отправка формы, поля листов. Живут в своём пакете — чужие не трогаем.

// ── Данные ───────────────────────────────────────────────────────────────────

/**
 * Загрузчик одного GET-запроса: «сначала кэш, потом сеть», без сброса показанного.
 * Разбор — функция [parse] от корневого объекта ответа; ключи читаем терпимо
 * (см. [str], [num]).
 */
class OpsLoader<T>(
    private val api: ApiClient,
    private val request: ApiRequest,
    private val parse: (JsonObject) -> T,
) : ViewModel() {
    private val _state = MutableStateFlow(Loadable<T>())
    val state: StateFlow<Loadable<T>> = _state.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.load(
                cached = { api.cached<JsonObject>(request)?.let(parse) },
                fresh = { parse(api.send<JsonObject>(request)) },
            )
        }
    }
}

/** Загрузчик экрана с первой загрузкой при входе; [key] отделяет разные запросы одного класса. */
@Composable
fun <T> rememberOpsLoader(key: String, request: ApiRequest, parse: (JsonObject) -> T): OpsLoader<T> {
    val env = LocalBusiness.current
    val loader: OpsLoader<T> = viewModel(
        key = "ops:$key:${env.api.organizationId}",
        factory = viewModelFactory { initializer { OpsLoader(env.api, request, parse) } },
    )
    androidx.compose.runtime.LaunchedEffect(loader) { loader.refresh() }
    return loader
}

/**
 * Отправка формы: пока идёт — [busy], при отказе — [error] человеческим текстом.
 * Живёт в композиции листа, поэтому закрытый лист не держит запрос.
 */
class OpsSubmit(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    fun run(block: suspend () -> Unit, onOk: () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
                busy = false
                onOk()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy = false
                error = e.userMessage()
            }
        }
    }
}

@Composable
fun rememberOpsSubmit(): OpsSubmit {
    val scope = rememberCoroutineScope()
    return remember { OpsSubmit(scope) }
}

// ── Даты ─────────────────────────────────────────────────────────────────────

/** `2026-10-27` → `27 окт.`; пусто, если разобрать нельзя. */
fun shortDay(iso: String?): String? {
    val date = parseDay(iso) ?: return null
    return "${date.dayOfMonth} ${MONTHS_SHORT[date.monthValue - 1]}"
}

/** `2026-10-27` → `27 октября`. */
fun longDay(iso: String?): String? {
    val date = parseDay(iso) ?: return null
    return "${date.dayOfMonth} ${MONTHS_GEN[date.monthValue - 1]}"
}

/** Дата и время из ISO-строки сервера: `27 окт., 14:05` (по часовому поясу телефона). */
fun dateTimeLabel(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val instant = runCatching { Instant.parse(iso) }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrNull()
        ?: return shortDay(iso)
    val local = instant.atZone(java.time.ZoneId.systemDefault())
    return "${local.dayOfMonth} ${MONTHS_SHORT[local.monthValue - 1]}, %02d:%02d".format(local.hour, local.minute)
}

private val MONTHS_SHORT = listOf("янв.", "февр.", "мар.", "апр.", "мая", "июн.", "июл.", "авг.", "сент.", "окт.", "нояб.", "дек.")
private val MONTHS_GEN = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

// ── Поля листов ──────────────────────────────────────────────────────────────

/** Текстовое поле листа в фирменных цветах. */
@Composable
fun OpsField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    suffix: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        suffix = suffix?.let { { Text(it, color = Orda.colors.textDim) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else 6,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(Radius.md),
        colors = ordaFieldColors(),
    )
}

/** Выбор одного из списка: поле с меню. `options` — пары «значение → подпись». */
@Composable
fun OpsPicker(
    label: String,
    options: List<Pair<String, String>>,
    value: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val title = options.firstOrNull { it.first == value }?.second ?: ""
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Orda.colors.surfaceRaised, RoundedCornerShape(Radius.md))
                .clickable { open = true }
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = Orda.colors.textDim, fontSize = 12.sp)
                Text(title.ifEmpty { "—" }, color = Orda.colors.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.ArrowDropDown, null, tint = Orda.colors.textDim)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onSelect(id) })
            }
        }
    }
}

/** Поле даты: пустое или `yyyy-MM-dd`; крестик очищает. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpsDateField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .background(Orda.colors.surfaceRaised, RoundedCornerShape(Radius.md))
            .clickable { open = true }
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CalendarMonth, null, tint = Orda.colors.brand, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = Spacing.md)) {
            Text(label, color = Orda.colors.textDim, fontSize = 12.sp)
            Text(longDay(value) ?: "Не выбрана", color = Orda.colors.text, fontSize = 16.sp)
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onChange("") }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, "Очистить", tint = Orda.colors.textDim, modifier = Modifier.size(18.dp))
            }
        }
    }
    if (open) {
        val initial = parseDay(value)?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        onChange(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    open = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } },
        ) { DatePicker(state = state) }
    }
}

/** Строка с переключателем. */
@Composable
fun OpsSwitchRow(title: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Orda.colors.text, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = Orda.colors.textDim, fontSize = 13.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Orda.colors.brand, checkedThumbColor = Color.White),
        )
    }
}

/** Подтверждение необратимого действия. */
@Composable
fun OpsConfirm(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = Orda.colors.negative) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Круглая кнопка «+» в углу экрана — вместо тулбара: экран живёт и во вкладке без шапки. */
@Composable
fun BoxScopeFab(modifier: Modifier = Modifier, icon: ImageVector = Icons.Filled.Add, description: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        modifier
            .padding(Spacing.lg)
            .size(56.dp)
            .pressScale()
            .background(Orda.colors.brand, CircleShape)
            .clickable { haptics.tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

/** Поле поиска над списком. */
@Composable
fun OpsSearchField(value: String, onChange: (String) -> Unit, placeholder: String = "Поиск") {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Orda.colors.surfaceRaised, RoundedCornerShape(Radius.md))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(Icons.Filled.Search, null, tint = Orda.colors.textDim, modifier = Modifier.size(20.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Orda.colors.text, fontSize = 16.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Orda.colors.brand),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, color = Orda.colors.textDim, fontSize = 16.sp)
                inner()
            },
        )
        if (value.isNotEmpty()) {
            Icon(Icons.Filled.Close, "Очистить", tint = Orda.colors.textDim, modifier = Modifier.size(18.dp).clickable { onChange("") })
        }
    }
}

/** Строка списка: значок в кружке, заголовок с подписью и правый край. */
@Composable
fun OpsRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    trailingColor: Color = Orda.colors.text,
    trailingSub: String? = null,
    strike: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.pressScale().clickable(onClick = onClick) else it }
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        TintedIcon(icon, tint, 40, corner = 20.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = if (strike) Orda.colors.textDim else Orda.colors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(trailing, color = trailingColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (trailingSub != null) Text(trailingSub, color = Orda.colors.textDim, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/** Белая секция со своим заголовком и необязательной подписью справа. */
@Composable
fun OpsSection(title: String, modifier: Modifier = Modifier, count: String? = null, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Orda.colors.surface, RoundedCornerShape(24.dp))
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Orda.colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (count != null) SectionLabel(count)
        }
        content()
    }
}

/** «1 задача», «3 задачи», «5 задач». */
fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

/** Пустой корень для экранов, которым нужна только заливка фона. */
@Composable
fun OpsBox(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Orda.colors.background), content = content)
}

/** Сегодня по часам телефона в виде `yyyy-MM-dd`. */
fun todayIso(): String = LocalDate.now().toString()

/** `+1 500 ₸` / `−1 500 ₸` — сумма со знаком, как `Money.signed`. */
fun signedMoney(value: Double): String {
    val text = kz.ordaops.android.ui.components.money(kotlin.math.abs(value))
    return when {
        value > 0 -> "+$text"
        value < 0 -> "−$text"
        else -> text
    }
}

/** Баллы без хвоста `.0`: `120`, `12,5`. */
fun pointsText(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(java.util.Locale("ru"), value)

/** Сумма из поля: запятая как точка, мусор — ноль. */
fun parseAmountInput(text: String): Double = text.replace(',', '.').trim().toDoubleOrNull() ?: 0.0
