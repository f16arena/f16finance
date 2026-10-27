package kz.ordaops.android.feature.business.finance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// Как ведёт себя точка — по нише (`companies.industry`), а не по коду F16, и зоны
// выручки. Порт `lib/core/point-profile.ts` и `lib/core/revenue-zones.ts` с сайта:
// iOS на момент порта ещё держал поведение по кодам arena/ramen/extra.

/** Точка из `/api/admin/companies` — со всем, что нужно формам прихода и расхода. */
@Serializable
data class FinCompany(
    val id: String = "",
    val name: String = "Точка",
    val code: String? = null,
    val industry: String? = null,
    @SerialName("exclude_from_totals") val excludeFromTotals: Boolean? = null,
    @SerialName("archived_at") val archivedAt: String? = null,
    @SerialName("revenue_zones") val revenueZones: JsonElement? = null,
)

/** Зона выручки точки: PS5, VR, бильярд — что угодно. Ключ пишется в `incomes.zone`. */
data class RevenueZone(val key: String, val label: String, val active: Boolean)

/** Профиль точки: какие поля показывать в форме прихода. */
data class PointProfile(
    val kind: String,
    /** Что писать в `incomes.zone` при приходе одной суммой. */
    val incomeZone: String,
    /** Приход вносится двумя строками: PS5 и VR. */
    val splitPsVr: Boolean,
    /** Поле «онлайн» (оплата бронирований через сайт). */
    val showOnline: Boolean,
    val hasNightShift: Boolean,
)

object PointLogic {
    private val industries = setOf("club", "ps_club", "shop", "food", "service", "other")

    /** Запасной вариант для точки без ниши — только по коду, как на сайте. */
    private val legacyByCode = mapOf("arena" to "club", "ramen" to "shop", "extra" to "ps_club")

    fun kind(company: FinCompany?): String {
        val industry = company?.industry?.trim().orEmpty()
        if (industry in industries) return industry
        return legacyByCode[company?.code?.trim()?.lowercase()] ?: "other"
    }

    fun profile(company: FinCompany?): PointProfile = when (val k = kind(company)) {
        "club" -> PointProfile(k, "pc", splitPsVr = false, showOnline = true, hasNightShift = true)
        "ps_club" -> PointProfile(k, "extra", splitPsVr = true, showOnline = false, hasNightShift = false)
        "shop", "food" -> PointProfile(k, "ramen", splitPsVr = false, showOnline = false, hasNightShift = true)
        else -> PointProfile(k, "other", splitPsVr = false, showOnline = false, hasNightShift = true)
    }

    /** Зона для прихода одной суммой: `pc`, `ramen` или `other`. */
    fun singleZone(company: FinCompany?): String = profile(company).incomeZone.let { if (it == "pc" || it == "ramen") it else "other" }

    /**
     * Зоны точки. Не задано (`null`) — как раньше: PS-зал получает PS5 + VR, остальные
     * вносят выручку одной суммой. Задан список — он решает (даже пустой).
     */
    fun zones(company: FinCompany?): List<RevenueZone> {
        if (company == null) return emptyList()
        val raw = company.revenueZones
        if (raw is JsonArray) {
            return raw.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val key = (o["key"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val label = (o["label"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: key
                val active = (o["active"] as? JsonPrimitive)?.booleanOrNull != false
                RevenueZone(key, label, active)
            }
        }
        return if (profile(company).splitPsVr) listOf(RevenueZone("ps5", "PS5", true), RevenueZone("vr", "VR", true)) else emptyList()
    }

    fun activeZones(company: FinCompany?): List<RevenueZone> = zones(company).filter { it.active }

    /** Точка вне общих итогов: флаг точки, а если его нет — старый признак (код `extra` / «F16 Extra»). */
    fun isExcludedFromTotals(company: FinCompany): Boolean =
        company.excludeFromTotals ?: (company.code?.lowercase() == "extra" || company.name == "F16 Extra")
}

/** Сумма по зоне: наличные и безнал отдельно. */
data class ZoneAmount(val cash: Double = 0.0, val cashless: Double = 0.0) {
    val total: Double get() = cash + cashless
}
