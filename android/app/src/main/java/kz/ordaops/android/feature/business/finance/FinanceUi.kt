package kz.ordaops.android.feature.business.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.BusinessEnv
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// Общие детали экранов раздела «Финансы».

/** ViewModel раздела: зависимости берём из `LocalBusiness`; живёт, пока жива Activity. */
@Composable
inline fun <reified VM : ViewModel> financeViewModel(
    key: String? = null,
    crossinline create: (BusinessEnv, FinanceApi) -> VM,
): VM {
    val env = LocalBusiness.current
    return viewModel(
        key = "${VM::class.java.name}:${key.orEmpty()}",
        factory = viewModelFactory { initializer { create(env, FinanceApi(env.api)) } },
    )
}

object FinanceColors {
    /** Красный градиент для «минусовых» героев (расходы, убыток). */
    val HeroNegative = listOf(Color(0xFFDC2626), Color(0xFF991B1B))
    val HeroPositive = listOf(Color(0xFF0B1F3B), Color(0xFF1B3B6E))

    private val categoryPalette = listOf(
        Color(0xFFEF4444), Color(0xFFF59E0B), Color(0xFF3B82F6), Color(0xFF8B5CF6),
        Color(0xFF10B981), Color(0xFFEC4899), Color(0xFF14B8A6), Color(0xFFF97316),
    )

    /** Цвет статьи закреплён за названием — фильтр не перекрашивает. */
    fun categoryTint(name: String): Color = categoryPalette[LedgerLogic.tintIndex(name, categoryPalette.size)]

    fun methodTint(name: String): Color = when (name) {
        Methods.Cash -> Color(0xFF10B981)
        Methods.Kaspi -> Color(0xFFEF4444)
        Methods.Card -> Color(0xFF3B82F6)
        else -> Color(0xFF8B5CF6)
    }

    val Day = Color(0xFFF59E0B)
    val Night = Color(0xFF6366F1)
}

fun expenseIcon(name: String): ImageVector = when (BizFormat.expenseIconKey(name)) {
    "rent" -> Icons.Filled.Home
    "salary" -> Icons.Filled.People
    "goods" -> Icons.Filled.ShoppingCart
    "tax" -> Icons.Filled.AccountBalance
    "utilities" -> Icons.Filled.Bolt
    "internet" -> Icons.Filled.Wifi
    "ads" -> Icons.Filled.Campaign
    "repair" -> Icons.Filled.Construction
    "equipment" -> Icons.Filled.Computer
    "transport" -> Icons.Filled.LocalTaxi
    "cleaning" -> Icons.Filled.CleaningServices
    "bank" -> Icons.Filled.CreditCard
    else -> Icons.Filled.LocalOffer
}

fun methodIcon(name: String): ImageVector = when (name) {
    Methods.Cash -> Icons.Filled.Payments
    Methods.Kaspi, Methods.Card -> Icons.Filled.CreditCard
    else -> Icons.Filled.Public
}

/**
 * Карточка-итог на цветном градиенте: крупная сумма, подпись и «подвал» с
 * расшифровкой. Порт `HeroSummary` (в отличие от `HeroNumberCard` — без
 * фиксированной высоты, подвал необязателен).
 */
@Composable
fun HeroSummary(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    footer: List<Pair<String, String>> = emptyList(),
    colors: List<Color> = FinanceColors.HeroPositive,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(colors), shape)
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(title, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(value, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (caption != null) Text(caption, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (footer.isNotEmpty()) {
            Row(Modifier.padding(top = Spacing.sm).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                footer.forEach { (label, text) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
                        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Плашка активного фильтра с крестиком — снять можно всегда, даже если карточка ушла за экран. */
@Composable
fun FilterPill(title: String, icon: ImageVector, onRemove: () -> Unit) {
    Row(
        Modifier
            .pressScale()
            .clip(CircleShape)
            .background(Orda.colors.brand, CircleShape)
            .clickable(onClick = onRemove)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Icon(Icons.Filled.Close, "Снять", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
    }
}

/** Тонкая полоса доли (как в `AmountRow`). */
@Composable
fun ShareBar(fraction: Double, tint: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).background(Orda.colors.surfaceRaised, CircleShape)) {
        Box(
            Modifier
                .height(4.dp)
                .fillMaxWidth(fraction.coerceIn(0.0, 1.0).toFloat())
                .background(tint, CircleShape),
        )
    }
}

/** Строка «подпись — значение» внутри карточки. */
@Composable
fun LineRow(label: String, value: String, valueColor: Color = Orda.colors.text, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = Orda.colors.textDim, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = valueColor,
            fontSize = 15.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(start = Spacing.lg),
        )
    }
}

/** Тонкий разделитель строк списка. */
@Composable
fun RowDivider(startPadding: Int = 0) {
    Box(Modifier.fillMaxWidth().padding(start = startPadding.dp).height(1.dp).background(Orda.colors.border.copy(alpha = 0.6f)))
}
