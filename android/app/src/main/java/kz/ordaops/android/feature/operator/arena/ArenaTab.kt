package kz.ordaops.android.feature.operator.arena

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kz.ordaops.android.feature.operator.common.operatorViewModel
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import kotlin.math.abs

/** Элемент сетки зала: заголовок зоны на всю ширину или плитка станции. */
@Immutable
private sealed interface HallEntry {
    val key: String

    @Immutable
    data class Header(val id: String, val title: String) : HallEntry {
        override val key get() = "zone-$id"
    }

    @Immutable
    data class Tile(val station: ArenaStation, val session: ArenaSession?) : HallEntry {
        override val key get() = "st-${station.id}"
    }
}

private enum class TileStatus { Free, Active, Soon, Expired }

/**
 * Зал клуба: кто где сидит и сколько кому осталось — `ArenaScreen.swift`.
 *
 * Первое, что должно бросаться в глаза, — не выручка, а станции, где время на
 * исходе: именно к ним нужно подойти и спросить, продлевают ли.
 */
@Composable
fun ArenaTab() {
    val vm = operatorViewModel { env -> ArenaViewModel(env.api) }
    val hallState by vm.hall.collectAsStateWithLifecycle()
    val available by vm.isAvailable.collectAsStateWithLifecycle()
    val shift by vm.shift.collectAsStateWithLifecycle()

    // Выбор переживает поворот. Станцию держим по id: лист читает сессию из свежего зала, а не из копии.
    var startingId by rememberSaveable { mutableStateOf<String?>(null) }
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    var techLogging by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }

    // Сессии запускают и закрывают из программы за стойкой, гости уходят
    // раньше времени — занятость надо переспрашивать, иначе на телефоне
    // висит расстановка получасовой давности. Опрос только пока экран виден.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(20_000)
                vm.load()
            }
        }
    }

    val hall = hallState.data
    Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
        Text(
            "Зал", color = Orda.colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg),
        )
        if (!available) {
            Column(Modifier.padding(Spacing.lg)) {
                InfoCard("У этой точки нет зала. Раздел нужен клубам — там, где гости садятся за станции.")
            }
        } else {
            StateContainer(
                state = hallState,
                onRetry = vm::refresh,
                isEmpty = { it.stations.isEmpty() },
                emptyIcon = Icons.Filled.Build,
                emptyTitle = "Зал пока пустой",
                // Проект есть, а станций нет: пустая сетка без объяснения выглядела бы поломкой.
                emptyMessage = "Станции и тарифы заводит владелец в разделе «Зал».",
            ) { loaded ->
                HallContent(
                    now = vm.now,
                    hall = loaded,
                    canSell = shift.canSell,
                    someoneElses = shift.isSomeoneElses,
                    onStart = { startingId = it.id },
                    onOpen = { openedId = it.id },
                    onHistory = { showHistory = true },
                    onTech = { techLogging = true },
                )
            }
        }
    }

    startingId?.let { id ->
        val station = hall?.stations?.firstOrNull { it.id == id }
        if (station != null) ArenaStartSheet(vm, station, onDismiss = { startingId = null }) else if (hall != null) startingId = null
    }
    openedId?.let { id ->
        val station = hall?.stations?.firstOrNull { it.id == id }
        if (station != null) ArenaSessionSheet(vm, station, onDismiss = { openedId = null }) else if (hall != null) openedId = null
    }
    if (techLogging) {
        ArenaTechSheet(vm, hall?.stations.orEmpty(), onDismiss = { techLogging = false })
    }
    if (showHistory && hall != null) {
        ArenaHistorySheet(hall, onDismiss = { showHistory = false })
    }
}

