package kz.ordaops.android.feature.business.platform

import androidx.compose.runtime.Composable

/**
 * Страницы платформы, которые открываются из каталога разделов: `pageID` → экран.
 * Подключены в [kz.ordaops.android.feature.business.NativePage].
 */
internal val platformPages: Map<String, @Composable () -> Unit> = mapOf(
    "logs" to { LogsScreen() },
)
