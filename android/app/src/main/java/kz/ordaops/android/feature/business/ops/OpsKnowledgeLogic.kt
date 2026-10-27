package kz.ordaops.android.feature.business.ops

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Правка регламентов, настройка базы знаний и импорт из Word — чистая логика
// (без Compose и Android), покрыта юнит-тестами. Маршруты и тела — как на сайте:
// `/api/admin/knowledge` (upsertArticle, applyImport), `/api/admin/knowledge/import`,
// `/api/admin/knowledge/industry`.

// ── Текст статьи: HTML сайта ↔ простой текст телефона ───────────────────────

/**
 * Как править текст статьи на телефоне. На сайте — визуальный редактор над HTML;
 * на телефоне простой HTML сводится к тексту с «- » для списков и обратно без потерь,
 * а всё сложное (таблицы, заголовки, ссылки) правится как есть, кодом.
 */
enum class ArticleTextMode {
    /** Без тегов вовсе (черновики ИИ, markdown): сохраняется как набрано. */
    Plain,

    /** `p`, `br`, `ul`, `li`, `strong`: превращается в текст и обратно. */
    SimpleHtml,

    /** Таблицы, заголовки, атрибуты: правка HTML целиком, чтобы ничего не потерять. */
    RawHtml,
}

private val TAG = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>")
private val SIMPLE_TAGS = setOf("p", "br", "ul", "li", "strong", "b")

fun detectTextMode(content: String): ArticleTextMode {
    val tags = TAG.findAll(content).toList()
    if (tags.isEmpty()) return ArticleTextMode.Plain
    for (m in tags) {
        val name = m.groupValues[2].lowercase()
        val rest = m.groupValues[3].trim().trimEnd('/').trim()
        if (name !in SIMPLE_TAGS || rest.isNotEmpty()) return ArticleTextMode.RawHtml
    }
    return ArticleTextMode.SimpleHtml
}

private fun decodeEntities(s: String): String = s
    .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

private fun escapeText(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Простой HTML → текст. Для [ArticleTextMode.SimpleHtml]; другое содержимое приводится как получится. */
fun htmlToText(html: String): String {
    var s = html.replace("\r\n", "\n")
    s = s.replace(Regex("<\\s*(strong|b)\\s*>", RegexOption.IGNORE_CASE), "**")
        .replace(Regex("<\\s*/\\s*(strong|b)\\s*>", RegexOption.IGNORE_CASE), "**")
    s = s.replace(Regex("<\\s*li\\s*>", RegexOption.IGNORE_CASE), "\n- ")
        .replace(Regex("<\\s*/\\s*li\\s*>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<\\s*/?\\s*ul\\s*>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("<\\s*br\\s*/?\\s*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<\\s*/\\s*p\\s*>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("<\\s*p\\s*>", RegexOption.IGNORE_CASE), "")
    s = s.replace(Regex("<[^>]+>"), "")
    s = decodeEntities(s)
    // Внутри списка «\n\n» между пунктами не нужен.
    s = s.replace(Regex("\n{3,}"), "\n\n")
    return s.trim()
}

private fun inline(s: String): String =
    escapeText(s).replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")

/** Текст → простой HTML: пустая строка делит абзацы, «- » в начале строки — пункт списка. */
fun textToHtml(text: String): String {
    val out = StringBuilder()
    val blocks = text.replace("\r\n", "\n").trim().split(Regex("\n{2,}"))
    for (block in blocks) {
        if (block.isBlank()) continue
        val lines = block.split("\n")
        var i = 0
        val para = ArrayList<String>()
        fun flushPara() {
            if (para.isNotEmpty()) {
                out.append("<p>").append(para.joinToString("<br>") { inline(it) }).append("</p>")
                para.clear()
            }
        }
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("- ")) {
                flushPara()
                out.append("<ul>")
                while (i < lines.size && lines[i].startsWith("- ")) {
                    out.append("<li>").append(inline(lines[i].removePrefix("- "))).append("</li>")
                    i++
                }
                out.append("</ul>")
            } else {
                para += line
                i++
            }
        }
        flushPara()
    }
    return out.toString()
}