@Composable
private fun HallContent(
    now: StateFlow<Long>,
    hall: ArenaHall,
    canSell: Boolean,
    someoneElses: Boolean,
    onStart: (ArenaStation) -> Unit,
    onOpen: (ArenaStation) -> Unit,
    onHistory: () -> Unit,
    onTech: () -> Unit,
) {
    // Раскладка считается один раз на загруженный зал, а не на каждую перерисовку.
    val entries = remember(hall) { buildEntries(hall) }

    // Широкий экран — больше колонок: плитки не растягиваются, а встают рядом.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (!canSell) {
            item(key = "shift", span = { GridItemSpan(maxLineSpan) }, contentType = "banner") { ShiftWarning(someoneElses) }
        }
        item(key = "summary", span = { GridItemSpan(maxLineSpan) }, contentType = "summary") { SummaryCard(hall) }
        item(key = "attention", span = { GridItemSpan(maxLineSpan) }, contentType = "attention") {
            AttentionCard(hall, now, onOpen)
        }
        items(
            entries,
            key = { it.key },
            span = { if (it is HallEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { if (it is HallEntry.Header) "header" else "station" },
        ) { entry ->
            when (entry) {
                is HallEntry.Header -> Text(
                    entry.title, color = Orda.colors.textMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                is HallEntry.Tile -> StationTile(entry.station, entry.session, now, canSell) {
                    // Открытую сессию смотреть можно всегда: посмотреть остаток не значит тронуть деньги.
                    if (entry.session != null) onOpen(entry.station) else if (canSell) onStart(entry.station)
                }
            }
        }
        item(key = "history", span = { GridItemSpan(maxLineSpan) }, contentType = "action") {
            ActionButton("Что было сегодня", Icons.Filled.History, onHistory)
        }
        item(key = "tech", span = { GridItemSpan(maxLineSpan) }, contentType = "action") {
            ActionButton("Записать техпроблему", Icons.Filled.Build, onTech)
        }
    }
}

private fun buildEntries(hall: ArenaHall): List<HallEntry> = buildList {
    zoneGroups(hall).forEach { group ->
        add(HallEntry.Header(group.id, group.title))
        group.stations.forEach { add(HallEntry.Tile(it, hall.session(it.id))) }
    }
}

// ── Блоки экрана ─────────────────────────────────────────────────────────────

@Composable
private fun InfoCard(text: String) {
    OrdaCard { Text(text, color = Orda.colors.textMuted, fontSize = 14.sp) }
}

/**
 * Смена не открыта — сажать гостей нельзя: сессия попадает в кассу дня, и гость
 * вне смены — выручка, которой не в чем сойтись. Сервер такое отклоняет.
 */
@Composable
private fun ShiftWarning(someoneElses: Boolean) {
    AccentCard(Orda.colors.warning) {
        Text(
            if (someoneElses) "На точке смена другого оператора" else "Смена не открыта",
            color = Orda.colors.text, fontWeight = FontWeight.Medium,
        )
        Text(
            if (someoneElses) {
                "Зал ведёт тот, кто открыл смену: выручка попадёт в его кассу."
            } else {
                "Зал виден, но посадить гостя нельзя — выручка не попадёт в кассу. Откройте смену на экране «Смена»."
            },
            color = Orda.colors.textMuted, fontSize = 12.sp,
        )
    }
}

@Composable
private fun SummaryCard(hall: ArenaHall) {
    OrdaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${hall.busyCount} из ${hall.stations.size}", color = Orda.colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("станций занято", color = Orda.colors.textMuted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(money(hall.todayTotal), color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "нал ${money(hall.todayCash)} · Kaspi ${money(hall.todayKaspi)}",
                    color = Orda.colors.textMuted, fontSize = 12.sp,
                )
            }
        }
    }
}

