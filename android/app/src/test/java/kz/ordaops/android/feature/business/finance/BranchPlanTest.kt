package kz.ordaops.android.feature.business.finance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Финмодель новой точки: расчёт как на сайте, разбор и сохранение черновика. */
class BranchPlanTest {
    private fun tiny(ramp: BpRamp = BpRamp(false, listOf(1.0))): BranchDraft {
        val t = BpTariff("t1", "2ч", 2.0, 0.0, 200.0)
        return BranchDraft(
            name = "Тест",
            pcConfigs = listOf(BpPcConfig("p1", "ПК", 2.0, listOf(BpComponent("c1", "ЦП", 100.0), BpComponent("c2", "ОЗУ", 50.0)))),
            capex = listOf(BpCapexRow("x1", "Ремонт", 1000.0, 1.0)),
            tariffs = listOf(t),
            zones = listOf(BpZone("z1", "Зал", 10.0, 6.0, listOf(BpZoneMix("t1", 100.0)))),
            opex = listOf(BpOpex("o1", "Аренда", 50_000.0, false), BpOpex("o2", "Налог", 10.0, true)),
            scenarios = BpScenarios(BpScenarioCfg(1.5, 1.0), BpScenarioCfg(1.0, 1.0), BpScenarioCfg(0.5, 2.0)),
            ramp = ramp,
        )
    }

    @Test
    fun `вложения считаются по комплектующим и прочим статьям`() {
        val c = calcBranch(tiny())
        assertEquals(300.0, c.pcCapex, 0.001)
        assertEquals(1000.0, c.otherCapex, 0.001)
        assertEquals(1300.0, c.totalCapex, 0.001)
    }

    @Test
    fun `выручка зоны и ожидаемый сценарий`() {
        val c = calcBranch(tiny())
        // 10 устройств x 6 ч x 100 тг/ч x 30 дней
        assertEquals(180_000.0, c.baseRevenue, 0.001)
        assertEquals(50_000.0, c.opexFixed, 0.001)
        assertEquals(18_000.0, c.opexPercent, 0.001)
        assertEquals(112_000.0, c.profit, 0.001)
        assertEquals(1300.0 / 112_000.0, c.paybackMonths!!, 1e-9)
    }

    @Test
    fun `худший сценарий в минусе не окупается`() {
        val c = calcBranch(tiny())
        assertEquals(90_000.0, c.worst.revenue, 0.001)
        // фикс x2 = 100 000, процент 10% от 90 000
        assertEquals(109_000.0, c.worst.opex, 0.001)
        assertEquals(-19_000.0, c.worst.profit, 0.001)
        assertNull(c.worst.payback)
        assertNotNull(c.best.payback)
    }

    @Test
    fun `точка безубыточности`() {
        val c = calcBranch(tiny())
        assertEquals(50_000.0 / 27_000.0, c.breakEvenHours!!, 1e-9)
    }

    @Test
    fun `кэш без ramp-up и с ним`() {
        val plain = calcBranch(tiny()).cashFlow
        assertEquals(25, plain.size)
        assertEquals(-1300L, plain[0].cash)
        assertEquals(110_700L, plain[1].cash)

        val ramp = calcBranch(tiny(BpRamp(true, listOf(0.5)))).cashFlow
        assertEquals(29_700L, ramp[1].cash)
        // после последнего значения идёт оно же
        assertEquals(60_700L, ramp[2].cash)
    }

    @Test
    fun `без зон и тарифов безубыточности нет и всё не падает`() {
        val c = calcBranch(tiny().copy(zones = emptyList(), tariffs = emptyList()))
        assertNull(c.breakEvenHours)
        assertEquals(0.0, c.baseRevenue, 0.0)
        assertNull(c.paybackMonths)
    }

    @Test
    fun `отрицательные и кривые числа считаются нулём`() {
        assertEquals(0.0, bpNum(-5.0), 0.0)
        assertEquals(0.0, bpNum(Double.NaN), 0.0)
        assertEquals(1.5, bpNum("1,5"), 0.0)
        assertEquals(0.0, bpNum("abc"), 0.0)
        assertEquals(0.0, bpNum("-3"), 0.0)
    }

    @Test
    fun `шаблон по умолчанию считается`() {
        val c = calcBranch(defaultBranchDraft())
        assertTrue(c.totalCapex > 0)
        assertTrue(c.revenue > 0)
        assertEquals(24, c.cashFlow.size - 1)
    }

    @Test
    fun `черновик сохраняется и читается без потерь`() {
        val d = tiny(BpRamp(true, listOf(0.3, 1.0))).copy(id = "abc")
        val json = Json.parseToJsonElement(
            """{"id":"abc","name":"Тест","payload":${d.payloadJson()}}""",
        ).jsonObject
        val back = parseBranchDraft(json)
        assertEquals("abc", back.id)
        assertEquals(d.pcConfigs, back.pcConfigs)
        assertEquals(d.capex, back.capex)
        assertEquals(d.tariffs, back.tariffs)
        assertEquals(d.zones, back.zones)
        assertEquals(d.opex, back.opex)
        assertEquals(d.scenarios, back.scenarios)
        assertEquals(d.ramp, back.ramp)
    }

    @Test
    fun `в сохранённых числах нет лишних дробных нулей`() {
        val s = tiny().payloadJson().toString()
        assertTrue(s.contains("\"unit_price\":1000"))
        assertTrue(!s.contains("1000.0"))
        assertTrue(s.contains("\"kind\":\"percent_of_revenue\""))
    }

    @Test
    fun `пустой payload берёт шаблон, числа-строки читаются`() {
        val back = parseBranchDraft(Json.parseToJsonElement("""{"id":"x","name":"","payload":{}}""").jsonObject)
        assertEquals("Новая точка", back.name)
        assertEquals(defaultBranchDraft().pcConfigs.size, back.pcConfigs.size)
        val withStrings = parseBranchDraft(
            Json.parseToJsonElement("""{"id":"x","name":"A","payload":{"capex":[{"name":"Стол","unit_price":"2500,5","quantity":"2"}],"opex":[]}}""").jsonObject,
        )
        assertEquals(2500.5, withStrings.capex.single().unitPrice, 0.0)
        assertEquals(2.0, withStrings.capex.single().quantity, 0.0)
        assertTrue(withStrings.opex.isEmpty())
    }

    @Test
    fun `список и один черновик разбираются`() {
        val list = parseBranchDraftList(Json.parseToJsonElement("""{"ok":true,"data":{"drafts":[{"id":"1","name":"А","updated_at":"2026-10-01T10:00:00Z"},{"name":"без id"}]}}"""))
        assertEquals(listOf("1"), list.map { it.id })
        val one = parseBranchDraftResponse(Json.parseToJsonElement("""{"ok":true,"data":{"draft":{"id":"1","name":"А","payload":{}}}}"""))
        assertEquals("А", one!!.name)
    }
}
