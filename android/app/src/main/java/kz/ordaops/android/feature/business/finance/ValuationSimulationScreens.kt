package kz.ordaops.android.feature.business.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiJson
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.BizData
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.HorizontalRow
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerFootnote
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.operator.shift.AmountField
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.feature.operator.shift.parseAmount
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// ── Оценка бизнеса (/valuation) ─────────────────────────────────────────────
// `GET /api/admin/valuation`: EBITDA за 12 полных месяцев × мультипликатор с факторами. Считает сервер.

@Serializable
data class ValuationFactor(val key: String = "", val label: String = "", val status: String = "neutral", val effect: Double = 0.0, val note: String = "")

@Serializable
data class ValuationMonth(val month: String = "", val revenue: Double = 0.0, val ebitda: Double = 0.0, @SerialName("net_profit") val netProfit: Double = 0.0, @SerialName("ebitda_margin") val ebitdaMargin: Double = 0.0)

@Serializable
data class ValuationMultiple(val base: Double = 3.0, val low: Double = 0.0, val mid: Double = 0.0, val high: Double = 0.0)

@Serializable
data class ValuationRange(val low: Double = 0.0, val mid: Double = 0.0, val high: Double = 0.0)

@Serializable
data class ValuationPeriod(val start: String = "", val end: String = "")

@Serializable
data class Valuation(
    val period: ValuationPeriod = ValuationPeriod(),
    @SerialName("revenue_12mo") val revenue12: Double = 0.0,
    @SerialName("ebitda_12mo") val ebitda12: Double = 0.0,
    @SerialName("ebitda_prev_12mo") val ebitdaPrev12: Double = 0.0,
    @SerialName("net_profit_12mo") val netProfit12: Double = 0.0,
    @SerialName("ebitda_margin") val ebitdaMargin: Double = 0.0,
    @SerialName("trend_pct") val trendPct: Double? = null,
    @SerialName("companies_count") val companiesCount: Int = 0,
    val profitable: Boolean = false,
    val multiple: ValuationMultiple = ValuationMultiple(),
    val valuation: ValuationRange = ValuationRange(),
    val factors: List<ValuationFactor> = emptyList(),
    val monthly: List<ValuationMonth> = emptyList(),
)

/** Ответ без данных (`data: null`) приходит у владельца без точек. */
@Serializable
data class ValuationReply(val data: Valuation? = null)

class ValuationViewModel(private val api: FinanceApi) :
    LoadViewModel<ValuationReply>({ api.api.send(ApiRequest("/api/admin/valuation")) })

@Composable
fun ValuationScreen() {
    val vm = financeViewModel("valuation") { _, api -> ValuationViewModel(api) }
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.reload() }
    StateContainer(state, onRetry = vm::reload) { reply ->
        OwnerScreenColumn {
            val v = reply.data
            if (v == null) { OwnerFootnote("Оценить пока нечего: нет данных по точкам."); return@OwnerScreenColumn }
            HeroSummary(
                title = "Оценка бизнеса",
                value = if (v.profitable) money(v.valuation.mid) else "Не оценивается",
                caption = if (v.profitable) "от ${money(v.valuation.low)} до ${money(v.valuation.high)} · мультипликатор ${v.multiple.mid}× к годовой EBITDA"
                else "EBITDA за 12 месяцев отрицательная — оценка по доходу не строится",
                footer = listOf("EBITDA за год" to money(v.ebitda12), "Выручка" to money(v.revenue12), "Маржа" to BizFormat.percent(v.ebitdaMargin)),
            )
            OwnerFootnote("Период: последние 12 полных месяцев (${v.period.start.take(7)} – ${v.period.end.take(7)}). Расчёт — оценочный, не оферта.")
            OwnerSection("Показатели") {
                LineRow("Чистая прибыль за год", money(v.netProfit12))
                LineRow("EBITDA за прошлый год", money(v.ebitdaPrev12))
                v.trendPct?.let { LineRow("Тренд EBITDA год к году", BizFormat.percent(it, signed = true), if (it >= 0) Orda.colors.positive else Orda.colors.negative) }
                LineRow("Точек с выручкой", "${v.companiesCount}")
            }
            OwnerSection("Как получился мультипликатор") {
                Text("Базовый ${v.multiple.base}× корректируется по факторам → итог ${v.multiple.mid}×", color = Orda.colors.textDim, fontSize = 13.sp)
                v.factors.forEach { f ->
                    val tint = when (f.status) { "good" -> Orda.colors.positive; "bad" -> Orda.colors.negative; else -> Orda.colors.textDim }
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(f.label, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            if (f.effect != 0.0) Text((if (f.effect > 0) "+" else "−") + kotlin.math.abs(f.effect).toString().replace('.', ',') + "×", color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(f.note, color = Orda.colors.textDim, fontSize = 12.sp)
                    }
                }
            }
            if (v.monthly.isNotEmpty()) {
                OwnerSection("EBITDA по месяцам") {
                    TrendLine(v.monthly.map { Bar(RuDates.monthShort((it.month.substring(5, 7).toIntOrNull() ?: 1) - 1), it.ebitda) }, Orda.colors.brand)
                }
            }
        }
    }
}

