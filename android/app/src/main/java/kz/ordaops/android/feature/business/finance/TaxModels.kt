package kz.ordaops.android.feature.business.finance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

// Налоги ИП на упрощёнке (/tax). Ставки, пороги и соцплатежи считает сервер
// (`lib/domain/tax`, `GET /api/admin/tax/summary`): приложение не держит их у
// себя, чтобы по цифре, с которой платят в бюджет, не разошлись две реализации.
// Здесь — разбор ответов и единственный пересчёт, который делает и сайт на своей
// странице: оборот с учётом «какие способы оплаты входят в налог» по каждой точке.

@Serializable
data class TaxChannels(
    val cash: Boolean = true,
    val kaspi: Boolean = true,
    val online: Boolean = true,
    val card: Boolean = true,
) {
    val isAll get() = cash && kaspi && online && card
}

/** Налоговые настройки организации: ставка маслихата, реквизиты, фильтр оборота. */
@Serializable
data class TaxSettings(
    /** 2–6 %; null — ещё не выбрана. */
    val rate: Double? = null,
    val companyFullName: String = "",
    val bin: String = "",
    val oked: String = "",
    val vatPayer: Boolean = false,
    val includeExtra: Boolean = false,
    val taxableFilter: Map<String, TaxChannels> = emptyMap(),
)

@Serializable
data class TaxSettingsReply(val tax: TaxSettings = TaxSettings(), val canEdit: Boolean = false)

@Serializable
data class TaxMonthRow(val month: String = "", val revenue: Double = 0.0, val ipn: Double = 0.0, val social: Double = 0.0, val total: Double = 0.0)

@Serializable
data class PayrollTaxes(val employees: Int = 0, val gross: Double = 0.0, val monthlyTax: Double = 0.0)

@Serializable
data class TaxBurden(val ipn: Double = 0.0, val selfSocial: Double = 0.0, val payrollTaxes: Double = 0.0, val total: Double = 0.0, val effectiveRate: Double = 0.0)

@Serializable
data class TaxYearOutlook(
    val yearRevenue: Double = 0.0,
    val projected: Double = 0.0,
    val vatThreshold: Double = 0.0,
    val vatRemaining: Double = 0.0,
    val vatRisk: Boolean = false,
    val simplifiedLimit: Double = 0.0,
    val simplifiedRemaining: Double = 0.0,
    val simplifiedRisk: Boolean = false,
)

@Serializable
data class TaxConstants(
    val year: Int = 0,
    val selfSocialMonthly: Double = 0.0,
    val minRate: Double = 2.0,
    val maxRate: Double = 6.0,
)

@Serializable
data class TaxSummary(
    val from: String = "",
    val to: String = "",
    val rate: Double = 0.0,
    val revenue: Double = 0.0,
    val ipn: Double = 0.0,
    val selfSocial: Double = 0.0,
    val monthsCount: Int = 0,
    val total: Double = 0.0,
    val effectiveRate: Double = 0.0,
    val months: List<TaxMonthRow> = emptyList(),
    val payroll: PayrollTaxes = PayrollTaxes(),
    val burden: TaxBurden = TaxBurden(),
    val year: TaxYearOutlook = TaxYearOutlook(),
    val constants: TaxConstants = TaxConstants(),
)

/** Строка прихода для пересчёта оборота. */
@Serializable
data class TaxIncomeRow(
    val date: String = "",
    @SerialName("company_id") val companyId: String? = null,
    @SerialName("cash_amount") val cash: Double = 0.0,
    @SerialName("kaspi_amount") val kaspi: Double = 0.0,
    @SerialName("online_amount") val online: Double = 0.0,
    @SerialName("card_amount") val card: Double = 0.0,
)

@Serializable
data class TaxRows(val incomes: List<TaxIncomeRow> = emptyList())

/** Налоговое полугодие: декларация 910.00 и оплата. */
data class TaxHalf(val year: Int, val half: Int, val from: String, val to: String, val label: String, val declarationDue: String, val paymentDue: String)

object TaxLogic {
    fun half(year: Int, half: Int): TaxHalf =
        if (half == 1) TaxHalf(year, 1, "$year-01-01", "$year-06-30", "1-е полугодие $year", "$year-08-15", "$year-08-25")
        else TaxHalf(year, 2, "$year-07-01", "$year-12-31", "2-е полугодие $year", "${year + 1}-02-15", "${year + 1}-02-25")

    fun halfOf(date: String): TaxHalf = half(date.substring(0, 4).toInt(), if (date.substring(5, 7).toInt() <= 6) 1 else 2)

    fun previous(h: TaxHalf): TaxHalf = if (h.half == 2) half(h.year, 1) else half(h.year - 1, 2)

