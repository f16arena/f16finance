package kz.ordaops.android.feature.business.store

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.ordaops.android.feature.business.AttentionCard
import kz.ordaops.android.feature.business.HeroColors
import kz.ordaops.android.feature.business.HorizontalRow
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.business.RoundAction
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing
import java.time.LocalDate
import java.time.ZoneId

// Обзор магазина — раздел `store`. Порт `StoreScreen.swift` + «Требует внимания»
// с веб-редизайна 27.10.
//
// Владелец открывает его не «посмотреть остатки», а ответить на два вопроса:
// что кончается и что от меня ждут. Поэтому наверху — то, что требует решения,
// а полный список остатков лежит отдельным разделом.

@Composable
fun StoreOverviewScreen() {
    val env = LocalBusiness.current
    val vm = rememberStoreViewModel()
    val state by vm.overview.collectAsState()
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadOverview() }
    BackHandler(enabled = searching) { searching = false }

    if (searching) {
        StoreSearchScreen(vm)
        return
    }
    StateContainer(
        state = state,
        onRetry = vm::loadOverview,
        emptyTitle = "Склад пуст",
        loadingMessage = "Загружаем склад…",
    ) { overview ->
        OwnerScreenColumn(Modifier.padding(top = Spacing.sm)) {
            // Поиск стоя у полки — вопрос «где эта банка» задают не за столом.
            if (env.can("store.global_search")) SearchTile { searching = true }
            Hero(overview)
            Attention(overview, env.open)
            StockValueCard(overview.metrics)
            LowStockCard(overview) { env.open("store-warehouse") }
            RequestsCard(overview) { env.open("store-requests") }
            MovementsCard(overview) { env.open("store-movements") }
            LocationsCard(overview)
        }
    }
}

@Composable
private fun SearchTile(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Orda.colors.surface)
            .pressScale()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(Icons.Filled.Search, null, tint = Orda.colors.textDim)
        Text("Найти товар, приёмку, заявку", color = Orda.colors.textDim, fontSize = 15.sp)
    }
}

