package kz.ordaops.android.feature.business.finance

import kotlinx.serialization.decodeFromString
import kz.ordaops.android.core.net.ApiJson
import kz.ordaops.android.feature.business.BizData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Аналитика «Финансов»: недельный баланс, цели, налоги, серия расходов, симуляция, прогноз. */
class FinanceInsightsTest {

    // ── Недельный баланс ─────────────────────────────────────────────────────

    private val main = WeekCompany("a", "Основная", isExtra = false)
    private val side = WeekCompany("x", "Отдельная касса", isExtra = true)

    private fun income(date: String, cash: Double = 0.0, kaspi: Double = 0.0, company: String = "a", shift: String = "day", before: Double? = null) =
        WeekIncome(id = "$date$company$shift", date = date, shift = shift, companyId = company, cash = cash, kaspi = kaspi, kaspiBeforeMidnight = before)

    @Test
    fun `безнал ночной смены после полуночи уходит на следующий день`() {
        val rows = WeeklyLogic.splitKaspiByCalendarDay(listOf(income("2026-10-04", cash = 100.0, kaspi = 1000.0, shift = "night", before = 400.0)))
        assertEquals(2, rows.size)
        assertEquals(400.0, rows[0].kaspi, 0.0)
        assertEquals("2026-10-05", rows[1].date)
        assertEquals(600.0, rows[1].kaspi, 0.0)
        assertEquals(0.0, rows[1].cash, 0.0)
    }

    @Test
    fun `ночь без разбивки целиком относится на следующий день, дневная смена не трогается`() {
        val night = WeeklyLogic.splitKaspiByCalendarDay(listOf(income("2026-10-04", kaspi = 500.0, shift = "night")))
        assertEquals(0.0, night[0].kaspi, 0.0)
        assertEquals(500.0, night[1].kaspi, 0.0)
        assertEquals(1, WeeklyLogic.splitKaspiByCalendarDay(listOf(income("2026-10-04", kaspi = 500.0))).size)
    }

    @Test
    fun `сравнение идёт по тем же дням и отклонённые расходы не считаются`() {
        // Неделя пн 2026-10-12 — вс 18.10; сегодня среда 14.10: сравниваем пн–ср против пн–ср прошлой недели.
        val incomes = listOf(
            income("2026-10-05", cash = 1000.0), income("2026-10-06", cash = 1000.0), income("2026-10-07", cash = 1000.0), income("2026-10-10", cash = 9000.0),
            income("2026-10-12", cash = 2000.0), income("2026-10-13", cash = 2000.0), income("2026-10-14", cash = 2000.0),
        )
        val expenses = listOf(
            WeekExpense(date = "2026-10-12", companyId = "a", category = "Аренда", cash = 500.0),
            WeekExpense(date = "2026-10-13", companyId = "a", category = "Аренда", cash = 7777.0, status = "declined"),
        )
        val b = WeeklyLogic.build(incomes, expenses, listOf(main), "2026-10-12", "2026-10-14", false)
        assertEquals("2026-10-14", b.compareUntil)
        assertEquals(3, b.comparedDays)
        assertEquals(6000.0, b.compared.income, 0.0)
        assertEquals(3000.0, b.previous.income, 0.0) // 10-10 (суббота) в сравнимые дни не входит
        assertEquals(500.0, b.current.expense, 0.0)
        assertEquals(5500.0, b.current.profit, 0.0)
    }

    @Test
    fun `точка вне итогов не складывается без галочки, но в списке точек видна`() {
        val incomes = listOf(income("2026-10-12", cash = 1000.0), income("2026-10-12", cash = 5000.0, company = "x"))
        val without = WeeklyLogic.build(incomes, emptyList(), listOf(main, side), "2026-10-12", "2026-10-18", false)
        assertEquals(1000.0, without.current.income, 0.0)
        assertEquals(5000.0, without.excludedIncome, 0.0)
        assertEquals(listOf("a", "x"), without.companies.map { it.id })
        assertFalse(without.companies[1].inTotals)
        val with = WeeklyLogic.build(incomes, emptyList(), listOf(main, side), "2026-10-12", "2026-10-18", true)
        assertEquals(6000.0, with.current.income, 0.0)
    }

