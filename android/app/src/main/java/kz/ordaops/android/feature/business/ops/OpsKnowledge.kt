package kz.ordaops.android.feature.business.ops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.NativePage
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// «Регламенты» (`knowledge-admin`; на сайте страница переехала в `/regulations`):
// правила и статьи, чек-листы смены, вопросы операторов и подтверждения.
// Запросы: `/api/admin/knowledge` (+ `/questions`, `/confirmations`). Сбор
// регламента через ИИ и импорт из Word — на сайте.

private const val KNOWLEDGE = "/api/admin/knowledge"

// ── Модели ───────────────────────────────────────────────────────────────────

data class KnowledgeCategory(val id: String, val title: String)

data class KnowledgeArticle(
    val id: String,
    val categoryId: String?,
    val title: String,
    val summary: String?,
    val severity: String,
    val isPublished: Boolean,
    val requiresConfirmation: Boolean,
    val version: Int,
    val updatedAt: String?,
    val fine: Double?,
    val bonus: Double?,
) {
    val severityLabel: String? get() = when (severity) { "critical" -> "критично"; "warning" -> "важно"; else -> null }
}

data class ChecklistItem(val id: String, val templateId: String, val title: String, val required: Boolean, val photo: Boolean)

data class ChecklistTemplate(
    val id: String,
    val title: String,
    val description: String?,
    val companyId: String?,
    val scope: String,
    val isActive: Boolean,
    val items: List<ChecklistItem>,
) {
    /** Когда чек-лист проходят: смена из `shift_scope`. */
    val scopeLabel: String
        get() = when (scope) {
            "day" -> "дневная смена"; "night" -> "ночная смена"; "opening" -> "открытие"
            "closing" -> "закрытие"; "handover" -> "пересменка"; else -> "любая смена"
        }
}

data class ChecklistRun(
    val id: String,
    val templateTitle: String,
    val companyName: String?,
    val status: String,
    val runBy: String?,
    val failed: Int,
    val answered: Int,
    val itemCount: Int,
    val at: String?,
    val fines: Double,
    val skipReason: String?,
) {
    val statusLabel: String
        get() = when (status) { "completed" -> "пройден"; "skipped" -> "прощён"; "in_progress" -> "идёт"; else -> status }
}

data class KnowledgeData(
    val categories: List<KnowledgeCategory>,
    val articles: List<KnowledgeArticle>,
    val templates: List<ChecklistTemplate>,
    val runs: List<ChecklistRun>,
) {
    companion object {
        fun parse(root: JsonObject): KnowledgeData {
            val d = root.obj("data") ?: root
            val items = d.arr("items").map {
                ChecklistItem(it.str("id") ?: "", it.str("template_id") ?: "", it.str("title") ?: "Пункт", it.bool("is_required") ?: true, it.bool("requires_photo") ?: false)
            }.groupBy { it.templateId }
            return KnowledgeData(
                categories = d.arr("categories").map { KnowledgeCategory(it.str("id") ?: "", it.str("title") ?: "Без названия") },
                articles = d.arr("articles").map {
                    KnowledgeArticle(
                        it.str("id") ?: "", it.str("category_id"), it.str("title") ?: "Без названия", it.str("summary"),
                        it.str("severity") ?: "normal", it.bool("is_published") ?: true, it.bool("requires_confirmation") ?: false,
                        it.int("version") ?: 1, it.str("updated_at"), it.num("related_fine_amount"), it.num("related_bonus_amount"),
                    )
                }.filter { it.id.isNotEmpty() },
                templates = d.arr("templates").map {
                    val id = it.str("id") ?: ""
                    ChecklistTemplate(id, it.str("title") ?: "Чек-лист", it.str("description"), it.str("company_id"), it.str("shift_scope") ?: "any", it.bool("is_active") ?: true, items[id].orEmpty())
                }.filter { it.id.isNotEmpty() },
                runs = d.arr("runs").map {
                    ChecklistRun(
                        it.str("id") ?: "", it.str("template_title") ?: "Чек-лист", it.str("company_name"), it.str("status") ?: "",
                        it.str("run_by_name"), it.int("failed_count") ?: 0, it.int("answered_count") ?: 0, it.int("item_count") ?: 0,
                        it.str("completed_at") ?: it.str("started_at") ?: it.str("created_at"), it.num("fines_total") ?: 0.0,
                        it.obj("responses")?.str("skip_reason"),
                    )
                }.filter { it.id.isNotEmpty() },
            )
        }
    }
}

