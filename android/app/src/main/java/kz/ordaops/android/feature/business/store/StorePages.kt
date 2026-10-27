package kz.ordaops.android.feature.business.store

import androidx.compose.runtime.Composable

/**
 * Нативные экраны раздела: `pageID` → экран. Подключены в [kz.ordaops.android.feature.business.NativePage].
 *
 * Все 23 страницы раздела «Магазин» открываются приложением. Разбор ответов и тела
 * записей сверены с текущим вебом (маршруты `app/api/admin/store` и `inventory`), а не
 * с iOS, который отстал.
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
    "store-settings" to { StoreSettingsScreen() },
    "store-shifts" to { StoreShiftsScreen() },
    "store-receipt-settings" to { ReceiptSettingsScreen() },
    "store-advertising" to { AdvertisingScreen() },
    "store-billing" to { BillingScreen() },
    "store-purchase-orders" to { PurchaseOrdersScreen() },
    "store-purchase-plan" to { PurchasePlanScreen() },
    "store-consumables" to { ConsumablesScreen() },
    "production" to { ProductionScreen() },
)
