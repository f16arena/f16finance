package kz.ordaops.android.feature.business.team

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.feature.business.HeroColors
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.operator.money.StatRow
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing
import java.time.LocalDate
import java.util.Locale

// «Эффективность», «Рейтинг» и «Достижения» операторов. Логика — PeopleLogic.kt,
// запросы те же, что у страниц сайта: ranking, incomes, operators.

// ── Загрузка с параметрами ───────────────────────────────────────────────────

/** Результат загрузки и перезапуск. */
class Loaded<T>(val state: Loadable<T>, val reload: () -> Unit)

/**
 * Загрузчик, зависящий от параметров экрана: при смене [key] данные сбрасываются
 * и грузятся заново (период и точка меняют выборку целиком).
 */
@Composable
fun <T> rememberLoaded(key: Any, fetch: suspend (ApiClient) -> T): Loaded<T> {
    val env = LocalBusiness.current
    var state by remember(key) { mutableStateOf(Loadable<T>(isLoading = true)) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(key, tick) {
        state = if (state.data != null) state.copy(isRefreshing = true, error = null) else Loadable(isLoading = true)
        state = try {
            Loadable(data = fetch(env.api))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.copy(isLoading = false, isRefreshing = false, error = e.userMessage())
        }
    }
    return Loaded(state) { tick++ }
}

/** Точки организации для фильтра: кэш `BusinessViewModel`, подгружаются при первом показе. */
@Composable
fun rememberCompanyChoices(): List<Pair<String, String>> {
    val env = LocalBusiness.current
    val companies by env.vm.companies.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (companies.isEmpty()) env.vm.loadCompanies() }
    return companies.map { it.id to it.name }
}

private suspend fun ApiClient.fetchIncomes(from: String?, to: String?, companyId: String?): List<IncomeLine> {
    val pageSize = 20_000
    val rows = mutableListOf<IncomeLine>()
    // Роут отдаёт страницу целиком; добираем следующие, пока страница полная.
    for (page in 0 until 5) {
        val query = buildMap {
            put("page_size", pageSize.toString()); put("page", page.toString())
            if (from != null) put("from", from)
            if (to != null) put("to", to)
            if (!companyId.isNullOrEmpty()) put("company_id", companyId)
        }
        val chunk = parseIncomes(send<JsonElement>(ApiRequest("/api/admin/incomes", query = query)))
        rows += chunk
        if (chunk.size < pageSize) break
    }
    return rows
}

private suspend fun ApiClient.fetchPeople(): List<PersonRef> =
    parsePersonRefs(send<JsonElement>(ApiRequest("/api/admin/operators", query = mapOf("activeOnly" to "false"))))

private fun fmt2(value: Double): String = "%.2f".format(Locale("ru"), value)

@Composable
private fun piColor(pi: Double): Color {
    val c = Orda.colors
    return when {
        pi >= 1.05 -> c.positive
        pi >= 0.95 -> c.text
        pi >= 0.85 -> c.warning
        else -> c.negative
    }
}

/** Полоса прогресса: заливка [ratio] от ширины. */
@Composable
private fun Bar(ratio: Double, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).background(Orda.colors.surfaceRaised, CircleShape)) {
        Box(Modifier.fillMaxWidth(ratio.toFloat().coerceIn(0f, 1f)).height(6.dp).background(color, CircleShape))
    }
}

