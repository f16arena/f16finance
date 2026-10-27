package kz.ordaops.android.feature.business.ops

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Правка регламентов, настройка базы знаний, импорт документа. */
class KnowledgeEditLogicTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `режим текста определяется по тегам`() {
        assertEquals(ArticleTextMode.Plain, detectTextMode("Просто текст\n- пункт"))
        assertEquals(ArticleTextMode.SimpleHtml, detectTextMode("<p>Привет</p><ul><li>раз</li></ul>"))
        assertEquals(ArticleTextMode.RawHtml, detectTextMode("<table><tr><td>1</td></tr></table>"))
        assertEquals(ArticleTextMode.RawHtml, detectTextMode("<h2>Заголовок</h2><p>x</p>"))
        assertEquals(ArticleTextMode.RawHtml, detectTextMode("<p style=\"color:red\">x</p>"))
        // сравнение «a < b» — не тег
        assertEquals(ArticleTextMode.Plain, detectTextMode("если a < b и b > c"))
    }

    @Test
    fun `простой HTML превращается в текст и обратно без потерь`() {
        val html = "<p>Первый абзац</p><p>Строка<br>вторая строка</p><ul><li>раз</li><li>два <strong>важно</strong></li></ul><p>Итог</p>"
        val text = htmlToText(html)
        assertEquals("Первый абзац\n\nСтрока\nвторая строка\n\n- раз\n- два **важно**\n\nИтог", text)
        assertEquals(html, textToHtml(text))
    }

    @Test
    fun `спецсимволы экранируются и не ломают разметку`() {
        assertEquals("<p>a &lt; b &amp; c</p>", textToHtml("a < b & c"))
        assertEquals("a < b & c", htmlToText("<p>a &lt; b &amp; c</p>"))
    }

    @Test
    fun `сложный HTML и обычный текст сохраняются как есть`() {
        val table = "<table><tr><td>Тариф</td></tr></table>"
        assertEquals(table, contentForSave(contentForEditor(table, ArticleTextMode.RawHtml), ArticleTextMode.RawHtml))
        val plain = "# Заголовок\nтекст"
        assertEquals(plain, contentForSave(contentForEditor(plain, ArticleTextMode.Plain), ArticleTextMode.Plain))
    }

    @Test
    fun `тело сохранения статьи — поля как на сайте`() {
        val body = ArticleDraft(
            id = "a1", companyId = "", categoryId = "c1", title = "  Касса  ", summary = "кратко", content = "<p>x</p>",
            tags = "касса, деньги , ", audience = setOf("manager", "operator"), severity = "critical",
            fine = "5 000", bonus = "", sortOrder = 100, isPublished = false, requiresConfirmation = true,
        ).body()
        assertEquals("upsertArticle", body["action"]!!.jsonPrimitive.content)
        val p = body["payload"]!!.jsonObject
        assertEquals("a1", p["id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, p["company_id"])
        assertEquals("c1", p["category_id"]!!.jsonPrimitive.content)
        assertEquals("Касса", p["title"]!!.jsonPrimitive.content)
        assertEquals(listOf("касса", "деньги"), p["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        // порядок аудитории — как в каталоге, а не как набрано
        assertEquals(listOf("operator", "manager"), p["audience"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(5000.0, p["related_fine_amount"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(JsonNull, p["related_bonus_amount"])
        assertEquals("false", p["is_published"]!!.jsonPrimitive.content)
        assertEquals("true", p["requires_confirmation"]!!.jsonPrimitive.content)
    }

    @Test
    fun `новая статья без id и название обязательно`() {
        val draft = ArticleDraft(title = " ")
        assertEquals("Название обязательно", draft.problem())
        assertFalse(draft.copy(title = "Ок").body()["payload"]!!.jsonObject.containsKey("id"))
        assertNull(draft.copy(title = "Ок").problem())
    }

    @Test
    fun `статья с сервера читается в форму`() {
        val d = ArticleDraft.fromJson(
            obj("""{"id":"a","company_id":"k","category_id":null,"title":"Т","summary":null,"content":"<p>x</p>","tags":["а","б"],"audience":["owner"],"severity":"warning","related_fine_amount":1500,"related_bonus_amount":null,"sort_order":30,"is_published":false,"requires_confirmation":true}"""),
        )
        assertEquals("k", d.companyId)
        assertEquals("", d.categoryId)
        assertEquals("а, б", d.tags)
        assertEquals(setOf("owner"), d.audience)
        assertEquals("1500", d.fine)
        assertEquals("", d.bonus)
        assertEquals(30, d.sortOrder)
        assertFalse(d.isPublished)
        assertTrue(d.requiresConfirmation)
    }

    @Test
    fun `суммы штрафа без минуса и мусора`() {
        assertEquals(1250.5, moneyOrNull("1 250,5")!!, 0.0)
        assertNull(moneyOrNull("-5"))
        assertNull(moneyOrNull("abc"))
        assertNull(moneyOrNull(""))
    }

    private val setupJson = """
        {"ok":true,"data":{
          "companies":[{"id":"c1","name":"Arena","code":"arena","industry":"club"},{"id":"c2","name":"Extra","industry":null}],
          "industries":[{"code":"club","label":"Клуб","description":"ПК"}],
          "company":{"id":"c1","name":"Arena","industry":"club"},
          "topics":[
            {"key":"t1","label":"Касса","hint":"как закрыть","severity":"critical","factSource":"salary_rules","factsAvailable":true,"published":1,"drafts":0,"articles":[{"id":"a1","title":"Касса","is_published":true,"summary":null,"content":null}]},
            {"key":"t2","label":"Общение","hint":"","published":0,"drafts":1,"articles":[{"id":"a2","title":"Черновик","is_published":false,"summary":"s","content":"текст"}]}
          ],
          "interview":[{"key":"t2","question":"Как общаетесь?","hint":"подсказка","topics":["t2"]}],
          "factsSummary":{"catalog":12,"salary_rules":true,"checklists":false}}}
    """.trimIndent()

    @Test
    fun `настройка разбирается, покрытие считается`() {
        val d = SetupData.parse(obj(setupJson))
        assertEquals(2, d.companies.size)
        assertEquals("club", d.company!!.industry)
        assertEquals(1, d.coverage.first)
        assertEquals(2, d.coverage.second)
        assertEquals(50, d.coverage.third)
        assertTrue(d.topics[0].critical)
        assertTrue(d.topics[0].hasFactSource)
        assertEquals(1, d.topics[1].drafts)
        assertEquals("текст", d.topics[1].articles.single().content)
        assertEquals(FactsSummary(12, true, false), d.facts)
        assertEquals("Как общаетесь?", d.interview.single().question)
    }

    @Test
    fun `пустая настройка не падает и покрытие ноль`() {
        val d = SetupData.parse(obj("""{"ok":true,"data":{"companies":[],"industries":[]}}"""))
        assertEquals(0, d.coverage.third)
        assertNull(d.company)
    }

    @Test
    fun `ответ засчитан от пяти знаков`() {
        val qs = listOf(InterviewQuestion("a", "?", null), InterviewQuestion("b", "?", null))
        assertEquals(1, answeredCount(qs, mapOf("a" to "  коротк ", "b" to "ok")))
        assertEquals(0, answeredCount(qs, mapOf("a" to "1234")))
    }

    @Test
    fun `тела запросов настройки`() {
        val ind = setIndustryBody("c1", "club")
        assertEquals("set_industry", ind["action"]!!.jsonPrimitive.content)
        assertEquals("c1", ind["company_id"]!!.jsonPrimitive.content)
        assertEquals("club", ind["industry"]!!.jsonPrimitive.content)
        val iv = generateInterviewBody("c1", mapOf("t1" to " ответ ", "t2" to "  "))
        val answers = iv["answers"]!!.jsonObject
        assertEquals(setOf("t1"), answers.keys)
        assertEquals("ответ", answers["t1"]!!.jsonPrimitive.content)
        assertEquals("a9", publishDraftBody("c1", "a9")["article_id"]!!.jsonPrimitive.content)
        assertEquals("drafts", resetKnowledgeBody("c1", "drafts")["scope"]!!.jsonPrimitive.content)
        assertEquals("generate_facts", generateFactsBody("c1")["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun `итог генерации`() {
        assertEquals("Создано черновиков: 2\nА\nБ", generationSummary(obj("""{"ok":true,"data":{"created":2,"titles":["А","Б"]}}""")))
        assertEquals("Создано черновиков: 0", generationSummary(obj("""{"ok":true,"data":{"created":0,"titles":[]}}""")))
    }

    private val chunkJson = """
        {"ok":true,"data":{
          "articles":[{"key":"article-0-0","title":"Касса","category":"Деньги","summary":"s","content":"<p>x</p>","severity":"critical","requires_confirmation":true,"tags":["касса"],"point":"Arena","company_id":"c1","exists":true,"existing_version":2}],
          "checklists":[{"key":"checklist-0-0","title":"Открытие","description":"d","schedule_type":"opening","recurrence_minutes":null,"blocks_shift":true,"point":null,"company_id":null,
                         "items":[{"title":"Свет","answer_type":"boolean","is_required":true,"requires_photo":false}]}]}}
    """.trimIndent()

    @Test
    fun `кусок импорта разбирается`() {
        val chunk = parseImportChunk(obj(chunkJson))
        val a = chunk.articles.single()
        assertEquals("c1", a.companyId)
        assertTrue(a.exists)
        assertEquals(2, a.existingVersion)
        assertEquals("Открытие", chunk.checklists.single().title)
        assertTrue(chunk.checklists.single().blocksShift)
        assertNull(chunk.checklists.single().recurrenceMinutes)
    }

    @Test
    fun `загрузка файла отдаёт куски и точки`() {
        val up = parseImportUpload(obj("""{"ok":true,"data":{"file_name":"r.docx","chars":900,"chunks":["один","два"],"companies":[{"id":"c1","name":"Arena"}]}}"""), "x")
        assertEquals("r.docx", up.fileName)
        assertEquals(listOf("один", "два"), up.chunks)
        assertEquals("Arena", up.companies.single().name)
    }

    @Test
    fun `одинаковые разделы из двух кусков берутся один раз`() {
        val first = parseImportChunk(obj(chunkJson))
        val merged = mergeImport(first, first)
        assertEquals(1, merged.articles.size)
        assertEquals(1, merged.checklists.size)
    }

    @Test
    fun `применение импорта — только отмеченное, точка из выбора`() {
        val chunk = parseImportChunk(obj(chunkJson))
        val body = applyImportBody(chunk, setOf("article-0-0"), mapOf("article-0-0" to "c2", "checklist-0-0" to "c1"), true)
        assertEquals("applyImport", body["action"]!!.jsonPrimitive.content)
        val p = body["payload"]!!.jsonObject
        val articles = p["articles"] as JsonArray
        assertEquals(1, articles.size)
        assertEquals("c2", articles[0].jsonObject["company_id"]!!.jsonPrimitive.content)
        assertEquals(0, (p["checklists"] as JsonArray).size)
        assertEquals("true", p["update_existing"]!!.jsonPrimitive.content)
        // пустая точка — «все точки»
        val all = applyImportBody(chunk, setOf("checklist-0-0"), mapOf("checklist-0-0" to ""), false)["payload"]!!.jsonObject
        assertEquals(JsonNull, (all["checklists"] as JsonArray)[0].jsonObject["company_id"])
        assertEquals(1, ((all["checklists"] as JsonArray)[0].jsonObject["items"] as JsonArray).size)
    }

    @Test
    fun `итог импорта и допустимые файлы`() {
        assertEquals(
            "Импорт завершён — создано статей: 3, обновлено: 1, чек-листов: 2, пропущено дублей: 4",
            importResultSummary(obj("""{"ok":true,"result":{"createdArticles":3,"updatedArticles":1,"createdChecklists":2,"skippedArticles":4}}""")),
        )
        assertEquals("Импорт завершён — создано статей: 0, чек-листов: 0", importResultSummary(obj("""{"ok":true}""")))
        assertTrue(isImportableName("Регламент.DOCX"))
        assertTrue(isImportableName("a.md"))
        assertFalse(isImportableName("a.pdf"))
    }
}