/** Кому пора продлевать — и кто уже сидит сверх оплаченного. */
@Composable
private fun AttentionCard(hall: ArenaHall, now: StateFlow<Long>, onOpen: (ArenaStation) -> Unit) {
    val nowState = now.collectAsStateWithLifecycle()
    // Состав списка меняется редко (станция вошла в последние десять минут), хотя время тикает каждую секунду:
    // derivedStateOf не даёт карточке перерисовываться, пока состав тот же.
    val soonIds by remember(hall) { derivedStateOf { endingSoon(hall, nowState.value).map { it.id } } }
    if (soonIds.isEmpty()) return
    val byId = remember(hall) { hall.stations.associateBy { it.id } }
    AccentCard(Orda.colors.warning) {
        Text("Подойти сейчас", color = Orda.colors.text, fontWeight = FontWeight.Medium)
        soonIds.forEach { id ->
            val station = byId[id] ?: return@forEach
            val session = hall.session(id) ?: return@forEach
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(station) }.padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(station.name, color = Orda.colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                CountdownText(
                    session, now,
                    colorFor = { expired -> if (expired) Orda.colors.negative else Orda.colors.warning },
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Таймер: единственное место, которое перерисовывается каждую секунду. Экран,
 * плитка и лист о времени не знают, пока не сменится статус.
 */
@Composable
internal fun CountdownText(
    session: ArenaSession,
    now: StateFlow<Long>,
    colorFor: @Composable (expired: Boolean) -> Color,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
) {
    val nowMillis by now.collectAsStateWithLifecycle()
    val left = session.remaining(nowMillis)
    Text(
        countdownText(left),
        color = colorFor(left <= 0),
        fontSize = fontSize, fontWeight = fontWeight, fontFamily = FontFamily.Monospace,
        modifier = modifier,
    )
}

@Composable
private fun StationTile(
    station: ArenaStation,
    session: ArenaSession?,
    now: StateFlow<Long>,
    canSell: Boolean,
    onClick: () -> Unit,
) {
    val colors = Orda.colors
    val nowState = now.collectAsStateWithLifecycle()
    // Плитка перерисовывается только при смене статуса, а не каждую секунду.
    val status by remember(session) {
        derivedStateOf {
            when {
                session == null -> TileStatus.Free
                session.isExpired(nowState.value) -> TileStatus.Expired
                session.isEndingSoon(nowState.value) -> TileStatus.Soon
                else -> TileStatus.Active
            }
        }
    }
    val tint = when (status) {
        TileStatus.Expired -> colors.negative
        TileStatus.Soon -> colors.warning
        TileStatus.Active -> colors.positive
        TileStatus.Free -> colors.textMuted
    }
    val shape = RoundedCornerShape(Radius.md)
    Column(
        Modifier
            .fillMaxWidth()
            .pressScale()
            .background(colors.surfaceRaised, shape)
            .border(1.dp, if (session == null) Color.Transparent else tint.copy(alpha = 0.35f), shape)
            .clickable(onClick = onClick)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(station.name, color = colors.text, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
            Box(Modifier.size(8.dp).background(tint, CircleShape))
        }
        if (session != null) {
            CountdownText(session, now, colorFor = { tint }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(if (status == TileStatus.Expired) "время вышло" else "осталось", color = colors.textMuted, fontSize = 12.sp)
        } else {
            Text("свободна", color = colors.textMuted, fontSize = 14.sp)
            Text(
                if (canSell) "нажмите, чтобы посадить" else "нужна своя смена",
                color = colors.textMuted, fontSize = 12.sp, maxLines = 2,
            )
        }
    }
}

@Composable
private fun ActionButton(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        border = BorderStroke(1.dp, Orda.colors.border),
    ) {
        Icon(icon, contentDescription = null, tint = Orda.colors.brandBright, modifier = Modifier.size(18.dp))
        Text(title, color = Orda.colors.text, modifier = Modifier.padding(start = Spacing.sm, top = Spacing.xs, bottom = Spacing.xs))
    }
}

/** Карточка с цветной рамкой — `Card(accent:)`. */
@Composable
internal fun AccentCard(accent: Color, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Radius.lg)
    Column(
        Modifier.fillMaxWidth()
            .background(Orda.colors.surface, shape)
            .border(1.dp, accent.copy(alpha = 0.5f), shape)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        content = content,
    )
}

// ── Мелочи ───────────────────────────────────────────────────────────────────

/** Станции с истекающим и уже вышедшим временем — по возрастанию остатка. */
private fun endingSoon(hall: ArenaHall, now: Long): List<ArenaStation> =
    hall.stations.mapNotNull { station ->
        val session = hall.session(station.id) ?: return@mapNotNull null
        val left = session.remaining(now)
        if (left <= 10 * 60) station to left else null
    }.sortedBy { it.second }.map { it.first }

private class HallGroup(val id: String, val title: String, val stations: List<ArenaStation>)

/** Зоны с их станциями; станции без зоны — группой в конце, потерять их нельзя. */
private fun zoneGroups(hall: ArenaHall): List<HallGroup> {
    val groups = hall.zones.map { HallGroup(it.id, it.name, hall.stations(it.id)) }.toMutableList()
    val unzoned = hall.stations(null)
    if (unzoned.isNotEmpty()) groups += HallGroup("unzoned", "Без зоны", unzoned)
    return groups.filter { it.stations.isNotEmpty() }
}

/** «1:24» — часы и минуты, «−12 мин» — сверх оплаченного. */
internal fun countdownText(seconds: Long): String {
    val overdue = seconds < 0
    val total = abs(seconds)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val body = when {
        hours > 0 -> "%d:%02d".format(hours, minutes)
        total >= 60 -> "$minutes мин"
        else -> "$total сек"
    }
    return if (overdue) "−$body" else body
}