    /** Ближайший платёж: прошлое полугодие, пока его срок оплаты не прошёл, иначе текущее. */
    fun nextPayment(today: String): TaxHalf {
        val current = halfOf(today)
        val prev = previous(current)
        return if (today <= prev.paymentDue) prev else current
    }

    /** Варианты выбора: два года назад до текущего полугодия. */
    fun halves(today: String): List<TaxHalf> {
        val current = halfOf(today)
        val out = ArrayList<TaxHalf>()
        var h = current
        repeat(4) { out += h; h = previous(h) }
        return out
    }

    fun rates(min: Double, max: Double): List<Double> = generateSequence(min) { if (it + 0.5 <= max + 1e-9) it + 0.5 else null }.toList()

    fun rateLabel(rate: Double): String = if (rate == rate.toLong().toDouble()) "${rate.toLong()}%" else "${rate.toString().replace('.', ',')}%"

    fun daysUntil(today: String, date: String): Long = ChronoUnit.DAYS.between(LocalDate.parse(today), LocalDate.parse(date))

    /** Налогооблагаемая часть строки: точка вне итогов без галочки не входит, остальное — по фильтру способов оплаты. */
    fun taxable(row: TaxIncomeRow, filter: Map<String, TaxChannels>, extraIds: Set<String>, includeExtra: Boolean): Double {
        val id = row.companyId
        if (id != null && id in extraIds && !includeExtra) return 0.0
        val cfg = id?.let { filter[it] } ?: TaxChannels()
        return (if (cfg.cash) row.cash else 0.0) + (if (cfg.kaspi) row.kaspi else 0.0) +
            (if (cfg.online) row.online else 0.0) + (if (cfg.card) row.card else 0.0)
    }

    /**
     * Налог за период по оборотy с фильтром способов оплаты — как на странице сайта:
     * ИПН от общего оборота периода, соцплатежи «за себя» — за месяцы, где была выручка.
     */
    fun recompute(base: TaxSummary, rows: List<TaxIncomeRow>, filter: Map<String, TaxChannels>, extraIds: Set<String>, includeExtra: Boolean): TaxSummary {
        val byMonth = java.util.TreeMap<String, Double>()
        for (r in rows) {
            val v = taxable(r, filter, extraIds, includeExtra)
            if (v == 0.0) continue
            byMonth.merge(r.date.take(7), v, Double::plus)
        }
        val social = base.constants.selfSocialMonthly
        val months = byMonth.map { (m, rev) ->
            val ipn = (rev * base.rate / 100).roundToLong().toDouble()
            TaxMonthRow(m, rev, ipn, social, ipn + social)
        }
        val revenue = months.sumOf { it.revenue }
        val ipn = (revenue * base.rate / 100).roundToLong().toDouble()
        val selfSocial = social * months.size
        val payrollTaxes = (base.payroll.monthlyTax * months.size).roundToLong().toDouble()
        val total = ipn + selfSocial
        return base.copy(
            revenue = revenue, ipn = ipn, selfSocial = selfSocial, monthsCount = months.size, total = total,
            effectiveRate = if (revenue > 0) total / revenue * 100 else 0.0, months = months,
            burden = TaxBurden(ipn, selfSocial, payrollTaxes, ipn + selfSocial + payrollTaxes, if (revenue > 0) (ipn + selfSocial + payrollTaxes) / revenue * 100 else 0.0),
        )
    }

    /** Сколько откладывать в месяц на налоги: налог периода и налоги за работников на число месяцев. */
    fun monthlyReserve(s: TaxSummary): Double {
        val months = maxOf(1, s.monthsCount)
        return (s.burden.total.takeIf { it > 0 } ?: s.total) / months
    }

    /** Платежи в бюджет: название, КБК, КНП, срок. */
    data class Requisite(val name: String, val kbk: String, val knp: String, val deadline: String)

    val requisites = listOf(
        Requisite("ИПН с упрощёнки (форма 910.00)", "101202", "911", "до 25 числа 2-го месяца после полугодия (25 авг / 25 фев)"),
        Requisite("ИПН с зарплаты работников", "101201", "911", "до 25 числа след. месяца"),
        Requisite("ОПВ работодателя за себя", "904101", "010", "до 25 числа след. месяца"),
        Requisite("ОПВ за работника", "904101", "010", "до 25 числа след. месяца"),
        Requisite("ОПВР работодателя", "904102", "010", "до 25 числа след. месяца"),
        Requisite("ВОСМС работника (2%)", "904201", "121", "до 25 числа след. месяца"),
        Requisite("ОСМС работодателя (3%)", "904201", "122", "до 25 числа след. месяца"),
        Requisite("СО (соц. отчисления, 3.5%)", "904103", "015", "до 25 числа след. месяца"),
    )
}
