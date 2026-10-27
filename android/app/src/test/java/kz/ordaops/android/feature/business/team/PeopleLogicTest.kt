package kz.ordaops.android.feature.business.team

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Эффективность, рейтинг и достижения операторов: чистая логика `PeopleLogic.kt`. */
class PeopleLogicTest {
    private val today = LocalDate.of(2026, 10, 28) // среда

    @Test
    fun `границы периодов календарные`() {
        assertEquals("2026-10-26" to "2026-11-01", PeoplePeriod.ThisWeek.bounds(today))
        assertEquals("2026-10-19" to "2026-10-25", PeoplePeriod.LastWeek.bounds(today))
        assertEquals("2026-10-01" to "2026-10-31", PeoplePeriod.ThisMonth.bounds(today))
        assertEquals("2026-09-01" to "2026-09-30", PeoplePeriod.LastMonth.bounds(today))
        assertEquals("2026-01-01" to "2026-12-31", PeoplePeriod.ThisYear.bounds(today))
    }

    @Test
    fun `предыдущий период вплотную и той же длины`() {
        // Месяц из 31 дня: предыдущие 31 день заканчиваются 30 сентября.
        assertEquals("2026-08-31" to "2026-09-30", previousRange("2026-10-01", "2026-10-31"))
        assertEquals("2026-10-19" to "2026-10-25", previousRange("2026-10-26", "2026-11-01"))
        assertEquals("2026-10-27" to "2026-10-27", previousRange("2026-10-28", "2026-10-28"))
    }

    @Test
    fun `оценки PI по границам сайта`() {
        assertEquals(PerformanceGrade.Excellent, PerformanceGrade.of(1.15))
        assertEquals(PerformanceGrade.Good, PerformanceGrade.of(1.05))
        assertEquals(PerformanceGrade.Norm, PerformanceGrade.of(0.95))
        assertEquals(PerformanceGrade.Below, PerformanceGrade.of(0.85))
        assertEquals(PerformanceGrade.Weak, PerformanceGrade.of(0.84))
    }

    private val rankingJson = """
        {"data":{
          "ranking":[
            {"operator_id":"a","operator_name":"Айгерим Сатыбалдиева","operator_short_name":"Айгерим","shifts":3,"total_revenue":300000,"avg_revenue_per_shift":100000,"pi":1.2,"qualifying":true,
             "shift_details":[
               {"date":"2026-10-05","shift":"day","company_id":"c1","actual":100000,"expected":80000,"pi":1.25,"source":"slot"},
               {"date":"2026-10-06","shift":"night","company_id":"c1","actual":100000,"expected":90000,"pi":1.1,"source":"slot"},
               {"date":"2026-10-12","shift":"day","company_id":"c1","actual":100000,"expected":80000,"pi":1.25,"source":"slot"}]},
            {"operator_id":"b","operator_name":"Бауыржан","shifts":1,"total_revenue":50000,"avg_revenue_per_shift":50000,"pi":0.6,"qualifying":false,
             "shift_details":[{"date":"2026-10-07","shift":"day","company_id":"c1","actual":50000,"expected":80000,"pi":0.6,"source":"global"}]}
          ],
          "baseline":{"from":"2026-07-01","to":"2026-09-30","shifts_count":120,"slots_count":14,"global_median":70000},
          "config":{"min_qualifying_shifts":2}}}
    """.trimIndent()

    @Test
    fun `рейтинг читается и порог берётся с сервера`() {
        val r = PerfRanking.from(Json.parseToJsonElement(rankingJson))
        assertEquals(2, r.items.size)
        assertEquals(2, r.minQualifyingShifts)
        assertEquals(1, r.qualified.size)
        assertEquals("Айгерим", r.items[0].displayName)
        assertEquals("Бауыржан", r.items[1].displayName)
        assertEquals(120, r.baselineShifts)
        assertEquals(1.2, r.averagePi!!, 0.0001)
        assertEquals(300000.0 - 250000.0, r.items[0].aboveNorm, 0.001)
    }

    @Test
    fun `порог по умолчанию — три смены`() {
        val r = PerfRanking.from(Json.parseToJsonElement("""{"data":{"ranking":[]}}"""))
        assertEquals(3, r.minQualifyingShifts)
        assertNull(r.averagePi)
    }

