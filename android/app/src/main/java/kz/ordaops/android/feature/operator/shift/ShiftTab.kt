package kz.ordaops.android.feature.operator.shift

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.ui.components.LoadingView
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Navy
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HeroAccent = listOf(Color(0xFF2563EB), Color(0xFF1D4ED8))
private val HeroNavy = listOf(Color(0xFF0B1F3B), Color(0xFF1B3B6E))

/**
 * Главный экран оператора — дашборд смены (OperatorHomeScreen.swift). Порядок
 * блоков — как нужно человеку за стойкой: состояние смены и выручка, быстрые
 * действия, всё, что «требует меня», и только затем справочное.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiftTab() {
    val env = LocalOperatorEnv.current
    val store = rememberOperatorStore()
    val scope = rememberCoroutineScope()
    var showOpen by remember { mutableStateOf(false) }
    var showClose by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    // Тикер для «идёт 2 ч 15 мин»: пересчитываем раз в полминуты.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(store) {
        store.loadOverview()
        store.loadTasks()
    }
    // Сводку кабинета догоняет этот экран, саму смену — хранилище: её состояние нужно на всех вкладках.
    LaunchedEffect(store) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
            store.loadOverview()
        }
    }
    // Каталог нужен кассе (и графику выручки): у клуба его незачем даже тянуть.
    LaunchedEffect(store.hasOpenShift, store.sellsGoods) {
        if (store.hasOpenShift && store.sellsGoods && store.recentSales.isEmpty()) store.loadCatalog()
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                store.loadShift()
                store.loadOverview()
                store.loadTasks()
                if (store.hasOpenShift && store.sellsGoods) store.loadCatalog()
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize().background(Orda.colors.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Header(store)
            if (store.queuedSalesCount > 0) OfflineBanner(store.queuedSalesCount) { scope.launch { store.flushQueue() } }
            ShiftHero(store, now, onOpen = { showOpen = true }, onClose = { showClose = true }, onRetry = { scope.launch { store.loadShift() } })
            QuickActions(store.sellsGoods, env.open)
            Attention(store, env.open)
            if (store.isMyShift && store.recentSales.isNotEmpty()) ShiftMoney(store)
            WeekCard(store) { env.open("Money") }
            store.overview?.nextShift?.let { next -> if (!store.hasOpenShift) NextShiftRow(next) }
        }
    }

    if (showOpen) OpenShiftSheet(store) { showOpen = false }
    if (showClose) CloseShiftSheet(store) { showClose = false }
}

// ── Шапка ────────────────────────────────────────────────────────────────────

/** Имя, а не фамилия: «Сарсенгазинова Али…» крупным заголовком не помещалось. */
private fun firstName(full: String): String {
    val words = full.split(" ").filter { it.isNotEmpty() && '@' !in it }
    val first = words.firstOrNull() ?: return full
    val endings = listOf(
        "ов", "ев", "ёв", "ин", "ын", "ова", "ева", "ёва", "ина", "ына", "ский", "ская", "цкий", "цкая",
        "енко", "ук", "юк", "ян", "дзе", "швили", "улы", "ұлы", "кызы", "қызы", "бек", "баев", "баева",
    )
    val looksLikeSurname = first.length > 3 && endings.any { first.lowercase().endsWith(it) }
    return if (words.size >= 2 && looksLikeSurname) words[1] else first
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Доброе утро"
    in 12..17 -> "Добрый день"
    in 18..22 -> "Добрый вечер"
    else -> "Доброй ночи"
}

