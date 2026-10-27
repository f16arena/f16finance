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
    "cashflow" to { CashflowScreen() },
    "reports" to { ReportsScreen() },
    "sales-kpi" to { SalesKpiScreen() },
    "weekly-report" to { WeeklyReportScreen() },
    "goals" to { GoalsScreen() },
    "tax" to { TaxScreen() },
    "ai-cfo" to { AiCfoScreen() },
    "analytics" to { MonthlyAnalyticsScreen() },
    "expense-analysis" to { ExpenseAnalysisScreen() },
    "team-analysis" to { TeamAnalysisScreen() },
    "valuation" to { ValuationScreen() },
    "simulation" to { SimulationScreen() },
    "analysis" to { AnalysisScreen() },
    "forecast" to { AnalysisScreen() },
    "branch-plan" to { BranchPlanScreen() },
)
