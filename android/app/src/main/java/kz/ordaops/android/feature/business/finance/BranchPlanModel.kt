package kz.ordaops.android.feature.business.finance

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.doubleOrNull
import java.util.UUID

// Финмодель новой точки — порт расчёта с сайта (`app/(main)/branch-plan/page-body.tsx`).
// Считается на устройстве, как и на сайте: сервер хранит только черновик
// (`/api/admin/branch-plan`: список, один черновик, save, delete).

data class BpComponent(val id: String, val name: String, val price: Double)
data class BpPcConfig(val id: String, val name: String, val quantity: Double, val components: List<BpComponent>) {
    /** Цена одного ПК — сумма комплектующих. */
    val unitPrice: Double get() = components.sumOf { bpNum(it.price) }
}
data class BpCapexRow(val id: String, val name: String, val unitPrice: Double, val quantity: Double)
data class BpTariff(val id: String, val name: String, val paidHours: Double, val bonusHours: Double, val price: Double) {
    /** ₸ за час = цена ÷ (оплаченные + бонусные часы). */
    val rate: Double get() {
        val hours = bpNum(paidHours) + bpNum(bonusHours)
        return if (hours > 0) bpNum(price) / hours else 0.0
    }
}
data class BpZoneMix(val tariffId: String, val sharePct: Double)
data class BpZone(val id: String, val name: String, val deviceCount: Double, val occupancyHours: Double, val mix: List<BpZoneMix>)
data class BpOpex(val id: String, val name: String, val amount: Double, val percentOfRevenue: Boolean)
data class BpScenarioCfg(val revenueMult: Double, val opexMult: Double)
data class BpScenarios(val best: BpScenarioCfg, val expected: BpScenarioCfg, val worst: BpScenarioCfg)
data class BpRamp(val enabled: Boolean, val months: List<Double>)

data class BranchDraft(
    val id: String? = null,
    val name: String,
    val pcConfigs: List<BpPcConfig>,
    val capex: List<BpCapexRow>,
    val tariffs: List<BpTariff>,
    val zones: List<BpZone>,
    val opex: List<BpOpex>,
    val scenarios: BpScenarios,
    val ramp: BpRamp,
)


/** Строка списка черновиков. */
data class BranchDraftItem(val id: String, val name: String, val updatedAt: String?)

/** Неотрицательное число, всё прочее — 0: как `num()` на сайте. */
fun bpNum(v: Double): Double = if (v.isFinite() && v >= 0) v else 0.0

/** То же для текста из поля ввода (запятая как разделитель). */
fun bpNum(text: String): Double = bpNum(text.trim().replace(',', '.').toDoubleOrNull() ?: 0.0)

fun bpUid(): String = UUID.randomUUID().toString().replace("-", "").take(10)

