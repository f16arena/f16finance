package kz.ordaops.android.feature.business.ops

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// «Лента» (`/news`): новости и объявления организации. Читают все, публикует тот,
// у кого есть право `news.create`, удаляет автор или обладатель `news.delete`.
// Запросы: GET/POST/DELETE `/api/news`. Новый пост сервер рассылает пушем сам.

private const val NEWS = "/api/news"

/** Пост ленты. */
data class NewsPost(
    val id: String,
    val author: String,
    val title: String?,
    val body: String,
    val imageUrl: String?,
    val linkUrl: String?,
    val linkLabel: String?,
    val createdAt: String?,
    val viewed: Boolean,
) {
    /** Подпись ссылки: своя, иначе сам адрес. */
    val linkText: String? get() = linkUrl?.let { linkLabel?.takeIf { l -> l.isNotBlank() } ?: it }
}

data class NewsFeed(val posts: List<NewsPost>, val unread: Int, val canPublish: Boolean) {
    companion object {
        fun parse(root: JsonObject): NewsFeed {
            val posts = root.arr("posts").map { o ->
                NewsPost(
                    id = o.str("id") ?: "", author = o.str("author_name") ?: "Владелец",
                    title = o.str("title")?.takeIf { it.isNotBlank() }, body = o.str("body") ?: "",
                    imageUrl = o.str("image_url"), linkUrl = o.str("link_url"), linkLabel = o.str("link_label"),
                    createdAt = o.str("created_at"), viewed = o.bool("viewed") ?: true,
                )
            }.filter { it.id.isNotEmpty() }
            return NewsFeed(posts, root.int("unreadCount") ?: posts.count { !it.viewed }, root.bool("canPublish") ?: false)
        }
    }
}

const val NewsMaxLength = 2000

/** Что мешает опубликовать: пост без текста и фото пуст, длинный сервер не примет. */
fun newsProblem(body: String): String? = when {
    body.isBlank() -> "Напишите текст поста"
    body.trim().length > NewsMaxLength -> "Слишком длинный текст: не больше $NewsMaxLength знаков"
    else -> null
}

fun newsBody(title: String, body: String, linkUrl: String, linkLabel: String): JsonObject = buildJsonObject {
    if (title.isNotBlank()) put("title", title.trim())
    put("body", body.trim())
    if (linkUrl.isNotBlank()) {
        put("linkUrl", linkUrl.trim())
        if (linkLabel.isNotBlank()) put("linkLabel", linkLabel.trim())
    }
}

fun newsDeleteBody(id: String): JsonObject = buildJsonObject { put("id", id) }

@Composable
fun NewsScreen() {
    val env = LocalBusiness.current
    val loader = rememberOpsLoader("news", ApiRequest(NEWS), NewsFeed::parse)
    val state by loader.state.collectAsStateWithLifecycle()
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    var composing by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<NewsPost?>(null) }
    val uri = LocalUriHandler.current

    Box(Modifier.fillMaxSize()) {
        StateContainer(
            state = state, onRetry = loader::refresh,
            isEmpty = { it.posts.isEmpty() }, emptyIcon = Icons.Filled.Newspaper,
            emptyTitle = "Лента пуста", emptyMessage = "Здесь появятся новости и объявления компании.",
        ) { feed ->
            val canPublish = feed.canPublish && env.can("news.create")
            val canDelete = feed.canPublish && env.can("news.delete")
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                submit.error?.let { item(key = "err") { ErrorBanner(it) } }
                items(feed.posts, key = { it.id }) { post ->
                    Column(
                        Modifier.fillMaxWidth().background(Orda.colors.surface, RoundedCornerShape(24.dp)).padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            Box(Modifier.size(36.dp).background(Orda.colors.brand.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                                Text(post.author.take(1).uppercase(), color = Orda.colors.brand, fontWeight = FontWeight.Bold)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(post.author, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    (dateTimeLabel(post.createdAt) ?: "") + if (!post.viewed) " · новое" else "",
                                    color = if (post.viewed) Orda.colors.textDim else Orda.colors.brand, fontSize = 12.sp,
                                )
                            }
                            if (canDelete) {
                                IconButton(onClick = { deleting = post }) { Icon(Icons.Filled.Delete, "Удалить", tint = Orda.colors.negative) }
                            }
                        }
                        post.title?.let { Text(it, color = Orda.colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                        post.imageUrl?.let {
                            AsyncImage(it, null, Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
                        }
                        if (post.body.isNotBlank()) Text(post.body, color = Orda.colors.text, fontSize = 15.sp)
                        post.linkText?.let { text ->
                            Text(text, color = Orda.colors.info, fontSize = 14.sp, modifier = Modifier.clickableLink { post.linkUrl?.let { u -> runCatching { uri.openUri(u) } } })
                        }
                    }
                }
            }
        }
        val feed = state.data
        if (feed != null && feed.canPublish && env.can("news.create")) {
            BoxScopeFab(Modifier.align(Alignment.BottomEnd), description = "Новый пост") { composing = true }
        }
    }

    if (composing) NewsComposeSheet(onDismiss = { composing = false }, onDone = { composing = false; loader.refresh() })
    deleting?.let { post ->
        OpsConfirm(
            "Удалить пост?", "Пост пропадёт из ленты у всех.", "Удалить",
            onConfirm = {
                deleting = null
                submit.run(
                    { env.api.sendUnit(ApiRequest.json(NEWS, newsDeleteBody(post.id), HttpMethod.DELETE)) },
                    { haptics.success(); loader.refresh() },
                )
            },
            onDismiss = { deleting = null },
        )
    }
}

private fun Modifier.clickableLink(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))

@Composable
private fun NewsComposeSheet(onDismiss: () -> Unit, onDone: () -> Unit) {
    val env = LocalBusiness.current
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    var linkLabel by rememberSaveable { mutableStateOf("") }
    val problem = newsProblem(body)
    OrdaSheet("Новый пост", onDismiss) {
        submit.error?.let { ErrorBanner(it) }
        OpsField("Заголовок", title, { title = it }, placeholder = "Необязательно", singleLine = true)
        OpsField("Текст", body, { body = it }, minLines = 4)
        Text("${body.trim().length} из $NewsMaxLength", color = Orda.colors.textDim, fontSize = 12.sp)
        OpsField("Ссылка", link, { link = it }, placeholder = "https://…", singleLine = true, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri)
        if (link.isNotBlank()) OpsField("Подпись ссылки", linkLabel, { linkLabel = it }, singleLine = true)
        problem?.let { if (body.isNotEmpty()) Text(it, color = Orda.colors.warning, fontSize = 13.sp) }
        Text("Сотрудники получат пуш-уведомление.", color = Orda.colors.textDim, fontSize = 13.sp)
        PrimaryButton("Опубликовать", Modifier.fillMaxWidth(), enabled = problem == null, loading = submit.busy) {
            submit.run(
                { env.api.sendUnit(ApiRequest.json(NEWS, newsBody(title, body, link, linkLabel))) },
                { haptics.success(); onDone() },
            )
        }
    }
}
