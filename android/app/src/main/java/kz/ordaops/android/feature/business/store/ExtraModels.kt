package kz.ordaops.android.feature.business.store

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Остальные страницы магазина: настройки, смены, настройки чека, реклама, оплата
// поставщикам, заявки поставщикам, план закупа, расходники, техкарты. Разбор —
// по текущему вебу (`app/api/admin/**`), а не по iOS: сервер с тех пор менялся.

// ── Настройки магазина: /api/admin/store/config ──────────────────────────────

/** Точка организации в настройках магазина. */
data class ShopCompany(
    val id: String,
    val name: String,
    val code: String?,
    val storeEnabled: Boolean,
    /** Закрытая точка (archived_at): только история, магазином быть не может. */
    val archived: Boolean,
)

data class StoreConfig(
    val companies: List<ShopCompany>,
    /** Точка магазина по умолчанию (`store_company_id`). */
    val defaultCompanyId: String?,
    val canManage: Boolean,
) {
    /** Живые магазины: включённые и не закрытые. */
    val shops: List<ShopCompany> get() = companies.filter { it.storeEnabled && !it.archived }

    companion object {
        fun parse(o: JsonObject) = StoreConfig(
            companies = o["companies"].asObjects().map {
                ShopCompany(
                    id = it.str("id") ?: "",
                    name = it.str("name") ?: "Точка",
                    code = it.str("code"),
                    storeEnabled = it.bool("store_enabled", false),
                    archived = it.str("archived_at") != null,
                )
            },
            defaultCompanyId = o.str("store_company_id"),
            canManage = o.bool("can_manage", false),
        )
    }
}

// ── Смены: /api/admin/shifts/reports ─────────────────────────────────────────

/** Смена точки с итогами и расхождением кассы. */
data class ShiftRow(
    val id: String,
    val status: String,
    val operatorName: String?,
    val openedAt: Long?,
    val closedAt: Long?,
    val closingCash: Double,
    val closingKaspi: Double,
    /** Выручка: у открытой — живая из продаж, у закрытой — из итогов смены; null — не известна. */
    val sales: Double?,
    val liveCash: Double,
    val liveKaspi: Double,
    /**
     * Расхождение кассы, которое посчитала касса при закрытии: «+» излишек, «−» недостача.
     * Берётся `cash_report_diff` (из отчёта смены), иначе из итогов смены; null — не считалось.
     */
    val cashDiff: Double?,
) {
    val isOpen: Boolean get() = status == "open"

    /** Расхождение, которое стоит показать: копейки не в счёт. */
    val hasDiff: Boolean get() = cashDiff != null && kotlin.math.abs(cashDiff) >= 1

    companion object {
        fun parse(o: JsonObject): ShiftRow {
            val open = o.str("status") == "open"
            val totals = o["totals_json"].asObj()
            val live = o["live_totals"].asObj()
            val closedSales = totals?.let { t ->
                listOf("sales_total", "total_sales", "revenue", "sales", "total").firstNotNullOfOrNull { t.dbl(it) }
            }
            val diff = o.dbl("cash_report_diff")
                ?: totals?.let { t -> listOf("cash_difference", "cash_diff", "difference", "discrepancy", "diff").firstNotNullOfOrNull { t.dbl(it) } }
            val operator = o["operator"].asObj()
            return ShiftRow(
                id = o.str("id") ?: "",
                status = o.str("status") ?: "closed",
                operatorName = operator?.str("full_name") ?: operator?.str("short_name"),
                openedAt = parseWhen(o.str("opened_at")),
                closedAt = parseWhen(o.str("closed_at")),
                closingCash = o.dbl("closing_cash") ?: 0.0,
                closingKaspi = o.dbl("closing_kaspi") ?: 0.0,
                sales = if (open) live?.dbl("sales") else closedSales,
                liveCash = live?.dbl("cash") ?: 0.0,
                liveKaspi = live?.dbl("kaspi") ?: 0.0,
                cashDiff = diff,
            )
        }
    }
}

