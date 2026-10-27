package kz.ordaops.android.feature.business.team

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Должности и доступ»: должности офиса и точек, галочки прав, охват по точкам. */
class PositionsLogicTest {
    @Test
    fun `страница только для владельца и суперадмина`() {
        assertTrue(canManageAccess("owner", false))
        assertTrue(canManageAccess(null, true))
        assertFalse(canManageAccess("manager", false))
        assertFalse(canManageAccess(null, false))
    }

    @Test
    fun `должности офиса читаются, встроенные по-русски`() {
        val r = OfficePositions.from(
            Json.parseToJsonElement(
                """{"data":[
                {"id":"1","name":"owner","is_builtin":true,"people":1,"label":null},
                {"id":"2","name":"manager","is_builtin":true,"people":2,"label":"Управляющий"},
                {"id":"3","name":"senior_admin","is_builtin":false,"description":"Старший","people":0}],"tableExists":true}""",
            ),
        )
        assertEquals(listOf("Владелец", "Управляющий", "senior admin"), r.items.map { it.title })
        assertTrue(r.tableExists)
        assertFalse(isRenamableBuiltin("owner"))
        assertTrue(isRenamableBuiltin("manager"))
    }

    @Test
    fun `создание должности офиса`() {
        val body = createOfficePositionBody("  Админ ", "  ", PositionSeed.Copy, "manager")
        assertEquals("create", body["action"]!!.jsonPrimitive.content)
        assertEquals("Админ", body["name"]!!.jsonPrimitive.content)
        assertEquals("copy_from", body["seed"]!!.jsonPrimitive.content)
        assertEquals("manager", body["copy_from_role"]!!.jsonPrimitive.content)
        assertNull(body["description"])
        assertNull(officePositionProblem("Админ", PositionSeed.Closed, null))
        assertNotNull(officePositionProblem("А", PositionSeed.Closed, null))
        assertNotNull(officePositionProblem("Админ", PositionSeed.Copy, ""))
        // Без «скопировать» источник в тело не попадает.
        assertNull(createOfficePositionBody("Админ", null, PositionSeed.Open, "manager")["copy_from_role"])
    }

