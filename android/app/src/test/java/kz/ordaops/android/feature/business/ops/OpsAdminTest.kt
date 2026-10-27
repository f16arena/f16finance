package kz.ordaops.android.feature.business.ops

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

/** Лента, регламенты, модерация, станции и действия настроек: чистая логика. */
class OpsAdminTest {
    // ── Лента ────────────────────────────────────────────────────────────────

    @Test
    fun `лента читается и считает непрочитанные`() {
        val feed = NewsFeed.parse(
            obj(
                """{"posts":[
                {"id":"1","author_name":"Айдос","title":" ","body":"Привет","viewed":false,"link_url":"https://a.kz","link_label":""},
                {"id":"2","author_name":"Айдос","title":"Итоги","body":"Тест","viewed":true,"link_url":"https://b.kz","link_label":"Смотреть"}],
                "unreadCount":1,"canPublish":true}""",
            ),
        )
        assertEquals(2, feed.posts.size)
        assertNull(feed.posts[0].title)
        assertEquals("https://a.kz", feed.posts[0].linkText)
        assertEquals("Смотреть", feed.posts[1].linkText)
        assertEquals(1, feed.unread)
        assertTrue(feed.canPublish)
    }

    @Test
    fun `пост — пустой и слишком длинный не уходят`() {
        assertNotNull(newsProblem("   "))
        assertNull(newsProblem("Текст"))
        assertNotNull(newsProblem("я".repeat(NewsMaxLength + 1)))
        val body = newsBody(" ", " Привет ", "https://a.kz", "Тут")
        assertNull(body["title"])
        assertEquals("Привет", body["body"]!!.jsonPrimitive.content)
        assertEquals("Тут", body["linkLabel"]!!.jsonPrimitive.content)
        assertNull(newsBody("", "x", "", "Подпись без ссылки")["linkLabel"])
        assertEquals("9", newsDeleteBody("9")["id"]!!.jsonPrimitive.content)
    }

    // ── Регламенты ───────────────────────────────────────────────────────────

    @Test
    fun `регламенты — статьи, чек-листы с пунктами и прохождения`() {
        val d = KnowledgeData.parse(
            obj(
                """{"data":{
                "categories":[{"id":"c1","title":"Правила смены"}],
                "articles":[{"id":"a1","category_id":"c1","title":"Приём смены","severity":"critical","is_published":false,"requires_confirmation":true,"version":3,"related_fine_amount":5000}],
                "templates":[{"id":"t1","title":"Открытие","shift_scope":"opening","is_active":true,"company_id":null}],
                "items":[{"id":"i1","template_id":"t1","title":"Включить свет","is_required":true,"requires_photo":false},
                         {"id":"i2","template_id":"t1","title":"Фото кассы","is_required":false,"requires_photo":true}],
                "runs":[{"id":"r1","template_title":"Открытие","status":"skipped","failed_count":0,"answered_count":0,"item_count":2,"responses":{"skip_reason":"проверка"}}]}}""",
            ),
        )
        val a = d.articles.single()
        assertEquals("критично", a.severityLabel)
        assertFalse(a.isPublished)
        assertEquals(3, a.version)
        assertEquals(5000.0, a.fine!!, 0.001)
        val t = d.templates.single()
        assertEquals(2, t.items.size)
        assertTrue(t.items[1].photo)
        assertEquals("открытие", t.scopeLabel)
        assertEquals("прощён", d.runs.single().statusLabel)
        assertEquals("проверка", d.runs.single().skipReason)
    }

    @Test
    fun `подтверждения считаются по текущей версии`() {
        val list = parseConfirmations(
            obj(
                """{"data":{
                "articles":[{"id":"a1","title":"Касса","version":2},{"id":"a2","title":"Чистота","version":1}],
                "staff":[{"id":"s1","full_name":"Аня"},{"id":"s2","full_name":"Борис"}],
                "confirmations":[
                  {"article_id":"a1","article_version":2,"staff_id":"s1"},
                  {"article_id":"a1","article_version":1,"staff_id":"s2"},
                  {"article_id":"a2","article_version":1,"staff_id":"s1"},
                  {"article_id":"a2","article_version":1,"staff_id":"s2"}]}}""",
            ),
        )
        // Больше всего должников — сверху.
        assertEquals(listOf("a1", "a2"), list.map { it.articleId })
        assertEquals(1, list[0].confirmed)
        assertEquals(listOf("Борис"), list[0].pending)
        assertEquals(2, list[0].total)
        assertTrue(list[1].pending.isEmpty())
    }

