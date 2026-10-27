package kz.ordaops.android.feature.business.platform

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Журнал действий: разбор ответа, фильтры, сводка, заголовки. */
class LogsLogicTest {
    private val sample = """
        {"ok":true,"total":3,"page":1,"limit":80,
         "items":[
           {"id":"a","kind":"audit","createdAt":"2026-10-27T10:00:00Z","title":"Алия добавила доход 120 000 ₸","subtitle":null,"details":null,"detailRows":["Дата: 27 октября","Статус: sent"],"entityType":"income","action":"create","actorEmail":"aliya@x.kz","channel":null,"status":null,"recipient":null,"payload":null},
           {"id":"b","kind":"audit","createdAt":"2026-10-27T10:01:00Z","title":"admin • create","subtitle":null,"details":"x","detailRows":[],"entityType":"system-error","action":"error","actorEmail":null,"channel":null,"status":null,"recipient":null,"payload":null},
           {"id":"c","kind":"notification","createdAt":"2026-10-27T10:02:00Z","title":"Telegram → 1","detailRows":[],"entityType":null,"action":null,"actorEmail":null,"channel":"telegram","status":"failed","recipient":"1"},
           {"kind":"audit"}
         ],
         "filters":{"kinds":["audit"],"entityTypes":["income","system-error"],"actions":["create"],"actors":["aliya@x.kz"],"channels":[],"statuses":[]}}
    """.trimIndent()

    @Test
    fun `ответ разбирается, запись без id отбрасывается`() {
        val page = parseLogPage(Json.parseToJsonElement(sample))
        assertEquals(listOf("a", "b", "c"), page.items.map { it.id })
        assertEquals(3, page.total)
        assertEquals(listOf("aliya@x.kz"), page.actors)
        assertEquals(listOf("income", "system-error"), page.entityTypes)
        assertFalse(page.hasNext)
        assertFalse(page.hasPrev)
    }

    @Test
    fun `пустой и кривой ответ не падает`() {
        assertEquals(0, parseLogPage(Json.parseToJsonElement("[]")).items.size)
        assertEquals(0, parseLogPage(Json.parseToJsonElement("{}")).items.size)
    }

    @Test
    fun `ошибки определяются как на сайте`() {
        val items = parseLogPage(Json.parseToJsonElement(sample)).items
        assertFalse(items[0].isError)
        assertTrue(items[1].isError)
        assertTrue(items[2].isError)
        assertEquals(2, logStats(items).errors)
        assertEquals(1, logStats(items).finance)
        assertEquals(1, logStats(items).notifications)
    }

    @Test
    fun `параметры запроса — только заданные фильтры`() {
        assertEquals(mapOf("page" to "1", "limit" to "80"), LogFilters().params(1))
        val p = LogFilters(search = " ошибка ", domain = "finance", onlyErrors = true, includeNoise = true).params(3)
        assertEquals("ошибка", p["q"])
        assertEquals("finance", p["domain"])
        assertEquals("true", p["onlyErrors"])
        assertEquals("true", p["includeNoise"])
        assertEquals("3", p["page"])
    }

    @Test
    fun `наборы фильтров сбрасывают остальное и не трогают шум`() {
        val dirty = LogFilters(search = "x", entityType = "income", actor = "a", includeNoise = true)
        val sited = dirty.withPreset(LogPreset.SiteErrors)
        assertEquals("site-errors", sited.domain)
        assertTrue(sited.onlyErrors)
        assertEquals("", sited.entityType)
        assertTrue(sited.includeNoise)
        val auth = dirty.withPreset(LogPreset.Auth)
        assertEquals("audit", auth.kind)
        val errors = dirty.withPreset(LogPreset.Errors)
        assertEquals("system-error", errors.entityType)
        assertTrue(errors.isPresetActive(LogPreset.Errors))
        assertTrue(LogFilters().isPresetActive(LogPreset.All))
        assertFalse(errors.isPresetActive(LogPreset.All))
    }

    @Test
    fun `заголовок сервера берётся, технический заменяется`() {
        val items = parseLogPage(Json.parseToJsonElement(sample)).items
        assertEquals("Алия добавила доход 120 000 ₸", logTitle(items[0]))
        assertEquals("Система — Ошибка системы (ошибка)", logTitle(items[1]))
    }

    @Test
    fun `строки деталей переводятся`() {
        assertEquals("Статус" to "отправлено", logDetailRow("Статус: sent"))
        assertEquals("Просто текст" to "", logDetailRow("Просто текст"))
        assertEquals("Где упало" to "/income", logDetailRow("Где упало: /income"))
    }

    @Test
    fun `запрос объяснения собирает область и сообщение`() {
        val item = LogItem("1", "audit", "", "Ошибка", null, "подробно", listOf("Где упало: api/x", "Сообщение: boom"), "system-error", "error", null, null, null, null)
        val body = explainErrorBody(item)
        assertEquals("api/x", body["area"]!!.toString().trim('"'))
        assertEquals("Где упало: api/x · Сообщение: boom", body["message"]!!.toString().trim('"'))
        assertEquals("system-error", body["entityType"]!!.toString().trim('"'))
    }

    @Test
    fun `относительное время`() {
        val now = Instant.parse("2026-10-27T12:00:00Z")
        val zone = ZoneId.of("UTC")
        assertEquals("только что", logRelativeTime("2026-10-27T11:59:30Z", now, zone))
        assertEquals("5 мин назад", logRelativeTime("2026-10-27T11:55:00Z", now, zone))
        assertEquals("3 ч назад", logRelativeTime("2026-10-27T09:00:00Z", now, zone))
        assertEquals("2 д назад", logRelativeTime("2026-10-25T12:00:00Z", now, zone))
        assertEquals("", logRelativeTime("не дата", now, zone))
    }
}
