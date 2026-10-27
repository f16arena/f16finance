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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load
import kz.ordaops.android.feature.business.LocalBusiness

/**
 * Состояние раздела «Магазин»: обзор, остатки, витрина, каталог, документы.
 * Порт склада из `BusinessStore`.
 *
 * Один на все экраны магазина (по ключу организации): заявки, склад и списание
 * читают один и тот же обзор, и поправка остатка на одном экране видна на другом
 * без второго запроса. Прошлый ответ показываем сразу (кэш `ApiClient`), свежий
 * приходит следом.
 *
 * Все записи идут через сервер: приложение ничего не считает за него — ни
 * остатки, ни решение по заявке (оно одной атомарной функцией в базе).
 */
class StoreViewModel(private val api: ApiClient) : ViewModel() {

    // ── Чтение ───────────────────────────────────────────────────────────────

    private val _overview = MutableStateFlow(Loadable<StoreOverview>())
    val overview: StateFlow<Loadable<StoreOverview>> = _overview.asStateFlow()

    private val _showcase = MutableStateFlow(Loadable<ShowcasePage>())
    val showcase: StateFlow<Loadable<ShowcasePage>> = _showcase.asStateFlow()

    private val _catalog = MutableStateFlow(Loadable<List<CatalogItem>>())
    val catalog: StateFlow<Loadable<List<CatalogItem>>> = _catalog.asStateFlow()

    private val _receipts = MutableStateFlow(Loadable<ReceiptsData>())
    val receipts: StateFlow<Loadable<ReceiptsData>> = _receipts.asStateFlow()

    /** Заявки с ценами и «хватает ли на складе» — `GET /inventory/requests`; обзор цен не отдаёт. */
    private val _requests = MutableStateFlow(Loadable<List<StockRequest>>())
    val requests: StateFlow<Loadable<List<StockRequest>>> = _requests.asStateFlow()

    /** Живые магазины: включённые и не закрытые точки (`/store/config`). null — ещё не знаем. */
    @Volatile
    var shopIds: Set<String>? = null
        private set

    private val _writeoffs = MutableStateFlow(Loadable<List<Writeoff>>())
    val writeoffs: StateFlow<Loadable<List<Writeoff>>> = _writeoffs.asStateFlow()

    private val _stocktakes = MutableStateFlow(Loadable<List<Stocktake>>())
    val stocktakes: StateFlow<Loadable<List<Stocktake>>> = _stocktakes.asStateFlow()

    private val _acts = MutableStateFlow(Loadable<List<RevisionAct>>())
    val acts: StateFlow<Loadable<List<RevisionAct>>> = _acts.asStateFlow()

    private val _suppliers = MutableStateFlow(Loadable<List<Supplier>>())
    val suppliers: StateFlow<Loadable<List<Supplier>>> = _suppliers.asStateFlow()

    private val _movements = MutableStateFlow(Loadable<List<StockMovement>>())
    val movements: StateFlow<Loadable<List<StockMovement>>> = _movements.asStateFlow()

    private val _forecast = MutableStateFlow(Loadable<List<StockForecastRow>>())
    val forecast: StateFlow<Loadable<List<StockForecastRow>>> = _forecast.asStateFlow()

    private val _analytics = MutableStateFlow(Loadable<StoreAnalytics>())
    val analytics: StateFlow<Loadable<StoreAnalytics>> = _analytics.asStateFlow()

    private var lastActId: String? = null

    private val _actDetail = MutableStateFlow(Loadable<RevisionActDetail>())
    val actDetail: StateFlow<Loadable<RevisionActDetail>> = _actDetail.asStateFlow()

    /** `{ data: … }` → содержимое; ответ без обёртки берём как есть. */
    private fun JsonElement.payload(): JsonElement = (this as? JsonObject)?.get("data") ?: this

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

    fun loadOverview() = launchLoad {
        ensureShopIds()
        _overview.fetch("/api/admin/store/overview") {
            StoreOverview.parse(it as? JsonObject ?: JsonObject(emptyMap())).restrictedTo(shopIds)
        }
    }

    /**
     * Закрытая точка (archived_at) — только история, в выбор места она не попадает;
     * как и точка без включённого магазина. Тот же фильтр, что у переключателя на сайте.
     */
    private suspend fun ensureShopIds() {
        if (shopIds != null) return
        runCatching {
            val data = api.send<JsonElement>(ApiRequest("/api/admin/store/config")).payload() as? JsonObject
            shopIds = data?.get("companies").asObjects()
                .filter { it.bool("store_enabled", false) && it.str("archived_at") == null }
                .mapNotNull { it.str("id") }.toSet()
        }
    }

