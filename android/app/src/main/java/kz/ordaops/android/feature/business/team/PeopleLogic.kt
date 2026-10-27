package kz.ordaops.android.feature.business.team

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// Чистая логика «Эффективности», «Рейтинга» и «Достижений» операторов. Без Compose
// и Android — покрыта юнит-тестами. Источник правды — страницы сайта
// `performance`, `ratings`, `operator-achievements` и `lib/achievements.ts`.

// ── Периоды ──────────────────────────────────────────────────────────────────

/**
 * Периоды разделов «по людям». Те же, что на сайте: границы календарные
 * (месяц — с 1-го по последний день, неделя — понедельник–воскресенье).
 */
enum class PeoplePeriod(val label: String) {
    ThisWeek("Неделя"),
    LastWeek("Прошлая неделя"),
    ThisMonth("Месяц"),
    LastMonth("Прошлый месяц"),
    ThisYear("Год"),
    ;

    /** `ГГГГ-ММ-ДД` — начало и конец периода. */
    fun bounds(today: LocalDate = LocalDate.now()): Pair<String, String> {
        val (from, to) = when (this) {
            ThisWeek -> WeekMath.monday(today).let { it to it.plusDays(6) }
            LastWeek -> WeekMath.monday(today).minusWeeks(1).let { it to it.plusDays(6) }
            ThisMonth -> YearMonth.from(today).let { it.atDay(1) to it.atEndOfMonth() }
            LastMonth -> YearMonth.from(today).minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }
            ThisYear -> LocalDate.of(today.year, 1, 1) to LocalDate.of(today.year, 12, 31)
        }
        return WeekMath.format(from) to WeekMath.format(to)
    }
}

/** Такой же по длине период вплотную перед заданным — для стрелок «к прошлому периоду». */
fun previousRange(from: String, to: String): Pair<String, String> {
    val start = WeekMath.parse(from) ?: return from to to
    val end = WeekMath.parse(to) ?: return from to to
    val length = ChronoUnit.DAYS.between(start, end).coerceAtLeast(0) + 1
    val prevEnd = start.minusDays(1)
    val prevStart = prevEnd.minusDays(length - 1)
    return WeekMath.format(prevStart) to WeekMath.format(prevEnd)
}

// ── Эффективность (PI) ───────────────────────────────────────────────────────

/** Словесная оценка PI. Границы те же, что на сайте. */
enum class PerformanceGrade(val label: String) {
    Excellent("Превосходно"), Good("Хорошо"), Norm("Норма"), Below("Ниже нормы"), Weak("Слабо");

    companion object {
        fun of(pi: Double): PerformanceGrade = when {
            pi >= 1.15 -> Excellent
            pi >= 1.05 -> Good
            pi >= 0.95 -> Norm
            pi >= 0.85 -> Below
            else -> Weak
        }
    }
}

/** Смена внутри рейтинга: сколько принесла и сколько от неё ждали. */
data class PerfShift(
    val date: String,
    val shift: String,
    val companyId: String,
    val actual: Double,
    val expected: Double,
    val pi: Double,
    val source: String,
) {
    val isNight: Boolean get() = shift == "night"

    /** Сервер режет PI в [0.5; 2.0]: смена у границы — повод проверить данные, а не рекорд. */
    val isClipped: Boolean get() = pi >= 1.99 || pi <= 0.51
}

data class PerfItem(
    val operatorId: String,
    val operatorName: String,
    val shortName: String?,
    val shifts: Int,
    val totalRevenue: Double,
    val avgPerShift: Double,
    val pi: Double,
    val qualifying: Boolean,
    val details: List<PerfShift>,
) {
    val displayName: String get() = shortName?.trim()?.takeIf { it.isNotEmpty() } ?: operatorName
    val grade: PerformanceGrade get() = PerformanceGrade.of(pi)

    /** Сумма норм по всем сменам периода. */
    val expectedTotal: Double get() = details.sumOf { it.expected }

    /** Сколько человек принёс сверх нормы (или недодал) — обоснование премии в деньгах. */
    val aboveNorm: Double get() = totalRevenue - expectedTotal
}

