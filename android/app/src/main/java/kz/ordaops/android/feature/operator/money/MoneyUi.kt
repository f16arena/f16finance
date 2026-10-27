package kz.ordaops.android.feature.operator.money

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

// Мелкие общие кирпичики экранов этого пакета: свои копии, чтобы не лезть в ui/*.

/** Шире этого — планшет/развёрнутый экран: контент по центру и в две колонки. */
internal val ExpandedWidth = 600.dp
internal val MaxContentWidth = 840.dp

/**
 * Рамка экрана: на широких экранах контент по центру не шире [MaxContentWidth].
 * `wide` — можно раскладывать в две колонки.
 */
@Composable
internal fun AdaptiveBox(modifier: Modifier = Modifier, content: @Composable (wide: Boolean) -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize().background(Orda.colors.background), contentAlignment = Alignment.TopCenter) {
        val wide = maxWidth >= ExpandedWidth
        Box(Modifier.widthIn(max = MaxContentWidth).fillMaxSize()) { content(wide) }
    }
}

/** Вертикальный список экрана с общими отступами; ключи и contentType задают сами элементы. */
@Composable
internal fun ScreenList(
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        content = content,
    )
}

/** Крупный шрифт системы: фиксированные ряды из семи колонок там не помещаются. */
@Composable
internal fun isLargeFont(): Boolean = LocalDensity.current.fontScale >= 1.4f

/** Заголовок экрана и переключатель периода над списком (не уезжают при прокрутке). */
@Composable
internal fun ScreenHeader(title: String, content: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(top = Spacing.lg)) {
        Text(title, color = Orda.colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

/** Экран-вложение с кнопкой «назад» (и системной). */
@Composable
internal fun SubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Orda.colors.text)
            }
            Text(title, color = Orda.colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
internal fun SectionLabel(text: String, color: Color = Orda.colors.textDim) {
    Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun SoftDivider() {
    HorizontalDivider(color = Orda.colors.border)
}

/** Строка «подпись — значение». */
@Composable
internal fun StatRow(
    label: String,
    value: String,
    valueColor: Color = Orda.colors.text,
    emphasized: Boolean = false,
    labelColor: Color = if (emphasized) Orda.colors.text else Orda.colors.textMuted,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = labelColor,
            fontSize = if (emphasized) 16.sp else 14.sp,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            color = valueColor,
            fontSize = if (emphasized) 17.sp else 14.sp,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

/** Таблетка-статус. */
@Composable
internal fun StatusChip(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), CircleShape)
            .padding(horizontal = Spacing.sm, vertical = 3.dp),
    )
}

/** Переключатель периода: «‹ заголовок ›». */
@Composable
internal fun PeriodStepper(
    title: String,
    caption: String? = null,
    canGoForward: Boolean = true,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "Назад", tint = Orda.colors.text)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (caption != null) Text(caption, color = Orda.colors.textMuted, fontSize = 12.sp)
        }
        IconButton(onClick = onForward, enabled = canGoForward, modifier = Modifier.alpha(if (canGoForward) 1f else 0.35f)) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "Вперёд", tint = Orda.colors.text)
        }
    }
}

/** Цветной квадрат с иконкой — для строк меню профиля. */
@Composable
internal fun TintedIcon(icon: ImageVector, tint: Color, size: Int = 38, corner: Dp = 11.dp) {
    Box(
        Modifier.size(size.dp).background(tint.copy(alpha = 0.15f), RoundedCornerShape(corner)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** Строка меню с заголовком, подписью и значком-счётчиком. */
@Composable
internal fun LinkRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String? = null,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().pressScale().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        TintedIcon(icon, tint)
        Column(Modifier.weight(1f)) {
            Text(title, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1)
        }
        if (badge > 0) {
            Text(
                "$badge",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Orda.colors.negative, CircleShape)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Orda.colors.textDim, modifier = Modifier.size(18.dp))
    }
}

/** Синяя «банковская» карточка — `SalaryBalanceCard` / сводка графика. */
@Composable
internal fun HeroCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Radius.xl)
    Column(
        modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(listOf(kz.ordaops.android.ui.theme.Navy, Orda.colors.brand)),
                shape,
            )
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        content()
    }
}