    fun loadRequests() = launchLoad {
        _requests.fetch("/api/admin/inventory/requests") { (it as? JsonObject)?.get("requests").asObjects().map(StockRequest::parse) }
    }

    fun loadShowcase(companyId: String? = null) = launchLoad {
        _showcase.fetch("/api/admin/store/showcase", companyQuery(companyId)) {
            ShowcasePage.parse(it as? JsonObject ?: JsonObject(emptyMap()))
        }
    }

    fun loadCatalog(companyId: String? = null) = launchLoad {
        _catalog.fetch("/api/admin/inventory/catalog", companyQuery(companyId)) { it.listOf(CatalogItem::parse) }
    }

    fun loadReceipts() = launchLoad {
        ensureShopIds()
        _receipts.fetch("/api/admin/store/receipts") { ReceiptsData.parse(it as? JsonObject ?: JsonObject(emptyMap())) }
    }

    fun loadWriteoffs() = launchLoad {
        _writeoffs.fetch("/api/admin/store/writeoffs") { (it as? JsonObject)?.get("writeoffs").asObjects().map(Writeoff::parse) }
    }

    fun loadStocktakes() = launchLoad {
        _stocktakes.fetch("/api/admin/store/revisions") { (it as? JsonObject)?.get("stocktakes").asObjects().map(Stocktake::parse) }
    }

    fun loadActs() = launchLoad {
        _acts.fetch("/api/admin/store/audit") { it.listOf(RevisionAct::parse) }
    }

    fun loadSuppliers() = launchLoad {
        _suppliers.fetch("/api/admin/store/suppliers") { (it as? JsonObject)?.get("suppliers").asObjects().map(Supplier::parse) }
    }

    fun loadMovements(scope: String = "all") = launchLoad {
        _movements.fetch("/api/admin/store/movements", mapOf("scope" to scope)) {
            (it as? JsonObject)?.get("movements").asObjects().map(StockMovement::parse)
        }
    }

    fun loadForecast(companyId: String? = null) = launchLoad {
        _forecast.fetch("/api/admin/inventory/forecast", companyQuery(companyId)) { it.listOf(StockForecastRow::parse) }
    }

    fun loadAnalytics(days: Int) = launchLoad {
        _analytics.fetch("/api/admin/store/analytics", mapOf("days" to days.toString())) {
            StoreAnalytics.parse(it as? JsonObject ?: JsonObject(emptyMap()))
        }
    }

    fun loadActDetail(actId: String) = launchLoad {
        // Деталь другого акта не должна мелькать, пока грузится этот.
        if (lastActId != actId) _actDetail.value = Loadable()
        lastActId = actId
        _actDetail.fetch("/api/admin/store/audit", mapOf("act" to actId)) {
            RevisionActDetail.parse(it as? JsonObject ?: JsonObject(emptyMap()))
        }
    }

    /** Глобальный поиск по складу: товары, приёмки, списания, заявки. Требует `store.global_search`. */
    suspend fun search(query: String, companyId: String?): List<StoreSearchHit> {
        val params = buildMap {
            put("q", query)
            if (!companyId.isNullOrEmpty()) put("company_id", companyId)
        }
        val response = api.send<JsonElement>(ApiRequest("/api/admin/store/global-search", query = params))
        return ((response.payload() as? JsonObject)?.get("results")).asObjects().map(StoreSearchHit::parse)
    }

    /** Категории товаров точки. */
    suspend fun categories(companyId: String?): List<Pair<String, String>> {
        val response = api.send<JsonElement>(ApiRequest("/api/admin/inventory/categories", query = companyQuery(companyId)))
        return response.payload().asObjects().map { (it.str("id") ?: "") to (it.str("name") ?: "") }
    }

    private fun companyQuery(companyId: String?): Map<String, String> =
        if (companyId.isNullOrEmpty()) emptyMap() else mapOf("company_id" to companyId)

    private fun <T> JsonElement.listOf(parse: (JsonObject) -> T): List<T> = asObjects().map(parse)

    // ── Запись ───────────────────────────────────────────────────────────────

    /**
     * Отправить тело и вернуть текст ошибки или null при успехе. Повторять запись
     * при непонятном ответе нельзя: у сервера нет ключа повтора, документ завёлся бы дважды.
     */
    private suspend fun write(path: String, body: JsonObject, method: HttpMethod = HttpMethod.POST): String? =
        try {
            api.sendUnit(ApiRequest.json(path, body, method))
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.userMessage()
        }

    /** Решение по заявке: одобрить как просили или отклонить. Один запрос — атомарная функция в базе. */
    suspend fun decideRequest(request: StockRequest, approved: Boolean, comment: String? = null): String? =
        write("/api/admin/inventory/requests", decideRequestBody(request.id, approved, request.lines, comment)).also { refreshIfOk(it) }