data class PerfRanking(
    val items: List<PerfItem>,
    val baselineShifts: Int,
    val baselineSlots: Int,
    val baselineFrom: String?,
    val baselineTo: String?,
    val minQualifyingShifts: Int,
) {
    /** Порог «сколько смен нужно, чтобы попасть в рейтинг» задаёт сервер. */
    val qualified: List<PerfItem> get() = items.filter { it.qualifying }
    val coldStart: List<PerfItem> get() = items.filter { !it.qualifying }

    /** Средний PI по тем, кого можно сравнивать. */
    val averagePi: Double? get() = qualified.takeIf { it.isNotEmpty() }?.let { q -> q.sumOf { it.pi } / q.size }

    /**
     * Только день или только ночь: PI и счётчики пересчитываются по нужным сменам,
     * формула та же, что на сервере — Σ(PI·ожидание)/Σ(ожидание). `null` — смен нет.
     */
    fun forShift(shift: String?): PerfRanking {
        if (shift == null) return this
        val rebuilt = items.mapNotNull { op ->
            val matched = op.details.filter { it.shift == shift }
            if (matched.isEmpty()) return@mapNotNull null
            val revenue = matched.sumOf { it.actual }
            val expected = matched.sumOf { it.expected }
            val weighted = matched.sumOf { it.pi * it.expected }
            val pi = if (expected > 0) weighted / expected else 1.0
            op.copy(
                shifts = matched.size,
                totalRevenue = revenue,
                avgPerShift = revenue / matched.size,
                pi = Math.round(pi * 1000) / 1000.0,
                qualifying = matched.size >= minQualifyingShifts,
                details = matched,
            )
        }.sortedWith { a, b ->
            when {
                a.qualifying != b.qualifying -> if (a.qualifying) -1 else 1
                a.qualifying -> b.pi.compareTo(a.pi)
                else -> b.totalRevenue.compareTo(a.totalRevenue)
            }
        }
        return copy(items = rebuilt)
    }

    companion object {
        /** Ответ `GET /api/admin/performance/ranking`: `{ data: { ranking, baseline, config } }`. */
        fun from(root: JsonElement): PerfRanking {
            val d = root.unwrapData().asObject()
            val baseline = d?.get("baseline").asObject()
            val config = d?.get("config").asObject()
            val items = d?.get("ranking").asObjects().map { o ->
                PerfItem(
                    operatorId = o.str("operator_id") ?: "",
                    operatorName = o.str("operator_name") ?: "Оператор",
                    shortName = o.str("operator_short_name"),
                    shifts = o.int("shifts"),
                    totalRevenue = o.dbl("total_revenue"),
                    avgPerShift = o.dbl("avg_revenue_per_shift"),
                    pi = o.dblOrNull("pi") ?: 1.0,
                    qualifying = o.bool("qualifying", false),
                    details = o["shift_details"].asObjects().map { s ->
                        PerfShift(
                            date = s.str("date") ?: "",
                            shift = s.str("shift") ?: "day",
                            companyId = s.str("company_id") ?: "",
                            actual = s.dbl("actual"),
                            expected = s.dbl("expected"),
                            pi = s.dblOrNull("pi") ?: 1.0,
                            source = s.str("source") ?: "",
                        )
                    },
                )
            }.filter { it.operatorId.isNotEmpty() }
            return PerfRanking(
                items = items,
                baselineShifts = baseline?.int("shifts_count") ?: 0,
                baselineSlots = baseline?.int("slots_count") ?: 0,
                baselineFrom = baseline?.str("from"),
                baselineTo = baseline?.str("to"),
                minQualifyingShifts = config?.int("min_qualifying_shifts")?.takeIf { it > 0 } ?: 3,
            )
        }
    }
}

/** Порядок списка рейтинга. */
enum class PerfSort(val label: String) {
    Pi("По PI"), AboveNorm("Сверх нормы"), Revenue("По выручке"), Shifts("По сменам");

    fun apply(list: List<PerfItem>): List<PerfItem> = when (this) {
        Pi -> list.sortedByDescending { it.pi }
        AboveNorm -> list.sortedByDescending { it.aboveNorm }
        Revenue -> list.sortedByDescending { it.totalRevenue }
        Shifts -> list.sortedByDescending { it.shifts }
    }
}

/** PI предыдущего периода по операторам (для стрелки тренда). */
fun previousPi(previous: PerfRanking?): Map<String, Double> =
    previous?.items?.associate { it.operatorId to it.pi }.orEmpty()

/** Разница PI к прошлому периоду; `null` — человека тогда не было («новый»). */
fun piDelta(pi: Double, previous: Double?): Double? = previous?.let { pi - it }

/** Слот «точка · день недели · смена», где факт систематически ниже нормы. */
data class ProblemSlot(val companyId: String, val weekday: Int, val shift: String, val actual: Double, val expected: Double, val count: Int) {
    val ratio: Double get() = if (expected > 0) actual / expected else 1.0
}