    @Test
    fun `безналичный - это терминал, онлайн и карта, а ночь и день считаются раздельно`() {
        val rows = listOf(
            WeekIncome(id = "1", date = "2026-10-12", shift = "day", companyId = "a", cash = 100.0, kaspi = 200.0, online = 30.0, card = 70.0),
            WeekIncome(id = "2", date = "2026-10-12", shift = "night", companyId = "a", cash = 50.0, kaspi = 0.0, kaspiBeforeMidnight = 0.0),
        )
        val b = WeeklyLogic.build(rows, emptyList(), listOf(main), "2026-10-12", "2026-10-18", false)
        assertEquals(300.0, b.current.incomeCashless, 0.0)
        assertEquals(150.0, b.current.cash, 0.0)
        assertEquals(400.0, b.current.day, 0.0)
        assertEquals(50.0, b.current.night, 0.0)
    }

    @Test
    fun `режим среднего за 8 недель делит базу на число недель`() {
        val rows = (1..8).map { income(WeeklyLogic.addDays("2026-10-12", -7 * it), cash = 800.0) } + income("2026-10-12", cash = 1000.0)
        val b = WeeklyLogic.build(rows, emptyList(), listOf(main), "2026-10-12", "2026-10-12", false, CompareMode.Avg8)
        assertEquals(800.0, b.previous.income, 0.001)
        assertEquals(1000.0, b.compared.income, 0.0)
    }

    @Test
    fun `понедельник недели находится по любой дате`() {
        assertEquals("2026-10-12", WeeklyLogic.mondayOf("2026-10-18"))
        assertEquals("2026-10-12", WeeklyLogic.mondayOf("2026-10-12"))
    }

    // ── Цели ─────────────────────────────────────────────────────────────────

    @Test
    fun `веса дней недели равны, пока данных мало, и растут у сильных дней`() {
        assertTrue(GoalsLogic.weekdayWeights(listOf("2026-10-01" to 100.0)).all { it == 1.0 })
        // 28 дней: суббота (6) вдвое сильнее остальных.
        val days = GoalsLogic.datesBetween("2026-09-01", "2026-09-28").map { it to if (GoalsLogic.weekday(it) == 6) 200.0 else 100.0 }
        val w = GoalsLogic.weekdayWeights(days)
        assertTrue(w[6] > w[1])
        assertEquals(7.0, w.sum(), 0.001)
    }

    @Test
    fun `темп - сколько нужно в день и отставание от ритма`() {
        val flat = DoubleArray(7) { 1.0 }
        val p = GoalsLogic.pace(3000.0, 1000.0, "2026-10-01", "2026-10-30", "2026-10-10", flat)!!
        assertEquals(10, p.daysPassed)
        assertEquals(20, p.daysLeft)
        assertEquals(100.0, p.requiredPerDay, 0.0)
        assertEquals(100.0, p.currentPerDay, 0.0)
        assertEquals(1000.0, p.expectedByNow, 0.0)
        assertEquals(0.0, p.gap, 0.0)
        assertNull(GoalsLogic.pace(0.0, 10.0, "2026-10-01", "2026-10-30", "2026-10-10", flat))
    }

