package kz.ordaops.android.feature.business.finance

import androidx.compose.runtime.Composable

/**
 * Нативные экраны раздела «Финансы»: `pageID` → экран. Подключены в
 * [kz.ordaops.android.feature.business.NativePage]. Идентификаторы — как в
 * `NativeSection.pageIDs` на айфоне; чего здесь нет, открывается сайтом.
 */
internal val financePages: Map<String, @Composable () -> Unit> = mapOf(
    "income" to { LedgerScreen(LedgerKind.Income) },
    "expenses" to { LedgerScreen(LedgerKind.Expense) },
    "expenses-pending" to { ApprovalsScreen() },
    "expense-whitelist" to { WhitelistScreen() },
    "categories" to { CategoriesScreen() },
    "point-debts" to { PointDebtsScreen() },
    "profitability" to { PnlScreen() },
)
