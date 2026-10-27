package kz.ordaops.android.feature.business.store

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

// Черновики складских документов и тела запросов — чистая логика без Android,
// зеркало ReceiptDraft / WriteoffDraft / StocktakeDraft / CatalogEdit /
// StockRequestDecision из OrdaKit. Правила проверки те же, что у сервера:
// приложение отказывает раньше, чем получит 400.

// ── Форматирование ───────────────────────────────────────────────────────────

/** Количество: «12», «1,5», «0,125» — лишние нули не показываем (`Quantity.format`). */
fun formatQty(value: Double?): String {
    if (value == null || !value.isFinite()) return "—"
    if (value == Math.rint(value)) return value.toLong().toString()
    return String.format(java.util.Locale.US, "%.3f", value).trimEnd('0').trimEnd('.').replace('.', ',')
}

/** «12 шт», «1,5 кг». Пустая единица — «шт», иначе строка читается как оборванная. */
fun formatQtyUnit(value: Double?, unit: String?): String = "${formatQty(value)} ${unit?.trim().takeUnless { it.isNullOrEmpty() } ?: "шт"}"

/** Склонение: «1 позиция», «2 позиции», «5 позиций». */
fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

fun positions(n: Int): String = "$n ${plural(n, "позиция", "позиции", "позиций")}"

/** Наценка к закупочной цене в процентах; null — считать не от чего. */
fun markupPercent(purchase: Double, sale: Double?): Double? =
    if (sale == null || purchase <= 0) null else (sale - purchase) / purchase * 100

/** Число из поля ввода: запятая как точка, мусор — ноль. */
fun parseNumber(text: String): Double = text.replace(',', '.').trim().toDoubleOrNull() ?: 0.0

// ── Приёмка ──────────────────────────────────────────────────────────────────

/** Позиция накладной. */
data class ReceiptLine(
    val itemId: String,
    val name: String,
    val unit: String,
    val quantity: Double = 0.0,
    /** Цена закупки за единицу. */
    val unitCost: Double = 0.0,
    /** Новая цена продажи; null — оставить прежнюю. */
    val salePrice: Double? = null,
    /** Бонус: пришёл бесплатно, в деньги не идёт. */
    val isBonus: Boolean = false,
    /** `yyyy-MM-dd`, если у товара есть срок. */
    val expiryDate: String? = null,
) {
    /** Бонус не стоит ничего: включать его в сумму накладной значит завысить себестоимость. */
    val total: Double get() = if (isBonus) 0.0 else quantity * unitCost
    val markup: Double? get() = markupPercent(unitCost, salePrice)
}

/** Как платим поставщику. */
enum class ReceiptPayment(val wire: String, val title: String) {
    Now("now", "Оплачено"),
    Deferred("deferred", "В долг"),
}

/** Накладная целиком. */
data class ReceiptDraft(
    val receivedAt: String,
    val locationId: String = "",
    val supplierId: String = "",
    val invoiceNumber: String = "",
    val payment: ReceiptPayment = ReceiptPayment.Now,
    /** `cash` или `kaspi` — только для оплаченных сразу. */
    val paymentMethod: String = "cash",
    /** Срок оплаты для долга, `yyyy-MM-dd`. */
    val dueDate: String? = null,
    /** Реализация: платим по мере продажи. */
    val isConsignment: Boolean = false,
    val comment: String = "",
    val lines: List<ReceiptLine> = emptyList(),
) {
    val total: Double get() = lines.sumOf { it.total }
    val bonusCount: Int get() = lines.count { it.isBonus }

    /** Что мешает провести; null — можно. */
    val validationMessage: String?
        get() = when {
            locationId.isEmpty() -> "Выберите, куда принимаем"
            supplierId.isEmpty() -> "Поставщик обязателен"
            lines.isEmpty() -> "Добавьте хотя бы одну позицию"
            lines.any { it.quantity <= 0 } -> "У каждой позиции должно быть количество"
            // Приёмка по нулевой цене обнуляет себестоимость товара.
            lines.any { !it.isBonus && it.unitCost <= 0 } -> "У платных позиций должна быть цена закупки"
            payment == ReceiptPayment.Deferred && dueDate.isNullOrEmpty() && !isConsignment -> "У долга должен быть срок оплаты"
            else -> null
        }

    /** Оприходование — излишки без поставщика, оплаты и цены. */
    val postingValidationMessage: String?
        get() = when {
            locationId.isEmpty() -> "Выберите, куда оприходовать"
            lines.isEmpty() -> "Добавьте хотя бы одну позицию"
            lines.any { it.quantity <= 0 } -> "У каждой позиции должно быть количество"
            else -> null
        }

    private fun trimmedComment() = comment.trim().ifEmpty { null }

    /** Тело `createReceipt` — ровно то, что шлёт сайт. */
    fun receiptBody(companyId: String?): JsonObject = buildJsonObject {
        put("action", "createReceipt")
        putNullable("company_id", companyId)
        put("payload", buildJsonObject {
            put("location_id", locationId)
            put("supplier_id", supplierId)
            put("received_at", receivedAt)
            putNullable("invoice_number", invoiceNumber.trim().ifEmpty { null })
            putNullable("payment_method", if (payment == ReceiptPayment.Now) paymentMethod else null)
            put("payment_mode", payment.wire)
            put("is_consignment", isConsignment)
            putNullable("due_date", if (payment == ReceiptPayment.Deferred) dueDate else null)
            putNullable("comment", trimmedComment())
            put("items", buildJsonArray {
                lines.forEach { l ->
                    add(buildJsonObject {
                        put("item_id", l.itemId)
                        put("quantity", l.quantity)
                        put("unit_cost", if (l.isBonus) 0.0 else l.unitCost)
                        putNullable("sale_price", l.salePrice)
                        put("is_bonus", l.isBonus)
                        putNullable("expiry_date", l.expiryDate)
                    })
                }
            })
        })
    }

    /** Тело `createPosting`. */
    fun postingBody(companyId: String?): JsonObject = buildJsonObject {
        put("action", "createPosting")
        putNullable("company_id", companyId)
        put("posting", buildJsonObject {
            put("location_id", locationId)
            put("received_at", receivedAt)
            putNullable("comment", trimmedComment())
            put("items", buildJsonArray {
                lines.forEach { l ->
                    add(buildJsonObject {
                        put("item_id", l.itemId)
                        put("quantity", l.quantity)
                        put("unit_cost", l.unitCost)
                        putNullable("sale_price", l.salePrice)
                        putNullable("expiry_date", l.expiryDate)
                    })
                }
            })
        })
    }
}