/** Что показать в поле при правке и во что превратить при сохранении. */
fun contentForEditor(content: String, mode: ArticleTextMode): String =
    if (mode == ArticleTextMode.SimpleHtml) htmlToText(content) else content

fun contentForSave(edited: String, mode: ArticleTextMode): String =
    if (mode == ArticleTextMode.SimpleHtml) textToHtml(edited) else edited

// ── Статья: черновик формы и тело запроса ────────────────────────────────────

val ARTICLE_SEVERITIES = listOf("info" to "Информация", "normal" to "Обычно", "warning" to "Важно", "critical" to "Критично")

val ARTICLE_AUDIENCE = listOf(
    "operator" to "Оператор", "cashier" to "Кассир", "manager" to "Менеджер", "owner" to "Владелец",
    "client" to "Клиент (киоск)", "public" to "Публично", "kiosk" to "Киоск",
)

/** Поля формы статьи; пустые строки — «не задано», как в редакторе сайта. */
data class ArticleDraft(
    val id: String? = null,
    val companyId: String = "",
    val categoryId: String = "",
    val title: String = "",
    val summary: String = "",
    val content: String = "",
    val tags: String = "",
    val audience: Set<String> = setOf("operator", "cashier", "manager"),
    val severity: String = "info",
    val fine: String = "",
    val bonus: String = "",
    val sortOrder: Int = 100,
    val isPublished: Boolean = true,
    val requiresConfirmation: Boolean = false,
) {
    fun problem(): String? = if (title.isBlank()) "Название обязательно" else null

    /** `{ action: upsertArticle, payload: { … } }` — поля как у `submitArticleDialog` на сайте. */
    fun body(): JsonObject = buildJsonObject {
        put("action", "upsertArticle")
        put("payload", buildJsonObject {
            id?.let { put("id", it) }
            put("company_id", companyId.ifBlank { null }?.let(::JsonPrimitive) ?: JsonNull)
            put("category_id", categoryId.ifBlank { null }?.let(::JsonPrimitive) ?: JsonNull)
            put("title", title.trim())
            put("summary", summary)
            put("content", content)
            put("tags", buildJsonArray { splitList(tags).forEach { add(JsonPrimitive(it)) } })
            put("audience", buildJsonArray { ARTICLE_AUDIENCE.map { it.first }.filter { it in audience }.forEach { add(JsonPrimitive(it)) } })
            put("severity", severity)
            put("related_fine_amount", moneyOrNull(fine)?.let(::JsonPrimitive) ?: JsonNull)
            put("related_bonus_amount", moneyOrNull(bonus)?.let(::JsonPrimitive) ?: JsonNull)
            put("sort_order", sortOrder)
            put("is_published", isPublished)
            put("requires_confirmation", requiresConfirmation)
        })
    }

    companion object {
        /** Из полной статьи (`GET /api/admin/knowledge?article=<id>`). */
        fun fromJson(o: JsonObject): ArticleDraft = ArticleDraft(
            id = o.str("id"),
            companyId = o.str("company_id").orEmpty(),
            categoryId = o.str("category_id").orEmpty(),
            title = o.str("title").orEmpty(),
            summary = o.str("summary").orEmpty(),
            content = o.str("content").orEmpty(),
            tags = (o["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.joinToString(", ").orEmpty(),
            audience = (o["audience"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet()
                ?: setOf("operator", "cashier", "manager"),
            severity = o.str("severity") ?: "info",
            fine = o.num("related_fine_amount")?.let(::plainNumber).orEmpty(),
            bonus = o.num("related_bonus_amount")?.let(::plainNumber).orEmpty(),
            sortOrder = o.int("sort_order") ?: 100,
            isPublished = o.bool("is_published") ?: true,
            requiresConfirmation = o.bool("requires_confirmation") ?: false,
        )
    }
}

fun splitList(text: String): List<String> = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

/** Сумма штрафа/бонуса: пусто или не число — `null`, без знака минус. */
fun moneyOrNull(text: String): Double? {
    val v = text.trim().replace(',', '.').replace(" ", "").toDoubleOrNull() ?: return null
    return if (v.isFinite() && v >= 0) v else null
}

private fun plainNumber(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

// ── Настройка базы знаний (`/regulations/setup`) ─────────────────────────────

data class SetupCompany(val id: String, val name: String, val industry: String?)
data class IndustryOption(val code: String, val label: String, val description: String)
data class SetupArticle(val id: String, val title: String, val isPublished: Boolean, val summary: String?, val content: String?)
data class SetupTopic(
    val key: String,
    val label: String,
    val hint: String,
    val critical: Boolean,
    val hasFactSource: Boolean,
    val factsAvailable: Boolean,
    val published: Int,
    val drafts: Int,
    val articles: List<SetupArticle>,
)
data class InterviewQuestion(val key: String, val question: String, val hint: String?)
data class FactsSummary(val catalog: Int, val salaryRules: Boolean, val checklists: Boolean)

data class SetupData(
    val companies: List<SetupCompany>,
    val industries: List<IndustryOption>,
    val company: SetupCompany?,
    val topics: List<SetupTopic>,
    val interview: List<InterviewQuestion>,
    val facts: FactsSummary?,
) {
    /** Закрыто тем / всего / процент: тема закрыта, если есть опубликованная статья. */
    val coverage: Triple<Int, Int, Int>
        get() {
            val total = topics.size
            val covered = topics.count { it.published > 0 }
            return Triple(covered, total, if (total > 0) Math.round(covered * 100.0 / total).toInt() else 0)
        }

    companion object {
        fun parse(root: JsonObject): SetupData {
            val d = root.obj("data") ?: root
            fun company(o: JsonObject) = SetupCompany(o.str("id") ?: "", o.str("name") ?: "Точка", o.str("industry"))
            return SetupData(
                companies = d.arr("companies").map(::company).filter { it.id.isNotEmpty() },
                industries = d.arr("industries").map { IndustryOption(it.str("code") ?: "", it.str("label") ?: "", it.str("description") ?: "") }
                    .filter { it.code.isNotEmpty() },
                company = d.obj("company")?.let(::company),
                topics = d.arr("topics").map { t ->
                    SetupTopic(
                        key = t.str("key") ?: "", label = t.str("label") ?: "Тема", hint = t.str("hint") ?: "",
                        critical = t.str("severity") == "critical", hasFactSource = !t.str("factSource").isNullOrEmpty(),
                        factsAvailable = t.bool("factsAvailable") ?: false,
                        published = t.int("published") ?: 0, drafts = t.int("drafts") ?: 0,
                        articles = t.arr("articles").map { a ->
                            SetupArticle(a.str("id") ?: "", a.str("title") ?: "Статья", a.bool("is_published") ?: false, a.str("summary"), a.str("content"))
                        },
                    )
                }.filter { it.key.isNotEmpty() },
                interview = d.arr("interview").map { InterviewQuestion(it.str("key") ?: "", it.str("question") ?: "", it.str("hint")) }
                    .filter { it.key.isNotEmpty() },
                facts = d.obj("factsSummary")?.let { FactsSummary(it.int("catalog") ?: 0, it.bool("salary_rules") ?: false, it.bool("checklists") ?: false) },
            )
        }
    }
}

/** Ответ засчитывается, если в нём хотя бы 5 знаков (как на сайте). */
fun answeredCount(questions: List<InterviewQuestion>, answers: Map<String, String>): Int =
    questions.count { (answers[it.key] ?: "").trim().length >= 5 }

/** Тело `POST /api/admin/knowledge/industry`. */
fun setupBody(action: String, companyId: String, extra: JsonObject.() -> JsonObject = { this }): JsonObject {
    val base = buildJsonObject { put("action", action); put("company_id", companyId) }
    return base.extra()
}

private fun JsonObject.plus(key: String, value: kotlinx.serialization.json.JsonElement): JsonObject = JsonObject(this + (key to value))

fun setIndustryBody(companyId: String, industry: String) = setupBody("set_industry", companyId) { plus("industry", JsonPrimitive(industry)) }
fun generateFactsBody(companyId: String) = setupBody("generate_facts", companyId)
fun publishDraftBody(companyId: String, articleId: String) = setupBody("publish_article", companyId) { plus("article_id", JsonPrimitive(articleId)) }

/** Ответы на интервью: пустые не отправляем. */
fun generateInterviewBody(companyId: String, answers: Map<String, String>) = setupBody("generate_interview", companyId) {
    plus("answers", buildJsonObject { answers.filterValues { it.isNotBlank() }.forEach { (k, v) -> put(k, v.trim()) } })
}

/** Сброс: `drafts` — черновики точки, `point` — все регламенты точки, `organization` — вся база. */
fun resetKnowledgeBody(companyId: String, scope: String) = setupBody("reset_knowledge", companyId) { plus("scope", JsonPrimitive(scope)) }

/** «Создано черновиков: 3» + названия — то, что сайт показывает в alert. */
fun generationSummary(root: JsonObject): String {
    val d = root.obj("data") ?: root
    val created = d.int("created") ?: 0
    val titles = (d["titles"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
    return "Создано черновиков: $created" + if (titles.isNotEmpty()) "\n" + titles.joinToString("\n") else ""
}

// ── Импорт документа ─────────────────────────────────────────────────────────

const val IMPORT_MAX_BYTES = 8 * 1024 * 1024

data class ImportItemRow(val title: String, val answerType: String, val isRequired: Boolean, val requiresPhoto: Boolean)

data class ImportArticleRow(
    val key: String,
    val title: String,
    val category: String,
    val summary: String,
    val content: String,
    val severity: String,
    val requiresConfirmation: Boolean,
    val tags: List<String>,
    val point: String?,
    val companyId: String?,
    val exists: Boolean,
    val existingVersion: Int?,
)

data class ImportChecklistRow(
    val key: String,
    val title: String,
    val description: String,
    val scheduleType: String,
    val recurrenceMinutes: Int?,
    val blocksShift: Boolean,
    val point: String?,
    val companyId: String?,
    val items: List<ImportItemRow>,
)

data class ImportUpload(val fileName: String, val chars: Int, val chunks: List<String>, val companies: List<SetupCompany>)
data class ImportChunk(val articles: List<ImportArticleRow>, val checklists: List<ImportChecklistRow>)

fun parseImportUpload(root: JsonObject, fallbackName: String): ImportUpload {
    val d = root.obj("data") ?: root
    return ImportUpload(
        fileName = d.str("file_name") ?: fallbackName,
        chars = d.int("chars") ?: 0,
        chunks = (d["chunks"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
        companies = d.arr("companies").map { SetupCompany(it.str("id") ?: "", it.str("name") ?: "", null) }.filter { it.id.isNotEmpty() },
    )
}

fun parseImportChunk(root: JsonObject): ImportChunk {
    val d = root.obj("data") ?: root
    return ImportChunk(
        articles = d.arr("articles").map { a ->
            ImportArticleRow(
                key = a.str("key") ?: "", title = a.str("title") ?: "", category = a.str("category") ?: "Регламент",
                summary = a.str("summary").orEmpty(), content = a.str("content").orEmpty(), severity = a.str("severity") ?: "normal",
                requiresConfirmation = a.bool("requires_confirmation") ?: false,
                tags = (a["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                point = a.str("point"), companyId = a.str("company_id"),
                exists = a.bool("exists") ?: false, existingVersion = a.int("existing_version"),
            )
        }.filter { it.title.isNotBlank() },
        checklists = d.arr("checklists").map { c ->
            ImportChecklistRow(
                key = c.str("key") ?: "", title = c.str("title") ?: "", description = c.str("description").orEmpty(),
                scheduleType = c.str("schedule_type") ?: "opening", recurrenceMinutes = c.int("recurrence_minutes"),
                blocksShift = c.bool("blocks_shift") ?: false, point = c.str("point"), companyId = c.str("company_id"),
                items = c.arr("items").map {
                    ImportItemRow(it.str("title") ?: "", it.str("answer_type") ?: "boolean", it.bool("is_required") ?: true, it.bool("requires_photo") ?: false)
                },
            )
        }.filter { it.title.isNotBlank() },
    )
}

/** Один и тот же раздел может попасть в два куска документа — берём первый, как сайт. */
fun mergeImport(acc: ImportChunk, next: ImportChunk): ImportChunk {
    val articleTitles = acc.articles.map { it.title.lowercase() }.toMutableSet()
    val checklistTitles = acc.checklists.map { it.title.lowercase() }.toMutableSet()
    return ImportChunk(
        acc.articles + next.articles.filter { articleTitles.add(it.title.lowercase()) },
        acc.checklists + next.checklists.filter { checklistTitles.add(it.title.lowercase()) },
    )
}

/** Тело `applyImport`: только отмеченное, точка — выбранная владельцем (пусто — все точки). */
fun applyImportBody(
    preview: ImportChunk,
    selected: Set<String>,
    companyByKey: Map<String, String>,
    updateExisting: Boolean,
): JsonObject {
    fun company(key: String): kotlinx.serialization.json.JsonElement = companyByKey[key]?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull
    return buildJsonObject {
        put("action", "applyImport")
        put("payload", buildJsonObject {
            put("articles", buildJsonArray {
                preview.articles.filter { it.key in selected }.forEach { a ->
                    add(buildJsonObject {
                        put("title", a.title); put("category", a.category); put("summary", a.summary); put("content", a.content)
                        put("severity", a.severity); put("requires_confirmation", a.requiresConfirmation)
                        put("tags", buildJsonArray { a.tags.forEach { add(JsonPrimitive(it)) } })
                        put("company_id", company(a.key))
                    })
                }
            })
            put("checklists", buildJsonArray {
                preview.checklists.filter { it.key in selected }.forEach { c ->
                    add(buildJsonObject {
                        put("title", c.title); put("description", c.description); put("schedule_type", c.scheduleType)
                        put("recurrence_minutes", c.recurrenceMinutes?.let(::JsonPrimitive) ?: JsonNull)
                        put("blocks_shift", c.blocksShift)
                        put("company_id", company(c.key))
                        put("items", buildJsonArray {
                            c.items.forEach { i ->
                                add(buildJsonObject {
                                    put("title", i.title); put("answer_type", i.answerType)
                                    put("is_required", i.isRequired); put("requires_photo", i.requiresPhoto)
                                })
                            }
                        })
                    })
                }
            })
            put("update_existing", updateExisting)
        })
    }
}

/** «Импорт завершён — создано статей: 3, чек-листов: 1» из `result` ответа applyImport. */
fun importResultSummary(root: JsonObject): String {
    val r = root.obj("result") ?: JsonObject(emptyMap())
    val parts = listOfNotNull(
        "создано статей: ${r.int("createdArticles") ?: 0}",
        r.int("updatedArticles")?.takeIf { it > 0 }?.let { "обновлено: $it" },
        "чек-листов: ${r.int("createdChecklists") ?: 0}",
        r.int("skippedArticles")?.takeIf { it > 0 }?.let { "пропущено дублей: $it" },
    )
    return "Импорт завершён — ${parts.joinToString(", ")}"
}

/** Разрешённые расширения документа. */
fun isImportableName(name: String): Boolean = name.lowercase().let { it.endsWith(".docx") || it.endsWith(".md") || it.endsWith(".txt") }
