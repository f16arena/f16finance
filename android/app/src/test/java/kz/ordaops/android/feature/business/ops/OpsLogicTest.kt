package kz.ordaops.android.feature.business.ops

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kz.ordaops.android.core.net.ApiJson
import kz.ordaops.android.core.net.userIdFromJwt
import kz.ordaops.android.feature.operator.chat.TeamChatMessage
import kz.ordaops.android.feature.operator.chat.isOwnTeamMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

private fun obj(text: String): JsonObject = ApiJson.parseToJsonElement(text).jsonObject

/** Зеркало TaskDraftTests / IncidentDraft / CashboxModels из OrdaKit: правила форм и терпимый разбор. */
class OpsLogicTest {

    // ── Терпимое чтение ─────────────────────────────────────────────────────

    @Test
    fun flexibleReadingAcceptsNumbersAndStrings() {
        val o = obj("""{"id": 42, "amount": "1 500", "n": "12,5", "flag": 1, "x": null}""")
        assertEquals("42", o.str("id"))
        assertEquals(12.5, o.num("n")!!, 0.0)
        assertNull(o.num("amount")) // пробел внутри числа не разбираем
        assertEquals(true, o.bool("flag"))
        assertNull(o.str("x"))
        assertNull(o.str("missing"))
    }

    // ── Задачи ──────────────────────────────────────────────────────────────

    @Test
    fun taskDraftNeedsTitleAndCompany() {
        assertEquals("Название задачи обязательно", TaskDraft(companyId = "c1").validationMessage)
        assertEquals("Для задачи нужно выбрать точку", TaskDraft(title = "Лампа").validationMessage)
        assertTrue(TaskDraft(title = " Лампа ", companyId = "c1").isValid)
    }