/** Крупная цифра: сколько позиций на складе, и три показателя под ней. */
@Composable
private fun Hero(overview: StoreOverview) {
    val totals = overview.totalsByItem
    val low = totals.count { it.isLow }
    val pending = overview.pendingRequests.size
    val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val today = overview.movements.count { (it.createdAt ?: 0L) >= startOfDay }
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(HeroColors.Accent), shape)
            .padding(Spacing.xl),
    ) {
        Text("Позиций на складе", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text("${totals.size}", color = androidx.compose.ui.graphics.Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(
            if (low > 0) "$low заканчивается — ниже порога" else "всего хватает",
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp,
        )
        Row(Modifier.padding(top = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            HeroStat("Заканчивается", "$low")
            HeroStat("Заявок ждёт", "$pending")
            HeroStat("Движений сегодня", "$today")
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String) {
    Column {
        Text(label, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Text(value, color = androidx.compose.ui.graphics.Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * «Требует внимания». Цвет по смыслу: rose — потеря, amber — ждёт человека,
 * sky — подсобка. Блок рисуется, только если сервер прислал счётчики и есть что показать.
 */
@Composable
private fun Attention(overview: StoreOverview, open: (String) -> Unit) {
    val m = overview.metrics ?: return
    if (!m.needsAttention) return
    OwnerSection("Требует внимания") {
        HorizontalRow {
            if (m.disputedRequests > 0) {
                AttentionCard(Icons.Filled.Warning, StoreTone.waits, "Спорные заявки: ${m.disputedRequests}", "Точка и склад не сошлись", { open("store-requests") })
            }
            if (m.outOfStock > 0) {
                val tail = if (m.outOfStockInBackroom > 0) "${m.outOfStockInBackroom} есть в подсобке — выставить" else "закупить"
                AttentionCard(Icons.Filled.Inventory2, StoreTone.loss, "Нет в наличии: ${m.outOfStock}", tail, { open("store-warehouse") })
            }
            if (m.overdueCount > 0) {
                AttentionCard(Icons.Filled.Payments, StoreTone.loss, "Просрочено: ${money(m.overdueSum)}", "${m.overdueCount} ${plural(m.overdueCount, "накладная", "накладные", "накладных")} поставщикам", { open("store-suppliers") })
            } else if (m.unpaidCount > 0) {
                AttentionCard(Icons.Filled.Payments, StoreTone.waits, "К оплате: ${money(m.unpaidSum)}", "${m.unpaidCount} ${plural(m.unpaidCount, "накладная", "накладные", "накладных")} поставщикам", { open("store-suppliers") })
            }
            if (m.receiptDrafts > 0) {
                AttentionCard(Icons.Filled.PendingActions, StoreTone.waits, "Черновики приёмок: ${m.receiptDrafts}", "Не проведены — остатки не выросли", { open("store-receipts") })
            }
            if (m.unresolvedWriteoffs > 0) {
                AttentionCard(Icons.Filled.Delete, StoreTone.waits, "Списания без причины: ${m.unresolvedWriteoffs}", "Укажите, почему списали", { open("store-writeoffs") })
            }
        }
    }
}

/** Деньги в товаре по закупочной цене: подсобка отдельно от витрин. */
@Composable
private fun StockValueCard(m: StoreMetrics?) {
    if (m == null || m.stockTotal <= 0) return
    OwnerSection("Товара на сумму") {
        Text(money(m.stockTotal), color = Orda.colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        StatLine("Склад и подсобка", money(m.stockWarehouse), StoreTone.backroom)
        StatLine("Витрины", money(m.stockShowcase))
        if (m.stockPositions > 0) StatLine("Позиций с ценой закупки", "${m.stockPositions}")
    }
}

@Composable
private fun LowStockCard(overview: StoreOverview, onAll: () -> Unit) {
    val low = overview.totalsByItem.filter { it.isLow }
    OwnerSection("Заканчивается", trailing = { LinkText("Весь склад", onAll) }) {
        if (low.isEmpty()) {
            InlineEmpty(Icons.Filled.CheckCircle, "Все запасы выше порога", StoreTone.good)
        } else {
            Text("${low.size} ${plural(low.size, "позиция", "позиции", "позиций")} ниже порога", color = Orda.colors.textDim, fontSize = 13.sp)
            low.take(8).forEachIndexed { i, item ->
                if (i > 0) RowDivider()
                StockRow(item.name, item.quantity, item.unit, item.threshold, caption = if (item.locationCount > 1) "в ${item.locationCount} точках" else null)
            }
        }
    }
}

@Composable
private fun RequestsCard(overview: StoreOverview, onAll: () -> Unit) {
    val pending = overview.pendingRequests
    OwnerSection("Заявки точек", trailing = { LinkText("Все", onAll) }) {
        if (pending.isEmpty()) {
            InlineEmpty(Icons.Filled.CheckCircle, "Нет заявок в ожидании", StoreTone.good)
        } else {
            pending.take(5).forEachIndexed { i, r ->
                if (i > 0) RowDivider()
                RequestRow(r, onClick = onAll)
            }
        }
    }
}

@Composable
private fun MovementsCard(overview: StoreOverview, onAll: () -> Unit) {
    OwnerSection("Последние движения", trailing = { LinkText("Все", onAll) }) {
        if (overview.movements.isEmpty()) {
            InlineEmpty(Icons.Filled.Inbox, "Движений пока нет")
        } else {
            overview.movements.take(7).forEachIndexed { i, m ->
                if (i > 0) RowDivider()
                MovementRow(m)
            }
        }
    }
}

@Composable
private fun LocationsCard(overview: StoreOverview) {
    val counts = overview.balances.groupingBy { it.locationId }.eachCount()
    val active = overview.locations.filter { it.isActive }
    OwnerSection("Точки хранения") {
        if (active.isEmpty()) {
            InlineEmpty(Icons.Filled.Warehouse, "Точек не заведено")
        } else {
            active.forEachIndexed { i, loc ->
                if (i > 0) RowDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Icon(locationIcon(loc.kind), null, tint = if (loc.kind == "backroom") StoreTone.backroom else Orda.colors.brand, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(loc.name, color = Orda.colors.text, fontSize = 15.sp)
                        Text(loc.companyName ?: loc.kindLabel, color = Orda.colors.textDim, fontSize = 13.sp)
                    }
                    val n = counts[loc.id] ?: 0
                    Text("$n", color = if (n == 0) Orda.colors.textDim else Orda.colors.textMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

internal fun locationIcon(kind: String) = when (kind) {
    "warehouse" -> Icons.Filled.Warehouse
    "point_display" -> Icons.Filled.Storefront
    "backroom" -> Icons.Filled.MoveToInbox
    "catalog" -> Icons.Filled.Checklist
    else -> Icons.Filled.LocalShipping
}

@Composable
internal fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Orda.colors.brand,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    )
}

/** Строка заявки в списке: точка буквой в кружке, статус цветной подписью. */
@Composable
internal fun RequestRow(request: StockRequest, onClick: () -> Unit) {
    val tint = if (request.isPending) StoreTone.waits else StoreTone.good
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        kz.ordaops.android.feature.business.LetterBadge(request.companyName ?: "Точка", tint)
        Column(Modifier.weight(1f)) {
            Text(request.companyName ?: "Точка", color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(positions(request.lines.size), color = Orda.colors.textDim, fontSize = 13.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            StatusCaption(request.statusLabel, tint)
            Text(fmtDay(request.createdAt), color = Orda.colors.textDim, fontSize = 12.sp)
        }
    }
}

/** Строка движения: тип цветом направления, товар, откуда-куда, количество. */
@Composable
internal fun MovementRow(m: StockMovement) {
    val tint = when (m.direction) {
        1 -> StoreTone.good
        -1 -> StoreTone.loss
        else -> Orda.colors.textMuted
    }
    val route = when {
        m.fromName != null && m.toName != null -> "${m.kindLabel} · ${m.fromName} → ${m.toName}"
        m.fromName != null -> "${m.kindLabel} · из ${m.fromName}"
        m.toName != null -> "${m.kindLabel} · в ${m.toName}"
        else -> m.kindLabel
    }
    val qty = formatQty(m.quantity).let {
        when (m.direction) {
            1 -> "+$it"
            -1 -> "−$it"
            else -> it
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        kz.ordaops.android.feature.operator.money.TintedIcon(
            when (m.direction) {
                1 -> Icons.Filled.MoveToInbox
                -1 -> Icons.Filled.Delete
                else -> Icons.Filled.LocalShipping
            },
            tint,
            40,
            corner = 20.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(m.itemName, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(route, color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(qty, color = if (m.direction == 0) Orda.colors.text else tint, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(fmtHourMinute(m.createdAt), color = Orda.colors.textDim, fontSize = 12.sp)
        }
    }
}
