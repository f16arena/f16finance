package kz.ordaops.android.feature.business.finance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── ОПиУ: /api/admin/profitability/summary ──────────────────────────────────
//
// Все величины приходят посчитанными с сервера. Считать их здесь значило бы
// держать вторую реализацию той же формулы: сайт показал бы одну EBITDA, а
// приложение — другую. Порт PnlModels.swift.

/** Строка отчёта — цепочка как на сайте. */
enum class PnlLine(val key: String, val title: String) {
    Revenue("revenue", "Выручка"),
    Cogs("cogs", "Себестоимость"),
    GrossProfit("grossProfit", "Валовая прибыль"),
    Operating("operating", "Операционные расходы"),
    Pos("pos", "Комиссия банка"),
    Payroll("payroll", "Зарплаты"),
    PayrollTaxes("payrollTaxes", "Налоги на зарплату"),
    Ebitda("ebitda", "EBITDA"),
    Depreciation("depreciation", "Амортизация"),
    OperatingProfit("operatingProfit", "Операционная прибыль"),
    Financial("financial", "Проценты по кредитам"),
    Tax("tax", "Налог"),
    NonOperating("nonOperating", "Разовые расходы"),
    NetProfit("netProfit", "Чистая прибыль"),
    Capex("capex", "Покупка оборудования"),
    Distribution("distribution", "Выплаты партнёрам");

    /** Итоговая строка (подытог), а не статья. */
    val isTotal: Boolean get() = this in setOf(GrossProfit, Ebitda, OperatingProfit, NetProfit)

    /** Расход — рост плохо. */
    val isExpense: Boolean get() = !isTotal && this != Revenue

    companion object {
        /** Цепочка до чистой прибыли. */
        val chain = listOf(Revenue, Cogs, GrossProfit, Operating, Pos, Payroll, PayrollTaxes, Ebitda, Depreciation, OperatingProfit, Financial, Tax, NonOperating, NetProfit)

        /** После чистой прибыли — в ОПиУ не входят. */
        val offChain = listOf(Capex, Distribution)
    }
}

@Serializable
data class PnlIncome(val cash: Double = 0.0, val kaspi: Double = 0.0, val card: Double = 0.0, val online: Double = 0.0)

@Serializable
data class PnlCategoryAmount(val name: String = "Без статьи", val amount: Double = 0.0)

@Serializable
data class PnlCheck(val expensesAll: Double = 0.0, val declined: Double = 0.0, val declinedCount: Int = 0)

/** Отчёт о прибылях и убытках за месяц. */
@Serializable
data class MonthlyPnl(
    val month: String = "",
    val revenue: Double = 0.0,
    val cashRevenue: Double = 0.0,
    val cashlessRevenue: Double = 0.0,
    val cogs: Double = 0.0,
    val grossProfit: Double = 0.0,
    val operatingExpenses: Double = 0.0,
    val posCommission: Double = 0.0,
    val payroll: Double = 0.0,
    val payrollTaxes: Double = 0.0,
    val otherOperating: Double = 0.0,
    val ebitda: Double = 0.0,
    val ebitdaMargin: Double = 0.0,
    val depreciation: Double = 0.0,
    val amortization: Double = 0.0,
    val operatingProfit: Double = 0.0,
    val financialExpenses: Double = 0.0,
    val incomeTax: Double = 0.0,
    val nonOperating: Double = 0.0,
    val netProfit: Double = 0.0,
    val netMargin: Double = 0.0,
    val capex: Double = 0.0,
    val profitDistribution: Double = 0.0,
    /** Выручка по способам оплаты — раскрытие строки «Выручка». */
    val income: PnlIncome = PnlIncome(),
    /** Статьи внутри каждой строки: ключ — строка отчёта, как на сайте. */
    val categories: Map<String, List<PnlCategoryAmount>> = emptyMap(),
    /** Для сверки с «Расходами»: всё из журнала и отклонённые из них. */
    val check: PnlCheck = PnlCheck(),
) {
    fun value(line: PnlLine): Double = when (line) {
        PnlLine.Revenue -> revenue
        PnlLine.Cogs -> cogs
        PnlLine.GrossProfit -> grossProfit
        PnlLine.Operating -> operatingExpenses
        PnlLine.Pos -> posCommission
        PnlLine.Payroll -> payroll
        PnlLine.PayrollTaxes -> payrollTaxes
        PnlLine.Ebitda -> ebitda
        PnlLine.Depreciation -> depreciation
        PnlLine.OperatingProfit -> operatingProfit
        PnlLine.Financial -> financialExpenses
        PnlLine.Tax -> incomeTax
        PnlLine.NonOperating -> nonOperating
        PnlLine.NetProfit -> netProfit
        PnlLine.Capex -> capex
        PnlLine.Distribution -> profitDistribution
    }

    /** Статьи строки отчёта; у выручки — способы оплаты. */
    fun parts(line: PnlLine): List<PnlCategoryAmount> {
        if (line == PnlLine.Revenue) {
            return listOf(
                PnlCategoryAmount("Наличные", income.cash), PnlCategoryAmount("Безналичный", income.kaspi),
                PnlCategoryAmount("Карта", income.card), PnlCategoryAmount("Онлайн", income.online),
            ).filter { Math.round(it.amount) != 0L }
        }
        return categories[line.key].orEmpty()
    }

    /** Остаётся после покупки оборудования и выплат партнёрам. */
    val leftover: Double get() = netProfit - capex - profitDistribution
}

