package kz.ordaops.android.feature.operator.arena

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiError
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load

/** Своя ли открытая смена: без неё сажать гостей нельзя (выручке некуда попасть). */
@Immutable
data class ArenaShift(val isOpen: Boolean = false, val isMine: Boolean = true) {
    val canSell: Boolean get() = isOpen && isMine
    val isSomeoneElses: Boolean get() = isOpen && !isMine
}

/**
 * Зал клуба: состояние станций и действия по сессиям — `ArenaStore.swift`.
 *
 * Живёт отдельно от смены: зал обновляется чаще всего остального, и тянуть
 * ради этого весь кабинет незачем. Действия зала в очередь не откладываем
 * (как и на айфоне): посадить гостя «потом» нельзя, деньги идут в кассу сразу.
 */
class ArenaViewModel(private val api: ApiClient) : ViewModel() {
    private val _hall = MutableStateFlow(Loadable<ArenaHall>())
    val hall: StateFlow<Loadable<ArenaHall>> = _hall.asStateFlow()

    /** Зала нет у каждой точки: `false` — сервер сказал «нет проекта точки». */
    private val _isAvailable = MutableStateFlow(true)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _shift = MutableStateFlow(ArenaShift())
    val shift: StateFlow<ArenaShift> = _shift.asStateFlow()

    /**
     * Часы зала: обратный отсчёт идёт и между запросами — гость смотрит на минуты, не на сеть.
     * Тикает только пока есть подписчик, и читают его лишь листовые композиции с таймерами,
     * чтобы секунда не перерисовывала весь экран.
     */
    val now: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())

    /** Идёт действие по сессии: вторую посадку/оплату, пока первая не ответила, не запускаем. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val hallRequest = ApiRequest("/api/operator/arena")
    private val shiftRequest = ApiRequest("/api/operator/shift/current")

    init {
        // Сначала то, что видели в прошлый раз, — экран рисуется сразу.
        viewModelScope.launch {
            runCatching { api.cached<ShiftHead>(shiftRequest) }.getOrNull()?.let(::applyShift)
            load()
        }
    }

    private fun applyShift(head: ShiftHead) {
        _shift.value = ArenaShift(isOpen = head.shift?.status == "open", isMine = head.isMine)
    }

    suspend fun load() {
        _hall.load(cached = { api.cached<ArenaEnvelope>(hallRequest)?.data }) {
            try {
                api.send<ArenaEnvelope>(hallRequest).data.also { _isAvailable.value = true }
            } catch (e: ApiError.NotFound) {
                // «Нет проекта точки» — не поломка, а устройство бизнеса: у магазина зала нет.
                if ((e.message ?: "").contains("point-project")) {
                    _isAvailable.value = false
                    ArenaHall()
                } else {
                    throw e
                }
            }
        }
        // Смена нужна только для прав на кнопки; её сбой экран не ломает.
        runCatching { api.send<ShiftHead>(shiftRequest) }
            .onFailure { if (it is CancellationException) throw it }
            .onSuccess(::applyShift)
    }

    fun refresh() {
        viewModelScope.launch { load() }
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

    private suspend fun action(build: JsonObjectBuilder.() -> Unit) {
        api.sendUnit(ApiRequest.json("/api/operator/arena", buildJsonObject(build)))
    }

    /**
     * Действие и перезагрузка зала. Возвращает текст ошибки или `null`.
     * Перезагружаем всегда: за станцией мог кто-то ещё запустить сессию из
     * программы за стойкой, и своё представление после действия — верный
     * способ разойтись с реальностью.
     *
     * Идёт в области ViewModel: поворот экрана не должен обрывать запрос на
     * посадку гостя на полпути.
     */
    private suspend fun run(block: suspend () -> Unit): String? {
        // Один запрос за раз: двойной тап или повтор после таймаута не должен посадить и списать дважды.
        if (!_busy.compareAndSet(false, true)) return "Предыдущее действие ещё выполняется"
        val job: Deferred<String?> = viewModelScope.async {
            val failure = try {
                block()
                null
            } catch (e: ApiError) {
                e.userMessage
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.userMessage()
            }
            _busy.value = false
            load()
            failure
        }
        return job.await()
    }
}