    @Test
    fun `успеем ли - вердикт по коридору прогноза`() {
        assertEquals("done", GoalsLogic.verdict(100.0, 120.0, null)?.status)
        assertNull(GoalsLogic.verdict(100.0, 50.0, null))
        assertEquals("safe", GoalsLogic.verdict(100.0, 50.0, Triple(110.0, 130.0, 150.0))?.status)
        assertEquals("likely", GoalsLogic.verdict(100.0, 50.0, Triple(80.0, 110.0, 150.0))?.status)
        assertEquals("at_risk", GoalsLogic.verdict(100.0, 50.0, Triple(60.0, 80.0, 120.0))?.status)
        val bad = GoalsLogic.verdict(100.0, 50.0, Triple(60.0, 70.0, 90.0))!!
        assertEquals("unlikely", bad.status)
        assertEquals(30.0, bad.shortfall, 0.0)
    }

    @Test
    fun `цель месяца сети без общей цели - сумма целей точек`() {
        fun plan(company: String?, metric: String, target: Double) =
            GoalPlan(id = "$company$metric", companyId = company, periodKind = "month", metric = metric, target = target, periodStart = "2026-10-01", periodEnd = "2026-10-31")
        val plans = listOf(plan("a", "revenue", 600.0), plan("b", "revenue", 400.0))
        val t = GoalsLogic.monthTarget(plans, 9, "revenue", null)
        assertEquals(1000.0, t.value, 0.0)
        assertEquals("companies", t.source)
        assertEquals("plan", GoalsLogic.monthTarget(plans + plan(null, "revenue", 1200.0), 9, "revenue", null).source)
        assertEquals("none", GoalsLogic.monthTarget(plans, 9, "margin", null).source)
        assertEquals("none", GoalsLogic.monthTarget(plans, 8, "revenue", null).source)
    }

    @Test
    fun `факт сети берёт только строки без точки, а точки - свои`() {
        val daily = listOf(
            GoalDay("2026-10-01", null, 1000.0, 400.0), GoalDay("2026-10-01", "a", 700.0, 100.0), GoalDay("2026-10-01", "x", 300.0, 300.0),
        )
        val network = GoalsLogic.facts(daily, null, "2026-10-01", "2026-10-31")
        assertEquals(600.0, network.profit, 0.0)
        assertEquals(60.0, network.margin, 0.0)
        assertEquals(600.0, GoalsLogic.facts(daily, "a", "2026-10-01", "2026-10-31").profit, 0.0)
    }

    @Test
    fun `итог года - сколько закрытых месяцев с планом выполнено`() {
        val s = GoalsLogic.yearSummary(listOf(
            GoalsLogic.MonthPlanFact(1, 100.0, 120.0, true), GoalsLogic.MonthPlanFact(2, 100.0, 80.0, true),
            GoalsLogic.MonthPlanFact(3, 100.0, 10.0, false), GoalsLogic.MonthPlanFact(4, null, 50.0, true),
        ))
        assertEquals(2, s.closedWithPlan)
        assertEquals(1, s.hit)
        assertEquals(20.0, s.missed.single().shortfall, 0.0)
        assertEquals(80, s.missed.single().pct)
    }

    @Test
    fun `год назад - 29 февраля становится 28-м, нечего сравнивать - null`() {
        assertEquals("2023-02-28", GoalsLogic.yearBack("2024-02-29"))
        assertNull(GoalsLogic.yoyBase(emptyList(), null, "2026-10-01", "2026-10-31", "2026-10-10", "revenue"))
        assertNull(GoalsLogic.yoyBase(listOf(GoalDay("2025-10-05", null, 100.0, 0.0)), null, "2026-10-01", "2026-10-31", "2026-10-10", "margin"))
        assertEquals(100.0, GoalsLogic.yoyBase(listOf(GoalDay("2025-10-05", null, 100.0, 0.0)), null, "2026-10-01", "2026-10-31", "2026-10-10", "revenue")!!, 0.0)
    }

    // ── Налоги ───────────────────────────────────────────────────────────────

