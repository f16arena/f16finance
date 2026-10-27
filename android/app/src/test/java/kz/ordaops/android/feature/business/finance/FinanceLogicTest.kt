package kz.ordaops.android.feature.business.finance

import java.time.LocalDate
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kz.ordaops.android.core.net.ApiJson
import kz.ordaops.android.feature.business.BizData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Чистая логика раздела «Финансы»: формы, выписка, ОПиУ, отчёты, точки. */
class FinanceLogicTest {

    // ── Формы ────────────────────────────────────────────────────────────────

    @Test
    fun `доход без оператора и без суммы не проходит с серверными словами`() {
        val base = IncomeDraft(date = "2026-09-01", companyId = "c1")
        assertEquals("Оператор обязателен", base.validationMessage)
        assertEquals("Сумма дохода обязательна", base.copy(operatorId = "o1").validationMessage)
        assertNull(base.copy(operatorId = "o1", cash = 1000.0).validationMessage)
        assertEquals("Сумма не может быть отрицательной", base.copy(operatorId = "o1", cash = -1.0).validationMessage)
    }

    @Test
    fun `расход требует название, комментарий и документ`() {
        var d = ExpenseDraft(date = "2026-09-01", companyId = "c", categoryId = "k", categoryName = "Аренда", amountCash = 500.0)
        assertEquals("Краткое название обязательно (≥ 5 символов)", d.validationMessage)
        d = d.copy(itemName = "Картриджи")
        assertEquals("Комментарий обязателен (≥ 20 символов)", d.validationMessage)
        d = d.copy(comment = "Для принтера на кассе, заменили старые")
        assertEquals("Прикрепите чек/накладную", d.validationMessage)
        assertNull(d.copy(documentUrls = listOf("u")).validationMessage)
        val oneOff = d.copy(documentKind = ExpenseDocumentKind.OneOff, oneOffPayee = "ИП", oneOffReason = "x")
        assertEquals("Укажите получателя (≥ 3 символов)", oneOff.validationMessage)
    }

    @Test
    fun `правка расхода без точки не предлагается`() {
        assertNull(ExpenseEdit.of(ExpenseRow(id = "1", cashAmount = 10.0)))
        assertEquals("c1", ExpenseEdit.of(ExpenseRow(id = "1", cashAmount = 10.0, category = "Аренда", companyId = "c1"))?.companyId)
    }

    @Test
    fun `приход по зонам пропускает пустые зоны и подписывает комментарий`() {
        val ps5 = RevenueZone("ps5", "PS5", true)
        val vr = RevenueZone("vr", "VR", true)
        val body = zoneIncomeBatchBody("2026-09-01", "c", "o", "day", "смена", listOf(ps5 to ZoneAmount(100.0, 50.0), vr to ZoneAmount()))
        val text = body.toString()
        assertTrue(text.contains("createIncomeBatch"))
        assertTrue(text.contains("смена • PS5"))
        assertFalse(text.contains("VR"))
    }

    // ── Точки и зоны (по вебу, не по кодам F16) ─────────────────────────────

    @Test
    fun `профиль точки решает ниша, а код — только запасной вариант`() {
        assertTrue(PointLogic.profile(FinCompany(industry = "club", code = "zzz")).showOnline)
        assertFalse(PointLogic.profile(FinCompany(industry = "shop", code = "arena")).showOnline)
        assertTrue(PointLogic.profile(FinCompany(code = "arena")).showOnline)
        assertEquals("other", PointLogic.kind(FinCompany(code = "castle")))
    }

    @Test
    fun `зоны - список точки главнее умолчания, ps_club без списка получает PS5 и VR`() {
        val custom = Json.decodeFromString<FinCompany>("""{"id":"1","name":"Зал","revenue_zones":[{"key":"bill","label":"Бильярд","active":true},{"key":"old","label":"Старая","active":false}]}""")
        assertEquals(listOf("bill"), PointLogic.activeZones(custom).map { it.key })
        assertEquals(listOf("ps5", "vr"), PointLogic.activeZones(FinCompany(industry = "ps_club")).map { it.key })
        assertTrue(PointLogic.activeZones(FinCompany(industry = "shop")).isEmpty())
        assertFalse(PointLogic.profile(FinCompany(industry = "ps_club")).hasNightShift)
    }

