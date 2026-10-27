package kz.ordaops.android.feature.business.finance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kz.ordaops.android.feature.business.Company
import java.time.LocalDate

// «Эффективность продавцов»: /api/admin/sales-kpi, /payout, /plans. Порт SalesKpiModels.swift.

@Serializable
data class KpiPayoutRow(
    @SerialName("cashier_id") val cashierId: String = "",
    val name: String = "Без имени",
    val shifts: Int = 0,
    val revenue: Double = 0.0,
    val receipts: Int = 0,
    val score: Double? = null,
    val status: String = "",
    @SerialName("status_label") val statusLabel: String = "",
    val amount: Double = 0.0,
    val paid: Boolean = false,
    @SerialName("zero_reason") val zeroReason: String? = null,
    val strengths: List<String> = emptyList(),
    val weaknesses: List<String> = emptyList(),
)

@Serializable
data class KpiPayoutTotals(
    @SerialName("to_pay") val toPay: Double = 0.0,
    @SerialName("to_pay_people") val toPayPeople: Int = 0,
    @SerialName("already_paid") val alreadyPaid: Double = 0.0,
    @SerialName("already_paid_people") val alreadyPaidPeople: Int = 0,
    val people: Int = 0,
)

@Serializable
data class KpiPayoutSettings(
    @SerialName("monthly_bonus_strong") val strong: Double = 0.0,
    @SerialName("monthly_bonus_top") val top: Double = 0.0,
    @SerialName("min_qualifying_shifts") val minQualifyingShifts: Int = 0,
)

/** Кому доплатить за месяц: ответ `/api/admin/sales-kpi/payout`. */
@Serializable
data class KpiPayout(
    val month: String = "",
    @SerialName("company_id") val companyId: String = "",
    val rows: List<KpiPayoutRow> = emptyList(),
    val totals: KpiPayoutTotals = KpiPayoutTotals(),
    val settings: KpiPayoutSettings = KpiPayoutSettings(),
)

@Serializable
data class KpiShift(
    val date: String = "",
    val shift: String = "day",
    @SerialName("shift_id") val shiftId: String? = null,
    @SerialName("cashier_name") val cashierName: String? = null,
    val revenue: Double = 0.0,
    @SerialName("expected_revenue") val expectedRevenue: Double? = null,
    val receipts: Int = 0,
    val verdict: String = "NORMAL",
    val evidence: List<String> = emptyList(),
) {
    val shiftLabel: String get() = if (shift == "night") "Ночь" else "День"
    val averageReceipt: Double get() = if (receipts > 0) revenue / receipts else 0.0
    val deviation: Double? get() = expectedRevenue?.takeIf { it > 0 }?.let { (revenue - it) / it }
}

@Serializable
data class KpiCashier(
    @SerialName("cashier_id") val cashierId: String = "",
    val name: String = "Продавец",
    val shifts: Int = 0,
    val revenue: Double = 0.0,
    val receipts: Int = 0,
    val score: Double? = null,
    val status: String = "OK",
    val strengths: List<String> = emptyList(),
    val weaknesses: List<String> = emptyList(),
    @SerialName("training_flag") val trainingFlag: Boolean = false,
    @SerialName("training_reason") val trainingReason: String? = null,
) {
    val averageReceipt: Double get() = if (receipts > 0) revenue / receipts else 0.0

    /** Оценка словами: 1.0 — норма, 1.12 — «на 12% лучше нормы». */
    val scoreText: String get() = SalesKpiLogic.scoreText(score)
    val statusText: String get() = SalesKpiLogic.statusLabel(status)
}

@Serializable
data class KpiTotals(val revenue: Double = 0.0, val receipts: Int = 0, val shifts: Int = 0) {
    val averageReceipt: Double get() = if (receipts > 0) revenue / receipts else 0.0
}

@Serializable
data class KpiCompanyRef(val id: String? = null, val name: String? = null)

@Serializable
data class KpiSettingsRef(@SerialName("min_qualifying_shifts") val minQualifyingShifts: Int? = null)

/**
 * Ответ `GET /api/admin/sales-kpi`. Без `company_id` у сети из нескольких магазинов сервер отвечает
 * только списком магазинов (`needs_company`) — смешивать их в один рейтинг нельзя; с `company_id`
 * (или если магазин один) — полным отчётом: продавцы, смены, итоги.
 */
