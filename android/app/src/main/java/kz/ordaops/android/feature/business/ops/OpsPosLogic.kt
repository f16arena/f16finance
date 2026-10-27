package kz.ordaops.android.feature.business.ops

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

// Чистая логика чеков и возвратов кассы: CashboxModels.swift. Тип чека кассы
// назван `PosReceipt`, чтобы не путать с приходной накладной склада.

/** Название товара из связи с каталогом: объект, массив или имя прямо в строке чека. */
private fun JsonObject.itemName(): String {
    val ref = this["inventory_items"]
    val fromRef = when (ref) {
        is JsonObject -> ref.str("name")
        is JsonArray -> (ref.firstOrNull() as? JsonObject)?.str("name")
        else -> null
    }
    return fromRef ?: str("universal_name") ?: "Товар"
}

/** Момент времени из ISO-строки сервера (с поясом или без — тогда по поясу телефона). */
fun parseMoment(value: String?, zone: ZoneId = ZoneId.systemDefault()): Instant? {
    if (value.isNullOrBlank()) return null
    runCatching { return Instant.parse(value) }
    runCatching { return OffsetDateTime.parse(value).toInstant() }
    runCatching { return LocalDateTime.parse(value.replace(' ', 'T').take(19)).atZone(zone).toInstant() }
    return parseDay(value)?.atStartOfDay(zone)?.toInstant()
}

data class PosReceiptLine(val id: String, val name: String, val quantity: Double, val unitPrice: Double, val totalPrice: Double)

/** Способ оплаты чека. */
enum class PosPaymentKind(val raw: String, val label: String) {
    Cash("cash", "Наличные"),
    Kaspi("kaspi", "Безналичный"),
    Card("card", "Карта"),
    Online("online", "Онлайн"),
    Mixed("mixed", "Смешанный"),
}

/** Чек продажи — `PosReceipt`. */
data class PosReceipt(
    val id: String,
    val soldAt: Instant?,
    val paymentMethod: String?,
    val cash: Double,
    val kaspi: Double,
    val card: Double,
    val online: Double,
    val total: Double,
    val discount: Double,
    val pointsEarned: Double,
    val pointsSpent: Double,
    val loyaltyDiscount: Double,
    val customerId: String?,
    val source: String?,
    val comment: String?,
    val items: List<PosReceiptLine>,
) {
    /** Последние шесть символов идентификатора — их печатают внизу чека. */
    val shortNumber: String get() = id.takeLast(6).uppercase()

    val paymentKind: PosPaymentKind
        get() {
            PosPaymentKind.entries.firstOrNull { it.raw == paymentMethod }?.let { return it }
            val used = listOfNotNull(
                PosPaymentKind.Cash.takeIf { cash > 0 }, PosPaymentKind.Kaspi.takeIf { kaspi > 0 },
                PosPaymentKind.Card.takeIf { card > 0 }, PosPaymentKind.Online.takeIf { online > 0 },
            )
            return if (used.size == 1) used[0] else if (used.isEmpty()) PosPaymentKind.Cash else PosPaymentKind.Mixed
        }

    val sourceLabel: String
        get() = when (val s = source.orEmpty()) {
            "point-client" -> "Касса точки"
            "operator-web" -> "Оператор (веб)"
            "owner-web" -> "Владелец"
            "web-pos" -> "Веб-касса"
            "" -> "Канал не указан"
            else -> s
        }

    val hasLoyalty: Boolean get() = pointsEarned > 0 || pointsSpent > 0 || loyaltyDiscount > 0

    companion object {
        fun parse(o: JsonObject): PosReceipt = PosReceipt(
            id = o.str("id") ?: "",
            soldAt = parseMoment(o.str("sold_at")) ?: parseMoment(o.str("sale_date")),
            paymentMethod = o.str("payment_method"),
            cash = o.num("cash_amount") ?: 0.0,
            kaspi = o.num("kaspi_amount") ?: 0.0,
            card = o.num("card_amount") ?: 0.0,
            online = o.num("online_amount") ?: 0.0,
            total = o.num("total_amount") ?: 0.0,
            discount = o.num("discount_amount") ?: 0.0,
            pointsEarned = o.num("loyalty_points_earned") ?: 0.0,
            pointsSpent = o.num("loyalty_points_spent") ?: 0.0,
            loyaltyDiscount = o.num("loyalty_discount_amount") ?: 0.0,
            customerId = o.str("customer_id"),
            source = o.str("source"),
            comment = o.str("comment"),
            items = o.arr("items").mapIndexed { i, line ->
                val qty = line.num("quantity") ?: 0.0
                val price = line.num("unit_price") ?: 0.0
                val stored = line.num("total_price")?.takeIf { it != 0.0 }
                PosReceiptLine(line.str("id") ?: "l$i", line.itemName(), qty, price, stored ?: (qty * price))
            },
        )
    }
}

