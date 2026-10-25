package kz.ordaops.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.ordaops.android.ui.theme.Navy

private val Cobalt = Color(0xFF2563EB)

/**
 * Знак ORDA CONTROL — кольцо и синий сегмент, геометрия из OrdaControlMark.swift.
 * `sweep` 0…1 — насколько прорисован сегмент (для анимации появления).
 */
@Composable
fun OrdaMark(size: Dp, ringColor: Color = Navy, sweep: Float = 1f, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val side = this.size.minDimension
        val radius = side * 0.29f
        val topLeft = Offset(center.x - radius, center.y - radius)
        drawCircle(ringColor, radius = radius, style = Stroke(width = side * 0.15f))
        if (sweep > 0.001f) {
            drawArc(
                color = Cobalt,
                startAngle = -104f,
                sweepAngle = 90f * sweep,
                useCenter = false,
                topLeft = topLeft,
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = side * 0.19f, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * Логотип «ORDA / CONTROL». Сегмент дорисовывается при появлении — один раз,
 * как заставка на айфоне, только без отдельного экрана ожидания.
 */
@Composable
fun OrdaLogo(height: Dp = 44.dp, color: Color = Navy, subtitleColor: Color = Color(0xFF6B7280), animate: Boolean = true) {
    val sweep = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animate) sweep.animateTo(1f, tween(durationMillis = 900, delayMillis = 150, easing = FastOutSlowInEasing))
    }
    val word = height.value * 0.74f
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((height.value * 0.1f).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OrdaMark(size = (word * 1.12f).dp, ringColor = color, sweep = sweep.value)
            Text("RDA", color = color, fontSize = word.sp, fontWeight = FontWeight.Black, letterSpacing = (-word * 0.02f).sp)
        }
        Text("CONTROL", color = subtitleColor, fontSize = (height.value * 0.26f).sp, fontWeight = FontWeight.Medium, letterSpacing = (height.value * 0.12f).sp)
    }
}