    @Test
    fun `только ночные смены пересчитывают PI и выбрасывают тех, у кого их нет`() {
        val r = PerfRanking.from(Json.parseToJsonElement(rankingJson)).forShift("night")
        assertEquals(1, r.items.size)
        val a = r.items.single()
        assertEquals(1, a.shifts)
        assertEquals(1.1, a.pi, 0.0001)
        assertEquals(100000.0, a.totalRevenue, 0.001)
        // Одной смены меньше порога 2 — человек уходит в «мало смен».
        assertFalse(a.qualifying)
    }

    @Test
    fun `дневной PI взвешен ожиданием`() {
        val r = PerfRanking.from(Json.parseToJsonElement(rankingJson)).forShift("day")
        val a = r.items.first { it.operatorId == "a" }
        assertEquals(2, a.shifts)
        assertEquals(1.25, a.pi, 0.0001)
        assertTrue(a.qualifying)
        assertEquals("a", r.items.first().operatorId) // квалифицированные выше
    }

    @Test
    fun `дельта PI и сортировки`() {
        assertNull(piDelta(1.1, null))
        assertEquals(0.1, piDelta(1.1, 1.0)!!, 0.0001)
        val r = PerfRanking.from(Json.parseToJsonElement(rankingJson))
        assertEquals("a", PerfSort.Revenue.apply(r.items).first().operatorId)
        assertEquals("b", PerfSort.AboveNorm.apply(r.items).last().operatorId)
    }

    @Test
    fun `слабые слоты — две смены и больше, ниже 92 процентов нормы`() {
        val shifts = listOf(
            PerfShift("2026-10-05", "day", "c1", 50.0, 100.0, 0.5, ""),
            PerfShift("2026-10-12", "day", "c1", 60.0, 100.0, 0.6, ""), // тот же понедельник
            PerfShift("2026-10-06", "day", "c1", 10.0, 100.0, 0.5, ""), // одна смена во вторник — мало
        )
        val item = PerfItem("a", "A", null, 3, 120.0, 40.0, 0.5, true, shifts)
        val slots = problemSlots(listOf(item))
        assertEquals(1, slots.size)
        assertEquals(1, slots[0].weekday)
        assertEquals(0.55, slots[0].ratio, 0.0001)
    }

    // ── Рейтинг ──────────────────────────────────────────────────────────────

    @Test
    fun `лидерборд считает сумму четырёх оплат и держит нулевых операторов`() {
        val incomes = parseIncomes(
            Json.parseToJsonElement(
                """{"data":[
                {"operator_id":"a","date":"2026-10-01","cash_amount":1000,"kaspi_amount":"2000","online_amount":null,"card_amount":500},
                {"operator_id":"a","date":"2026-10-01","cash_amount":500},
                {"operator_id":"b","date":"2026-10-02","cash_amount":300},
                {"operator_id":"b","date":"2026-10-03","cash_amount":0},
                {"operator_id":null,"date":"2026-10-03","cash_amount":999}]}""",
            ),
        )
        val prev = listOf(IncomeLine("a", "2026-09-01", 2000.0))
        val people = parsePersonRefs(
            Json.parseToJsonElement(
                """{"data":[{"id":"a","name":"Аня","operator_profiles":[{"full_name":"Анна Иванова"}]},{"id":"b","name":"Борис"},{"id":"c","name":"Света"}]}""",
            ),
        )
        val board = buildLeaderboard(incomes, prev, people)
        assertEquals(listOf("a", "b", "c"), board.map { it.operatorId })
        val a = board[0]
        assertEquals("Анна Иванова", a.name)
        assertEquals(4000.0, a.revenue, 0.001)
        assertEquals(2, a.shifts)
        assertEquals(1, a.days)
        assertEquals(2000.0, a.avgPerShift, 0.001)
        assertEquals(100.0, a.change!!, 0.001) // было 2000, стало 4000
        assertEquals(1, board[1].shifts) // нулевая строка — не смена
        assertEquals(0.0, board[2].revenue, 0.001)
        assertNull(board[1].change)
        assertEquals(93.0, a.share, 1.0)
    }

