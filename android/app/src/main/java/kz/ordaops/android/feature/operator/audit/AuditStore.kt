package kz.ordaops.android.feature.operator.audit

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiError
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load
import kz.ordaops.android.feature.operator.common.ActionOutbox

// ── Модели (OperatorAudit.swift) ─────────────────────────────────────────────

/**
 * Акт ревизии, на который назначен оператор. Оператор не начинает ревизию сам:
 * акт открывает управляющий и назначает кассиров на секции.
 */
@Serializable
@Immutable
data class AuditAct(
    @SerialName("act_id") val actId: String = "",
    val locationName: String = "",
    val comment: String? = null,
    @SerialName("opened_at") val openedAt: String? = null,
    /** Что именно поручено считать: «Вся локация» или перечень категорий. */
    val sectionLabel: String = "",
)

@Serializable
internal data class AuditActList(val data: List<AuditAct> = emptyList())

/**
 * Позиция акта. Системного остатка здесь нет намеренно — счёт слепой: иначе
 * кассир «подгоняет» под ожидаемое число и ревизия перестаёт что-либо показывать.
 */
@Serializable
@Immutable
data class AuditItem(
    @SerialName("item_id") val itemId: String = "",
    val name: String = "",
    val barcode: String? = null,
    val unit: String? = null,
    /** Мой подсчёт. `null` — ещё не считал. */
    val counted: Double? = null,
    /** Подсчёт коллеги (только в совместном режиме). */
    @SerialName("otherQty") val otherQuantity: Double? = null,
    val otherBy: String? = null,
)

@Serializable
@Immutable
internal data class AuditSheetData(
    @SerialName("act_id") val actId: String = "",
    /** `single` — совместный счёт, `double` — двойной слепой. */
    val mode: String = "single",
    val items: List<AuditItem> = emptyList(),
) {
    val isBlind: Boolean get() = mode == "double"
}

@Serializable
internal data class AuditSheet(val data: AuditSheetData = AuditSheetData())

@Serializable
internal data class AuditSaveResult(val data: AuditSaved = AuditSaved())

@Serializable
internal data class AuditSaved(val saved: Int = 0)

/** «12», «1,5» — как `Quantity.format` в OrdaKit. */
internal fun formatQuantity(value: Double?): String {
    if (value == null || !value.isFinite()) return "—"
    if (value == Math.rint(value)) return value.toLong().toString()
    return "%.3f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.').replace('.', ',')
}

/** Число из поля: запятая как точка, пустое — ноль, минус не бывает. */
internal fun parseQuantity(text: String): Double = maxOf(0.0, text.replace(',', '.').replace(" ", "").toDoubleOrNull() ?: 0.0)

// ── Список актов ─────────────────────────────────────────────────────────────

/**
 * Список назначенных актов и общая для раздела отправка подсчётов.
 *
 * Область корутин ViewModel живёт дольше экрана подсчёта: уходя с него, остаток
 * надо дослать, когда экрана уже нет.
 */
internal class AuditViewModel(private val api: ApiClient, private val outbox: ActionOutbox) : ViewModel() {
    private val _acts = MutableStateFlow(Loadable<List<AuditAct>>())
    val acts: StateFlow<Loadable<List<AuditAct>>> = _acts.asStateFlow()

