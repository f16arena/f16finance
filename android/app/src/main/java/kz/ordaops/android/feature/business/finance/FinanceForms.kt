package kz.ordaops.android.feature.business.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kz.ordaops.android.feature.business.AnalyticsPeriod
import kz.ordaops.android.feature.operator.shift.ordaFieldColors
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// Поля форм раздела: дата, выпадающий выбор, многострочный текст.

/** Поле даты `YYYY-MM-DD`: нажатие открывает календарь. [maxToday] — будущее выбрать нельзя. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, iso: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, maxToday: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    val shown = runCatching { LocalDate.parse(iso) }.getOrNull()?.let { AnalyticsPeriod.rangeLabel(iso, iso, currentYear = -1) } ?: iso
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.CalendarMonth, null, tint = Orda.colors.textDim) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.md),
            colors = ordaFieldColors(),
        )
        // Поверх поля — прозрачный слой, иначе readOnly-поле съедает нажатие.
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        val today = AnalyticsPeriod.businessToday()
        val initial = runCatching { LocalDate.parse(iso) }.getOrDefault(today).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !maxToday || !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } },
        ) { DatePicker(state) }
    }
}

/** Пункт выпадающего выбора. */
data class Option(val id: String, val title: String)

/** Выпадающий выбор: `""` — «не выбрано». */
@Composable
fun PickerField(
    label: String,
    options: List<Option>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Не выбрано",
) {
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.id == selectedId }?.title ?: placeholder
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ExpandMore, null, tint = Orda.colors.textDim) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.md),
            colors = ordaFieldColors(),
        )
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(placeholder, color = Orda.colors.textDim) }, onClick = { onSelect(""); open = false })
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o.title, fontWeight = if (o.id == selectedId) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { onSelect(o.id); open = false },
                )
            }
        }
    }
}

/** Текстовое поле в несколько строк. */
@Composable
fun TextAreaField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, minLines: Int = 2, placeholder: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        minLines = minLines,
        maxLines = 6,
        shape = RoundedCornerShape(Radius.md),
        colors = ordaFieldColors(),
    )
}

/** Однострочное текстовое поле. */
@Composable
fun TextLineField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        shape = RoundedCornerShape(Radius.md),
        colors = ordaFieldColors(),
    )
}

/** Подсказка под полем. */
@Composable
fun FieldHint(text: String, warning: Boolean = false) {
    Text(text, color = if (warning) Orda.colors.warning else Orda.colors.textDim, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
}