// ── Списание ─────────────────────────────────────────────────────────────────

data class WriteoffLine(val itemId: String, val name: String, val quantity: Double = 0.0, val comment: String? = null)

/** Акт списания: разбилось, испортилось, просрочено. */
data class WriteoffDraft(
    val writtenAt: String,
    val locationId: String = "",
    val reason: String = "",
    val comment: String = "",
    val lines: List<WriteoffLine> = emptyList(),
) {
    val validationMessage: String?
        get() = when {
            locationId.isEmpty() -> "Выберите, откуда списываем"
            reason.isBlank() -> "Причина обязательна"
            lines.isEmpty() -> "Добавьте хотя бы одну позицию"
            lines.any { it.quantity <= 0 } -> "У каждой позиции должно быть количество"
            else -> null
        }

    fun body(companyId: String?): JsonObject = buildJsonObject {
        put("action", "createWriteoff")
        putNullable("company_id", companyId)
        put("payload", buildJsonObject {
            put("location_id", locationId)
            put("written_at", writtenAt)
            put("reason", reason.trim())
            putNullable("comment", comment.trim().ifEmpty { null })
            put("items", buildJsonArray {
                lines.forEach { l ->
                    add(buildJsonObject {
                        put("item_id", l.itemId)
                        put("quantity", l.quantity)
                        putNullable("comment", l.comment?.trim()?.ifEmpty { null })
                    })
                }
            })
        })
    }
}

// ── Ревизия ──────────────────────────────────────────────────────────────────

/** Строка пересчёта. `actual == null` — «до неё не дошли», а не «ноль на полке». */
data class StocktakeLine(
    val itemId: String,
    val name: String,
    val unit: String,
    val barcode: String?,
    val expected: Double,
    val actual: Double? = null,
    val comment: String? = null,
) {
    val isCounted: Boolean get() = actual != null
    val difference: Double? get() = actual?.let { it - expected }
    val hasMismatch: Boolean get() = difference?.let { abs(it) > 0.0005 } ?: false
}

data class StocktakeDraft(
    val countedAt: String,
    val locationId: String = "",
    val comment: String = "",
    val lines: List<StocktakeLine> = emptyList(),
) {
    val countedLines: List<StocktakeLine> get() = lines.filter { it.isCounted }
    val mismatchedLines: List<StocktakeLine> get() = lines.filter { it.hasMismatch }
    val remaining: Int get() = lines.size - countedLines.size

    val validationMessage: String?
        get() = when {
            locationId.isEmpty() -> "Выберите, что пересчитываем"
            countedLines.isEmpty() -> "Ни одна позиция не пересчитана"
            lines.any { (it.actual ?: 0.0) < 0 } -> "Количество не может быть отрицательным"
            else -> null
        }

    /**
     * В акт уходят только пересчитанные строки. Непосчитанная позиция — не
     * «ноль на полке»: отправить её нулём значит списать товар, которого никто не искал.
     */
    fun body(companyId: String?): JsonObject = buildJsonObject {
        put("action", "createRevision")
        putNullable("company_id", companyId)
        put("payload", buildJsonObject {
            put("location_id", locationId)
            put("counted_at", countedAt)
            putNullable("comment", comment.trim().ifEmpty { null })
            put("items", buildJsonArray {
                countedLines.forEach { l ->
                    add(buildJsonObject {
                        put("item_id", l.itemId)
                        put("actual_qty", l.actual ?: 0.0)
                        putNullable("comment", l.comment?.trim()?.ifEmpty { null })
                    })
                }
            })
        })
    }
}

