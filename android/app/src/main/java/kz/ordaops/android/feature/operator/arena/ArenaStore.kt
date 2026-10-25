package kz.ordaops.android.feature.operator.arena

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiError
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage

/**
 * Зал клуба: состояние станций и действия по сессиям — `ArenaStore.swift`.
 *
 * Живёт отдельно от смены: зал обновляется чаще всего остального, и тянуть
 * ради этого весь кабинет незачем. Действия зала в очередь не откладываем
 * (как и на айфоне): посадить гостя «потом» нельзя, деньги идут в кассу сразу.
 */
class ArenaStore(private val api: ApiClient) {
    var hall by mutableStateOf<ArenaHall?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Часы экрана: обратный отсчёт идёт и между запросами — гость смотрит на минуты, не на сеть. */
    var nowMillis by mutableLongStateOf(System.currentTimeMillis())
        private set

    /** Зала нет у каждой точки: `false` — сервер сказал «нет проекта точки». */
    var isAvailable by mutableStateOf(true)
        private set

    /** Своя ли открытая смена: без неё сажать гостей нельзя (выручке некуда попасть). */
    var hasOpenShift by mutableStateOf(false)
        private set
    private var shiftIsMine by mutableStateOf(true)
    val isMyShift: Boolean get() = hasOpenShift && shiftIsMine
    val isSomeoneElsesShift: Boolean get() = hasOpenShift && !shiftIsMine

    fun tick() {
        nowMillis = System.currentTimeMillis()
    }

    private val hallRequest = ApiRequest("/api/operator/arena")
    private val shiftRequest = ApiRequest("/api/operator/shift/current")

    /** Сначала то, что видели в прошлый раз, — экран рисуется сразу. */
    suspend fun loadCached() {
        if (hall == null) {
            api.cached<ArenaEnvelope>(hallRequest)?.let { hall = it.data }
        }
        api.cached<ShiftHead>(shiftRequest)?.let(::applyShift)
    }

    private fun applyShift(head: ShiftHead) {
        hasOpenShift = head.shift?.status == "open"
        shiftIsMine = head.isMine
    }

    suspend fun load() {
        if (hall == null) isLoading = true
        try {
            val loaded = api.send<ArenaEnvelope>(hallRequest).data
            hall = loaded
            nowMillis = System.currentTimeMillis()
            error = null
            isAvailable = true
        } catch (e: ApiError) {
            // «Нет проекта точки» — не поломка, а устройство бизнеса: у магазина зала нет.
            if (e is ApiError.NotFound && (e.message ?: "").contains("point-project")) {
                isAvailable = false
                error = null
            } else {
                error = e.userMessage
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.userMessage()
        } finally {
            isLoading = false
        }
        // Смена нужна только для прав на кнопки; её сбой экран не ломает.
        runCatching { api.send<ShiftHead>(shiftRequest) }.onSuccess(::applyShift)
    }

    // ── Действия ─────────────────────────────────────────────────────────────

    suspend fun start(
        stationId: String,
        tariffId: String,
        payment: ArenaPayment,
        cash: Double,
        kaspi: Double,
        discountPercent: Double,
    ): String? = run {
        action {
            put("action", "startSession")
            put("stationId", stationId)
            put("tariffId", tariffId)
            put("payment_method", payment.wire)
            put("cash_amount", cash)
            put("kaspi_amount", kaspi)
            put("discount_percent", discountPercent)
        }
    }

    suspend fun end(sessionId: String): String? = run {
        action {
            put("action", "endSession")
            put("sessionId", sessionId)
        }
    }

    /** Возврат за неиспользованное: долю считает сервер, делить деньги на глазок нельзя. */
    suspend fun refund(sessionId: String): String? = run {
        action {
            put("action", "endSessionWithRefund")
            put("sessionId", sessionId)
        }
    }

    suspend fun extend(sessionId: String, tariffId: String, payment: ArenaPayment, cash: Double, kaspi: Double): String? = run {
        action {
            put("action", "extendSession")
            put("sessionId", sessionId)
            put("tariffId", tariffId)
            put("payment_method", payment.wire)
            put("cash_amount", cash)
            put("kaspi_amount", kaspi)
        }
    }

    suspend fun extendByAmount(sessionId: String, payment: ArenaPayment, cash: Double, kaspi: Double): String? = run {
        action {
            put("action", "extendSession")
            put("sessionId", sessionId)
            put("amount_extension", true)
            put("payment_method", payment.wire)
            put("cash_amount", cash)
            put("kaspi_amount", kaspi)
        }
    }

    suspend fun logTech(station: ArenaStation?, reason: String, amount: Double): String? = run {
        action {
            put("action", "techLog")
            put("reason", reason)
            put("amount", amount)
            station?.let {
                put("stationId", it.id)
                put("stationName", it.name)
            }
        }
    }

    private suspend fun action(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        api.sendUnit(ApiRequest.json("/api/operator/arena", buildJsonObject(build)))
    }

    /**
     * Действие и перезагрузка зала. Возвращает текст ошибки или `null`.
     * Перезагружаем всегда: за станцией мог кто-то ещё запустить сессию из
     * программы за стойкой, и своё представление после действия — верный
     * способ разойтись с реальностью.
     */
    private suspend fun run(block: suspend () -> Unit): String? {
        val failure = try {
            block()
            null
        } catch (e: ApiError) {
            e.userMessage
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.userMessage()
        }
        load()
        return failure
    }
}
