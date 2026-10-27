package kz.ordaops.android.feature.business.finance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kz.ordaops.android.feature.business.BizFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Отчёты: /api/admin/reports/bundle. Порт ReportModels.swift (без сырых строк операций).

@Serializable
data class FinancialTotals(
    val incomeCash: Double = 0.0,
    val incomeKaspi: Double = 0.0,
    val incomeOnline: Double = 0.0,
    val incomeCard: Double = 0.0,
    val totalIncome: Double = 0.0,
    val totalExpense: Double = 0.0,
    val profit: Double = 0.0,
    val transactionCount: Int = 0,
    val avgTransaction: Double = 0.0,
) {
    val incomeNonCash: Double get() = incomeKaspi + incomeCard + incomeOnline
}

/** Столбик графика: день, неделя или месяц. */
@Serializable
data class ReportBucket(
    val key: String = "",
    val label: String = "",
    val sortISO: String = "",
    val income: Double = 0.0,
    val expense: Double = 0.0,
    val profit: Double = 0.0,
    val count: Int = 0,
)

@Serializable
data class ReportCompanyIncome(
    val companyId: String = "",
    val name: String = "Точка",
    @SerialName("value") val amount: Double = 0.0,
    val cash: Double = 0.0,
    val kaspi: Double = 0.0,
    val online: Double = 0.0,
    val card: Double = 0.0,
    val count: Int = 0,
)

@Serializable
data class ReportCompanyStat(
    val income: Double = 0.0,
    val expense: Double = 0.0,
    val profit: Double? = null,
    val transactions: Int = 0,
) {
    val net: Double get() = profit ?: (income - expense)
    val margin: Double? get() = if (income > 0) net / income else null
}

@Serializable
data class ReportAggregate(
    val dateFrom: String = "",
    val dateTo: String = "",
    val prevFrom: String = "",
    val prevTo: String = "",
    val totalsCur: FinancialTotals = FinancialTotals(),
    val totalsPrev: FinancialTotals = FinancialTotals(),
    val chartData: List<ReportBucket> = emptyList(),
    val expenseByCategory: Map<String, Double> = emptyMap(),
    val incomeByCompany: Map<String, ReportCompanyIncome> = emptyMap(),
    val companyStats: Map<String, ReportCompanyStat> = emptyMap(),
    val companyStatsPrev: Map<String, ReportCompanyStat> = emptyMap(),
) {
    val incomeChange: Double? get() = BizFormat.change(totalsCur.totalIncome, totalsPrev.totalIncome)
    val profitChange: Double? get() = BizFormat.change(totalsCur.profit, totalsPrev.profit)
    val expenseChange: Double? get() = BizFormat.change(totalsCur.totalExpense, totalsPrev.totalExpense)
    val companyIncome: List<ReportCompanyIncome> get() = incomeByCompany.values.sortedByDescending { it.amount }
    val expenseCategories: List<Pair<String, Double>> get() = expenseByCategory.map { it.key to it.value }.sortedByDescending { it.second }
}

@Serializable
data class ReportExpenseCategory(val name: String = "Без категории", val amount: Double = 0.0)

/** Статья расходов (группа ОПиУ) с разбивкой по категориям. */
@Serializable
data class ReportExpenseArticle(
    val group: String = "",
    val label: String = "Статья",
    val offChain: Boolean = false,
    val amount: Double = 0.0,
    val prevAmount: Double = 0.0,
    val categories: List<ReportExpenseCategory> = emptyList(),
) {
    val change: Double? get() = BizFormat.change(amount, prevAmount)
}

@Serializable
data class ReportBundle(
    val aggregate: ReportAggregate = ReportAggregate(),
    val expenseByGroup: List<ReportExpenseArticle> = emptyList(),
    val impreciseNightKaspiCount: Int = 0,
)

/** Фильтры отчёта — как на сайте. */
data class ReportQuery(
    val from: String,
    val to: String,
    val companyId: String? = null,
    /** `all`, `day`, `night`. */
    val shift: String = "all",
    /** `auto`, `day`, `week`, `month`. */
    val grouping: String = "auto",
    val compareYear: Boolean = false,
    val includeExtra: Boolean = false,
) {
    val resolvedGroup: String get() = if (grouping == "auto") autoGroup(from, to) else grouping

    fun queryItems(rows: String, asOf: String): Map<String, String> = buildMap {
        put("from", from); put("to", to); put("group", resolvedGroup); put("rows", rows); put("as_of", asOf)
        if (!companyId.isNullOrEmpty()) put("company_id", companyId)
        if (shift != "all") put("shift", shift)
        if (compareYear) put("compare", "year")
        if (includeExtra) put("include_extra", "1")
    }

    companion object {
        /** Шаг графика по длине периода: до 31 дня — дни, до 120 — недели, дальше — месяцы. */
        fun autoGroup(from: String, to: String): String {
            val a = runCatching { LocalDate.parse(from) }.getOrNull() ?: return "day"
            val b = runCatching { LocalDate.parse(to) }.getOrNull() ?: return "day"
            val days = ChronoUnit.DAYS.between(a, b) + 1
            return when {
                days <= 31 -> "day"
                days <= 120 -> "week"
                else -> "month"
            }
        }

        val groupings = listOf("auto" to "Авто", "day" to "Дни", "week" to "Недели", "month" to "Месяцы")
        val shifts = listOf("all" to "Все смены", "day" to "День", "night" to "Ночь")
    }
}