    @Test
    fun `налоговое полугодие и ближайший платёж`() {
        assertEquals("2026-08-25", TaxLogic.half(2026, 1).paymentDue)
        assertEquals("2027-02-25", TaxLogic.half(2026, 2).paymentDue)
        assertEquals(2, TaxLogic.halfOf("2026-10-27").half)
        // В июле платят за первое полугодие, после 25 августа — уже за текущее.
        assertEquals(1, TaxLogic.nextPayment("2026-07-10").half)
        assertEquals(2, TaxLogic.nextPayment("2026-09-01").half)
        assertEquals(2, TaxLogic.nextPayment("2027-01-15").half)
        assertEquals(2026, TaxLogic.nextPayment("2027-01-15").year)
        assertEquals(4, TaxLogic.halves("2026-10-27").size)
    }

    @Test
    fun `оборот для налога учитывает фильтр способов оплаты и точку вне итогов`() {
        val base = TaxSummary(rate = 4.0, constants = TaxConstants(selfSocialMonthly = 1000.0), payroll = PayrollTaxes(monthlyTax = 500.0))
        val rows = listOf(
            TaxIncomeRow("2026-07-05", "a", cash = 1000.0, kaspi = 1000.0),
            TaxIncomeRow("2026-08-05", "a", cash = 2000.0),
            TaxIncomeRow("2026-08-06", "x", cash = 5000.0),
        )
        val filter = mapOf("a" to TaxChannels(cash = false))
        val s = TaxLogic.recompute(base, rows, filter, setOf("x"), includeExtra = false)
        // июль: только безнал 1000; август: наличные точки a отключены, точка x вне итогов.
        assertEquals(1000.0, s.revenue, 0.0)
        assertEquals(1, s.monthsCount)
        assertEquals(40.0, s.ipn, 0.0)
        assertEquals(1000.0, s.selfSocial, 0.0)
        assertEquals(1040.0, s.total, 0.0)
        assertEquals(1540.0, s.burden.total, 0.0)
        val withExtra = TaxLogic.recompute(base, rows, filter, setOf("x"), includeExtra = true)
        assertEquals(6000.0, withExtra.revenue, 0.0)
        assertEquals(2, withExtra.monthsCount)
    }

    @Test
    fun `ставки маслихата идут шагом в полпроцента`() {
        assertEquals(9, TaxLogic.rates(2.0, 6.0).size)
        assertEquals("2,5%", TaxLogic.rateLabel(2.5))
        assertEquals("3%", TaxLogic.rateLabel(3.0))
    }

    @Test
    fun `настройки налога разбираются терпимо`() {
        val r = ApiJson.decodeFromString<TaxSettingsReply>("""{"ok":true,"tax":{"rate":3.5,"bin":"123","taxableFilter":{"c1":{"cash":false}},"new":1},"canEdit":true}""")
        assertEquals(3.5, r.tax.rate!!, 0.0)
        assertFalse(r.tax.taxableFilter.getValue("c1").cash)
        assertTrue(r.tax.taxableFilter.getValue("c1").kaspi)
        assertTrue(r.canEdit)
        assertNull(ApiJson.decodeFromString<TaxSettingsReply>("""{"tax":{"rate":null}}""").tax.rate)
    }

    // ── Серия расходов ───────────────────────────────────────────────────────

    @Test
    fun `месяцы серии не выпрыгивают из целевого месяца`() {
        assertEquals("2026-02-28", ExpenseSeriesLogic.addMonthsClamped("2026-01-31", 1))
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31"), ExpenseSeriesLogic.dates("2026-01-31", SeriesKind.Month, 3))
        assertEquals(listOf("2026-01-01", "2026-04-01"), ExpenseSeriesLogic.dates("2026-01-01", SeriesKind.Quarter, 2))
        assertEquals(31, ExpenseSeriesLogic.dates("2026-01-01", SeriesKind.Day, 99).size)
        assertEquals(24, ExpenseSeriesLogic.dates("2026-01-01", SeriesKind.Month, 99).size)
    }