    /** Почему не сохранился пересчёт, отправленный при уходе с экрана. */
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val listRequest = ApiRequest("/api/operator/audit")

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _acts.load(cached = { api.cached<AuditActList>(listRequest)?.data }) {
                api.send<AuditActList>(listRequest).data
            }
        }
    }

    fun dismissSaveError() {
        _saveError.value = null
    }

    fun sheetRequest(actId: String) = ApiRequest("/api/operator/audit", query = mapOf("act" to actId))

    suspend fun loadSheet(actId: String): AuditSheetData = api.send<AuditSheet>(sheetRequest(actId)).data

    suspend fun cachedSheet(actId: String): AuditSheetData? = api.cached<AuditSheet>(sheetRequest(actId))?.data

    /**
     * Сохранить подсчёты пачкой: по запросу на позицию ревизия на 800
     * наименований превратилась бы в 800 запросов.
     *
     * `null` в ответе — «легло в очередь»: связи нет, но пересчёт уже сделан.
     * Ревизию считают на складе, где сети не бывает, и терять час работы нельзя.
     * В очередь идёт только обрыв связи: отказ по существу (закрытый акт, чужая
     * секция) повтор не спасёт. Пачка всегда полная, поэтому в очереди она
     * заменяет предыдущую по тому же акту (`mergeKey`).
     */
    suspend fun saveCounts(actId: String, counts: Map<String, Double>): Int? {
        val body = JsonObject(
            mapOf(
                "act_id" to JsonPrimitive(actId),
                "counts" to JsonArray(
                    counts.map { (id, qty) ->
                        JsonObject(mapOf("item_id" to JsonPrimitive(id), "counted_qty" to JsonPrimitive(qty)))
                    },
                ),
            ),
        )
        return try {
            val result = api.send<AuditSaveResult>(ApiRequest.json("/api/operator/audit", body, HttpMethod.POST))
            _saveError.value = null
            result.data.saved
        } catch (e: ApiError.Transport) {
            outbox.perform("/api/operator/audit", HttpMethod.POST, body.toString(), "Подсчёты ревизии", "audit:$actId")
            _saveError.value = null
            null
        }
    }

    /** Досылка при уходе с экрана: показать отказ негде, поэтому он ложится в [saveError] на список. */
    fun flushOnLeave(actId: String, counts: Map<String, Double>) {
        if (counts.isEmpty()) return
        viewModelScope.launch {
            try {
                saveCounts(actId, counts)
            } catch (e: ApiError) {
                _saveError.value = e.userMessage
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _saveError.value = e.userMessage()
            }
        }
    }

    /** Корутина, переживающая экран подсчёта (для автосохранения). */
    fun launch(block: suspend () -> Unit): Job = viewModelScope.launch { block() }
}

// ── Подсчёт одного акта ──────────────────────────────────────────────────────

@Immutable
data class AuditToast(val text: String, val isError: Boolean)

/** Состояние экрана подсчёта: введённые числа и то, что ещё не улетело на сервер. */
internal class AuditCountViewModel(private val store: AuditViewModel, val actId: String) : ViewModel() {
    private val _sheet = MutableStateFlow(Loadable<AuditSheetData>())
    val sheet: StateFlow<Loadable<AuditSheetData>> = _sheet.asStateFlow()

    private val _counts = MutableStateFlow<Map<String, Double>>(emptyMap())
    val counts: StateFlow<Map<String, Double>> = _counts.asStateFlow()

    /**
     * Посчитанное, но ещё не улетевшее на сервер. Пересчёт на четыреста позиций
     * — час работы; держать его только в памяти телефона, который падает в
     * ящик с бутылками, нельзя.
     */
    private val _unsaved = MutableStateFlow<Set<String>>(emptySet())
    val unsaved: StateFlow<Set<String>> = _unsaved.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()
    private val _savedMessage = MutableStateFlow<String?>(null)
    val savedMessage: StateFlow<String?> = _savedMessage.asStateFlow()
    private val _toast = MutableStateFlow<AuditToast?>(null)
    val toast: StateFlow<AuditToast?> = _toast.asStateFlow()

    private var autosave: Job? = null

    init {
        viewModelScope.launch { load() }
        // Отметки «уже посчитал коллега» подтягиваем раз в двадцать секунд, как страница на сайте:
        // без этого двое считают одну полку и узнают об этом из расхождения в акте.
        viewModelScope.launch {
            while (true) {
                delay(20_000)
                refreshMarks()
            }
        }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        // Уже сохранённые ранее подсчёты подставляем: работу можно продолжить с другого устройства.
        _sheet.load(cached = { store.cachedSheet(actId) }, fresh = { store.loadSheet(actId) })
        _sheet.value.data?.let { applyLoaded(it, initial = false) }
    }