    @Test
    fun `вне итогов - флаг точки, а без него старый признак`() {
        assertTrue(PointLogic.isExcludedFromTotals(FinCompany(name = "Любая", excludeFromTotals = true)))
        assertFalse(PointLogic.isExcludedFromTotals(FinCompany(name = "F16 Extra", excludeFromTotals = false)))
        assertTrue(PointLogic.isExcludedFromTotals(FinCompany(name = "F16 Extra")))
    }

    // ── Выписка ──────────────────────────────────────────────────────────────

    private val names: (String?) -> String? = { if (it == "c1") "Арена" else null }

    @Test
    fun `отклонённые расходы не считаются`() {
        val ops = LedgerLogic.expenseOps(
            listOf(
                ExpenseRow(id = "1", date = "2026-09-01", category = "Аренда", cashAmount = 100.0, companyId = "c1"),
                ExpenseRow(id = "2", date = "2026-09-01", category = "Аренда", cashAmount = 900.0, status = "declined"),
            ),
            names,
        )
        assertEquals(listOf("1"), ops.map { it.id })
    }

    @Test
    fun `выписка - фильтр по способу, сумма под пузырём и порядок дней`() {
        val ops = LedgerLogic.incomeOps(
            listOf(
                IncomeRow(id = "a", date = "2026-09-01", cashAmount = 100.0, kaspiAmount = 50.0, companyId = "c1"),
                IncomeRow(id = "b", date = "2026-09-02", cashAmount = 10.0, companyId = "c1"),
            ),
            names,
        )
        val kaspi = LedgerLogic.filter(ops, Methods.Kaspi, null, "")
        assertEquals(listOf("a"), kaspi.map { it.id })
        // Под пузырём «Безналичный» - только его часть, не вся операция.
        assertEquals(50.0, LedgerLogic.shownAmount(kaspi[0], isIncome = true, bucket = Methods.Kaspi), 0.0)
        assertEquals(listOf("2026-09-02", "2026-09-01"), LedgerLogic.days(LedgerLogic.filter(ops, null, null, ""), true, null).map { it.date })
        assertEquals(listOf("a"), LedgerLogic.filter(ops, null, null, "150").map { it.id })
    }

    @Test
    fun `столбики по дням включают тихие дни нулём и обрезаются сегодняшним`() {
        val ops = LedgerLogic.incomeOps(listOf(IncomeRow(id = "a", date = "2026-09-02", cashAmount = 5.0)), names)
        val bars = LedgerLogic.dailySums("2026-09-01", "2026-09-30", "2026-09-03", ops, true, null)
        assertEquals(listOf("2026-09-01" to 0.0, "2026-09-02" to 5.0, "2026-09-03" to 0.0), bars)
    }

    @Test
    fun `заголовок дня и склонения`() {
        val today = LocalDate.of(2026, 9, 22)
        assertEquals("Сегодня", LedgerLogic.dayTitle("2026-09-22", today))
        assertEquals("Вчера", LedgerLogic.dayTitle("2026-09-21", today))
        assertEquals("12 сентября, суббота", LedgerLogic.dayTitle("2026-09-12", today))
        assertEquals("статья", LedgerLogic.articlesWord(21))
        assertEquals("операции", LedgerLogic.operationsWord(3))
        assertEquals("операций", LedgerLogic.operationsWord(12))
    }

    // ── ОПиУ ─────────────────────────────────────────────────────────────────

    @Test
    fun `месяц ОПиУ сдвигается через границу года`() {
        assertEquals("2026-12", PnlLogic.shift("2027-01", -1))
        assertEquals("2027-02", PnlLogic.shift("2026-12", 2))
        assertEquals("Сентябрь 2026", PnlLogic.title("2026-09"))
    }