/** Вопросы операторов по правилу: какой пункт переписать, чтобы его поняли. */
data class QuestionGroup(val articleId: String, val title: String, val count: Int, val operators: Int, val samples: List<String>)

data class QuestionsData(val available: Boolean, val total: Int, val groups: List<QuestionGroup>) {
    companion object {
        fun parse(root: JsonObject): QuestionsData {
            val d = root.obj("data") ?: root
            return QuestionsData(
                available = d.bool("available") ?: true, total = d.int("total") ?: 0,
                groups = d.arr("groups").map { g ->
                    QuestionGroup(
                        g.str("article_id") ?: "", g.str("title") ?: "Правило", g.int("count") ?: 0, g.int("operators") ?: 0,
                        g.arr("samples").mapNotNull { s -> s.str("question") },
                    )
                },
            )
        }
    }
}

/** Подтверждение правила: кто и какую версию подтвердил. */
data class ArticleConfirmation(val articleId: String, val title: String, val version: Int, val confirmed: Int, val pending: List<String>) {
    val total: Int get() = confirmed + pending.size
}

/**
 * Для каждого правила с обязательным подтверждением — кто подтвердил его текущую
 * версию, а кто ещё нет. Подтверждение старой версии не считается: правило
 * менялось, прочитать его надо заново.
 */
fun parseConfirmations(root: JsonObject): List<ArticleConfirmation> {
    val d = root.obj("data") ?: root
    val staff = d.arr("staff")
    val confirmations = d.arr("confirmations")
    return d.arr("articles").map { a ->
        val id = a.str("id") ?: ""
        val version = a.int("version") ?: 1
        val done = confirmations.filter { it.str("article_id") == id && (it.int("article_version") ?: 1) >= version }
            .mapNotNull { it.str("staff_id") }.toSet()
        ArticleConfirmation(
            id, a.str("title") ?: "Правило", version, staff.count { it.str("id") in done },
            staff.filter { it.str("id") !in done }.map { it.str("full_name", "short_name") ?: "Сотрудник" },
        )
    }.filter { it.articleId.isNotEmpty() }.sortedByDescending { it.pending.size }
}

fun setPublishedBody(id: String, published: Boolean): JsonObject = buildJsonObject {
    put("action", "setArticlePublished")
    put("payload", buildJsonObject { put("id", id); put("is_published", published) })
}

fun deleteArticleBody(id: String): JsonObject = buildJsonObject { put("action", "deleteArticle"); put("id", id) }

/** Запуск чек-листа в открытую смену точки; пропуск требует причину — её увидят в истории смены. */
fun checklistRunBody(templateId: String, companyId: String, skipReason: String?): JsonObject = buildJsonObject {
    put("action", if (skipReason == null) "startChecklistRun" else "skipChecklistRun")
    put("payload", buildJsonObject {
        put("template_id", templateId); put("company_id", companyId)
        if (skipReason != null) put("reason", skipReason.trim())
    })
}

fun skipReasonProblem(reason: String): String? = if (reason.trim().length < 3) "Нужна причина: её увидят в истории смены." else null

// ── Экран ────────────────────────────────────────────────────────────────────

@Composable
fun KnowledgeAdminScreen() {
    var tab by rememberSaveable { mutableStateOf("articles") }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            PillSegment(
                listOf("articles" to "Правила", "checklists" to "Чек-листы", "questions" to "Вопросы", "confirm" to "Подтверждения"),
                tab,
            ) { tab = it }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                "articles" -> ArticlesTab()
                "checklists" -> ChecklistsTab()
                "questions" -> QuestionsTab()
                else -> ConfirmationsTab()
            }
        }
    }
}

@Composable
private fun WebHint() {
    val context = LocalContext.current
    OpsRow(
        Icons.Filled.MenuBook, Orda.colors.info, "Сбор через ИИ и импорт из Word",
        subtitle = "Настройка ниши, каркас тем и правка текстов — на сайте.",
        onClick = { NativePage.openWeb(context, "/regulations/setup") },
    )
}