/** Период выборки чеков (`PosPeriod`). */
enum class PosPeriod(val title: String) {
    Today("Сегодня"), Yesterday("Вчера"), Week("7 дней");

    val isSingleDay: Boolean get() = this != Week

    /** Границы в формате `yyyy-MM-dd`. */
    fun range(today: LocalDate = LocalDate.now()): Pair<String, String> = when (this) {
        Today -> today.toString() to today.toString()
        Yesterday -> today.minusDays(1).let { it.toString() to it.toString() }
        Week -> today.minusDays(6).toString() to today.toString()
    }
}

/** Сводка по чекам: выручка, способы оплаты, скидки, проданные позиции. */
data class PosSalesSummary(
    val revenue: Double, val count: Int, val cash: Double, val kaspi: Double, val card: Double, val online: Double,
    val discounts: Double, val loyaltyDiscounts: Double, val itemsSold: Double,
) {
    val averageCheck: Double get() = if (count > 0) revenue / count else 0.0
    val cashless: Double get() = kaspi + card + online

    /** Доля безналичных в оплаченном; null, если платежей нет. */
    val cashlessShare: Double?
        get() {
            val paid = cash + cashless
            return if (paid > 0) cashless / paid else null
        }

    companion object {
        fun of(receipts: List<PosReceipt>) = PosSalesSummary(
            revenue = receipts.sumOf { it.total }, count = receipts.size,
            cash = receipts.sumOf { it.cash }, kaspi = receipts.sumOf { it.kaspi },
            card = receipts.sumOf { it.card }, online = receipts.sumOf { it.online },
            discounts = receipts.sumOf { it.discount }, loyaltyDiscounts = receipts.sumOf { it.loyaltyDiscount },
            itemsSold = receipts.sumOf { r -> r.items.sumOf { it.quantity } },
        )

        /** Выручка по каналам продажи, от большего к меньшему. */
        fun sources(receipts: List<PosReceipt>): List<PosSourceShare> =
            receipts.groupBy { it.sourceLabel }
                .map { (label, list) -> PosSourceShare(label, list.sumOf { it.total }, list.size) }
                .sortedByDescending { it.amount }
    }
}

data class PosSourceShare(val label: String, val amount: Double, val count: Int)

/** Поиск по номеру чека или сумме (как фильтр `ReceiptsListScreen`), новые сверху. */
fun filterReceipts(receipts: List<PosReceipt>, query: String): List<PosReceipt> {
    val q = query.trim().lowercase()
    val sorted = receipts.sortedByDescending { it.soldAt ?: Instant.EPOCH }
    if (q.isEmpty()) return sorted
    return sorted.filter { it.shortNumber.lowercase().contains(q) || "%.0f".format(it.total).contains(q) }
}

// ── Возврат ──────────────────────────────────────────────────────────────────

