package kz.ordaops.android.feature.business.pos

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.feature.operator.shift.OperatorShift
import kz.ordaops.android.feature.operator.shift.PaymentMethod
import kz.ordaops.android.feature.operator.shift.flexDouble
import java.util.UUID

// Веб-касса сотрудника: `app/pos/OperatorPos.tsx` в режиме `owner` и маршруты
// `app/api/admin/pos`. Это касса владельца и администрации по сессии сайта, а не
// касса оператора по токену устройства: точку выбирает сам человек, продажа идёт
// «от владельца» в смену точки. Услуг с таймерами и долгов кассы в ней нет.

/** Категория товара из join'а `category:category_id(id, name)`. */
@Serializable
data class PosCategory(val id: String = "", val name: String? = null)

/** Товар витрины точки. `display_qty` — остаток на витрине, продать больше нельзя. */
@Serializable
data class PosItem(
    val id: String = "",
    val name: String = "Без названия",
    val barcode: String? = null,
    val unit: String? = null,
    @SerialName("sale_price") val salePrice: Double = 0.0,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("display_qty") val displayQty: Double = 0.0,
    val category: PosCategory? = null,
) {
    val isInStock: Boolean get() = displayQty > 0
    val categoryName: String get() = category?.name?.takeIf { it.isNotBlank() } ?: NO_CATEGORY

    companion object {
        const val NO_CATEGORY = "Без категории"
    }
}

/** Правила бонусов точки (`loyalty_config`); числа сервер отдаёт то числом, то строкой. */
data class PosLoyalty(
    val isActive: Boolean = false,
    val tengePerPoint: Double = 1.0,
    /** Доля чека, которую можно закрыть баллами, %. */
    val maxRedeemPercent: Double = 50.0,
) {
    companion object {
        fun from(element: JsonElement?): PosLoyalty? {
            val o = element as? JsonObject ?: return null
            val active = (o["is_active"] as? JsonPrimitive)?.let { it.content == "true" || it.content == "1" } ?: false
            return PosLoyalty(
                isActive = active,
                tengePerPoint = o["tenge_per_point"].flexDouble()?.takeIf { it > 0 } ?: 1.0,
                // Как в вебе: max_redeem_percent, а в старых записях max_redeem_percent_per_purchase.
                maxRedeemPercent = o["max_redeem_percent"].flexDouble()
                    ?: o["max_redeem_percent_per_purchase"].flexDouble() ?: 50.0,
            )
        }
    }
}

@Serializable
internal data class PosCompanyRef(val id: String = "", val name: String? = null)

@Serializable
internal data class PosCatalogData(
    val company: PosCompanyRef? = null,
    val items: List<PosItem> = emptyList(),
    @SerialName("loyalty_config") val loyaltyConfig: JsonElement? = null,
)

/** Ответ `GET /api/admin/pos/inventory-sales`: `{ ok, data: { company, items, loyalty_config, ... } }`. */
@Serializable
internal data class PosCatalogResponse(val data: PosCatalogData? = null)

/** Каталог точки в виде, удобном экрану. */
data class PosCatalog(
    val companyName: String,
    val items: List<PosItem>,
    val loyalty: PosLoyalty?,
)

/** Ответ `GET /api/admin/pos/shift?company_id=`: `{ shift }` — `null`, если смена не открыта. */
@Serializable
internal data class PosShiftResponse(val shift: OperatorShift? = null)

@Serializable
internal data class PosSaleData(
    @SerialName("sale_id") val saleId: String? = null,
    @SerialName("total_amount") val totalAmount: Double = 0.0,
    @SerialName("sold_at") val soldAt: String? = null,
    val idempotent: Boolean = false,
)

/** Ответ `POST /api/admin/pos/inventory-sales`: `{ ok, data: { sale_id, total_amount, sold_at, ... } }`. */
@Serializable
internal data class PosSaleResponse(val data: PosSaleData? = null)

/** Ответ `POST /api/pos/receipt-email`: `{ ok, status, email, message }`. */
@Serializable
internal data class PosEmailResponse(val ok: Boolean = false, val status: String? = null, val email: String? = null, val message: String? = null)

/** Позиция чека. Без `itemId` — «универсальная»: только название и цена, остатка нет. */
data class PosLine(
    val id: String = UUID.randomUUID().toString(),
    val itemId: String?,
    val name: String,
    val unit: String?,
    val quantity: Double,
    val unitPrice: Double,
    /** Остаток витрины на момент добавления: выше него количество не растёт. */
    val maxQuantity: Double? = null,
) {
    val total: Double get() = quantity * unitPrice
}

/** Всё, что кассир задал на экране оплаты. */
data class PosCheckout(
    val method: PaymentMethod,
    val cash: Double,
    val kaspi: Double,
    val discountAmount: Double,
    val customerId: String?,
    val customerName: String?,
    val pointsSpent: Double,
    val loyaltyDiscount: Double,
    val comment: String?,
    /** Сколько дал покупатель наличными; `null` — без сдачи. */
    val cashReceived: Double?,
)

/** Проведённая продажа — для экрана «Оплата проведена», печати и письма. */
data class PosReceipt(
    val saleId: String?,
    val companyName: String,
    val soldAtMillis: Long,
    val lines: List<PosLine>,
    val subtotal: Double,
    val discount: Double,
    val loyaltyDiscount: Double,
    val total: Double,
    val method: PaymentMethod,
    val cash: Double,
    val kaspi: Double,
    val cashReceived: Double?,
    val customerName: String?,
    val id: String = UUID.randomUUID().toString(),
) {
    val change: Double get() = cashReceived?.let { (it - cash).coerceAtLeast(0.0) } ?: 0.0

    /** Всё, что уменьшило чек: процентная скидка и баллы. */
    val totalDiscount: Double get() = discount + loyaltyDiscount
}