@Serializable
data class KpiReport(
    val stores: List<Company> = emptyList(),
    @SerialName("no_store") val noStore: Boolean = false,
    @SerialName("needs_company") val needsCompany: Boolean = false,
    val company: KpiCompanyRef? = null,
    val cashiers: List<KpiCashier> = emptyList(),
    val totals: KpiTotals = KpiTotals(),
    val settings: KpiSettingsRef? = null,
    val shifts: List<KpiShift> = emptyList(),
) {
    val minQualifyingShifts: Int get() = settings?.minQualifyingShifts ?: 6
}

@Serializable
data class KpiWeather(@SerialName("bucket_label") val bucketLabel: String? = null, val known: Boolean? = null)

@Serializable
data class KpiPlan(
    val date: String = "",
    val shift: String = "day",
    val control: Double? = null,
    val b1: Double? = null,
    val b2: Double? = null,
    val b3: Double? = null,
    @SerialName("expected_revenue") val expectedRevenue: Double? = null,
    @SerialName("expected_receipts") val expectedReceipts: Double? = null,
    val locked: Boolean = false,
    val weather: KpiWeather? = null,
) {
    val shiftLabel: String get() = if (shift == "night") "Ночь" else "День"
    val hasTargets: Boolean get() = b1 != null
    val weatherLabel: String? get() = if (weather?.known == true) weather.bucketLabel else null
}

@Serializable
data class KpiMonthly(
    /** Индекс цели месяца, который реально применён (1 — как обычно, пока не утверждён). */
    val effective: Double? = null,
)

@Serializable
data class KpiPlans(val plans: List<KpiPlan> = emptyList(), val monthly: List<KpiMonthly> = emptyList()) {
    val monthlyIndex: Double? get() = monthly.firstOrNull()?.effective

    val monthlyText: String?
        get() {
            val index = monthlyIndex ?: return null
            val delta = Math.round((index - 1) * 100).toInt()
            if (Math.abs(delta) < 2) return "Цели как обычно"
            return if (delta > 0) "Цели выше обычного на $delta%" else "Цели ниже обычного на ${Math.abs(delta)}%"
        }
}

/** Подписи и склонения экрана — чистые функции для теста. */
object SalesKpiLogic {
    /** Метрики модели человеческими словами: сервер отдаёт ключи, а не подписи. */
    fun metricLabel(key: String): String = when (key) {
        "avg_ticket" -> "Средний чек"
        "items_per_receipt" -> "Товаров на чек"
        "attach_rate" -> "Допродажи"
        "revenue_efficiency" -> "Отдача с покупателя"
        "plan_attainment" -> "Выполнение плана"
        "product_knowledge" -> "Знание товара"
        else -> key
    }

    fun scoreText(score: Double?): String {
        if (score == null) return "нет оценки"
        val delta = Math.round((score - 1) * 100).toInt()
        return when {
            delta == 0 -> "как норма"
            delta > 0 -> "на $delta% лучше нормы"
            else -> "на ${Math.abs(delta)}% ниже нормы"
        }
    }

    fun statusLabel(status: String): String = when (status) {
        "TOP" -> "Топ"
        "STRONG" -> "Сильный"
        "OK", "NORMAL" -> "Норма"
        "WEAK" -> "Слабее нормы"
        "LOW_SAMPLE", "FEW_SHIFTS" -> "Мало смен"
        else -> status.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Первый и последний день месяца `2026-08` → (`2026-08-01`, `2026-08-31`). */
    fun monthBounds(month: String): Pair<String, String> {
        val first = runCatching { LocalDate.parse("$month-01") }.getOrNull() ?: return month to month
        return first.toString() to first.withDayOfMonth(first.lengthOfMonth()).toString()
    }

    /** Порядок разбора смен: сначала «вопрос к продавцу», потом сильные, потом по свежести. */
    fun reviewWeight(verdict: String): Int = when (verdict) {
        "POSSIBLE_CASHIER_ISSUE" -> 0
        "STRONG_CASHIER" -> 1
        "LOW_DEMAND", "HIGH_DEMAND" -> 2
        else -> 3
    }

    fun reviewOrder(shifts: List<KpiShift>): List<KpiShift> =
        shifts.sortedWith(compareBy<KpiShift> { reviewWeight(it.verdict) }.thenByDescending { it.date })
}