/** Свод по списку смен. */
data class ShiftSummary(
    val revenue: Double,
    val average: Double,
    val opened: Int,
    val shortCount: Int,
    val shortSum: Double,
    val overCount: Int,
    val overSum: Double,
) {
    companion object {
        fun of(shifts: List<ShiftRow>): ShiftSummary {
            var revenue = 0.0
            var counted = 0
            var opened = 0
            var shortCount = 0
            var shortSum = 0.0
            var overCount = 0
            var overSum = 0.0
            for (s in shifts) {
                s.sales?.let { revenue += it; counted += 1 }
                if (s.isOpen) opened += 1
                val d = s.cashDiff
                if (d != null && s.hasDiff) {
                    if (d < 0) { shortCount += 1; shortSum += d } else { overCount += 1; overSum += d }
                }
            }
            return ShiftSummary(revenue, if (counted > 0) revenue / counted else 0.0, opened, shortCount, shortSum, overCount, overSum)
        }
    }
}

data class ShiftSale(val id: String, val soldAt: Long?, val payment: String, val total: Double, val names: List<String>, val operatorName: String?)
data class ShiftReturn(val id: String, val returnedAt: Long?, val payment: String, val total: Double, val comment: String?)

data class ShiftDetail(val shift: ShiftRow, val sales: List<ShiftSale>, val returns: List<ShiftReturn>) {
    companion object {
        fun parse(o: JsonObject): ShiftDetail = ShiftDetail(
            shift = ShiftRow.parse(o["shift"].asObj() ?: JsonObject(emptyMap())),
            sales = o["sales"].asObjects().map { s ->
                ShiftSale(
                    id = s.str("id") ?: "",
                    soldAt = parseWhen(s.str("sold_at")),
                    payment = s.str("payment_method") ?: "",
                    total = s.dbl("total_amount") ?: 0.0,
                    names = s["items"].asObjects().mapNotNull { it["item"].asObj()?.str("name") ?: it.str("universal_name") },
                    operatorName = s["operator"].asObj()?.let { it.str("full_name") ?: it.str("short_name") },
                )
            },
            returns = o["returns"].asObjects().map { r ->
                ShiftReturn(r.str("id") ?: "", parseWhen(r.str("returned_at")), r.str("payment_method") ?: "", r.dbl("total_amount") ?: 0.0, r.str("comment"))
            },
        )
    }
}

fun paymentLabel(method: String): String = when (method) {
    "cash" -> "Нал"
    "kaspi" -> "Безнал"
    "card" -> "Карта"
    "online" -> "Онлайн"
    "mixed" -> "Смеш."
    else -> method
}

// ── Настройки чека: /api/admin/store/receipt-settings ────────────────────────

