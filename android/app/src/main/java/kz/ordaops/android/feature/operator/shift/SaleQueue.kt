package kz.ordaops.android.feature.operator.shift

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiError
import kz.ordaops.android.core.net.ApiJson
import kz.ordaops.android.core.net.ApiRequest
import java.io.File

/**
 * Очередь неотправленных продаж — SaleQueue.swift.
 *
 * Точка может оказаться в подвале без связи, а очередь у кассы ждать не будет.
 * Чек пишется на устройство сразу и уходит, как только появится сеть.
 * Безопасность повторов держится на `local_ref`: сервер дедуплицирует по нему,
 * поэтому отправлять можно смело, а удалять из очереди — только после ответа.
 */
class SaleQueue(context: Context, private val api: ApiClient) {

    /**
     * Чек вместе с тем, кто его пробил. Телефон на точке общий: чек, ушедший под
     * другим входом, попал бы в чужую смену — в чужую кассу и чужой отчёт.
     */
    @Serializable
    private data class Entry(val draft: SaleDraft, val owner: String? = null)

    sealed class SubmitOutcome {
        data class Sent(val result: SaleResult) : SubmitOutcome()
        /** Сети нет, чек в очереди — продажа состоялась. */
        data class Queued(val pendingCount: Int) : SubmitOutcome()
        /** Сервер отказал по существу. */
        data class Failed(val error: ApiError) : SubmitOutcome()
        /** Чек не прошёл проверку на устройстве. */
        data class Rejected(val issue: SaleDraft.ValidationIssue) : SubmitOutcome()
    }

    data class FlushResult(val sent: Int, val remaining: Int, val failed: Int)

    private val file = File(context.filesDir, "orda-sale-queue.json")
    private val mutex = Mutex()
    private var pending: List<Entry> = emptyList()
    private var owner: String? = null
    private var isFlushing = false

    private val _pendingCount = MutableStateFlow(0)
    /** Сколько своих чеков ждёт отправки; чужие сюда не попадают. */
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val mine: List<Entry> get() = pending.filter { it.owner == null || it.owner == owner }

    suspend fun setOwner(owner: String?) = mutex.withLock {
        this.owner = owner
        _pendingCount.value = mine.size
    }

    /** Загрузить очередь с диска при старте. */
    suspend fun load() = mutex.withLock {
        pending = withContext(Dispatchers.IO) {
            runCatching { ApiJson.decodeFromString(ListSerializer(Entry.serializer()), file.readText()) }.getOrDefault(emptyList())
        }.sortedBy { it.draft.createdAt }
        _pendingCount.value = mine.size
    }

    private suspend fun persist() {
        val snapshot = pending
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(ApiJson.encodeToString(ListSerializer(Entry.serializer()), snapshot)) }
        }
        _pendingCount.value = mine.size
    }

    private suspend fun createSale(draft: SaleDraft): SaleResult {
        val response = api.send<SaleResultResponse>(ApiRequest.json("/api/operator/inventory-sales", draft.requestBody()))
        val d = response.data ?: SaleResultData()
        return SaleResult(d.saleId, d.totalAmount, d.receiptUrl, d.loyaltyPointsEarned, d.idempotent, d.serviceSessions)
    }

    /**
     * Провести продажу: сначала пробуем сразу — в норме сеть есть, и кассир
     * должен получить чек немедленно. Не вышло по сетевой причине — чек уходит в
     * очередь и продажа считается состоявшейся: товар уже отдан.
     */
    suspend fun submit(draft: SaleDraft): SubmitOutcome {
        draft.validate()?.let { return SubmitOutcome.Rejected(it) }
        return try {
            SubmitOutcome.Sent(createSale(draft))
        } catch (e: ApiError) {
            // Отказ по существу (нет смены, нет товара) в очередь не кладём:
            // повтор даст тот же отказ, а кассир будет думать, что продажа прошла.
            if (e.isRetryable) SubmitOutcome.Queued(enqueue(draft)) else SubmitOutcome.Failed(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SubmitOutcome.Queued(enqueue(draft))
        }
    }

    private suspend fun enqueue(draft: SaleDraft): Int = mutex.withLock {
        if (pending.none { it.draft.localRef == draft.localRef }) {
            pending = pending + Entry(draft, owner)
            persist()
        }
        mine.size
    }

    /** Отправить всё накопленное — при появлении сети и при открытии экрана. */
    suspend fun flush(): FlushResult {
        val toSend = mutex.withLock {
            if (isFlushing || mine.isEmpty()) return FlushResult(0, mine.size, 0)
            isFlushing = true
            pending
        }
        var sent = 0
        var failed = 0
        val done = mutableSetOf<String>()
        try {
            for (entry in toSend) {
                // Чужой чек ждёт своего кассира.
                if (entry.owner != null && entry.owner != owner) continue
                try {
                    createSale(entry.draft)
                    sent++
                    done += entry.draft.localRef
                } catch (e: ApiError) {
                    // Сеть снова пропала — остаток на следующий раз. Отказ по
                    // существу держать вечно нельзя: убираем и сообщаем.
                    if (!e.isRetryable) {
                        failed++
                        done += entry.draft.localRef
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Сеть — оставляем.
                }
            }
        } finally {
            mutex.withLock {
                pending = pending.filterNot { it.draft.localRef in done }
                isFlushing = false
                persist()
            }
        }
        return FlushResult(sent, mine.size, failed)
    }

    /** Убрать чек из очереди вручную (после разбора). */
    suspend fun drop(localRef: String) = mutex.withLock {
        pending = pending.filterNot { it.draft.localRef == localRef }
        persist()
    }
}