    @Test
    fun `вопросы операторов группируются`() {
        val q = QuestionsData.parse(
            obj("""{"data":{"available":true,"total":4,"groups":[{"article_id":"a","title":"Касса","count":3,"operators":2,"samples":[{"question":"Что это?"},{"question":"Как?"}]}]}}"""),
        )
        assertEquals(4, q.total)
        assertEquals(listOf("Что это?", "Как?"), q.groups.single().samples)
    }

    @Test
    fun `тела запросов регламентов`() {
        val pub = setPublishedBody("a", true)
        assertEquals("setArticlePublished", pub["action"]!!.jsonPrimitive.content)
        assertEquals("a", pub["payload"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("deleteArticle", deleteArticleBody("a")["action"]!!.jsonPrimitive.content)
        val start = checklistRunBody("t", "c", null)
        assertEquals("startChecklistRun", start["action"]!!.jsonPrimitive.content)
        assertNull(start["payload"]!!.jsonObject["reason"])
        val skip = checklistRunBody("t", "c", " нет света ")
        assertEquals("skipChecklistRun", skip["action"]!!.jsonPrimitive.content)
        assertEquals("нет света", skip["payload"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
        assertNotNull(skipReasonProblem("ab"))
        assertNull(skipReasonProblem("авария"))
    }

    // ── Модерация ────────────────────────────────────────────────────────────

    @Test
    fun `флаги модерации читаются`() {
        val d = ModerationData.parse(
            obj(
                """{"flags":[{"id":"f1","source_table":"team_chat","author_name":"Аня","message_text":"текст","severity":8,
                "categories":["cash_skim","data_leak"],"ai_summary":"Похоже на сговор","status":"pending"},
                {"id":"f2","source_table":"direct_messages","severity":3,"categories":[],"status":"confirmed","reviewer_note":"ок"}],"pendingCount":5}""",
            ),
        )
        assertEquals(5, d.pending)
        assertEquals("Командный чат", d.flags[0].sourceLabel)
        assertEquals("Личное сообщение", d.flags[1].sourceLabel)
        assertEquals("Сговор / кража", flagCategoryLabel("cash_skim"))
        assertEquals("Другое", flagCategoryLabel("что-то"))
        assertEquals("Сотрудник", d.flags[1].author)
        assertEquals("confirmed", moderationBody("f1", true)["status"]!!.jsonPrimitive.content)
        assertEquals("dismissed", moderationBody("f1", false)["status"]!!.jsonPrimitive.content)
    }

    // ── Станции ──────────────────────────────────────────────────────────────

    private val now = Instant.parse("2026-10-28T12:00:00Z")

    private val boardJson = """
        {"data":{"zones":[{"id":"z1","name":"PS5","is_active":true,"extension_hourly_price":1500},{"id":"z2","name":"VIP"}],
        "stations":[
          {"id":"s1","zone_id":"z1","name":"PS-1","order_index":2,"is_active":true,"last_heartbeat_at":"2026-10-28T11:55:00Z","active_session_id":"x","active_session_ends_at":"2026-10-28T12:40:00Z"},
          {"id":"s2","zone_id":"z1","name":"PS-0","order_index":1,"is_active":true,"last_heartbeat_at":"2026-10-28T11:00:00Z","active_session_id":"y","active_session_ends_at":"2026-10-28T11:50:00Z"},
          {"id":"s3","zone_id":"z2","name":"VIP-1","is_active":true},
          {"id":"s4","zone_id":null,"name":"Без зоны","is_active":false}],
        "tariffs":[{"id":"t2","zone_id":"z1","name":"3 часа","duration_minutes":180,"price":4000,"is_active":true},
                   {"id":"t1","zone_id":"z1","name":"Час","duration_minutes":60,"price":1500,"is_active":true},
                   {"id":"t0","zone_id":"z1","name":"Старый","duration_minutes":30,"price":500,"is_active":false},
                   {"id":"t3","zone_id":"z1","name":"Ночь","duration_minutes":480,"price":6000,"is_active":true,"window_start_time":"22:00:00","window_end_time":"08:00:00"}],
        "gamesCatalog":[{"id":"g1","is_active":true}],
        "stationGames":[{"station_id":"s1","is_active":true}]}}
    """.trimIndent()

    @Test
    fun `зал — занятость, тревоги и тарифы`() {
        val b = ArenaBoard.parse(obj(boardJson))
        assertEquals(3, b.working.size)
        assertEquals(2, b.busy.size)
        assertEquals(2.0 / 3.0, b.load!!, 0.0001)
        assertEquals(listOf("PS-0"), b.overdue(now).map { it.name })
        assertEquals(listOf("PS-0"), b.silent(now).map { it.name })
        assertEquals(listOf("PS-0", "VIP-1"), b.withoutGames.map { it.name })
        val ps = b.stations.first { it.id == "s1" }
        assertEquals(listOf("Час", "3 часа", "Ночь"), b.tariffsFor(ps).map { it.name })
        assertEquals("22:00–08:00", b.tariffs.first { it.id == "t3" }.windowLabel)
        assertEquals("3 ч", b.tariffs.first { it.id == "t2" }.durationLabel)
    }

    @Test
    fun `группы зала сортируют станции и держат станции без зоны`() {
        val b = ArenaBoard.parse(obj(boardJson))
        assertEquals(listOf("PS5", "VIP", null), b.groups.map { it.first?.name })
        assertEquals(listOf("PS-0", "PS-1"), b.groups[0].second.map { it.name })
        assertEquals(listOf("Без зоны"), b.groups[2].second.map { it.name })
    }

    @Test
    fun `без каталога игр предупреждать о пустых станциях нечего`() {
        val b = ArenaBoard.parse(obj("""{"data":{"stations":[{"id":"s","name":"A","is_active":true}],"gamesCatalog":[]}}"""))
        assertTrue(b.withoutGames.isEmpty())
    }

    @Test
    fun `статус станции`() {
        val b = ArenaBoard.parse(obj(boardJson))
        fun status(id: String) = stationStatus(b.stations.first { it.id == id }, now)
        assertEquals("осталось 40 мин" to false, status("s1"))
        assertEquals("переигрывает 10 мин" to true, status("s2"))
        assertEquals("свободна" to false, status("s3"))
        assertEquals("выключена" to false, status("s4"))
        assertEquals("1 ч 35 мин", minutesText(95))
        assertEquals("2 ч", minutesText(120))
        assertEquals("40 мин", minutesText(40))
    }

    @Test
    fun `выручка зала — только завершённые сессии`() {
        val sessions = ArenaRevenue.parse(
            obj(
                """{"data":{"sessions":[
                {"station_id":"s1","amount":1500,"cash_amount":1500,"kaspi_amount":0,"status":"completed","station":{"name":"PS-1"},"tariff":{"duration_minutes":60}},
                {"station_id":"s1","amount":3000,"cash_amount":1000,"kaspi_amount":2000,"status":"completed","station":[{"name":"PS-1"}],"tariff":[{"duration_minutes":120}]},
                {"station_id":"s2","amount":900,"status":"active","station":{"name":"PS-0"},"tariff":{"duration_minutes":30}}]}}""",
            ),
        )
        val r = ArenaRevenue.of(sessions)
        assertEquals(4500.0, r.amount, 0.001)
        assertEquals(2, r.count)
        assertEquals(2250.0, r.averageCheck, 0.001)
        assertEquals(2500.0, r.cash, 0.001)
        assertEquals(2000.0, r.kaspi, 0.001)
        assertEquals(180, r.minutes)
        assertEquals("PS-1", r.byStation.single().first)
        assertEquals(180.0 / (2 * 1 * 24 * 60), r.occupancy(2, 1)!!, 0.0001)
        assertNull(r.occupancy(0, 7))
    }

    @Test
    fun `периоды арены задают моменты времени`() {
        val zone = ZoneId.of("Asia/Almaty")
        val (from, to) = ArenaPeriod.Week.bounds(now, zone)
        // Семь суток назад, 00:00 по Алматы (UTC+5) → 18:00 UTC накануне.
        assertEquals("2026-10-21T19:00:00Z", from)
        assertEquals("2026-10-28T12:00:00Z", to)
        assertEquals("2026-10-27T19:00:00Z", ArenaPeriod.Today.bounds(now, zone).first)
    }

    @Test
    fun `запросы зала`() {
        val start = startSessionBody("s", "t", "p", "")
        assertEquals("adminStartSession", start["action"]!!.jsonPrimitive.content)
        assertNull(start["companyId"])
        assertEquals("c", startSessionBody("s", "t", "p", "c")["companyId"]!!.jsonPrimitive.content)
        assertEquals("adminEndSession", endSessionBody("s")["action"]!!.jsonPrimitive.content)
        assertEquals("getAnalytics", analyticsBody("p", null, ArenaPeriod.Month)["action"]!!.jsonPrimitive.content)
        assertEquals("Станция уже занята — обновите зал.", friendlyArenaError("station-already-occupied"))
        assertEquals("что-то", friendlyArenaError("что-то"))
    }

    // ── Настройки ────────────────────────────────────────────────────────────

    @Test
    fun `закрыть и открыть точку`() {
        assertTrue(canCloseOpenPoints("owner", false))
        assertTrue(canCloseOpenPoints(null, true))
        assertFalse(canCloseOpenPoints("manager", false))
        val close = closePointBody("c", "  ремонт ")
        assertEquals("close", close["action"]!!.jsonPrimitive.content)
        assertEquals("ремонт", close["reason"]!!.jsonPrimitive.content)
        assertNull(closePointBody("c", " ")["reason"])
        assertEquals("reopen", reopenPointBody("c")["action"]!!.jsonPrimitive.content)
        assertEquals(300, closePointBody("c", "я".repeat(500))["reason"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun `зоны выручки`() {
        val z = ZonesData.parse(
            obj("""{"ok":true,"zones":[{"key":"ps5","label":"PS5","active":true},{"key":"vr","label":"VR","active":false}],"stats":{"ps5":{"total30":120000,"lastDate":"2026-10-27"}}}"""),
        )
        assertEquals(2, z.zones.size)
        assertFalse(z.zones[1].active)
        assertEquals(120000.0, z.stats["ps5"]!!.total30, 0.001)
        assertNull(z.stats["vr"])
        assertNull(zonesProblem(z.zones))
        assertEquals("У зоны должно быть название", zonesProblem(z.zones + RevenueZone("", "  ", true)))
        assertEquals("Зона «VR» указана дважды", zonesProblem(z.zones + RevenueZone("", "VR", true)))
        assertNotNull(zonesProblem(List(MaxRevenueZones + 1) { RevenueZone("k$it", "З$it", true) }))
        val body = saveZonesBody("c", z.zones + RevenueZone("", " Бильярд ", true))
        assertEquals("saveZones", body["action"]!!.jsonPrimitive.content)
        val sent = body["zones"] as JsonArray
        assertEquals("", sent.last().jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals("Бильярд", sent.last().jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("zones", zonesRequestBody("c")["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun `праздники — по умолчанию прежний список`() {
        val s = HolidaySettings.parse(obj("""{"ok":true,"settings":{}}"""))
        assertTrue(s.enabled)
        assertTrue(s.isOn("nauryz"))
        assertEquals(15, KzHolidays.size)
        assertEquals("21 марта", holidayDate(3, 21))
        assertTrue(KzHolidays.all { "{name}" in it.greeting })
    }

    @Test
    fun `праздники читаются и пишутся как на сайте`() {
        val s = HolidaySettings.parse(
            obj(
                """{"settings":{"holidays":{"enabled":false,"disabled":["nauryz","нет-такого"],"greetings":{"victory_day":"Своё, {name}","чужой":"x"},
                "custom":[{"id":"c1","month":6,"day":15,"name":"День клуба","greeting":""}]}}}""",
            ),
        )
        assertFalse(s.enabled)
        assertEquals(setOf("nauryz"), s.disabled)
        assertEquals(mapOf("victory_day" to "Своё, {name}"), s.greetings)
        assertEquals("День клуба", s.custom.single().name)

        val next = s.toggled("nauryz").toggled("new_year").withGreeting("victory_day", null).withGreeting("nauryz", " Привет ")
        assertEquals(setOf("new_year"), next.disabled)
        assertEquals(mapOf("nauryz" to "Привет"), next.greetings)
        val body = next.withCustom("2026-11-05", " Тест ", "c9").body()["holidays"]!!.jsonObject
        assertEquals("false", body["enabled"]!!.jsonPrimitive.content)
        assertEquals(listOf("new_year"), body["disabled"]!!.jsonArray.map { it.jsonPrimitive.content })
        val custom = body["custom"]!!.jsonArray
        assertEquals(2, custom.size)
        assertEquals(11, custom.last().jsonObject["month"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, custom.last().jsonObject["day"]!!.jsonPrimitive.content.toInt())
        assertEquals("Тест", custom.last().jsonObject["name"]!!.jsonPrimitive.content)
        // Пустая дата или название — не добавляем.
        assertEquals(s, s.withCustom("", "Тест", "x"))
        assertEquals(s, s.withCustom("2026-11-05", " ", "x"))
        assertTrue(s.withoutCustom("c1").custom.isEmpty())
    }
}
