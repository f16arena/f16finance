package kz.ordaops.android.feature.operator.shift

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.math.abs

// Модели продажи — OperatorSale.swift.

/** Товар витрины, доступный к продаже. */
@Serializable
data class SaleCatalogItem(
    val id: String = "",
    val name: String = "Без названия",
    val barcode: String? = null,
    val unit: String? = null,
    @SerialName("sale_price") val salePrice: Double? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    /** Остаток на витрине — сервер считает его отдельно от складского. */
    @SerialName("display_qty") val displayQuantity: Double = 0.0,
) {
    val isInStock: Boolean get() = displayQuantity > 0
}

/** Проведённый чек — для графика выручки смены. */
@Serializable
data class RecentSale(
    val id: String = "",
    @SerialName("total_amount") val totalAmount: Double = 0.0,
    @SerialName("cash_amount") val cashAmount: Double = 0.0,
    @SerialName("kaspi_amount") val kaspiAmount: Double = 0.0,
    @SerialName("sold_at") val soldAt: String? = null,
    @SerialName("payment_method") val paymentMethod: String? = null,
    val items: List<JsonElement> = emptyList(),
) {
    val soldAtMillis: Long? get() = parseIsoMillis(soldAt)
}

/** Вариант услуги: «30 мин — 500 ₸». duration_minutes есть у услуг с таймером. */
@Serializable
data class SaleServiceVariant(
    val id: String = "",
    val name: String = "",
    val price: Double = 0.0,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
)

/** Услуга точки (теннис, печать): справочник с вариантами цены. */
@Serializable
data class SaleService(
    val id: String = "",
    val name: String = "",
    val variants: List<SaleServiceVariant> = emptyList(),
)

/** Идущий таймер услуги. Отсчёт на устройстве идёт до `ends_at`. */
@Serializable
data class ServiceSession(
    val id: String = "",
    @SerialName("service_id") val serviceId: String? = null,
    @SerialName("variant_id") val variantId: String? = null,
    val title: String = "",
    @SerialName("ends_at") val endsAt: String? = null,
) {
    val endsAtMillis: Long? get() = parseIsoMillis(endsAt)
}

@Serializable
internal data class ServiceSessionsData(val sessions: List<ServiceSession> = emptyList())

/** Ответ `GET /api/operator/service-sessions`: `{ data: { sessions } }`. */
@Serializable
internal data class ServiceSessionsResponse(val data: ServiceSessionsData? = null)

@Serializable
internal data class CatalogCompany(val name: String? = null)

@Serializable
internal data class CatalogData(
    val company: CatalogCompany? = null,
    val items: List<SaleCatalogItem> = emptyList(),
    val services: List<SaleService> = emptyList(),
    val sales: List<RecentSale> = emptyList(),
)

/** Ответ `GET /api/operator/inventory-sales`: `{ data: { company, items, sales } }`. */
@Serializable
internal data class SaleCatalogResponse(val data: CatalogData? = null)

/** Позиция в чеке. */
@Serializable
data class SaleLine(
    val itemId: String,
    val name: String,
    val quantity: Double,
    val unitPrice: Double,
    /** Услуга вместо товара: уходит как service_variant_id, остатка нет. */
    val serviceVariantId: String? = null,
    /** Продление идущего таймера: время прибавится к нему, а не запустит новый. */
    val extendSessionId: String? = null,
) {
    val total: Double get() = quantity * unitPrice
}

/** Способ оплаты. */
@Serializable
enum class PaymentMethod(val raw: String, val label: String) {
    @SerialName("cash") Cash("cash", "Наличные"),
    @SerialName("kaspi") Kaspi("kaspi", "Kaspi"),
    @SerialName("mixed") Mixed("mixed", "Смешанная"),
}