    @Test
    fun `подпись изменения`() {
        assertEquals("+12%", percentChange(12.4))
        assertEquals("−5%", percentChange(-5.0))
        assertEquals("0%", percentChange(0.2))
    }

    // ── Достижения ───────────────────────────────────────────────────────────

    private fun people(vararg ids: String) = ids.map { PersonRef(it, it.uppercase()) }

    @Test
    fun `в достижения не попадают люди без выручки и смен`() {
        val stats = achievementStats(listOf(IncomeLine("a", "2026-10-01", 100.0)), people("a", "b"))
        assertEquals(listOf("a"), stats.map { it.operatorId })
        assertTrue(achievementStats(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `смена в достижениях — рабочий день`() {
        val rows = listOf(
            IncomeLine("a", "2026-10-01", 100.0), IncomeLine("a", "2026-10-01", 100.0), IncomeLine("a", "2026-10-02", 100.0),
        )
        val s = achievementStats(rows, people("a")).single()
        assertEquals(2, s.shifts)
        assertEquals(300.0, s.revenue, 0.001)
        assertEquals(150.0, s.avgPerShift, 0.001)
        assertEquals(100.0, s.share, 0.001)
    }

    @Test
    fun `чемпион призёры и пороги`() {
        val stats = listOf(
            AchStat("a", "A", 6_000_000.0, 55, 60.0),
            AchStat("b", "B", 2_000_000.0, 22, 20.0),
            AchStat("c", "C", 1_000_000.0, 3, 10.0),
            AchStat("d", "D", 100.0, 1, 10.0),
        )
        val r = computeAchievements(stats)
        assertEquals(listOf("a", "b", "c", "d"), r.map { it.stat.operatorId })
        val a = r[0]
        assertTrue(Achievement.Champion in a.earned)
        assertTrue(Achievement.Mega in a.earned && Achievement.Millionaire in a.earned)
        assertTrue(Achievement.Iron in a.earned && Achievement.Marathoner in a.earned && Achievement.Major in a.earned)
        assertTrue(Achievement.Top3 in r[1].earned && Achievement.Champion !in r[1].earned)
        assertTrue(Achievement.Top3 in r[2].earned)
        assertTrue(Achievement.Millionaire in r[2].earned) // ровно миллион — достаточно
        assertTrue(r[3].earned.isEmpty())
        assertEquals(8, r[3].locked.size)
    }

    @Test
    fun `премиум-кассир — пять смен и чек выше среднего на 30 процентов`() {
        val stats = listOf(
            AchStat("a", "A", 1000.0, 5, 50.0), // чек 200
            AchStat("b", "B", 500.0, 5, 50.0), // чек 100
            AchStat("c", "C", 300.0, 5, 50.0), // чек 60
        )
        // Средний чек по людям: (200+100+60)/3 = 120 → порог 156.
        val r = computeAchievements(stats)
        assertTrue(Achievement.Premium in r.first { it.stat.operatorId == "a" }.earned)
        assertFalse(Achievement.Premium in r.first { it.stat.operatorId == "b" }.earned)
    }

    @Test
    fun `прогресс есть у пороговых и нет у рейтинговых`() {
        val s = AchStat("a", "A", 2_500_000.0, 10, 5.0)
        assertNull(Achievement.Champion.progress(s))
        assertNull(Achievement.Premium.progress(s))
        assertEquals(0.5, Achievement.Mega.progress(s)!!.ratio, 0.0001)
        assertEquals(0.5, Achievement.Marathoner.progress(s)!!.ratio, 0.0001)
        assertEquals(1.0, Achievement.Millionaire.progress(s)!!.ratio, 0.0001)
        assertEquals(0.25, Achievement.Major.progress(s)!!.ratio, 0.0001)
    }

    @Test
    fun `сводка считает получивших`() {
        val r = computeAchievements(listOf(AchStat("a", "A", 6_000_000.0, 55, 100.0)))
        val summary = achievementSummary(r)
        assertEquals(1, summary[Achievement.Champion])
        assertEquals(0, summary[Achievement.Premium])
        assertEquals(Achievement.entries.size, summary.size)
    }
}