data class ReceiptSettingsForm(
    val taxPayerName: String = "",
    val taxPayerBin: String = "",
    val pointAddress: String = "",
    val kkmFactoryNumber: String = "",
    val kkmRegistrationNumber: String = "",
    val isVatPayer: Boolean = false,
    val vatRate: Double = 12.0,
    val ofdName: String = "",
    val ofdCheckUrl: String = "",
    /** `ru`, `kk` или `both`. */
    val receiptLanguage: String = "ru",
    val receiptFooterText: String = "",
    val requireBuyerIin: Boolean = false,
    val markingEnabled: Boolean = false,
    val nktEnabled: Boolean = false,
    val reviewUrl: String = "",
) {
    /** Тело PUT: сервер сам режет поля и берёт `settings`. */
    fun body(companyId: String): JsonObject = buildJsonObject {
        put("company_id", companyId)
        put("settings", buildJsonObject {
            put("tax_payer_name", taxPayerName)
            put("tax_payer_bin", taxPayerBin)
            put("point_address", pointAddress)
            put("kkm_factory_number", kkmFactoryNumber)
            put("kkm_registration_number", kkmRegistrationNumber)
            put("is_vat_payer", isVatPayer)
            put("vat_rate", vatRate)
            put("ofd_name", ofdName)
            put("ofd_check_url", ofdCheckUrl)
            put("receipt_language", receiptLanguage)
            put("receipt_footer_text", receiptFooterText)
            put("require_buyer_iin", requireBuyerIin)
            put("marking_enabled", markingEnabled)
            put("nkt_enabled", nktEnabled)
            put("review_url", reviewUrl)
        })
    }

    companion object {
        fun parse(o: JsonObject) = ReceiptSettingsForm(
            taxPayerName = o.str("tax_payer_name").orEmpty(),
            taxPayerBin = o.str("tax_payer_bin").orEmpty(),
            pointAddress = o.str("point_address").orEmpty(),
            kkmFactoryNumber = o.str("kkm_factory_number").orEmpty(),
            kkmRegistrationNumber = o.str("kkm_registration_number").orEmpty(),
            isVatPayer = o.bool("is_vat_payer", false),
            vatRate = o.dbl("vat_rate") ?: 12.0,
            ofdName = o.str("ofd_name").orEmpty(),
            ofdCheckUrl = o.str("ofd_check_url").orEmpty(),
            receiptLanguage = o.str("receipt_language") ?: "ru",
            receiptFooterText = o.str("receipt_footer_text").orEmpty(),
            requireBuyerIin = o.bool("require_buyer_iin", false),
            markingEnabled = o.bool("marking_enabled", false),
            nktEnabled = o.bool("nkt_enabled", false),
            reviewUrl = o.str("review_url").orEmpty(),
        )
    }
}

// ── Реклама на втором экране: /api/admin/advertising ─────────────────────────

data class AdItem(
    val id: String,
    val mediaType: String,
    val url: String,
    val title: String?,
    val durationSec: Int?,
    val isActive: Boolean,
) {
    val isVideo: Boolean get() = mediaType == "video"

    companion object {
        fun parse(o: JsonObject) = AdItem(
            id = o.str("id") ?: "",
            mediaType = o.str("media_type") ?: "image",
            url = o.str("url") ?: "",
            title = o.str("title"),
            durationSec = o.int("duration_sec"),
            isActive = o.bool("is_active", true),
        )
    }
}

// ── Оплата поставщикам: /api/admin/store/debts ───────────────────────────────

data class SupplierDebtRow(
    val id: String,
    val supplierName: String,
    val companyName: String?,
    val total: Double,
    val status: String,
    val dueDate: Long?,
    val isOverdue: Boolean,
    val isConsignment: Boolean,
    val invoiceNumber: String?,
    val receivedAt: Long?,
    val invoiceUrl: String?,
    val paidAt: Long?,
    val comment: String?,
) {
    val isOpen: Boolean get() = status == "open"

    val statusLabel: String
        get() = when (status) {
            "open" -> if (isOverdue) "Просрочен" else "К оплате"
            "paid" -> "Оплачен"
            "written_off" -> "Списан"
            else -> "Долг"
        }

    companion object {
        fun parse(o: JsonObject): SupplierDebtRow {
            val receipt = o["receipt"].asObj()
            return SupplierDebtRow(
                id = o.str("id") ?: "",
                supplierName = o["supplier"].asObj()?.str("name") ?: "Поставщик",
                companyName = o.nestedName("company"),
                total = o.dbl("total_amount") ?: 0.0,
                status = o.str("status") ?: "open",
                dueDate = parseWhen(o.str("due_date")),
                isOverdue = o.bool("is_overdue", false),
                isConsignment = o.bool("is_consignment", false),
                invoiceNumber = receipt?.str("invoice_number"),
                receivedAt = parseWhen(receipt?.str("received_at")),
                invoiceUrl = receipt?.str("invoice_file_url"),
                paidAt = parseWhen(o.str("payment_paid_at")),
                comment = o.str("payment_comment"),
            )
        }
    }
}