/** Те же правила, что `computeProblemSlots` на сайте: ≥2 смен, ожидание > 0, факт ниже 92% нормы. */
fun problemSlots(items: List<PerfItem>, limit: Int = 6): List<ProblemSlot> {
    data class Key(val company: String, val weekday: Int, val shift: String)
    val sums = LinkedHashMap<Key, DoubleArray>() // actual, expected, count
    for (op in items) for (s in op.details) {
        val day = WeekMath.parse(s.date)?.dayOfWeek?.value ?: continue
        val acc = sums.getOrPut(Key(s.companyId, day, s.shift)) { DoubleArray(3) }
        acc[0] += s.actual; acc[1] += s.expected; acc[2] += 1.0
    }
    return sums.map { (k, v) -> ProblemSlot(k.company, k.weekday, k.shift, v[0], v[1], v[2].toInt()) }
        .filter { it.count >= 2 && it.expected > 0 && it.ratio < 0.92 }
        .sortedBy { it.ratio }
        .take(limit)
}

// ── Доходы и операторы для рейтинга и достижений ─────────────────────────────

/** Строка дохода: оператор, дата и сумма четырёх способов оплаты. */
data class IncomeLine(val operatorId: String?, val date: String, val total: Double)

fun parseIncomes(root: JsonElement): List<IncomeLine> =
    root.unwrapData().asObjects().map { o ->
        IncomeLine(
            operatorId = o.str("operator_id"),
            date = o.str("date") ?: "",
            total = o.dbl("cash_amount") + o.dbl("kaspi_amount") + o.dbl("online_amount") + o.dbl("card_amount"),
        )
    }

/** Оператор в минимуме, нужном рейтингу: имя из профиля, если есть. */
data class PersonRef(val id: String, val name: String, val isActive: Boolean = true)

fun parsePersonRefs(root: JsonElement): List<PersonRef> =
    root.unwrapData().asObjects().mapNotNull { o ->
        val id = o.str("id") ?: return@mapNotNull null
        val profile = (o["operator_profiles"] as? JsonArray)?.firstOrNull().asObject() ?: o["operator_profiles"].asObject()
        val full = profile?.str("full_name")?.trim().orEmpty()
        PersonRef(id, full.ifEmpty { o.str("name") ?: o.str("short_name") ?: id }, o.bool("is_active", true))
    }

// ── Рейтинг операторов ───────────────────────────────────────────────────────

data class LeaderEntry(
    val operatorId: String,
    val name: String,
    val revenue: Double,
    /** Смен = строк дохода: одна запись — одна закрытая смена. */
    val shifts: Int,
    val days: Int,
    val avgPerShift: Double,
    /** Доля в выручке периода, проценты. */
    val share: Double,
    val previousRevenue: Double,
) {
    /** Изменение к прошлому периоду, %. `null` — сравнивать не с чем. */
    val change: Double? get() = if (previousRevenue > 0) (revenue - previousRevenue) / previousRevenue * 100 else null
}

/**
 * Лидерборд по выручке — как `ratings/page.tsx`: пустые строки не считаются
 * сменой, операторы без выручки остаются в списке с нулём.
 */
fun buildLeaderboard(incomes: List<IncomeLine>, previous: List<IncomeLine>, operators: List<PersonRef>): List<LeaderEntry> {
    val revenue = LinkedHashMap<String, Double>()
    val shifts = HashMap<String, Int>()
    val days = HashMap<String, MutableSet<String>>()
    for (row in incomes) {
        val id = row.operatorId ?: continue
        if (row.total == 0.0) continue
        revenue[id] = (revenue[id] ?: 0.0) + row.total
        shifts[id] = (shifts[id] ?: 0) + 1
        days.getOrPut(id) { mutableSetOf() }.add(row.date)
    }
    val prev = HashMap<String, Double>()
    for (row in previous) {
        val id = row.operatorId ?: continue
        if (row.total == 0.0) continue
        prev[id] = (prev[id] ?: 0.0) + row.total
    }
    for (op in operators) if (op.id !in revenue) revenue[op.id] = 0.0
    val names = operators.associate { it.id to it.name }
    val total = revenue.values.sum()
    return revenue.map { (id, earned) ->
        val count = shifts[id] ?: 0
        LeaderEntry(
            operatorId = id,
            name = names[id] ?: "Оператор ${id.take(6)}",
            revenue = earned,
            shifts = count,
            days = days[id]?.size ?: 0,
            avgPerShift = if (count > 0) earned / count else 0.0,
            share = if (total > 0) earned / total * 100 else 0.0,
            previousRevenue = prev[id] ?: 0.0,
        )
    }.sortedByDescending { it.revenue }
}

// ── Достижения ───────────────────────────────────────────────────────────────

/** Показатели оператора, по которым выдаются достижения. */
data class AchStat(val operatorId: String, val name: String, val revenue: Double, val shifts: Int, val share: Double) {
    val avgPerShift: Double get() = if (shifts > 0) revenue / shifts else 0.0
}

