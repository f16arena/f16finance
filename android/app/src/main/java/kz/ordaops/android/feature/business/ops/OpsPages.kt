package kz.ordaops.android.feature.business.ops

import androidx.compose.runtime.Composable

/** Нативные экраны раздела: `pageID` → экран. Подключены в [kz.ordaops.android.feature.business.NativePage]. */
internal val opsPages: Map<String, @Composable () -> Unit> = mapOf(
    "tasks" to { TasksScreen() },
    "incidents" to { IncidentsScreen() },
    "customers" to { CustomersScreen() },
    "pos-receipts" to { ReceiptsListScreen() },
    "pos-returns" to { ReturnsScreen() },
    "point-devices" to { PointDevicesScreen() },
    "settings" to { SettingsScreen() },
    "telegram" to { TelegramScreen() },
    "debug" to { DiagnosticsScreen() },
    "discounts" to { DiscountsScreen() },
    "team-chat" to { TeamChatPage() },
    "messages" to { MessagesPage() },
)