/** Черновик продажи — и для отправки, и для офлайн-очереди. */
@Serializable
data class SaleDraft(
    /** Ключ идемпотентности: повтор после обрыва сети не создаст второй чек. */
    val localRef: String = UUID.randomUUID().toString(),
    val saleDate: String,
    val shift: ShiftKind,
    val lines: List<SaleLine> = emptyList(),
    val paymentMethod: PaymentMethod = PaymentMethod.Cash,
    val cashAmount: Double = 0.0,
    val kaspiAmount: Double = 0.0,
    val kaspiBeforeMidnight: Double = 0.0,
    val kaspiAfterMidnight: Double = 0.0,
    val comment: String? = null,
    val customerId: String? = null,
    /** Для порядка в очереди отправки. */
    val createdAt: Long = System.currentTimeMillis(),
) {
    val total: Double get() = lines.sumOf { it.total }
    val paymentTotal: Double get() = cashAmount + kaspiAmount

    enum class ValidationIssue(val message: String) {
        NoLines("Добавьте хотя бы один товар."),
        ZeroPayment("Сумма оплаты должна быть больше нуля."),
        KaspiSplitMismatch("Kaspi до и после полуночи в сумме должны давать общую сумму Kaspi."),
    }

    /** Проверки, которые сервер выполнит всё равно, — но заведомо плохой чек по сети не гоняем. */
    fun validate(): ValidationIssue? {
        if (lines.isEmpty()) return ValidationIssue.NoLines
        if (paymentTotal <= 0) return ValidationIssue.ZeroPayment
        // Сервер сверяет с точностью до копейки — тот же допуск.
        if (abs(kaspiAmount - (kaspiBeforeMidnight + kaspiAfterMidnight)) > 0.01) return ValidationIssue.KaspiSplitMismatch
        return null
    }

    /** Тело `POST /api/operator/inventory-sales`. */
    fun requestBody(): JsonObject = buildJsonObject {
        put("action", "createSale")
        put(
            "payload",
            buildJsonObject {
                put("sale_date", saleDate)
                put("shift", shift.raw)
                put("payment_method", paymentMethod.raw)
                put("cash_amount", cashAmount)
                put("kaspi_amount", kaspiAmount)
                put("kaspi_before_midnight_amount", kaspiBeforeMidnight)
                put("kaspi_after_midnight_amount", kaspiAfterMidnight)
                customerId?.let { put("customer_id", it) }
                comment?.let { put("comment", it) }
                put("local_ref", localRef)
                put(
                    "items",
                    buildJsonArray {
                        lines.forEach { line ->
                            add(
                                buildJsonObject {
                                    if (line.serviceVariantId != null) {
                                        // Цену и название услуги сервер берёт из справочника сам.
                                        put("service_variant_id", line.serviceVariantId)
                                        line.extendSessionId?.let { put("extend_session_id", it) }
                                    } else {
                                        put("item_id", line.itemId)
                                    }
                                    put("quantity", line.quantity)
                                    put("unit_price", line.unitPrice)
                                },
                            )
                        }
                    },
                )
            },
        )
    }
}

/** Результат проведённой продажи: `{ data: { sale_id, total_amount, receipt_url, ... } }`. */
@Serializable
internal data class SaleResultData(
    @SerialName("sale_id") val saleId: String? = null,
    @SerialName("total_amount") val totalAmount: Double = 0.0,
    @SerialName("receipt_url") val receiptUrl: String? = null,
    @SerialName("loyalty_points_earned") val loyaltyPointsEarned: Int = 0,
    val idempotent: Boolean = false,
    @SerialName("service_sessions") val serviceSessions: List<ServiceSession> = emptyList(),
)

@Serializable
internal data class SaleResultResponse(val data: SaleResultData? = null)

data class SaleResult(
    val saleId: String?,
    val totalAmount: Double,
    val receiptUrl: String?,
    val loyaltyPointsEarned: Int,
    val isIdempotentReplay: Boolean,
    val serviceSessions: List<ServiceSession> = emptyList(),
)

/** Клиент точки: карта лояльности, бонусы. Поля приходят то строкой, то числом. */
data class PointCustomer(
    val id: String,
    val name: String,
    val phone: String?,
    val cardNumber: String?,
    val loyaltyPoints: Double,
    val totalSpent: Double,
    val visitsCount: Int,
) {
    /** Чем подписать в списке: карта или телефон — по ним и ищут. */
    val subtitle: String get() = listOfNotNull(cardNumber, phone).filter { it.isNotEmpty() }.joinToString(" · ")

    companion object {
        fun from(element: JsonElement): PointCustomer? {
            val o = element as? JsonObject ?: return null
            return PointCustomer(
                id = o["id"].flexString() ?: "",
                name = o["name"].flexString() ?: "Клиент",
                phone = o["phone"].flexString(),
                cardNumber = o["card_number"].flexString(),
                loyaltyPoints = o["loyalty_points"].flexDouble() ?: 0.0,
                totalSpent = o["total_spent"].flexDouble() ?: 0.0,
                visitsCount = (o["visits_count"].flexDouble() ?: 0.0).toInt(),
            )
        }

        /** Список клиентов: `{ items: [...] }`, `{ data: [...] }` или голый массив. */
        fun listFrom(root: JsonElement): List<PointCustomer> {
            val array = when (root) {
                is JsonArray -> root
                is JsonObject -> (root["items"] ?: root["data"] ?: root["customers"]) as? JsonArray
                else -> null
            } ?: return emptyList()
            return array.mapNotNull { from(it) }
        }
    }
}
