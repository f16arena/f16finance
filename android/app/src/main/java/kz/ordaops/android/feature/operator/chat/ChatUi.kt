package kz.ordaops.android.feature.operator.chat

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val ru = Locale("ru", "RU")

// ── Время и дни ──────────────────────────────────────────────────────────────

internal fun timeLabel(ms: Long?): String =
    ms?.let { DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: ""

private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

/** «Сегодня», «Вчера», день недели внутри недели, иначе «5 октября». */
internal fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    val days = java.time.temporal.ChronoUnit.DAYS.between(day, today)
    return when {
        days == 0L -> "Сегодня"
        days == 1L -> "Вчера"
        days in 2..6 -> day.dayOfWeek.getDisplayName(TextStyle.FULL, ru).replaceFirstChar { it.uppercase() }
        else -> DateTimeFormatter.ofPattern("d MMMM", ru).format(day)
    }
}

internal fun shortDate(ms: Long?): String =
    ms?.let { DateTimeFormatter.ofPattern("d MMM", ru).format(dayOf(it)) } ?: ""

/** Разложить ленту по дням, сохранив порядок. Элементы без времени пропускаем, как на айфоне. */
internal fun <T> groupByDay(items: List<T>, ms: (T) -> Long?): List<Pair<LocalDate, List<T>>> {
    val result = LinkedHashMap<LocalDate, MutableList<T>>()
    for (item in items) {
        val moment = ms(item) ?: continue
        result.getOrPut(dayOf(moment)) { mutableListOf() }.add(item)
    }
    return result.map { it.key to it.value }
}

// ── Картинки ─────────────────────────────────────────────────────────────────

private val imageHttp = OkHttpClient()
private val imageCache = LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(40)

/** Картинка по публичной ссылке (хранилище вложений открыто). Нет библиотеки — тянем сами. */
@Composable
internal fun rememberRemoteImage(url: String?): androidx.compose.ui.graphics.ImageBitmap? {
    var image by remember(url) { mutableStateOf(url?.let { imageCache.get(it) }) }
    LaunchedEffect(url) {
        if (url.isNullOrEmpty() || image != null) return@LaunchedEffect
        image = withContext(Dispatchers.IO) {
            runCatching {
                imageHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val bytes = r.body?.bytes() ?: return@use null
                    // Чат живёт сутки, но фото с камеры тяжёлые: уменьшаем при чтении.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 1600) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?.asImageBitmap()
                }
            }.getOrNull()?.also { imageCache.put(url, it) }
        }
    }
    return image
}

@Composable
internal fun Avatar(name: String, avatarUrl: String? = null, side: Int = 32, tint: Color = Orda.colors.info) {
    val image = rememberRemoteImage(avatarUrl)
    Box(
        Modifier.size(side.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, null, Modifier.size(side.dp), contentScale = ContentScale.Crop)
        } else {
            Text(ChatText.initials(name), color = tint, fontSize = (side * 0.38f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun DayDivider(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), contentAlignment = Alignment.Center) {
        Text(
            label,
            color = Orda.colors.textDim,
            fontSize = 12.sp,
            modifier = Modifier.background(Orda.colors.surfaceRaised, CircleShape).padding(horizontal = Spacing.md, vertical = 3.dp),
        )
    }
}

// ── Пузырь сообщения ─────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatBubble(
    text: String,
    senderName: String,
    roleLabel: String?,
    timeMs: Long?,
    isMine: Boolean,
    isEdited: Boolean,
    isPending: Boolean,
    showsSender: Boolean,
    showsAvatar: Boolean,
    avatarUrl: String?,
    attachments: List<ChatAttachment>,
    replyPreview: String? = null,
    reactions: List<ReactionGroup> = emptyList(),
    poll: ChatPoll? = null,
    onOpenSender: (() -> Unit)? = null,
    onVote: ((String) -> Unit)? = null,
    onReact: ((String) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val colors = Orda.colors
    val bubbleColor = if (isMine) colors.brand else colors.surfaceRaised
    val textColor = if (isMine) Color.White else colors.text
    val subColor = if (isMine) Color.White.copy(alpha = 0.75f) else colors.textDim
    val uriHandler = LocalUriHandler.current

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isMine && showsAvatar) {
            Box(Modifier.padding(end = Spacing.sm)) {
                Avatar(senderName, avatarUrl, 30)
            }
        } else if (!isMine) {
            Box(Modifier.padding(end = Spacing.sm).size(30.dp))
        }

        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(Radius.md))
                .background(bubbleColor)
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (showsSender && !isMine) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = if (onOpenSender != null) Modifier.clickable(onClick = onOpenSender) else Modifier,
                ) {
                    Text(senderName, color = colors.brandBright, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (roleLabel != null) Text(roleLabel, color = colors.textDim, fontSize = 11.sp)
                }
            }

            if (replyPreview != null) {
                Text(
                    replyPreview,
                    color = subColor,
                    fontSize = 12.sp,
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                        .padding(horizontal = Spacing.sm, vertical = 4.dp),
                )
            }

            attachments.filter { !it.isPoll }.forEach { att ->
                if (att.isImage) {
                    val image = rememberRemoteImage(att.url)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp, max = 260.dp)
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(Color.Black.copy(alpha = 0.08f))
                            .clickable { runCatching { uriHandler.openUri(att.url) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (image != null) {
                            Image(image, att.label, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                        } else {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = subColor)
                        }
                    }
                } else {
                    // Голосовое и документы открываем ссылкой: плеер и просмотрщик есть в системе.
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(Color.Black.copy(alpha = 0.10f))
                            .clickable { runCatching { uriHandler.openUri(att.url) } }
                            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Icon(Icons.Filled.AttachFile, null, tint = textColor, modifier = Modifier.size(18.dp))
                        Text(att.label, color = textColor, fontSize = 14.sp, maxLines = 1)
                    }
                }
            }

            if (text.isNotEmpty()) Text(text, color = textColor, fontSize = 15.sp)

            if (poll != null) PollCard(poll, onVote)

            if (reactions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    reactions.forEach { r ->
                        Text(
                            "${r.emoji} ${r.count}",
                            color = textColor,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.12f))
                                .clickable(enabled = onReact != null) { onReact?.invoke(r.emoji) }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Text(
                buildString {
                    if (isEdited) append("изм. ")
                    append(if (isPending) "отправляется…" else timeLabel(timeMs))
                },
                color = subColor,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** Опрос: полоса доли, число голосов и кто проголосовал — голоса не тайные. */
@Composable
private fun PollCard(poll: ChatPoll, onVote: ((String) -> Unit)?) {
    val colors = Orda.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(Radius.sm))
            .padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(poll.poll.question, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        poll.poll.options.forEach { option ->
            val voted = poll.hasVoted(option)
            val names = poll.voters[option.id].orEmpty()
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, if (voted) colors.brand else colors.border, RoundedCornerShape(8.dp))
                    .clickable(enabled = onVote != null) { onVote?.invoke(option.id) },
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .fillMaxWidth(poll.share(option).coerceIn(0f, 1f))
                            .height(34.dp)
                            .background(colors.brand.copy(alpha = 0.18f)),
                    )
                    Row(
                        Modifier.fillMaxWidth().height(34.dp).padding(horizontal = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            (if (voted) "✓ " else "") + option.label,
                            color = colors.text,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Text("${poll.count(option)}", color = colors.textMuted, fontSize = 13.sp)
                    }
                }
                if (names.isNotEmpty()) {
                    Text(
                        names.joinToString(", "),
                        color = colors.textDim,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                    )
                }
            }
        }
        Text("Всего голосов: ${poll.totalVotes}", color = colors.textDim, fontSize = 11.sp)
    }
}

