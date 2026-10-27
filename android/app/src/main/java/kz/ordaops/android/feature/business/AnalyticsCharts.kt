package kz.ordaops.android.feature.business

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// Графики аналитики — на Canvas, без внешних библиотек. Порт ComparisonChart,
// HeatmapGrid и «часов дня» из OrdaUI / OwnerAnalyticsScreen.

/** Палитра рядов: цвет закреплён за показателем, а не за местом в списке. */
object ChartColors {
    val Revenue: Color @Composable get() = if (Orda.colors.isDark) Color(0xFF3B82F6) else Color(0xFF2563EB)
    val Profit: Color @Composable get() = if (Orda.colors.isDark) Color(0xFF2DD4BF) else Color(0xFF0D9488)
    val Expense: Color @Composable get() = if (Orda.colors.isDark) Color(0xFFFBBF24) else Color(0xFFD97706)

    @Composable
    fun of(metric: OverviewMetric): Color = when (metric) {
        OverviewMetric.Revenue -> Revenue
        OverviewMetric.Profit -> Profit
        OverviewMetric.Expense -> Expense
    }
}

/**
 * Период поверх пунктира базы сравнения. Касание или проведение пальцем
 * показывает обе цифры: «22 сен — 184 500 ₸ · было 150 000 ₸». База нарисована на
 * тех же позициях, что и текущий период.
 */
@Composable
fun ComparisonChart(
    points: List<ChartPoint>,
    color: Color,
    asBars: Boolean,
    modifier: Modifier = Modifier,
    height: Int = 190,
) {
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val c = Orda.colors
    val sel = selected?.let { points.getOrNull(it) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (sel != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(sel.label, color = c.textMuted, fontSize = 13.sp, maxLines = 1)
                Text(money(sel.value), color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("· было ${money(sel.previous)}", color = c.textDim, fontSize = 13.sp, maxLines = 1)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                LegendSwatch(color, "Текущий период", dashed = false)
                LegendSwatch(c.textDim, "База сравнения", dashed = true)
            }
        }
        val maxValue = (points.maxOfOrNull { maxOf(it.value, it.previous) } ?: 0.0).coerceAtLeast(1.0)
        val minValue = (points.minOfOrNull { minOf(it.value, it.previous) } ?: 0.0).coerceAtMost(0.0)
        val labelStyle = TextStyle(color = c.textDim, fontSize = 10.sp)
        val axisTicks = remember(maxValue, minValue) { niceTicks(minValue, maxValue, 4) }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height.dp)
                .pointerInput(points) {
                    detectTapGestures { offset -> selected = indexAt(offset.x, size.width.toFloat(), points.size, asBars, AXIS_DP.dp.toPx()) }
                }
                .pointerInput(points) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> selected = indexAt(offset.x, size.width.toFloat(), points.size, asBars, AXIS_DP.dp.toPx()) },
                        onHorizontalDrag = { change, _ -> selected = indexAt(change.position.x, size.width.toFloat(), points.size, asBars, AXIS_DP.dp.toPx()) },
                    )
                },
        ) {
            drawChart(points, color, asBars, axisTicks, selected, c.border, c.textDim, measurer, labelStyle)
        }
    }
}

private fun indexAt(x: Float, width: Float, count: Int, asBars: Boolean, left: Float): Int? {
    if (count == 0) return null
    val plot = (width - left).coerceAtLeast(1f)
    val slot = if (asBars) plot / count else plot / (count - 1).coerceAtLeast(1)
    val raw = if (asBars) ((x - left) / slot).toInt() else Math.round((x - left) / slot)
    return raw.coerceIn(0, count - 1)
}

/** Ширина подписей оси слева, dp. */
private const val AXIS_DP = 52

