package kz.ordaops.android.feature.business.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// Простые графики раздела на Canvas: столбики по дням и кольцо долей.
// Порт `ComparisonChart(asBars: true, showsPrevious: false)` и `DonutChart`.

/** Подписанный столбик. */
data class Bar(val label: String, val value: Double)

/**
 * Столбики по дням. Касание или проведение пальцем показывает подпись и сумму;
 * ноль рисуется как «пусто», а не дырой.
 */
@Composable
fun DayBars(bars: List<Bar>, color: Color, modifier: Modifier = Modifier, height: Int = 170) {
    var selected by remember(bars) { mutableStateOf<Int?>(null) }
    val track = Orda.colors.surfaceRaised
    val sel = selected?.let { bars.getOrNull(it) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(Modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (sel != null) {
                Text(sel.label, color = Orda.colors.textMuted, fontSize = 13.sp, maxLines = 1)
                Text(money(sel.value), color = Orda.colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            } else {
                Text("Нажмите на столбик — покажу день", color = Orda.colors.textDim, fontSize = 12.sp)
            }
        }
        val max = (bars.maxOfOrNull { it.value } ?: 0.0).coerceAtLeast(1.0)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height.dp)
                .pointerInput(bars) {
                    detectTapGestures { o -> selected = barAt(o.x, size.width.toFloat(), bars.size) }
                }
                .pointerInput(bars) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> selected = barAt(o.x, size.width.toFloat(), bars.size) },
                        onHorizontalDrag = { change, _ -> selected = barAt(change.position.x, size.width.toFloat(), bars.size) },
                    )
                },
        ) {
            if (bars.isEmpty()) return@Canvas
            val slot = size.width / bars.size
            val barWidth = (slot * 0.62f).coerceAtMost(28.dp.toPx())
            bars.forEachIndexed { i, bar ->
                val h = (bar.value / max).toFloat().coerceIn(0f, 1f) * size.height
                val x = i * slot + (slot - barWidth) / 2
                // Фон-дорожка — чтобы тихий день не выглядел пропуском.
                drawRoundRect(track, Offset(x, 0f), Size(barWidth, size.height), CornerRadius(barWidth / 3))
                if (h > 0f) {
                    val alpha = if (selected == null || selected == i) 1f else 0.4f
                    drawRoundRect(color.copy(alpha = alpha), Offset(x, size.height - h), Size(barWidth, h), CornerRadius(barWidth / 3))
                }
            }
        }
    }
}

private fun barAt(x: Float, width: Float, count: Int): Int? {
    if (count == 0 || width <= 0f) return null
    return (x / (width / count)).toInt().coerceIn(0, count - 1)
}

/** Доля кольца. */
data class Slice(val label: String, val value: Double, val color: Color)

/** Кольцо долей с подписью в центре. Серый хвост «Прочее» собирает вызывающий. */
@Composable
fun DonutChart(slices: List<Slice>, centerTitle: String, centerValue: String, modifier: Modifier = Modifier, size: Int = 180) {
    val total = slices.sumOf { it.value }.coerceAtLeast(0.0)
    val track = Orda.colors.surfaceRaised
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size.dp)) {
                val stroke = 26.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                if (total > 0) {
                    var start = -90f
                    val gap = if (slices.size > 1) 2f else 0f
                    slices.forEach { s ->
                        val sweep = (s.value / total * 360).toFloat()
                        if (sweep > gap) {
                            drawArc(s.color, start + gap / 2, sweep - gap, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                        }
                        start += sweep
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(centerTitle, color = Orda.colors.textDim, fontSize = 12.sp)
                Text(centerValue, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** Цвет серого хвоста «Прочее». */
val OtherSliceColor = Color(0xFF94A3B8)
