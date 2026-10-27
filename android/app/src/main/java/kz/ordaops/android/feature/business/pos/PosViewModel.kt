package kz.ordaops.android.feature.business.pos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiError
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.BizList
import kz.ordaops.android.feature.business.Company
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.operator.shift.OperatorShift
import kz.ordaops.android.feature.operator.shift.PaymentMethod
import kz.ordaops.android.feature.operator.shift.PointCustomer
import kz.ordaops.android.feature.operator.shift.parseIsoMillis
import kz.ordaops.android.feature.operator.shift.todayString
import java.util.UUID

/**
 * Касса сотрудника — состояние и запросы. Порт `OperatorPos.tsx` в режиме владельца:
 * точки `/api/admin/companies`, смена `/api/admin/pos/shift`, витрина и продажа
 * `/api/admin/pos/inventory-sales`, чек на почту `/api/pos/receipt-email`.
 *
 * Права проверяет сервер (`pos.view`, аддон веб-кассы): отказ приходит ответом 403 и
 * показывается как есть. Очереди офлайна у кассы сотрудника нет и в вебе: без сети чек
 * не проводится, а ключ чека (`local_ref`) переживает повтор, поэтому дубля не будет.
 */
class PosViewModel(private val api: ApiClient) : ViewModel() {

    var companies by mutableStateOf<List<Company>>(emptyList())
        private set
    var companyId by mutableStateOf<String?>(null)
        private set
    var booting by mutableStateOf(true)
        private set
    var bootError by mutableStateOf<String?>(null)
        private set

    /** Открытая смена выбранной точки; `null` — смены нет. */
    var shift by mutableStateOf<OperatorShift?>(null)
        private set
    var shiftLoading by mutableStateOf(false)
        private set

    var catalog by mutableStateOf<PosCatalog?>(null)
        private set
    var catalogLoading by mutableStateOf(false)
        private set
    var catalogError by mutableStateOf<String?>(null)
        private set

    var cart by mutableStateOf<List<PosLine>>(emptyList())
        private set
    var lastSale by mutableStateOf<PosReceipt?>(null)
        private set

    private var cartRef: String? = null
    private var busy = false

    val companyName: String
        get() = catalog?.companyName?.takeIf { it.isNotBlank() } ?: companies.firstOrNull { it.id == companyId }?.name ?: "Касса"
    val hasOpenShift: Boolean get() = shift?.id?.isNotEmpty() == true
    val isNight: Boolean get() = shift?.isNight == true
    val cartCount: Int get() = cart.size
    val cartTotal: Double get() = PosMath.subtotal(cart)

    // ── Точки ────────────────────────────────────────────────────────────────