    private fun applyLoaded(loaded: AuditSheetData, initial: Boolean) {
        val pending = _unsaved.value
        _counts.update { current ->
            val next = current.toMutableMap()
            for (item in loaded.items) {
                val counted = item.counted ?: continue
                // Своё число с сервера подставляем только там, где локально ничего не
                // ждёт отправки, иначе обновление затрёт только что набранное.
                if (initial || item.itemId !in pending) next[item.itemId] = counted
            }
            next
        }
    }

    /** Тихое обновление: чужие отметки меняются, мой ввод — нет. */
    private suspend fun refreshMarks() {
        // В слепом режиме чужих отметок нет вовсе — и опрашивать нечего.
        val current = _sheet.value.data
        if (current == null || current.isBlind) return
        try {
            val fresh = store.loadSheet(actId)
            _sheet.update { it.copy(data = fresh) }
            applyLoaded(fresh, initial = false)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Фоновое обновление: отметки лишь постарели, следующий круг попробует снова.
        }
    }

    /**
     * Записанное число уходит на сервер само, через полторы секунды тишины.
     * Кнопка «Сохранить» осталась, но полагаться на неё нельзя: час пересчёта
     * пропадает, если телефон разрядился или приложение убили.
     */
    fun record(itemId: String, value: Double) {
        _counts.update { it + (itemId to value) }
        _savedMessage.value = null
        _unsaved.update { it + itemId }
        autosave?.cancel()
        autosave = store.launch {
            delay(1500)
            flushUnsaved()
        }
    }

    private fun takeUnsaved(): List<String> {
        val ids = _unsaved.value.toList()
        _unsaved.value = emptySet()
        return ids
    }

    private suspend fun flushUnsaved() {
        val ids = takeUnsaved()
        if (ids.isEmpty()) return
        val all = _counts.value
        val payload = ids.mapNotNull { id -> all[id]?.let { id to it } }.toMap()
        if (payload.isEmpty()) return
        try {
            store.saveCounts(actId, payload)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Не ушло — возвращаем в очередь: следующее число попробует снова, а «Сохранить» отправит всё разом.
            _unsaved.update { it + ids }
        }
    }

    /** Ушли с экрана: досылаем последнее число, не дожидаясь таймера. */
    fun onLeave() {
        autosave?.cancel()
        val ids = takeUnsaved()
        if (ids.isEmpty()) return
        val all = _counts.value
        store.flushOnLeave(actId, ids.mapNotNull { id -> all[id]?.let { id to it } }.toMap())
    }

    fun save() {
        // Один запрос за раз: повторный тап по «Сохранить» ничего не запускает.
        if (!_isSaving.compareAndSet(false, true)) return
        viewModelScope.launch {
            autosave?.cancel()
            val queued = takeUnsaved()
            try {
                // Очередь берёт на себя только обрыв связи — об этом и говорим прямо:
                // обещать «сохранено», когда данные лежат на телефоне, нельзя.
                val saved = store.saveCounts(actId, _counts.value)
                _savedMessage.value = if (saved != null) "Сохранено $saved" else "Связи нет — уйдёт само"
            } catch (e: ApiError) {
                _unsaved.update { it + queued }
                showToast(e.userMessage, true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _unsaved.update { it + queued }
                showToast(e.userMessage(), true)
            } finally {
                _isSaving.value = false
            }
        }
    }

    /** Показать плашку; гаснет сама (см. LaunchedEffect на экране). */
    fun showToast(text: String, isError: Boolean) {
        _toast.value = AuditToast(text, isError)
    }

    fun clearToast() {
        _toast.value = null
    }
}
