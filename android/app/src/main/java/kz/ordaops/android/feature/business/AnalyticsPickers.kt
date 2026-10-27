package kz.ordaops.android.feature.business

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

// Выбор периода, точек и сравнения для аналитики. Порт PeriodBar.swift,
// AnalyticsPickerSheets.swift и ExtraCashToggle.swift.

/** Сервер считает не больше 400 дней за раз — дальше даты не предлагаем. */
private const val MAX_RANGE_DAYS = 399L

/**
 * Выбор периода для аналитического экрана. Быстрые кнопки — то, что берут чаще
 * всего; календарь — все готовые периоды и свои даты. Под кнопками — точные даты:
 * «месяц» без дат непонятно, календарный он или последние тридцать дней.
 */
@Composable
fun PeriodBar(
    selection: AnalyticsPeriod,
    quick: List<AnalyticsPeriod>,
    onSelect: (AnalyticsPeriod) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    extraCash: (@Composable () -> Unit)? = null,
) {
    var showPicker by remember { mutableStateOf(false) }
    // Выбор вне быстрых кнопок — подсвечиваем календарь.
    val isOther = selection !in quick
    val c = Orda.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PillSegment(
                options = quick.map { it to it.shortTitle },
                selection = if (isOther) null else selection,
                modifier = Modifier.weight(1f),
                onSelect = onSelect,
            )
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(44.dp)
                    .pressScale()
                    .clip(CircleShape)
                    .background(if (isOther) c.brand else c.surfaceRaised, CircleShape)
                    .clickable { showPicker = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.CalendarMonth, "Выбрать даты", tint = if (isOther) androidx.compose.ui.graphics.Color.White else c.text, modifier = Modifier.size(22.dp))
            }
        }
        Row(Modifier.padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isOther) {
                Text(if (selection.isCustom) "Свои даты" else selection.title, color = c.brand, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("·", color = c.textDim, fontSize = 13.sp)
            }
            val (from, to) = selection.bounds()
            Text(AnalyticsPeriod.rangeLabel(from, to), color = c.textDim, fontSize = 13.sp)
            if (trailing != null) {
                Text("·", color = c.textDim, fontSize = 13.sp)
                trailing()
            }
        }
        extraCash?.invoke()
    }
    if (showPicker) {
        PeriodPickerSheet(selection, onDismiss = { showPicker = false }) {
            onSelect(it)
            showPicker = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodPickerSheet(selection: AnalyticsPeriod, onDismiss: () -> Unit, onPick: (AnalyticsPeriod) -> Unit) {
    val c = Orda.colors
    val (from, to) = selection.bounds()
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = LocalDate.parse(from).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        initialSelectedEndDateMillis = LocalDate.parse(to).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
    )
    var custom by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.elevated,
    ) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text("Период", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm))
            if (!custom) {
                AnalyticsPeriod.presets.forEach { preset ->
                    val (pf, pt) = preset.bounds()
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(preset) }.padding(horizontal = Spacing.lg, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(preset.title, color = c.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(AnalyticsPeriod.rangeLabel(pf, pt), color = c.textDim, fontSize = 13.sp)
                        if (preset == selection) Icon(Icons.Filled.Check, null, tint = c.brand, modifier = Modifier.padding(start = Spacing.sm).size(18.dp))
                    }
                    HorizontalDivider(color = c.border)
                }
                TextButton(onClick = { custom = true }, modifier = Modifier.padding(horizontal = Spacing.sm)) {
                    Text("Свои даты…", fontWeight = FontWeight.SemiBold)
                }
            } else {
                DateRangePicker(state = state, modifier = Modifier.height(520.dp), showModeToggle = false, title = null, headline = null)
                val start = state.selectedStartDateMillis?.let(::toDate)
                val end = state.selectedEndDateMillis?.let(::toDate)
                PrimaryButton(
                    "Показать за эти даты",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                    enabled = start != null && end != null,
                ) {
                    if (start != null && end != null) onPick(customPeriod(start, end))
                }
            }
        }
    }
}