// ── Симуляция потенциала клуба (/simulation) ────────────────────────────────
// `GET /api/admin/simulation?company_id=`: зоны, тарифы, факт за 90 дней. Правка — `POST` с полной заменой конфига точки.

@Serializable
data class SimMix(@SerialName("tariff_id") val tariffId: String = "", @SerialName("share_pct") val sharePct: Double = 0.0)

@Serializable
data class SimZone(
    val id: String? = null,
    val name: String = "Зона",
    @SerialName("device_type") val deviceType: String = "pc",
    @SerialName("device_count") val deviceCount: Double = 0.0,
    @SerialName("assumed_occupancy_hours") val occupancyHours: Double = 0.0,
    @SerialName("tariff_mix") val tariffMix: List<SimMix> = emptyList(),
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class SimTariff(
    val id: String = "",
    val name: String = "Тариф",
    @SerialName("paid_hours") val paidHours: Double = 0.0,
    @SerialName("bonus_hours") val bonusHours: Double = 0.0,
    val price: Double = 0.0,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class SimFact(
    @SerialName("window_days") val windowDays: Int = 90,
    @SerialName("revenue_per_day") val revenuePerDay: Double = 0.0,
    @SerialName("revenue_per_month") val revenuePerMonth: Double = 0.0,
)

@Serializable
data class SimCompany(val id: String = "", val name: String = "Точка")

@Serializable
data class SimulationData(
    val companies: List<SimCompany> = emptyList(),
    @SerialName("company_id") val companyId: String? = null,
    val zones: List<SimZone> = emptyList(),
    val tariffs: List<SimTariff> = emptyList(),
    val fact: SimFact? = null,
)

data class ZoneProjection(val zone: SimZone, val blendedRate: Double, val shareSum: Double, val perDay: Double) {
    val perMonth get() = perDay * SimulationLogic.MONTH_DAYS
}

data class SimulationProjection(
    val zones: List<ZoneProjection>, val totalDevices: Double, val capacityRatePerHour: Double,
    val potentialPerDay: Double, val factPerDay: Double, val factPerMonth: Double,
    val impliedOccupancy: Double?, val assumedOccupancy: Double?,
) {
    val potentialPerMonth get() = potentialPerDay * SimulationLogic.MONTH_DAYS
    val gapPerMonth get() = potentialPerMonth - factPerMonth
    val occupancyGap get() = if (impliedOccupancy != null && assumedOccupancy != null && assumedOccupancy > 0) impliedOccupancy - assumedOccupancy else null
}

/** Порт `lib/domain/simulation.ts`: потенциал выручки зон и обратный расчёт загрузки. */
object SimulationLogic {
    const val MONTH_DAYS = 30

    private fun nn(v: Double) = if (v.isFinite() && v >= 0) v else 0.0

    /** ₸ за час сидения: бонусные часы входят в знаменатель («2+1 за 600» = 200 ₸/час). */
    fun rate(t: SimTariff): Double {
        val hours = nn(t.paidHours) + nn(t.bonusHours)
        return if (hours > 0) nn(t.price) / hours else 0.0
    }

    fun project(zones: List<SimZone>, tariffs: List<SimTariff>, fact: SimFact?): SimulationProjection {
        val rates = tariffs.filter { it.id.isNotEmpty() }.associate { it.id to rate(it) }
        val rows = zones.map { z ->
            var blended = 0.0; var shares = 0.0
            for (m in z.tariffMix) {
                val r = rates[m.tariffId] ?: continue
                blended += nn(m.sharePct) / 100 * r
                shares += nn(m.sharePct)
            }
            ZoneProjection(z, blended, shares, nn(z.deviceCount) * nn(z.occupancyHours) * blended)
        }
        val devices = rows.sumOf { nn(it.zone.deviceCount) }
        val capacity = rows.sumOf { nn(it.zone.deviceCount) * it.blendedRate }
        val factDay = nn(fact?.revenuePerDay ?: 0.0)
        val factMonth = fact?.revenuePerMonth?.let(::nn) ?: (factDay * MONTH_DAYS)
        return SimulationProjection(
            rows, devices, capacity, rows.sumOf { it.perDay }, factDay, factMonth,
            if (capacity > 0) factDay / capacity else null,
            if (devices > 0) rows.sumOf { nn(it.zone.deviceCount) * nn(it.zone.occupancyHours) } / devices else null,
        )
    }
}

class SimulationViewModel(private val api: FinanceApi) : LoadViewModel<SimulationData>() {
    private var companyId: String? = null

    override suspend fun fetch(): SimulationData =
        api.api.send<BizData<SimulationData>>(ApiRequest("/api/admin/simulation", query = if (companyId.isNullOrEmpty()) emptyMap() else mapOf("company_id" to companyId!!))).data

    fun show(company: String?) {
        if (company == companyId && state.value.hasData) return
        companyId = company
        reset(); reload()
    }

    /** Сохранить конфиг точки целиком (сервер заменяет зоны и тарифы). */
    suspend fun save(data: SimulationData, zones: List<SimZone>): String? = try {
        val body = buildJsonObject {
            put("company_id", data.companyId.orEmpty())
            put("zones", ApiJson.encodeToJsonElement(ListSerializer(SimZone.serializer()), zones))
            put("tariffs", ApiJson.encodeToJsonElement(ListSerializer(SimTariff.serializer()), data.tariffs))
        }
        api.api.sendUnit(ApiRequest.json("/api/admin/simulation", body))
        reload(); null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.userMessage() }
}

@Composable
fun SimulationScreen() {
    val env = LocalBusiness.current
    val vm = financeViewModel("simulation") { _, api -> SimulationViewModel(api) }
    val state by vm.state.collectAsState()
    var companyId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(companyId) { vm.show(companyId) }
    Column(Modifier.fillMaxSize()) {
        state.data?.companies?.takeIf { it.size > 1 }?.let { list ->
            Column(Modifier.padding(horizontal = Spacing.lg)) {
                HorizontalRow { InsightMenu(list.firstOrNull { it.id == (companyId ?: state.data?.companyId) }?.name ?: "Точка", list.map { it.id to it.name }) { companyId = it } }
            }
        }
        StateContainer(state, onRetry = vm::reload, modifier = Modifier.weight(1f)) { data ->
            OwnerScreenColumn { SimulationContent(data, vm, env.can("simulation.edit")) }
        }
    }
}

@Composable
private fun SimulationContent(data: SimulationData, vm: SimulationViewModel, canEdit: Boolean) {
    if (data.companyId == null) { OwnerFootnote("Точек нет — симуляцию строить не на чем."); return }
    val p = remember(data) { SimulationLogic.project(data.zones, data.tariffs, data.fact) }
    var editing by remember { mutableStateOf<Int?>(null) }
    HeroSummary(
        title = "Потенциал выручки в месяц",
        value = money(p.potentialPerMonth),
        caption = "Факт за ${data.fact?.windowDays ?: 90} дн.: ${money(p.factPerMonth)} в месяц",
        footer = listOf(
            (if (p.gapPerMonth >= 0) "Недобор" else "Выше потенциала") to money(kotlin.math.abs(p.gapPerMonth)),
            "Устройств" to p.totalDevices.toLong().toString(),
        ),
    )
    p.impliedOccupancy?.let { implied ->
        OwnerSection("Загрузка") {
            LineRow("Нужна, чтобы выйти на факт", "${"%.1f".format(java.util.Locale.US, implied).replace('.', ',')} ч в сутки на устройство")
            p.assumedOccupancy?.let { LineRow("Заложена в зонах", "${"%.1f".format(java.util.Locale.US, it).replace('.', ',')} ч") }
            p.occupancyGap?.let { gap ->
                Text(
                    if (gap < 0) "Фактически клуб загружен меньше заложенного на ${"%.1f".format(java.util.Locale.US, -gap).replace('.', ',')} ч." else "Фактическая загрузка выше заложенной на ${"%.1f".format(java.util.Locale.US, gap).replace('.', ',')} ч.",
                    color = Orda.colors.textMuted, fontSize = 13.sp,
                )
            }
        }
    }
    OwnerSection("Зоны") {
        if (p.zones.isEmpty()) Text("Зон нет — задайте их на сайте.", color = Orda.colors.textDim, fontSize = 13.sp)
        p.zones.forEachIndexed { i, z ->
            Column(
                Modifier.fillMaxWidth().then(if (canEdit) Modifier.clickable { editing = i } else Modifier).padding(vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    Text(z.zone.name, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(money(z.perMonth), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("${z.zone.deviceCount.toLong()} устр. × ${z.zone.occupancyHours.toString().removeSuffix(".0")} ч × ${money(z.blendedRate)}/ч", color = Orda.colors.textDim, fontSize = 12.sp)
                if (kotlin.math.abs(z.shareSum - 100) > 0.5) Text("Доли тарифов в сумме ${z.shareSum.toLong()}% — проверьте микс на сайте.", color = Orda.colors.warning, fontSize = 12.sp)
            }
        }
        if (canEdit) OwnerFootnote("Нажмите на зону, чтобы поменять число устройств и загрузку.")
    }
    if (p.zones.isNotEmpty() && data.tariffs.isNotEmpty()) {
        OwnerSection("Тарифы") {
            data.tariffs.forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, color = Orda.colors.text, fontSize = 15.sp)
                        Text("${t.paidHours.toString().removeSuffix(".0")}+${t.bonusHours.toString().removeSuffix(".0")} ч за ${money(t.price)}", color = Orda.colors.textDim, fontSize = 12.sp)
                    }
                    Text("${money(SimulationLogic.rate(t))}/ч", color = Orda.colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    editing?.let { idx ->
        data.zones.getOrNull(idx)?.let { zone -> ZoneSheet(zone, onDismiss = { editing = null }) { changed ->
            vm.save(data, data.zones.mapIndexed { i, z -> if (i == idx) changed else z })
        } }
    }
}

@Composable
private fun ZoneSheet(zone: SimZone, onDismiss: () -> Unit, onSave: suspend (SimZone) -> String?) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(zone.deviceCount.toLong().toString()) }
    var hours by remember { mutableStateOf(zone.occupancyHours.toString().removeSuffix(".0")) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    OrdaSheet(zone.name, onDismiss) {
        AmountField("Устройств", devices, { devices = it.filter(Char::isDigit) }, placeholder = "0")
        AmountField("Загрузка, часов в сутки на устройство", hours, { hours = it })
        error?.let { ErrorBanner(it) }
        PrimaryButton("Сохранить", Modifier.fillMaxWidth(), loading = saving) {
            val h = parseAmount(hours)
            if (h > 24) { error = "В сутках не больше 24 часов."; return@PrimaryButton }
            saving = true; error = null
            scope.launch {
                val failure = onSave(zone.copy(deviceCount = parseAmount(devices), occupancyHours = h))
                saving = false
                if (failure == null) onDismiss() else error = failure
            }
        }
    }
}