@Serializable
data class PnlCompany(
    val id: String = "",
    val name: String = "Точка",
    val isExtra: Boolean = false,
    val months: List<MonthlyPnl> = emptyList(),
    val previous: MonthlyPnl? = null,
)

/** Месяц, в котором внесены не все отчёты смен. */
@Serializable
data class PnlIncompleteMonth(
    val month: String = "",
    @SerialName("companyId") val companyId: String = "",
    val company: String = "Точка",
    val days: Int = 0,
    val expectedDays: Int = 0,
)

@Serializable
data class PnlExtra(val names: List<String> = emptyList())

/** Ответ `GET /api/admin/profitability/summary` (в обёртке `data`). */
@Serializable
data class PnlReport(
    val months: List<MonthlyPnl> = emptyList(),
    val previous: MonthlyPnl? = null,
    val companies: List<PnlCompany> = emptyList(),
    val incompleteMonths: List<PnlIncompleteMonth> = emptyList(),
    val extra: PnlExtra = PnlExtra(),
)

/** Чистая логика экрана ОПиУ — для теста. */
object PnlLogic {
    private val names = listOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")

    /** Сдвиг месяца `YYYY-MM` на [offset] месяцев. */
    fun shift(month: String, offset: Int): String {
        val parts = month.split('-').mapNotNull { it.toIntOrNull() }
        if (parts.size != 2) return month
        val total = parts[0] * 12 + (parts[1] - 1) + offset
        return String.format("%04d-%02d", total / 12, total % 12 + 1)
    }

    /** «Сентябрь 2026». */
    fun title(month: String): String {
        val parts = month.split('-').mapNotNull { it.toIntOrNull() }
        if (parts.size != 2 || parts[1] !in 1..12) return month
        return "${names[parts[1] - 1]} ${parts[0]}"
    }

    /** Доля строки в выручке: «12 % выручки» или «—». */
    fun shareText(value: Double, revenue: Double): String =
        if (revenue > 0) kz.ordaops.android.feature.business.BizFormat.percent(value / revenue * 100) + " выручки" else "—"

    /**
     * Статьи строки за два месяца вместе: если статья была только в прошлом
     * месяце, она всё равно видна (с нулём сейчас). Крупные сверху.
     */
    fun mergedParts(line: PnlLine, current: MonthlyPnl, previous: MonthlyPnl?): List<Triple<String, Double, Double>> {
        val cur = current.parts(line).groupBy { it.name }.mapValues { e -> e.value.sumOf { it.amount } }
        val prev = previous?.parts(line).orEmpty().groupBy { it.name }.mapValues { e -> e.value.sumOf { it.amount } }
        return (cur.keys + prev.keys)
            .map { Triple(it, cur[it] ?: 0.0, prev[it] ?: 0.0) }
            .filter { Math.round(it.second) != 0L || Math.round(it.third) != 0L }
            .sortedWith(compareByDescending<Triple<String, Double, Double>> { it.second }.thenByDescending { it.third })
    }

    /** Строки цепочки, которые стоит показывать: итоги и выручка всегда, статьи — если не нули. */
    fun visibleLines(current: MonthlyPnl, previous: MonthlyPnl?): List<PnlLine> =
        PnlLine.chain.filter { line ->
            line.isTotal || line == PnlLine.Revenue ||
                Math.round(current.value(line)) != 0L || (previous?.let { Math.round(it.value(line)) } ?: 0L) != 0L
        }
}
