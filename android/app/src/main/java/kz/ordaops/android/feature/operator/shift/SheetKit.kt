package kz.ordaops.android.feature.operator.shift

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

// Общие мелочи для листов смены и продажи. Живут здесь, а не в ui/*:
// чужие пакеты не трогаем.

/** Нижний лист на всю высоту содержимого — аналог `.sheet` на айфоне. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrdaSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Orda.colors.background,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(title, color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/** Поле суммы: цифры и запятая/точка. Пустое поле и «0» — разные вещи. */
@Composable
internal fun AmountField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "0",
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() || it == ',' || it == '.' }) },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        suffix = { Text("₸", color = Orda.colors.textDim) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = RoundedCornerShape(Radius.md),
        colors = ordaFieldColors(),
    )
}

@Composable
internal fun ordaFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Orda.colors.text,
    unfocusedTextColor = Orda.colors.text,
    focusedBorderColor = Orda.colors.brand,
    unfocusedBorderColor = Orda.colors.border,
    focusedLabelColor = Orda.colors.brand,
    unfocusedLabelColor = Orda.colors.textDim,
    cursorColor = Orda.colors.brand,
    focusedContainerColor = Orda.colors.surfaceRaised,
    unfocusedContainerColor = Orda.colors.surfaceRaised,
)

/** Разбор суммы из поля: запятая как точка, мусор — ноль. */
internal fun parseAmount(text: String): Double = text.replace(',', '.').trim().toDoubleOrNull() ?: 0.0

/** Блок с заголовком раздела формы. */
@Composable
internal fun FormSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, color = Orda.colors.textDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

/** Сегментный переключатель из двух-трёх вариантов — `PillSegment`. */
@Composable
internal fun <T> PillSegment(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Orda.colors.surfaceRaised, RoundedCornerShape(Radius.md))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, title) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (on) Orda.colors.brand else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(Radius.sm))
                    .clickable { onSelect(value) }
                    .padding(vertical = Spacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title,
                    color = if (on) androidx.compose.ui.graphics.Color.White else Orda.colors.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
        }
    }
}
