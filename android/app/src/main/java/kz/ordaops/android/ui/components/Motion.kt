package kz.ordaops.android.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star

/**
 * Пружинное сжатие при нажатии. Касание только подсматриваем (проход Initial, без
 * consume), поэтому работает рядом с любым `clickable` и не ломает его жесты.
 */
fun Modifier.pressScale(pressed: Float = 0.96f): Modifier = composed {
    var down by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (down) pressed else 1f,
        // Лёгкий «отскок» при отпускании — то самое expressive-ощущение.
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down = true
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
                down = false
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}

/** Семантическая вибрация: что произошло, а не какая амплитуда. */
@Immutable
class Haptics(private val view: View) {
    /** Лёгкий тик: добавили в корзину, шаг, переключение. */
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Успех: оплата прошла, смена открыта/закрыта, задача выполнена, скан распознан. */
    fun success() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP,
        )
    }

    /** Ошибка: штрихкод не найден, оплата не прошла. */
    fun error() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
        )
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** «Печенье» — звезда со скруглёнными лучами, фигура аватаров вместо круга. */
val CookieShape: Shape = object : Shape {
    private val polygon = RoundedPolygon.star(
        numVerticesPerRadius = 9,
        radius = 1f,
        innerRadius = 0.86f,
        rounding = CornerRounding(0.5f),
    )

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        val cubics = polygon.cubics
        if (cubics.isNotEmpty()) {
            val half = minOf(size.width, size.height) / 2f
            val cx = size.width / 2f
            val cy = size.height / 2f
            fun x(v: Float) = cx + v * half
            fun y(v: Float) = cy + v * half
            path.moveTo(x(cubics.first().anchor0X), y(cubics.first().anchor0Y))
            for (c in cubics) {
                path.cubicTo(
                    x(c.control0X), y(c.control0Y),
                    x(c.control1X), y(c.control1Y),
                    x(c.anchor1X), y(c.anchor1Y),
                )
            }
            path.close()
        }
        return Outline.Generic(path)
    }
}

/**
 * Число с переходом: при смене значения цифры выезжают снизу вверх.
 * Для денег и счётчиков, которые обновляются на экране.
 */
@Composable
fun AnimatedText(text: String, style: TextStyle, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = {
            (slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it / 2 } + fadeIn())
                .togetherWith(slideOutVertically { -it / 2 } + fadeOut())
        },
        label = "animatedText",
    ) { value ->
        Text(value, style = style, color = color)
    }
}
