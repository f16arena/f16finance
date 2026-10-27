package kz.ordaops.android.feature.business.finance

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// Чистая логика выписки «Доходы / Расходы» — без Compose, чтобы проверять тестом.
// Порт вычислений из LedgerStatementScreen.swift.

/** Способы оплаты дохода в порядке показа. */
object Methods {
    const val Cash = "Наличные"
    const val Kaspi = "Kaspi"
    const val Card = "Карта"
    const val Online = "Онлайн"
}

/** Операция выписки — общий вид дохода и расхода. */
data class LedgerOp(
    val id: String,
    val date: String,
    val title: String,
    val subtitle: String?,
    val amount: Double,
    val isPending: Boolean,
    val companyId: String?,
    /** Разбивка по способам: «Наличные 5 000 ₸». */
    val parts: List<Pair<String, Double>>,
    val comment: String?,
    /** Корзины, в которые попадает операция: статья или способы оплаты. */
    val buckets: Set<String>,
    /** Смена дохода (`day`/`night`) — для значка. */
    val shift: String? = null,
)

/** День выписки: итог и операции. */
data class LedgerDay(val date: String, val total: Double, val items: List<LedgerOp>)

object LedgerLogic {
    fun shiftLabel(shift: String?): String? = when (shift) {
        "day" -> "День"
        "night" -> "Ночь"
        else -> null
    }

    /** Доходы → операции. Название — точка, подпись — смена и комментарий. */
    fun incomeOps(rows: List<IncomeRow>, companyName: (String?) -> String?): List<LedgerOp> = rows.map { row ->
        val parts = listOf(
            Methods.Cash to row.cashAmount,
            Methods.Kaspi to row.kaspiAmount,
            Methods.Card to row.cardAmount,
            Methods.Online to row.onlineAmount,
        ).filter { it.second != 0.0 }
        LedgerOp(
            id = row.id,
            date = row.date,
            title = companyName(row.companyId) ?: "Выручка",
            subtitle = listOfNotNull(shiftLabel(row.shift)?.let { "$it, смена" }, row.comment)
                .filter { it.isNotEmpty() }.joinToString(" · "),
            amount = row.total,
            isPending = false,
            companyId = row.companyId,
            parts = parts,
            comment = row.comment,
            buckets = parts.map { it.first }.toSet(),
            shift = row.shift,
        )
    }

    /** Расходы → операции. Отклонённые не считаем; пустая статья — «Без категории». */
    fun expenseOps(rows: List<ExpenseRow>, companyName: (String?) -> String?): List<LedgerOp> =
        rows.filter { it.isCounted }.map { row ->
            val category = row.category?.takeIf { it.isNotEmpty() } ?: "Без категории"
            val parts = listOf(Methods.Cash to row.cashAmount, Methods.Kaspi to row.kaspiAmount).filter { it.second != 0.0 }
            LedgerOp(
                id = row.id,
                date = row.date,
                title = category,
                subtitle = listOfNotNull(companyName(row.companyId), row.comment).filter { it.isNotEmpty() }.joinToString(" · "),
                amount = row.total,
                isPending = row.isPending,
                companyId = row.companyId,
                parts = parts,
                comment = row.comment,
                buckets = setOf(category),
            )
        }

    /**
     * Фильтр выписки: статья/способ, точка и поиск по названию, подписи и сумме.
     * Порядок — свежие дни сверху, внутри дня крупные суммы выше.
     */
    fun filter(ops: List<LedgerOp>, bucket: String?, companyId: String?, query: String): List<LedgerOp> {
        val needle = query.trim().lowercase()
        val digits = needle.replace(" ", "")
        return ops.filter { op ->
            (bucket == null || bucket in op.buckets) &&
                (companyId == null || op.companyId == companyId) &&
                (
                    needle.isEmpty() ||
                        op.title.lowercase().contains(needle) ||
                        (op.subtitle?.lowercase()?.contains(needle) ?: false) ||
                        op.amount.toLong().toString().contains(digits)
                    )
        }.sortedWith(compareByDescending<LedgerOp> { it.date }.thenByDescending { it.amount })
    }

