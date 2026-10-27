package kz.ordaops.android.feature.business.ops

import androidx.compose.runtime.Composable

/** Нативные экраны раздела: `pageID` → экран. Подключены в [kz.ordaops.android.feature.business.NativePage]. */
internal val opsPages: Map<String, @Composable () -> Unit> = mapOf(
    "tasks" to { TasksScreen() },
    // Старый адрес задач оператора — на сайте он ведёт в те же задачи.
    "operator-tasks" to { TasksScreen() },
    // Монитор сервера на сайте — то же, что вкладка «Сервер» платформы.
    "server-monitor" to { kz.ordaops.android.feature.business.platform.ServerTab() },
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
    "knowledge-admin" to { KnowledgeAdminScreen() },
    "news" to { NewsScreen() },
    "moderation" to { ModerationScreen() },
    "stations" to { StationsScreen() },
)
