package kz.ordaops.android.feature.business.store

import kotlinx.serialization.json.JsonObject

// Данные формы приёмки (GET /api/admin/store/receipts) и итог закрытия акта.
// С 27.10 приёмка требует накладную-файл, категорию расхода COGS, чек оплаты
// (если платим сразу) и «годен до» у товаров со сроком — справочники для этого
// приходят тем же ответом, что и список документов.

/** Позиция каталога для выбора в приёмке: цены подставляются, срок годности проверяется. */
data class PickableItem(
    val id: String,
    val name: String,
    val unit: String,
    val barcode: String?,
    val salePrice: Double,
    val purchasePrice: Double,
    /** Товар со сроком годности: без «годен до» сервер приёмку не проведёт. */
    val requiresExpiry: Boolean,
    val itemType: String,
) {
    companion object {
        fun parse(o: JsonObject) = PickableItem(
            id = o.str("id") ?: "",
            name = o.str("name") ?: "Товар",
            unit = o.str("unit") ?: "шт",
            barcode = o.str("barcode"),
            salePrice = o.dbl("sale_price") ?: 0.0,
            purchasePrice = o.dbl("default_purchase_price") ?: 0.0,
            requiresExpiry = o.bool("requires_expiry", true),
            itemType = o.str("item_type") ?: "product",
        )
    }
}

/** Категория расхода; для приёмки годятся только из группы COGS. */
data class ExpenseCategoryRef(val id: String, val name: String, val group: String?) {
    val isCogs: Boolean get() = group?.trim()?.lowercase() == "cogs"
}

/** Ответ списка приёмок целиком: документы, справочники формы и счётчик черновиков. */
data class ReceiptsData(
    val receipts: List<Receipt>,
    val items: List<PickableItem>,
    val suppliers: List<Pair<String, String>>,
    val locations: List<StoreLocation>,
    val categories: List<ExpenseCategoryRef>,
    val draftCount: Int,
) {
    companion object {
        fun parse(o: JsonObject) = ReceiptsData(
            receipts = o["receipts"].asObjects().map(Receipt::parse),
            // Услуга — позиция без остатка: принимать и списывать её нечего.
            items = o["items"].asObjects().map(PickableItem::parse).filter { it.itemType != "service" },
            suppliers = o["suppliers"].asObjects().map { (it.str("id") ?: "") to (it.str("name") ?: "Поставщик") },
            locations = o["locations"].asObjects().map(StoreLocation::parse),
            categories = o["expense_categories"].asObjects().map { ExpenseCategoryRef(it.str("id") ?: "", it.str("name") ?: "", it.str("accounting_group")) },
            draftCount = o["drafts"].asObjects().size,
        )
    }
}

/** Итог закрытия акта ревизии: что насчитали, что обнулили. */
data class AuditCloseSummary(
    val shrinkageItems: Int,
    val shrinkageValue: Double,
    val surplusItems: Int,
    val surplusValue: Double,
    val zeroedItems: Int,
    val zeroedValue: Double,
    val debtsCreated: Int,
) {
    companion object {
        fun parse(data: JsonObject): AuditCloseSummary {
            val s = data["summary"].asObj()
            return AuditCloseSummary(
                shrinkageItems = s?.int("shrinkageItems") ?: 0,
                shrinkageValue = s?.dbl("shrinkageValue") ?: 0.0,
                surplusItems = s?.int("surplusItems") ?: 0,
                surplusValue = s?.dbl("surplusValue") ?: 0.0,
                zeroedItems = s?.int("zeroedItems") ?: 0,
                zeroedValue = s?.dbl("zeroedValue") ?: 0.0,
                debtsCreated = data.int("debtsCreated") ?: 0,
            )
        }
    }
}