@Composable
private fun Header(store: OperatorStore) {
    val env = LocalOperatorEnv.current
    val session = env.resolver.session
    val fullName = store.overview?.operatorName ?: session.displayName ?: "Оператор"
    val initials = fullName.split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }
        .ifEmpty { "О" }.uppercase()
    Row(
        Modifier.fillMaxWidth().padding(top = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            Modifier.size(44.dp).background(Brush.linearGradient(HeroAccent), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(greeting(), color = Orda.colors.textDim, fontSize = 13.sp)
            Text(
                firstName(fullName),
                color = Orda.colors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        session.roleLabel?.takeIf { it.isNotEmpty() }?.let { role ->
            Text(
                role,
                color = Orda.colors.brand,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .background(Orda.colors.brand.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

// ── Карточка смены ───────────────────────────────────────────────────────────

private fun elapsed(sinceMillis: Long, now: Long): String {
    val seconds = ((now - sinceMillis) / 1000).coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "$hours ч $minutes мин" else "$minutes мин"
}

@Composable
private fun ShiftHero(store: OperatorStore, now: Long, onOpen: () -> Unit, onClose: () -> Unit, onRetry: () -> Unit) {
    val shift = store.shift
    when {
        store.isLoadingShift && store.shiftState == null -> Box(Modifier.fillMaxWidth().height(180.dp)) { LoadingView() }
        store.shiftError != null && store.shiftState == null -> OrdaCard {
            Text(store.shiftError.orEmpty(), color = Orda.colors.negative)
            HeroButton("Повторить", Icons.Filled.Sync, prominent = false, dark = true, onClick = onRetry)
        }
        // Чужая смена: имя того, кто стоит, и ничего больше — ни выручки, ни кнопки закрытия.
        store.isSomeoneElsesShift -> Hero(HeroNavy) {
            HeroLabel("На смене другой")
            Text(
                shift?.operatorName ?: "Сменщик",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            shift?.openedAtMillis?.let {
                Text("на смене ${elapsed(it, now)}", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
            }
            Text(
                "Выручку и закрытие смены видит тот, кто её открыл.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
        store.hasOpenShift -> Hero(HeroAccent) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HeroLabel("Смена идёт")
                Spacer(Modifier.weight(1f))
                shift?.openedAtMillis?.let {
                    Text(elapsed(it, now), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                money(store.totals.netTotal),
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            Text("выручка смены", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
            Row(Modifier.padding(top = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                HeroStat("Чеков", "${store.totals.salesCount}")
                HeroStat("Наличные", money(store.totals.expectedCash))
                HeroStat("Kaspi", money(store.totals.expectedKaspi))
            }
            Box(Modifier.padding(top = Spacing.lg)) { HeroButton("Закрыть смену", Icons.Filled.Lock, prominent = false, onClick = onClose) }
        }
        else -> Hero(HeroNavy) {
            HeroLabel("Смена закрыта")
            Text(
                "Начните смену",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            Text(
                "Пока смена не открыта, продавать нельзя. Открытие проверит, стоите ли вы сегодня в графике.",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Box(Modifier.padding(top = Spacing.lg)) { HeroButton("Открыть смену", Icons.Filled.PlayArrow, prominent = true, onClick = onOpen) }
        }
    }
}

@Composable
private fun Hero(colors: List<Color>, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.xl))
            .background(Brush.linearGradient(colors))
            .padding(Spacing.xl),
        content = content,
    )
}

@Composable
private fun HeroLabel(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun HeroStat(label: String, value: String) {
    Column {
        Text(label, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun HeroButton(title: String, icon: ImageVector, prominent: Boolean, dark: Boolean = false, onClick: () -> Unit) {
    val fg = if (prominent) Navy else if (dark) Orda.colors.text else Color.White
    val bg = if (prominent) Color.White else if (dark) Orda.colors.surfaceRaised else Color.White.copy(alpha = 0.18f)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(title, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Быстрые действия ─────────────────────────────────────────────────────────

/** Круглые кнопки. Продажа и ревизия — работа магазина; оператору клуба вместо них задачи. */
@Composable
private fun QuickActions(sellsGoods: Boolean, open: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (sellsGoods) {
            RoundAction(Icons.Filled.QrCodeScanner, "Продать", Modifier.weight(1f)) { open("Sale") }
            RoundAction(Icons.Filled.Inventory, "Ревизия", Modifier.weight(1f)) { open("Audit") }
        } else {
            RoundAction(Icons.Filled.Checklist, "Задачи", Modifier.weight(1f)) { open("Tasks") }
        }
        RoundAction(Icons.Filled.Verified, "Чек-листы", Modifier.weight(1f)) { open("Checklists") }
        RoundAction(Icons.AutoMirrored.Filled.MenuBook, "База", Modifier.weight(1f)) { open("Knowledge") }
        RoundAction(Icons.Filled.AccountBalanceWallet, "Деньги", Modifier.weight(1f)) { open("Money") }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, title: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(52.dp).background(Orda.colors.brand.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Orda.colors.brand, modifier = Modifier.size(24.dp))
        }
        Text(title, color = Orda.colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

// ── Требует внимания ─────────────────────────────────────────────────────────

/** Карточками в ряд: каждая — одно дело и куда нажать. */
@Composable
private fun Attention(store: OperatorStore, open: (String) -> Unit) {
    val debts = store.overview?.counters
    val blocking = store.blockingChecklists
    val articles = store.pendingArticles
    val overdue = store.overdueCount
    val hasDebt = (debts?.activeDebts ?: 0) > 0
    if (blocking.isEmpty() && articles.isEmpty() && overdue == 0 && !hasDebt) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (blocking.isNotEmpty()) {
            AttentionCard(
                Icons.Filled.Checklist, Orda.colors.warning,
                "${blocking.size} ${pluralize(blocking.size, "чек-лист", "чек-листа", "чек-листов")} до закрытия",
                "без них смену не закрыть",
            ) { open("Checklists") }
        }
        if (articles.isNotEmpty()) {
            AttentionCard(
                Icons.AutoMirrored.Filled.MenuBook, Orda.colors.info,
                "Прочитать ${articles.size} ${pluralize(articles.size, "правило", "правила", "правил")}",
                "новое в базе знаний",
            ) { open("Knowledge") }
        }
        if (overdue > 0) {
            AttentionCard(
                Icons.Filled.Schedule, Orda.colors.negative,
                "$overdue ${pluralize(overdue, "задача просрочена", "задачи просрочены", "задач просрочено")}",
                "откройте и отметьте",
            ) { open("Tasks") }
        }
        if (debts != null && debts.activeDebts > 0) {
            AttentionCard(
                Icons.Filled.CreditCard, Orda.colors.negative,
                "Долг перед точкой",
                money(debts.activeDebtAmount),
            ) { open("Money") }
        }
    }
}

@Composable
private fun AttentionCard(icon: ImageVector, tint: Color, title: String, subtitle: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radius.lg)
    Row(
        Modifier
            .width(250.dp)
            .background(Orda.colors.surface, shape)
            .border(1.dp, tint.copy(alpha = 0.25f), shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Column {
            Text(title, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(subtitle, color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1)
        }
    }
}

// ── Деньги смены ─────────────────────────────────────────────────────────────

private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Накопленная выручка по времени чеков — видно темп смены. Сервер отдаёт последние 20 продаж. */
@Composable
private fun ShiftMoney(store: OperatorStore) {
    val points = remember(store.recentSales) {
        var running = 0.0
        store.recentSales
            .mapNotNull { s -> s.soldAtMillis?.let { it to s.totalAmount } }
            .sortedBy { it.first }
            .map { (at, amount) -> running += amount; at to running }
    }
    val totals = store.totals
    OrdaCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Выручка по чекам", color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "${totals.salesCount} ${pluralize(totals.salesCount, "чек", "чека", "чеков")}",
                color = Orda.colors.textDim,
                fontSize = 13.sp,
            )
        }
        if (points.size >= 2) {
            TrendChart(points.map { it.second }, Orda.colors.brandBright)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val zone = ZoneId.systemDefault()
                Text(TimeFormat.format(Instant.ofEpochMilli(points.first().first).atZone(zone)), color = Orda.colors.textDim, fontSize = 12.sp)
                Text(TimeFormat.format(Instant.ofEpochMilli(points.last().first).atZone(zone)), color = Orda.colors.textDim, fontSize = 12.sp)
            }
        }
        if (totals.returnsCount > 0) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Возвраты", color = Orda.colors.textMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(
                    "${totals.returnsCount} · ${money(totals.returnsTotal)}",
                    color = Orda.colors.warning,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun TrendChart(values: List<Double>, color: Color) {
    val max = values.max().coerceAtLeast(1.0)
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val stepX = size.width / (values.size - 1)
        fun point(i: Int) = Offset(i * stepX, size.height - (values[i] / max).toFloat() * (size.height - 8.dp.toPx()) - 4.dp.toPx())
        val line = Path().apply {
            moveTo(point(0).x, point(0).y)
            for (i in 1 until values.size) lineTo(point(i).x, point(i).y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ── Неделя ───────────────────────────────────────────────────────────────────

@Composable
private fun WeekCard(store: OperatorStore, onClick: () -> Unit) {
    val week = store.overview?.week
    if (week == null) {
        if (store.isLoadingOverview) Box(Modifier.fillMaxWidth().height(90.dp)) { LoadingView() }
        return
    }
    val shape = RoundedCornerShape(Radius.lg)
    val paid = week.status == "paid"
    Row(
        Modifier
            .fillMaxWidth()
            .background(Orda.colors.surface, shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).background(Orda.colors.positive.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, tint = Orda.colors.positive)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Заработано за неделю", color = Orda.colors.textDim, fontSize = 14.sp, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(money(week.netAmount), color = Orda.colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    week.statusLabel,
                    color = if (paid) Orda.colors.positive else Orda.colors.textMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background((if (paid) Orda.colors.positive else Orda.colors.textDim).copy(alpha = 0.12f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            // Остаток — только если часть уже выплачена: иначе он равен заработанному и читается как расхождение.
            if (week.paidAmount > 0 && week.remainingAmount > 0) {
                Text("осталось получить ${money(week.remainingAmount)}", color = Orda.colors.textDim, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun NextShiftRow(next: NextShift) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Orda.colors.surface, RoundedCornerShape(Radius.lg))
            .padding(Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).background(Orda.colors.brand.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = Orda.colors.brand)
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Ближайшая смена", color = Orda.colors.textDim, fontSize = 14.sp)
            Text(next.label ?: next.date, color = Orda.colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Офлайн ───────────────────────────────────────────────────────────────────

@Composable
private fun OfflineBanner(count: Int, onSend: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Orda.colors.surface, RoundedCornerShape(Radius.lg))
            .padding(Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).background(Orda.colors.warning.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Sync, contentDescription = null, tint = Orda.colors.warning)
        }
        Column(Modifier.weight(1f)) {
            Text(
                "$count ${pluralize(count, "чек", "чека", "чеков")} ждёт отправки",
                color = Orda.colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text("Продажи сохранены на устройстве.", color = Orda.colors.textDim, fontSize = 13.sp)
        }
        Text(
            "Отправить",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .background(Orda.colors.brand, RoundedCornerShape(50))
                .clickable(onClick = onSend)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
