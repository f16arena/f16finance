package kz.ordaops.android.feature.business

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Проценты и подписи графиков — порт `Percent` и `Money.axisTick` из OrdaKit. */
object BizFormat {
    private val thinSpace = ' '

    /**
     * `+23 %`, `23,4 %`, `−4 %`. Десятая доля показывается, когда она есть:
     * целое число прятало бы разницу между 23 % и 23,4 %.
     */
    fun percent(value: Double?, signed: Boolean = false): String {
        if (value == null || !value.isFinite()) return "—"
        val rounded = (value * 10).roundToLong() / 10.0
        val magnitude = abs(rounded)
        val text = if (magnitude == magnitude.toLong().toDouble()) {
            magnitude.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", magnitude).replace('.', ',')
        }
        val sign = when {
            signed && rounded > 0 -> "+"
            rounded < 0 -> "−"
            else -> ""
        }
        return "$sign$text$thinSpace%"
    }

    /**
     * Изменение относительно прошлого периода. null, если сравнивать не с чем:
     * рост «с нуля» — не тысяча процентов, а отсутствие базы.
     */
    fun change(current: Double, previous: Double): Double? =
        if (previous == 0.0) null else (current - previous) / abs(previous) * 100

    /**
     * Сокращённая сумма — **только для делений оси графика**: `1,8 млн ₸`, `120к ₸`.
     * Везде, кроме осей, сокращение прячет то, ради чего в приложение и заходят.
     */
    fun axisTick(value: Double): String {
        if (!value.isFinite()) return "—"
        val magnitude = abs(value)
        return when {
            magnitude >= 1_000_000 -> {
                val text = String.format(Locale.US, "%.1f", value / 1_000_000).replace(".0", "").replace('.', ',')
                "$text млн ₸"
            }
            magnitude >= 1_000 -> "${(value / 1_000).roundToLong()}к ₸"
            else -> "${value.roundToLong()} ₸"
        }
    }

    /** «1 точка», «3 точки», «5 точек». */
    fun pointsWord(n: Int): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> "точка"
            m10 in 2..4 && m100 !in 12..14 -> "точки"
            else -> "точек"
        }
    }

    fun expensesWord(n: Int): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> "расход"
            m10 in 2..4 && m100 !in 12..14 -> "расхода"
            else -> "расходов"
        }
    }

    fun itemsWord(n: Int): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> "товар"
            m10 in 2..4 && m100 !in 12..14 -> "товара"
            else -> "товаров"
        }
    }

    /** Первое слово из ФИО; почту вместо имени в приветствие не ставим. */
    fun firstName(fullName: String?): String? {
        val word = fullName?.trim()?.split(' ')?.firstOrNull { it.isNotEmpty() } ?: return null
        return if ('@' in word) null else word
    }

    /** Инициалы для аватара: первые буквы двух слов, без почты. */
    fun initials(fullName: String?, fallback: String): String {
        val letters = (fullName ?: "").split(' ')
            .filter { it.isNotEmpty() && '@' !in it }
            .take(2)
            .joinToString("") { it.first().toString() }
        return letters.ifEmpty { fallback }.uppercase()
    }

    fun greeting(hour: Int): String = when (hour) {
        in 5..11 -> "Доброе утро"
        in 12..17 -> "Добрый день"
        in 18..22 -> "Добрый вечер"
        else -> "Доброй ночи"
    }

    /** Иконка статьи расходов по названию — угадываем по ключевым словам. */
    fun expenseIconKey(name: String): String {
        val n = name.lowercase()
        return when {
            "аренд" in n -> "rent"
            "зарплат" in n || "зп" in n || "аванс" in n -> "salary"
            "товар" in n || "закуп" in n || "продукт" in n -> "goods"
            "налог" in n -> "tax"
            "коммунал" in n || "свет" in n || "электр" in n || "вода" in n -> "utilities"
            "интернет" in n || "связь" in n -> "internet"
            "реклам" in n || "маркет" in n -> "ads"
            "ремонт" in n || "обслуж" in n -> "repair"
            "оборуд" in n || "техник" in n -> "equipment"
            "такси" in n || "транспорт" in n || "достав" in n || "бензин" in n -> "transport"
            "хоз" in n || "убор" in n || "чист" in n -> "cleaning"
            "банк" in n || "комисс" in n || "кредит" in n -> "bank"
            else -> "tag"
        }
    }
}

/** Показатель большого графика аналитики. */
enum class OverviewMetric(val title: String, val higherIsBetter: Boolean) {
    Revenue("Выручка", true),
    Expense("Расходы", false),
    Profit("Прибыль", true);

    fun value(p: OwnerAnalytics.SeriesPoint): Double = when (this) {
        Revenue -> p.revenue
        Expense -> p.expense
        Profit -> p.profit
    }

    fun previous(p: OwnerAnalytics.SeriesPoint): Double = when (this) {
        Revenue -> p.prevRevenue
        Expense -> p.prevExpense
        Profit -> p.prevProfit
    }

    fun value(row: OwnerAnalytics.CompanyRow): Double = when (this) {
        Revenue -> row.revenue
        Expense -> row.expense
        Profit -> row.profit
    }

    /** База сравнения по точке; расход считаем как «выручка минус прибыль» прошлого периода. */
    fun previous(row: OwnerAnalytics.CompanyRow): Double = when (this) {
        Revenue -> row.prevRevenue
        Expense -> row.prevRevenue - row.prevProfit
        Profit -> row.prevProfit
    }
}

/** Точка графика: подписи (длинная для подсказки, короткая для оси) и база сравнения. */
data class ChartPoint(
    val date: LocalDate,
    val label: String,
    val shortLabel: String,
    val value: Double,
    val previousLabel: String,
    val previous: Double,
)

/** Ряд графика аналитики. */
object AnalyticsChart {
    fun points(data: OwnerAnalytics, metric: OverviewMetric): List<ChartPoint> =
        data.series.mapNotNull { p ->
            val date = runCatching { LocalDate.parse(p.date) }.getOrNull() ?: return@mapNotNull null
            ChartPoint(
                date = date,
                label = bucketLabel(p.date, data.period.group),
                shortLabel = AnalyticsPeriod.rangeLabel(p.date, p.date, currentYear = date.year),
                value = metric.value(p),
                previousLabel = bucketLabel(p.prevDate, data.period.group),
                previous = metric.previous(p),
            )
        }

    fun bucketLabel(iso: String, group: String): String = when (group) {
        "week" -> "неделя с " + AnalyticsPeriod.rangeLabel(iso, iso)
        "month" -> runCatching {
            val d = LocalDate.parse(iso)
            d.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru")) + " " + d.year
        }.getOrDefault(iso)
        else -> AnalyticsPeriod.rangeLabel(iso, iso)
    }
}
