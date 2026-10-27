package kz.ordaops.android.feature.business.team

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

/** Чистая логика команды: недели, зарплата, график, отчёты смен. Зеркало TeamTests из apple/OrdaKit. */
class TeamLogicTest {
    // ── Недели ───────────────────────────────────────────────────────────────

    @Test
    fun `неделя выравнивается на понедельник`() {
        // Среда, 28 октября 2026 → понедельник 26-го.
        assertEquals("2026-10-26", WeekMath.currentWeekStart(LocalDate.of(2026, 10, 28)))
        // Воскресенье тоже относится к неделе, которая началась в понедельник.
        assertEquals("2026-10-26", WeekMath.currentWeekStart(LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun `сдвиг недели переходит через год`() {
        assertEquals("2026-12-28", WeekMath.shifted("2026-12-21", 1))
        assertEquals("2027-01-04", WeekMath.shifted("2026-12-28", 1))
        assertEquals("2025-12-22", WeekMath.shifted("2026-01-05", -2))
    }

    @Test
    fun `в зарплате нельзя уйти в будущую неделю`() {
        val today = LocalDate.of(2026, 10, 28)
        assertTrue(WeekMath.isCurrentOrFuture("2026-10-26", today))
        assertTrue(WeekMath.isCurrentOrFuture("2026-11-02", today))
        assertFalse(WeekMath.isCurrentOrFuture("2026-10-19", today))
    }

    @Test
    fun `семь дней недели от понедельника и подписи`() {
        val days = WeekMath.days("2026-10-26")
        assertEquals(7, days.size)
        assertEquals(LocalDate.of(2026, 11, 1), days.last())
        assertEquals("26 октября — 1 ноября", WeekMath.rangeTitle("2026-10-26"))
        assertEquals("вт, 27 октября", WeekMath.dayTitle("2026-10-27"))
    }

    @Test
    fun `склонения`() {
        assertEquals("смена", plural(1, "смена", "смены", "смен"))
        assertEquals("смены", plural(3, "смена", "смены", "смен"))
        assertEquals("смен", plural(11, "смена", "смены", "смен"))
        assertEquals("смен", plural(25, "смена", "смены", "смен"))
    }

    // ── Операторы ────────────────────────────────────────────────────────────

    @Test
    fun `логин из имени транслитерируется`() {
        assertEquals("aliya.karimova", OperatorUsername.suggestion("Алия Каримова"))
        assertEquals("zhanna", OperatorUsername.suggestion("Жанна "))
        assertEquals("a.b", OperatorUsername.suggestion("а  б"))
    }

    @Test
    fun `проверка логина`() {
        assertNotNull(OperatorUsername.validationMessage("ab"))
        assertNotNull(OperatorUsername.validationMessage("a b c"))
        assertNotNull(OperatorUsername.validationMessage("алия"))
        assertNull(OperatorUsername.validationMessage("aliya.k-1"))
    }

    @Test
    fun `проверка формы оператора`() {
        assertEquals("Имя обязательно", FormChecks.operatorProblem(" ", "", ""))
        assertEquals("Проверьте почту", FormChecks.operatorProblem("Алия", "", "a@b"))
        assertEquals("Проверьте телефон", FormChecks.operatorProblem("Алия", "123", ""))
        assertNull(FormChecks.operatorProblem("Алия", "+7 700 000 00 00", "a@b.kz"))
    }

    @Test
    fun `сумма из поля понимает запятую`() {
        assertEquals(1500.5, FormChecks.amount("1 500,5"), 0.0001)
        assertEquals(0.0, FormChecks.amount("мусор"), 0.0)
    }

    @Test
    fun `оператор читается с профилем-объектом и профилем-массивом`() {
        val asObject = TeamOperator.from(
            json("""{"id":"1","name":"Алия К.","is_active":true,"operator_profiles":{"full_name":"Алия Каримова","position":"Кассир"},"auth":{"username":"aliya","user_id":"u1"},"stats":{"totalShifts":"12","totalTurnover":340000}}""").asObject()!!,
        )
        assertEquals("Алия Каримова", asObject.displayName)
        assertEquals("Кассир", asObject.position)
        assertEquals(12, asObject.stats.totalShifts)
        assertEquals("u1", asObject.authUserId)
        // Старый сервер флаги не присылает: считаем, что связка с карточкой есть, а учётка не административная.
        assertTrue(asObject.hasStaffLink)
        assertFalse(asObject.isAdminStaff)

        val asArray = TeamOperator.from(
            json("""{"id":"2","name":"Марат","operator_profiles":[{"full_name":"Марат Сеитов"}],"has_staff_link":false,"is_admin_staff":true}""").asObject()!!,
        )
        assertEquals("Марат Сеитов", asArray.displayName)
        assertFalse(asArray.hasStaffLink)
        assertTrue(asArray.isAdminStaff)
    }

    @Test
    fun `список операторов - действующие выше, дальше по обороту`() {
        val list = TeamOperator.list(
            json("""{"data":[
              {"id":"a","name":"A","is_active":false,"stats":{"totalTurnover":900}},
              {"id":"b","name":"B","stats":{"totalTurnover":100}},
              {"id":"c","name":"C","stats":{"totalTurnover":500}}]}"""),
        )
        assertEquals(listOf("c", "b", "a"), list.sortedForRoster().map { it.id })
    }

    // ── Зарплата ─────────────────────────────────────────────────────────────

    private val weekJson = """{"data":{"weekStart":"2026-10-19","weekEnd":"2026-10-25",
      "operators":[
        {"operator":{"id":"o1","name":"Алия К.","full_name":"Алия Каримова","is_active":true},"hasActivity":true,
         "week":{"grossAmount":50000,"bonusAmount":5000,"fineAmount":2000,"debtAmount":1000,"advanceAmount":10000,
                 "netAmount":42000,"paidAmount":20000,"remainingAmount":22000,"status":"partial","shiftsCount":5,
                 "payments":[{"id":"p1","payment_date":"2026-10-22","total_amount":20000,"status":"active"},
                             {"id":"p2","payment_date":"2026-10-23","total_amount":500,"status":"voided"}],
                 "adjustments":[{"id":"a1","date":"2026-10-21","amount":5000,"kind":"bonus","status":"active"}]}}],
      "totals":{"grossAmount":50000,"netAmount":42000,"paidAmount":21000,"remainingAmount":21000,"paidOperators":1,"activeOperators":4}}}"""

    @Test
    fun `ведомость недели разбирается целиком`() {
        val report = SalaryWeekReport.from(json(weekJson))
        assertEquals("2026-10-19", report.weekStart)
        val row = report.operators.single()
        assertEquals("Алия Каримова", row.operatorName)
        assertEquals(2, row.week.payments.size)
        // Отменённая выплата остаётся в истории, но откатывать её уже нечего.
        assertEquals(listOf(true, false), row.week.payments.map { it.isActive })
        assertTrue(row.week.adjustments.single().isAddition)
        assertEquals(0.5, report.totals.paidRatio, 0.0001)
    }

    @Test
    fun `статус под суммой для частичной выплаты называет остаток`() {
        val row = SalaryWeekReport.from(json(weekJson)).operators.single()
        assertTrue(row.statusText.startsWith("осталось"))
        assertEquals("частично", row.copy(week = row.week.copy(remaining = 0.0)).statusText)
        assertEquals("не выплачено", row.copy(week = row.week.copy(status = "draft")).statusText)
    }

    @Test
    fun `расчёт недели не показывает пустые строки`() {
        val week = SalaryWeekReport.from(json(weekJson)).operators.single().week
        assertEquals(
            listOf("Начислено за смены", "Премии", "Штрафы", "Долги", "Авансы", "К выплате", "Выплачено"),
            week.breakdown().map { it.label },
        )
        val clean = week.copy(bonus = 0.0, fine = 0.0, debt = 0.0, advance = 0.0, paid = 0.0)
        assertEquals(listOf("Начислено за смены", "К выплате"), clean.breakdown().map { it.label })
    }

    private fun staffRow(slots: String, extra: String = "") = StaffSalaryRow.from(
        json("""{"id":"s1","name":"Бухгалтер","paid_slots":$slots,"toPay":40000,"half":50000,"fines":10000$extra}""").asObject()!!,
    )

    @Test
    fun `открытая половина месяца - первая свободная`() {
        assertEquals("first", staffRow("[]").openSlot)
        assertEquals("second", staffRow("[\"first\"]").openSlot)
        assertEquals("first", staffRow("[\"second\"]").openSlot)
        assertNull(staffRow("[\"first\",\"second\"]").openSlot)
    }

    @Test
    fun `строка из оператора-администратора - витрина без оклада`() {
        assertTrue(staffRow("[]", ",\"source_type\":\"operator\"").isFromOperator)
        assertFalse(staffRow("[]").isFromOperator)
    }

    @Test
    fun `своя строка сводки - помеченная или единственная при self_only`() {
        val marked = StaffSalarySummary.from(json("""{"rows":[{"id":"a"},{"id":"b","is_me":true}],"slot":"second"}"""))
        assertEquals("b", marked.myRow?.id)
        assertFalse(marked.isFirstHalf)
        val selfOnly = StaffSalarySummary.from(json("""{"rows":[{"id":"x"}],"self_only":true,"me_linked":true}"""))
        assertEquals("x", selfOnly.myRow?.id)
        val none = StaffSalarySummary.from(json("""{"rows":[{"id":"x"}],"me_linked":false}"""))
        assertNull(none.myRow)
        assertEquals(false, none.meLinked)
    }

    @Test
    fun `выплаты сотруднику считаются за календарный месяц`() {
        val list = StaffList.from(
            json("""{"staff":[{"id":"s1","full_name":"Иван","monthly_salary":200000}],
              "payments":[{"id":"1","staff_id":"s1","pay_date":"2026-10-05","amount":100000},
                          {"id":"2","staff_id":"s1","pay_date":"2026-09-30","amount":50000},
                          {"id":"3","staff_id":"s2","pay_date":"2026-10-06","amount":7}]}"""),
        )
        assertEquals(100000.0, list.paidThisMonth("s1", YearMonth.of(2026, 10)), 0.0)
        assertEquals(50000.0, list.paidThisMonth("s1", YearMonth.of(2026, 9)), 0.0)
    }

    // ── График ───────────────────────────────────────────────────────────────

    private val scheduleJson = """{"schedule":{"companies":[{"id":"c1","name":"Arena"}],
      "shifts":[{"id":"1","date":"2026-10-26","operator_name":"Алия","shift_type":"day","company_id":"c1"},
                {"id":"2","date":"2026-10-26","operator_name":"Марат","shift_type":"night","company_id":"c1"},
                {"id":"3","date":"2026-10-27","operator_name":"Дина","shift_type":"day","company_id":"c1"}]},
      "requests":[{"id":"r1","company_id":"c1","operator_name":"Алия","shift_date":"2026-10-26","shift_type":"day","status":"open",
                   "lead_action":"replace","lead_status":"proposed","lead_replacement_operator_name":"Эльмира"},
                  {"id":"r2","company_id":"c1","operator_name":"Дина","shift_date":"2026-10-27","status":"resolved"}]}"""

    @Test
    fun `заявки лежат в корне ответа, открытые отдельно`() {
        val schedule = ShiftSchedule.from(json(scheduleJson))
        assertEquals(listOf("r1"), schedule.openRequests.map { it.id })
        assertEquals("Старший: заменить на Эльмира", schedule.openRequests.single().proposalLabel)
        assertEquals(2, schedule.shiftsOn("2026-10-26", "c1").size)
        assertTrue(schedule.shifts[1].isNight)
    }

    @Test
    fun `кандидаты на замену - люди точки без заявителя, предложение старшего первым`() {
        val schedule = ShiftSchedule.from(json(scheduleJson))
        val issue = schedule.openRequests.single()
        assertEquals(listOf("Эльмира", "Марат", "Дина"), schedule.replacementCandidates(issue))
    }

    // ── Отчёты смен ──────────────────────────────────────────────────────────

    private fun shift(extra: String) = ShiftReport.from(
        json("""{"id":"x","status":"closed","opening_cash":10000,"closing_cash":25000,"totals_json":{"sales_total":30000,"sales_cash":12000,"sales_kaspi":18000,"sales_count":7}$extra}""").asObject()!!,
    )

    @Test
    fun `итоги закрытой смены читаются из totals_json`() {
        val s = shift("")
        assertEquals(30000.0, s.totals.sales, 0.0)
        assertEquals(7, s.totals.count)
        // 25 000 − (10 000 + 12 000) = +3 000 по наличным.
        assertEquals(3000.0, s.computedCashDifference!!, 0.0)
        assertNull(s.cashReportDiff)
        assertEquals(3000.0, s.cashDifference!!, 0.0)
    }

    @Test
    fun `итог из отчёта кассы главнее расчёта по наличным`() {
        val shortage = shift(",\"cash_report_diff\":-1200")
        assertEquals(-1200.0, shortage.cashDifference!!, 0.0)
        assertTrue(isMaterialDiscrepancy(shortage.cashDifference))
        // Ноль в отчёте кассы - касса сошлась, даже если по наличным выходит разница.
        val matched = shift(",\"cash_report_diff\":0")
        assertEquals(0.0, matched.cashDifference!!, 0.0)
        assertFalse(isMaterialDiscrepancy(matched.cashDifference))
    }

    @Test
    fun `открытая смена не имеет расхождения, итоги берутся из live_totals`() {
        val open = ShiftReport.from(json("""{"id":"o","status":"open","live_totals":{"sales":500,"cash":200,"kaspi":300,"count":2}}""").asObject()!!)
        assertTrue(open.isOpen)
        assertNull(open.cashDifference)
        assertEquals(500.0, open.totals.sales, 0.0)
    }

    // ── Дни рождения ─────────────────────────────────────────────────────────

    @Test
    fun `дни рождения делятся на сегодня, неделю и дальше`() {
        val list = BirthdayList.from(
            json("""{"data":{"items":[{"id":"1","name":"А","daysUntil":0},{"id":"2","name":"Б","daysUntil":3,"age":30},{"id":"3","name":"В","daysUntil":20}],"stats":{"withoutBirthDate":4}}}"""),
        )
        assertEquals(listOf("1"), list.today.map { it.id })
        assertEquals(listOf("2"), list.thisWeek.map { it.id })
        assertEquals(listOf("3"), list.later.map { it.id })
        assertEquals(4, list.withoutBirthDate)
        assertEquals("завтра", Birthday("x", "x", null, null, null, 1, null).whenLabel)
        assertEquals("через 3 дня", list.items[1].whenLabel)
    }

    // ── Правила зарплаты, кадры, структура ───────────────────────────────────

    @Test
    fun `потолок смены - ставка плюс надбавки и больший бонус старшего`() {
        val rule = SalaryRule.from(
            json("""{"id":"r","company_code":"arena","shift_type":"night","base_per_shift":10000,"threshold1_bonus":2000,"threshold2_bonus":3000,"senior_operator_bonus":1000,"senior_cashier_bonus":1500,"threshold1_turnover":100000}""").asObject()!!,
        )
        // 10 000 + 2 000 + 3 000 + max(1 000; 1 500) — бонусы старших не складываются.
        assertEquals(16500.0, rule.ceilingPerShift, 0.0)
        assertTrue(rule.isNight)
        assertEquals(listOf("base", "t1", "so", "sc"), rule.terms.map { it.id }.filter { it != "t2" })
    }

    @Test
    fun `подпись стажа`() {
        assertEquals("6 месяцев", SeniorityTier("a", 6, 5.0, true).tenureLabel)
        assertEquals("2 года", SeniorityTier("a", 24, 5.0, true).tenureLabel)
        assertEquals("1 год 3 месяца", SeniorityTier("a", 15, 5.0, true).tenureLabel)
    }

    @Test
    fun `кадры - стаж до увольнения и недавние уходы`() {
        val gone = HrPerson.from(json("""{"id":"1","kind":"staff","full_name":"Иван","hire_date":"2025-01-15","is_active":false,"dismissal_date":"2026-10-10","dismissal_type":"voluntary"}""").asObject()!!)
        val today = LocalDate.of(2026, 10, 27)
        assertEquals(20, gone.tenureMonths(today))
        assertTrue(gone.leftWithin(90, today))
        assertFalse(gone.leftWithin(10, today))
        assertEquals("По собственному желанию", gone.dismissalTypeLabel)
        val working = HrPerson.from(json("""{"id":"2","kind":"operator","full_name":"Алия","hire_date":"2026-07-27"}""").asObject()!!)
        assertEquals(3, working.tenureMonths(today))
        assertEquals("Оператор", working.roleLabel)
        // Своя должность владельца показывается как есть.
        assertEquals("Шеф-повар", HrPerson.from(json("""{"id":"3","role":"Шеф-повар"}""").asObject()!!).roleLabel)
    }

    @Test
    fun `в кадрах работающие по алфавиту, уволенные ниже - свежие выше`() {
        val people = HrPerson.list(
            json("""{"data":[
              {"id":"1","full_name":"Яна"},
              {"id":"2","full_name":"Артём","is_active":false,"dismissal_date":"2026-01-01"},
              {"id":"3","full_name":"Борис"},
              {"id":"4","full_name":"Вера","is_active":false,"dismissal_date":"2026-09-01"}]}"""),
        )
        assertEquals(listOf("3", "1", "4", "2"), people.sortedForHr().map { it.id })
    }

    @Test
    fun `структура находит людей без точки и без руководителя`() {
        val s = TeamStructure.from(
            json("""{"data":{"staff":[{"id":"l1","full_name":"Руководитель"}],"companies":[{"id":"c1","name":"Arena","code":"arena"}],
              "operators":[{"id":"o1","name":"А"},{"id":"o2","name":"Б"}],
              "assignments":[{"operator_id":"o1","company_id":"c1","role_in_company":"senior_operator","is_primary":true}],
              "careerLinks":[{"operator_id":"o1","staff_id":"l1","operator":{"name":"А","operator_profiles":[{"full_name":"Алия"}]}}]}}"""),
        )
        assertEquals(listOf("o2"), s.unassignedOperators.map { it.id })
        assertEquals(listOf("o2"), s.operatorsWithoutLead.map { it.id })
        assertTrue(s.assignment("o1", "c1")!!.isSenior)
        assertEquals(listOf("Алия"), s.subordinates("l1").mapNotNull { it.operatorName })
    }

    // ── Прочее ───────────────────────────────────────────────────────────────

    @Test
    fun `пароль без похожих знаков`() {
        val forbidden = setOf('0', 'o', '1', 'l')
        repeat(50) {
            val password = generatePassword()
            assertEquals(10, password.length)
            assertTrue(password.none { it in forbidden })
        }
    }

    @Test
    fun `цвет аватара закреплён за именем`() {
        assertEquals(avatarColor("Алия"), avatarColor("Алия"))
    }
}