    /** Список точек. Одна — выбирается сама, как в вебе; несколько — человек выбирает. */
    fun loadCompanies() {
        viewModelScope.launch {
            booting = true
            bootError = null
            try {
                companies = api.send<BizList<Company>>(ApiRequest("/api/admin/companies", query = mapOf("open" to "1"))).data
                if (companyId == null && companies.size == 1) selectCompany(companies[0].id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bootError = e.userMessage()
            } finally {
                booting = false
            }
        }
    }

    fun selectCompany(id: String) {
        if (id == companyId) return
        companyId = id
        shift = null
        catalog = null
        catalogError = null
        clearCart()
        loadShift()
    }

    // ── Смена ────────────────────────────────────────────────────────────────

    fun loadShift() {
        val company = companyId ?: return
        viewModelScope.launch {
            shiftLoading = true
            try {
                val response = api.send<PosShiftResponse>(ApiRequest("/api/admin/pos/shift", query = mapOf("company_id" to company)))
                shift = response.shift?.takeIf { it.id.isNotEmpty() }
                if (shift != null) loadCatalogNow(company)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                catalogError = e.userMessage()
            } finally {
                shiftLoading = false
            }
        }
    }

    /** Открыть смену «от себя»: тело `{ action: open, company_id, opening_cash, shift_type }`. */
    suspend fun openShift(openingCash: Double, night: Boolean): String? {
        val company = companyId ?: return "Выберите точку."
        return guarded("Смена уже открывается.") {
            try {
                api.sendUnit(
                    ApiRequest.json(
                        "/api/admin/pos/shift",
                        buildJsonObject {
                            put("action", "open")
                            put("company_id", company)
                            put("opening_cash", openingCash)
                            put("shift_type", if (night) "night" else "day")
                        },
                    ),
                )
                loadShift()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError.Conflict) {
                // 409 «смена уже открыта»: подхватываем её, как веб.
                if (e.code == "point-shift-already-open") {
                    loadShift()
                    null
                } else e.userMessage()
            } catch (e: Exception) {
                e.userMessage()
            }
        }
    }

    /** Закрыть смену: `{ action: close, company_id, closing_cash, closing_kaspi }`. */
    suspend fun closeShift(cash: Double, kaspi: Double): String? {
        val company = companyId ?: return "Выберите точку."
        return guarded("Смена уже закрывается.") {
            try {
                api.sendUnit(
                    ApiRequest.json(
                        "/api/admin/pos/shift",
                        buildJsonObject {
                            put("action", "close")
                            put("company_id", company)
                            put("closing_cash", cash.coerceAtLeast(0.0))
                            put("closing_kaspi", kaspi.coerceAtLeast(0.0))
                        },
                    ),
                )
                clearCart()
                shift = null
                catalog = null
                loadShift()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError.Conflict) {
                // Нет открытой смены — на сервере уже закрыта.
                if (e.code == "point-shift-no-open") {
                    shift = null
                    catalog = null
                    null
                } else e.userMessage()
            } catch (e: ApiError) {
                if (e.message?.contains("point-shift-required-checklists-missing") == true) "Перед закрытием завершите обязательные чек-листы." else e.userMessage()
            } catch (e: Exception) {
                e.userMessage()
            }
        }
    }

    // ── Витрина ──────────────────────────────────────────────────────────────

    fun loadCatalog() {
        val company = companyId ?: return
        viewModelScope.launch { loadCatalogNow(company) }
    }

    private suspend fun loadCatalogNow(company: String) {
        catalogLoading = true
        catalogError = null
        try {
            val data = api.send<PosCatalogResponse>(
                ApiRequest("/api/admin/pos/inventory-sales", query = mapOf("company_id" to company)),
            ).data
            catalog = PosCatalog(
                companyName = data?.company?.name.orEmpty(),
                items = data?.items.orEmpty().filter { it.id.isNotEmpty() },
                loyalty = PosLoyalty.from(data?.loyaltyConfig),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            catalogError = e.userMessage()
        } finally {
            catalogLoading = false
        }
    }

    /** Товар по штрихкоду: точное совпадение, как Enter в поиске веб-кассы. */
    fun itemByBarcode(code: String): PosItem? = catalog?.items?.firstOrNull { it.barcode == code.trim() }

    // ── Чек ──────────────────────────────────────────────────────────────────

    fun add(item: PosItem) = editCart { PosMath.add(it, item) }

    fun setQuantity(id: String, quantity: Double) = editCart { PosMath.setQuantity(it, id, quantity) }

    fun remove(id: String) = editCart { list -> list.filterNot { it.id == id } }

    /** «Универсальная» позиция: название и цена от руки, без остатка. */
    fun addUniversal(name: String, price: Double): Boolean {
        val title = name.trim()
        if (title.isEmpty() || price <= 0) return false
        editCart { it + PosLine(itemId = null, name = title, unit = "шт", quantity = 1.0, unitPrice = price) }
        return true
    }

    fun clearCart() = editCart { emptyList() }

    /** Любая правка чека — новый чек: ключ идемпотентности старой попытки к нему не относится. */
    private fun editCart(change: (List<PosLine>) -> List<PosLine>) {
        cart = change(cart)
        cartRef = null
    }

    /**
     * Провести продажу. `null` — проведена (чек в [lastSale]), иначе текст ошибки.
     * Ключ чека один на попытку и переживает повтор после обрыва сети.
     */
    suspend fun checkout(input: PosCheckout): String? {
        val company = companyId ?: return "Выберите точку."
        if (cart.isEmpty()) return "Корзина пуста."
        val lines = cart
        val ref = cartRef ?: UUID.randomUUID().toString().also { cartRef = it }
        return guarded("Оплата уже проводится.") {
            try {
                val data = api.send<PosSaleResponse>(
                    ApiRequest.json(
                        "/api/admin/pos/inventory-sales",
                        posSaleBody(company, todayString(), isNight, input, lines, ref),
                    ),
                ).data
                val subtotal = PosMath.subtotal(lines)
                lastSale = PosReceipt(
                    saleId = data?.saleId,
                    companyName = companyName,
                    soldAtMillis = parseIsoMillis(data?.soldAt) ?: System.currentTimeMillis(),
                    lines = lines,
                    subtotal = subtotal,
                    discount = input.discountAmount,
                    loyaltyDiscount = input.loyaltyDiscount,
                    total = (subtotal - input.discountAmount - input.loyaltyDiscount).coerceAtLeast(0.0),
                    method = input.method,
                    cash = input.cash,
                    kaspi = input.kaspi,
                    cashReceived = input.cashReceived?.takeIf { input.cash > 0 && it >= input.cash },
                    customerName = input.customerName,
                )
                clearCart()
                loadCatalog() // остатки витрины уменьшились
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError.Transport) {
                "Нет связи с сервером. Чек не проведён — повторите, когда появится сеть: дубля не будет."
            } catch (e: ApiError.Conflict) {
                if (e.code == "point-shift-no-open") {
                    shift = null
                    "Смена на точке закрыта. Откройте смену, чтобы продавать."
                } else e.userMessage()
            } catch (e: Exception) {
                e.userMessage()
            }
        }
    }

    fun dismissLastSale() {
        lastSale = null
    }

    // ── Клиенты и письмо ─────────────────────────────────────────────────────

    /** Поиск клиента точки: `GET /api/admin/customers?company_id=&search=`. */
    suspend fun searchCustomers(query: String): List<PointCustomer> {
        val company = companyId ?: return emptyList()
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return PointCustomer.listFrom(
            api.send<JsonElement>(ApiRequest("/api/admin/customers", query = mapOf("company_id" to company, "search" to q))),
        )
    }

    /** Чек на почту покупателя. Возвращает (получилось, текст для человека). */
    suspend fun sendReceiptEmail(saleId: String, email: String): Pair<Boolean, String> = try {
        val response = api.send<PosEmailResponse>(
            ApiRequest.json("/api/pos/receipt-email", buildJsonObject {
                put("saleId", saleId)
                put("email", email.trim())
            }),
        )
        if (response.ok) true to (response.message ?: "Чек отправлен на ${response.email ?: email}")
        else false to (response.message ?: "Чек не отправлен. Попробуйте ещё раз через минуту.")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false to e.userMessage()
    }

    private suspend fun guarded(busyMessage: String, block: suspend () -> String?): String? {
        if (busy) return busyMessage
        busy = true
        return try {
            block()
        } finally {
            busy = false
        }
    }
}

/** Касса одна на организацию: переключение вкладок не теряет чек и смену. */
@Composable
fun rememberPosViewModel(): PosViewModel {
    val api = LocalBusiness.current.api
    return viewModel(
        key = "pos:${api.organizationId ?: "own"}",
        factory = viewModelFactory { initializer { PosViewModel(api) } },
    )
}