    @Test
    fun `общая сумма делится поровну, остаток уходит в последний период`() {
        assertEquals(listOf(14000.0, 14000.0, 14000.0, 14000.0, 14000.0, 14000.0), ExpenseSeriesLogic.splitTotal(84000.0, 6))
        assertEquals(listOf(33333.0, 33333.0, 33334.0), ExpenseSeriesLogic.splitTotal(100000.0, 3))
        assertTrue(ExpenseSeriesLogic.splitTotal(10.0, 0).isEmpty())
    }

    @Test
    fun `правка суммы периода сохраняет пропорцию нал - безнал`() {
        assertEquals(500.0 to 0.0, ExpenseSeriesLogic.splitPeriod(500.0, 100.0, 0.0))
        assertEquals(0.0 to 500.0, ExpenseSeriesLogic.splitPeriod(500.0, 0.0, 100.0))
        assertEquals(250.0 to 250.0, ExpenseSeriesLogic.splitPeriod(500.0, 100.0, 100.0))
    }

    @Test
    fun `серия по месяцам - подписи, суммы и проверка`() {
        val perPeriod = ExpenseSeriesLogic.rows("2026-01-01", SeriesKind.Month, 3, 100.0, 50.0, SeriesAmountMode.PerPeriod)
        assertEquals("январь 2026", perPeriod[0].label)
        assertEquals(150.0, perPeriod[1].total, 0.0)
        val split = ExpenseSeriesLogic.rows("2026-01-01", SeriesKind.Month, 3, 90000.0, 0.0, SeriesAmountMode.Split)
        assertEquals(listOf(30000.0, 30000.0, 30000.0), split.map { it.total })
        assertNull(ExpenseSeriesLogic.problem(perPeriod, SeriesKind.Month))
        assertEquals("В серии должно быть минимум 2 периода", ExpenseSeriesLogic.problem(perPeriod.take(1), SeriesKind.Month))
        assertEquals("Сумма каждого периода должна быть больше 0", ExpenseSeriesLogic.problem(perPeriod.map { it.copy(cash = 0.0, kaspi = 0.0) }, SeriesKind.Month))
        assertEquals("1 кв. 2026", ExpenseSeriesLogic.label("2026-02-10", SeriesKind.Quarter))
    }

    @Test
    fun `тело серии содержит сессию, вид периода и периоды`() {
        val body = ExpenseSeriesLogic.body("s1", SeriesKind.Week, ExpenseSeriesLogic.rows("2026-10-05", SeriesKind.Week, 2, 10.0, 0.0, SeriesAmountMode.PerPeriod)).toString()
        assertTrue(body.contains("\"session_id\":\"s1\""))
        assertTrue(body.contains("\"period_kind\":\"week\""))
        assertTrue(body.contains("2026-10-12"))
    }

    // ── Симуляция ────────────────────────────────────────────────────────────

    @Test
    fun `ставка тарифа учитывает бонусные часы`() {
        assertEquals(200.0, SimulationLogic.rate(SimTariff(id = "t", paidHours = 2.0, bonusHours = 1.0, price = 600.0)), 0.0)
        assertEquals(0.0, SimulationLogic.rate(SimTariff(id = "t", price = 600.0)), 0.0)
    }

    @Test
    fun `потенциал зон и обратный расчёт загрузки`() {
        val tariffs = listOf(SimTariff("t1", "Час", 1.0, 0.0, 500.0), SimTariff("t2", "Пакет", 2.0, 1.0, 900.0))
        val zones = listOf(SimZone(id = "z", name = "Зал", deviceCount = 10.0, occupancyHours = 8.0, tariffMix = listOf(SimMix("t1", 50.0), SimMix("t2", 50.0))))
        val p = SimulationLogic.project(zones, tariffs, SimFact(90, revenuePerDay = 20000.0, revenuePerMonth = 600000.0))
        // ставка зоны = 0.5*500 + 0.5*300 = 400; потенциал/сутки = 10 * 8 * 400 = 32 000
        assertEquals(400.0, p.zones[0].blendedRate, 0.001)
        assertEquals(32000.0, p.potentialPerDay, 0.001)
        assertEquals(960000.0, p.potentialPerMonth, 0.001)
        assertEquals(360000.0, p.gapPerMonth, 0.001)
        assertEquals(5.0, p.impliedOccupancy!!, 0.001) // 20 000 / (10 * 400)
        assertEquals(8.0, p.assumedOccupancy!!, 0.001)
        assertEquals(-3.0, p.occupancyGap!!, 0.001)
    }

