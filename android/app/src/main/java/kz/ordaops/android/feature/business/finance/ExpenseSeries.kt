package kz.ordaops.android.feature.business.finance

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import kotlin.math.roundToLong

// Серия расходов: одна сессия мастера → N записей по периодам (налог за полгода,
// оплаченный одним платежом, лежит в отчётах помесячно). Порт чистой логики
// `lib/domain/expense-series.ts`; запись — `POST /api/admin/expenses/wizard/submit-series`.

enum class SeriesKind(val wire: String, val title: String) {
    Day("day", "День"), Week("week", "Неделя"), Month("month", "Месяц"), Quarter("quarter", "Квартал");

    /** Потолок периодов: дни — до месяца, остальное — 24. */
    val maxPeriods get() = if (this == Day) 31 else 24
}

/** Как понимать введённую сумму. */
enum class SeriesAmountMode(val title: String) { PerPeriod("За каждый период"), Split("За все — разделить") }

data class SeriesRow(val date: String, val cash: Double, val kaspi: Double, val label: String) {
    val total get() = cash + kaspi
}

object ExpenseSeriesLogic {
    private val months = listOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")
    private val weekdays = listOf("вс", "пн", "вт", "ср", "чт", "пт", "сб")

    /** Прибавить месяцы, не выпрыгивая из целевого: 31 января + 1 месяц = конец февраля. */
    fun addMonthsClamped(iso: String, months: Int): String {
        val d = LocalDate.parse(iso)
        val target = d.withDayOfMonth(1).plusMonths(months.toLong())
        return target.withDayOfMonth(minOf(d.dayOfMonth, target.lengthOfMonth())).toString()
    }

    fun addDays(iso: String, days: Int): String = LocalDate.parse(iso).plusDays(days.toLong()).toString()

    fun label(iso: String, kind: SeriesKind): String {
        val d = LocalDate.parse(iso)
        return when (kind) {
            SeriesKind.Month -> "${months[d.monthValue - 1]} ${d.year}"
            SeriesKind.Quarter -> "${(d.monthValue - 1) / 3 + 1} кв. ${d.year}"
            SeriesKind.Day -> "%02d.%02d.%d, %s".format(d.dayOfMonth, d.monthValue, d.year, weekdays[d.dayOfWeek.value % 7])
            SeriesKind.Week -> "неделя с %02d.%02d.%d".format(d.dayOfMonth, d.monthValue, d.year)
        }
    }

    /** Даты периодов от стартовой. */
    fun dates(start: String, kind: SeriesKind, count: Int): List<String> {
        val n = count.coerceIn(0, kind.maxPeriods)
        return (0 until n).map { i ->
            when (kind) {
                SeriesKind.Day -> addDays(start, i)
                SeriesKind.Week -> addDays(start, 7 * i)
                SeriesKind.Month -> addMonthsClamped(start, i)
                SeriesKind.Quarter -> addMonthsClamped(start, 3 * i)
            }
        }
    }

    /** Общая сумма поровну, целые тенге; остаток — в последний период (100 000 на 3 → 33 333, 33 333, 33 334). */
    fun splitTotal(total: Double, count: Int): List<Double> {
        if (count <= 0) return emptyList()
        val value = maxOf(0L, total.roundToLong())
        val base = value / count
        return List(count) { if (it == count - 1) (base + value - base * count).toDouble() else base.toDouble() }
    }

    /** Сумма периода по пропорции «наличные / безнал» карточки: правка цифры не меняет способ оплаты. */
    fun splitPeriod(total: Double, cardCash: Double, cardKaspi: Double): Pair<Double, Double> {
        val v = maxOf(0.0, total)
        if (cardKaspi <= 0) return v to 0.0
        if (cardCash <= 0) return 0.0 to v
        val cash = (v * (cardCash / (cardCash + cardKaspi))).roundToLong().toDouble()
        return cash to maxOf(0.0, v - cash)
    }

    fun rows(start: String, kind: SeriesKind, count: Int, cash: Double, kaspi: Double, mode: SeriesAmountMode): List<SeriesRow> {
        val ds = dates(start, kind, count)
        val totals = if (mode == SeriesAmountMode.Split) splitTotal(cash + kaspi, ds.size) else null
        return ds.mapIndexed { i, d ->
            val (c, k) = if (totals != null) splitPeriod(totals[i], cash, kaspi) else cash to kaspi
            SeriesRow(d, c, k, label(d, kind))
        }
    }

    /** Что не так с серией — формулировки серверные; null — можно отправлять. */
    fun problem(rows: List<SeriesRow>, kind: SeriesKind): String? = when {
        rows.size < 2 -> "В серии должно быть минимум 2 периода"
        rows.size > kind.maxPeriods -> "Слишком много периодов (максимум ${kind.maxPeriods})"
        rows.any { it.total <= 0 } -> "Сумма каждого периода должна быть больше 0"
        else -> null
    }

    fun body(sessionId: String, kind: SeriesKind, rows: List<SeriesRow>): JsonObject = buildJsonObject {
        put("session_id", sessionId)
        put("period_kind", kind.wire)
        put("periods", JsonArray(rows.map {
            buildJsonObject { put("date", it.date); put("amount_cash", it.cash); put("amount_kaspi", it.kaspi); put("label", it.label) }
        }))
    }
}