data class DebtsData(val debts: List<SupplierDebtRow>, val open: Double, val openCount: Int, val overdue: Double, val overdueCount: Int) {
    companion object {
        fun parse(o: JsonObject): DebtsData {
            val t = o["totals"].asObj()
            return DebtsData(
                debts = o["debts"].asObjects().map(SupplierDebtRow::parse),
                open = t?.dbl("open") ?: 0.0,
                openCount = t?.int("open_count") ?: 0,
                overdue = t?.dbl("overdue") ?: 0.0,
                overdueCount = t?.int("overdue_count") ?: 0,
            )
        }
    }
}

// ── Заявки поставщикам: /api/admin/store/purchase-orders ─────────────────────

data class PurchaseOrderRow(
    val id: String,
    val supplierName: String,
    val status: String,
    val isAuto: Boolean,
    val comment: String?,
    val createdAt: Long?,
    val sentAt: Long?,
    val receivedAt: Long?,
    val itemCount: Int,
    val salesRep: String?,
    val salesRepPhone: String?,
    val lines: List<Line>,
    val cancelReason: String?,
) {
    data class Line(val name: String, val unit: String, val suggested: Double, val current: Double, val threshold: Double?, val comment: String?)

    val statusLabel: String
        get() = when (status) {
            "draft" -> "Черновик"
            "sent" -> "Отправлена"
            "received" -> "Получена"
            "cancelled" -> "Отменена"
            else -> "Заявка"
        }

    companion object {
        fun parse(o: JsonObject): PurchaseOrderRow {
            val sup = o["supplier"].asObj()
            return PurchaseOrderRow(
                id = o.str("id") ?: "",
                supplierName = sup?.str("name") ?: "Поставщик",
                status = o.str("status") ?: "draft",
                isAuto = o.bool("is_auto", false),
                comment = o.str("comment"),
                createdAt = parseWhen(o.str("created_at")),
                sentAt = parseWhen(o.str("sent_at")),
                receivedAt = parseWhen(o.str("received_at")),
                itemCount = o.int("item_count") ?: o["items"].asObjects().size,
                salesRep = sup?.str("sales_rep_name"),
                salesRepPhone = sup?.str("sales_rep_phone") ?: sup?.str("phone"),
                lines = o["items"].asObjects().map {
                    val item = it["item"].asObj()
                    Line(
                        name = item?.str("name") ?: "Товар",
                        unit = item?.str("unit") ?: "шт",
                        suggested = it.dbl("suggested_qty") ?: 0.0,
                        current = it.dbl("current_qty") ?: 0.0,
                        threshold = it.dbl("threshold"),
                        comment = it.str("comment"),
                    )
                },
                cancelReason = o.str("cancel_reason"),
            )
        }
    }
}

// ── План закупа: /api/admin/store/purchase-plan/suggest ──────────────────────

