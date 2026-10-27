package kz.ordaops.android.feature.business.ops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.operator.money.HeroCard
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// «Станции» (`stations`; на сайте раздел «Игровой зал»): занятость зала, запуск и
// завершение сессии, тревоги (время вышло, киоск молчит, нет игр), тарифы и выручка.
// Запросы: `/api/admin/arena` — список проектов, `?projectId=&companyId=` — зал,
// `POST getAnalytics | adminStartSession | adminEndSession`. Зоны, станции,
// тарифы, карту и каталог игр заводят на сайте.

private const val ARENA = "/api/admin/arena"

// ── Модели ───────────────────────────────────────────────────────────────────

data class ArenaProject(val id: String, val name: String, val points: List<Pair<String, String>>)

data class ArenaZone(val id: String, val name: String, val isActive: Boolean, val extensionHourly: Double?)

data class ArenaStation(
    val id: String,
    val zoneId: String?,
    val name: String,
    val order: Int,
    val isActive: Boolean,
    val lastHeartbeat: Instant?,
    val activeSessionId: String?,
    val sessionEndsAt: Instant?,
) {
    val isBusy: Boolean get() = activeSessionId != null

    /** Сколько минут осталось у сессии; отрицательное — время вышло, а станцию не освободили. */
    fun remainingMinutes(now: Instant = Instant.now()): Int? =
        sessionEndsAt?.let { Math.round((it.epochSecond - now.epochSecond) / 60.0).toInt() }

    fun isOverdue(now: Instant = Instant.now()): Boolean = (remainingMinutes(now) ?: 0) < 0

    /** Киоск молчит дольше пятнадцати минут (с запасом к обычному интервалу). */
    fun isSilent(now: Instant = Instant.now()): Boolean =
        lastHeartbeat != null && now.epochSecond - lastHeartbeat.epochSecond > 15 * 60
}

data class ArenaTariff(
    val id: String,
    val zoneId: String?,
    val name: String,
    val minutes: Int,
    val price: Double,
    val isActive: Boolean,
    val windowStart: String?,
    val windowEnd: String?,
) {
    val durationLabel: String
        get() {
            val h = minutes / 60
            val m = minutes % 60
            return when {
                h > 0 && m > 0 -> "$h ч $m мин"
                h > 0 -> "$h ч"
                else -> "$m мин"
            }
        }

    /** Окно действия ночного/дневного тарифа: `22:00–08:00`. */
    val windowLabel: String? get() = if (windowStart != null && windowEnd != null) "${windowStart.take(5)}–${windowEnd.take(5)}" else null
}

