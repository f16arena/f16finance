package kz.ordaops.android.feature.business.ops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

private const val KNOWLEDGE_PATH = "/api/admin/knowledge"

/**
 * Создание и правка правила (`upsertArticle`, как редактор статьи на сайте).
 * Текст: простой HTML сайта правится как обычный текст («- » — пункт списка,
 * `**жирный**`), всё сложное (таблицы, заголовки) — как HTML, чтобы ничего не потерять.
 * Публикация — отдельное право, как на сервере: без него новое правило сохраняется
 * черновиком, а состояние существующего не меняется.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArticleEditSheet(
    articleId: String?,
    categories: List<KnowledgeCategory>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val env = LocalBusiness.current
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    val canPublish = env.can("knowledge-admin.publish")
    var draft by remember { mutableStateOf<ArticleDraft?>(if (articleId == null) ArticleDraft(isPublished = canPublish) else null) }
    var mode by remember { mutableStateOf(ArticleTextMode.SimpleHtml) }
    var text by remember { mutableStateOf("") }
    var companies by remember { mutableStateOf<List<SetupCompany>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    val c = Orda.colors

    LaunchedEffect(articleId) {
        if (articleId != null) {
            runCatching { env.api.send<JsonObject>(ApiRequest(KNOWLEDGE_PATH, query = mapOf("article" to articleId))) }
                .onSuccess {
                    val loaded = ArticleDraft.fromJson(it.obj("data") ?: it)
                    mode = detectTextMode(loaded.content)
                    text = contentForEditor(loaded.content, mode)
                    draft = loaded
                }
                .onFailure { failed = true }
        }
        runCatching { env.api.send<JsonObject>(ApiRequest(KNOWLEDGE_PATH)) }.onSuccess {
            val d = it.obj("data") ?: it
            companies = d.arr("companies").map { o -> SetupCompany(o.str("id") ?: "", o.str("name") ?: "Точка", null) }.filter { o -> o.id.isNotEmpty() }
        }
    }

    OrdaSheet(if (articleId == null) "Новое правило" else "Правка правила", onDismiss) {
        val d = draft
        when {
            failed -> Text("Правило не загрузилось.", color = c.negative, fontSize = 14.sp)
            d == null -> Text("Загружаем…", color = c.textDim, fontSize = 14.sp)
            else -> {
                submit.error?.let { ErrorBanner(it) }
                OpsField("Название", d.title, { draft = d.copy(title = it) }, singleLine = true)
                OpsPicker(
                    "Раздел", listOf("" to "Без раздела") + categories.map { it.id to it.title }, d.categoryId,
                    { draft = d.copy(categoryId = it) },
                )
                OpsPicker(
                    "Точка", listOf("" to "Все точки") + companies.map { it.id to it.name }, d.companyId,
                    { draft = d.copy(companyId = it) },
                )
                OpsField("Кратко", d.summary, { draft = d.copy(summary = it) }, minLines = 2)
                OpsField(
                    if (mode == ArticleTextMode.RawHtml) "Текст (HTML)" else "Текст", text, { text = it },
                    minLines = 8,
                )
                when (mode) {
                    ArticleTextMode.SimpleHtml -> Text("Пустая строка — новый абзац, «- » в начале — пункт списка, **текст** — жирный.", color = c.textDim, fontSize = 12.sp)
                    ArticleTextMode.RawHtml -> Text("В тексте есть таблицы или заголовки — он правится как HTML, чтобы оформление не потерялось.", color = c.warning, fontSize = 12.sp)
                    ArticleTextMode.Plain -> Text("Текст без оформления сохраняется как набран.", color = c.textDim, fontSize = 12.sp)
                }
                OpsField("Теги через запятую", d.tags, { draft = d.copy(tags = it) }, singleLine = true)
                OpsPicker("Важность", ARTICLE_SEVERITIES, d.severity, { draft = d.copy(severity = it) })
                Text("Для кого", color = c.textMuted, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    ARTICLE_AUDIENCE.forEach { (value, label) ->
                        FilterChip(
                            selected = value in d.audience,
                            onClick = { draft = d.copy(audience = if (value in d.audience) d.audience - value else d.audience + value) },
                            label = { Text(label) },
                        )
                    }
                }
                OpsField("Штраф, ₸", d.fine, { draft = d.copy(fine = it) }, singleLine = true, keyboardType = KeyboardType.Decimal)
                OpsField("Бонус, ₸", d.bonus, { draft = d.copy(bonus = it) }, singleLine = true, keyboardType = KeyboardType.Decimal)
                OpsSwitchRow(
                    "Нужно подтверждение", d.requiresConfirmation,
                    subtitle = "Сотрудник должен подтвердить, что прочитал правило.",
                ) { draft = d.copy(requiresConfirmation = it) }
                if (canPublish) {
                    OpsSwitchRow("Опубликовано", d.isPublished, subtitle = "Видно всей команде.") { draft = d.copy(isPublished = it) }
                } else {
                    Text("Публикация — отдельное право: правило сохранится как есть, без изменения статуса.", color = c.textDim, fontSize = 12.sp)
                }
                PrimaryButton(
                    "Сохранить", modifier = Modifier.fillMaxWidth(), enabled = d.problem() == null, loading = submit.busy,
                ) {
                    val toSave = d.copy(content = contentForSave(text, mode))
                    submit.run(
                        { env.api.sendUnit(ApiRequest.json(KNOWLEDGE_PATH, toSave.body())) },
                        { haptics.success(); onSaved(); onDismiss() },
                    )
                }
                d.problem()?.let { Text(it, color = c.warning, fontSize = 13.sp) }
            }
        }
    }
}
