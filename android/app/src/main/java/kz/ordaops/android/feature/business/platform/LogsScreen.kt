package kz.ordaops.android.feature.business.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.business.ops.OpsPicker
import kz.ordaops.android.feature.business.ops.OpsSearchField
import kz.ordaops.android.feature.business.team.TeamError
import kz.ordaops.android.feature.business.team.TeamHint
import kz.ordaops.android.feature.business.team.TeamPill
import kz.ordaops.android.feature.business.team.copyToClipboard
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val prettyJson = Json { prettyPrint = true }

/** «только что», «5 мин назад», «3 ч назад», «2 д назад», дальше — дата. Пусто, если время не разобрать. */
internal fun logRelativeTime(iso: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val at = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(iso) }.getOrNull()
        ?: return ""
    val minutes = Duration.between(at, now).toMinutes()
    return when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes мин назад"
        minutes < 24 * 60 -> "${minutes / 60} ч назад"
        minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)} д назад"
        else -> DateTimeFormatter.ofPattern("d MMM", Locale("ru")).withZone(zone).format(at)
    }
}

private fun exactTime(iso: String): String {
    val at = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull() ?: return iso
    return DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale("ru")).withZone(ZoneId.systemDefault()).format(at)
}

/**
 * Журнал действий (веб `/logs`): кто, что и когда сделал, с фильтрами и страницами.
 * Только суперадмин. ИИ-объяснение ошибки — по кнопке, один запрос на запись.
 */