@Composable
private fun RankBadge(rank: Int) {
    val c = Orda.colors
    val tint = when (rank) { 1 -> Color(0xFFF59E0B); 2 -> Color(0xFF94A3B8); 3 -> Color(0xFFD97706); else -> c.textDim }
    Box(Modifier.size(32.dp).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Text("$rank", color = tint, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Выбор точки: «Все точки» и по одной. Не показывается, если точка одна. */
@Composable
private fun CompanyFilter(companies: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    if (companies.size < 2) return
    PillSegment(listOf("" to "Все точки") + companies, selected, onSelect = onSelect)
}

// ── Эффективность ────────────────────────────────────────────────────────────

private data class PerfQuery(val from: String, val to: String, val companyId: String)

private fun PerfQuery.request(): ApiRequest = ApiRequest(
    "/api/admin/performance/ranking",
    query = buildMap { put("from", from); put("to", to); if (companyId.isNotEmpty()) put("company_id", companyId) },
)

/**
 * Эффективность операторов: Performance Index = факт / ожидание для слота
 * (точка, день недели, день/ночь). Рейтинг считает сервер; здесь — период, точка,
 * смена, тренд к прошлому периоду и разбор каждого человека по сменам.
 * Продавцы магазина — отдельная страница «sales-kpi» (второй тип эффективности).
 */
@Composable
fun PerformanceScreen() {
    val env = LocalBusiness.current
    var periodName by rememberSaveable { mutableStateOf(PeoplePeriod.ThisMonth.name) }
    var company by rememberSaveable { mutableStateOf("") }
    var shift by rememberSaveable { mutableStateOf("all") }
    var sortName by rememberSaveable { mutableStateOf(PerfSort.Pi.name) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val period = PeoplePeriod.valueOf(periodName)
    val (from, to) = remember(period) { period.bounds() }
    val (prevFrom, prevTo) = remember(from, to) { previousRange(from, to) }
    val companies = rememberCompanyChoices()

    val current = rememberResource("performance", PerfQuery(from, to, company), { it.request() }, PerfRanking::from)
    val previous = rememberResource("performance-prev", PerfQuery(prevFrom, prevTo, company), { it.request() }, PerfRanking::from)
    LaunchedEffect(from, to, company) {
        current.setParam(PerfQuery(from, to, company))
        previous.setParam(PerfQuery(prevFrom, prevTo, company))
    }
    val state by current.state.collectAsStateWithLifecycle()
    val prevState by previous.state.collectAsStateWithLifecycle()
    val prevPi = remember(prevState.data) { previousPi(prevState.data) }
    val companyNames = remember(companies) { companies.toMap() }
    val salesKpi = env.access.canSee("sales-kpi")

    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PillSegment(PeoplePeriod.entries.map { it.name to it.label }, periodName) { periodName = it }
            CompanyFilter(companies, company) { company = it }
            PillSegment(listOf("all" to "Все смены", "day" to "День", "night" to "Ночь"), shift) { shift = it }
        }
        StateContainer(
            state = state, onRetry = { current.reload(); previous.reload() },
            modifier = Modifier.weight(1f),
            isEmpty = { it.items.isEmpty() },
            emptyIcon = Icons.Filled.TrendingUp, emptyTitle = "Данных нет",
            emptyMessage = "За выбранный период у операторов нет закрытых смен.",
        ) { raw ->
            val ranking = remember(raw, shift) { raw.forShift(if (shift == "all") null else shift) }
            val sort = PerfSort.valueOf(sortName)
            val qualified = remember(ranking, sort) { sort.apply(ranking.qualified) }
            val c = Orda.colors
            OwnerScreenColumn {
                val avg = ranking.averagePi
                TeamHeroCard(
                    title = "Средний PI по команде",
                    value = avg?.let(::fmt2) ?: "—",
                    caption = avg?.let { PerformanceGrade.of(it).label },
                    footer = listOf(
                        "В рейтинге" to "${qualified.size}",
                        "Выше нормы" to "${ranking.qualified.count { it.pi >= 1.05 }}",
                        "Ниже нормы" to "${ranking.qualified.count { it.pi < 0.95 }}",
                    ),
                    colors = HeroColors.Accent,
                )
                ranking.qualified.firstOrNull()?.let { leader ->
                    Text("Лидер: ${leader.displayName} — PI ${fmt2(leader.pi)}", color = c.textMuted, fontSize = 14.sp)
                }
                OwnerSection("Рейтинг", trailing = { Text("${qualified.size}", color = c.textDim, fontSize = 14.sp) }) {
                    PillSegment(PerfSort.entries.map { it.name to it.label }, sortName) { sortName = it }
                    if (qualified.isEmpty()) TeamHint("Никто не набрал ${ranking.minQualifyingShifts} ${plural(ranking.minQualifyingShifts, "смену", "смены", "смен")} — сравнивать пока рано.")
                    qualified.forEachIndexed { i, item ->
                        if (i > 0) TeamDivider()
                        PerfRow(i + 1, item, prevPi, sort) { selectedId = item.operatorId }
                    }
                }
                if (ranking.coldStart.isNotEmpty()) {
                    OwnerSection("Мало смен", trailing = { Text("${ranking.coldStart.size}", color = c.textDim, fontSize = 14.sp) }) {
                        TeamHint("Меньше ${ranking.minQualifyingShifts} смен за период: честно сравнивать рано, показана только выручка.")
                        ranking.coldStart.forEachIndexed { i, item ->
                            if (i > 0) TeamDivider()
                            Row(Modifier.fillMaxWidth().clickable { selectedId = item.operatorId }.padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                PersonInitial(item.displayName)
                                Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                                    Text(item.displayName, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text("${item.shifts} ${plural(item.shifts, "смена", "смены", "смен")}", color = c.textDim, fontSize = 13.sp)
                                }
                                Text(money(item.totalRevenue), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                val slots = remember(ranking) { problemSlots(ranking.items) }
                if (slots.isNotEmpty()) {
                    OwnerSection("Слабые слоты") {
                        TeamHint("Где факт систематически ниже нормы (2 смены и больше, меньше 92% от ожидания).")
                        slots.forEachIndexed { i, s ->
                            if (i > 0) TeamDivider()
                            val day = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")[s.weekday - 1]
                            StatRow(
                                "${companyNames[s.companyId] ?: "Точка"} · $day · ${if (s.shift == "night") "ночь" else "день"}",
                                "${Math.round(s.ratio * 100)}% от нормы", valueColor = c.warning,
                            )
                        }
                    }
                }
                if (salesKpi) {
                    OwnerSection("Продавцы магазина") {
                        TeamActionRow(Icons.Filled.Storefront, "Эффективность продавцов магазина", c.info) { env.open("sales-kpi") }
                        TeamHint("Магазин считается отдельно: там нет потока посетителей, как в клубе.")
                    }
                }
                Text(
                    "База норм: ${ranking.baselineShifts} смен за ${ranking.baselineSlots} слотов" +
                        (if (ranking.baselineFrom != null && ranking.baselineTo != null) " (${WeekMath.dayMonth(ranking.baselineFrom)} — ${WeekMath.dayMonth(ranking.baselineTo)})" else "") +
                        ". PI = факт / норма слота; 1,00 — норма, значения режутся в диапазон 0,5–2,0.",
                    color = c.textDim, fontSize = 12.sp,
                )
            }
            raw.items.firstOrNull { it.operatorId == selectedId }?.let { base ->
                val item = ranking.items.firstOrNull { it.operatorId == base.operatorId } ?: base
                PerfDetailSheet(item, prevPi[item.operatorId], companyNames) { selectedId = null }
            }
        }
    }
}

@Composable
private fun PerfRow(rank: Int, item: PerfItem, prevPi: Map<String, Double>, sort: PerfSort, onClick: () -> Unit) {
    val c = Orda.colors
    val delta = piDelta(item.pi, prevPi[item.operatorId])
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        RankBadge(rank)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.displayName, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                when (sort) {
                    PerfSort.AboveNorm -> "${signedMoney(item.aboveNorm)} к норме"
                    else -> "${item.shifts} ${plural(item.shifts, "смена", "смены", "смен")} · ср. ${money(item.avgPerShift)}"
                },
                color = c.textDim, fontSize = 13.sp, maxLines = 1,
            )
            // Шкала 0,5–2,0: засечка нормы — на трети длины.
            Bar(((item.pi - 0.5) / 1.5), piColor(item.pi))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmt2(item.pi), color = piColor(item.pi), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(item.grade.label, color = c.textDim, fontSize = 12.sp)
            when {
                delta == null -> Text("новый", color = c.textDim, fontSize = 11.sp)
                abs2(delta) < 0.005 -> Text("0,00", color = c.textDim, fontSize = 11.sp)
                else -> Text((if (delta > 0) "▲ +" else "▼ −") + fmt2(abs2(delta)), color = if (delta > 0) c.positive else c.negative, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun abs2(v: Double) = if (v < 0) -v else v

@Composable
private fun PerfDetailSheet(item: PerfItem, prevPi: Double?, companyNames: Map<String, String>, onDismiss: () -> Unit) {
    val c = Orda.colors
    OrdaSheet(item.displayName, onDismiss) {
        TeamHeroCard(
            title = "Performance Index", value = fmt2(item.pi), caption = item.grade.label,
            footer = listOf(
                "Смен" to "${item.shifts}",
                "Выручка" to money(item.totalRevenue),
                "Норма" to money(item.expectedTotal),
            ),
            colors = HeroColors.Accent,
        )
        StatRow("Сверх нормы", signedMoney(item.aboveNorm), valueColor = if (item.aboveNorm >= 0) c.positive else c.negative)
        StatRow("Средняя смена", money(item.avgPerShift))
        piDelta(item.pi, prevPi)?.let { StatRow("К прошлому периоду", (if (it >= 0) "+" else "−") + fmt2(abs2(it)), valueColor = if (it >= 0) c.positive else c.negative) }
        if (!item.qualifying) TeamHint("Смен мало для рейтинга — PI ориентировочный.")
        Text("Смены", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        item.details.sortedByDescending { it.date }.forEachIndexed { i, s ->
            if (i > 0) TeamDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${WeekMath.dayTitle(s.date)} · ${if (s.isNight) "ночь" else "день"}", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "${companyNames[s.companyId] ?: "Точка"} · ${money(s.actual)} из ${money(s.expected)}" + if (s.isClipped) " · граница шкалы" else "",
                        color = if (s.isClipped) c.warning else c.textDim, fontSize = 12.sp,
                    )
                }
                Text(fmt2(s.pi), color = piColor(s.pi), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── Рейтинг ──────────────────────────────────────────────────────────────────

private enum class RatingRange(val label: String) {
    Today("Сегодня"), Week("7 дней"), Month("Месяц");

    fun bounds(today: LocalDate = LocalDate.now()): Pair<String, String> = when (this) {
        Today -> WeekMath.format(today) to WeekMath.format(today)
        Week -> WeekMath.format(today.minusDays(6)) to WeekMath.format(today)
        Month -> WeekMath.format(today.withDayOfMonth(1)) to WeekMath.format(today)
    }
}

private data class RatingsData(val board: List<LeaderEntry>)

/** Рейтинг операторов: лидерборд по выручке, сменам и среднему чеку, с изменением к прошлому периоду. */
@Composable
fun RatingsScreen() {
    var rangeName by rememberSaveable { mutableStateOf(RatingRange.Month.name) }
    var company by rememberSaveable { mutableStateOf("") }
    val range = RatingRange.valueOf(rangeName)
    val (from, to) = remember(range) { range.bounds() }
    val companies = rememberCompanyChoices()
    val loaded = rememberLoaded(listOf(from, to, company)) { api ->
        val (prevFrom, prevTo) = previousRange(from, to)
        coroutineScope {
            val cur = async { api.fetchIncomes(from, to, company) }
            val prev = async { api.fetchIncomes(prevFrom, prevTo, company) }
            val people = async { api.fetchPeople() }
            RatingsData(buildLeaderboard(cur.await(), prev.await(), people.await()))
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PillSegment(RatingRange.entries.map { it.name to it.label }, rangeName) { rangeName = it }
            CompanyFilter(companies, company) { company = it }
        }
        StateContainer(
            state = loaded.state, onRetry = loaded.reload, modifier = Modifier.weight(1f),
            isEmpty = { it.board.isEmpty() }, emptyIcon = Icons.Filled.EmojiEvents, emptyTitle = "Операторов нет",
            emptyMessage = "Когда появятся операторы и закрытые смены, здесь будет лидерборд.",
        ) { data ->
            val c = Orda.colors
            val total = data.board.sumOf { it.revenue }
            val top = data.board.firstOrNull()?.revenue ?: 0.0
            OwnerScreenColumn {
                TeamHeroCard(
                    title = "Выручка операторов", value = money(total),
                    footer = listOf(
                        "Операторов" to "${data.board.size}",
                        "Смен" to "${data.board.sumOf { it.shifts }}",
                        "Лидер" to (data.board.firstOrNull()?.takeIf { it.revenue > 0 }?.name?.substringBefore(' ') ?: "—"),
                    ),
                    colors = HeroColors.Accent,
                )
                OwnerSection("Лидерборд", trailing = { Text("${data.board.size}", color = c.textDim, fontSize = 14.sp) }) {
                    data.board.forEachIndexed { i, e ->
                        if (i > 0) TeamDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            RankBadge(i + 1)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(e.name, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(
                                    "${e.shifts} ${plural(e.shifts, "смена", "смены", "смен")} · ${e.days} ${plural(e.days, "день", "дня", "дней")} · ср. ${money(e.avgPerShift)}",
                                    color = c.textDim, fontSize = 13.sp, maxLines = 1,
                                )
                                Bar(if (top > 0) e.revenue / top else 0.0, c.brand)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(money(e.revenue), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                val change = e.change
                                Text(
                                    change?.let { percentChange(it) } ?: "${Math.round(e.share)}% доли",
                                    color = when { change == null -> c.textDim; change >= 0 -> c.positive; else -> c.negative },
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Достижения ───────────────────────────────────────────────────────────────

private const val ALL_TIME = "AllTime"

private data class AchData(val results: List<AchResult>)

/** Достижения операторов: кто и что получил за период, с прогрессом к закрытым. */
@Composable
fun AchievementsScreen() {
    var periodName by rememberSaveable { mutableStateOf(PeoplePeriod.ThisMonth.name) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val bounds = remember(periodName) { if (periodName == ALL_TIME) null else PeoplePeriod.valueOf(periodName).bounds() }
    val loaded = rememberLoaded(periodName) { api ->
        coroutineScope {
            val incomes = async { api.fetchIncomes(bounds?.first, bounds?.second, null) }
            val people = async { api.fetchPeople() }
            AchData(computeAchievements(achievementStats(incomes.await(), people.await())))
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            PillSegment(PeoplePeriod.entries.map { it.name to it.label } + (ALL_TIME to "Всё время"), periodName) { periodName = it }
        }
        StateContainer(
            state = loaded.state, onRetry = loaded.reload, modifier = Modifier.weight(1f),
            isEmpty = { it.results.isEmpty() }, emptyIcon = Icons.Filled.EmojiEvents, emptyTitle = "Нет данных",
            emptyMessage = "За выбранный период смен и выручки не было.",
        ) { data ->
            val c = Orda.colors
            val summary = achievementSummary(data.results)
            OwnerScreenColumn {
                OwnerSection("Сводка достижений") {
                    Achievement.entries.forEachIndexed { i, a ->
                        if (i > 0) TeamDivider()
                        val count = summary[a] ?: 0
                        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.title, color = if (count > 0) c.text else c.textDim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(a.desc, color = c.textDim, fontSize = 12.sp)
                            }
                            Text("$count", color = if (count > 0) c.brand else c.textDim, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                OwnerSection("Кто что получил") {
                    data.results.forEachIndexed { i, r ->
                        if (i > 0) TeamDivider()
                        Row(
                            Modifier.fillMaxWidth().clickable { selectedId = r.stat.operatorId }.padding(vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            RankBadge(r.rank)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(r.stat.name, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(
                                    if (r.earned.isEmpty()) "пока без достижений" else r.earned.joinToString(" · ") { it.title },
                                    color = if (r.earned.isEmpty()) c.textDim else c.brand, fontSize = 13.sp, maxLines = 2,
                                )
                            }
                            Text("${r.earned.size}/${Achievement.entries.size}", color = c.textDim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            data.results.firstOrNull { it.stat.operatorId == selectedId }?.let { AchievementSheet(it) { selectedId = null } }
        }
    }
}

@Composable
private fun AchievementSheet(result: AchResult, onDismiss: () -> Unit) {
    val c = Orda.colors
    val s = result.stat
    OrdaSheet(s.name, onDismiss) {
        TeamHeroCard(
            title = "Место ${result.rank}", value = money(s.revenue),
            footer = listOf("Смен" to "${s.shifts}", "Ср. смена" to money(s.avgPerShift), "Доля" to "${Math.round(s.share)}%"),
            colors = HeroColors.Accent,
        )
        Text("Получено: ${result.earned.size}", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (result.earned.isEmpty()) TeamHint("Пока ничего — смотрите, сколько осталось до следующего.")
        result.earned.forEach { a ->
            Column(Modifier.fillMaxWidth().background(c.positive.copy(alpha = 0.10f), RoundedCornerShape(14.dp)).padding(Spacing.md)) {
                Text(a.title, color = c.positive, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(a.desc, color = c.textMuted, fontSize = 13.sp)
            }
        }
        if (result.locked.isNotEmpty()) Text("Ещё не получено", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        result.locked.forEach { a ->
            val p = a.progress(s)
            Column(Modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(14.dp)).padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(a.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(a.desc, color = c.textMuted, fontSize = 13.sp)
                if (p != null) {
                    Bar(p.ratio, c.brand)
                    Text("${Math.round(p.current).toLong().toString()} из ${Math.round(p.target)} ${p.unit}", color = c.textDim, fontSize = 12.sp)
                }
            }
        }
    }
}
