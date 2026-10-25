package kz.ordaops.android.feature.operator.knowledge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.feature.operator.work.AccentCard
import kz.ordaops.android.feature.operator.work.CabinetStore
import kz.ordaops.android.feature.operator.work.ChipKind
import kz.ordaops.android.feature.operator.work.KnowledgeArticle
import kz.ordaops.android.feature.operator.work.NoteField
import kz.ordaops.android.feature.operator.work.NoticeCard
import kz.ordaops.android.feature.operator.work.Refreshable
import kz.ordaops.android.feature.operator.work.SectionTitle
import kz.ordaops.android.feature.operator.work.StatusChip
import kz.ordaops.android.feature.operator.work.pluralize
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.theme.Navy
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

/**
 * База знаний — `KnowledgeScreen` + экзамены.
 *
 * Сверху два режима: статьи и экзамены. На айфоне экзамены лежат в «Деньгах»,
 * здесь они рядом со статьями — аттестация идёт по этим же регламентам.
 */
@Composable
fun KnowledgeTab() {
    val env = LocalOperatorEnv.current
    val context = LocalContext.current
    val store = remember { CabinetStore(env, context) }
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(0) }
    var openedId by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        store.loadCachedKnowledge()
        store.loadKnowledge()
    }

    val knowledge = store.knowledge
    val pendingIds = knowledge?.pendingConfirmations?.map { it.id }?.toSet().orEmpty()

    val opened = openedId?.let { id -> knowledge?.articles?.firstOrNull { it.id == id } ?: knowledge?.pendingConfirmations?.firstOrNull { it.id == id } }
    if (opened != null) {
        BackHandler { openedId = null }
        ArticleReader(store, opened, needsConfirmation = opened.id in pendingIds, onBack = { openedId = null })
        return
    }

    Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
        SectionTitle("Знания")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            ModeChip("Статьи", mode == 0, Modifier.weight(1f)) { mode = 0 }
            ModeChip("Экзамены", mode == 1, Modifier.weight(1f)) { mode = 1 }
        }
        Box(Modifier.height(Spacing.sm))

        if (mode == 1) {
            ExamsScreen(showTitle = false)
            return@Column
        }

        // Все статьи: сначала непрочитанные, потом важные, потом остальные — правила про кассу ищут в спешке.
        val ordered = remember(knowledge) {
            if (knowledge == null) emptyList() else {
                val pending = knowledge.pendingConfirmations
                val ids = pending.map { it.id }.toSet()
                val rest = knowledge.articles.filter { it.id !in ids }
                pending + rest.filter { it.isCritical } + rest.filter { !it.isCritical }
            }
        }
        // Статей десятки, а нужную ищут в конкретной ситуации — «не работает компьютер», «долг клиента».
        val query = search.trim()
        val articles = if (query.isEmpty()) ordered else ordered.filter {
            it.title.contains(query, ignoreCase = true) || (it.summary ?: "").contains(query, ignoreCase = true)
        }

        Refreshable(refreshing = store.isLoadingKnowledge && knowledge != null, onRefresh = { scope.launch { store.loadKnowledge() } }) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (knowledge == null) {
                    item {
                        if (store.isLoadingKnowledge) {
                            Box(Modifier.fillMaxWidth().padding(Spacing.xxl), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Orda.colors.brand)
                            }
                        } else {
                            EmptyState(
                                icon = Icons.AutoMirrored.Filled.MenuBook,
                                title = "Не удалось загрузить",
                                message = store.error ?: "Потяните вниз, чтобы обновить.",
                            )
                        }
                    }
                } else {
                    item { KnowledgeSummary(pending = pendingIds.size, total = knowledge.articles.size) }
                    item { NoteField(search, { search = it }, "Найти статью", minLines = 1, maxLines = 1) }
                    store.deferredNotice?.let { notice -> item { NoticeCard(notice, Orda.colors.warning) } }
                    if (articles.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.AutoMirrored.Filled.MenuBook,
                                title = if (search.isEmpty()) "Статей нет" else "Ничего не нашлось",
                                message = if (search.isEmpty()) "База знаний для вашей точки пока пуста." else "По запросу «$search» статей нет.",
                            )
                        }
                    } else {
                        items(articles, key = { it.id }) { article ->
                            ArticleRow(article, article.id in pendingIds) { openedId = article.id }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(title: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(if (selected) Orda.colors.brand else Orda.colors.surfaceRaised)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = if (selected) Color.White else Orda.colors.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/**
 * Сводка над списком: отвечает на единственный вопрос, с которым сюда заходят
 * в начале смены, — «сколько мне ещё читать».
 */
@Composable
private fun KnowledgeSummary(pending: Int, total: Int) {
    val ratio = if (total > 0) (total - pending).toFloat() / total else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(Navy, Orda.colors.brand)), RoundedCornerShape(Radius.xl))
            .padding(Spacing.xl),
    ) {
        Text(
            if (pending > 0) "Ждут подтверждения" else "Всё прочитано",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        val count = if (pending > 0) pending else total
        Text(
            "$count ${pluralize(count, "статья", "статьи", "статей")}",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        if (total > 0) {
            // Полоса прогресса: «осталось 27» звучит как приговор, «14 из 27» — как работа, которая идёт.
            Box(
                Modifier.padding(top = Spacing.md).fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
            ) {
                Box(Modifier.fillMaxWidth(maxOf(ratio, 0.02f)).height(6.dp).clip(CircleShape).background(Color.White))
            }
            Text(
                "${total - pending} из $total подтверждено",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
        Text(
            if (pending > 0) "Они наверху списка. Откройте и подтвердите — это фиксируется по версии правила."
            else "Пригодится, когда что-то пойдёт не так.",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = Spacing.md),
        )
    }
}

/** Строка статьи: значок слева несёт состояние — непрочитанное синим, важное жёлтым, прочитанное серым. */
@Composable
private fun ArticleRow(article: KnowledgeArticle, needsConfirmation: Boolean, onClick: () -> Unit) {
    val accent = when {
        needsConfirmation -> Orda.colors.info
        article.isCritical -> Orda.colors.warning
        else -> Orda.colors.textDim
    }
    val icon = when {
        needsConfirmation -> Icons.Filled.Error
        article.isCritical -> Icons.Filled.Warning
        else -> Icons.Filled.Description
    }
    Box(Modifier.clip(RoundedCornerShape(Radius.lg)).clickable(onClick = onClick)) {
        AccentCard(if (needsConfirmation) Orda.colors.info else null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(30.dp).background(accent.copy(alpha = 0.12f), RoundedCornerShape(Radius.sm)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp)) }

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(article.title, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    article.summary?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, color = Orda.colors.textMuted, fontSize = 13.sp, maxLines = 2)
                    }
                    if (needsConfirmation) {
                        Text("нужно подтвердить", color = Orda.colors.info, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    } else if (article.isCritical) {
                        Text("важное", color = Orda.colors.warning, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Orda.colors.textDim, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Текст статьи с разобранной разметкой — `ArticleReader`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticleReader(store: CabinetStore, article: KnowledgeArticle, needsConfirmation: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var isConfirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var explaining by remember { mutableStateOf(false) }

    val blocks = remember(article) {
        RichText.blocks(article.text).ifEmpty {
            article.summary?.takeIf { it.isNotEmpty() }?.let { listOf(RichText.Block(RichText.Kind.Paragraph, it)) }.orEmpty()
        }
    }

    Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
        SectionTitle("Статья", onBack = onBack) {
            // «Объясни проще» — в панели, а не в тексте: за ним тянутся, когда уже прочитали и не поняли.
            TextButton(onClick = { explaining = true }) {
                Icon(Icons.Filled.QuestionAnswer, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Проще")
            }
        }
        // Ширина строки ограничена самим телефоном; интервалы больше обычных: регламент читают по диагонали.
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text(article.title, color = Orda.colors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                if (article.isCritical) StatusChip("важное", ChipKind.Warning)
                Text("версия ${article.version}", color = Orda.colors.textDim, fontSize = 13.sp)
            }
            if (blocks.isEmpty()) {
                Text("У статьи нет текста.", color = Orda.colors.textDim, fontSize = 14.sp)
            } else {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) { blocks.forEach { BlockView(it) } }
                }
            }
        }

        // Подтверждение закреплено снизу: до конца длинного регламента человек не домотает и уйдёт, не подтвердив.
        if (needsConfirmation) {
            Column(
                Modifier.fillMaxWidth().background(Orda.colors.elevated).padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (store.knowledge?.canConfirm != false) {
                    error?.let { Text(it, color = Orda.colors.negative, fontSize = 13.sp, modifier = Modifier.fillMaxWidth()) }
                    PrimaryButton("Прочитал и понял", Modifier.fillMaxWidth(), loading = isConfirming) {
                        scope.launch {
                            isConfirming = true
                            error = store.confirmArticle(article)
                            isConfirming = false
                        }
                    }
                    Text(
                        "Подтверждение привязано к версии ${article.version}: если правила изменят, вас попросят прочитать заново.",
                        color = Orda.colors.textMuted,
                        fontSize = 13.sp,
                    )
                } else {
                    // Подтверждение пишется на карточку сотрудника; нет связи с ней — кнопка обещала бы то, чего не будет.
                    Text("Подтвердить прочтение пока нельзя", color = Orda.colors.text, fontWeight = FontWeight.Medium)
                    Text(
                        "Ваш аккаунт не связан с карточкой сотрудника. Скажите руководителю — он свяжет её в разделе «Команда», и подтверждение заработает.",
                        color = Orda.colors.textMuted,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }

    if (explaining) {
        ModalBottomSheet(onDismissRequest = { explaining = false }, containerColor = Orda.colors.background) {
            ArticleExplainContent(store, article) { explaining = false }
        }
    }
}

@Composable
private fun BlockView(block: RichText.Block) {
    when (block.kind) {
        RichText.Kind.Heading -> Text(
            block.text, color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        RichText.Kind.ListItem -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(Modifier.padding(top = 9.dp).size(5.dp).background(Orda.colors.brand, CircleShape))
            Text(block.text, color = Orda.colors.textMuted, fontSize = 16.sp)
        }
        RichText.Kind.Quote -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(Modifier.width(3.dp).height(24.dp).background(Orda.colors.brand.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
            Text(block.text, color = Orda.colors.textMuted, fontSize = 16.sp, fontStyle = FontStyle.Italic)
        }
        // Шапку таблицы показываем подписью: она объясняет колонки, а не содержит правило.
        RichText.Kind.TableHeader -> Text(
            block.cells.joinToString(" · ").uppercase(), color = Orda.colors.textDim, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = Spacing.sm),
        )
        // Две колонки на телефоне не помещаются: левая ячейка — заголовок строки, остальные — текст под ней.
        RichText.Kind.TableRow -> Column(
            Modifier.fillMaxWidth().background(Orda.colors.surfaceRaised, RoundedCornerShape(Radius.md)).padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            block.cells.firstOrNull()?.let { Text(it, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            block.cells.drop(1).forEach { Text(it, color = Orda.colors.textMuted, fontSize = 16.sp) }
        }
        RichText.Kind.Paragraph -> Text(block.text, color = Orda.colors.textMuted, fontSize = 16.sp, lineHeight = 24.sp)
    }
}

/**
 * «Объясни проще» по статье — `ArticleExplainSheet`.
 *
 * Отвечает сервер строго по тексту статьи: если в правиле ответа нет, так и
 * будет сказано — выдуманный порядок действий хуже непонятного.
 */
@Composable
private fun ArticleExplainContent(store: CabinetStore, article: KnowledgeArticle, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<String?>(null) }
    var isAsking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun ask() {
        if (isAsking) return
        scope.launch {
            isAsking = true
            error = null
            val q = question.trim()
            store.explainArticle(article.id, q.ifEmpty { null })
                .onSuccess {
                    answer = it
                    question = ""
                }
                .onFailure { error = it.message }
            isAsking = false
        }
    }

    // Первое объяснение просим сразу: человек открыл лист ровно за этим.
    LaunchedEffect(Unit) { if (answer == null) ask() }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg).padding(bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        OrdaCard {
            Text("ПРАВИЛО", color = Orda.colors.textDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(article.title, color = Orda.colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        val text = answer
        if (isAsking && text == null) {
            Box(Modifier.fillMaxWidth().padding(Spacing.lg), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Orda.colors.brand)
            }
        } else if (text != null) {
            // Ответ приходит списком пунктов — каждая строка отдельно, а не абзацем.
            OrdaCard {
                text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                    Text(it, color = Orda.colors.text, fontSize = 16.sp)
                }
            }
            Text(
                "Это пересказ правила, а не новое правило. Спорные случаи решает руководитель.",
                color = Orda.colors.textMuted,
                fontSize = 13.sp,
            )
        }

        OrdaCard {
            Text("СПРОСИТЬ ПО ЭТОМУ ПРАВИЛУ", color = Orda.colors.textDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            NoteField(question, { question = it }, "Например: а если гость уже ушёл?", minLines = 1, maxLines = 3)
        }

        error?.let { Text(it, color = Orda.colors.negative, fontSize = 14.sp) }

        PrimaryButton(
            if (answer == null) "Объяснить проще" else "Спросить",
            Modifier.fillMaxWidth(),
            loading = isAsking,
            onClick = ::ask,
        )
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
    }
}