    /**
     * Сумма операции под выбранным пузырём. У дохода «Kaspi» — это только его
     * безналичная часть: иначе в итог попали бы и наличные.
     */
    fun shownAmount(op: LedgerOp, isIncome: Boolean, bucket: String?): Double {
        if (!isIncome || bucket == null) return op.amount
        return op.parts.firstOrNull { it.first == bucket }?.second ?: 0.0
    }

    /** Пузыри сверху: статьи расходов или способы оплаты доходов, от крупных к мелким. */
    fun buckets(ops: List<LedgerOp>, isIncome: Boolean, companyId: String?): List<Pair<String, Double>> {
        val sums = LinkedHashMap<String, Double>()
        for (op in ops) {
            if (companyId != null && op.companyId != companyId) continue
            if (isIncome) {
                for ((name, value) in op.parts) sums[name] = (sums[name] ?: 0.0) + value
            } else {
                sums[op.title] = (sums[op.title] ?: 0.0) + op.amount
            }
        }
        return sums.map { it.key to it.value }.sortedByDescending { it.second }
    }

    /** Сколько по каждой точке — с учётом статьи, но без фильтра по точке. */
    fun companyTotals(ops: List<LedgerOp>, isIncome: Boolean, bucket: String?): List<Pair<String, Double>> {
        val sums = LinkedHashMap<String, Double>()
        for (op in ops) {
            if (bucket != null && bucket !in op.buckets) continue
            val id = op.companyId ?: continue
            sums[id] = (sums[id] ?: 0.0) + shownAmount(op, isIncome, bucket)
        }
        return sums.map { it.key to it.value }.filter { it.second != 0.0 }.sortedByDescending { it.second }
    }

    fun days(filtered: List<LedgerOp>, isIncome: Boolean, bucket: String?): List<LedgerDay> =
        filtered.groupBy { it.date }
            .map { (date, items) -> LedgerDay(date, items.sumOf { shownAmount(it, isIncome, bucket) }, items) }
            .sortedByDescending { it.date }

    /** Столбик на каждый день периода, по сегодня: тихий день — ноль, а не дыра. */
    fun dailySums(from: String, to: String, today: String, filtered: List<LedgerOp>, isIncome: Boolean, bucket: String?): List<Pair<String, Double>> {
        val sums = HashMap<String, Double>()
        for (op in filtered) sums[op.date] = (sums[op.date] ?: 0.0) + shownAmount(op, isIncome, bucket)
        val start = runCatching { LocalDate.parse(from) }.getOrNull() ?: return emptyList()
        val last = minOf(runCatching { LocalDate.parse(to) }.getOrDefault(start), runCatching { LocalDate.parse(today) }.getOrDefault(start))
        val out = ArrayList<Pair<String, Double>>()
        var day = start
        while (!day.isAfter(last) && out.size < 400) {
            out += day.toString() to (sums[day.toString()] ?: 0.0)
            day = day.plusDays(1)
        }
        return out
    }

    /** «Сегодня», «Вчера» или «12 сентября, пятница». */
    fun dayTitle(iso: String, today: LocalDate): String {
        val d = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
        return when (d) {
            today -> "Сегодня"
            today.minusDays(1) -> "Вчера"
            else -> {
                val ru = Locale("ru")
                val month = d.month.getDisplayName(TextStyle.FULL, ru)
                val weekday = d.dayOfWeek.getDisplayName(TextStyle.FULL_STANDALONE, ru)
                "${d.dayOfMonth} $month, $weekday"
            }
        }
    }

    fun operationsWord(n: Int): String = plural(n, "операция", "операции", "операций")
    fun articlesWord(n: Int): String = plural(n, "статья", "статьи", "статей")

    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
    }

    /** Индекс цвета закреплён за названием, а не за местом в списке: фильтр не перекрашивает статьи. */
    fun tintIndex(name: String, size: Int): Int {
        var hash = 0
        for (ch in name) hash = (hash * 31 + ch.code) and 0x7FFFFFFF
        return hash % size
    }
}
