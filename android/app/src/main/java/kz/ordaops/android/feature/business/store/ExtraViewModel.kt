package kz.ordaops.android.feature.business.store

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load
import kz.ordaops.android.feature.business.LocalBusiness

/**
 * Остальные страницы магазина: настройки, смены, настройки чека, реклама, оплата
 * поставщикам, заявки поставщикам, план закупа, расходники, техкарты.
 *
 * Отдельно от [StoreViewModel], чтобы тот не разрастался: здесь нет общего обзора,
 * у каждой страницы своё состояние. Все записи — через сервер, приложение ничего
 * не считает за него.
 */
class StoreExtraViewModel(private val api: ApiClient) : ViewModel() {

    private fun JsonElement.payload(): JsonElement = (this as? JsonObject)?.get("data") ?: this
    private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

    private suspend fun <T> MutableStateFlow<Loadable<T>>.fetch(
        path: String,
        query: Map<String, String> = emptyMap(),
        parse: (JsonElement) -> T,
    ) {
        val request = ApiRequest(path, query = query)
        load(
            cached = { api.cached<JsonElement>(request)?.let { parse(it.payload()) } },
            fresh = { parse(api.send<JsonElement>(request).payload()) },
        )
    }

    private fun launchLoad(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private suspend fun write(path: String, body: JsonObject, method: HttpMethod = HttpMethod.POST): String? =
        try {
            api.sendUnit(ApiRequest.json(path, body, method))
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.userMessage()
        }

    // ── Настройки магазина ───────────────────────────────────────────────────

    private val _config = MutableStateFlow(Loadable<StoreConfig>())
    val config: StateFlow<Loadable<StoreConfig>> = _config.asStateFlow()

    fun loadConfig() = launchLoad { _config.fetch("/api/admin/store/config") { StoreConfig.parse(it.obj()) } }

    /** Какие точки — магазины. PUT, как на сайте; точка по умолчанию не трогается. */
    suspend fun saveEnabled(ids: Set<String>): String? =
        write(
            "/api/admin/store/config",
            buildJsonObject { put("store_enabled_ids", kotlinx.serialization.json.JsonArray(ids.sorted().map { JsonPrimitive(it) })) },
            HttpMethod.PUT,
        ).also { if (it == null) loadConfig() }

    // ── Смены ────────────────────────────────────────────────────────────────

    private val _shifts = MutableStateFlow(Loadable<List<ShiftRow>>())
    val shifts: StateFlow<Loadable<List<ShiftRow>>> = _shifts.asStateFlow()

    private val _shiftDetail = MutableStateFlow(Loadable<ShiftDetail>())
    val shiftDetail: StateFlow<Loadable<ShiftDetail>> = _shiftDetail.asStateFlow()

    private var lastShiftId: String? = null

    fun loadShifts(companyId: String, status: String) = launchLoad {
        _shifts.fetch("/api/admin/shifts/reports", mapOf("company_id" to companyId, "status" to status, "limit" to "200")) {
            it.obj()["shifts"].asObjects().map(ShiftRow::parse)
        }
    }

    fun loadShiftDetail(id: String) = launchLoad {
        if (lastShiftId != id) _shiftDetail.value = Loadable()
        lastShiftId = id
        _shiftDetail.fetch("/api/admin/shifts/reports/$id") { ShiftDetail.parse(it.obj()) }
    }

    // ── Настройки чека ───────────────────────────────────────────────────────

    /** Форма чека выбранной точки; `companyId` — для какой точки пришла. */
    data class ReceiptSettingsPage(val companyId: String, val form: ReceiptSettingsForm)

    private val _receiptSettings = MutableStateFlow(Loadable<ReceiptSettingsPage>())
    val receiptSettings: StateFlow<Loadable<ReceiptSettingsPage>> = _receiptSettings.asStateFlow()

    fun loadReceiptSettings(companyId: String?) = launchLoad {
        val query = if (companyId.isNullOrEmpty()) emptyMap() else mapOf("company_id" to companyId)
        _receiptSettings.fetch("/api/admin/store/receipt-settings", query) { data ->
            val o = data.obj()
            val settings = o["settings"].asObj()
            ReceiptSettingsPage(settings?.str("company_id") ?: companyId.orEmpty(), settings?.let(ReceiptSettingsForm::parse) ?: ReceiptSettingsForm())
        }
    }

    suspend fun saveReceiptSettings(companyId: String, form: ReceiptSettingsForm): String? =
        write("/api/admin/store/receipt-settings", form.body(companyId), HttpMethod.PUT)

    // ── Реклама ──────────────────────────────────────────────────────────────

    private val _ads = MutableStateFlow(Loadable<List<AdItem>>())
    val ads: StateFlow<Loadable<List<AdItem>>> = _ads.asStateFlow()

    fun loadAds(companyId: String) = launchLoad {
        _ads.fetch("/api/admin/advertising", mapOf("company_id" to companyId)) { it.asObjects().map(AdItem::parse) }
    }

    suspend fun setAdActive(companyId: String, id: String, active: Boolean): String? =
        write("/api/admin/advertising", buildJsonObject { put("id", id); put("is_active", active) }, HttpMethod.PATCH)
            .also { if (it == null) loadAds(companyId) }

    suspend fun renameAd(companyId: String, id: String, title: String, durationSec: Int?): String? =
        write(
            "/api/admin/advertising",
            buildJsonObject {
                put("id", id)
                put("title", title)
                if (durationSec != null) put("duration_sec", durationSec)
            },
            HttpMethod.PATCH,
        ).also { if (it == null) loadAds(companyId) }

    suspend fun deleteAd(companyId: String, id: String): String? =
        try {
            api.sendUnit(ApiRequest("/api/admin/advertising", HttpMethod.DELETE, query = mapOf("id" to id)))
            loadAds(companyId)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.userMessage()
        }

    // ── Оплата поставщикам ───────────────────────────────────────────────────

    private val _debts = MutableStateFlow(Loadable<DebtsData>())
    val debts: StateFlow<Loadable<DebtsData>> = _debts.asStateFlow()

    fun loadDebts(status: String) = launchLoad {
        _debts.fetch("/api/admin/store/debts", mapOf("status" to status)) { DebtsData.parse(it.obj()) }
    }

    /** Оплата долга: дата, способ и чек (файл) обязательны — расход заводит сервер. */
    suspend fun payDebt(id: String, paidAt: String, method: String, receiptUrl: String, comment: String?, status: String): String? =
        write(
            "/api/admin/store/debts/$id/pay",
            buildJsonObject {
                put("paid_at", paidAt)
                put("payment_method", method)
                put("receipt_file_url", receiptUrl)
                if (!comment.isNullOrBlank()) put("comment", comment.trim())
            },
        ).also { if (it == null) loadDebts(status) }

    suspend fun rescheduleDebt(id: String, dueDate: String, reason: String?, status: String): String? =
        write(
            "/api/admin/store/debts/$id/due-date",
            buildJsonObject {
                put("due_date", dueDate)
                if (!reason.isNullOrBlank()) put("reason", reason.trim())
            },
            HttpMethod.PATCH,
        ).also { if (it == null) loadDebts(status) }

    // ── Заявки поставщикам ───────────────────────────────────────────────────

    private val _orders = MutableStateFlow(Loadable<List<PurchaseOrderRow>>())
    val orders: StateFlow<Loadable<List<PurchaseOrderRow>>> = _orders.asStateFlow()

    private val _orderDetail = MutableStateFlow(Loadable<PurchaseOrderRow>())
    val orderDetail: StateFlow<Loadable<PurchaseOrderRow>> = _orderDetail.asStateFlow()

    private var lastOrderId: String? = null

    fun loadOrders() = launchLoad {
        _orders.fetch("/api/admin/store/purchase-orders") { it.obj()["orders"].asObjects().map(PurchaseOrderRow::parse) }
    }

    fun loadOrderDetail(id: String) = launchLoad {
        if (lastOrderId != id) _orderDetail.value = Loadable()
        lastOrderId = id
        _orderDetail.fetch("/api/admin/store/purchase-orders/$id") { PurchaseOrderRow.parse(it.obj()) }
    }

    /** Статус заявки: draft → sent → received или cancelled (причина по желанию). */
    suspend fun setOrderStatus(id: String, status: String, cancelReason: String? = null): String? =
        write(
            "/api/admin/store/purchase-orders/$id",
            buildJsonObject {
                put("status", status)
                if (!cancelReason.isNullOrBlank()) put("cancel_reason", cancelReason.trim())
            },
            HttpMethod.PATCH,
        ).also {
            if (it == null) {
                loadOrders()
                loadOrderDetail(id)
            }
        }

    // ── План закупа ──────────────────────────────────────────────────────────

    private val _plan = MutableStateFlow(Loadable<PurchasePlan>())
    val plan: StateFlow<Loadable<PurchasePlan>> = _plan.asStateFlow()

    fun loadPlan(companyId: String) = launchLoad {
        _plan.value = Loadable()
        _plan.fetch("/api/admin/store/purchase-plan/suggest", mapOf("company_id" to companyId)) { PurchasePlan.parse(it.obj()) }
    }

    // ── Расходники ───────────────────────────────────────────────────────────

    private val _consumables = MutableStateFlow(Loadable<Consumables>())
    val consumables: StateFlow<Loadable<Consumables>> = _consumables.asStateFlow()

    fun loadConsumables() = launchLoad {
        _consumables.fetch("/api/admin/inventory/consumables") { Consumables.parse(it.obj()) }
    }

    /** Норма расхода: сколько в месяц уходит на точке и за сколько дней предупреждать. */
    suspend fun saveNorm(itemId: String, locationId: String, monthlyQty: Double, alertDays: Int): String? =
        write(
            "/api/admin/inventory/consumables",
            buildJsonObject {
                put("action", "upsertNorm")
                put("payload", buildJsonObject {
                    put("item_id", itemId)
                    put("location_id", locationId)
                    put("monthly_qty", monthlyQty)
                    put("alert_days", alertDays)
                })
            },
        ).also { if (it == null) loadConsumables() }

    /** Выдача расходника на точку: сервер сам создаёт, одобряет, выдаёт и принимает заявку, списывая со склада. */
    suspend fun recordIssue(pointLocationId: String, date: String, itemId: String, qty: Double, comment: String?): String? =
        write(
            "/api/admin/inventory/consumables",
            buildJsonObject {
                put("action", "recordIssue")
                put("payload", buildJsonObject {
                    put("point_location_id", pointLocationId)
                    put("issue_date", date)
                    if (comment.isNullOrBlank()) put("comment", JsonNull) else put("comment", comment.trim())
                    put("items", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                        put("item_id", itemId)
                        put("requested_qty", qty)
                    })))
                })
            },
        ).also { if (it == null) loadConsumables() }

    // ── Техкарты ─────────────────────────────────────────────────────────────

    private val _production = MutableStateFlow(Loadable<ProductionData>())
    val production: StateFlow<Loadable<ProductionData>> = _production.asStateFlow()

    private val _analysis = MutableStateFlow(Loadable<ProductionAnalysis>())
    val analysis: StateFlow<Loadable<ProductionAnalysis>> = _analysis.asStateFlow()

    fun loadProduction() = launchLoad {
        _production.fetch("/api/admin/production/recipes") { ProductionData.parse(it.obj()) }
    }

    fun loadAnalysis(from: String, to: String) = launchLoad {
        _analysis.fetch("/api/admin/production/analysis", mapOf("from" to from, "to" to to)) { ProductionAnalysis.parse(it.obj()) }
    }

    /** Приход сырья (`receipt`) или пересчёт (`count`) — остаток ингредиента. */
    suspend fun stockAction(action: String, ingredientId: String, qty: Double): String? =
        write(
            "/api/admin/production/stock",
            buildJsonObject {
                put("action", action)
                put("ingredient_id", ingredientId)
                if (action == "receipt") put("qty", qty) else put("counted", qty)
            },
        ).also { if (it == null) loadProduction() }
}

@Composable
fun rememberStoreExtraViewModel(): StoreExtraViewModel {
    val api = LocalBusiness.current.api
    return viewModel(
        key = "store-extra:${api.organizationId ?: "own"}",
        factory = viewModelFactory { initializer { StoreExtraViewModel(api) } },
    )
}
