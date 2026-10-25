package kz.ordaops.android.feature.operator.work

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

// Небольшие общие элементы трёх разделов: в ui/components их нет, а трогать общий слой нельзя.

/** Заголовок раздела; со стрелкой, когда открыт вложенный экран. */
@Composable
fun SectionTitle(title: String, onBack: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Orda.colors.text)
            }
        } else {
            Box(Modifier.size(Spacing.sm))
        }
        Text(
            title,
            color = Orda.colors.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

enum class ChipKind { Good, Danger, Warning, Info, Neutral }

/** Метка состояния — `StatusChip`. */
@Composable
fun StatusChip(text: String, kind: ChipKind = ChipKind.Neutral) {
    val color = when (kind) {
        ChipKind.Good -> Orda.colors.positive
        ChipKind.Danger -> Orda.colors.negative
        ChipKind.Warning -> Orda.colors.warning
        ChipKind.Info -> Orda.colors.info
        ChipKind.Neutral -> Orda.colors.textDim
    }
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = Spacing.sm, vertical = 3.dp),
    )
}

/** Карточка с цветной полосой слева — `Card(accent:)`. */
@Composable
fun AccentCard(accent: Color?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Radius.lg)
    Row(
        modifier
            .fillMaxWidth()
            .background(Orda.colors.surface, shape)
            .border(1.dp, accent?.copy(alpha = 0.5f) ?: Orda.colors.border, shape),
    ) {
        Column(Modifier.weight(1f).padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            content()
        }
    }
}

/** Плашка-уведомление (смена чужая, связи нет и т.п.). */
@Composable
fun NoticeCard(text: String, color: Color) {
    Text(
        text,
        color = Orda.colors.textMuted,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(Radius.md))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(Radius.md))
            .padding(Spacing.md),
    )
}

/** Вторичная кнопка — `SecondaryButtonStyle`. */
@Composable
fun SecondaryButton(title: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(Radius.md),
        border = androidx.compose.foundation.BorderStroke(1.dp, Orda.colors.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Orda.colors.text),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
    }
}

/** Многострочное поле ввода на плашке — как `TextField(axis: .vertical)`. */
@Composable
fun NoteField(value: String, onChange: (String) -> Unit, placeholder: String, minLines: Int = 2, maxLines: Int = 6) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Orda.colors.surfaceRaised, RoundedCornerShape(Radius.sm))
            .padding(Spacing.md),
    ) {
        if (value.isEmpty()) Text(placeholder, color = Orda.colors.textDim, fontSize = 15.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = TextStyle(color = Orda.colors.text, fontSize = 15.sp),
            cursorBrush = SolidColor(Orda.colors.brand),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Потянуть вниз — обновить. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(refreshing: Boolean, onRefresh: () -> Unit, content: @Composable () -> Unit) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh) { content() }
}

/** Строка нажимаемая целиком. */
fun Modifier.tappable(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