    @Test
    fun `права ролей — только выданные`() {
        val rights = RoleRights.from(
            Json.parseToJsonElement(
                """{"items":[{"role":"manager","capability":"income.view","granted":true},
                {"role":"manager","capability":"income.edit","granted":false},{"role":"other","capability":"tasks.view","granted":true}]}""",
            ),
        )
        assertTrue(rights.has("manager", "income.view"))
        assertFalse(rights.has("manager", "income.edit"))
        assertFalse(rights.has("marketer", "tasks.view"))
        assertEquals(1, rights.count("other"))
        val body = setRightBody("manager", "income.view", false)
        assertEquals("set", body["action"]!!.jsonPrimitive.content)
        assertEquals(false, body["granted"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("view_only", RightsPreset.ViewOnly.body("x")["action"]!!.jsonPrimitive.content)
    }

    // ── Точки ────────────────────────────────────────────────────────────────

    @Test
    fun `права по умолчанию — касса без чужих долгов`() {
        assertEquals(4, DefaultKassaPermissions.size)
        assertFalse("kassa_delete_debts" in DefaultKassaPermissions)
        assertEquals(DefaultKassaPermissions, legacyPointPermissions("operator"))
        assertEquals(7, legacyPointPermissions("senior_cashier").size)
    }

    @Test
    fun `должность без списка прав берёт права по базовой роли`() {
        val r = PointPositions.from(
            Json.parseToJsonElement(
                """{"data":[
                {"id":"1","company_id":null,"name":"Старший","base_role":"senior_operator","shift_bonus":null,"permissions":null,"is_active":true,"people":2},
                {"id":"2","company_id":"c1","name":"Бариста","base_role":"operator","shift_bonus":"1500","permissions":["kassa_refunds","мусор"],"is_active":false,"people":0}]}""",
            ),
        )
        val senior = r.items[0]
        assertEquals(7, senior.permissions.size)
        assertEquals("старший, касса: 4 из 5 прав", senior.permissionsSummary)
        assertEquals("бонус старшего оператора из правил", senior.payDescription)
        val barista = r.items[1]
        assertEquals(listOf("kassa_refunds"), barista.permissions)
        assertEquals("+1500 ₸ за смену", barista.payDescription)
        assertEquals("не старший, касса: 1 из 5 прав", barista.permissionsSummary)
        assertFalse(barista.isActive)
    }

    @Test
    fun `миграция не применена — флаг`() {
        val r = PointPositions.from(Json.parseToJsonElement("""{"ok":true,"data":[],"migrationPending":true}"""))
        assertTrue(r.migrationPending)
        assertTrue(r.items.isEmpty())
    }

    @Test
    fun `черновик с правилами зарплаты не шлёт доплату`() {
        val draft = PointPositionDraft(name = " Бариста  кофе ", companyId = "", bonusMode = BonusMode.SeniorCashier)
        val p = draft.payload()
        assertEquals("Бариста кофе", p["name"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, p["company_id"])
        assertEquals("senior_cashier", p["base_role"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, p["shift_bonus"])
        assertEquals(DefaultKassaPermissions, (p["permissions"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("create", draft.body()["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun `своя доплата — старший, если есть права`() {
        val withPerms = PointPositionDraft(name = "Кассир", bonusMode = BonusMode.Fixed, bonusAmount = "2500", companyId = "c1")
        val p = withPerms.payload()
        assertEquals("senior_operator", p["base_role"]!!.jsonPrimitive.content)
        assertEquals(2500L, p["shift_bonus"]!!.jsonPrimitive.content.toLong())
        assertEquals("c1", p["company_id"]!!.jsonPrimitive.content)
        val none = withPerms.copy(permissions = emptySet(), id = "7")
        assertEquals("operator", none.payload()["base_role"]!!.jsonPrimitive.content)
        assertEquals("update", none.body()["action"]!!.jsonPrimitive.content)
        assertEquals("7", none.body()["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `проверка черновика`() {
        assertNotNull(PointPositionDraft().problem)
        assertNull(PointPositionDraft(name = "Бариста").problem)
        assertNotNull(PointPositionDraft(name = "x".repeat(61)).problem)
        assertNotNull(PointPositionDraft(name = "Б", bonusMode = BonusMode.Fixed, bonusAmount = "").problem)
        assertNull(PointPositionDraft(name = "Б", bonusMode = BonusMode.Fixed, bonusAmount = "0").problem)
    }

    @Test
    fun `галочка переключается и правится из должности`() {
        val d = PointPositionDraft(name = "Б").toggled("kassa_refunds")
        assertFalse("kassa_refunds" in d.permissions)
        assertTrue("point_tasks" in d.toggled("point_tasks").permissions)
        val p = PointPosition("1", "c", "Бар", "senior_operator", null, null, true, 0)
        val back = PointPositionDraft.from(p)
        assertEquals(BonusMode.SeniorOperator, back.bonusMode)
        assertEquals(7, back.permissions.size)
        val fixed = PointPositionDraft.from(p.copy(shiftBonus = 1000.0))
        assertEquals(BonusMode.Fixed, fixed.bonusMode)
        assertEquals("1000", fixed.bonusAmount)
    }

    @Test
    fun `архив и возврат`() {
        assertEquals("archive", archivePointPositionBody("1", true)["action"]!!.jsonPrimitive.content)
        assertEquals("restore", archivePointPositionBody("1", false)["action"]!!.jsonPrimitive.content)
    }

    // ── Охват ────────────────────────────────────────────────────────────────

    @Test
    fun `охват сотрудника по точкам`() {
        val names = mapOf("a" to "Север", "b" to "Юг")
        assertEquals("Все точки", scopeSummary(null, names))
        assertEquals("Все точки", scopeSummary(emptyList(), names))
        assertEquals("2 точки: Север, Юг", scopeSummary(listOf("a", "b"), names))
        assertEquals("1 точка", scopeSummary(listOf("zz"), names))
        val all = setStaffCompaniesBody("s1", emptySet())
        assertEquals("setStaffCompanies", all["action"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, all["companyIds"])
        val some = setStaffCompaniesBody("s1", setOf("b", "a"))
        assertEquals(listOf("a", "b"), some["companyIds"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `ответ staff с охватом`() {
        val list = StaffList.from(
            Json.parseToJsonElement(
                """{"staff":[{"id":"1","full_name":"А","role":"manager","monthly_salary":1,"is_active":true,"company_ids":["a","b"]},
                {"id":"2","full_name":"Б","role":"other","monthly_salary":1,"company_ids":null},
                {"id":"3","full_name":"В","role":"other","monthly_salary":1,"company_ids":[]}],"payments":[]}""",
            ),
        )
        assertEquals(listOf("a", "b"), list.staff[0].companyIds)
        assertNull(list.staff[1].companyIds)
        assertNull(list.staff[2].companyIds) // пустой список — те же «все точки»
    }
}
