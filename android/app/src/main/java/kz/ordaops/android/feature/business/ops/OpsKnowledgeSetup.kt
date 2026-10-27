package kz.ordaops.android.feature.business.ops

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

// «Настройка базы знаний» (`knowledge-setup`; на сайте — `/regulations/setup`):
// ниша точки задаёт каркас тем, черновики собираются из данных системы или из
// ответов владельца, документ из Word разбирается на статьи и чек-листы.
// Ничего не публикуется само: черновики читает и публикует человек.
// Маршруты: `/api/admin/knowledge/industry`, `/api/admin/knowledge/import`,
// `/api/admin/knowledge` (applyImport).

private const val INDUSTRY = "/api/admin/knowledge/industry"
private const val IMPORT = "/api/admin/knowledge/import"
private const val KNOWLEDGE_ROOT = "/api/admin/knowledge"

@Composable
fun KnowledgeSetupScreen() {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val c = Orda.colors
    var companyId by rememberSaveable { mutableStateOf("") }
    var data by remember { mutableStateOf<SetupData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var interviewOpen by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(0) }
    val answers = remember { mutableStateMapOf<String, String>() }
    var openDraft by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf<String?>(null) }
    var confirmResetOrg by remember { mutableStateOf(false) }

    val canSetIndustry = env.can("knowledge-setup.set_industry")
    val canGenerate = env.can("knowledge-setup.generate")
    val canReset = env.can("knowledge-setup.reset")

    LaunchedEffect(companyId, tick) {
        loading = true
        error = null
        try {
            val query = if (companyId.isEmpty()) emptyMap() else mapOf("company_id" to companyId)
            data = SetupData.parse(env.api.send<JsonObject>(ApiRequest(INDUSTRY, query = query)))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        } finally {
            loading = false
        }
    }

    /** Запись и перезагрузка. Возвращает ответ или `null` при ошибке (она уже показана). */
    fun post(key: String, body: JsonObject, then: (JsonObject) -> Unit = {}) {
        if (busy != null) return
        busy = key
        error = null
        scope.launch {
            try {
                val res = env.api.send<JsonObject>(ApiRequest.json(INDUSTRY, body))
                tick++
                then(res)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                busy = null
            }
        }
    }

    val d = data
    val company = d?.company

    OwnerScreenColumn {
        Text(
            "Ниша точки задаёт каркас тем. Черновики собираются из данных системы или из ваших ответов — публикуете вы.",
            color = c.textMuted, fontSize = 14.sp,
        )
        error?.let { ErrorBanner(it) }
        notice?.let {
            Column(
                Modifier.fillMaxWidth().background(c.positive.copy(alpha = 0.12f), RoundedCornerShape(Radius.md)).clickable { notice = null }.padding(Spacing.md),
            ) {
                Text(it, color = c.text, fontSize = 14.sp)
                Text("Нажмите, чтобы скрыть", color = c.textDim, fontSize = 12.sp)
            }
        }

        SetupCard("Как заполнить: четыре шага") {
            listOf(
                "1. Выберите точку и нишу — у клуба, PS-зоны и магазина темы разные.",
                "2. Соберите из данных — цены и правила оплаты подтянутся из системы.",
                "3. Пройдите интервью — спросит только про темы без статей.",
                "4. Проверьте и опубликуйте — черновики в экзамен не идут.",
            ).forEach { Text(it, color = c.textMuted, fontSize = 13.sp) }
        }

        if (loading && d == null) Text("Загружаем…", color = c.textDim, fontSize = 14.sp)

        if (d != null) {
            SetupCard("Точка") {
                if (d.companies.isEmpty()) Text("Точек нет.", color = c.textDim, fontSize = 14.sp)
                OpsPicker(
                    "Точка",
                    d.companies.map { it.id to (it.name + " · " + (d.industries.firstOrNull { i -> i.code == it.industry }?.label ?: it.industry ?: "ниша не задана")) },
                    companyId, { companyId = it; interviewOpen = false; step = 0; openDraft = null },
                )
            }
        }

        if (companyId.isEmpty() && d != null) {
            Text(
                "Выберите точку. Ниша задаётся отдельно для каждой — у клуба, PS-зоны и магазина разные регламенты, и экзамен по ним тоже разный.",
                color = c.textDim, fontSize = 14.sp,
            )
        }

        if (d != null && companyId.isNotEmpty() && company != null) {
            SetupCard("Ниша точки «${company.name}»") {
                d.industries.forEach { industry ->
                    val active = company.industry == industry.code
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (active) c.brand.copy(alpha = 0.12f) else c.surfaceRaised, RoundedCornerShape(Radius.md))
                            .clickable(enabled = canSetIndustry && busy == null) {
                                post("industry", setIndustryBody(companyId, industry.code)) {
                                    // Ниша выбрана — сразу ведём заполнять дыры, как на сайте.
                                    step = 0
                                    interviewOpen = true
                                }
                            }
                            .padding(Spacing.md),
                    ) {
                        Text(industry.label, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(industry.description, color = c.textDim, fontSize = 13.sp)
                    }
                }
                if (company.industry == null) {
                    Text("Пока ниша не выбрана, каркас показывает только общие темы, а сбор через ИИ недоступен.", color = c.warning, fontSize = 13.sp)
                }
            }

            if (company.industry != null) {
                SetupCard("Собрать из данных системы") {
                    Text("Берёт настоящие цифры: каталог с ценами, правила оплаты смены, чек-листы. Цены не выдуманы.", color = c.textMuted, fontSize = 13.sp)
                    val f = d.facts
                    Text("Каталог: " + (if (f != null && f.catalog > 0) "${f.catalog} позиций с ценой" else "пусто"), color = c.textDim, fontSize = 13.sp)
                    Text("Правила зарплаты: " + (if (f?.salaryRules == true) "есть" else "нет"), color = c.textDim, fontSize = 13.sp)
                    Text("Чек-листы: " + (if (f?.checklists == true) "есть" else "нет"), color = c.textDim, fontSize = 13.sp)
                    PrimaryButton("Собрать черновики", Modifier.fillMaxWidth(), enabled = canGenerate && busy == null, loading = busy == "facts") {
                        post("facts", generateFactsBody(companyId)) { notice = generationSummary(it) }
                    }
                }

                SetupCard("Интервью про точку") {
                    Text(
                        if (d.interview.isNotEmpty()) "${d.interview.size} вопросов ровно по темам, которые ещё не закрыты. Отвечайте своими словами — ИИ оформит в регламент."
                        else "Все темы каркаса закрыты — вопросов не осталось.",
                        color = c.textMuted, fontSize = 13.sp,
                    )
                    if (d.interview.isNotEmpty()) {
                        PrimaryButton(if (interviewOpen) "Свернуть" else "Пройти интервью", Modifier.fillMaxWidth(), enabled = canGenerate) {
                            step = 0
                            interviewOpen = !interviewOpen
                        }
                    }
                }

                if (interviewOpen && d.interview.isNotEmpty()) {
                    val q = d.interview[minOf(step, d.interview.size - 1)]
                    val isLast = step >= d.interview.size - 1
                    val answered = answeredCount(d.interview, answers)
                    SetupCard("Вопрос ${step + 1} из ${d.interview.size}") {
                        Text(q.question, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        q.hint?.let { Text(it, color = c.textDim, fontSize = 13.sp) }
                        OpsField("Ответ", answers[q.key].orEmpty(), { answers[q.key] = it }, minLines = 4, placeholder = "Своими словами — как есть на самом деле")
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            if (step > 0) kz.ordaops.android.feature.business.team.TeamPill("Назад", c.textMuted) { step-- }
                            if (!isLast) kz.ordaops.android.feature.business.team.TeamPill("Дальше") { step++ }
                        }
                        if (!isLast) Text("Не знаете — жмите «Дальше», тема останется незакрытой.", color = c.textDim, fontSize = 12.sp)
                        Text("Заполнено $answered из ${d.interview.size}", color = c.textDim, fontSize = 13.sp)
                        PrimaryButton("Составить регламенты ($answered)", Modifier.fillMaxWidth(), enabled = answered > 0 && busy == null, loading = busy == "interview") {
                            post("interview", generateInterviewBody(companyId, answers.toMap())) {
                                notice = generationSummary(it)
                                answers.clear()
                                step = 0
                                interviewOpen = false
                            }
                        }
                    }
                }
            }

            // Каркас тем
            val (covered, total, pct) = d.coverage
            SetupCard("Каркас регламентов") {
                Text("Закрыто $covered из $total", color = c.textMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                CoverageBar(pct)
                d.topics.forEach { topic ->
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Icon(
                            if (topic.published > 0) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                            tint = if (topic.published > 0) c.positive else c.textDim, modifier = Modifier.height(22.dp),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(topic.label, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            val flags = listOfNotNull(
                                if (topic.critical) "критично" else null,
                                if (topic.hasFactSource) (if (topic.factsAvailable) "есть данные в системе" else "данных в системе нет") else null,
                                if (topic.drafts > 0) "${topic.drafts} черновик(а) — проверьте и опубликуйте" else null,
                            )
                            if (flags.isNotEmpty()) Text(flags.joinToString(" · "), color = if (topic.drafts > 0) c.warning else c.textDim, fontSize = 12.sp)
                            if (topic.hint.isNotBlank()) Text(topic.hint, color = c.textDim, fontSize = 12.sp)
                            topic.articles.forEach { a ->
                                if (a.isPublished) {
                                    Text("✓ ${a.title}", color = c.positive, fontSize = 12.sp)
                                } else {
                                    Text(
                                        "${a.title} · черновик " + if (openDraft == a.id) "▾" else "▸", color = c.warning, fontSize = 13.sp,
                                        modifier = Modifier.clickable { openDraft = if (openDraft == a.id) null else a.id }.padding(vertical = 2.dp),
                                    )
                                    if (openDraft == a.id) {
                                        Column(
                                            Modifier.fillMaxWidth().background(c.warning.copy(alpha = 0.08f), RoundedCornerShape(Radius.sm)).padding(Spacing.sm),
                                            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                                        ) {
                                            a.summary?.takeIf { it.isNotBlank() }?.let { Text(it, color = c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                                            Text(draftPreview(a.content.orEmpty()), color = c.text, fontSize = 12.sp)
                                            if (canGenerate) {
                                                PrimaryButton("Всё верно — опубликовать", Modifier.fillMaxWidth(), enabled = busy == null, loading = busy == "publish:${a.id}") {
                                                    post("publish:${a.id}", publishDraftBody(companyId, a.id)) { openDraft = null }
                                                }
                                            }
                                            if (env.can("knowledge-admin.edit")) {
                                                Text("Поправить текст можно в «Регламенты» → правило.", color = c.textDim, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (covered > 0) {
                SetupCard("Готово к экзамену") {
                    Text(
                        "Опубликовано тем: $covered из $total. Вопросы соберутся по ним." + if (covered < total) " Незакрытые темы просто не попадут в билет." else "",
                        color = c.textMuted, fontSize = 13.sp,
                    )
                    if (env.access.canSee("operator-exams")) {
                        PrimaryButton("Назначить экзамен", Modifier.fillMaxWidth()) { env.open("operator-exams") }
                    }
                }
            } else {
                Text(
                    "Черновики не участвуют в экзамене — вопросы собираются только по опубликованным регламентам. Прочитайте текст черновика (нажмите на жёлтую строку) и опубликуйте: ИИ мог упустить деталь, а экзамен спросит ровно то, что написано.",
                    color = c.info, fontSize = 13.sp,
                )
            }

            if (canReset) {
                SetupCard("Удалить и собрать заново") {
                    Text("Удалённое не восстанавливается. Отметки сотрудников об ознакомлении с этими регламентами удаляются вместе с ними.", color = c.textDim, fontSize = 13.sp)
                    kz.ordaops.android.feature.business.team.TeamPill("Только черновики точки", c.negative) { if (busy == null) confirmReset = "drafts" }
                    kz.ordaops.android.feature.business.team.TeamPill("Все регламенты точки", c.negative) { if (busy == null) confirmReset = "point" }
                    kz.ordaops.android.feature.business.team.TeamPill("Всю базу организации", c.negative) { if (busy == null) confirmReset = "organization" }
                }
            }
        }

        // Импорт документа не зависит от выбранной точки: точки назначает разбор.
        if (env.can("knowledge-admin.create")) {
            ImportSection(onApplied = { notice = it; tick++ })
        }
    }

    confirmReset?.let { scopeKey ->
        val name = company?.name.orEmpty()
        val (title, message) = when (scopeKey) {
            "drafts" -> "Удалить черновики?" to "Все черновики точки «$name» будут удалены. Опубликованные регламенты останутся."
            "point" -> "Удалить регламенты точки?" to "Все регламенты точки «$name», включая опубликованные, будут удалены."
            else -> "Удалить всю базу знаний?" to "Все точки и общесетевые правила организации. Это необратимо."
        }
        OpsConfirm(
            title, message, "Удалить",
            onConfirm = {
                confirmReset = null
                if (scopeKey == "organization") confirmResetOrg = true
                else post("reset", resetKnowledgeBody(companyId, scopeKey)) { r ->
                    notice = "Удалено: ${(r.obj("data") ?: r).int("deleted") ?: 0}"
                }
            },
            onDismiss = { confirmReset = null },
        )
    }
    if (confirmResetOrg) {
        OpsConfirm(
            "Точно удалить?", "Экзамены перестанут собираться, пока база не будет заполнена заново. Это необратимо.", "Удалить всё",
            onConfirm = {
                confirmResetOrg = false
                post("reset", resetKnowledgeBody(companyId, "organization")) { r -> notice = "Удалено: ${(r.obj("data") ?: r).int("deleted") ?: 0}" }
            },
            onDismiss = { confirmResetOrg = false },
        )
    }
}

/** Текст черновика на телефоне: HTML сводим к читаемому тексту, обычный текст оставляем. */
private fun draftPreview(content: String): String =
    if (detectTextMode(content) == ArticleTextMode.Plain) content.trim() else htmlToText(content)

@Composable
private fun SetupCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Orda.colors.surface, RoundedCornerShape(24.dp)).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(title, color = Orda.colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun CoverageBar(pct: Int) {
    val c = Orda.colors
    val tint = if (pct >= 80) c.positive else if (pct >= 40) c.warning else c.negative
    Row(Modifier.fillMaxWidth().height(6.dp).background(c.surfaceRaised, RoundedCornerShape(3.dp))) {
        if (pct > 0) Row(Modifier.weight(pct.toFloat()).height(6.dp).background(tint, RoundedCornerShape(3.dp))) {}
        if (pct < 100) Row(Modifier.weight((100 - pct).toFloat()).height(6.dp)) {}
    }
}

// ── Импорт документа ─────────────────────────────────────────────────────────

private fun readPicked(context: Context, uri: Uri): Triple<String, ByteArray, String>? {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
        if (cur.moveToFirst()) cur.getString(0) else null
    } ?: "document"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    return Triple(name, bytes, mime)
}

@Composable
private fun ImportSection(onApplied: (String) -> Unit) {
    val env = LocalBusiness.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Orda.colors
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var companies by remember { mutableStateOf<List<SetupCompany>>(emptyList()) }
    var preview by remember { mutableStateOf<ImportChunk?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val companyByKey = remember { mutableStateMapOf<String, String>() }
    var updateExisting by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            preview = null
            try {
                val picked = readPicked(context, uri) ?: throw IllegalStateException("Не удалось открыть файл")
                val (name, bytes, mime) = picked
                if (!isImportableName(name)) throw IllegalStateException("Поддерживаются .docx, .md и .txt")
                if (bytes.size > IMPORT_MAX_BYTES) throw IllegalStateException("Максимальный размер файла — 8 МБ")
                fileName = name
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", name, bytes.toRequestBody(mime.toMediaType()))
                    .build()
                val upload = parseImportUpload(env.api.send<JsonObject>(ApiRequest(IMPORT, HttpMethod.POST, body = form)), name)
                companies = upload.companies
                var acc = ImportChunk(emptyList(), emptyList())
                progress = 0 to upload.chunks.size
                try {
                    // Куски разбираются по одному: виден прогресс, а длинный регламент не упирается в таймаут.
                    upload.chunks.forEachIndexed { index, chunk ->
                        val part = parseImportChunk(
                            env.api.send<JsonObject>(ApiRequest.json(IMPORT, buildJsonObject { put("chunk", chunk); put("offset", index) })),
                        )
                        acc = mergeImport(acc, part)
                        progress = (index + 1) to upload.chunks.size
                    }
                } finally {
                    // Частичный разбор оставляем даже при сбое на середине — как на сайте.
                    if (acc.articles.isNotEmpty() || acc.checklists.isNotEmpty()) {
                        preview = acc
                        selected = (acc.articles.map { it.key } + acc.checklists.map { it.key }).toSet()
                        (acc.articles.map { it.key to it.companyId } + acc.checklists.map { it.key to it.companyId })
                            .forEach { (k, v) -> companyByKey[k] = v.orEmpty() }
                    }
                }
                if (acc.articles.isEmpty() && acc.checklists.isEmpty()) throw IllegalStateException("В документе не нашлось материалов для базы знаний")
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                busy = false
                progress = null
            }
        }
    }

    SetupCard("Импорт из Word") {
        Text(
            "Готовый регламент (.docx, .md, .txt до 8 МБ) разбирается на статьи и чек-листы. В базу попадёт только то, что вы отметите. Большой документ разбирается частями — до пары минут.",
            color = c.textMuted, fontSize = 13.sp,
        )
        error?.let { ErrorBanner(it) }
        PrimaryButton(if (busy) "Разбираю…" else "Выбрать файл", Modifier.fillMaxWidth(), enabled = !busy, loading = busy && progress == null) {
            picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain", "text/markdown", "application/octet-stream"))
        }
        progress?.let { (done, total) -> Text("Разобрано $done из $total частей", color = c.textDim, fontSize = 13.sp) }

        val p = preview
        if (p != null) {
            Text("${fileName.orEmpty()} — статей: ${p.articles.size}, чек-листов: ${p.checklists.size}", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            val options = listOf("" to "Все точки") + companies.map { it.id to it.name }
            p.articles.forEach { a ->
                ImportRow(
                    title = a.title,
                    subtitle = listOfNotNull(a.category, severityRu(a.severity), if (a.exists) "уже есть (v${a.existingVersion ?: 1})" else null).joinToString(" · "),
                    note = a.summary,
                    checked = a.key in selected,
                    onChecked = { selected = if (it) selected + a.key else selected - a.key },
                    options = options, company = companyByKey[a.key].orEmpty(), onCompany = { companyByKey[a.key] = it },
                )
            }
            p.checklists.forEach { cl ->
                ImportRow(
                    title = cl.title,
                    subtitle = "Чек-лист · ${scheduleRu(cl.scheduleType)} · пунктов: ${cl.items.size}" + if (cl.blocksShift) " · блокирует смену" else "",
                    note = cl.description,
                    checked = cl.key in selected,
                    onChecked = { selected = if (it) selected + cl.key else selected - cl.key },
                    options = options, company = companyByKey[cl.key].orEmpty(), onCompany = { companyByKey[cl.key] = it },
                )
            }
            if (p.articles.any { it.exists }) {
                OpsSwitchRow("Обновить существующие", updateExisting, subtitle = "Иначе статьи, которые уже есть, будут пропущены.") { updateExisting = it }
            }
            PrimaryButton("Добавить выбранное (${selected.size})", Modifier.fillMaxWidth(), enabled = selected.isNotEmpty() && !busy, loading = busy) {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        val res = env.api.send<JsonObject>(ApiRequest.json(KNOWLEDGE_ROOT, applyImportBody(p, selected, companyByKey.toMap(), updateExisting)))
                        onApplied(importResultSummary(res))
                        preview = null
                        selected = emptySet()
                        companyByKey.clear()
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.userMessage()
                    } finally {
                        busy = false
                    }
                }
            }
            kz.ordaops.android.feature.business.team.TeamPill("Отменить разбор", c.textMuted) { preview = null; selected = emptySet() }
        }
    }
}

@Composable
private fun ImportRow(
    title: String,
    subtitle: String,
    note: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    options: List<Pair<String, String>>,
    company: String,
    onCompany: (String) -> Unit,
) {
    val c = Orda.colors
    Column(
        Modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(Radius.md)).padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onChecked)
            Column(Modifier.weight(1f)) {
                Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = c.textDim, fontSize = 12.sp)
                note?.takeIf { it.isNotBlank() }?.let { Text(it, color = c.textMuted, fontSize = 12.sp) }
            }
        }
        if (checked && options.size > 1) OpsPicker("Точка", options, company, onCompany)
    }
}

private fun severityRu(s: String) = when (s) { "info" -> "инфо"; "warning" -> "важное"; "critical" -> "критичное"; else -> "обычное" }

private fun scheduleRu(s: String) = when (s) {
    "opening" -> "открытие"; "periodic" -> "обход"; "closing" -> "закрытие"; "onboarding" -> "онбординг"; "handover" -> "пересменка"; else -> s
}
