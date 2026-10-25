package kz.ordaops.android.feature.operator.common

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
import kz.ordaops.android.core.net.HttpMethod
import kz.ordaops.android.core.net.JsonMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID

/**
 * Очередь действий, которые не дошли до сервера из-за связи — как
 * ActionOutbox.swift. Одна на все разделы оператора: файл общий.
 *
 * Отказ по существу (400/403/409) в очередь не кладём — повтор даст тот же
 * отказ, разбираться должен человек.
 */
class ActionOutbox(context: Context, private val api: ApiClient) {

    @Serializable
    data class Item(
        val id: String = UUID.randomUUID().toString(),
        val createdAt: Long = System.currentTimeMillis(),
        val path: String,
        val method: String,
        /** JSON-тело как строка. */
        val body: String? = null,
        /** Человеческое название — для экрана очереди. */
        val title: String,
        /** Повторное действие над тем же объектом заменяет прежнее. */
        val mergeKey: String,
        /** Кто сделал. На общем телефоне чужое не отправляем под своим именем. */
        val owner: String? = null,
    )

    data class FlushResult(val sent: Int, val remaining: Int, val failed: Int)

    // filesDir — обращение к диску; первым к файлу идёт load() уже в фоновом потоке.
    private val file by lazy { File(context.filesDir, "orda-action-outbox.json") }
    private val mutex = Mutex()
    private var pending: List<Item> = emptyList()
    private var owner: String? = null
    private var flushing = false

    private val _pendingCount = MutableStateFlow(0)
    /** Сколько своих действий ждёт отправки — для бейджей и баннера. */
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val mine: List<Item> get() = pending.filter { it.owner == null || it.owner == owner }

    val pendingItems: List<Item> get() = mine

    suspend fun setOwner(owner: String?) = mutex.withLock {
        this.owner = owner
        _pendingCount.value = mine.size
    }

    suspend fun load() = mutex.withLock {
        pending = withContext(Dispatchers.IO) {
            runCatching { ApiJson.decodeFromString(ListSerializer(Item.serializer()), file.readText()) }.getOrDefault(emptyList())
        }.sortedBy { it.createdAt }
        _pendingCount.value = mine.size
    }

    private suspend fun persist() {
        val snapshot = pending
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(ApiJson.encodeToString(ListSerializer(Item.serializer()), snapshot)) }
        }
        _pendingCount.value = mine.size
    }

    /**
     * Отправить сейчас; нет связи — положить в очередь и вернуть `false`.
     * Отказ сервера по существу пробрасывается.
     */
    suspend fun perform(item: Item): Boolean = try {
        api.sendUnit(request(item))
        true
    } catch (e: ApiError) {
        if (!e.isRetryable) throw e
        enqueue(item)
        false
    } catch (e: Exception) {
        enqueue(item)
        false
    }

    /** Удобная обёртка для вызывающих: JSON-строка тела и путь. */
    suspend fun perform(path: String, method: HttpMethod, body: String?, title: String, mergeKey: String): Boolean =
        perform(Item(path = path, method = method.name, body = body, title = title, mergeKey = mergeKey))

    private suspend fun enqueue(item: Item) = mutex.withLock {
        // Хозяин ставится здесь: забыть его в одном из мест — записать работу на чужого.
        val stamped = item.copy(owner = item.owner ?: owner)
        // Склейка — только со своими: у сменщика может ждать своё действие над тем же объектом.
        pending = pending.filterNot { it.mergeKey == stamped.mergeKey && it.owner == stamped.owner } + stamped
        persist()
    }

    suspend fun flush(): FlushResult {
        val toSend = mutex.withLock {
            if (flushing || mine.isEmpty()) return FlushResult(0, mine.size, 0)
            flushing = true
            pending
        }
        var sent = 0
        var failed = 0
        val sentIds = mutableSetOf<String>()
        try {
            for (item in toSend) {
                // Чужое ждёт своего хозяина.
                if (item.owner != null && item.owner != owner) continue
                try {
                    api.sendUnit(request(item))
                    sent++
                    sentIds += item.id
                } catch (e: ApiError) {
                    if (!e.isRetryable) {
                        failed++
                        sentIds += item.id
                    }
                } catch (e: Exception) {
                    // Сеть — оставляем.
                }
            }
        } finally {
            mutex.withLock {
                pending = pending.filterNot { it.id in sentIds }
                flushing = false
                persist()
            }
        }
        return FlushResult(sent, mine.size, failed)
    }

    suspend fun drop(id: String) = mutex.withLock {
        pending = pending.filterNot { it.id == id }
        persist()
    }

    private fun request(item: Item) = ApiRequest(
        path = item.path,
        method = runCatching { HttpMethod.valueOf(item.method.uppercase()) }.getOrDefault(HttpMethod.POST),
        body = item.body?.toRequestBody(JsonMediaType),
    )
}