@Composable
fun LogsScreen() {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val c = Orda.colors
    val isSuper = env.access.resolver.session.isSuperAdmin
    if (!isSuper) {
        OwnerScreenColumn { TeamHint("Журнал действий доступен только суперадмину платформы.") }
        return
    }

    var filters by remember { mutableStateOf(LogFilters()) }
    var searchText by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(1) }
    var tick by remember { mutableStateOf(0) }
    var data by remember { mutableStateOf<LogPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }
    var explaining by remember { mutableStateOf<String?>(null) }
    val explanations = remember { mutableStateMapOf<String, String>() }
    val canExplain = env.can("logs.explain_error")

    LaunchedEffect(filters, page, tick) {
        loading = true
        error = null
        try {
            data = parseLogPage(env.api.send<JsonElement>(ApiRequest("/api/admin/logs", query = filters.params(page))))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        } finally {
            loading = false
        }
    }

    fun apply(next: LogFilters) {
        page = 1
        filters = next
    }

    OwnerScreenColumn {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            LogPreset.entries.forEach { p ->
                val active = filters.isPresetActive(p)
                TeamPill(p.title, tint = if (active) c.brand else c.textMuted) { searchText = ""; apply(filters.withPreset(p)) }
            }
        }
        OpsSearchField(searchText, { searchText = it }, "Поиск по тексту")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            TeamPill("Найти") { apply(filters.copy(search = searchText)) }
            TeamPill(if (showFilters) "Скрыть фильтры" else "Фильтры", tint = c.info) { showFilters = !showFilters }
            TeamPill("Обновить", tint = c.textMuted) { tick++ }
        }
        if (showFilters) {
            val d = data
            OpsPicker("Пользователь", listOf("" to "Все пользователи") + (d?.actors.orEmpty()).map { it to it.substringBefore('@') }, filters.actor, { apply(filters.copy(actor = it)) })
            OpsPicker("Тип", listOf("" to "Все типы") + (d?.entityTypes.orEmpty()).map { it to (LogLabels.entity[it] ?: it) }, filters.entityType, { apply(filters.copy(entityType = it)) })
            OpsPicker("Действие", listOf("" to "Все действия") + (d?.actions.orEmpty()).map { it to (LogLabels.actionBadge[it] ?: it) }, filters.action, { apply(filters.copy(action = it)) })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Switch(checked = filters.onlyErrors, onCheckedChange = { apply(filters.copy(onlyErrors = it)) })
                Text("Только ошибки", color = c.textMuted, fontSize = 14.sp)
            }
            TeamPill("Сбросить всё", tint = c.negative) { searchText = ""; apply(LogFilters(includeNoise = filters.includeNoise)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Switch(checked = filters.includeNoise, onCheckedChange = { apply(filters.copy(includeNoise = it)) })
            Column {
                Text("Показывать всё", color = c.text, fontSize = 14.sp)
                Text("Включая просмотры страниц и ИИ-вызовы", color = c.textDim, fontSize = 12.sp)
            }
        }

        val d = data
        if (d != null) {
            val s = logStats(d.items)
            OwnerSection("Сводка") {
                Text("Показано ${s.shown}" + if (d.total > s.shown) " из ${d.total}" else "", color = c.textDim, fontSize = 13.sp)
                val parts = listOf(
                    "продаж" to s.sales, "фин. операций" to s.finance, "долгов" to s.debts, "по сменам" to s.shifts,
                    "по кадрам" to s.staff, "по складу" to s.inventory, "задач/инцидентов" to s.tasks, "уведомлений" to s.notifications,
                ).filter { it.second > 0 }
                if (parts.isNotEmpty()) Text(parts.joinToString(" · ") { "${it.second} ${it.first}" }, color = c.textMuted, fontSize = 14.sp)
                Text(
                    "${s.errors} " + if (s.errors == 1) "ошибка" else "ошибок",
                    color = if (s.errors > 0) c.negative else c.textDim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        TeamError(error)
        if (loading && d == null) TeamHint("Загружаем журнал…")

        d?.items?.forEach { item ->
            LogCard(
                item = item,
                open = expanded == item.id,
                onToggle = { expanded = if (expanded == item.id) null else item.id },
                explanation = explanations[item.id],
                explaining = explaining == item.id,
                canExplain = canExplain,
                copied = copied == item.id,
                onCopy = {
                    copyToClipboard(context, "Запись журнала", prettyJson.encodeToString(JsonObject.serializer(), item.toJson()))
                    copied = item.id
                },
                onExplain = {
                    if (explanations[item.id] == null && explaining == null) {
                        explaining = item.id
                        scope.launch {
                            explanations[item.id] = try {
                                val res = env.api.send<JsonElement>(ApiRequest.json("/api/ai/explain-error", explainErrorBody(item)))
                                ((res as? JsonObject)?.get("explanation") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                                    ?: "Не удалось получить объяснение."
                            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Лимит запросов сайт показывает отдельной фразой.
                                if (e.message?.contains("too-many-requests") == true) "Слишком часто — подождите минуту." else "Не удалось получить объяснение."
                            }
                            explaining = null
                        }
                    }
                },
            )
        }
        if (d != null && d.items.isEmpty() && !loading) TeamHint("Записей по этим фильтрам нет.")
        if (d != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (d.hasPrev) TeamPill("← Назад") { page = maxOf(1, page - 1) } else Text("")
                Text("Стр. ${d.page} · всего ${d.total}", color = c.textDim, fontSize = 13.sp)
                if (d.hasNext) TeamPill("Вперёд →") { page += 1 } else Text("")
            }
        }
    }
}

@Composable
private fun LogCard(
    item: LogItem,
    open: Boolean,
    onToggle: () -> Unit,
    explanation: String?,
    explaining: Boolean,
    canExplain: Boolean,
    copied: Boolean,
    onCopy: () -> Unit,
    onExplain: () -> Unit,
) {
    val c = Orda.colors
    val kindTitle = when (item.kind) { "notification" -> "уведомление"; "ai" -> "ИИ"; else -> "аудит" }
    val kindColor: Color = when (item.kind) { "notification" -> c.positive; "ai" -> c.accent; else -> c.info }
    Column(
        Modifier.fillMaxWidth()
            .background(c.surface, RoundedCornerShape(Radius.lg))
            .clickable(onClick = onToggle)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(kindTitle.uppercase(), color = kindColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            LogLabels.actionBadge[item.action.orEmpty()]?.let { Text(it, color = c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
            if (item.isError) Text("ошибка", color = c.negative, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(logRelativeTime(item.createdAt), color = c.textDim, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Text(logTitle(item), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (open) {
            if (item.detailRows.isNotEmpty()) {
                item.detailRows.forEach { row ->
                    val (label, value) = logDetailRow(row)
                    Text(
                        if (value.isEmpty()) label else "$label: $value",
                        color = c.textMuted, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(Radius.sm)).padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    )
                }
            } else item.details?.let { Text(it, color = c.textDim, fontSize = 13.sp) }
            explanation?.let {
                Column(
                    Modifier.fillMaxWidth().background(c.accent.copy(alpha = 0.10f), RoundedCornerShape(Radius.sm)).padding(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text("Объяснение ИИ", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(it, color = c.text, fontSize = 13.sp)
                }
            }
            item.actorEmail?.let { Text("Кто: $it", color = c.textDim, fontSize = 12.sp) }
            Text("Когда: ${exactTime(item.createdAt)}", color = c.textDim, fontSize = 12.sp)
            (if (item.kind == "notification") item.subtitle ?: item.recipient else item.recipient)?.let { Text("Получатель: $it", color = c.textDim, fontSize = 12.sp) }
            item.channel?.let { Text("Канал: ${LogLabels.channel[it] ?: LogLabels.value(it)}", color = c.textDim, fontSize = 12.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.xs)) {
                TeamPill(if (copied) "Скопировано" else "Копировать JSON", tint = c.textMuted, onClick = onCopy)
                if (item.isError && canExplain) {
                    TeamPill(if (explaining) "ИИ думает…" else "Объяснить", tint = c.accent, onClick = onExplain)
                }
            }
        }
    }
}
