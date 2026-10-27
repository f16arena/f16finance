package kz.ordaops.android.feature.business.store

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// Магазин: /api/admin/store/* и /api/admin/inventory/*. Порт StoreModels,
// DocumentModels, ShowcaseCatalogModels и OperationsModels из OrdaKit.
//
// Сервер отдаёт строки Supabase как есть: связи приходят вложенными объектами
// (`item`, `location`, `company`) — иногда массивом из одного, числа — то
// числом, то строкой «12.5». Поэтому разбор ручной и терпимый, как
// `decodeFlexible*` на айфоне: пустой склад лучше экрана с ошибкой из-за
// одного кривого поля.

// ── Терпимое чтение JSON ─────────────────────────────────────────────────────

/** Объект из объекта или из массива с одним объектом (join PostgREST). */
internal fun JsonElement?.asObj(): JsonObject? = when (this) {
    is JsonObject -> this
    is JsonArray -> firstOrNull() as? JsonObject
    else -> null
}

internal fun JsonElement?.asObjects(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }

internal fun JsonObject.dbl(key: String): Double? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.doubleOrNull ?: p.contentOrNull?.replace(',', '.')?.trim()?.toDoubleOrNull()
}

internal fun JsonObject.int(key: String): Int? = dbl(key)?.toInt()

internal fun JsonObject.bool(key: String, default: Boolean): Boolean {
    val p = this[key] as? JsonPrimitive ?: return default
    return p.booleanOrNull ?: when (p.contentOrNull) {
        "true", "1" -> true
        "false", "0" -> false
        else -> default
    }
}

/** Вложенное название: `{ "item": { "name": … } }`. */
internal fun JsonObject.nestedName(key: String): String? = this[key].asObj()?.str("name")

/**
 * Время из PostgREST: полная отметка с зоной или только дата `yyyy-MM-dd`.
 * Возвращает миллисекунды; null — не разобралось.
 */
