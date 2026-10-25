package kz.ordaops.android.feature.operator.knowledge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.operator.common.operatorViewModel
import kz.ordaops.android.feature.operator.work.AccentCard
import kz.ordaops.android.feature.operator.work.CenteredContent
import kz.ordaops.android.feature.operator.work.ChipKind
import kz.ordaops.android.feature.operator.work.NoteField
import kz.ordaops.android.feature.operator.work.NoticeCard
import kz.ordaops.android.feature.operator.work.SectionTitle
import kz.ordaops.android.feature.operator.work.StatusChip
import kz.ordaops.android.feature.operator.work.pluralize
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.theme.Navy
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

/**
 * База знаний — `KnowledgeScreen` + экзамены.
 *
 * Сверху два режима: статьи и экзамены. На айфоне экзамены лежат в «Деньгах»,
 * здесь они рядом со статьями — аттестация идёт по этим же регламентам.
 *
 * Статьи: на телефоне список → чтение (панели сменяют друг друга), от 600dp —
 * две панели рядом.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun KnowledgeTab() {
    val vm = operatorViewModel { env -> KnowledgeViewModel(env) }
    val state by vm.state.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refresh() }

    if (mode == 1) {
        Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
            KnowledgeHeader(mode) { mode = it }
            ExamsScreen(showTitle = false)
        }
        return
    }

    val scope = rememberCoroutineScope()
    val info = currentWindowAdaptiveInfo()
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    // Стандартная схема даёт две панели только от 840dp; на 7–10" планшетах хотим от 600.
    val directive = remember(info, wide) {
        val base = calculatePaneScaffoldDirective(info)
        if (wide) base.copy(maxHorizontalPartitions = 2, horizontalPartitionSpacerSize = 16.dp) else base
    }
    val navigator = rememberListDetailPaneScaffoldNavigator<String>(scaffoldDirective = directive)
    BackHandler(enabled = navigator.canNavigateBack()) { scope.launch { navigator.navigateBack() } }
    val dual = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Expanded &&
        navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded
    val selectedId = navigator.currentDestination?.contentKey

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        modifier = Modifier.background(Orda.colors.background),
        listPane = {
            AnimatedPane {
                ArticleListPane(
                    state = state,
                    notice = notice,
                    mode = mode,
                    onMode = { mode = it },
                    search = search,
                    onSearch = { search = it },
                    selectedId = if (dual) selectedId else null,
                    onRetry = vm::refresh,
                    onOpen = { id -> scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) } },
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val article = state.data?.find(selectedId)
                val back: (() -> Unit)? = if (dual) null else ({ scope.launch { navigator.navigateBack() } })
                if (article != null) {
                    ArticleReader(vm, article, canConfirm = state.data?.canConfirm != false, onBack = back)
                } else {
                    Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
                        if (back != null) SectionTitle("Статья", onBack = back)
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyState(
                                icon = Icons.AutoMirrored.Filled.MenuBook,
                                title = "Выберите статью",
                                message = "Текст откроется здесь.",
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun KnowledgeHeader(mode: Int, onMode: (Int) -> Unit) {
    SectionTitle("Знания")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ModeChip("Статьи", mode == 0, Modifier.weight(1f)) { onMode(0) }
        ModeChip("Экзамены", mode == 1, Modifier.weight(1f)) { onMode(1) }
    }
    Box(Modifier.height(Spacing.sm))
}

@Composable
private fun ArticleListPane(
    state: Loadable<KnowledgeUi>,
    notice: String?,
    mode: Int,
    onMode: (Int) -> Unit,
    search: String,
    onSearch: (String) -> Unit,
    selectedId: String?,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Orda.colors.background).imePadding()) {
        KnowledgeHeader(mode, onMode)
        StateContainer(
            state = state,
            onRetry = onRetry,
            modifier = Modifier.weight(1f),
        ) { ui ->
            // Статей десятки, а нужную ищут в конкретной ситуации — «не работает компьютер», «долг клиента».
            val query = search.trim()
            val shown = remember(ui, query) {
                if (query.isEmpty()) ui.articles else ui.articles.filter {
                    it.article.title.contains(query, ignoreCase = true) || (it.article.summary ?: "").contains(query, ignoreCase = true)
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item(key = "summary", contentType = "summary") { KnowledgeSummary(pending = ui.pendingCount, total = ui.total) }
                item(key = "search", contentType = "search") { NoteField(search, onSearch, "Найти статью", minLines = 1, maxLines = 1) }
                notice?.let { n -> item(key = "notice", contentType = "notice") { NoticeCard(n, Orda.colors.warning) } }
                if (shown.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        EmptyState(
                            icon = Icons.AutoMirrored.Filled.MenuBook,
                            title = if (search.isEmpty()) "Статей нет" else "Ничего не нашлось",
                            message = if (search.isEmpty()) "База знаний для вашей точки пока пуста." else "По запросу «$search» статей нет.",
                        )
                    }
                } else {
                    items(shown, key = { it.article.id }, contentType = { "article" }) { article ->
                        ArticleRow(article, selected = article.article.id == selectedId) { onOpen(article.article.id) }
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
private fun ArticleRow(item: ArticleUi, selected: Boolean, onClick: () -> Unit) {
    val article = item.article
    val needsConfirmation = item.needsConfirmation
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
        AccentCard(if (selected) Orda.colors.brand else if (needsConfirmation) Orda.colors.info else null) {
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

/** Текст статьи с разобранной разметкой — `ArticleReader`. Блоки уже разобраны во ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticleReader(vm: KnowledgeViewModel, item: ArticleUi, canConfirm: Boolean, onBack: (() -> Unit)?) {
    val article = item.article
    val confirm by vm.confirm.collectAsStateWithLifecycle()
    var explaining by rememberSaveable { mutableStateOf(false) }
    val mine = confirm.articleId == article.id

    Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
        SectionTitle("Статья", onBack = onBack) {
            // «Объясни проще» — в панели, а не в тексте: за ним тянутся, когда уже прочитали и не поняли.
            TextButton(onClick = { explaining = true }) {
                Icon(Icons.Filled.QuestionAnswer, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Проще")
            }
        }
        // Интервалы больше обычных: регламент читают по диагонали.
        Box(Modifier.weight(1f)) {
            CenteredContent {
                SelectionContainer {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                    ) {
                        item(key = "title", contentType = "title") {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                                Text(article.title, color = Orda.colors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                    if (article.isCritical) StatusChip("важное", ChipKind.Warning)
                                    Text("версия ${article.version}", color = Orda.colors.textDim, fontSize = 13.sp)
                                }
                            }
                        }
                        if (item.blocks.isEmpty()) {
                            item(key = "no-text", contentType = "empty") {
                                Text("У статьи нет текста.", color = Orda.colors.textDim, fontSize = 14.sp)
                            }
                        } else {
                            itemsIndexed(item.blocks, key = { i, _ -> i }, contentType = { _, b -> b.kind }) { _, block ->
                                BlockView(block)
                            }
                        }
                    }
                }
            }
        }

        // Подтверждение закреплено снизу: до конца длинного регламента человек не домотает и уйдёт, не подтвердив.
        if (item.needsConfirmation) {
            Column(
                Modifier.fillMaxWidth().background(Orda.colors.elevated).padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (canConfirm) {
                    if (mine) confirm.error?.let { Text(it, color = Orda.colors.negative, fontSize = 13.sp, modifier = Modifier.fillMaxWidth()) }
                    PrimaryButton("Прочитал и понял", Modifier.fillMaxWidth(), loading = mine && confirm.busy) {
                        vm.confirmArticle(article)
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
            ArticleExplainContent(vm, article.id, article.title) { explaining = false }
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
        // Полоса тянется за высотой текста, а не фиксированные 24dp: при крупном шрифте цитата в несколько строк.
        RichText.Kind.Quote -> Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(Orda.colors.brand.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
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
        RichText.Kind.Image -> AsyncImage(
            model = block.text,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)),
        )
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
private fun ArticleExplainContent(vm: KnowledgeViewModel, articleId: String, title: String, onDone: () -> Unit) {
    val explain by vm.explain.collectAsStateWithLifecycle()
    var question by rememberSaveable { mutableStateOf("") }
    // Ответ уже есть для этой статьи — повторно не просим; поле очищаем только по новому ответу.
    val mine = explain.articleId == articleId
    val answer = if (mine) explain.answer else null
    val seenAnswers = remember { explain.answers }

    fun ask() {
        val q = question.trim()
        vm.explainArticle(articleId, q.ifEmpty { null })
    }

    // Первое объяснение просим сразу: человек открыл лист ровно за этим.
    LaunchedEffect(articleId) { if (!(mine && explain.answer != null)) ask() }
    LaunchedEffect(explain.answers) { if (explain.answers != seenAnswers) question = "" }

    Column(
        Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg).padding(bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        OrdaCard {
            Text("ПРАВИЛО", color = Orda.colors.textDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(title, color = Orda.colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        if (explain.isAsking && answer == null) {
            Box(Modifier.fillMaxWidth().padding(Spacing.lg), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Orda.colors.brand)
            }
        } else if (answer != null) {
            // Ответ приходит списком пунктов — каждая строка отдельно, а не абзацем.
            val lines = remember(answer) { answer.split("\n").map { it.trim() }.filter { it.isNotEmpty() } }
            OrdaCard {
                lines.forEach { Text(it, color = Orda.colors.text, fontSize = 16.sp) }
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

        if (mine) explain.error?.let { Text(it, color = Orda.colors.negative, fontSize = 14.sp) }

        PrimaryButton(
            if (answer == null) "Объяснить проще" else "Спросить",
            Modifier.fillMaxWidth(),
            loading = explain.isAsking,
            onClick = ::ask,
        )
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
    }
}
