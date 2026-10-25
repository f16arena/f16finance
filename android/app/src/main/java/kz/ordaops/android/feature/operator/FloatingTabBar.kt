package kz.ordaops.android.feature.operator

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

/**
 * Плавающая нижняя панель — как на айфоне: белая «таблетка» с тенью над фоном.
 *
 * Подсветка выбранной вкладки — одна плашка, которая переезжает пружиной под
 * нужный пункт. Пункты равной ширины и стоят на месте: соседи не сдвигаются,
 * как бывает, когда подпись появляется только у выбранного.
 *
 * Панель стоит под контентом, а не поверх: последние строки списков не
 * прячутся под ней.
 */
@Composable
fun FloatingTabBar(
    tabs: List<OperatorSection>,
    selected: OperatorSection?,
    badges: Map<OperatorSection, Int>,
    onSelect: (OperatorSection) -> Unit,
) {
    val colors = Orda.colors
    val haptics = rememberHaptics()
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .navigationBarsPadding()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(32.dp)
        BoxWithConstraints(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .shadow(16.dp, shape, ambientColor = colors.brand.copy(alpha = 0.12f), spotColor = colors.brand.copy(alpha = 0.18f))
                .background(colors.surface, shape)
                .padding(6.dp),
        ) {
            val count = tabs.size.coerceAtLeast(1)
            val itemWidth = maxWidth / count
            val index = tabs.indexOf(selected)
            // Плашка выбора: одна на панель, едет под выбранный пункт.
            val indicatorX by animateDpAsState(
                targetValue = itemWidth * index.coerceAtLeast(0),
                animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                label = "tabIndicator",
            )
            val indicatorAlpha by animateFloatAsState(if (index >= 0) 1f else 0f, label = "tabIndicatorAlpha")
            Box(
                Modifier
                    .offset(x = indicatorX)
                    .width(itemWidth)
                    .heightIn(min = 56.dp)
                    .graphicsLayer { alpha = indicatorAlpha }
                    .background(colors.brand.copy(alpha = 0.12f), RoundedCornerShape(26.dp)),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                tabs.forEach { tab ->
                    val isSelected = tab == selected
                    val tint by animateColorAsState(if (isSelected) colors.brand else colors.textMuted, label = "tabTint")
                    val bounce by animateFloatAsState(
                        if (isSelected) 1.12f else 1f,
                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                        label = "tabBounce",
                    )
                    Column(
                        Modifier
                            .width(itemWidth)
                            .heightIn(min = 56.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                            ) {
                                if (!isSelected) haptics.tick()
                                onSelect(tab)
                            }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.graphicsLayer { scaleX = bounce; scaleY = bounce }) {
                            val count = badges[tab] ?: 0
                            if (count > 0) {
                                BadgedBox(badge = { Badge { Text(if (count > 99) "99+" else "$count") } }) {
                                    Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                                }
                            } else {
                                Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                            }
                        }
                        Text(
                            tab.tabTitle,
                            color = tint,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