    @Test
    fun createTaskBodyWritesExplicitNulls() {
        val body = TaskDraft(title = "  Лампа ", companyId = "c1").body()
        assertEquals("createTask", body["action"]!!.jsonPrimitive.content)
        val payload = body["payload"]!!.jsonObject
        assertEquals("Лампа", payload["title"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, payload["description"])
        assertEquals(JsonNull, payload["operator_id"])
        assertEquals(JsonNull, payload["due_date"])
        assertEquals("medium", payload["priority"]!!.jsonPrimitive.content)
        assertEquals("todo", payload["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun patchSendsOnlyChangedKeys() {
        val body = TaskPatch(dueDate = "", operatorId = "").body("t1")
        assertEquals("updateTask", body["action"]!!.jsonPrimitive.content)
        assertEquals("t1", body["taskId"]!!.jsonPrimitive.content)
        val payload = body["payload"]!!.jsonObject
        assertEquals(setOf("due_date", "operator_id"), payload.keys)
        assertEquals("", payload["due_date"]!!.jsonPrimitive.content) // пустая строка — «снять»
    }

    @Test
    fun taskStateAndPriorityFallBackSafely() {
        assertEquals(TaskState.Todo, TaskState.of("что-то новое"))
        assertEquals(TaskPriority.Medium, TaskPriority.of(null))
        assertFalse(TaskState.Archived in TaskState.selectable)
    }

    @Test
    fun overdueIgnoresDoneTasks() {
        val today = LocalDate.of(2026, 10, 27)
        val late = OpsTask.parse(obj("""{"id":"1","title":"а","status":"todo","due_date":"2026-10-20"}"""))
        val doneLate = OpsTask.parse(obj("""{"id":"2","title":"б","status":"done","due_date":"2026-10-20"}"""))
        assertTrue(late.isOverdue(today))
        assertFalse(doneLate.isOverdue(today))
    }

    @Test
    fun boardOrderIsOverdueUrgentThenDue() {
        val today = LocalDate.of(2026, 10, 27)
        fun t(id: String, due: String?, priority: String = "medium") =
            OpsTask.parse(obj("""{"id":"$id","title":"$id","status":"todo","priority":"$priority"${if (due != null) ",\"due_date\":\"$due\"" else ""}}"""))
        val sorted = sortTasks(
            listOf(t("noDue", null), t("later", "2026-11-05"), t("urgent", "2026-11-09", "high"), t("late", "2026-10-01")),
            today,
        ).map { it.id }
        assertEquals(listOf("late", "urgent", "later", "noDue"), sorted)
    }

    @Test
    fun taskParsesChecklistAndCounts() {
        val task = OpsTask.parse(
            obj("""{"id":7,"task_number":12,"title":"T","comments_count":"3","checklist":[{"text":"a","done":true},{"title":"b"}]}"""),
        )
        assertEquals("7", task.id)
        assertEquals(12, task.number)
        assertEquals(3, task.commentsCount)
        assertEquals(1, task.doneCount)
        assertEquals("b", task.checklist[1].text)
    }

    // ── Инциденты ───────────────────────────────────────────────────────────

    @Test
    fun incidentDraftRejectsMismatchedMoney() {
        val base = IncidentDraft(companyId = "c", title = "Витрина")
        assertEquals("Точка обязательна", IncidentDraft(title = "x").validationMessage)
        assertEquals("Название обязательно", IncidentDraft(companyId = "c").validationMessage)
        assertEquals("У поощрения не бывает штрафа", base.copy(kind = IncidentKind.Bonus, fineAmount = 100.0).validationMessage)
        assertEquals("У нарушения не бывает премии", base.copy(bonusAmount = 100.0).validationMessage)
        assertTrue(base.copy(fineAmount = 500.0).isValid)
    }

    @Test
    fun incidentBodyOmitsEmptyOptionalFields() {
        val body = IncidentDraft(companyId = "c", title = " Опоздание ", fineAmount = 500.0).body()
        assertEquals("Опоздание", body["title"]!!.jsonPrimitive.content)
        assertEquals("manual", body["source"]!!.jsonPrimitive.content)
        assertEquals("confirmed", body["status"]!!.jsonPrimitive.content)
        assertTrue("description" !in body)
        assertTrue("bonus_amount" !in body)
        assertTrue("subject_staff_id" !in body)
        assertEquals(500.0, body["fine_amount"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun incidentTotalsAndNet() {
        val rows = listOf(
            OpsIncident.parse(obj("""{"id":"1","kind":"violation","title":"a","fine_amount":500,"status":"draft"}""")),
            OpsIncident.parse(obj("""{"id":"2","kind":"bonus","title":"b","bonus_amount":"1200","status":"confirmed"}""")),
        )
        val totals = incidentTotals(rows)
        assertEquals(500.0, totals.fines, 0.0)
        assertEquals(1200.0, totals.bonuses, 0.0)
        assertEquals(700.0, totals.net, 0.0)
        assertEquals(1, totals.pending)
        assertEquals("Подтверждён", rows[1].statusLabel)
    }

    @Test
    fun incidentNamesComeFromNestedObjects() {
        val i = OpsIncident.parse(obj("""{"id":"1","title":"t","company":{"name":"F16"},"subject":{"full_name":"Алия К."},"photo_urls":["a","b"]}"""))
        assertEquals("F16", i.companyName)
        assertEquals("Алия К.", i.subjectName)
        assertEquals(2, i.photoCount)
    }

    // ── Клиенты и баллы ─────────────────────────────────────────────────────

    @Test
    fun customerSearchAndAverageCheck() {
        val c = OpsCustomer.parse(obj("""{"id":"1","name":"Иван Петров","phone":"+7 777","card_number":"4600","total_spent":10000,"visits_count":4,"loyalty_points":"120"}"""))
        assertEquals(2500.0, c.averageCheck, 0.0)
        assertEquals("ИП", c.initials)
        assertTrue(c.matches("иван"))
        assertTrue(c.matches("777"))
        assertTrue(c.matches("4600"))
        assertFalse(c.matches("мария"))
        assertEquals(0.0, OpsCustomer.parse(obj("""{"id":"2","name":"Н"}""")).averageCheck, 0.0)
    }

    @Test
    fun pointsAdjustmentBodiesAndGuards() {
        val body = adjustPointsBody("c1", -50, " не прошло ")
        assertEquals("adjustPoints", body["action"]!!.jsonPrimitive.content)
        assertEquals(-50, body["delta"]!!.jsonPrimitive.content.toInt())
        assertEquals("не прошло", body["reason"]!!.jsonPrimitive.content)
        assertTrue("reason" !in adjustPointsBody("c1", 10, "  "))
        assertEquals(30.0, resultingPoints(80.0, 50, adding = false), 0.0)
        assertTrue(resultingPoints(10.0, 50, adding = false) < 0)
        assertEquals(1234, parsePointsInput("1 234 балла"))
    }

    @Test
    fun createCustomerBodyKeepsOnlyFilledFields() {
        val payload = createCustomerBody(" Иван ", "", "4600", null)["payload"]!!.jsonObject
        assertEquals(setOf("name", "card_number"), payload.keys)
        assertEquals("Иван", payload["name"]!!.jsonPrimitive.content)
    }

    // ── Чеки и возвраты ─────────────────────────────────────────────────────

    private val receiptJson = """{"data":[
        {"id":"aaaaaaaa-1c54e4","sold_at":"2026-10-27T09:30:00Z","cash_amount":1000,"kaspi_amount":500,"total_amount":1500,
         "discount_amount":100,"source":"point-client","items":[{"id":"l1","quantity":2,"unit_price":300,"inventory_items":{"name":"Чай"}},
         {"id":"l2","quantity":1,"unit_price":900,"universal_name":"Услуга","total_price":900}]},
        {"id":"bbbbbbbb-000001","sold_at":"2026-10-27T10:00:00Z","kaspi_amount":700,"total_amount":700,"items":[]}],"total":2}"""

    @Test
    fun receiptParsingAndPaymentKind() {
        val receipts = obj(receiptJson).items("data").map(PosReceipt::parse)
        assertEquals("1C54E4", receipts[0].shortNumber)
        assertEquals(PosPaymentKind.Mixed, receipts[0].paymentKind)
        assertEquals(PosPaymentKind.Kaspi, receipts[1].paymentKind)
        assertEquals("Чай", receipts[0].items[0].name)
        assertEquals(600.0, receipts[0].items[0].totalPrice, 0.0) // нет total_price — считаем сами
        assertEquals("Услуга", receipts[0].items[1].name)
        assertEquals("Касса точки", receipts[0].sourceLabel)
        assertEquals("Канал не указан", receipts[1].sourceLabel)
    }

    @Test
    fun salesSummaryTotals() {
        val receipts = obj(receiptJson).items("data").map(PosReceipt::parse)
        val s = PosSalesSummary.of(receipts)
        assertEquals(2200.0, s.revenue, 0.0)
        assertEquals(2, s.count)
        assertEquals(1100.0, s.averageCheck, 0.0)
        assertEquals(1200.0, s.kaspi, 0.0)
        assertEquals(3.0, s.itemsSold, 0.0)
        assertEquals(1200.0 / 2200.0, s.cashlessShare!!, 1e-9)
        assertEquals("Касса точки", PosSalesSummary.sources(receipts)[0].label)
    }

    @Test
    fun receiptFilterByNumberOrAmountNewestFirst() {
        val receipts = obj(receiptJson).items("data").map(PosReceipt::parse)
        assertEquals(listOf("000001", "1C54E4"), filterReceipts(receipts, "").map { it.shortNumber })
        assertEquals(listOf("1C54E4"), filterReceipts(receipts, "1c54").map { it.shortNumber })
        assertEquals(listOf("000001"), filterReceipts(receipts, "700").map { it.shortNumber })
    }

    @Test
    fun periodRanges() {
        val today = LocalDate.of(2026, 10, 27)
        assertEquals("2026-10-27" to "2026-10-27", PosPeriod.Today.range(today))
        assertEquals("2026-10-26" to "2026-10-26", PosPeriod.Yesterday.range(today))
        assertEquals("2026-10-21" to "2026-10-27", PosPeriod.Week.range(today))
    }

    private val saleJson = """{"id":"11111111-2222-3333-4444-555555555555","total_amount":1800,"cash_amount":1800,"items":[
        {"id":"a","item_id":"i1","quantity":3,"unit_price":400,"returned_qty":1,"inventory_items":[{"name":"Сок"}]},
        {"id":"b","item_id":null,"universal_name":"Печать","quantity":1,"unit_price":600,"returned_qty":1,"returnable_qty":0},
        {"id":"c","quantity":2,"unit_price":50,"universal_name":"Ручка"}]}"""

    @Test
    fun returnParsingKeepsOnlyReturnableLines() {
        val sale = PosSaleForReturn.parse(obj(saleJson))
        assertEquals(PosPaymentKind.Cash, sale.paymentKind)
        assertTrue(sale.hasPreviousReturns)
        assertEquals(listOf("a", "c"), sale.returnable.map { it.id })
        assertEquals(2.0, sale.returnable[0].returnableQuantity, 0.0) // 3 − 1
        assertEquals(2.0, sale.returnable[1].returnableQuantity, 0.0) // нет returnable_qty — всё проданное
    }

    @Test
    fun returnBodyAndAmount() {
        val sale = PosSaleForReturn.parse(obj(saleJson))
        val quantities = mapOf("a" to 2.0, "c" to 1.0, "b" to 5.0)
        assertEquals(2 * 400.0 + 50.0, returnAmount(sale, quantities), 0.0)
        val body = returnBody(sale, quantities, "  брак ")
        assertEquals("брак", body["reason"]!!.jsonPrimitive.content)
        val items = (body["items"] as JsonArray).map { it.jsonObject }
        assertEquals(2, items.size) // полностью возвращённая позиция b не уходит
        assertEquals("i1", items[0]["item_id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, items[0]["universal_name"])
        assertEquals(JsonNull, items[1]["item_id"])
        assertEquals("Ручка", items[1]["universal_name"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, returnBody(sale, quantities, "   ")["reason"])
    }

    @Test
    fun returnLookupDistinguishesFullIdFromShortNumber() {
        assertEquals("short_id" to "1C54E4", returnLookupQuery(" 1C54E4 "))
        assertEquals("sale_id" to "11111111-2222-3333-4444-555555555555", returnLookupQuery("11111111-2222-3333-4444-555555555555"))
    }

    // ── Устройства, настройки, диагностика ──────────────────────────────────

    @Test
    fun deviceStatusAndOffline() {
        val now = Instant.parse("2026-10-27T12:00:00Z")
        val fresh = PointProject.parse(obj("""{"id":"1","name":"A","point_mode":"pos","last_seen_at":"2026-10-27T08:00:00Z"}"""))
        val stale = PointProject.parse(obj("""{"id":"2","name":"B","last_seen_at":"2026-10-20T08:00:00Z"}"""))
        val never = PointProject.parse(obj("""{"id":"3","name":"C"}"""))
        val off = PointProject.parse(obj("""{"id":"4","name":"D","is_active":false}"""))
        assertEquals("на связи", fresh.statusText(now))
        assertEquals("молчит", stale.statusText(now))
        assertEquals("не подключалась", never.statusText(now))
        assertEquals("выключена", off.statusText(now))
        assertEquals("Касса", fresh.modeLabel)
        assertEquals(listOf("2", "3"), offlineProjects(listOf(fresh, stale, never, off), now).map { it.id })
    }

    @Test
    fun toggleBody() {
        val body = toggleProjectBody("p1", false)
        assertEquals("toggleProjectActive", body["action"]!!.jsonPrimitive.content)
        assertEquals("false", body["is_active"]!!.jsonPrimitive.content)
    }

    @Test
    fun settingsLimitsAndStaffOrder() {
        val s = SystemSettings.parse(
            obj(
                """{"companyLimit":3,"companies":[{"id":"1","name":"A"},{"id":"2","name":"B"}],
                    "staff":[{"id":"a","full_name":"Яна","role":"other"},{"id":"b","full_name":"Борис","role":"manager"}],
                    "categories":[{"id":"c","name":"Аренда","monthly_budget":"100000"},{"id":"d","name":"Прочее"}]}""",
            ),
        )
        assertEquals(1, s.freeCompanySlots)
        assertFalse(s.isCompanyLimitReached)
        assertEquals(100000.0, s.monthlyBudgetTotal, 0.0)
        assertEquals(listOf("Борис", "Яна"), s.sortedStaff.map { it.fullName })
        assertTrue(SystemSettings.parse(obj("""{"companyLimit":1,"companies":[{"id":"1"}]}""")).isCompanyLimitReached)
    }

    @Test
    fun telegramStatusReading() {
        val st = TelegramStatus.parse(
            obj("""{"hasToken":true,"hasChatId":false,"botInfo":{"username":"orda_bot"},"webhookInfo":{"isConfigured":true,"pending_update_count":4,"last_error_date":1700000000}}"""),
        )
        assertTrue(st.isOperational)
        assertEquals("@orda_bot", st.botName)
        assertEquals(4, st.pendingUpdates)
        assertEquals(Instant.ofEpochSecond(1_700_000_000), st.lastErrorAt)
        assertFalse(TelegramStatus.parse(obj("""{"hasToken":true}""")).isOperational)
    }

    @Test
    fun diagnosticsChecks() {
        val now = Instant.parse("2026-10-27T12:00:00Z")
        val d = SystemDiagnostics.parse(
            obj(
                """{"migrations":{"applied_count":10,"file_count":12,"pending":["a","b"],"extra":[]},
                    "crons":[{"path":"/api/cron/a","schedule":"0 * * * *","last_run_at":"2026-10-27T11:00:00Z"},
                             {"path":"/api/cron/b","schedule":"daily","last_run_at":"2026-10-20T11:00:00Z"},
                             {"path":"/api/cron/c","schedule":"daily"}]}""",
            ),
        )
        val states = d.checks(now).map { it.state }
        assertEquals(
            listOf(DiagnosticCheck.State.Failure, DiagnosticCheck.State.Ok, DiagnosticCheck.State.Warning, DiagnosticCheck.State.Unknown),
            states,
        )
        assertEquals(DiagnosticCheck.State.Unknown, SystemDiagnostics.parse(obj("{}")).checks(now).first().state)
    }

    // ── Чат команды и JWT ───────────────────────────────────────────────────

    @Test
    fun staffRecognizesOwnTeamMessagesByUserId() {
        val message = TeamChatMessage(id = "m", senderUserId = "u1", senderOperatorId = null)
        // Сотрудник: operatorId нет, своё сообщение — по учётке входа.
        assertTrue(isOwnTeamMessage(message, operatorId = null, userId = "u1"))
        assertFalse(isOwnTeamMessage(message, operatorId = null, userId = "u2"))
        assertFalse(isOwnTeamMessage(message, operatorId = null, userId = null))
        // Оператор по-прежнему узнаётся по operatorId.
        val fromOperator = TeamChatMessage(id = "m2", senderUserId = "u9", senderOperatorId = "op1")
        assertTrue(isOwnTeamMessage(fromOperator, operatorId = "op1", userId = null))
        assertFalse(isOwnTeamMessage(fromOperator, operatorId = "op2", userId = "u1"))
        assertTrue(isOwnTeamMessage(TeamChatMessage(id = "p", isPending = true), null, null))
    }

    @Test
    fun jwtSubject() {
        // {"alg":"none"}.{"sub":"user-42"}.
        val token = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ1c2VyLTQyIn0.sig"
        assertEquals("user-42", userIdFromJwt(token))
        assertNull(userIdFromJwt("мусор"))
        assertNull(userIdFromJwt(null))
    }

    @Test
    fun statusHumanizeKnownAndUnknown() {
        assertEquals("Ожидает", StatusText.humanize("pending"))
        assertEquals("In review", StatusText.humanize("in_review"))
    }
}