internal fun parseWhen(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
    runCatching {
        return java.time.LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    runCatching {
        return LocalDate.parse(text.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    return null
}

// ── Места хранения, остатки, движения ────────────────────────────────────────

/** Точка хранения: склад, витрина, подсобка. */
data class StoreLocation(
    val id: String,
    val name: String,
    val code: String?,
    /** `warehouse`, `point_display`, `backroom`, `catalog` — как в `inventory_locations_location_type_check`. */
    val kind: String,
    val isActive: Boolean,
    val companyName: String?,
    /** Точка, которой принадлежит место: серверу она нужна, чтобы найти склад. */
    val companyId: String?,
) {
    val kindLabel: String
        get() = when (kind) {
            "warehouse" -> "Склад"
            "point_display" -> "Витрина"
            "backroom" -> "Подсобка"
            "catalog" -> "Каталог"
            else -> "Точка"
        }

    companion object {
        fun parse(o: JsonObject) = StoreLocation(
            id = o.str("id") ?: "",
            name = o.str("name") ?: "Без названия",
            code = o.str("code"),
            kind = o.str("location_type") ?: "warehouse",
            isActive = o.bool("is_active", true),
            companyName = o.nestedName("company"),
            companyId = o.str("company_id"),
        )
    }
}

/** Остаток товара в конкретном месте. */
data class StockBalance(
    val itemId: String,
    val locationId: String,
    val name: String,
    val unit: String,
    val barcode: String?,
    val quantity: Double,
    val threshold: Double?,
    val locationName: String?,
) {
    val id: String get() = "$locationId/$itemId"

    /** Ниже порога. Без порога тревожить нечем, поэтому false, а не «ноль». */
    val isLow: Boolean get() = threshold != null && threshold > 0 && quantity <= threshold

    companion object {
        fun parse(o: JsonObject): StockBalance {
            val item = o["item"].asObj()
            val location = o["location"].asObj()
            return StockBalance(
                itemId = o.str("item_id") ?: item?.str("id") ?: "",
                locationId = o.str("location_id") ?: location?.str("id") ?: "",
                name = item?.str("name") ?: "Товар",
                unit = item?.str("unit") ?: "шт",
                barcode = item?.str("barcode"),
                quantity = o.dbl("quantity") ?: 0.0,
                threshold = item?.dbl("low_stock_threshold"),
                locationName = location?.str("name"),
            )
        }
    }
}

/** Движение товара: приход, списание, перемещение, продажа. */
data class StockMovement(
    val id: String,
    val kind: String,
    val quantity: Double,
    val amount: Double,
    val createdAt: Long?,
    val itemName: String,
    val fromName: String?,
    val toName: String?,
) {
    /** Названия — те же, что в журнале движений на сайте. */
    val kindLabel: String get() = movementLabel(kind)

    /** Приход увеличивает запас, списание и продажа уменьшают; перемещение нейтрально. */
    val direction: Int
        get() = when (kind) {
            "receipt", "purchase" -> 1
            "writeoff", "sale" -> -1
            else -> 0
        }

    companion object {
        fun parse(o: JsonObject) = StockMovement(
            id = o.str("id") ?: "",
            kind = o.str("movement_type") ?: "movement",
            quantity = o.dbl("quantity") ?: 0.0,
            amount = o.dbl("total_amount") ?: 0.0,
            createdAt = parseWhen(o.str("created_at")),
            itemName = o.nestedName("item") ?: "Товар",
            fromName = o.nestedName("from_location"),
            toName = o.nestedName("to_location"),
        )
    }
}

internal fun movementLabel(kind: String): String = when (kind) {
    "receipt", "purchase" -> "Приёмка"
    "receipt_cancel" -> "Отмена приёмки"
    "transfer" -> "Перемещение"
    "transfer_to_point" -> "Выдача на точку"
    "transfer_cancel" -> "Откат выдачи"
    "transfer_warehouse_to_showcase" -> "Получение точкой"
    "transfer_showcase_to_warehouse" -> "Возврат на склад"
    "auto_warehouse_to_showcase" -> "Авто-перенос"
    "reservation" -> "Резерв"
    "reservation_release" -> "Снятие резерва"
    "writeoff" -> "Списание"
    "sale" -> "Продажа"
    "debt" -> "Долг"
    "return" -> "Возврат с кассы"
    "inventory_adjustment" -> "Корректировка"
    "set_stock" -> "Синхронизация"
    "posting" -> "Оприходование"
    "migration_initial" -> "Миграция"
    "revision", "audit" -> "Ревизия"
    // Незнакомый вид показываем нейтрально: английское слово из базы ничего не объясняет.
    else -> "Движение"
}

// ── Заявки ───────────────────────────────────────────────────────────────────

/** Позиция заявки точки на товар со склада. */
data class RequestLine(
    val id: String,
    val name: String,
    val unit: String,
    val requested: Double,
    val approved: Double?,
) {
    companion object {
        fun parse(o: JsonObject): RequestLine {
            val item = o["item"].asObj()
            return RequestLine(
                id = o.str("id") ?: "",
                name = item?.str("name") ?: "Товар",
                unit = item?.str("unit") ?: "шт",
                requested = o.dbl("requested_qty") ?: 0.0,
                approved = o.dbl("approved_qty"),
            )
        }
    }
}

/** Куда двигать заявку: одобрили → выдали → приняли. */
enum class RequestStage(val wire: String, val actionLabel: String) {
    Issued("issued", "Отметить выдачу"),
    Received("received", "Отметить получение"),
}

/** Заявка точки на товар со склада. */
data class StockRequest(
    val id: String,
    val status: String,
    val comment: String?,
    val createdAt: Long?,
    val companyName: String?,
    val sourceName: String?,
    val targetName: String?,
    val lines: List<RequestLine>,
) {
    val isPending: Boolean get() = status == "pending" || status == "new"

    /** Одобрена, но со склада ещё не выдана. */
    val isApproved: Boolean get() = status == "approved_full" || status == "approved_partial"

    /** Выдана со склада, точкой ещё не принята. */
    val isIssued: Boolean get() = status == "issued"

    /** Следующий шаг цепочки; null — заявка своё прошла. */
    val nextStage: RequestStage?
        get() = when {
            isApproved -> RequestStage.Issued
            isIssued -> RequestStage.Received
            else -> null
        }

    val statusLabel: String
        get() = when (status) {
            "pending", "new" -> "Ждёт решения"
            "approved", "approved_full" -> "Одобрена"
            "approved_partial" -> "Одобрена частично"
            "issued" -> "Выдана"
            "received", "fulfilled", "completed" -> "Выполнена"
            "rejected", "declined" -> "Отклонена"
            "disputed" -> "Спорная"
            "cancelled", "canceled" -> "Отменена"
            else -> "Заявка"
        }

    companion object {
        fun parse(o: JsonObject) = StockRequest(
            id = o.str("id") ?: "",
            status = o.str("status") ?: "pending",
            comment = o.str("comment"),
            createdAt = parseWhen(o.str("created_at")),
            companyName = o.nestedName("company"),
            sourceName = o.nestedName("source_location"),
            targetName = o.nestedName("target_location"),
            lines = o["items"].asObjects().map(RequestLine::parse),
        )
    }
}

// ── Обзор магазина ───────────────────────────────────────────────────────────

/**
 * Счётчики «Требует внимания» с нового обзора (27.10). Все необязательны:
 * старый сервер их не присылает, и блок тогда просто не рисуется.
 */
data class StoreMetrics(
    val pendingRequests: Int,
    val disputedRequests: Int,
    val lowStock: Int,
    val outOfStock: Int,
    /** Пусто на витрине, но есть в подсобке — выставить, а не закупать. */
    val outOfStockInBackroom: Int,
    val receiptDrafts: Int,
    val unresolvedWriteoffs: Int,
    val unpaidCount: Int,
    val unpaidSum: Double,
    val overdueCount: Int,
    val overdueSum: Double,
    /** Деньги в товаре по закупочной цене: подсобка и витрины. */
    val stockWarehouse: Double,
    val stockShowcase: Double,
    val stockPositions: Int,
) {
    /** Есть ли что показать в блоке «Требует внимания». */
    val needsAttention: Boolean
        get() = disputedRequests > 0 || outOfStock > 0 || receiptDrafts > 0 || unresolvedWriteoffs > 0 || unpaidCount > 0

    val stockTotal: Double get() = stockWarehouse + stockShowcase

    companion object {
        fun parse(o: JsonObject): StoreMetrics {
            val debts = o["unpaidSupplierDebts"].asObj()
            val value = o["stockValue"].asObj()
            return StoreMetrics(
                pendingRequests = o.int("pendingRequests") ?: 0,
                disputedRequests = o.int("disputedRequests") ?: 0,
                lowStock = o.int("lowStock") ?: 0,
                outOfStock = o.int("outOfStock") ?: 0,
                outOfStockInBackroom = o.int("outOfStockInBackroom") ?: 0,
                receiptDrafts = o.int("receiptDrafts") ?: 0,
                unresolvedWriteoffs = o.int("unresolvedWriteoffs") ?: 0,
                unpaidCount = debts?.int("count") ?: 0,
                unpaidSum = debts?.dbl("sum") ?: 0.0,
                overdueCount = debts?.int("overdueCount") ?: 0,
                overdueSum = debts?.dbl("overdueSum") ?: 0.0,
                stockWarehouse = value?.dbl("warehouse") ?: 0.0,
                stockShowcase = value?.dbl("showcase") ?: 0.0,
                stockPositions = value?.int("positions") ?: 0,
            )
        }
    }
}

/** Сумма остатков одного товара по всем местам. */
data class ItemTotal(
    val id: String,
    val name: String,
    val unit: String,
    val quantity: Double,
    val threshold: Double?,
    val locationCount: Int,
) {
    val isLow: Boolean get() = threshold != null && threshold > 0 && quantity <= threshold
}

/** Остатки, свёрнутые по товару: владельцу важен общий запас, а не где лежит. */
fun totalsOf(balances: List<StockBalance>): List<ItemTotal> {
    val map = LinkedHashMap<String, ItemTotal>()
    for (b in balances) {
        val old = map[b.itemId]
        map[b.itemId] = if (old != null) {
            old.copy(quantity = old.quantity + b.quantity, locationCount = old.locationCount + 1)
        } else {
            ItemTotal(b.itemId, b.name, b.unit, b.quantity, b.threshold, 1)
        }
    }
    return map.values.sortedBy { it.name.lowercase() }
}

/** Ответ `GET /api/admin/store/overview`. */
data class StoreOverview(
    val locations: List<StoreLocation>,
    val balances: List<StockBalance>,
    val movements: List<StockMovement>,
    val requests: List<StockRequest>,
    val metrics: StoreMetrics?,
) {
    val totalsByItem: List<ItemTotal> by lazy { totalsOf(balances) }
    val pendingRequests: List<StockRequest> get() = requests.filter { it.isPending }

    companion object {
        fun parse(data: JsonObject) = StoreOverview(
            locations = data["locations"].asObjects().map(StoreLocation::parse),
            balances = data["balances"].asObjects().map(StockBalance::parse),
            movements = data["movements"].asObjects().map(StockMovement::parse),
            requests = data["requests"].asObjects().map(StockRequest::parse),
            // Новые поля лежат рядом со списками. Нет ни одного — метрик нет.
            metrics = if (data.containsKey("pendingRequests") || data.containsKey("stockValue")) StoreMetrics.parse(data) else null,
        )
    }
}

// ── Склад, витрина, каталог ──────────────────────────────────────────────────

/** Остатки точки: что лежит на складе и что стоит на витрине. */
data class WarehouseBalance(
    val itemId: String,
    val name: String,
    val barcode: String?,
    val unit: String,
    val warehouse: Double,
    /** Из них отложено под заявки — трогать нельзя. */
    val reserved: Double,
    val showcase: Double,
) {
    val available: Double get() = maxOf(0.0, warehouse - reserved)

    companion object {
        fun parse(o: JsonObject): WarehouseBalance {
            val item = o["item"].asObj()
            return WarehouseBalance(
                itemId = o.str("item_id") ?: "",
                name = item?.str("name") ?: "Без названия",
                barcode = item?.str("barcode"),
                unit = item?.str("unit") ?: "шт",
                warehouse = o.dbl("warehouse_quantity") ?: 0.0,
                reserved = o.dbl("warehouse_reserved") ?: 0.0,
                showcase = o.dbl("showcase_quantity") ?: 0.0,
            )
        }
    }
}

/** Точка в переключателе: идентификатор и название. */
data class StoreCompany(val id: String, val name: String)

internal fun parseCompanies(array: JsonElement?): List<StoreCompany> =
    array.asObjects().map { StoreCompany(it.str("id") ?: "", it.str("name") ?: "Точка") }

/** Ответ `GET /api/admin/store/warehouse`. */
data class WarehouseStock(
    val balances: List<WarehouseBalance>,
    val companies: List<StoreCompany>,
    val selectedCompanyId: String?,
) {
    companion object {
        fun parse(data: JsonObject) = WarehouseStock(
            balances = data["balances"].asObjects().map(WarehouseBalance::parse),
            companies = parseCompanies(data["companies"]),
            selectedCompanyId = data.str("selectedCompanyId"),
        )
    }
}

/** Строка витрины: сколько на полке и сколько есть чем пополнить. */
data class ShowcaseRow(
    val itemId: String,
    val name: String,
    val unit: String?,
    val showcase: Double,
    val warehouse: Double,
) {
    /** Витрина пуста, а на складе товар есть — повод сделать заявку. */
    val needsRefill: Boolean get() = showcase <= 0 && warehouse > 0

    companion object {
        fun parse(o: JsonObject): ShowcaseRow {
            val item = o["item"].asObj()
            return ShowcaseRow(
                itemId = o.str("item_id") ?: "",
                name = item?.str("name") ?: "Без названия",
                unit = item?.str("unit"),
                showcase = o.dbl("showcase_quantity") ?: 0.0,
                warehouse = o.dbl("warehouse_quantity") ?: 0.0,
            )
        }
    }
}

/** Ответ `GET /api/admin/store/showcase`. */
data class ShowcasePage(
    val companies: List<StoreCompany>,
    val rows: List<ShowcaseRow>,
    val selectedCompanyId: String?,
) {
    companion object {
        fun parse(data: JsonObject) = ShowcasePage(
            companies = parseCompanies(data["companies"]),
            rows = data["balances"].asObjects().map(ShowcaseRow::parse),
            selectedCompanyId = data.str("selectedCompanyId"),
        )
    }
}

/** Позиция номенклатуры: справочник с ценой и штрихкодом, а не остаток. */
data class CatalogItem(
    val id: String,
    val name: String,
    val unit: String?,
    val barcode: String?,
    val salePrice: Double?,
    val categoryName: String?,
    val catalogQty: Double,
    val warehouseQty: Double,
    val showcaseQty: Double,
    val isActive: Boolean,
    val categoryId: String?,
    val companyId: String?,
    val purchasePrice: Double?,
    val notes: String?,
    val itemType: String,
    val lowStockThreshold: Double?,
    val requiresExpiry: Boolean,
    val imageUrl: String?,
) {
    /** Заведена, но нигде не лежит. */
    val isOutOfStock: Boolean get() = catalogQty <= 0

    val isConsumable: Boolean get() = itemType == "consumable"

    companion object {
        fun parse(o: JsonObject) = CatalogItem(
            id = o.str("id") ?: "",
            name = o.str("name") ?: "Без названия",
            unit = o.str("unit"),
            barcode = o.str("barcode"),
            salePrice = o.dbl("sale_price"),
            categoryName = o.nestedName("category"),
            catalogQty = o.dbl("catalog_qty") ?: 0.0,
            warehouseQty = o.dbl("warehouse_qty") ?: 0.0,
            showcaseQty = o.dbl("showcase_qty") ?: 0.0,
            isActive = o.bool("is_active", true),
            categoryId = o.str("category_id"),
            companyId = o.str("company_id"),
            purchasePrice = o.dbl("default_purchase_price"),
            notes = o.str("notes"),
            itemType = o.str("item_type") ?: "product",
            lowStockThreshold = o.dbl("low_stock_threshold"),
            requiresExpiry = o.bool("requires_expiry", false),
            imageUrl = o.str("image_url"),
        )
    }
}

/** Находка глобального поиска по складу. */
data class StoreSearchHit(val type: String, val title: String, val subtitle: String?) {
    val kindLabel: String
        get() = when (type) {
            "item" -> "Товар"
            "receipt" -> "Приёмка"
            "writeoff" -> "Списание"
            "request" -> "Заявка"
            "supplier" -> "Поставщик"
            "revision" -> "Ревизия"
            else -> "Склад"
        }

    companion object {
        fun parse(o: JsonObject) = StoreSearchHit(o.str("type") ?: "", o.str("title") ?: "", o.str("subtitle"))
    }
}

// ── Документы: приёмки и списания ────────────────────────────────────────────

/** Строка складского документа. Форма одинакова у приёмки и списания. */
data class DocumentLine(
    val id: String,
    val name: String,
    val unit: String,
    val quantity: Double,
    val unitCost: Double,
    val totalCost: Double,
    val comment: String?,
    val isBonus: Boolean,
    val expiryDate: Long?,
) {
    /** Срок годности вышел или выйдет в ближайшую неделю. */
    fun expiresSoon(now: Long = System.currentTimeMillis()): Boolean = expiryDate != null && expiryDate - now < 7 * 86_400_000L

    companion object {
        fun parse(o: JsonObject): DocumentLine {
            val item = o["item"].asObj()
            val quantity = o.dbl("quantity") ?: 0.0
            val unitCost = o.dbl("unit_cost") ?: 0.0
            // Сумму строки сервер считает сам, но на ручных документах её может не быть.
            val stored = o.dbl("total_cost")
            return DocumentLine(
                id = o.str("id") ?: "",
                name = item?.str("name") ?: "Товар",
                unit = item?.str("unit") ?: "шт",
                quantity = quantity,
                unitCost = unitCost,
                totalCost = if (stored != null && stored != 0.0) stored else quantity * unitCost,
                comment = o.str("comment"),
                isBonus = o.bool("is_bonus", false),
                expiryDate = parseWhen(o.str("expiry_date")),
            )
        }
    }
}

/** Приход товара: поставка от поставщика либо оприходование своими силами. */
data class Receipt(
    val id: String,
    val receivedAt: Long?,
    val invoiceNumber: String?,
    val comment: String?,
    val totalAmount: Double,
    val status: String,
    val cancelReason: String?,
    val locationName: String?,
    val supplierName: String?,
    val items: List<DocumentLine>,
    /** `supplier` — по накладной, `posting` — оприходование излишков. */
    val kind: String,
) {
    val isPosting: Boolean get() = kind == "posting"
    val isCancelled: Boolean get() = status == "cancelled" || status == "canceled"

    val statusLabel: String
        get() = when (status) {
            "draft" -> "Черновик"
            "posted", "completed", "done" -> "Проведена"
            "cancelled", "canceled" -> "Отменена"
            else -> "Проведена"
        }

    companion object {
        fun parse(o: JsonObject) = Receipt(
            id = o.str("id") ?: "",
            receivedAt = parseWhen(o.str("received_at")),
            invoiceNumber = o.str("invoice_number"),
            comment = o.str("comment"),
            totalAmount = o.dbl("total_amount") ?: 0.0,
            status = o.str("status") ?: "posted",
            cancelReason = o.str("cancel_reason"),
            locationName = o.nestedName("location"),
            supplierName = o.nestedName("supplier"),
            items = o["items"].asObjects().map(DocumentLine::parse),
            kind = o.str("kind") ?: "supplier",
        )
    }
}

/** Списание товара. */
data class Writeoff(
    val id: String,
    val writtenAt: Long?,
    val reason: String?,
    val comment: String?,
    val totalAmount: Double,
    val status: String,
    val locationName: String?,
    val companyName: String?,
    val items: List<DocumentLine>,
) {
    val isCancelled: Boolean get() = status == "cancelled" || status == "canceled"

    val reasonLabel: String
        get() = when (reason) {
            "expired" -> "Истёк срок"
            "damaged", "broken" -> "Порча"
            "shortage" -> "Недостача"
            "internal" -> "Внутреннее потребление"
            else -> reason?.takeIf { it.isNotBlank() } ?: "Списание"
        }

    companion object {
        fun parse(o: JsonObject): Writeoff {
            val location = o["location"].asObj()
            return Writeoff(
                id = o.str("id") ?: "",
                writtenAt = parseWhen(o.str("written_at")),
                reason = o.str("reason"),
                comment = o.str("comment"),
                totalAmount = o.dbl("total_amount") ?: 0.0,
                status = o.str("status") ?: "posted",
                locationName = location?.str("name"),
                companyName = location?.nestedName("company"),
                items = o["items"].asObjects().map(DocumentLine::parse),
            )
        }
    }
}

// ── Ревизии, акты, поставщики ────────────────────────────────────────────────

/** Пересчёт остатков, уже проведённый: сколько ждала система и сколько нашли. */
data class Stocktake(
    val id: String,
    val countedAt: Long?,
    val comment: String?,
    val locationName: String?,
    val companyName: String?,
    val authorName: String?,
    val lines: List<Line>,
) {
    data class Line(val id: String, val name: String, val unit: String, val expected: Double, val actual: Double, val delta: Double, val price: Double) {
        /** Недостача отрицательна, излишек положителен. */
        val amount: Double get() = delta * price
        val isShortage: Boolean get() = delta < 0
        val isMatch: Boolean get() = delta == 0.0
    }

    /** Сумма недостач (положительным числом) — то, что владелец потерял. */
    val shortageAmount: Double get() = lines.filter { it.isShortage }.sumOf { -it.amount }

    companion object {
        fun parse(o: JsonObject): Stocktake {
            val author = o["created_by_staff"].asObj()
            return Stocktake(
                id = o.str("id") ?: "",
                countedAt = parseWhen(o.str("counted_at")),
                comment = o.str("comment"),
                locationName = o.nestedName("location"),
                companyName = o["location"].asObj()?.nestedName("company"),
                authorName = author?.str("full_name"),
                lines = o["items"].asObjects().map { l ->
                    val item = l["item"].asObj()
                    val expected = l.dbl("expected_qty") ?: 0.0
                    val actual = l.dbl("actual_qty") ?: 0.0
                    Line(
                        id = l.str("id") ?: "",
                        name = item?.str("name") ?: "Товар",
                        unit = item?.str("unit") ?: "шт",
                        expected = expected,
                        actual = actual,
                        delta = l.dbl("delta_qty") ?: (actual - expected),
                        // Недостачу оцениваем по закупочной цене: потеря — то, что заплатили.
                        price = item?.dbl("default_purchase_price") ?: item?.dbl("sale_price") ?: 0.0,
                    )
                },
            )
        }
    }
}

/** Поставщик с историей приёмок и открытыми долгами. */
data class Supplier(
    val id: String,
    val name: String,
    val organizationName: String?,
    val binIin: String?,
    val contactName: String?,
    val phone: String?,
    val receiptsCount: Int,
    val receiptsTotal: Double,
    val lastReceiptDate: Long?,
    val openDebtsCount: Int,
    val openDebtsAmount: Double,
) {
    val hasDebt: Boolean get() = openDebtsAmount > 0

    companion object {
        fun parse(o: JsonObject) = Supplier(
            id = o.str("id") ?: "",
            name = o.str("name") ?: "Поставщик",
            organizationName = o.str("organization_name"),
            binIin = o.str("bin_iin"),
            contactName = o.str("contact_name"),
            phone = o.str("phone"),
            receiptsCount = o.int("receipts_count") ?: 0,
            receiptsTotal = o.dbl("receipts_total") ?: 0.0,
            lastReceiptDate = parseWhen(o.str("last_receipt_date")),
            openDebtsCount = o.int("open_debts_count") ?: 0,
            openDebtsAmount = o.dbl("open_debts_sum") ?: 0.0,
        )
    }
}

/** Акт пересчёта: то, что считают сейчас или уже посчитали. */
data class RevisionAct(
    val id: String,
    val status: String,
    val comment: String?,
    val locationName: String,
    val openedAt: Long?,
    val closedAt: Long?,
    val totalItems: Int,
    val countedItems: Int,
) {
    val isOpen: Boolean get() = status == "open"
    val isClosed: Boolean get() = status == "closed"
    val isCancelled: Boolean get() = status == "cancelled"

    val statusLabel: String
        get() = when (status) {
            "open" -> "Идёт пересчёт"
            "closed" -> "Проведён"
            "cancelled" -> "Отменён"
            else -> "Акт"
        }

    /** Доля посчитанного; null — снимок пуст. */
    val progress: Double? get() = if (totalItems > 0) countedItems.toDouble() / totalItems else null

    companion object {
        fun parse(o: JsonObject) = RevisionAct(
            id = o.str("id") ?: "",
            status = o.str("status") ?: "open",
            comment = o.str("comment"),
            locationName = o.str("locationName") ?: "—",
            openedAt = parseWhen(o.str("opened_at")),
            closedAt = parseWhen(o.str("closed_at")),
            totalItems = o.int("totalItems") ?: 0,
            countedItems = o.int("countedItems") ?: 0,
        )
    }
}

/** Состав акта: пересчитанное с расхождениями и непосчитанное. */
data class RevisionActDetail(
    val totalItems: Int,
    val countedItems: Int,
    val report: List<Line>,
    val uncounted: List<Pending>,
) {
    data class Line(
        val itemId: String,
        val name: String,
        val expected: Double,
        val counted: Double,
        val variance: Double,
        val purchasePrice: Double,
        /** Двое считали и получили разное. */
        val conflict: Boolean,
        val countedBy: String?,
    ) {
        /** Во сколько обходится расхождение: недостача — деньгами. */
        val varianceCost: Double get() = variance * purchasePrice
    }

    data class Pending(val itemId: String, val name: String, val expected: Double)

    companion object {
        fun parse(o: JsonObject) = RevisionActDetail(
            totalItems = o.int("totalItems") ?: 0,
            countedItems = o.int("countedItems") ?: 0,
            report = o["report"].asObjects().map {
                Line(
                    itemId = it.str("item_id") ?: "",
                    name = it.str("name") ?: "Товар",
                    expected = it.dbl("expected") ?: 0.0,
                    counted = it.dbl("counted") ?: 0.0,
                    variance = it.dbl("variance") ?: 0.0,
                    purchasePrice = it.dbl("purchase_price") ?: 0.0,
                    conflict = it.bool("conflict", false),
                    countedBy = it.str("countedBy"),
                )
            },
            uncounted = o["uncounted"].asObjects().map {
                Pending(it.str("item_id") ?: "", it.str("name") ?: "Товар", it.dbl("expected") ?: 0.0)
            },
        )
    }
}

/** Прогноз запаса: что и через сколько дней закончится. */
data class StockForecastRow(
    val itemId: String,
    val name: String,
    val categoryName: String?,
    val balance: Double,
    /** Сколько уходит в день; ноль — за период не продавали. */
    val dailyVelocity: Double,
    /** На сколько дней хватит; null — расхода не было. */
    val daysLeft: Int?,
    val threshold: Double?,
    /** `critical` ≤ 3 дней, `warning` ≤ 7, `low` ≤ 14, `no_sales`, `ok`. */
    val status: String,
) {
    val statusLabel: String
        get() = when (status) {
            "critical" -> "Кончается"
            "warning" -> "На исходе"
            "low" -> "Пора заказать"
            "no_sales" -> "Не продаётся"
            else -> "Хватает"
        }

    val isUrgent: Boolean get() = status == "critical" || status == "warning"

    companion object {
        fun parse(o: JsonObject) = StockForecastRow(
            itemId = o.str("item_id") ?: "",
            name = o.str("name") ?: "Без названия",
            categoryName = o.str("category"),
            balance = o.dbl("balance") ?: 0.0,
            dailyVelocity = o.dbl("daily_velocity") ?: 0.0,
            daysLeft = o.int("days_left"),
            threshold = o.dbl("threshold"),
            status = o.str("status") ?: "ok",
        )
    }
}

/** Продажи и движение товара за окно: аналитика магазина считается из движений. */
data class StoreAnalytics(
    val balances: List<StockBalance>,
    val movements: List<StockMovement>,
) {
    data class SoldItem(val name: String, val quantity: Double, val amount: Double)

    private val sales: List<StockMovement> by lazy { movements.filter { it.kind == "sale" } }

    /** Топ проданного за окно — по выручке. */
    val topSold: List<SoldItem> by lazy {
        val map = LinkedHashMap<String, SoldItem>()
        for (m in sales) {
            val old = map[m.itemName]
            map[m.itemName] = SoldItem(
                m.itemName,
                (old?.quantity ?: 0.0) + kotlin.math.abs(m.quantity),
                (old?.amount ?: 0.0) + kotlin.math.abs(m.amount),
            )
        }
        map.values.sortedByDescending { it.amount }
    }

    /** Товары с остатком, но без единой продажи за окно — деньги, лежащие мёртвым грузом. */
    val stale: List<ItemTotal> by lazy {
        val sold = sales.map { it.itemName }.toSet()
        totalsOf(balances.filter { it.name !in sold && it.quantity > 0 }).sortedByDescending { it.quantity }
    }

    val totalSales: Double get() = sales.sumOf { kotlin.math.abs(it.amount) }
    val salesCount: Int get() = sales.size

    companion object {
        fun parse(data: JsonObject) = StoreAnalytics(
            balances = data["balances"].asObjects().map(StockBalance::parse),
            movements = data["movements"].asObjects().map(StockMovement::parse),
        )
    }
}