private fun toDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/** Свой период; длиннее 400 дней укорачиваем — иначе выбор молча упирался бы в ошибку сервера. */
fun customPeriod(start: LocalDate, end: LocalDate): AnalyticsPeriod {
    val (a, b) = if (start <= end) start to end else end to start
    val limited = if (ChronoUnit.DAYS.between(a, b) > MAX_RANGE_DAYS) a.plusDays(MAX_RANGE_DAYS) else b
    return AnalyticsPeriod.Custom(a.toString(), limited.toString())
}

/** «сравнение: прошлый период ⌄» — меню выбора базы сравнения. */
@Composable
fun CompareMenu(compare: AnalyticsCompare, onSelect: (AnalyticsCompare) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.clickable { open = true },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("сравнение: ${compare.shortTitle}", color = Orda.colors.brand, fontSize = 13.sp)
        Icon(Icons.Filled.KeyboardArrowDown, null, tint = Orda.colors.brand, modifier = Modifier.size(16.dp))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AnalyticsCompare.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.title) },
                    trailingIcon = { if (item == compare) Icon(Icons.Filled.Check, null, tint = Orda.colors.brand) },
                    onClick = {
                        open = false
                        onSelect(item)
                    },
                )
            }
        }
    }
}

/**
 * Кнопка «Учесть F16 Extra» — включает отдельную кассу в итоги. Одна настройка на
 * все отчёты и запоминается. Показывается, только если такая касса у организации есть.
 */
@Composable
fun ExtraCashToggle(env: BusinessEnv) {
    val companies by env.vm.companies.collectAsStateWithLifecycle()
    val isOn by env.vm.includeExtra.collectAsStateWithLifecycle()
    val extra = companies.firstOrNull(BusinessViewModel::isExtra) ?: return
    val c = Orda.colors
    Row(
        Modifier
            .pressScale()
            .clip(CircleShape)
            .background(if (isOn) c.brand else c.surface, CircleShape)
            .clickable { env.vm.setIncludeExtra(!isOn) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (isOn) androidx.compose.ui.graphics.Color.White else c.text
        Icon(if (isOn) Icons.Filled.CheckCircle else Icons.Filled.AddCircleOutline, null, tint = fg, modifier = Modifier.size(16.dp))
        Text(if (isOn) "С учётом ${extra.name}" else "Учесть ${extra.name}", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * Выбор точек. Правим копию и применяем по «Готово»: иначе каждая галочка
 * запускала бы новый запрос, пока человек ещё выбирает.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyPickerSheet(
    companies: List<OwnerAnalytics.CompanyOption>,
    selection: Set<String>,
    onDismiss: () -> Unit,
    onDone: (Set<String>) -> Unit,
) {
    val c = Orda.colors
    var draft by remember { mutableStateOf(selection) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.elevated,
    ) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text("Точки", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm))
            CompanyRow("Все точки", null, draft.isEmpty()) { draft = emptySet() }
            HorizontalDivider(color = c.border)
            companies.forEach { company ->
                CompanyRow(
                    company.name,
                    if (company.isExtra) "Не входит в общие итоги" else null,
                    company.id in draft,
                ) { draft = if (company.id in draft) draft - company.id else draft + company.id }
            }
            PrimaryButton("Готово", Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
                // Выбраны все — это то же, что «все точки», и так понятнее подпись.
                onDone(if (draft.size == companies.size) emptySet() else draft)
            }
        }
    }
}

@Composable
private fun CompanyRow(title: String, subtitle: String?, isOn: Boolean, onClick: () -> Unit) {
    val c = Orda.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = c.textDim, fontSize = 12.sp)
        }
        Icon(
            if (isOn) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            null,
            tint = if (isOn) c.brand else c.textDim,
            modifier = Modifier.size(22.dp),
        )
    }
}