@Composable
private fun ArticlesTab() {
    val loader = rememberOpsLoader("knowledge", ApiRequest(KNOWLEDGE), KnowledgeData::parse)
    val state by loader.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    StateContainer(
        state = state, onRetry = loader::refresh,
        isEmpty = { it.articles.isEmpty() }, emptyIcon = Icons.Filled.Article,
        emptyTitle = "Правил пока нет", emptyMessage = "Соберите регламент через ИИ или добавьте правила на сайте.",
    ) { data ->
        val needle = query.trim().lowercase()
        val shown = data.articles.filter { needle.isEmpty() || it.title.lowercase().contains(needle) || (it.summary ?: "").lowercase().contains(needle) }
        val titles = data.categories.associate { it.id to it.title }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            item(key = "search") { OpsSearchField(query, { query = it }, "Поиск по правилам") }
            val published = data.articles.count { it.isPublished }
            item(key = "stats") {
                OpsSection("Регламент", count = "опубликовано $published из ${data.articles.size}") { WebHint() }
            }
            val groups = shown.groupBy { it.categoryId }
            groups.forEach { (categoryId, list) ->
                item(key = "cat-$categoryId") {
                    OpsSection(titles[categoryId] ?: "Без раздела", count = "${list.size}") {
                        list.forEach { a ->
                            OpsRow(
                                if (a.isPublished) Icons.Filled.Article else Icons.Filled.HelpOutline,
                                if (a.severity == "critical") Orda.colors.negative else if (a.severity == "warning") Orda.colors.warning else if (a.isPublished) Orda.colors.brand else Orda.colors.textDim,
                                a.title,
                                subtitle = listOfNotNull(
                                    a.severityLabel, if (!a.isPublished) "черновик" else null,
                                    if (a.requiresConfirmation) "нужно подтверждение" else null, a.summary?.takeIf { it.isNotBlank() },
                                ).joinToString(" · ").ifEmpty { null },
                                onClick = { openId = a.id },
                            )
                        }
                    }
                }
            }
        }
        data.articles.firstOrNull { it.id == openId }?.let { ArticleSheet(it, onDismiss = { openId = null }, onChanged = loader::refresh) }
    }
}