data class PurchasePlan(
    val weekStart: String,
    val total: Double,
    val revenuePerWeek: Double,
    val groups: List<Group>,
    val doNotBuy: List<Skip>,
) {
    data class Line(
        val name: String,
        val weeklyDemand: Double,
        val stock: Double,
        val order: Double,
        val unitCost: Double,
        val amount: Double,
        val marginPct: Double,
        val coverageWeeks: Double,
        val wasOutOfStock: Boolean,
        val packSize: Double,
        val packs: Double,
    )

    data class Group(val supplier: String, val total: Double, val items: List<Line>)
    data class Skip(val name: String, val stock: Double, val weeklyDemand: Double, val coverageWeeks: Double)

    companion object {
        fun parse(o: JsonObject) = PurchasePlan(
            weekStart = o.str("weekStart").orEmpty(),
            total = o.dbl("total") ?: 0.0,
            revenuePerWeek = o.dbl("revenue4wPerWeek") ?: 0.0,
            groups = o["bySupplier"].asObjects().map { g ->
                Group(
                    supplier = g.str("supplier") ?: "Без поставщика",
                    total = g.dbl("total") ?: 0.0,
                    items = g["items"].asObjects().map {
                        Line(
                            name = it.str("name") ?: "Товар",
                            weeklyDemand = it.dbl("weeklyDemand") ?: 0.0,
                            stock = it.dbl("stock") ?: 0.0,
                            order = it.dbl("order") ?: 0.0,
                            unitCost = it.dbl("unitCost") ?: 0.0,
                            amount = it.dbl("amount") ?: 0.0,
                            marginPct = it.dbl("marginPct") ?: 0.0,
                            coverageWeeks = it.dbl("coverageWeeks") ?: 0.0,
                            wasOutOfStock = it.bool("wasOutOfStock", false),
                            packSize = it.dbl("packSize") ?: 1.0,
                            packs = it.dbl("packs") ?: 0.0,
                        )
                    },
                )
            },
            doNotBuy = o["doNotBuy"].asObjects().map {
                Skip(it.str("name") ?: "Товар", it.dbl("stock") ?: 0.0, it.dbl("weeklyDemand") ?: 0.0, it.dbl("coverageWeeks") ?: 0.0)
            },
        )
    }
}

// ── Расходники: /api/admin/inventory/consumables ─────────────────────────────

data class Consumables(
    val items: List<Item>,
    val norms: List<Norm>,
    val balances: List<Balance>,
    val points: List<Point>,
    val issues: List<Issue>,
) {
    data class Item(val id: String, val name: String, val unit: String)
    data class Norm(val id: String, val itemId: String, val locationId: String, val monthlyQty: Double, val alertDays: Int)
    data class Balance(val itemId: String, val locationId: String, val quantity: Double, val itemName: String, val locationName: String)
    data class Point(val locationId: String, val name: String, val companyName: String?)
    data class Issue(val id: String, val createdAt: Long?, val status: String, val companyName: String?, val comment: String?, val lines: List<Pair<String, Double>>)

    /** Запас в днях по норме: ниже порога тревоги — красный, до двух порогов — янтарный. */
    fun stockDays(balance: Balance): Int? {
        val norm = norms.firstOrNull { it.itemId == balance.itemId && it.locationId == balance.locationId } ?: return null
        if (norm.monthlyQty <= 0) return null
        return kotlin.math.floor(balance.quantity / (norm.monthlyQty / 30)).toInt()
    }

    fun norm(balance: Balance) = norms.firstOrNull { it.itemId == balance.itemId && it.locationId == balance.locationId }

    companion object {
        fun parse(o: JsonObject) = Consumables(
            items = o["items"].asObjects().map { Item(it.str("id") ?: "", it.str("name") ?: "Расходник", it.str("unit") ?: "шт") },
            norms = o["norms"].asObjects().map {
                Norm(it.str("id") ?: "", it.str("item_id") ?: "", it.str("location_id") ?: "", it.dbl("monthly_qty") ?: 0.0, it.int("alert_days") ?: 14)
            },
            balances = o["balances"].asObjects().map {
                Balance(
                    it.str("item_id") ?: "", it.str("location_id") ?: "", it.dbl("quantity") ?: 0.0,
                    it["item"].asObj()?.str("name") ?: "Расходник", it["location"].asObj()?.str("name") ?: "Склад",
                )
            },
            points = o["locations"].asObjects().filter { it.str("location_type") == "point_display" }.map {
                Point(it.str("id") ?: "", it.str("name") ?: "Точка", it["company"].asObj()?.str("name"))
            },
            issues = o["issues"].asObjects().map { r ->
                Issue(
                    id = r.str("id") ?: "",
                    createdAt = parseWhen(r.str("created_at")),
                    status = r.str("status") ?: "",
                    companyName = r["company"].asObj()?.str("name"),
                    comment = r.str("comment")?.removePrefix("[consumable-issue]")?.trim(),
                    lines = r["items"].asObjects().map { (it["item"].asObj()?.str("name") ?: "Расходник") to (it.dbl("approved_qty") ?: it.dbl("requested_qty") ?: 0.0) },
                )
            },
        )
    }
}