data class PosReturnLine(
    val id: String,
    val itemId: String?,
    val universalName: String?,
    val name: String,
    val quantity: Double,
    val unitPrice: Double,
    val returnedQuantity: Double,
    val returnableQuantity: Double,
) {
    val isFullyReturned: Boolean get() = returnableQuantity <= 0
}

/** Чек, найденный для возврата, с остатком к возврату по каждой позиции. */
data class PosSaleForReturn(
    val id: String,
    val soldAt: Instant?,
    val total: Double,
    val paymentKind: PosPaymentKind,
    val items: List<PosReturnLine>,
) {
    val shortNumber: String get() = id.takeLast(6).uppercase()
    val returnable: List<PosReturnLine> get() = items.filter { !it.isFullyReturned }
    val hasPreviousReturns: Boolean get() = items.any { it.returnedQuantity > 0 }

    companion object {
        fun parse(o: JsonObject): PosSaleForReturn {
            val cash = o.num("cash_amount") ?: 0.0
            val kaspi = o.num("kaspi_amount") ?: 0.0
            val method = o.str("payment_method")
            val kind = PosPaymentKind.entries.firstOrNull { it.raw == method }
                ?: if (cash > 0 && kaspi > 0) PosPaymentKind.Mixed else if (cash > 0) PosPaymentKind.Cash else PosPaymentKind.Kaspi
            return PosSaleForReturn(
                id = o.str("id") ?: "",
                soldAt = parseMoment(o.str("sold_at")) ?: parseMoment(o.str("sale_date")),
                total = o.num("total_amount") ?: 0.0,
                paymentKind = kind,
                items = o.arr("items").mapIndexed { i, line ->
                    val qty = line.num("quantity") ?: 0.0
                    val returned = line.num("returned_qty") ?: 0.0
                    PosReturnLine(
                        id = line.str("id") ?: "l$i",
                        itemId = line.str("item_id"),
                        universalName = line.str("universal_name"),
                        name = line.itemName(),
                        quantity = qty,
                        unitPrice = line.num("unit_price") ?: 0.0,
                        returnedQuantity = returned,
                        // Поля может не быть у старого ответа — тогда доступно всё, что продано: сервер перепроверит.
                        returnableQuantity = line.num("returnable_qty") ?: maxOf(qty - returned, 0.0),
                    )
                },
            )
        }
    }
}

/** Ключ запроса поиска: полный идентификатор (36 символов с дефисами) или последние символы номера. */
fun returnLookupQuery(number: String): Pair<String, String> {
    val trimmed = number.trim()
    val isFullId = trimmed.length == 36 && '-' in trimmed
    return (if (isFullId) "sale_id" else "short_id") to trimmed
}

/** Выбранные позиции возврата: количество по идентификатору строки. */
fun returnAmount(sale: PosSaleForReturn, quantities: Map<String, Double>): Double =
    sale.returnable.sumOf { (quantities[it.id] ?: 0.0).coerceAtLeast(0.0) * it.unitPrice }

/**
 * Тело `POST /api/pos/return`. У позиции без карточки товара (`item_id` пуст) шлём
 * имя; причину пустой не шлём — `null`.
 */
fun returnBody(sale: PosSaleForReturn, quantities: Map<String, Double>, reason: String): JsonObject = buildJsonObject {
    put("sale_id", sale.id)
    put(
        "items",
        buildJsonArray {
            sale.returnable.filter { (quantities[it.id] ?: 0.0) > 0 }.forEach { line ->
                add(
                    buildJsonObject {
                        put("quantity", quantities[line.id] ?: 0.0)
                        put("unit_price", line.unitPrice)
                        put("item_id", line.itemId)
                        put("universal_name", if (line.itemId == null) line.universalName ?: line.name else null)
                    },
                )
            }
        },
    )
    val trimmed = reason.trim()
    if (trimmed.isEmpty()) put("reason", JsonNull) else put("reason", trimmed)
}