/** Округлённые деления оси: 0, 50к, 100к… */
internal fun niceTicks(min: Double, max: Double, desired: Int): List<Double> {
    val range = (max - min).coerceAtLeast(1.0)
    val rough = range / desired
    val pow = Math.pow(10.0, Math.floor(Math.log10(rough)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * pow }.firstOrNull { it >= rough } ?: (10 * pow)
    val first = Math.floor(min / step) * step
    val result = mutableListOf<Double>()
    var v = first
    while (v <= max + step * 0.999) {
        result += v
        v += step
        if (result.size > 12) break
    }
    return result
}

private fun DrawScope.drawChart(
    points: List<ChartPoint>,
    color: Color,
    asBars: Boolean,
    ticks: List<Double>,
    selected: Int?,
    grid: Color,
    dim: Color,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
) {
    if (points.isEmpty() || ticks.isEmpty()) return
    val bottomPad = 18.dp.toPx()
    val topPad = 6.dp.toPx()
    val left = AXIS_DP.dp.toPx()
    val plotW = size.width - left
    val plotH = size.height - bottomPad - topPad
    val lo = ticks.first()
    val hi = ticks.last().coerceAtLeast(lo + 1)
    fun y(v: Double) = topPad + plotH - ((v - lo) / (hi - lo)).toFloat() * plotH
    val slot = if (asBars) plotW / points.size else plotW / (points.size - 1).coerceAtLeast(1)
    fun x(i: Int) = if (asBars) left + slot * (i + 0.5f) else left + slot * i

    // Сетка и подписи оси слева.
    ticks.forEach { t ->
        val yy = y(t)
        drawLine(grid.copy(alpha = 0.5f), Offset(left, yy), Offset(size.width, yy), strokeWidth = 1f)
        val text = measurer.measure(BizFormat.axisTick(t), labelStyle)
        drawText(text, topLeft = Offset(left - text.size.width - 6.dp.toPx(), yy - text.size.height / 2f))
    }

    // Подписи оси X: не больше пяти, у краёв — чтобы не налезали.
    val labelStep = if (points.size <= 8) 1 else Math.ceil(points.size / 5.0).toInt()
    points.forEachIndexed { i, p ->
        if (i % labelStep != 0) return@forEachIndexed
        val text = measurer.measure(p.shortLabel, labelStyle)
        val tx = (x(i) - text.size.width / 2f).coerceIn(left, size.width - text.size.width)
        drawText(text, topLeft = Offset(tx, size.height - text.size.height))
    }

    if (asBars) {
        val barW = if (points.size <= 12) 22.dp.toPx() else 8.dp.toPx()
        val base = y(0.0.coerceIn(lo, hi))
        points.forEachIndexed { i, p ->
            val alpha = if (selected == null || selected == i) 1f else 0.35f
            val top = y(p.value)
            drawRoundRect(
                color.copy(alpha = alpha),
                topLeft = Offset(x(i) - barW / 2, minOf(top, base)),
                size = Size(barW, kotlin.math.abs(base - top).coerceAtLeast(2f)),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
    } else {
        val line = Path()
        points.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(i), y(p.value)) else line.lineTo(x(i), y(p.value)) }
        val area = Path().apply {
            addPath(line)
            lineTo(x(points.lastIndex), topPad + plotH)
            lineTo(x(0), topPad + plotH)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.24f), color.copy(alpha = 0.01f)), startY = topPad, endY = topPad + plotH))
        drawPath(line, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // База сравнения — пунктиром поверх.
    val prev = Path()
    points.forEachIndexed { i, p -> if (i == 0) prev.moveTo(x(i), y(p.previous)) else prev.lineTo(x(i), y(p.previous)) }
    drawPath(
        prev, dim.copy(alpha = 0.8f),
        style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))),
    )

    selected?.let { i ->
        val p = points.getOrNull(i) ?: return@let
        drawLine(dim.copy(alpha = 0.4f), Offset(x(i), topPad), Offset(x(i), topPad + plotH), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        if (!asBars) drawCircle(color, radius = 4.dp.toPx(), center = Offset(x(i), y(p.value)))
    }
}

@Composable
private fun LegendSwatch(color: Color, title: String, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(width = 18.dp, height = 4.dp)) {
            drawLine(
                color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                strokeWidth = if (dashed) 1.4.dp.toPx() else 2.4.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null,
            )
        }
        Text(title, color = Orda.colors.textDim, fontSize = 12.sp)
    }
}