// ── Поле ввода ───────────────────────────────────────────────────────────────

/**
 * Поле ввода с вложением и (в командном чате) опросом.
 * Файл читаем сами и отдаём байтами: сервер ждёт multipart, как с сайта.
 */
@Composable
internal fun ChatComposer(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    isSending: Boolean,
    errorText: String?,
    onSend: () -> Unit,
    onPickFile: suspend (bytes: ByteArray, name: String, mime: String, kind: String) -> Unit,
    onPoll: (() -> Unit)? = null,
) {
    val colors = Orda.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val mime = resolver.getType(uri) ?: "application/octet-stream"
                    var name = "файл"
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) name = c.getString(0) ?: name
                    }
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    Triple(bytes, name, mime)
                }.getOrNull()
            }
            if (picked == null || picked.first.isEmpty()) {
                pickError = "Не удалось прочитать файл."
                return@launch
            }
            // Сервер принимает до 50 МБ — не гоним заведомо отказ по сети.
            if (picked.first.size > 50 * 1024 * 1024) {
                pickError = "Файл слишком большой (макс 50 МБ)."
                return@launch
            }
            pickError = null
            val (bytes, name, mime) = picked
            val kind = when {
                mime.startsWith("image/") -> "image"
                mime.startsWith("audio/") -> "audio"
                else -> "file"
            }
            onPickFile(bytes, name, mime, kind)
        }
    }

    Column(Modifier.fillMaxWidth().background(colors.elevated).imePadding()) {
        val error = pickError ?: errorText
        if (error != null) {
            Text(
                error,
                color = colors.negative,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = { picker.launch("*/*") }, enabled = !isSending) {
                Icon(Icons.Filled.AttachFile, "Прикрепить файл", tint = colors.textMuted)
            }
            if (onPoll != null) {
                IconButton(onClick = onPoll, enabled = !isSending) {
                    Icon(Icons.Filled.Poll, "Опрос", tint = colors.textMuted)
                }
            }
            TextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text(placeholder, color = colors.textDim, fontSize = 15.sp) },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                shape = RoundedCornerShape(Radius.md),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surfaceRaised,
                    unfocusedContainerColor = colors.surfaceRaised,
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                    cursorColor = colors.brand,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            if (isSending) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = colors.brand)
                }
            } else {
                IconButton(onClick = onSend, enabled = text.isNotBlank()) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        "Отправить",
                        tint = if (text.isNotBlank()) colors.brand else colors.textDim,
                    )
                }
            }
        }
    }
}

/** Плашка «отвечаем на…» над полем ввода. */
@Composable
internal fun ReplyBar(senderName: String, preview: String, onClose: () -> Unit) {
    val colors = Orda.colors
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Ответ: $senderName", color = colors.brandBright, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(preview, color = colors.textDim, fontSize = 12.sp, maxLines = 1)
        }
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, "Отменить ответ", tint = colors.textMuted, modifier = Modifier.size(18.dp))
        }
    }
}
