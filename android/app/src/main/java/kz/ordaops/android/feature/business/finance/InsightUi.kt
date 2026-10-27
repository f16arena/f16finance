package kz.ordaops.android.feature.business.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing
import kotlin.math.abs
import kotlin.math.roundToLong

// Общие детали аналитических экранов «Финансов»: недельный баланс, цели, налоги,
// прогноз, ИИ-разборы. Своё имя у каждой детали — приватные `Chip` соседних файлов не трогаем.

/** Капсула-переключатель (вкладка, период, точка). */
@Composable
fun InsightChip(title: String, on: Boolean, onClick: () -> Unit) {
    Text(
        title, color = if (on) Color.White else Orda.colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.pressScale().clip(CircleShape).background(if (on) Orda.colors.brand else Orda.colors.surface)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Выпадающая капсула: подпись + список вариантов `id → название`. */
@Composable
fun InsightMenu(title: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.pressScale().clip(CircleShape).background(Orda.colors.surface).clickable { open = true }
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = Orda.colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Icon(Icons.Filled.ExpandMore, null, tint = Orda.colors.textDim, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onPick(id); open = false }) }
        }
    }
}

/** Цветная плашка-вывод: danger / warning / success / info. */
@Composable
fun ToneCard(tone: String, title: String, text: String, modifier: Modifier = Modifier) {
    val tint = when (tone) {
        "danger" -> Orda.colors.negative
        "warning" -> Orda.colors.warning
        "success" -> Orda.colors.positive
        else -> Orda.colors.brand
    }
    Column(
        modifier.fillMaxWidth().background(tint.copy(alpha = 0.12f), RoundedCornerShape(16.dp)).padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (text.isNotBlank()) Text(text, color = Orda.colors.textMuted, fontSize = 13.sp)
    }
}

/** «+12 345 ₸» / «−12 345 ₸» / «0 ₸». */
fun signedMoney(v: Double): String = when {
    v.roundToLong() > 0 -> "+" + money(v)
    v.roundToLong() < 0 -> "−" + money(abs(v))
    else -> money(0.0)
}

/**
 * «было X · ±разница» — цвет по смыслу метрики, а не по знаку: рост расходов не зелёный.
 * [higherIsBetter] = false для расходов и долгов.
 */
@Composable
fun WasDelta(current: Double, previous: Double, higherIsBetter: Boolean = true, modifier: Modifier = Modifier) {
    val diff = current - previous
    val flat = diff.roundToLong() == 0L
    val good = (diff > 0) == higherIsBetter
    val color = when {
        flat -> Orda.colors.textDim
        good -> Orda.colors.positive
        else -> Orda.colors.negative
    }
    Text("было ${money(previous)} · ${signedMoney(diff)}", color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = modifier)
}

/** Плитка «подпись — крупное значение — подпись снизу». */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Orda.colors.text, hint: @Composable () -> Unit = {}) {
    Column(
        modifier.background(Orda.colors.surfaceRaised, RoundedCornerShape(18.dp)).padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = Orda.colors.textDim, fontSize = 12.sp, maxLines = 1)
        Text(value, color = valueColor, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        hint()
    }
}

/** Строка из двух плиток поровну. */
@Composable
fun TileRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md), content = content)
}

private val monthNames = listOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")
private val monthShort = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

object RuDates {
    fun monthName(index0: Int) = monthNames.getOrElse(index0) { "" }
    fun monthShort(index0: Int) = monthShort.getOrElse(index0) { "" }

    /** `2026-10` → «Октябрь 2026». */
    fun monthTitle(ym: String): String {
        val p = ym.split('-').mapNotNull { it.toIntOrNull() }
        return if (p.size == 2 && p[1] in 1..12) "${monthNames[p[1] - 1]} ${p[0]}" else ym
    }

    /** `2026-10-27` → «27.10». */
    fun dm(iso: String): String = if (iso.length >= 10) "${iso.substring(8, 10)}.${iso.substring(5, 7)}" else iso
}