// ── Заявки ───────────────────────────────────────────────────────────────────

/**
 * Решение по заявке одним запросом: `decideRequest`. Сервер вызывает
 * `inventory_decide_request` — минус со склада и плюс на витрину атомарно.
 * Раскладывать на два шага нельзя: при обрыве связи товар пропадёт из обоих мест.
 *
 * «Одобрить» значит «как просили»: количества берём из самой заявки. Функции в
 * базе нужна строка на каждую позицию, без неё решение не проходит.
 */
fun decideRequestBody(requestId: String, approved: Boolean, lines: List<RequestLine>, comment: String? = null): JsonObject =
    buildJsonObject {
        put("action", "decideRequest")
        put("requestId", requestId)
        put("approved", approved)
        putNullable("decision_comment", comment?.trim()?.ifEmpty { null })
        put("items", buildJsonArray {
            if (approved) {
                lines.forEach {
                    add(buildJsonObject {
                        put("request_item_id", it.id)
                        put("approved_qty", it.requested)
                    })
                }
            }
        })
    }

fun transitionBody(requestId: String, stage: RequestStage): JsonObject = buildJsonObject {
    put("action", "transitionStatus")
    put("requestId", requestId)
    put("status", stage.wire)
}

fun undecideBody(requestId: String, reason: String): JsonObject = buildJsonObject {
    put("action", "undecideRequest")
    put("requestId", requestId)
    put("reason", reason.trim())
}

/** Заявка на перенос со склада на витрину: `createRequest` в `/store/showcase`. */
fun showcaseRequestBody(companyId: String, itemId: String, quantity: Double, comment: String?): JsonObject = buildJsonObject {
    put("action", "createRequest")
    put("company_id", companyId)
    put("items", buildJsonArray {
        add(buildJsonObject {
            put("item_id", itemId)
            put("requested_qty", quantity)
            if (!comment.isNullOrBlank()) put("comment", comment)
        })
    })
}

fun showcaseReturnBody(companyId: String, itemId: String, quantity: Double): JsonObject = buildJsonObject {
    put("action", "returnToWarehouse")
    put("company_id", companyId)
    put("items", buildJsonArray {
        add(buildJsonObject {
            put("item_id", itemId)
            put("quantity", quantity)
        })
    })
}

// ── Каталог ──────────────────────────────────────────────────────────────────

/** Форма товара: новый или правка существующего. */
data class CatalogItemDraft(
    val name: String = "",
    val barcode: String = "",
    val unit: String = "шт",
    val salePrice: Double = 0.0,
    val purchasePrice: Double = 0.0,
    val categoryId: String? = null,
    /** `product` — продаётся, `consumable` — расходник. */
    val itemType: String = "product",
    val notes: String = "",
    val lowStockThreshold: Double? = null,
    val requiresExpiry: Boolean = false,
    val imageUrl: String = "",
) {
    /** Почему нельзя сохранить; null — можно. Те же правила, что у сервера. */
    val problem: String?
        get() = when {
            name.isBlank() -> "Укажите название"
            barcode.isBlank() -> "Укажите штрихкод"
            salePrice < 0 || purchasePrice < 0 -> "Цена не может быть меньше нуля"
            else -> null
        }

    /** Поля карточки. Правка на сервере — полная замена, поэтому уходит всё. */
    fun fields(): JsonObject = buildJsonObject {
        put("name", name.trim())
        put("barcode", barcode.trim())
        put("unit", unit.trim().ifEmpty { "шт" })
        put("sale_price", salePrice)
        put("default_purchase_price", purchasePrice)
        putNullable("category_id", categoryId)
        put("item_type", itemType)
        putNullable("notes", notes.trim().ifEmpty { null })
        putNullable("low_stock_threshold", lowStockThreshold)
        put("requires_expiry", requiresExpiry)
    }

    fun createBody(companyId: String): JsonObject = buildJsonObject {
        put("action", "createItem")
        put("company_id", companyId)
        put("payload", fields())
    }

    fun updateBody(itemId: String): JsonObject = buildJsonObject {
        put("action", "updateItem")
        put("item_id", itemId)
        put("fields", fields())
    }

    companion object {
        fun of(item: CatalogItem) = CatalogItemDraft(
            name = item.name,
            barcode = item.barcode.orEmpty(),
            unit = item.unit ?: "шт",
            salePrice = item.salePrice ?: 0.0,
            purchasePrice = item.purchasePrice ?: 0.0,
            categoryId = item.categoryId,
            itemType = item.itemType,
            notes = item.notes.orEmpty(),
            lowStockThreshold = item.lowStockThreshold,
            requiresExpiry = item.requiresExpiry,
            imageUrl = item.imageUrl.orEmpty(),
        )
    }
}

// ── Мелочи сборки JSON ───────────────────────────────────────────────────────

internal fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
    put(key, value?.let { JsonPrimitive(it) } ?: JsonNull)
}

internal fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Double?) {
    put(key, value?.let { JsonPrimitive(it) } ?: JsonNull)
}