/** Расчёт чека — те же формулы, что в `OperatorPos.tsx` (скидка, баллы, к оплате). */
object PosMath {
    fun subtotal(lines: List<PosLine>): Double = lines.sumOf { it.total }

    /** Скидка в процентах от суммы, 0..99, до копейки. */
    fun discountAmount(subtotal: Double, percent: Double): Double {
        val p = percent.coerceIn(0.0, 99.0)
        return Math.round(subtotal * p / 100 * 100) / 100.0
    }

    fun afterDiscount(subtotal: Double, discount: Double): Double = (subtotal - discount).coerceAtLeast(0.0)

    /** Сколько баллов клиент может потратить в этом чеке: не больше его баллов и доли чека. */
    fun maxRedeemable(customerPoints: Double, loyalty: PosLoyalty?, afterDiscount: Double): Double {
        if (loyalty == null || !loyalty.isActive) return 0.0
        val maxTenge = Math.floor(afterDiscount * loyalty.maxRedeemPercent / 100)
        val byPoints = if (loyalty.tengePerPoint > 0) Math.floor(maxTenge / loyalty.tengePerPoint) else 0.0
        return minOf(customerPoints, byPoints).coerceAtLeast(0.0)
    }

    fun loyaltyDiscount(points: Double, loyalty: PosLoyalty?, afterDiscount: Double): Double {
        val rate = loyalty?.tengePerPoint ?: 1.0
        return minOf(points.coerceAtLeast(0.0) * rate, afterDiscount)
    }

    fun payable(afterDiscount: Double, loyaltyDiscount: Double): Double = (afterDiscount - loyaltyDiscount).coerceAtLeast(0.0)

    /** Товары для сетки: категория, поиск по названию и штрихкоду; в наличии выше, затем по алфавиту. */
    fun visible(items: List<PosItem>, category: String?, query: String): List<PosItem> {
        val q = query.trim().lowercase()
        return items
            .filter { category == null || it.categoryName == category }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || (it.barcode ?: "").contains(q) }
            .sortedWith(compareByDescending<PosItem> { it.isInStock }.thenBy { it.name.lowercase() })
    }

    fun categories(items: List<PosItem>): List<String> = items.map { it.categoryName }.distinct().sortedBy { it.lowercase() }

    /** Добавить единицу товара; выше остатка витрины не растёт (как `addItem` в вебе). */
    fun add(cart: List<PosLine>, item: PosItem): List<PosLine> {
        if (!item.isInStock) return cart
        val existing = cart.firstOrNull { it.itemId == item.id }
        if (existing == null) {
            return cart + PosLine(itemId = item.id, name = item.name, unit = item.unit, quantity = 1.0, unitPrice = item.salePrice, maxQuantity = item.displayQty)
        }
        if (existing.quantity + 1 > item.displayQty) return cart
        return cart.map { if (it.id == existing.id) it.copy(quantity = it.quantity + 1) else it }
    }

    /** Новое количество строки; ноль и меньше убирают её, остаток витрины — потолок. */
    fun setQuantity(cart: List<PosLine>, id: String, quantity: Double): List<PosLine> {
        if (quantity <= 0) return cart.filterNot { it.id == id }
        return cart.map { line ->
            if (line.id != id) line else line.copy(quantity = line.maxQuantity?.let { minOf(quantity, it) } ?: quantity)
        }
    }

    /** Разбивка оплаты: наличные и безнал. Ошибка — текст как в вебе, `null` — всё сходится. */
    fun paymentIssue(method: PaymentMethod, cash: Double, kaspi: Double, payable: Double): String? {
        if (method != PaymentMethod.Mixed) return null
        if (Math.abs(cash + kaspi - payable) > 0.01) return "Сумма наличных и безнала должна равняться ${Math.round(payable)} ₸"
        if (cash <= 0 || kaspi <= 0) return "Для смешанной оплаты укажите обе части: наличные и безнал."
        return null
    }
}

/**
 * Тело `POST /api/admin/pos/inventory-sales`. Совпадает с веб-кассой: смена день/ночь по
 * открытой смене, Kaspi целиком «до полуночи», товар без `item_id` уходит как универсальный.
 */
fun posSaleBody(
    companyId: String,
    saleDate: String,
    night: Boolean,
    checkout: PosCheckout,
    lines: List<PosLine>,
    localRef: String,
): JsonObject = buildJsonObject {
    put("action", "createSale")
    put(
        "payload",
        buildJsonObject {
            put("company_id", companyId)
            put("sale_date", saleDate)
            put("shift", if (night) "night" else "day")
            put("payment_method", checkout.method.raw)
            put("cash_amount", checkout.cash)
            put("kaspi_amount", checkout.kaspi)
            put("kaspi_before_midnight_amount", checkout.kaspi)
            put("kaspi_after_midnight_amount", 0.0)
            put("discount_amount", checkout.discountAmount)
            checkout.customerId?.let { put("customer_id", it) }
            put("loyalty_points_spent", if (checkout.customerId != null) checkout.pointsSpent else 0.0)
            put("loyalty_discount_amount", checkout.loyaltyDiscount)
            checkout.comment?.takeIf { it.isNotBlank() }?.let { put("comment", it.trim()) }
            put("local_ref", localRef)
            put(
                "items",
                buildJsonArray {
                    lines.forEach { line ->
                        add(
                            buildJsonObject {
                                if (line.itemId != null) put("item_id", line.itemId) else put("universal_name", line.name)
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