@Composable
private fun ArticleSheet(article: KnowledgeArticle, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val env = LocalBusiness.current
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    var content by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var published by remember { mutableStateOf(article.isPublished) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(article.id) {
        runCatching { env.api.send<JsonObject>(ApiRequest(KNOWLEDGE, query = mapOf("article" to article.id))) }
            .onSuccess { content = (it.obj("data") ?: it).str("content") ?: "" }
            .onFailure { failed = true }
    }
    val c = Orda.colors
    OrdaSheet(article.title, onDismiss) {
        submit.error?.let { ErrorBanner(it) }
        article.severityLabel?.let { Text(it, color = if (article.severity == "critical") c.negative else c.warning, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        article.summary?.takeIf { it.isNotBlank() }?.let { Text(it, color = c.textMuted, fontSize = 15.sp) }
        when {
            content != null -> Text(content.orEmpty().ifBlank { "Текст не заполнен." }, color = c.text, fontSize = 15.sp)
            failed -> Text("Текст не загрузился.", color = c.negative, fontSize = 14.sp)
            else -> Text("Загружаем текст…", color = c.textDim, fontSize = 14.sp)
        }
        if (article.fine != null || article.bonus != null) {
            Text(
                listOfNotNull(article.fine?.let { "штраф ${kz.ordaops.android.ui.components.money(it)}" }, article.bonus?.let { "бонус ${kz.ordaops.android.ui.components.money(it)}" }).joinToString(" · "),
                color = c.textDim, fontSize = 13.sp,
            )
        }
        if (env.can("knowledge-admin.publish")) {
            OpsSwitchRow(
                "Опубликовано", published, subtitle = "Снятие с публикации убирает правило у всей команды.",
                enabled = !submit.busy,
            ) { next ->
                submit.run(
                    { env.api.sendUnit(ApiRequest.json(KNOWLEDGE, setPublishedBody(article.id, next))) },
                    { published = next; haptics.success(); onChanged() },
                )
            }
        }
        if (env.can("knowledge-admin.delete")) {
            PrimaryDanger("Удалить правило") { confirmDelete = true }
        }
        Text("Править текст правила удобнее на сайте.", color = c.textDim, fontSize = 12.sp)
    }
    if (confirmDelete) {
        OpsConfirm(
            "Удалить правило?", "Оно пропадёт у всех сотрудников. Это нельзя отменить.", "Удалить",
            onConfirm = {
                confirmDelete = false
                submit.run({ env.api.sendUnit(ApiRequest.json(KNOWLEDGE, deleteArticleBody(article.id))) }, { onChanged(); onDismiss() })
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun PrimaryDanger(title: String, onClick: () -> Unit) {
    OpsRow(Icons.Filled.Delete, Orda.colors.negative, title, onClick = onClick)
}

@Composable
private fun ChecklistsTab() {
    val loader = rememberOpsLoader("knowledge", ApiRequest(KNOWLEDGE), KnowledgeData::parse)
    val state by loader.state.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    StateContainer(
        state = state, onRetry = loader::refresh,
        isEmpty = { it.templates.isEmpty() }, emptyIcon = Icons.Filled.Checklist,
        emptyTitle = "Чек-листов нет", emptyMessage = "Чек-листы приёма, обхода и сдачи смены заводятся на сайте.",
    ) { data ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            item(key = "templates") {
                OpsSection("Чек-листы", count = "${data.templates.size}") {
                    data.templates.forEach { t ->
                        OpsRow(
                            Icons.Filled.Checklist, if (t.isActive) Orda.colors.brand else Orda.colors.textDim, t.title,
                            subtitle = "${t.scopeLabel} · ${t.items.size} ${plural(t.items.size, "пункт", "пункта", "пунктов")}" + if (!t.isActive) " · выключен" else "",
                            onClick = { openId = t.id },
                        )
                    }
                }
            }
            item(key = "runs") {
                OpsSection("Недавние прохождения", count = "${data.runs.take(20).size}") {
                    if (data.runs.isEmpty()) Text("Пока никто не проходил", color = Orda.colors.textDim)
                    data.runs.take(20).forEach { r ->
                        val bad = r.failed > 0
                        OpsRow(
                            if (r.status == "completed") Icons.Filled.CheckCircle else if (r.status == "skipped") Icons.Filled.Warning else Icons.Filled.PlayArrow,
                            if (bad) Orda.colors.negative else if (r.status == "completed") Orda.colors.positive else Orda.colors.warning,
                            r.templateTitle,
                            subtitle = listOfNotNull(
                                r.companyName, r.runBy, dateTimeLabel(r.at),
                                r.skipReason?.let { "причина: $it" },
                                if (bad) "не пройдено: ${r.failed}" else null,
                            ).joinToString(" · "),
                            trailing = r.statusLabel, trailingColor = if (r.status == "completed") Orda.colors.positive else Orda.colors.textDim,
                            trailingSub = if (r.itemCount > 0) "${r.answered}/${r.itemCount}" else null,
                        )
                    }
                }
            }
        }
        data.templates.firstOrNull { it.id == openId }?.let { TemplateSheet(it, onDismiss = { openId = null }, onChanged = loader::refresh) }
    }
}

@Composable
private fun TemplateSheet(template: ChecklistTemplate, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val env = LocalBusiness.current
    val companies = rememberCompanyOptions()
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    var company by rememberSaveable { mutableStateOf(template.companyId.orEmpty()) }
    var skipping by rememberSaveable { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }
    var done by remember { mutableStateOf<String?>(null) }
    val canRun = env.can("knowledge-admin.run_checklist")
    val canSkip = env.can("knowledge-admin.skip_checklist")
    val c = Orda.colors
    OrdaSheet(template.title, onDismiss) {
        submit.error?.let { ErrorBanner(it) }
        done?.let { Text(it, color = c.positive, fontSize = 14.sp) }
        template.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = c.textMuted, fontSize = 14.sp) }
        Text(template.scopeLabel, color = c.textDim, fontSize = 13.sp)
        OpsSection("Пункты", count = "${template.items.size}") {
            if (template.items.isEmpty()) Text("Пунктов нет", color = c.textDim)
            template.items.forEach { i ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(i.title, color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(listOfNotNull(if (i.required) "обяз." else null, if (i.photo) "фото" else null).joinToString(" · "), color = c.textDim, fontSize = 12.sp)
                }
            }
        }
        if (canRun || canSkip) {
            OpsPicker("Точка", companies.map { it.id to it.name }, company, { company = it })
            Text("Нужна открытая смена на точке — иначе сервер откажет.", color = c.textDim, fontSize = 12.sp)
        }
        if (canRun) {
            PrimaryButton("Запустить в текущую смену", Modifier.fillMaxWidth(), enabled = company.isNotEmpty(), loading = submit.busy) {
                submit.run(
                    { env.api.sendUnit(ApiRequest.json(KNOWLEDGE, checklistRunBody(template.id, company, null))) },
                    { haptics.success(); done = "Чек-лист запущен в смене."; onChanged() },
                )
            }
        }
        if (canSkip) {
            if (!skipping) {
                OpsRow(Icons.Filled.Warning, c.warning, "Простить в этой смене", subtitle = "Закрыть без прохождения, с причиной", onClick = { skipping = true })
            } else {
                OpsField("Причина", reason, { reason = it }, minLines = 2)
                skipReasonProblem(reason)?.let { if (reason.isNotEmpty()) Text(it, color = c.warning, fontSize = 13.sp) }
                PrimaryButton("Простить", Modifier.fillMaxWidth(), enabled = company.isNotEmpty() && skipReasonProblem(reason) == null, loading = submit.busy) {
                    submit.run(
                        { env.api.sendUnit(ApiRequest.json(KNOWLEDGE, checklistRunBody(template.id, company, reason))) },
                        { haptics.success(); done = "Чек-лист прощён."; skipping = false; reason = ""; onChanged() },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionsTab() {
    val loader = rememberOpsLoader("knowledge-questions", ApiRequest("$KNOWLEDGE/questions"), QuestionsData::parse)
    val state by loader.state.collectAsStateWithLifecycle()
    StateContainer(
        state = state, onRetry = loader::refresh,
        isEmpty = { it.groups.isEmpty() }, emptyIcon = Icons.Filled.HelpOutline,
        emptyTitle = "Вопросов нет", emptyMessage = "Когда операторы начнут просить объяснить правила проще, здесь появится, что переписать.",
    ) { data ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            item { Text("Каждое «объясни проще» — сигнал, что текст непонятен. Всего вопросов: ${data.total}.", color = Orda.colors.textDim, fontSize = 13.sp) }
            items(data.groups.size) { index ->
                val g = data.groups[index]
                OpsSection(g.title, count = "${g.count} ${plural(g.count, "вопрос", "вопроса", "вопросов")} · ${g.operators} ${plural(g.operators, "человек", "человека", "человек")}") {
                    g.samples.forEach { Text("— $it", color = Orda.colors.text, fontSize = 14.sp) }
                }
            }
        }
    }
}

@Composable
private fun ConfirmationsTab() {
    val loader = rememberOpsLoader("knowledge-confirm", ApiRequest("$KNOWLEDGE/confirmations"), ::parseConfirmations)
    val state by loader.state.collectAsStateWithLifecycle()
    StateContainer(
        state = state, onRetry = loader::refresh,
        isEmpty = { it.isEmpty() }, emptyIcon = Icons.Filled.Verified,
        emptyTitle = "Подтверждать нечего", emptyMessage = "Правила с обязательным подтверждением появятся здесь.",
    ) { list ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            items(list.size) { index ->
                val a = list[index]
                OpsSection(a.title, count = "${a.confirmed} из ${a.total}") {
                    if (a.pending.isEmpty()) Text("Все подтвердили версию ${a.version}", color = Orda.colors.positive, fontSize = 14.sp)
                    else {
                        Text("Не подтвердили версию ${a.version}:", color = Orda.colors.textDim, fontSize = 13.sp)
                        a.pending.forEach { Text(it, color = Orda.colors.text, fontSize = 15.sp) }
                    }
                }
            }
        }
    }
}