    suspend fun moveRequest(id: String, stage: RequestStage): String? =
        write("/api/admin/inventory/requests", transitionBody(id, stage)).also { refreshIfOk(it) }

    suspend fun undoRequest(id: String, reason: String): String? =
        write("/api/admin/inventory/requests", undecideBody(id, reason)).also { refreshIfOk(it) }

    suspend fun requestToShowcase(companyId: String, itemId: String, quantity: Double, comment: String?): String? =
        write("/api/admin/store/showcase", showcaseRequestBody(companyId, itemId, quantity, comment)).also { refreshIfOk(it) }

    suspend fun returnFromShowcase(companyId: String, itemId: String, quantity: Double): String? =
        write("/api/admin/store/showcase", showcaseReturnBody(companyId, itemId, quantity)).also { refreshIfOk(it) }

    /** Остаток склада ставится равным пересчитанному — повтор даёт тот же результат. */
    suspend fun setWarehouseQuantity(companyId: String, itemId: String, quantity: Double): String? =
        write(
            "/api/admin/store/warehouse",
            buildJsonObject {
                put("action", "setWarehouse")
                put("company_id", companyId)
                put("item_id", itemId)
                put("quantity", quantity)
            },
        ).also { refreshIfOk(it) }

    /**
     * Приёмка или оприходование. Сервер может ответить `ok: true` с `warning`: товар
     * оприходован, а расход или долг не записался. Предупреждение нельзя глотать —
     * иначе приёмку проведут повторно и остаток задвоится.
     */
    suspend fun createReceipt(draft: ReceiptDraft, companyId: String?, posting: Boolean): WriteOutcome =
        try {
            val body = if (posting) draft.postingBody(companyId) else draft.receiptBody(companyId)
            val response = api.send<JsonElement>(ApiRequest.json("/api/admin/store/receipts", body))
            loadOverview()
            loadReceipts()
            WriteOutcome(null, (response as? JsonObject)?.str("warning"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WriteOutcome(e.userMessage(), null)
        }

    /** Загрузить файл накладной или чека; вернёт ссылку на документ либо текст ошибки. */
    suspend fun uploadReceiptFile(bytes: ByteArray, mime: String, name: String): Pair<String?, String?> =
        try {
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", name, bytes.toRequestBody(mime.toMediaType()))
                .build()
            val response = api.send<JsonElement>(ApiRequest("/api/admin/store/receipts/upload", HttpMethod.POST, body = form))
            val url = (response as? JsonObject)?.str("document_url")
            if (url.isNullOrBlank()) null to "Сервер не вернул ссылку на файл" else url to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null to e.userMessage()
        }

    suspend fun createWriteoff(draft: WriteoffDraft, companyId: String?): String? =
        write("/api/admin/store/writeoffs", draft.body(companyId)).also {
            if (it == null) {
                loadOverview()
                loadWriteoffs()
            }
        }

    suspend fun createStocktake(draft: StocktakeDraft, companyId: String?): String? =
        write("/api/admin/store/revisions", draft.body(companyId)).also {
            if (it == null) {
                loadOverview()
                loadStocktakes()
            }
        }

    /** Отменить проведённую приёмку: остатки вернутся, документ останется в истории. Причина обязательна. */
    suspend fun cancelReceipt(id: String, reason: String): String? =
        write(
            "/api/admin/store/receipts",
            buildJsonObject {
                put("action", "cancelReceipt")
                put("receipt_id", id)
                put("cancel_reason", reason.trim())
            },
        ).also {
            if (it == null) {
                loadOverview()
                loadReceipts()
            }
        }

    suspend fun cancelWriteoff(id: String, reason: String): String? =
        write(
            "/api/admin/store/writeoffs",
            buildJsonObject {
                put("action", "cancelWriteoff")
                put("writeoff_id", id)
                put("cancel_reason", reason.trim())
            },
        ).also {
            if (it == null) {
                loadOverview()
                loadWriteoffs()
            }
        }

    suspend fun createSupplier(name: String, companyId: String, contact: String?, phone: String?, binIin: String?): String? =
        write(
            "/api/admin/store/suppliers",
            buildJsonObject {
                put("name", name.trim())
                put("company_id", companyId)
                if (!contact.isNullOrBlank()) put("contact_name", contact.trim())
                if (!phone.isNullOrBlank()) put("phone", phone.trim())
                if (!binIin.isNullOrBlank()) put("bin_iin", binIin.trim())
            },
        ).also { if (it == null) loadSuppliers() }

    /** Завести товар у полки: имя и штрихкод — обязательный минимум. */
    suspend fun createStoreItem(companyId: String, name: String, barcode: String, salePrice: Double, purchasePrice: Double): String? =
        write(
            "/api/admin/store/warehouse",
            buildJsonObject {
                put("action", "createItem")
                put("company_id", companyId)
                put("name", name.trim())
                put("barcode", barcode.trim())
                put("unit", "шт")
                put("sale_price", salePrice)
                put("purchase_price", purchasePrice)
            },
        ).also { refreshIfOk(it) }

    suspend fun createCatalogItem(draft: CatalogItemDraft, companyId: String): String? =
        write("/api/admin/inventory", draft.createBody(companyId)).also { if (it == null) loadCatalog() }

    /** Правка карточки — полная замена полей; фото — отдельным PATCH, если ссылка изменилась. */
    suspend fun updateCatalogItem(id: String, draft: CatalogItemDraft, previousImage: String?): String? {
        val error = write("/api/admin/inventory/catalog", draft.updateBody(id))
        if (error != null) return error
        val photo = draft.imageUrl.trim()
        if (photo != (previousImage ?: "")) {
            val photoError = write(
                "/api/admin/store/catalog/$id",
                buildJsonObject {
                    put("image_url", if (photo.isEmpty()) JsonNull else JsonPrimitive(photo))
                },
                HttpMethod.PATCH,
            )
            if (photoError != null) return photoError
        }
        loadCatalog()
        return null
    }

    /** Удалить товар: с историей сервер отправит в архив, с остатком — откажет. */
    suspend fun deleteCatalogItem(id: String): String? =
        write(
            "/api/admin/inventory/catalog",
            buildJsonObject {
                put("action", "deleteItem")
                put("item_id", id)
            },
        ).also { if (it == null) loadCatalog() }

    suspend fun restoreCatalogItem(id: String): String? =
        write(
            "/api/admin/inventory/catalog",
            buildJsonObject {
                put("action", "restoreItem")
                put("item_id", id)
            },
        ).also { if (it == null) loadCatalog() }

    // ── Акты ревизии ─────────────────────────────────────────────────────────

    private suspend fun actAction(action: String, actId: String, extra: Map<String, JsonElement> = emptyMap()): String? =
        write(
            "/api/admin/store/audit",
            buildJsonObject {
                put("action", action)
                put("act_id", actId)
                extra.forEach { (k, v) -> put(k, v) }
            },
        ).also {
            if (it == null) {
                loadActs()
                loadOverview()
            }
        }

    /** Отменить открытый акт: остатки не трогаются, снимок и подсчёты отбрасываются. */
    suspend fun cancelAct(id: String): String? = actAction("cancel", id)

    /** Провести акт: остатки становятся пересчитанными, расхождения — движениями и долгами. */
    suspend fun closeAct(id: String, zeroItemIds: Set<String>? = null, assignDebt: Boolean = false, assignDebtZeroed: Boolean = false): Pair<AuditCloseSummary?, String?> =
        try {
            val response = api.send<JsonElement>(ApiRequest.json("/api/admin/store/audit", closeActBody(id, zeroItemIds, assignDebt, assignDebtZeroed)))
            loadActs()
            loadOverview()
            AuditCloseSummary.parse(response.payload() as? JsonObject ?: JsonObject(emptyMap())) to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null to e.userMessage()
        }

    /** Сбросить подсчёты позиции: операторы посчитают заново. */
    suspend fun resetActItem(actId: String, itemId: String): String? =
        write("/api/admin/store/audit", buildJsonObject {
            put("action", "recount")
            put("act_id", actId)
            put("item_id", itemId)
        }).also { if (it == null) loadActDetail(actId) }

    /** Откатить проведённый акт. Тяжёлое действие: сервер пускает только владельца. */
    suspend fun revertAct(id: String): String? = actAction("revert", id)

    /** Записать итоговое количество по позиции; заменяет все прежние подсчёты (ответ на конфликт). */
    suspend fun recountAct(actId: String, itemId: String, quantity: Double): String? =
        actAction(
            "resolve",
            actId,
            mapOf(
                "item_id" to JsonPrimitive(itemId),
                "qty" to JsonPrimitive(quantity),
            ),
        ).also { if (it == null) loadActDetail(actId) }

    private fun refreshIfOk(error: String?) {
        if (error == null) {
            loadOverview()
            loadRequests()
        }
    }
}

/** Итог записи, которая может завершиться предупреждением: `error` — отказ, `warning` — сделано, но с оговоркой. */
data class WriteOutcome(val error: String?, val warning: String? = null)

/** Общее состояние магазина для экранов раздела. Ключ включает организацию — чужой склад не переезжает. */
@Composable
fun rememberStoreViewModel(): StoreViewModel {
    val api = LocalBusiness.current.api
    return viewModel(
        key = "store:${api.organizationId ?: "own"}",
        factory = viewModelFactory { initializer { StoreViewModel(api) } },
    )
}