data class ArenaBoard(
    val zones: List<ArenaZone>,
    val stations: List<ArenaStation>,
    val tariffs: List<ArenaTariff>,
    val gamesCount: Int,
    private val boundStations: Set<String>,
) {
    val working: List<ArenaStation> get() = stations.filter { it.isActive }
    val busy: List<ArenaStation> get() = stations.filter { it.isBusy }

    /** Доля занятых станций; `null` — включённых станций нет. */
    val load: Double? get() = working.takeIf { it.isNotEmpty() }?.let { busy.size.toDouble() / it.size }

    fun overdue(now: Instant = Instant.now()): List<ArenaStation> = busy.filter { it.isOverdue(now) }
    fun silent(now: Instant = Instant.now()): List<ArenaStation> = working.filter { it.isSilent(now) }

    /** Станции без привязанных игр: киоск покажет пустой список. */
    val withoutGames: List<ArenaStation> get() = if (gamesCount == 0) emptyList() else working.filter { it.id !in boundStations }

    fun tariffsFor(station: ArenaStation): List<ArenaTariff> =
        tariffs.filter { it.zoneId == station.zoneId && it.isActive }.sortedBy { it.price }

    /** Зоны со своими станциями; станции без зоны — отдельной группой в конце. */
    val groups: List<Pair<ArenaZone?, List<ArenaStation>>>
        get() {
            val order = compareBy<ArenaStation>({ it.order }, { it.name })
            val byZone = stations.groupBy { it.zoneId }
            val result = zones.map { z -> z to byZone[z.id].orEmpty().sortedWith(order) }.toMutableList<Pair<ArenaZone?, List<ArenaStation>>>()
            val known = zones.map { it.id }.toSet()
            val loose = stations.filter { it.zoneId == null || it.zoneId !in known }.sortedWith(order)
            if (loose.isNotEmpty()) result += (null to loose)
            return result
        }

    companion object {
        fun parse(root: JsonObject): ArenaBoard {
            val d = root.obj("data") ?: root
            return ArenaBoard(
                zones = d.arr("zones").map { ArenaZone(it.str("id") ?: "", it.str("name") ?: "Зона", it.bool("is_active") ?: true, it.num("extension_hourly_price")) },
                stations = d.arr("stations").map {
                    ArenaStation(
                        it.str("id") ?: "", it.str("zone_id"), it.str("name") ?: "Станция", it.int("order_index") ?: 0, it.bool("is_active") ?: true,
                        parseMoment(it.str("last_heartbeat_at")), it.str("active_session_id"), parseMoment(it.str("active_session_ends_at")),
                    )
                }.filter { it.id.isNotEmpty() },
                tariffs = d.arr("tariffs").map {
                    ArenaTariff(
                        it.str("id") ?: "", it.str("zone_id"), it.str("name") ?: "Тариф", it.int("duration_minutes") ?: 60, it.num("price") ?: 0.0,
                        it.bool("is_active") ?: true, it.str("window_start_time"), it.str("window_end_time"),
                    )
                }.filter { it.id.isNotEmpty() },
                gamesCount = d.arr("gamesCatalog").count { it.bool("is_active") ?: true },
                boundStations = d.arr("stationGames").filter { it.bool("is_active") ?: true }.mapNotNull { it.str("station_id") }.toSet(),
            )
        }
    }
}

/** Сессия из аналитики проекта. */
data class ArenaSession(val stationId: String?, val stationName: String?, val minutes: Int, val amount: Double, val cash: Double, val kaspi: Double, val completed: Boolean)

/** Выручка проекта по завершённым сессиям (активная — деньги ещё могут быть не взяты). */
data class ArenaRevenue(val amount: Double, val count: Int, val cash: Double, val kaspi: Double, val minutes: Int, val byStation: List<Triple<String, Double, Int>>) {
    val averageCheck: Double get() = if (count > 0) amount / count else 0.0

    /** Проданные часы против календарных; `null`, если станций или дней нет. */
    fun occupancy(stationCount: Int, days: Int): Double? {
        if (stationCount <= 0 || days <= 0) return null
        return minOf(minutes.toDouble() / (stationCount.toDouble() * days * 24 * 60), 1.0)
    }

    companion object {
        fun parse(root: JsonObject): List<ArenaSession> = (root.obj("data") ?: root).arr("sessions").map { o ->
            fun ref(key: String): JsonObject? = o.obj(key) ?: (o[key] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
            ArenaSession(
                o.str("station_id"), ref("station")?.str("name"), ref("tariff")?.int("duration_minutes") ?: 0,
                o.num("amount") ?: 0.0, o.num("cash_amount") ?: 0.0, o.num("kaspi_amount") ?: 0.0, o.str("status") == "completed",
            )
        }

        fun of(sessions: List<ArenaSession>): ArenaRevenue {
            val done = sessions.filter { it.completed }
            val byStation = done.groupBy { it.stationId ?: "—" }.map { (_, list) ->
                Triple(list.first().stationName ?: "Станция", list.sumOf { it.amount }, list.size)
            }.sortedByDescending { it.second }
            return ArenaRevenue(done.sumOf { it.amount }, done.size, done.sumOf { it.cash }, done.sumOf { it.kaspi }, done.sumOf { it.minutes }, byStation)
        }
    }
}

enum class ArenaPeriod(val label: String, val days: Int) {
    Today("Сегодня", 1), Week("7 дней", 7), Month("30 дней", 30);