/** Шаблон новой точки — те же цифры, что у `defaultDraft()` на сайте. */
fun defaultBranchDraft(): BranchDraft {
    val t21 = BpTariff(bpUid(), "2+1", 2.0, 1.0, 600.0)
    val t32 = BpTariff(bpUid(), "3+2", 3.0, 2.0, 1000.0)
    val night = BpTariff(bpUid(), "Ночь", 8.0, 0.0, 3500.0)
    fun c(name: String, price: Double) = BpComponent(bpUid(), name, price)
    fun x(name: String, price: Double, qty: Double) = BpCapexRow(bpUid(), name, price, qty)
    return BranchDraft(
        name = "Новая точка",
        pcConfigs = listOf(
            BpPcConfig(
                bpUid(), "Премиум ПК", 20.0,
                listOf(
                    c("Процессор Intel i5-13600KF", 130_000.0), c("Видеокарта RTX 4070", 250_000.0),
                    c("ОЗУ 32GB DDR5", 50_000.0), c("Материнская плата B760", 60_000.0),
                    c("SSD 1TB NVMe", 30_000.0), c("Блок питания 750W Gold", 35_000.0),
                    c("Корпус", 25_000.0), c("Кулер CPU", 20_000.0), c("Монитор 27\" 165Hz", 110_000.0),
                ),
            ),
            BpPcConfig(
                bpUid(), "Стандарт ПК", 10.0,
                listOf(
                    c("Процессор Intel i5-12400F", 80_000.0), c("Видеокарта RTX 4060", 170_000.0),
                    c("ОЗУ 16GB DDR4", 25_000.0), c("Материнская плата B660", 40_000.0),
                    c("SSD 500GB NVMe", 18_000.0), c("Блок питания 650W", 25_000.0),
                    c("Корпус", 18_000.0), c("Кулер CPU", 12_000.0), c("Монитор 24\" 144Hz", 75_000.0),
                ),
            ),
        ),
        capex = listOf(
            x("Геймерское кресло DXRacer", 80_000.0, 30.0), x("Стол игровой + перегородка", 35_000.0, 30.0),
            x("Периферия (мышь/клава/гарнитура)", 25_000.0, 30.0), x("Коврик игровой", 5_000.0, 30.0),
            x("Сетевое оборудование (роутер/свитчи)", 250_000.0, 1.0), x("Кондиционеры", 250_000.0, 3.0),
            x("Ресепшн + барная стойка", 400_000.0, 1.0), x("Ремонт и отделка", 2_500_000.0, 1.0),
            x("Депозит аренды", 800_000.0, 1.0), x("Вывеска и брендинг", 500_000.0, 1.0),
            x("Камеры/охранка", 400_000.0, 1.0), x("Кассовое оборудование", 250_000.0, 1.0),
        ),
        tariffs = listOf(t21, t32, night),
        zones = listOf(
            BpZone(bpUid(), "Премиум зона", 20.0, 8.0, listOf(BpZoneMix(t21.id, 40.0), BpZoneMix(t32.id, 50.0), BpZoneMix(night.id, 10.0))),
            BpZone(bpUid(), "Стандарт зона", 10.0, 6.0, listOf(BpZoneMix(t21.id, 70.0), BpZoneMix(t32.id, 20.0), BpZoneMix(night.id, 10.0))),
        ),
        opex = listOf(
            BpOpex(bpUid(), "Аренда", 800_000.0, false), BpOpex(bpUid(), "ФОТ операторов", 900_000.0, false),
            BpOpex(bpUid(), "Электричество", 300_000.0, false), BpOpex(bpUid(), "Интернет", 40_000.0, false),
            BpOpex(bpUid(), "Расходники / бар", 120_000.0, false), BpOpex(bpUid(), "Маркетинг", 100_000.0, false),
            BpOpex(bpUid(), "Налог (3% от выручки)", 3.0, true),
        ),
        scenarios = BpScenarios(BpScenarioCfg(1.3, 1.0), BpScenarioCfg(1.0, 1.0), BpScenarioCfg(0.7, 1.15)),
        ramp = BpRamp(true, listOf(0.3, 0.5, 0.75, 1.0)),
    )
}

// ── Расчёт ───────────────────────────────────────────────────────────────────

data class BpScenarioResult(val revenue: Double, val opex: Double, val profit: Double, val payback: Double?)
data class BpZoneRevenue(val zone: BpZone, val perMonth: Double)
data class BpCashPoint(val month: Int, val cash: Long)

data class BranchCalc(
    val pcCapex: Double,
    val otherCapex: Double,
    val totalCapex: Double,
    val baseRevenue: Double,
    /** Ожидаемый сценарий: выручка, расходы (фикс + %), прибыль. */
    val revenue: Double,
    val opexFixed: Double,
    val opexPercent: Double,
    val opex: Double,
    val profit: Double,
    val paybackMonths: Double?,
    val best: BpScenarioResult,
    val expected: BpScenarioResult,
    val worst: BpScenarioResult,
    val cashFlow: List<BpCashPoint>,
    val zones: List<BpZoneRevenue>,
    /** Сколько часов в сутки на устройство нужно, чтобы выйти в ноль. */
    val breakEvenHours: Double?,
    val totalDevices: Double,
)