/** Выручка по часам за день — столбики только в рабочие часы. */
@Composable
fun HourBars(hours: List<OwnerAnalytics.Pos.Hour>, color: Color, modifier: Modifier = Modifier) {
    val c = Orda.colors
    val active = hours.filter { it.amount > 0 }.map { it.hour }
    if (active.isEmpty()) return
    val shown = hours.filter { it.hour in active.min()..active.max() }
    var selected by remember(hours) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = c.textDim, fontSize = 10.sp)
    val sel = shown.getOrNull(selected ?: -1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (sel != null) {
            Text(
                "${sel.hour}:00–${sel.hour + 1}:00 · ${money(sel.amount)} · ${sel.count} чеков",
                color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        } else {
            Text("По часам", color = c.textDim, fontSize = 13.sp)
        }
        val ticks = remember(shown) { niceTicks(0.0, shown.maxOf { it.amount }.coerceAtLeast(1.0), 3) }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .pointerInput(shown) {
                    detectTapGestures { o -> selected = indexAt(o.x, size.width.toFloat(), shown.size, true, AXIS_DP.dp.toPx()) }
                }
                .pointerInput(shown) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> selected = indexAt(o.x, size.width.toFloat(), shown.size, true, AXIS_DP.dp.toPx()) },
                        onHorizontalDrag = { ch, _ -> selected = indexAt(ch.position.x, size.width.toFloat(), shown.size, true, AXIS_DP.dp.toPx()) },
                    )
                },
        ) {
            val bottomPad = 18.dp.toPx()
            val left = AXIS_DP.dp.toPx()
            val plotW = size.width - left
            val plotH = size.height - bottomPad - 6.dp.toPx()
            val top = ticks.last().coerceAtLeast(1.0)
            fun y(v: Double) = 6.dp.toPx() + plotH - (v / top).toFloat() * plotH
            ticks.forEach { t ->
                drawLine(c.border.copy(alpha = 0.5f), Offset(left, y(t)), Offset(size.width, y(t)), strokeWidth = 1f)
                val text = measurer.measure(BizFormat.axisTick(t), labelStyle)
                drawText(text, topLeft = Offset(left - text.size.width - 6.dp.toPx(), y(t) - text.size.height / 2f))
            }
            val slot = plotW / shown.size
            shown.forEachIndexed { i, h ->
                val barW = (slot * 0.62f).coerceAtMost(22.dp.toPx())
                val cx = left + slot * (i + 0.5f)
                val alpha = if (selected == null || selected == i) 1f else 0.35f
                val barTop = y(h.amount)
                drawRoundRect(
                    color.copy(alpha = alpha), Offset(cx - barW / 2, barTop),
                    Size(barW, (y(0.0) - barTop).coerceAtLeast(0f)), CornerRadius(4.dp.toPx()),
                )
                if (h.hour % 3 == 0) {
                    val text = measurer.measure("${h.hour}:00", labelStyle)
                    drawText(text, topLeft = Offset((cx - text.size.width / 2f).coerceIn(left, size.width - text.size.width), size.height - text.size.height))
                }
            }
        }
    }
}

/**
 * Выручка по дням недели и часам: где пик, где простой. Одна шкала от прозрачного
 * к насыщенному — «больше/меньше», а не «плохо/хорошо». Пустые часы не закрашены.
 */
@Composable
fun HeatmapGrid(values: List<List<Double>>, color: Color, modifier: Modifier = Modifier) {
    val c = Orda.colors
    val weekdays = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    val peak = values.flatten().maxOrNull() ?: 0.0
    var selected by remember(values) { mutableStateOf<Pair<Int, Int>?>(null) }
    val hours = remember(values) {
        var first = 23
        var last = 0
        values.forEach { row -> row.forEachIndexed { h, v -> if (v > 0) { first = minOf(first, h); last = maxOf(last, h) } } }
        if (first <= last) (first..last).toList() else (0..23).toList()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        val s = selected
        if (s != null && values.getOrNull(s.first)?.getOrNull(s.second) != null) {
            Text(
                "${weekdays[s.first]}, ${s.second}:00–${s.second + 1}:00 · ${money(values[s.first][s.second])}",
                color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
        } else {
            Text("Нажмите на клетку, чтобы увидеть сумму", color = c.textDim, fontSize = 12.sp)
        }
        if (peak <= 0) return@Column
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (day in 0 until minOf(values.size, 7)) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(weekdays[day], color = c.textDim, fontSize = 10.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(20.dp))
                    hours.forEach { hour ->
                        val v = values[day].getOrNull(hour) ?: 0.0
                        val isSel = s == day to hour
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .background(
                                    if (v > 0) color.copy(alpha = (0.12 + 0.88 * v / peak).toFloat()) else c.surfaceRaised.copy(alpha = 0.6f),
                                    RoundedCornerShape(3.dp),
                                )
                                .let { if (isSel) it.border(1.5.dp, c.text, RoundedCornerShape(3.dp)) else it }
                                .clickable { selected = if (isSel) null else day to hour },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.width(20.dp))
                hours.forEach { hour ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (hour % 3 == 0) Text("$hour", color = c.textDim, fontSize = 9.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** Средняя выручка по дням недели — семь столбиков, лучший день выделен. */
@Composable
fun WeekdayBars(weekdays: List<OwnerAnalytics.Weekday>, modifier: Modifier = Modifier) {
    val c = Orda.colors
    val names = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    val top = (weekdays.maxOfOrNull { it.average } ?: 0.0).coerceAtLeast(1.0)
    val best = weekdays.maxByOrNull { it.average }?.weekday
    Row(
        modifier.fillMaxWidth().height(160.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        weekdays.forEach { day ->
            val isBest = day.weekday == best
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    BizFormat.axisTick(day.average).replace(" ₸", ""),
                    color = if (isBest) c.text else c.textDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(maxOf(6.0, 110 * day.average / top).dp)
                        .background(if (isBest) c.brand else c.brand.copy(alpha = 0.25f), RoundedCornerShape(8.dp)),
                )
                Text(
                    names.getOrElse(day.weekday) { "" },
                    color = if (isBest) c.text else c.textDim, fontSize = 12.sp,
                    fontWeight = if (isBest) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}