    /**
     * Границы полными моментами: сервер фильтрует по `started_at` (timestamp), и голая
     * дата в верхней границе отрезала бы весь последний день.
     */
    fun bounds(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Pair<String, String> {
        val start = LocalDate.ofInstant(now, zone).minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant()
        return start.toString() to now.toString()
    }
}

/** Статус станции одной строкой и признак тревоги. */
fun stationStatus(s: ArenaStation, now: Instant = Instant.now()): Pair<String, Boolean> {
    val left = s.remainingMinutes(now)
    return when {
        !s.isActive -> "выключена" to false
        s.isBusy && left != null && left < 0 -> "переигрывает ${-left} мин" to true
        s.isBusy && left != null -> "осталось ${minutesText(left)}" to false
        s.isBusy -> "занята" to false
        s.isSilent(now) -> "киоск молчит" to true
        else -> "свободна" to false
    }
}

/** 95 → «1 ч 35 мин», 40 → «40 мин». */
fun minutesText(minutes: Int): String = if (minutes >= 60) "${minutes / 60} ч" + (if (minutes % 60 > 0) " ${minutes % 60} мин" else "") else "$minutes мин"

fun startSessionBody(stationId: String, tariffId: String, projectId: String, pointId: String?): JsonObject = buildJsonObject {
    put("action", "adminStartSession"); put("stationId", stationId); put("tariffId", tariffId); put("projectId", projectId)
    if (!pointId.isNullOrEmpty()) put("companyId", pointId)
}

fun endSessionBody(stationId: String): JsonObject = buildJsonObject { put("action", "adminEndSession"); put("stationId", stationId) }

fun analyticsBody(projectId: String, pointId: String?, period: ArenaPeriod): JsonObject {
    val (from, to) = period.bounds()
    return buildJsonObject {
        put("action", "getAnalytics"); put("projectId", projectId); put("from", from); put("to", to)
        if (!pointId.isNullOrEmpty()) put("companyId", pointId)
    }
}

/** Коды сервера человеческим текстом. */
fun friendlyArenaError(message: String): String = when {
    "station-already-occupied" in message -> "Станция уже занята — обновите зал."
    "no-active-session" in message -> "Активной сессии на станции нет."
    "tariff-not-found" in message -> "Тариф не найден или выключен."
    else -> message
}

// ── Экран ────────────────────────────────────────────────────────────────────

private fun parseProjects(root: JsonObject): List<ArenaProject> =
    (root.obj("data") ?: root).arr("projects").map { p ->
        ArenaProject(p.str("id") ?: "", p.str("name") ?: "Проект", p.arr("companies").map { (it.str("id") ?: "") to (it.str("name") ?: "Точка") })
    }.filter { it.id.isNotEmpty() }

@Composable
fun StationsScreen() {
    val loader = rememberOpsLoader("arena-projects", ApiRequest(ARENA), ::parseProjects)
    val state by loader.state.collectAsStateWithLifecycle()
    StateContainer(
        state = state, onRetry = loader::refresh,
        isEmpty = { it.isEmpty() }, emptyIcon = Icons.Filled.SportsEsports,
        emptyTitle = "Игровых проектов нет",
        emptyMessage = "Арена не включена ни на одном проекте. Флаг «арена» ставится проекту или точке на сайте.",
    ) { projects -> StationsBoard(projects) }
}

@Composable
private fun StationsBoard(projects: List<ArenaProject>) {
    val env = LocalBusiness.current
    var projectId by rememberSaveable { mutableStateOf(projects.first().id) }
    var pointId by rememberSaveable { mutableStateOf("") }
    var periodName by rememberSaveable { mutableStateOf(ArenaPeriod.Today.name) }
    val project = projects.firstOrNull { it.id == projectId } ?: projects.first()
    val period = ArenaPeriod.valueOf(periodName)
    val canStart = env.can("stations.admin_start_session")
    val canEnd = env.can("stations.admin_end_session")
    val canRevenue = env.can("stations.get_analytics")

    val query = buildMap { put("projectId", project.id); if (pointId.isNotEmpty()) put("companyId", pointId) }
    val loader = rememberOpsLoader("arena-board:${project.id}:$pointId", ApiRequest(ARENA, query = query), ArenaBoard::parse)
    val state by loader.state.collectAsStateWithLifecycle()
    // Зал живой: сессии кончаются и начинаются, поэтому обновляем сами.
    LaunchedEffect(project.id, pointId) { while (true) { delay(30_000); loader.refresh() } }

    val revenueLoader = if (canRevenue) rememberOpsFetcher("arena-revenue:${project.id}:$pointId:$periodName") { api ->
        ArenaRevenue.of(ArenaRevenue.parse(api.send<JsonObject>(ApiRequest.json(ARENA, analyticsBody(project.id, pointId.ifEmpty { null }, period)))))
    } else null
    val revenueState = revenueLoader?.state?.collectAsStateWithLifecycle()?.value

    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    var startFor by remember { mutableStateOf<ArenaStation?>(null) }
    var endFor by remember { mutableStateOf<ArenaStation?>(null) }
    val c = Orda.colors

    StateContainer(state = state, onRetry = loader::refresh, emptyIcon = Icons.Filled.SportsEsports) { board ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            if (projects.size > 1 || project.points.size > 1) {
                item(key = "pickers") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (projects.size > 1) PillSegment(projects.map { it.id to it.name }, project.id) { projectId = it; pointId = "" }
                        if (project.points.size > 1) PillSegment(listOf("" to "Все точки") + project.points, pointId) { pointId = it }
                    }
                }
            }
            item(key = "hero") {
                HeroCard {
                    Text("Занято сейчас", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                    Text("${board.busy.size} из ${board.working.size}", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text(board.load?.let { "загрузка зала ${Math.round(it * 100)}%" } ?: "включённых станций нет", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                    val revenue = revenueState?.data
                    Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                        if (revenue != null) {
                            HeroFact("Выручка · ${period.label.lowercase()}", money(revenue.amount))
                            HeroFact("Сессий", "${revenue.count}")
                        } else {
                            HeroFact("Зон", "${board.zones.size}")
                            HeroFact("Тарифов", "${board.tariffs.count { it.isActive }}")
                        }
                    }
                }
            }
            submit.error?.let { item(key = "err") { ErrorBanner(friendlyArenaError(it)) } }

            val overdue = board.overdue()
            val silent = board.silent()
            val noGames = board.withoutGames
            if (overdue.isNotEmpty()) item(key = "overdue") {
                OpsSection("Время вышло", count = "станция играет бесплатно") {
                    overdue.forEach { OpsRow(Icons.Filled.Warning, c.negative, it.name, subtitle = stationStatus(it).first, trailingColor = c.negative) }
                }
            }
            if (silent.isNotEmpty()) item(key = "silent") {
                OpsSection("Киоск молчит", count = "нет сигнала больше 15 минут") {
                    silent.forEach { OpsRow(Icons.Filled.WifiOff, c.warning, it.name, subtitle = it.lastHeartbeat?.let { t -> dateTimeLabel(t.toString()) }) }
                }
            }
            if (noGames.isNotEmpty()) item(key = "nogames") {
                OpsSection("Без игр", count = "киоск покажет пустой список") {
                    noGames.forEach { OpsRow(Icons.Filled.Warning, c.warning, it.name, trailing = "игр нет", trailingColor = c.warning) }
                }
            }
            if (board.groups.isEmpty()) item(key = "empty") {
                EmptyState(Icons.Filled.SportsEsports, "В проекте нет станций", "Зоны и станции заводят на сайте.")
            }
            board.groups.forEach { (zone, stations) ->
                item(key = "zone-${zone?.id ?: "none"}") {
                    val busy = stations.count { it.isBusy }
                    OpsSection(zone?.name ?: "Без зоны", count = "$busy из ${stations.count { it.isActive }} занято") {
                        if (stations.isEmpty()) Text("В зоне нет станций", color = c.textDim)
                        stations.forEach { s ->
                            val (status, alarm) = stationStatus(s)
                            val tint = when { !s.isActive -> c.textDim; alarm -> c.negative; s.isBusy -> c.warning; else -> c.positive }
                            OpsRow(
                                if (s.isBusy) Icons.Filled.PlayArrow else Icons.Filled.SportsEsports, tint, s.name, subtitle = status,
                                trailing = when {
                                    s.isBusy && canEnd -> "Завершить"
                                    !s.isBusy && s.isActive && canStart -> "Запустить"
                                    else -> null
                                },
                                trailingColor = c.brand,
                                onClick = when {
                                    s.isBusy && canEnd -> ({ endFor = s })
                                    !s.isBusy && s.isActive && canStart -> ({ startFor = s })
                                    else -> null
                                },
                            )
                        }
                        val tariffs = board.tariffs.filter { it.zoneId == zone?.id && it.isActive }.sortedBy { it.price }
                        if (tariffs.isNotEmpty()) {
                            Text("Тарифы", color = c.textDim, fontSize = 13.sp, modifier = Modifier.padding(top = Spacing.sm))
                            tariffs.forEach { t ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text("${t.name} · ${t.durationLabel}" + (t.windowLabel?.let { " · $it" } ?: ""), color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    Text(money(t.price), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
            if (canRevenue) item(key = "revenue") {
                OpsSection("Выручка зала", count = "по завершённым сессиям") {
                    PillSegment(ArenaPeriod.entries.map { it.name to it.label }, periodName) { periodName = it }
                    val revenue = revenueState?.data
                    when {
                        revenue == null && revenueState?.error != null -> Text(revenueState.error!!, color = c.negative, fontSize = 14.sp)
                        revenue == null -> Text("Считаем…", color = c.textDim)
                        revenue.count == 0 -> Text("За период сессий не было", color = c.textDim)
                        else -> {
                            StatLine("Выручка", money(revenue.amount), true)
                            StatLine("Наличные", money(revenue.cash))
                            StatLine("Безналичный", money(revenue.kaspi))
                            StatLine("Сессий", "${revenue.count}")
                            StatLine("Средний чек", money(revenue.averageCheck))
                            revenue.occupancy(board.working.size, period.days)?.let { StatLine("Загрузка за период", "${Math.round(it * 100)}%") }
                            Text("Кто заработал", color = c.textDim, fontSize = 13.sp, modifier = Modifier.padding(top = Spacing.sm))
                            revenue.byStation.take(8).forEach { (name, amount, count) ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text("$name · $count", color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    Text(money(amount), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
        startFor?.let { s ->
            OrdaSheet("Запустить: ${s.name}", { startFor = null }) {
                val tariffs = board.tariffsFor(s)
                if (tariffs.isEmpty()) Text("Для зоны станции нет активных тарифов.", color = c.textDim)
                tariffs.forEach { t ->
                    OpsRow(
                        Icons.Filled.PlayArrow, c.brand, t.name, subtitle = t.durationLabel + (t.windowLabel?.let { " · $it" } ?: ""),
                        trailing = money(t.price),
                        onClick = {
                            startFor = null
                            submit.run(
                                { env.api.sendUnit(ApiRequest.json(ARENA, startSessionBody(s.id, t.id, project.id, pointId.ifEmpty { null }))) },
                                { haptics.success(); loader.refresh() },
                            )
                        },
                    )
                }
                Text("Сессия оплачивается наличными по цене тарифа и сразу попадает в выручку зала.", color = c.textDim, fontSize = 12.sp)
            }
        }
        endFor?.let { s ->
            OpsConfirm(
                "Завершить сессию?", "${s.name}: сессия закроется, киоск получит команду и погасит экран.", "Завершить",
                onConfirm = {
                    endFor = null
                    submit.run({ env.api.sendUnit(ApiRequest.json(ARENA, endSessionBody(s.id))) }, { haptics.success(); loader.refresh() })
                },
                onDismiss = { endFor = null },
            )
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = if (emphasized) Orda.colors.text else Orda.colors.textMuted, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Orda.colors.text, fontSize = 15.sp, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.SemiBold)
    }
}
