package kz.ordaops.android.feature.business.ops

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// «Модерация ИИ» (`/moderation`): сообщения чатов, которые ИИ отметил как
// подозрительные. Запросы: `GET /api/admin/moderation?status=…`, `PATCH` с
// `{ id, status }`. Подтвердить и отклонить — два разных права.

private const val MODERATION = "/api/admin/moderation"

/** Категории нарушений в подписях сайта. */
fun flagCategoryLabel(key: String): String = when (key) {
    "cash_skim" -> "Сговор / кража"
    "data_leak" -> "Утечка данных"
    "harassment" -> "Харассмент"
    "threat" -> "Угрозы"
    "profanity" -> "Грубость"
    else -> "Другое"
}

data class ModerationFlag(
    val id: String,
    val source: String,
    val author: String,
    val text: String,
    val severity: Int,
    val categories: List<String>,
    val summary: String?,
    val status: String,
    val createdAt: String?,
    val note: String?,
) {
    val sourceLabel: String get() = if (source == "team_chat") "Командный чат" else "Личное сообщение"
}

data class ModerationData(val flags: List<ModerationFlag>, val pending: Int) {
    companion object {
        fun parse(root: JsonObject): ModerationData = ModerationData(
            flags = root.arr("flags").map { o ->
                ModerationFlag(
                    id = o.str("id") ?: "", source = o.str("source_table") ?: "", author = o.str("author_name") ?: "Сотрудник",
                    text = o.str("message_text") ?: "", severity = o.int("severity") ?: 0,
                    categories = (o["categories"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty(),
                    summary = o.str("ai_summary"), status = o.str("status") ?: "pending", createdAt = o.str("created_at"), note = o.str("reviewer_note"),
                )
            }.filter { it.id.isNotEmpty() },
            pending = root.int("pendingCount") ?: 0,
        )
    }
}

/** Решение по флагу: `confirmed` — нарушение подтверждено, `dismissed` — всё в порядке. */
fun moderationBody(id: String, confirm: Boolean): JsonObject = buildJsonObject {
    put("id", id); put("status", if (confirm) "confirmed" else "dismissed")
}

@Composable
fun ModerationScreen() {
    val env = LocalBusiness.current
    var tab by rememberSaveable { mutableStateOf("pending") }
    val loader = rememberOpsLoader("moderation:$tab", ApiRequest(MODERATION, query = mapOf("status" to tab)), ModerationData::parse)
    val state by loader.state.collectAsStateWithLifecycle()
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    val pending = state.data?.pending ?: 0

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            PillSegment(
                listOf("pending" to (if (pending > 0) "На рассмотрении ($pending)" else "На рассмотрении"), "confirmed" to "Подтверждённые", "dismissed" to "Отклонённые"),
                tab,
            ) { tab = it }
        }
        submit.error?.let { Box(Modifier.padding(horizontal = Spacing.lg)) { ErrorBanner(it) } }
        StateContainer(
            state = state, onRetry = loader::refresh, modifier = Modifier.weight(1f),
            isEmpty = { it.flags.isEmpty() }, emptyIcon = Icons.Filled.CheckCircle,
            emptyTitle = if (tab == "pending") "Всё чисто" else "Список пуст",
            emptyMessage = if (tab == "pending") "Новых подозрительных сообщений нет." else null,
        ) { data ->
            LazyColumn(
                Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                items(data.flags, key = { it.id }) { flag ->
                    FlagCard(
                        flag,
                        canConfirm = env.can("moderation.confirm"), canDismiss = env.can("moderation.dismiss"), busy = submit.busy,
                    ) { confirm ->
                        submit.run(
                            { env.api.sendUnit(ApiRequest.json(MODERATION, moderationBody(flag.id, confirm), HttpMethod.PATCH)) },
                            { haptics.success(); loader.refresh() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FlagCard(flag: ModerationFlag, canConfirm: Boolean, canDismiss: Boolean, busy: Boolean, onDecide: (Boolean) -> Unit) {
    val c = Orda.colors
    val severityColor = when {
        flag.severity >= 8 -> c.negative
        flag.severity >= 6 -> c.warning
        else -> c.textMuted
    }
    Column(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(24.dp)).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("${flag.severity}", color = severityColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f)) {
                Text(flag.author, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${flag.sourceLabel} · ${dateTimeLabel(flag.createdAt) ?: ""}", color = c.textDim, fontSize = 12.sp)
            }
        }
        if (flag.categories.isNotEmpty()) {
            Text(flag.categories.joinToString(" · ") { flagCategoryLabel(it) }, color = c.warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            flag.text, color = c.text, fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(14.dp)).padding(Spacing.md),
        )
        flag.summary?.takeIf { it.isNotBlank() }?.let {
            Text("Анализ ИИ", color = c.brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(it, color = c.textMuted, fontSize = 14.sp)
        }
        if (flag.status == "pending") {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                if (canDismiss) {
                    OutlinedButton(onClick = { onDecide(false) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Всё ок") }
                }
                if (canConfirm) {
                    Box(Modifier.weight(1f)) { PrimaryButton("Нарушение", Modifier.fillMaxWidth(), enabled = !busy) { onDecide(true) } }
                }
            }
        } else {
            flag.note?.takeIf { it.isNotBlank() }?.let { Text("Заметка: $it", color = c.textDim, fontSize = 12.sp) }
        }
    }
}
