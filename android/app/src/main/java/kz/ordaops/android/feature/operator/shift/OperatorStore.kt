package kz.ordaops.android.feature.operator.shift

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.operator.common.ActionOutbox
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.feature.operator.common.OperatorEnv
import java.time.LocalTime
import java.util.UUID

/**
 * Состояние операторского дня — OperatorStore.swift: смена, каталог витрины,
 * корзина и очередь неотправленных чеков.
 *
 * Один объект на «Смену» и «Продажу»: обе вкладки смотрят на одну смену, а
 * разъехавшееся состояние здесь стоило бы расхождения в деньгах.
 */
class OperatorStore internal constructor(
    context: Context,
    val api: ApiClient,
    private val outbox: ActionOutbox,
    private val ownerId: String?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = SaleQueue(context.applicationContext, api)

    // ── Смена ────────────────────────────────────────────────────────────────

    var shiftState by mutableStateOf<ShiftState?>(null)
        private set
    var isLoadingShift by mutableStateOf(false)
        private set
    var shiftError by mutableStateOf<String?>(null)
        private set

    val shift: OperatorShift? get() = shiftState?.shift
    val totals: ShiftTotals get() = shiftState?.totals ?: ShiftTotals()
    val hasOpenShift: Boolean get() = shift?.isOpen == true
    /** Смена открыта, но не мной: на точке стоит сменщик. */
    val isSomeoneElsesShift: Boolean get() = hasOpenShift && shiftState?.isMine == false
    val isMyShift: Boolean get() = hasOpenShift && shiftState?.isMine != false
    val blockingChecklists: List<ChecklistTemplate> get() = shiftState?.blockingChecklists.orEmpty()
    val pendingArticles: List<PendingKnowledgeArticle> get() = shiftState?.pendingKnowledge.orEmpty()

    // ── Главная (кабинет) ────────────────────────────────────────────────────

    var overview by mutableStateOf<OperatorOverview?>(null)
        private set
    var isLoadingOverview by mutableStateOf(false)
        private set
    var tasks by mutableStateOf<List<HomeTask>>(emptyList())
        private set

    val overdueCount: Int get() = tasks.count { it.isOverdue }
    /** Торгует ли точка. Пока сводка не пришла — считаем, что да. */
    val sellsGoods: Boolean get() = overview?.points?.sellsGoods ?: true

    // ── Каталог и корзина ────────────────────────────────────────────────────

    var catalog by mutableStateOf<List<SaleCatalogItem>>(emptyList())
        private set
    var recentSales by mutableStateOf<List<RecentSale>>(emptyList())
        private set
    var companyName by mutableStateOf("Точка")
        private set
    var isLoadingCatalog by mutableStateOf(false)
        private set
    var catalogError by mutableStateOf<String?>(null)
        private set

    /** Услуги точки (теннис, печать) и идущие таймеры. */
    var services by mutableStateOf<List<SaleService>>(emptyList())
        private set
    var sessions by mutableStateOf<List<ServiceSession>>(emptyList())
        private set

    var cart by mutableStateOf<List<SaleLine>>(emptyList())
        private set
    val cartTotal: Double get() = cart.sumOf { it.total }
    val cartCount: Int get() = cart.size

    // ── Очередь ──────────────────────────────────────────────────────────────

    var queuedSalesCount by mutableIntStateOf(0)
        private set

    /** Последнее событие продажи — для подтверждения и ссылки на чек. */
    var lastSale by mutableStateOf<SaleFeedback?>(null)
        private set

    data class SaleFeedback(
        val total: Double,
        val receiptUrl: String?,
        val wasQueued: Boolean,
        /** Состав чека — для печати и экрана подтверждения (корзина после продажи пуста). */
        val lines: List<SaleLine> = emptyList(),
        val method: PaymentMethod = PaymentMethod.Cash,
        val cashAmount: Double = 0.0,
        val kaspiAmount: Double = 0.0,
        /** Сколько дал покупатель наличными; null — без сдачи. */
        val cashReceived: Double? = null,
        val soldAtMillis: Long = System.currentTimeMillis(),
        val id: String = UUID.randomUUID().toString(),
    ) {
        val change: Double get() = cashReceived?.let { (it - cashAmount).coerceAtLeast(0.0) } ?: 0.0
    }

    private var started = false
    private var shiftWatch: Job? = null

    /** Ключ, по которому хранилище переиспользуется между вкладками. */
    internal fun isFor(api: ApiClient, outbox: ActionOutbox, ownerId: String?) =
        this.api === api && this.outbox === outbox && this.ownerId == ownerId

    internal fun close() {
        scope.cancel()
    }

    // ── Загрузка ─────────────────────────────────────────────────────────────

    /**
     * Первый запуск: очереди с диска, смена, отправка накопленного. Один раз на
     * хранилище и в своём scope — переключение вкладки не должно его обрывать.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            queue.load()
            queue.setOwner(ownerId)
            refreshQueueCount()
            loadShift()
            watchShift()
            // Накопленные офлайн-чеки уходят при первой возможности — деньги не
            // должны лежать на устройстве дольше необходимости.
            flushQueue()
        }
    }

    /**
     * Догонять смену, пока приложение открыто — на любой вкладке: смену закрывают
     * на кассе точки, а не в телефоне.
     */
    private fun watchShift() {
        if (shiftWatch != null) return
        shiftWatch = scope.launch {
            while (isActive) {
                delay(30_000)
                loadShift()
            }
        }
    }

    suspend fun loadShift() {
        isLoadingShift = true
        shiftError = null
        try {
            val request = ApiRequest("/api/operator/shift/current")
            if (shiftState == null) api.cached<ShiftState>(request)?.let { shiftState = it }
            shiftState = api.send<ShiftState>(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Закэшированное остаётся на экране; ошибку показываем только когда показать нечего.
            if (shiftState == null) shiftError = e.userMessage()
        } finally {
            isLoadingShift = false
        }
    }

    suspend fun loadOverview() {
        val request = ApiRequest("/api/operator/overview")
        if (overview == null) api.cached<OperatorOverview>(request)?.let { overview = it }
        isLoadingOverview = overview == null
        try {
            overview = api.send<OperatorOverview>(request)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Сводка не критична: на экране остаются прошлые цифры.
        } finally {
            isLoadingOverview = false
        }
    }

    suspend fun loadTasks() {
        try {
            tasks = api.send<HomeTaskList>(ApiRequest("/api/operator/tasks")).tasks
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun loadCatalog() {
        isLoadingCatalog = true
        catalogError = null
        try {
            val request = ApiRequest("/api/operator/inventory-sales")
            if (catalog.isEmpty()) api.cached<SaleCatalogResponse>(request)?.data?.let { apply(it) }
            api.send<SaleCatalogResponse>(request).data?.let { apply(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (catalog.isEmpty()) catalogError = e.userMessage()
        } finally {
            isLoadingCatalog = false
        }
    }

    private fun apply(data: CatalogData) {
        catalog = data.items
        recentSales = data.sales
        companyName = data.company?.name ?: "Точка"
        services = data.services
    }

    /** Идущие таймеры услуг. Не критично: при сбое остаются прежние. */
    suspend fun loadSessions() {
        if (services.isEmpty() && sessions.isEmpty()) return
        try {
            sessions = api.send<ServiceSessionsResponse>(ApiRequest("/api/operator/service-sessions")).data?.sessions.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** «Завершить»: убираем сразу, на сервер — следом; не дошло — следующая загрузка вернёт таймер. */
    suspend fun finishSession(id: String) {
        sessions = sessions.filterNot { it.id == id }
        try {
            api.sendUnit(
                ApiRequest.json(
                    "/api/operator/service-sessions",
                    buildJsonObject {
                        put("action", "finish")
                        put("session_id", id)
                    },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            loadSessions()
        }
    }

    // ── Смена: открыть / закрыть ─────────────────────────────────────────────

    /** Открыть смену. Сервер проверит, стоит ли оператор в графике. Возвращает текст ошибки или null. */
    suspend fun openShift(openingCash: Double, kind: ShiftKind): String? = try {
        api.sendUnit(
            ApiRequest.json(
                "/api/operator/shift/open",
                buildJsonObject {
                    put("opening_cash", openingCash)
                    put("shift_type", kind.raw)
                },
            ),
        )
        loadShift()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }

    /**
     * Закрыть смену. Kaspi ночной смены сервер хранит раздельно (до и после
     * полуночи) — в ОПиУ они попадают в разные дни.
     */
    suspend fun closeShift(
        cash: Double,
        kaspi: Double,
        kaspiBeforeMidnight: Double = 0.0,
        kaspiAfterMidnight: Double = 0.0,
        notes: String?,
        report: ShiftReportDraft? = null,
    ): String? {
        // Закрывать с неотправленными чеками нельзя: их суммы ещё не в итогах, касса не сойдётся.
        if (queuedSalesCount > 0) {
            flushQueue()
            if (queuedSalesCount > 0) {
                return "Осталось $queuedSalesCount неотправленных чеков. Подключитесь к сети — смена закроется с верными суммами."
            }
        }
        return try {
            val shiftId = shift?.id
            val shiftType = shift?.shiftType ?: "day"
            api.sendUnit(
                ApiRequest.json(
                    "/api/operator/shift/close",
                    buildJsonObject {
                        put("closing_cash", cash)
                        put("closing_kaspi", kaspi)
                        put("kaspi_before_midnight", kaspiBeforeMidnight)
                        put("kaspi_after_midnight", kaspiAfterMidnight)
                        if (!notes.isNullOrEmpty()) put("closing_notes", notes)
                    },
                ),
            )
            // Отчёт — отдельным шагом после закрытия: закрытие фиксирует пересчёт
            // кассы, а выручка дня берётся из отчёта (иначе смены нет в ОПиУ и зарплате).
            if (report != null) {
                try {
                    api.sendUnit(
                        ApiRequest.body(
                            "/api/operator/shift-report",
                            report.copy(date = todayString(), shift = shiftType, shiftId = shiftId, comment = notes),
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Смена уже закрыта — молчать нельзя, но и «не удалось закрыть» было бы неправдой.
                    loadShift()
                    return "Смена закрыта, но отчёт не ушёл. Отправьте его из программы на точке или повторите позже."
                }
            }
            loadShift()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.userMessage()
        }
    }

    // ── Корзина ──────────────────────────────────────────────────────────────

    /** Повторное добавление увеличивает количество — при сканировании подряд это единственное разумное поведение. */
    fun add(item: SaleCatalogItem, quantity: Double = 1.0) {
        val price = item.salePrice
        if (price == null || price <= 0) return
        val index = cart.indexOfFirst { it.itemId == item.id }
        cart = if (index >= 0) {
            cart.toMutableList().also { it[index] = it[index].copy(quantity = it[index].quantity + quantity) }
        } else {
            cart + SaleLine(item.id, item.name, quantity, price)
        }
    }

    /**
     * Услуга в чек. Разные варианты — разные строки; продление — отдельная строка
     * на конкретный таймер, чтобы оплата прибавила время именно к нему.
     */
    fun addService(service: SaleService, variant: SaleServiceVariant, extend: ServiceSession? = null) {
        if (variant.price <= 0) return
        val key = "service:${variant.id}" + (extend?.let { ":${it.id}" } ?: "")
        val index = cart.indexOfFirst { it.itemId == key }
        cart = if (index >= 0) {
            cart.toMutableList().also { it[index] = it[index].copy(quantity = it[index].quantity + 1) }
        } else {
            val name = "${service.name} · ${variant.name}" + if (extend != null) " (продление)" else ""
            cart + SaleLine(key, name, 1.0, variant.price, serviceVariantId = variant.id, extendSessionId = extend?.id)
        }
    }

    fun setQuantity(quantity: Double, itemId: String) {
        val index = cart.indexOfFirst { it.itemId == itemId }
        if (index < 0) return
        cart = if (quantity <= 0) {
            cart.filterIndexed { i, _ -> i != index }
        } else {
            cart.toMutableList().also { it[index] = it[index].copy(quantity = quantity) }
        }
    }

    fun remove(itemId: String) {
        cart = cart.filterNot { it.itemId == itemId }
    }

    fun clearCart() {
        cart = emptyList()
    }

    /** Товар по штрихкоду; пробелы по краям (сканер их иногда добавляет) не мешают. */
    fun item(barcode: String): SaleCatalogItem? {
        val needle = barcode.trim()
        if (needle.isEmpty()) return null
        return catalog.firstOrNull { (it.barcode ?: "").trim() == needle }
    }

    // ── Продажа ──────────────────────────────────────────────────────────────

    /** Провести чек. Возвращает текст ошибки или null. */
    suspend fun checkout(
        method: PaymentMethod,
        cash: Double,
        kaspi: Double,
        customerId: String? = null,
        cashReceived: Double? = null,
    ): String? {
        if (cart.isEmpty()) return "Корзина пуста."
        val kind = if (shift?.shiftType == "night") ShiftKind.Night else ShiftKind.Day
        var draft = SaleDraft(
            saleDate = todayString(),
            shift = kind,
            lines = cart,
            paymentMethod = method,
            cashAmount = cash,
            kaspiAmount = kaspi,
            // Без клиента бонусы не начисляются: карта на брелке есть, а чек уходит «в никуда».
            customerId = customerId,
        )
        // Для ночной смены Kaspi обязан делиться на «до» и «после» полуночи.
        // Определяем по текущему времени: продажа проводится сейчас.
        if (kind == ShiftKind.Night && kaspi > 0) {
            draft = if (LocalTime.now().hour < 12) draft.copy(kaspiAfterMidnight = kaspi) else draft.copy(kaspiBeforeMidnight = kaspi)
        }
        // Сдача имеет смысл только с наличными и только если дали не меньше, чем нужно.
        val received = cashReceived?.takeIf { cash > 0 && it >= cash }
        return when (val outcome = queue.submit(draft)) {
            is SaleQueue.SubmitOutcome.Sent -> {
                lastSale = SaleFeedback(
                    outcome.result.totalAmount, outcome.result.receiptUrl, wasQueued = false,
                    lines = draft.lines, method = method, cashAmount = cash, kaspiAmount = kaspi, cashReceived = received,
                )
                if (outcome.result.serviceSessions.isNotEmpty()) {
                    // Продление возвращает тот же таймер с новым концом — заменяем, а не дублируем.
                    val started = outcome.result.serviceSessions
                    sessions = sessions.filterNot { old -> started.any { it.id == old.id } } + started
                }
                clearCart()
                loadShift()
                null
            }
            is SaleQueue.SubmitOutcome.Queued -> {
                refreshQueueCount()
                lastSale = SaleFeedback(
                    draft.total, null, wasQueued = true,
                    lines = draft.lines, method = method, cashAmount = cash, kaspiAmount = kaspi, cashReceived = received,
                )
                clearCart()
                null
            }
            is SaleQueue.SubmitOutcome.Failed -> outcome.error.userMessage
            is SaleQueue.SubmitOutcome.Rejected -> outcome.issue.message
        }
    }

    fun dismissLastSale() {
        lastSale = null
    }

    // ── Очередь ──────────────────────────────────────────────────────────────

    /** Чеки и отложенные действия уходят вместе: связь появилась — отправляем всё накопленное. */
    suspend fun flushQueue() {
        val result = queue.flush()
        val actions = outbox.flush()
        refreshQueueCount()
        if (result.sent > 0 || actions.sent > 0) loadShift()
        if (result.sent > 0) loadSessions()
    }

    private suspend fun refreshQueueCount() {
        queuedSalesCount = queue.pendingCount.value
    }
}

/** Хранилище, общее для «Смены» и «Продажи»: пока api и хозяин те же — тот же объект. */
private object OperatorStoreHolder {
    private var store: OperatorStore? = null

    fun get(context: Context, env: OperatorEnv): OperatorStore {
        store?.let { if (it.isFor(env.api, env.outbox, env.operatorId)) return it }
        store?.close()
        return OperatorStore(context.applicationContext, env.api, env.outbox, env.operatorId).also { store = it }
    }
}

/** Общее хранилище смены; первый вызов запускает его начальную загрузку. */
@Composable
fun rememberOperatorStore(): OperatorStore {
    val env = LocalOperatorEnv.current
    val context = LocalContext.current
    val store = remember(env.api, env.outbox, env.operatorId) { OperatorStoreHolder.get(context, env) }
    LaunchedEffect(store) { store.start() }
    return store
}
