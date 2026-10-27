package kz.ordaops.android.feature.business.finance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kz.ordaops.android.feature.business.Company

// ── Долги с точки: /api/admin/point-debts ───────────────────────────────────

/**
 * Долг клиента, записанный оператором. Приходит двумя видами строк: новые —
 * позиции с товаром (сумма в `total_amount`), старые — одна строка на человека
 * (`amount`). Читаем оба, иначе список показывал бы нули при верном итоге.
 */
@Serializable
data class PointDebt(
    val id: String = "",
    @SerialName("client_name") val clientName: String? = null,
    /** Имя должника: оператор или клиент. Сервер собирает его сам — предпочитаем его. */
    @SerialName("debtor_name") val debtorName: String? = null,
    @SerialName("item_name") val itemName: String? = null,
    val quantity: Double? = null,
    @SerialName("total_amount") val totalAmount: Double? = null,
    @SerialName("amount") val amountLegacy: Double? = null,
    val comment: String? = null,
    val status: String = "open",
    @SerialName("company_id") val companyId: String? = null,
) {
    val amount: Double get() = totalAmount ?: amountLegacy ?: 0.0

    /** Кто должен. */
    val title: String get() = debtorName?.takeIf { it.isNotBlank() } ?: clientName?.takeIf { it.isNotBlank() } ?: "Должник"

    /** Что взяли: «Сок × 2», а если позиции нет — комментарий. */
    val subtitle: String?
        get() {
            val item = itemName?.takeIf { it.isNotBlank() }?.let { n -> quantity?.takeIf { it > 1 }?.let { "$n × ${if (it == Math.floor(it)) it.toLong() else it}" } ?: n }
            return item ?: comment?.takeIf { it.isNotBlank() }
        }
    val isPaid: Boolean get() = status == "paid" || status == "closed"
    val statusLabel: String
        get() = when (status) {
            "open", "active", "pending" -> "Не погашен"
            "paid", "closed" -> "Погашен"
            "written_off" -> "Списан"
            else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
}

/** Долг старого учёта (таблица `debts`): одна строка на человека. */
@Serializable
data class LegacyDebt(
    val id: String = "",
    @SerialName("company_id") val companyId: String? = null,
    @SerialName("debtor_name") val debtorName: String? = null,
    val amount: Double = 0.0,
    val comment: String? = null,
)

@Serializable
data class PointDebtTotals(val count: Int? = null, val amount: Double? = null)

/** Ответ `GET /api/admin/point-debts?weekStart=…`. */
@Serializable
data class PointDebtWeek(
    val weekStart: String = "",
    val weekEnd: String = "",
    val companies: List<Company> = emptyList(),
    val items: List<PointDebt> = emptyList(),
    val totals: PointDebtTotals = PointDebtTotals(),
    val legacyAggregates: List<LegacyDebt> = emptyList(),
    val legacyTotals: PointDebtTotals = PointDebtTotals(),
) {
    val totalAmount: Double get() = totals.amount ?: 0.0
    val totalCount: Int get() = totals.count ?: 0
    val unpaidAmount: Double get() = items.filter { !it.isPaid }.sumOf { it.amount }
}
