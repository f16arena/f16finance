package kz.ordaops.android.feature.business

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Выбранный период аналитики. Порт `AnalyticsPeriod` из AnalyticsFilter.swift:
 * границы считаются здесь, сервер получает готовые даты `YYYY-MM-DD`.
 */
sealed class AnalyticsPeriod(val key: String) {
    data object Today : AnalyticsPeriod("today")
    data object Yesterday : AnalyticsPeriod("yesterday")
    data object ThisWeek : AnalyticsPeriod("thisWeek")
    data object LastWeek : AnalyticsPeriod("lastWeek")
    data object ThisMonth : AnalyticsPeriod("thisMonth")
    data object LastMonth : AnalyticsPeriod("lastMonth")
    data object ThisQuarter : AnalyticsPeriod("thisQuarter")
    data object ThisYear : AnalyticsPeriod("thisYear")
    data object Last7Days : AnalyticsPeriod("last7Days")
    data object Last30Days : AnalyticsPeriod("last30Days")
    data object Last90Days : AnalyticsPeriod("last90Days")
    data object Last12Months : AnalyticsPeriod("last12Months")
    data class Custom(val from: String, val to: String) : AnalyticsPeriod("custom:$from:$to")

    val isCustom: Boolean get() = this is Custom

    /** Короткое имя для кнопки быстрого выбора. */
    val shortTitle: String
        get() = when (this) {
            Today -> "День"
            LastWeek -> "Прошлая"
            LastMonth -> "Прошлый"
            ThisWeek -> "Неделя"
            ThisMonth -> "Месяц"
            ThisQuarter -> "Квартал"
            ThisYear -> "Год"
            Last7Days -> "7 дней"
            Last30Days -> "30 дней"
            Last90Days -> "90 дней"
            Last12Months -> "12 мес"
            else -> title
        }

    val title: String
        get() = when (this) {
            Today -> "Сегодня"
            Yesterday -> "Вчера"
            ThisWeek -> "Эта неделя"
            LastWeek -> "Прошлая неделя"
            ThisMonth -> "Этот месяц"
            LastMonth -> "Прошлый месяц"
            ThisQuarter -> "Этот квартал"
            ThisYear -> "Этот год"
            Last7Days -> "Последние 7 дней"
            Last30Days -> "Последние 30 дней"
            Last90Days -> "Последние 90 дней"
            Last12Months -> "Последние 12 месяцев"
            is Custom -> rangeLabel(from, to)
        }

    /** Границы периода, обе включительно. «Сегодня» — по поясу бизнеса, как на сервере. */
    fun bounds(today: LocalDate = businessToday()): Pair<String, String> {
        fun iso(d: LocalDate) = d.toString()
        return when (this) {
            Today -> iso(today) to iso(today)
            Yesterday -> today.minusDays(1).let { iso(it) to iso(it) }
            ThisWeek, LastWeek -> {
                // Неделя с понедельника.
                var monday = today.with(DayOfWeek.MONDAY)
                if (this == LastWeek) monday = monday.minusDays(7)
                iso(monday) to iso(monday.plusDays(6))
            }
            ThisMonth, LastMonth -> {
                var first = today.withDayOfMonth(1)
                if (this == LastMonth) first = first.minusMonths(1)
                iso(first) to iso(first.plusMonths(1).minusDays(1))
            }
            ThisQuarter -> {
                val startMonth = (today.monthValue - 1) / 3 * 3 + 1
                val first = LocalDate.of(today.year, startMonth, 1)
                iso(first) to iso(first.plusMonths(3).minusDays(1))
            }
            ThisYear -> "${today.year}-01-01" to "${today.year}-12-31"
            Last7Days -> iso(today.minusDays(6)) to iso(today)
            Last30Days -> iso(today.minusDays(29)) to iso(today)
            Last90Days -> iso(today.minusDays(89)) to iso(today)
            // Двенадцать целых месяцев, включая текущий: помесячные отчёты не должны начинаться с середины месяца.
            Last12Months -> iso(today.withDayOfMonth(1).minusMonths(11)) to iso(today)
            is Custom -> if (from <= to) from to to else to to from
        }
    }

    companion object {
        /** Часовой пояс бизнеса: у владельца в поездке сутки не должны съезжать на часы. */
        val BusinessZone: ZoneId = ZoneId.of("Asia/Almaty")

        fun businessToday(): LocalDate = LocalDate.now(BusinessZone)

        /**
         * Готовые варианты в порядке показа. Лениво: companion поднимается вместе с
         * базовым классом, и объекты-наследники в этот момент ещё null.
         */
        val presets: List<AnalyticsPeriod> by lazy {
            listOf(
                Today, Yesterday, ThisWeek, LastWeek, ThisMonth, LastMonth,
                Last7Days, Last30Days, Last90Days, ThisQuarter, ThisYear, Last12Months,
            )
        }

        /** Обратно из [key] — период переживает перезапуск. */
        fun fromKey(key: String?): AnalyticsPeriod? {
            if (key == null) return null
            if (key.startsWith("custom:")) {
                val parts = key.split(':')
                // custom:YYYY-MM-DD:YYYY-MM-DD
                return if (parts.size == 3) Custom(parts[1], parts[2]) else null
            }
            return presets.firstOrNull { it.key == key }
        }

        private val shortMonths = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

        /** «3–9 сен», «28 авг – 3 сен», «1 янв 2025 – 31 мар 2025». */
        fun rangeLabel(from: String, to: String, currentYear: Int = LocalDate.now().year): String {
            val a = runCatching { LocalDate.parse(from) }.getOrNull()
            val b = runCatching { LocalDate.parse(to) }.getOrNull()
            if (a == null || b == null) return "$from – $to"
            val showYear = a.year != currentYear || b.year != currentYear
            fun day(d: LocalDate) = d.dayOfMonth.toString()
            fun month(d: LocalDate) = shortMonths[d.monthValue - 1]
            return when {
                from == to -> "${day(a)} ${month(a)}" + if (showYear) " ${a.year}" else ""
                a.year == b.year && a.monthValue == b.monthValue ->
                    "${day(a)}–${day(b)} ${month(b)}" + if (showYear) " ${b.year}" else ""
                showYear -> "${day(a)} ${month(a)} ${a.year} – ${day(b)} ${month(b)} ${b.year}"
                else -> "${day(a)} ${month(a)} – ${day(b)} ${month(b)}"
            }
        }
    }
}

/** С чем сравнивать выбранный период. */
enum class AnalyticsCompare(val wire: String, val title: String, val shortTitle: String) {
    Previous("prev", "С прошлым периодом", "прошлый период"),
    Year("year", "С прошлым годом", "прошлый год"),
}

/** Фильтр целиком: что владелец выставил и что уйдёт на сервер. */
data class AnalyticsFilter(
    val period: AnalyticsPeriod = AnalyticsPeriod.ThisMonth,
    /** Пусто — все точки. */
    val companyIds: Set<String> = emptySet(),
    val compare: AnalyticsCompare = AnalyticsCompare.Previous,
) {
    val isAllCompanies: Boolean get() = companyIds.isEmpty()
}