// ── Техкарты: /api/admin/production/* ────────────────────────────────────────

data class Recipe(
    val id: String,
    val name: String,
    val category: String?,
    val outputQty: Double,
    val outputUnit: String,
    val isSemiFinished: Boolean,
    val saleItemId: String?,
    val recipeCost: Double,
    val portionCost: Double,
    val components: List<Component>,
) {
    data class Component(val name: String, val qty: Double, val unit: String, val wastePct: Double)

    /** Доля себестоимости в цене: ниже — лучше. */
    fun foodCostPct(salePrice: Double?): Double? = if (salePrice == null || salePrice <= 0) null else portionCost / salePrice * 100

    companion object {
        fun parse(o: JsonObject) = Recipe(
            id = o.str("id") ?: "",
            name = o.str("name") ?: "Техкарта",
            category = o.str("category"),
            outputQty = o.dbl("output_qty") ?: 1.0,
            outputUnit = o.str("output_unit") ?: "порц",
            isSemiFinished = o.bool("is_semi_finished", false),
            saleItemId = o.str("sale_item_id"),
            recipeCost = o.dbl("recipe_cost") ?: 0.0,
            portionCost = o.dbl("portion_cost") ?: 0.0,
            components = o["components"].asObjects().map {
                Component(it.str("name") ?: "Ингредиент", it.dbl("qty") ?: 0.0, it.str("unit") ?: "", it.dbl("waste_pct") ?: 0.0)
            },
        )
    }
}

data class Ingredient(val id: String, val name: String, val unit: String, val price: Double, val stock: Double)

data class ProductionData(val recipes: List<Recipe>, val ingredients: List<Ingredient>, val salePrices: Map<String, Double>) {
    companion object {
        fun parse(o: JsonObject) = ProductionData(
            recipes = o["recipes"].asObjects().map(Recipe::parse),
            ingredients = o["ingredients"].asObjects().map {
                Ingredient(it.str("id") ?: "", it.str("name") ?: "Сырьё", it.str("unit") ?: "", it.dbl("purchase_price") ?: 0.0, it.dbl("stock_qty") ?: 0.0)
            },
            salePrices = o["saleItems"].asObjects().mapNotNull { s -> s.str("id")?.let { it to (s.dbl("sale_price") ?: 0.0) } }.toMap(),
        )
    }
}

data class ProductionAnalysis(val rows: List<Row>, val sold: Double, val foodCost: Double, val revenue: Double, val margin: Double, val foodCostPct: Double) {
    data class Row(val name: String, val sold: Double, val portionCost: Double, val foodCost: Double, val revenue: Double, val margin: Double, val foodCostPct: Double)

    companion object {
        fun parse(o: JsonObject): ProductionAnalysis {
            val t = o["totals"].asObj()
            return ProductionAnalysis(
                rows = o["rows"].asObjects().map {
                    Row(
                        it.str("name") ?: "Блюдо", it.dbl("sold_qty") ?: 0.0, it.dbl("portion_cost") ?: 0.0, it.dbl("food_cost") ?: 0.0,
                        it.dbl("revenue") ?: 0.0, it.dbl("margin") ?: 0.0, it.dbl("food_cost_pct") ?: 0.0,
                    )
                },
                sold = t?.dbl("sold") ?: 0.0,
                foodCost = t?.dbl("food_cost") ?: 0.0,
                revenue = t?.dbl("revenue") ?: 0.0,
                margin = t?.dbl("margin") ?: 0.0,
                foodCostPct = t?.dbl("food_cost_pct") ?: 0.0,
            )
        }
    }
}
