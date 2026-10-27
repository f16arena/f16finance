package kz.ordaops.android.feature.business

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.ordaops.android.feature.operator.money.TintedIcon
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Navy
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// Детали кабинета владельца — язык банковских приложений: одна крупная цифра на
// карточку, круглые кнопки действий, сервисы плиткой из иконок, строки с иконкой
// в кружке. Порт `OwnerComponents.swift`.

/** Ширина колонки контента: на планшете не растягиваем строки на весь экран. */
val OwnerContentWidth = 720.dp

/** Прокручиваемая колонка экрана кабинета: отступы, центрирование, потолок по ширине. */
@Composable
fun OwnerScreenColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = OwnerContentWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            content = content,
        )
    }
}

/** Белый блок с заголовком — секция главной без рамки и капса. */
@Composable
fun OwnerSection(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Orda.colors.surface, RoundedCornerShape(24.dp))
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

/** Карточка с одной большой цифрой на цветном градиенте (выручка сегодня / за месяц). */
@Composable
fun HeroNumberCard(
    title: String,
    value: Double?,
    change: Double?,
    changeCaption: String,
    footer: List<Pair<String, String>>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .fillMaxWidth()
            .height(196.dp)
            .clip(shape)
            .background(Brush.linearGradient(colors), shape)
            .pressScale()
            .clickable(onClick = onClick)
            .padding(Spacing.xl),
    ) {
        Text(title, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(
            value?.let { kz.ordaops.android.ui.components.money(it) } ?: "—",
            color = Color.White,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        Row(Modifier.height(24.dp).padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (change != null) {
                Row(
                    Modifier.background(Color.White.copy(alpha = 0.2f), CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        if (change >= 0) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                        null, tint = Color.White, modifier = Modifier.size(14.dp),
                    )
                    Text(BizFormat.percent(change, signed = true), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text(changeCaption, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1)
            }
        }
        Box(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            footer.forEach { (label, text) ->
                Column {
                    Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/** Цвета героев: «сегодня» — яркий кобальт, «месяц» — тёмно-синий. */
object HeroColors {
    val Accent = listOf(Color(0xFF2563EB), Color(0xFF1D4ED8))
    val Deep = listOf(Navy, Color(0xFF1B3B6E))
}

/** Круглая кнопка быстрого действия с подписью снизу. */
@Composable
fun RoundAction(icon: ImageVector, title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Column(
        modifier
            .pressScale()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.tick()
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .shadow(6.dp, CircleShape, ambientColor = Orda.colors.brand.copy(alpha = 0.12f), spotColor = Orda.colors.brand.copy(alpha = 0.12f))
                .background(Orda.colors.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Orda.colors.brand, modifier = Modifier.size(24.dp))
        }
        Text(title, color = Orda.colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Плитка сервиса: иконка в скруглённом квадрате и название под ней. У раздела без
 * нативного экрана под названием стоит «сайт» — человек знает, что откроется браузер.
 */
@Composable
fun ServiceTile(item: ServiceItem, tint: Color, badge: Int, size: Int = 54, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Column(
        Modifier
            .fillMaxWidth()
            .pressScale()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.tick()
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box {
            TintedIcon(pageIcon(item.pageId), tint, size, corner = (size * 0.3f).dp)
            if (badge > 0) {
                Text(
                    if (badge > 99) "99+" else "$badge",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-6).dp)
                        .background(Orda.colors.negative, CircleShape)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            item.title,
            color = Orda.colors.text,
            fontSize = if (size > 60) 14.sp else 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(if (size > 60) 36.dp else 30.dp),
        )
        if (item.isWeb) {
            Text("сайт", color = Orda.colors.textDim, fontSize = 10.sp, modifier = Modifier.offset(y = (-4).dp))
        }
    }
}

/** Сетка плиток по `columns` в ряд — без ленивой сетки внутри прокрутки. */
@Composable
fun <T> TileGrid(items: List<T>, columns: Int, rowSpacing: Dp = Spacing.lg, tile: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                row.forEach { item -> Box(Modifier.weight(1f)) { tile(item) } }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** Кружок с первой буквой — для точек и людей без иконки. */
@Composable
fun LetterBadge(text: String, tint: Color, size: Int = 40) {
    // Буква последнего слова: у «F16 Arena» и «F16 Ramen» первое слово общее, и все кружки были бы «F».
    val word = text.trim().split(' ').lastOrNull { it.isNotEmpty() } ?: text
    Box(Modifier.size(size.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
        Text(word.take(1).uppercase(), color = tint, fontSize = (size * 0.42f).sp, fontWeight = FontWeight.Bold)
    }
}

/** Изменение текстом со стрелкой, без плашки — для строк. Цвет по смыслу метрики, а не по знаку. */
@Composable
fun ChangeText(change: Double, higherIsBetter: Boolean = true) {
    val rising = change >= 0
    val flat = kotlin.math.abs(change) < 0.05
    val good = rising == higherIsBetter
    val color = when {
        flat -> Orda.colors.textDim
        good -> Orda.colors.positive
        else -> Orda.colors.negative
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(
            when {
                flat -> Icons.Filled.Remove
                rising -> Icons.AutoMirrored.Filled.TrendingUp
                else -> Icons.AutoMirrored.Filled.TrendingDown
            },
            null, tint = color, modifier = Modifier.size(14.dp),
        )
        Text(BizFormat.percent(kotlin.math.abs(change)), color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Строка списка: значок, название с подписью, сумма и изменение справа, тонкая
 * полоса доли снизу.
 */
@Composable
fun AmountRow(
    title: String,
    amount: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    change: Double? = null,
    higherIsBetter: Boolean = true,
    share: Double? = null,
    tint: Color = Orda.colors.brand,
    onClick: (() -> Unit)? = null,
    leading: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.pressScale().clickable(onClick = onClick) else it }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(title, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(amount, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(subtitle.orEmpty(), color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (change != null) ChangeText(change, higherIsBetter)
            }
            if (share != null) {
                val fraction = share.coerceIn(0.0, 1.0).toFloat()
                Box(Modifier.fillMaxWidth().padding(top = 2.dp).height(4.dp).background(Orda.colors.surfaceRaised, CircleShape)) {
                    Box(Modifier.height(4.dp).widthIn(min = 3.dp).fillMaxWidth(fraction).background(tint, CircleShape))
                }
            }
        }
    }
}

/** Карточка «требует внимания» в горизонтальной ленте. */
@Composable
fun AttentionCard(icon: ImageVector, tint: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .size(width = 280.dp, height = 68.dp)
            .pressScale()
            .clip(RoundedCornerShape(20.dp))
            .background(Orda.colors.surface)
            .clickable(onClick = onClick)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        TintedIcon(icon, tint, 42, corner = 21.dp)
        Column {
            Text(title, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Лента карточек вбок. */
@Composable
fun HorizontalRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        content = content,
    )
}

/** Нижняя подпись-сноска. */
@Composable
fun OwnerFootnote(text: String) {
    Text(text, color = Orda.colors.textDim, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs))
}

/** Сегмент выбора: «День · Неделя · Месяц · Год». Подписи не влезают — прокрутка вбок. */
@Composable
fun <T> PillSegment(options: List<Pair<T, String>>, selection: T?, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val haptics = rememberHaptics()
    Row(
        modifier
            .background(Orda.colors.surfaceRaised, CircleShape)
            .padding(4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, title) ->
            val on = value == selection
            val bg by animateColorAsState(if (on) Orda.colors.surface else Color.Transparent, label = "pill")
            Text(
                title,
                color = if (on) Orda.colors.text else Orda.colors.textMuted,
                fontSize = 14.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(bg, CircleShape)
                    .clickable {
                        if (!on) haptics.tick()
                        onSelect(value)
                    }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}
