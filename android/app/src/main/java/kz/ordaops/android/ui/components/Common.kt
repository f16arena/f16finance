package kz.ordaops.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.text.NumberFormat
import java.util.Locale

/** Карточка — `Card` из OrdaUI. */
@Composable
fun OrdaCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Radius.lg)
    Column(
        modifier
            .fillMaxWidth()
            .background(Orda.colors.surface, shape)
            .border(1.dp, Orda.colors.border, shape)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        content = content,
    )
}

@Composable
fun LoadingView(message: String? = null) {
    Column(
        Modifier.fillMaxSize().background(Orda.colors.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Orda.colors.brand)
        if (message != null) {
            Text(message, color = Orda.colors.textDim, modifier = Modifier.padding(top = Spacing.lg))
        }
    }
}

/** Пустое состояние с иконкой и необязательной кнопкой — `EmptyStateView`. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String? = null,
    actionTitle: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).background(Orda.colors.surfaceRaised, RoundedCornerShape(32.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Orda.colors.textMuted, modifier = Modifier.size(28.dp))
        }
        Text(title, color = Orda.colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (message != null) {
            Text(message, color = Orda.colors.textDim, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        if (actionTitle != null && onAction != null) {
            PrimaryButton(actionTitle, onClick = onAction)
        }
    }
}

@Composable
fun PrimaryButton(
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.padding(top = Spacing.xs),
        shape = RoundedCornerShape(Radius.md),
        colors = ButtonDefaults.buttonColors(containerColor = Orda.colors.brand),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = androidx.compose.ui.graphics.Color.White)
        } else {
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = Spacing.xs))
        }
    }
}

/** Красная строка ошибки над формой. */
@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        color = Orda.colors.negative,
        fontSize = 14.sp,
        modifier = modifier
            .fillMaxWidth()
            .background(Orda.colors.negative.copy(alpha = 0.12f), RoundedCornerShape(Radius.sm))
            .padding(Spacing.md),
    )
}

private val moneyFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale("ru", "RU"))

/** 12 345 ₸ — как `Money` в OrdaKit: без копеек, с пробелами. */
fun money(value: Double): String = moneyFormat.format(Math.round(value)) + " ₸"
