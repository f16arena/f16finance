package kz.ordaops.android.feature.operator.arena

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.LoadingView
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import kotlin.math.abs

/**
 * Зал клуба: кто где сидит и сколько кому осталось — `ArenaScreen.swift`.
 *
 * Первое, что должно бросаться в глаза, — не выручка, а станции, где время на
 * исходе: именно к ним нужно подойти и спросить, продлевают ли.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArenaTab() {
    val env = LocalOperatorEnv.current
    val store = remember(env.api) { ArenaStore(env.api) }
    val scope = rememberCoroutineScope()

    var starting by remember { mutableStateOf<ArenaStation?>(null) }
    // Открытую станцию держим по id: лист читает сессию из свежего зала, а не из копии.
    var openedId by remember { mutableStateOf<String?>(null) }
    var techLogging by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(store) {
        store.loadCached()
        store.load()
        // Сессии запускают и закрывают из программы за стойкой, гости уходят
        // раньше времени — занятость надо переспрашивать, иначе на телефоне
        // висит расстановка получасовой давности.
        while (true) {
            delay(20_000)
            store.load()
        }
    }
    // Обратный отсчёт идёт каждую секунду, а к серверу ходим раз в 20 секунд.
    LaunchedEffect(store) {
        while (true) {
            delay(1_000)
            store.tick()
        }
    }

    val hall = store.hall
    val now = store.nowMillis
    val canSell = store.isMyShift

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                store.load()
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize().background(Orda.colors.background),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Зал", color = Orda.colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)

            when {
                !store.isAvailable -> InfoCard(
                    "У этой точки нет зала. Раздел нужен клубам — там, где гости садятся за станции.",
                )
                hall != null && hall.stations.isEmpty() -> InfoCard(
                    // Проект есть, а станций нет: пустая сетка без объяснения выглядела бы поломкой.
                    "Зал пока пустой: станции и тарифы заводит владелец в разделе «Зал».",
                )
                hall != null -> {
                    if (!canSell) ShiftWarning(store.isSomeoneElsesShift)
                    SummaryCard(hall)

                    val soon = endingSoon(hall, now)
                    if (soon.isNotEmpty()) AttentionCard(soon, hall, now) { openedId = it.id }

                    zoneGroups(hall).forEach { group ->
                        Text(group.title, color = Orda.colors.textMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        StationGrid(group.stations, hall, now, canSell) { station, hasSession ->
                            // Открытую сессию смотреть можно всегда: посмотреть остаток не значит тронуть деньги.
                            if (hasSession) openedId = station.id else if (canSell) starting = station
                        }
                    }

                    ActionButton("Что было сегодня", Icons.Filled.History) { showHistory = true }
                    ActionButton("Записать техпроблему", Icons.Filled.Build) { techLogging = true }
                }
                store.isLoading -> Box(Modifier.fillMaxWidth().padding(top = 80.dp)) { LoadingView() }
                store.error != null -> ErrorBanner(store.error ?: "")
            }
        }
    }

    starting?.let { station ->
        ArenaStartSheet(store, station, onDismiss = { starting = null })
    }
    openedId?.let { id ->
        val station = hall?.stations?.firstOrNull { it.id == id }
        if (station != null) {
            ArenaSessionSheet(store, station, onDismiss = { openedId = null })
        } else {
            openedId = null
        }
    }
    if (techLogging) {
        ArenaTechSheet(store, hall?.stations.orEmpty(), onDismiss = { techLogging = false })
    }
    if (showHistory && hall != null) {
        ArenaHistorySheet(hall, onDismiss = { showHistory = false })
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
private fun AttentionCard(stations: List<ArenaStation>, hall: ArenaHall, now: Long, onOpen: (ArenaStation) -> Unit) {
    AccentCard(Orda.colors.warning) {
        Text("Подойти сейчас", color = Orda.colors.text, fontWeight = FontWeight.Medium)
        stations.forEach { station ->
            val session = hall.session(station.id) ?: return@forEach
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(station) }.padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(station.name, color = Orda.colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(
                    countdownText(session.remaining(now)),
                    color = if (session.isExpired(now)) Orda.colors.negative else Orda.colors.warning,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun StationGrid(
    stations: List<ArenaStation>,
    hall: ArenaHall,
    now: Long,
    canSell: Boolean,
    onTap: (ArenaStation, Boolean) -> Unit,
) {
    // Два столбца на телефоне; ленивая сетка внутри прокрутки экрана не нужна.
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        stations.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                pair.forEach { station ->
                    val session = hall.session(station.id)
                    StationTile(station, session, now, canSell, Modifier.weight(1f)) { onTap(station, session != null) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StationTile(
    station: ArenaStation,
    session: ArenaSession?,
    now: Long,
    canSell: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = Orda.colors
    val expired = session?.isExpired(now) ?: false
    val soon = session?.isEndingSoon(now) ?: false
    val tint = when {
        expired -> colors.negative
        soon -> colors.warning
        session != null -> colors.positive
        else -> colors.textMuted
    }
    val shape = RoundedCornerShape(Radius.md)
    Column(
        modifier
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
            Text(
                countdownText(session.remaining(now)),
                color = tint, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
            )
            Text(if (expired) "время вышло" else "осталось", color = colors.textMuted, fontSize = 12.sp)
        } else {
            Text("свободна", color = colors.textMuted, fontSize = 14.sp)
            Text(
                if (canSell) "нажмите, чтобы посадить" else "нужна своя смена",
                color = colors.textMuted, fontSize = 12.sp, maxLines = 1,
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
