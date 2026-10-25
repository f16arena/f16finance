package kz.ordaops.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

/**
 * Единая обёртка экрана: Loading / Content / Empty / Error / Refreshing.
 *
 * - Первая загрузка — индикатор; дальше контент не исчезает никогда.
 * - Обновление — потянуть вниз; индикатор поверх контента, без мигания.
 * - Ошибка при показанных данных — тонкая плашка сверху, а не пустой экран.
 * - Смена «загрузка → контент» — мягкое затухание, без скачка.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> StateContainer(
    state: Loadable<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = onRetry,
    isEmpty: (T) -> Boolean = { false },
    emptyIcon: ImageVector = Icons.Filled.Inbox,
    emptyTitle: String = "Пока пусто",
    emptyMessage: String? = null,
    loadingMessage: String? = null,
    content: @Composable (T) -> Unit,
) {
    val data = state.data
    val phase = when {
        data != null -> if (isEmpty(data)) Phase.Empty else Phase.Content
        state.error != null && !state.isLoading -> Phase.Error
        else -> Phase.Loading
    }
    Column(modifier.fillMaxSize()) {
        // Ошибка обновления при уже показанных данных — тихо сверху.
        AnimatedVisibility(
            visible = data != null && state.error != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            StaleBanner(offline = state.isOffline, message = state.error.orEmpty(), onRetry = onRetry)
        }
        val body: @Composable () -> Unit = {
            Crossfade(targetState = phase, animationSpec = tween(220), label = "state") { p ->
                when (p) {
                    Phase.Loading -> LoadingView(loadingMessage)
                    Phase.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = if (state.isOffline) Icons.Filled.CloudOff else Icons.Filled.ErrorOutline,
                            title = if (state.isOffline) "Нет связи" else "Не удалось загрузить",
                            message = state.error,
                            actionTitle = "Повторить",
                            onAction = onRetry,
                        )
                    }
                    Phase.Empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(icon = emptyIcon, title = emptyTitle, message = emptyMessage)
                    }
                    // data здесь не null: фаза Content выбирается только при данных.
                    Phase.Content -> @Suppress("UNCHECKED_CAST") content(state.data as T)
                }
            }
        }
        if (onRefresh != null) {
            PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) { body() }
        } else {
            Box(Modifier.fillMaxSize()) { body() }
        }
    }
}

private enum class Phase { Loading, Content, Empty, Error }

@Composable
private fun StaleBanner(offline: Boolean, message: String, onRetry: () -> Unit) {
    val colors = Orda.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            .background(colors.warning.copy(alpha = 0.14f), RoundedCornerShape(Radius.sm))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(if (offline) Icons.Filled.CloudOff else Icons.Filled.ErrorOutline, null, tint = colors.warning, modifier = Modifier.size(18.dp))
        Text(
            if (offline) "Нет связи — показаны сохранённые данные" else message,
            color = colors.text,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        TextButton(onClick = onRetry) { Text("Повторить", fontSize = 13.sp) }
    }
}