    @Test
    fun `ответ ОПиУ читается, статьи раскрываются вместе с прошлым месяцем`() {
        val json = """{"data":{"months":[{"month":"2026-09","revenue":1000,"netProfit":100,"categories":{"operating":[{"name":"Аренда","amount":300}]},"check":{"expensesAll":900,"declined":50,"declinedCount":1}}],
            "extra":{"names":["Зал"],"included":false}}}"""
        val report = ApiJson.decodeFromString<BizData<PnlReport>>(json).data
        val cur = report.months.first()
        assertEquals(50.0, cur.check.declined, 0.0)
        assertEquals(listOf("Зал"), report.extra.names)
        val prev = MonthlyPnl(month = "2026-08", categories = mapOf("operating" to listOf(PnlCategoryAmount("Реклама", 40.0))))
        assertEquals(listOf("Аренда", "Реклама"), PnlLogic.mergedParts(PnlLine.Operating, cur, prev).map { it.first })
        assertTrue(PnlLogic.shareText(300.0, 1000.0).startsWith("30"))
        assertEquals("—", PnlLogic.shareText(300.0, 0.0))
    }

    // ── Отчёты, движение денег, KPI ──────────────────────────────────────────

    @Test
    fun `шаг графика отчёта зависит от длины периода`() {
        assertEquals("day", ReportQuery.autoGroup("2026-09-01", "2026-09-30"))
        assertEquals("week", ReportQuery.autoGroup("2026-07-01", "2026-09-30"))
        assertEquals("month", ReportQuery.autoGroup("2026-01-01", "2026-12-31"))
        val q = ReportQuery("2026-09-01", "2026-09-30", companyId = "c", shift = "night", compareYear = true, includeExtra = true).queryItems("0", "2026-09-22")
        assertEquals("year", q["compare"])
        assertEquals("night", q["shift"])
        assertEquals("1", q["include_extra"])
    }

    @Test
    fun `изменение чистого потока считается к прошлому только если там что-то пришло`() {
        val report = CashflowReport(
            flows = CashflowChannels(total = CashflowFlow(1000.0, 500.0)),
            previous = CashflowChannels(total = CashflowFlow(400.0, 300.0)),
        )
        assertEquals(400.0, CashflowLogic.netChange(report)!!, 0.0001)
        assertNull(CashflowLogic.netChange(report.copy(previous = CashflowChannels())))
        assertEquals("−1 500 ₸", CashflowLogic.signedText(-1500.0) { String.format("%,d ₸", it.toLong()).replace(',', ' ') })
    }

    @Test
    fun `долги точки - сумма из позиций, имя должника и обёртка data`() {
        val json = """{"data":{"weekStart":"2026-09-21","items":[{"id":"1","debtor_name":"Алия","item_name":"Сок","quantity":2,"total_amount":900}],
            "totals":{"count":1,"amount":900},"legacyAggregates":[{"id":"2","debtor_name":"Дастан","amount":300}]}}"""
        val week = ApiJson.decodeFromString<BizData<PointDebtWeek>>(json).data
        assertEquals("Алия", week.items[0].title)
        assertEquals("Сок × 2", week.items[0].subtitle)
        assertEquals(900.0, week.unpaidAmount, 0.0)
        assertEquals(300.0, week.legacyAggregates[0].amount, 0.0)
    }

    @Test
    fun `KPI продавцов - подписи и границы месяца`() {
        assertEquals("на 12% лучше нормы", SalesKpiLogic.scoreText(1.12))
        assertEquals("как норма", SalesKpiLogic.scoreText(1.0))
        assertEquals("нет оценки", SalesKpiLogic.scoreText(null))
        assertEquals("Допродажи", SalesKpiLogic.metricLabel("attach_rate"))
        assertEquals("2026-02-01" to "2026-02-28", SalesKpiLogic.monthBounds("2026-02"))
        val order = SalesKpiLogic.reviewOrder(listOf(KpiShift(date = "2026-09-02", verdict = "NORMAL"), KpiShift(date = "2026-09-01", verdict = "POSSIBLE_CASHIER_ISSUE")))
        assertEquals("POSSIBLE_CASHIER_ISSUE", order[0].verdict)
        assertEquals("Цели выше обычного на 10%", KpiPlans(monthly = listOf(KpiMonthly(1.10))).monthlyText)
    }

    @Test
    fun `категории - бюджет, перерасход и близость к лимиту`() {
        val c = ExpenseCategory(name = "Аренда", monthlyBudget = 1000.0, spentThisMonth = 850.0)
        assertTrue(c.isNearLimit)
        assertFalse(c.isOverBudget)
        assertTrue(c.copy(spentThisMonth = 1200.0).isOverBudget)
        assertNull(ExpenseCategory(spentThisMonth = 5.0).usage)
    }
}
