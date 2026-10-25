package kz.ordaops.android.feature.operator.audit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import kz.ordaops.android.feature.operator.common.ActionOutbox

// ── Модели (OperatorAudit.swift) ─────────────────────────────────────────────

/**
 * Акт ревизии, на который назначен оператор. Оператор не начинает ревизию сам:
 * акт открывает управляющий и назначает кассиров на секции.
 */
@Serializable
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
 * Своя область корутин, а не областью экрана: уходя с подсчёта, остаток надо
 * дослать, когда экрана уже нет.
 */
internal class AuditStore(private val api: ApiClient, private val outbox: ActionOutbox) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var acts by mutableStateOf<List<AuditAct>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Почему не сохранился пересчёт, отправленный при уходе с экрана. */
    var saveError by mutableStateOf<String?>(null)

    private val listRequest = ApiRequest("/api/operator/audit")

    suspend fun loadCached() {
        if (acts.isEmpty()) api.cached<AuditActList>(listRequest)?.let { acts = it.data }
    }

    suspend fun load() {
        error = null
        try {
            acts = api.send<AuditActList>(listRequest).data
        } catch (e: ApiError) {
            if (acts.isEmpty()) error = e.userMessage
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = e.userMessage()
        } finally {
            isLoading = false
        }
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
            saveError = null
            result.data.saved
        } catch (e: ApiError.Transport) {
            outbox.perform("/api/operator/audit", HttpMethod.POST, body.toString(), "Подсчёты ревизии", "audit:$actId")
            saveError = null
            null
        }
    }

    /** Досылка при уходе с экрана: показать отказ негде, поэтому он ложится в [saveError] на список. */
    fun flushOnLeave(actId: String, counts: Map<String, Double>) {
        if (counts.isEmpty()) return
        scope.launch {
            try {
                saveCounts(actId, counts)
            } catch (e: ApiError) {
                saveError = e.userMessage
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                saveError = e.userMessage()
            }
        }
    }

    /** Корутина, переживающая экран подсчёта (для автосохранения). */
    fun launch(block: suspend () -> Unit): Job = scope.launch { block() }
}

// ── Подсчёт одного акта ──────────────────────────────────────────────────────

/** Состояние экрана подсчёта: введённые числа и то, что ещё не улетело на сервер. */
internal class AuditCountState(private val store: AuditStore, val act: AuditAct) {
    internal var sheet by mutableStateOf<AuditSheetData?>(null)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val counts = mutableStateMapOf<String, Double>()

    /**
     * Посчитанное, но ещё не улетевшее на сервер. Пересчёт на четыреста позиций
     * — час работы; держать его только в памяти телефона, который падает в
     * ящик с бутылками, нельзя.
     */
    val unsaved = mutableStateListOf<String>()

    var isSaving by mutableStateOf(false)
        private set
    var savedMessage by mutableStateOf<String?>(null)
        private set
    var toast by mutableStateOf<String?>(null)
        private set
    var toastIsError by mutableStateOf(false)
        private set

    private var autosave: Job? = null

    val countedCount: Int get() = sheet?.items?.count { counts[it.itemId] != null } ?: 0

    suspend fun load() {
        error = null
        if (sheet == null) {
            store.cachedSheet(act.actId)?.let { applyLoaded(it, initial = true) }
        }
        try {
            // Уже сохранённые ранее подсчёты подставляем: работу можно продолжить с другого устройства.
            applyLoaded(store.loadSheet(act.actId), initial = true)
        } catch (e: ApiError) {
            if (sheet == null) error = e.userMessage
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (sheet == null) error = e.userMessage()
        } finally {
            isLoading = false
        }
    }

    private fun applyLoaded(loaded: AuditSheetData, initial: Boolean) {
        sheet = loaded
        for (item in loaded.items) {
            val counted = item.counted ?: continue
            // Своё число с сервера подставляем только там, где локально ничего не
            // ждёт отправки, иначе обновление затрёт только что набранное.
            if (initial || item.itemId !in unsaved) counts[item.itemId] = counted
        }
    }

    /** Тихое обновление: чужие отметки меняются, мой ввод — нет. */
    suspend fun refreshMarks() {
        // В слепом режиме чужих отметок нет вовсе — и опрашивать нечего.
        if (sheet?.isBlind != false) return
        try {
            applyLoaded(store.loadSheet(act.actId), initial = false)
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
        counts[itemId] = value
        savedMessage = null
        if (itemId !in unsaved) unsaved.add(itemId)
        autosave?.cancel()
        autosave = store.launch {
            delay(1500)
            flushUnsaved()
        }
    }

    private suspend fun flushUnsaved() {
        val ids = unsaved.toList()
        if (ids.isEmpty()) return
        unsaved.clear()
        val payload = ids.mapNotNull { id -> counts[id]?.let { id to it } }.toMap()
        if (payload.isEmpty()) return
        try {
            store.saveCounts(act.actId, payload)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Не ушло — возвращаем в очередь: следующее число попробует снова, а «Сохранить» отправит всё разом.
            ids.forEach { if (it !in unsaved) unsaved.add(it) }
        }
    }

    /** Ушли с экрана: досылаем последнее число, не дожидаясь таймера. */
    fun onLeave() {
        autosave?.cancel()
        val ids = unsaved.toList()
        if (ids.isEmpty()) return
        unsaved.clear()
        store.flushOnLeave(act.actId, ids.mapNotNull { id -> counts[id]?.let { id to it } }.toMap())
    }

    suspend fun save() {
        isSaving = true
        autosave?.cancel()
        val queued = unsaved.toList()
        unsaved.clear()
        try {
            // Очередь берёт на себя только обрыв связи — об этом и говорим прямо:
            // обещать «сохранено», когда данные лежат на телефоне, нельзя.
            val saved = store.saveCounts(act.actId, counts.toMap())
            savedMessage = if (saved != null) "Сохранено $saved" else "Связи нет — уйдёт само"
        } catch (e: ApiError) {
            queued.forEach { if (it !in unsaved) unsaved.add(it) }
            showToast(e.userMessage, true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            queued.forEach { if (it !in unsaved) unsaved.add(it) }
            showToast(e.userMessage(), true)
        } finally {
            isSaving = false
        }
    }

    /** Показать плашку; гаснет сама (см. LaunchedEffect на экране). */
    fun showToast(text: String, isError: Boolean) {
        toast = text
        toastIsError = isError
    }

    fun clearToast() {
        toast = null
    }
}