    @Test
    fun `без устройств загрузку не считаем, а удалённый тариф не даёт ставки`() {
        val p = SimulationLogic.project(listOf(SimZone(deviceCount = 0.0, occupancyHours = 5.0, tariffMix = listOf(SimMix("нет", 100.0)))), emptyList(), null)
        assertNull(p.impliedOccupancy)
        assertNull(p.assumedOccupancy)
        assertEquals(0.0, p.potentialPerDay, 0.0)
    }

    // ── ИИ Финдиректор ───────────────────────────────────────────────────────

    @Test
    fun `что если - цена трогает выручку, поток - выручку и переменные, постоянные сами по себе`() {
        val price = CfoLogic.whatIf(1000.0, 300.0, 400.0, 10.0, 0.0, 0.0)
        assertEquals(1100.0, price.revenue, 0.0)
        assertEquals(400.0, price.operatingProfit, 0.0) // 1100 - 300 - 400
        assertEquals(100.0, price.delta, 0.0)
        val volume = CfoLogic.whatIf(1000.0, 300.0, 400.0, 0.0, 10.0, 0.0)
        assertEquals(370.0, volume.operatingProfit, 0.0) // 1100 - 330 - 400
        val fixed = CfoLogic.whatIf(1000.0, 300.0, 400.0, 0.0, 0.0, -10.0)
        assertEquals(340.0, fixed.operatingProfit, 0.0) // 1000 - 300 - 360
    }

    @Test
    fun `теги статуса из текста модели убираются`() {
        assertEquals("Аренда выросла", CfoLogic.clean("[ФАКТ] Аренда выросла"))
        assertEquals("", CfoLogic.clean(null))
    }

    @Test
    fun `месяц - границы`() {
        assertEquals("2026-02-01" to "2026-02-28", CfoLogic.monthRange("2026-02"))
    }

    // ── Прогноз ──────────────────────────────────────────────────────────────

    @Test
    fun `ответ прогноза разбирается терпимо`() {
        val json = """{"next":{"targetMonth":"2026-11","scenarios":{"pessimistic":{"income":1,"expense":2,"profit":-1},"realistic":{"income":3,"expense":2,"profit":1},"optimistic":{"income":5,"expense":2,"profit":3}},
            "accuracy":{"checks":2,"recentError":{"income":0.12},"coverage":{"income":{"inside":1,"total":2}},"trend":"improving"},"unknown":true},
            "current":{"month":"2026-10","outlook":{"knownDays":10,"daysInMonth":31,"paceIncome":null}},"snapshots":[],"byCompany":null}"""
        val board = ApiJson.decodeFromString<ForecastBoard>(json)
        assertEquals(3.0, board.next.scenarios!!.realistic.income, 0.0)
        assertEquals(0.12, board.next.accuracy.recentError.income!!, 0.0)
        assertEquals("12%", ForecastText.share(board.next.accuracy.recentError.income!!))
        assertEquals(10, board.current.outlook!!.knownDays)
        assertNull(board.current.outlook!!.paceIncome)
        assertNull(board.byCompany)
    }

    @Test
    fun `сверки - зафиксированные берутся только с фактом`() {
        val s = Scenarios()
        val board = ForecastBoard(
            snapshots = listOf(
                SnapshotView(targetMonth = "2026-08", scenarios = s, actual = ForecastTriple(1.0, 1.0, 0.0), inside = InsidePair(income = true)),
                SnapshotView(targetMonth = "2026-09", scenarios = s),
            ),
            next = NextForecast(backtest = listOf(BacktestRow(month = "2026-07"))),
        )
        assertEquals(listOf("2026-08"), board.accuracyRows(true).map { it.month })
        assertEquals(listOf("2026-07"), board.accuracyRows(false).map { it.month })
    }