/** Прогресс к достижению: текущее, цель, единица. */
data class AchProgress(val current: Double, val target: Double, val unit: String) {
    val ratio: Double get() = if (target > 0) (current / target).coerceIn(0.0, 1.0) else 0.0
}

/** Каталог достижений — пороги из `lib/achievements.ts`. */
enum class Achievement(val title: String, val desc: String) {
    Champion("Чемпион", "1-е место по выручке за период"),
    Top3("Призёр", "Вошёл в топ-3 по выручке"),
    Millionaire("Миллионер", "Выручка превысила 1 000 000 ₸"),
    Mega("Мега-миллионер", "Выручка превысила 5 000 000 ₸"),
    Marathoner("Марафонец", "Отработал 20+ смен за период"),
    Iron("Железный", "Отработал 50+ смен за период"),
    Premium("Премиум-кассир", "Средний чек выше среднего на 30%+"),
    Major("Тяжеловес", "Доля выручки больше 20%"),
    ;

    fun isEarned(stat: AchStat, rank: Int, averagePerShift: Double): Boolean = when (this) {
        Champion -> rank == 1
        Top3 -> rank in 2..3
        Millionaire -> stat.revenue >= 1_000_000
        Mega -> stat.revenue >= 5_000_000
        Marathoner -> stat.shifts >= 20
        Iron -> stat.shifts >= 50
        Premium -> stat.shifts >= 5 && averagePerShift > 0 && stat.avgPerShift > averagePerShift * 1.3
        Major -> stat.share >= 20
    }

    /** `null` — прогресс не считается: место и сравнение со средним зависят от остальных. */
    fun progress(stat: AchStat): AchProgress? = when (this) {
        Champion, Top3, Premium -> null
        Millionaire -> AchProgress(minOf(stat.revenue, 1_000_000.0), 1_000_000.0, "₸")
        Mega -> AchProgress(minOf(stat.revenue, 5_000_000.0), 5_000_000.0, "₸")
        Marathoner -> AchProgress(minOf(stat.shifts.toDouble(), 20.0), 20.0, "см")
        Iron -> AchProgress(minOf(stat.shifts.toDouble(), 50.0), 50.0, "см")
        Major -> AchProgress(minOf(stat.share, 20.0), 20.0, "%")
    }
}

data class AchResult(val stat: AchStat, val rank: Int, val earned: List<Achievement>, val locked: List<Achievement>)

/**
 * Свернуть доходы в показатели. «Смена» здесь — рабочий день: роут доходов не
 * отдаёт идентификатор смены, и сайт считает по дням. Без выручки и без дней
 * человек в список не попадает.
 */
fun achievementStats(incomes: List<IncomeLine>, operators: List<PersonRef>): List<AchStat> {
    if (operators.isEmpty()) return emptyList()
    val known = operators.map { it.id }.toSet()
    val revenue = HashMap<String, Double>()
    val days = HashMap<String, MutableSet<String>>()
    for (row in incomes) {
        val id = row.operatorId?.takeIf { it in known } ?: continue
        revenue[id] = (revenue[id] ?: 0.0) + row.total
        if (row.date.isNotEmpty()) days.getOrPut(id) { mutableSetOf() }.add(row.date)
    }
    val total = revenue.values.sum()
    return operators.mapNotNull { p ->
        val earned = revenue[p.id] ?: 0.0
        val shifts = days[p.id]?.size ?: 0
        if (earned <= 0 && shifts == 0) return@mapNotNull null
        AchStat(p.id, p.name, earned, shifts, if (total > 0) earned / total * 100 else 0.0)
    }
}

/** Раздать достижения: люди по убыванию выручки, ранг — место в этом порядке. */
fun computeAchievements(stats: List<AchStat>): List<AchResult> {
    val sorted = stats.sortedByDescending { it.revenue }
    val working = stats.filter { it.shifts > 0 }
    val avg = if (working.isEmpty()) 0.0 else working.sumOf { it.avgPerShift } / working.size
    return sorted.mapIndexed { index, stat ->
        val rank = index + 1
        val (earned, locked) = Achievement.entries.partition { it.isEarned(stat, rank, avg) }
        AchResult(stat, rank, earned, locked)
    }
}

/** Сколько человек получило каждое достижение. */
fun achievementSummary(results: List<AchResult>): Map<Achievement, Int> =
    Achievement.entries.associateWith { a -> results.count { a in it.earned } }

/** `+12%` / `−5%` / `0%`; `abs` убирает «−0». */
fun percentChange(value: Double): String {
    val rounded = Math.round(value)
    return when {
        rounded > 0 -> "+$rounded%"
        rounded < 0 -> "−${abs(rounded)}%"
        else -> "0%"
    }
}
