package kz.ordaops.android.feature.business.store

import androidx.compose.runtime.Composable

/**
 * Нативные экраны раздела: `pageID` → экран. Подключены в [kz.ordaops.android.feature.business.NativePage].
 *
 * Как `NativeSection` на айфоне: что здесь есть — открывается приложением, чего нет
 * (закупки, расходники, производство, настройки магазина, смены магазина, реклама,
 * оплата поставщикам, настройки чека) — сайтом во вкладке Chrome.
 */
internal val storePages: Map<String, @Composable () -> Unit> = mapOf(
    "store" to { StoreOverviewScreen() },
    "store-warehouse" to { StockScreen() },
    "store-showcase" to { ShowcaseScreen() },
    "store-catalog" to { CatalogScreen() },
    "store-requests" to { RequestsScreen(journal = false) },
    "store-requests-journal" to { RequestsScreen(journal = true) },
    "store-movements" to { MovementsScreen() },
    "store-receipts" to { ReceiptsScreen(posting = false) },
    "store-postings" to { ReceiptsScreen(posting = true) },
    "store-writeoffs" to { WriteoffsScreen() },
    "store-revisions" to { RevisionsScreen() },
    "store-suppliers" to { SuppliersScreen() },
    "store-forecast" to { StockForecastScreen() },
    "store-analytics" to { StoreAnalyticsScreen() },
)