    @Test
    fun `прогноз прибыли для целей берёт нужный ряд сценария`() {
        val o = RunningOutlook(outlook = Scenarios(ForecastTriple(10.0, 5.0, 5.0), ForecastTriple(20.0, 5.0, 15.0), ForecastTriple(30.0, 5.0, 25.0)))
        assertEquals(Triple(10.0, 20.0, 30.0), GoalsLogic.outlookTriple(o, "revenue"))
        assertEquals(Triple(5.0, 15.0, 25.0), GoalsLogic.outlookTriple(o, "profit"))
        assertNull(GoalsLogic.outlookTriple(null, "revenue"))
    }

    // ── Аналитика по месяцам, оценка ─────────────────────────────────────────

    @Test
    fun `итоги года - лучший и худший месяц без пустых`() {
        val months = listOf(MonthAggregate(month = "2026-01", revenue = 100.0, expenses = 40.0, checks = 10), MonthAggregate(month = "2026-02", revenue = 300.0, expenses = 100.0, checks = 20), MonthAggregate(month = "2026-03"))
        val t = MonthlyLogic.totals(months, listOf(PreviousMonth("2025-01", 200.0)))
        assertEquals(400.0, t.revenue, 0.0)
        assertEquals(260.0, t.profit, 0.0)
        assertEquals("2026-02", t.bestMonth!!.month)
        assertEquals("2026-01", t.worstMonth!!.month)
        assertEquals(100.0, t.yoy!!, 0.0)
        assertEquals(1, MonthlyLogic.monthIndex("2026-02"))
    }

    @Test
    fun `оценка бизнеса и аналитика декодируются из ответов сайта`() {
        val v = ApiJson.decodeFromString<ValuationReply>("""{"ok":true,"data":{"ebitda_12mo":1200,"profitable":true,"multiple":{"base":3,"low":3,"mid":4,"high":5},"valuation":{"low":3600,"mid":4800,"high":6000},"factors":[{"key":"trend","label":"Тренд","status":"good","effect":1,"note":"+"}]}}""")
        assertEquals(4800.0, v.data!!.valuation.mid, 0.0)
        assertEquals("good", v.data!!.factors.single().status)
        assertNull(ApiJson.decodeFromString<ValuationReply>("""{"ok":true,"data":null}""").data)
        val m = ApiJson.decodeFromString<BizData<MonthlyBoard>>("""{"data":{"year":2026,"months":[{"month":"2026-01","revenue":5,"by_company":{"c":{"revenue":5}}}],"previousYear":[]}}""").data
        assertEquals(5.0, m.months.single().byCompany.getValue("c").revenue, 0.0)
    }

    @Test
    fun `цели - ответ сайта разбирается, план без метрики не падает`() {
        val d = ApiJson.decodeFromString<BizData<GoalsData>>(
            """{"ok":true,"data":{"year":2026,"plans":[{"id":"p","company_id":null,"kind":"month.revenue","target_amount":1000,"period_start":"2026-10-01","period_end":"2026-10-31","period_kind":"month","metric":"revenue","fact_value":400,"is_closed":false},{"id":"q","kind":"weird"}],
              "dailyAggregates":[{"date":"2026-10-01","company_id":null,"revenue":10,"expenses":4,"checks":2}],"factEnd":"2026-10-26"}}""",
        ).data
        assertEquals(2, d.plans.size)
        assertNull(d.plans[1].periodKind)
        assertNotNull(GoalsLogic.findPlan(d.plans, "month", 9, "revenue", null))
        assertEquals("2026-10-26", d.factEnd)
    }
}