/** Те же формулы, что `calc` в `page-body.tsx`: ничего не упрощено и не переосмыслено. */
fun calcBranch(d: BranchDraft): BranchCalc {
    val pcCapex = d.pcConfigs.sumOf { bpNum(it.quantity) * it.unitPrice }
    val otherCapex = d.capex.sumOf { bpNum(it.unitPrice) * bpNum(it.quantity) }
    val totalCapex = pcCapex + otherCapex

    val tariffById = d.tariffs.associateBy { it.id }
    var baseRevenue = 0.0
    val zoneBreakdown = d.zones.map { z ->
        var blended = 0.0
        for (m in z.mix) {
            val t = tariffById[m.tariffId] ?: continue
            blended += (bpNum(m.sharePct) / 100) * t.rate
        }
        val perMonth = bpNum(z.deviceCount) * bpNum(z.occupancyHours) * blended * 30
        baseRevenue += perMonth
        BpZoneRevenue(z, perMonth)
    }

    fun opexFor(revenue: Double, mult: Double): Triple<Double, Double, Double> {
        var fixed = 0.0
        var percent = 0.0
        for (o in d.opex) {
            if (!o.percentOfRevenue) fixed += bpNum(o.amount) * mult else percent += (bpNum(o.amount) / 100) * revenue
        }
        return Triple(fixed, percent, fixed + percent)
    }

    val exp = d.scenarios.expected
    val expRevenue = baseRevenue * bpNum(exp.revenueMult)
    val (fixed, percent, total) = opexFor(expRevenue, bpNum(exp.opexMult))
    val profit = expRevenue - total
    val payback = if (profit > 0) totalCapex / profit else null

    fun scenario(cfg: BpScenarioCfg): BpScenarioResult {
        val rev = baseRevenue * bpNum(cfg.revenueMult)
        val op = opexFor(rev, bpNum(cfg.opexMult)).third
        val p = rev - op
        return BpScenarioResult(rev, op, p, if (p > 0) totalCapex / p else null)
    }

    // Кэш по месяцам с выходом на нагрузку: фикс не зависит от выручки, процент — зависит.
    val ramp = d.ramp
    val cash = ArrayList<BpCashPoint>()
    var acc = -totalCapex
    cash += BpCashPoint(0, Math.round(acc))
    for (i in 1..24) {
        val idx = i - 1
        val mult = if (ramp.enabled) {
            if (idx < ramp.months.size) bpNum(ramp.months[idx]) else bpNum(ramp.months.lastOrNull() ?: 1.0)
        } else 1.0
        val monthRev = expRevenue * mult
        val monthOpex = fixed + (percent / maxOf(expRevenue, 1.0)) * monthRev
        acc += monthRev - monthOpex
        cash += BpCashPoint(i, Math.round(acc))
    }

    val totalDevices = d.zones.sumOf { bpNum(it.deviceCount) }
    val weightedRate = if (totalDevices > 0) {
        zoneBreakdown.sumOf { z ->
            val dev = bpNum(z.zone.deviceCount)
            val occ = bpNum(z.zone.occupancyHours)
            val rate = if (dev > 0 && occ > 0) z.perMonth / (dev * occ * 30) else 0.0
            dev * rate
        } / totalDevices
    } else 0.0
    val percentShare = d.opex.filter { it.percentOfRevenue }.sumOf { bpNum(it.amount) / 100 }
    val denom = totalDevices * 30 * weightedRate * (1 - percentShare)
    val breakEven = if (denom > 0) fixed / denom else null

    return BranchCalc(
        pcCapex, otherCapex, totalCapex, baseRevenue,
        expRevenue, fixed, percent, total, profit, payback,
        scenario(d.scenarios.best), scenario(exp), scenario(d.scenarios.worst),
        cash, zoneBreakdown, breakEven, totalDevices,
    )
}

// ── JSON ─────────────────────────────────────────────────────────────────────

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String, default: Double = 0.0): Double =
    (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.replace(',', '.')?.toDoubleOrNull() } ?: default
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.idOrNew(): String = text("id")?.takeIf { it.isNotBlank() } ?: bpUid()

/**
 * Черновик с сервера: `{ id, name, payload: { pc_configs, capex, … } }`. Чего в нём нет,
 * берём из шаблона — как `mergeDraft` на сайте. Лишние и кривые поля не роняют разбор.
 */
fun parseBranchDraft(draft: JsonObject): BranchDraft {
    val base = defaultBranchDraft()
    val payload = draft["payload"].obj() ?: JsonObject(emptyMap())
    fun scen(o: JsonObject?, fallback: BpScenarioCfg) =
        if (o == null) fallback else BpScenarioCfg(o.number("revenue_mult", fallback.revenueMult), o.number("opex_mult", fallback.opexMult))
    val sc = payload["scenarios"].obj()
    val rampObj = payload["ramp_up"].obj()
    return BranchDraft(
        id = draft.text("id"),
        name = draft.text("name")?.takeIf { it.isNotBlank() } ?: base.name,
        pcConfigs = payload.array("pc_configs")?.mapNotNull { it.obj() }?.map { c ->
            BpPcConfig(
                c.idOrNew(), c.text("name").orEmpty(), c.number("quantity"),
                c.array("components")?.mapNotNull { it.obj() }?.map { BpComponent(it.idOrNew(), it.text("name").orEmpty(), it.number("price")) }.orEmpty(),
            )
        } ?: base.pcConfigs,
        capex = payload.array("capex")?.mapNotNull { it.obj() }?.map {
            BpCapexRow(it.idOrNew(), it.text("name").orEmpty(), it.number("unit_price"), it.number("quantity"))
        } ?: base.capex,
        tariffs = payload.array("tariffs")?.mapNotNull { it.obj() }?.map {
            BpTariff(it.idOrNew(), it.text("name").orEmpty(), it.number("paid_hours"), it.number("bonus_hours"), it.number("price"))
        } ?: base.tariffs,
        zones = payload.array("zones")?.mapNotNull { it.obj() }?.map { z ->
            BpZone(
                z.idOrNew(), z.text("name").orEmpty(), z.number("device_count"), z.number("occupancy_hours"),
                z.array("tariff_mix")?.mapNotNull { it.obj() }?.mapNotNull { m ->
                    m.text("tariff_id")?.let { BpZoneMix(it, m.number("share_pct")) }
                }.orEmpty(),
            )
        } ?: base.zones,
        opex = payload.array("opex")?.mapNotNull { it.obj() }?.map {
            BpOpex(it.idOrNew(), it.text("name").orEmpty(), it.number("amount"), it.text("kind") == "percent_of_revenue")
        } ?: base.opex,
        scenarios = if (sc == null) base.scenarios else BpScenarios(
            scen(sc["best"].obj(), base.scenarios.best), scen(sc["expected"].obj(), base.scenarios.expected), scen(sc["worst"].obj(), base.scenarios.worst),
        ),
        ramp = if (rampObj == null) base.ramp else BpRamp(
            (rampObj["enabled"] as? JsonPrimitive)?.contentOrNull?.let { it == "true" } ?: base.ramp.enabled,
            rampObj.array("months")?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: base.ramp.months,
        ),
    )
}

/** Список черновиков: `{ ok, data: { drafts: [...] } }`. */
fun parseBranchDraftList(root: JsonElement): List<BranchDraftItem> {
    val data = root.obj()?.get("data").obj() ?: return emptyList()
    return data.array("drafts")?.mapNotNull { it.obj() }?.mapNotNull { o ->
        val id = o.text("id") ?: return@mapNotNull null
        BranchDraftItem(id, o.text("name")?.takeIf { it.isNotBlank() } ?: "Без названия", o.text("updated_at"))
    }.orEmpty()
}

/** Один черновик: `{ ok, data: { draft } }`. */
fun parseBranchDraftResponse(root: JsonElement): BranchDraft? =
    root.obj()?.get("data").obj()?.get("draft").obj()?.let(::parseBranchDraft)

private fun numJson(v: Double): JsonPrimitive =
    if (v.isFinite() && v == Math.floor(v) && Math.abs(v) < 1e15) JsonPrimitive(v.toLong()) else JsonPrimitive(v)

/** Тело `payload` для `action: save` — те же ключи, что шлёт сайт. */
fun BranchDraft.payloadJson(): JsonObject = buildJsonObject {
    put("pc_configs", buildJsonArray {
        pcConfigs.forEach { c ->
            add(buildJsonObject {
                put("id", c.id); put("name", c.name); put("quantity", numJson(c.quantity))
                put("components", buildJsonArray {
                    c.components.forEach { k -> add(buildJsonObject { put("id", k.id); put("name", k.name); put("price", numJson(k.price)) }) }
                })
            })
        }
    })
    put("capex", buildJsonArray {
        capex.forEach { r ->
            add(buildJsonObject { put("id", r.id); put("name", r.name); put("unit_price", numJson(r.unitPrice)); put("quantity", numJson(r.quantity)) })
        }
    })
    put("tariffs", buildJsonArray {
        tariffs.forEach { t ->
            add(buildJsonObject {
                put("id", t.id); put("name", t.name); put("paid_hours", numJson(t.paidHours))
                put("bonus_hours", numJson(t.bonusHours)); put("price", numJson(t.price))
            })
        }
    })
    put("zones", buildJsonArray {
        zones.forEach { z ->
            add(buildJsonObject {
                put("id", z.id); put("name", z.name); put("device_count", numJson(z.deviceCount)); put("occupancy_hours", numJson(z.occupancyHours))
                put("tariff_mix", buildJsonArray {
                    z.mix.forEach { m -> add(buildJsonObject { put("tariff_id", m.tariffId); put("share_pct", numJson(m.sharePct)) }) }
                })
            })
        }
    })
    put("opex", buildJsonArray {
        opex.forEach { o ->
            add(buildJsonObject {
                put("id", o.id); put("name", o.name); put("amount", numJson(o.amount))
                put("kind", if (o.percentOfRevenue) "percent_of_revenue" else "fixed")
            })
        }
    })
    fun scen(s: BpScenarioCfg) = buildJsonObject { put("revenue_mult", numJson(s.revenueMult)); put("opex_mult", numJson(s.opexMult)) }
    put("scenarios", buildJsonObject { put("best", scen(scenarios.best)); put("expected", scen(scenarios.expected)); put("worst", scen(scenarios.worst)) })
    put("ramp_up", buildJsonObject {
        put("enabled", ramp.enabled)
        put("months", buildJsonArray { ramp.months.forEach { add(numJson(it)) } })
    })
}
