package kz.ordaops.android.feature.operator.work

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kz.ordaops.android.core.net.ApiJson
import java.io.File
import java.util.UUID

/**
 * Чек-листы, пройденные без связи — ChecklistOutbox.swift.
 *
 * Обход точки — двадцать минут работы, и проходят его там, где связи нет:
 * подсобка, склад. Поэтому пройденный чек-лист кладём на диск целиком (шаблон,
 * ответы), а когда сеть вернётся, проигрываем на сервере: запуск, ответы,
 * завершение. На диске, а не в памяти: приложение выгружают, а работа уже сделана.
 */
class ChecklistOutbox(context: Context) {

    @Serializable
    data class Item(
        val id: String = UUID.randomUUID().toString(),
        /** Чьё это: телефон на точке общий, работа сменщика не должна уйти под чужим именем. */
        val owner: String? = null,
        val templateId: String,
        /** Название — для строки «ждёт связи»: показывать идентификатор человеку нечестно. */
        val title: String,
        val answers: List<ChecklistAnswer>,
        val passedAt: Long = System.currentTimeMillis(),
    )

    private val file = File(context.filesDir, "orda-checklist-outbox.json")
    private val mutex = Mutex()
    private var items: List<Item> = emptyList()
    private var loaded = false
    private var owner: String? = null

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        items = runCatching { ApiJson.decodeFromString(ListSerializer(Item.serializer()), file.readText()) }
            .getOrDefault(emptyList())
    }

    private fun persist() {
        runCatching { file.writeText(ApiJson.encodeToString(ListSerializer(Item.serializer()), items)) }
    }

    /** Кто вошёл. Чужое остаётся ждать своего хозяина. */
    suspend fun setOwner(owner: String?) = mutex.withLock { this.owner = owner }

    suspend fun add(item: Item) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadIfNeeded()
            val stamped = item.copy(owner = item.owner ?: owner)
            // Один шаблон — одна запись, но только у одного человека: сменщик мог пройти тот же чек-лист.
            items = items.filterNot { it.templateId == stamped.templateId && it.owner == stamped.owner } + stamped
            persist()
        }
    }

    /** Только своё: чужой чек-лист уйдёт, когда его хозяин снова войдёт. */
    suspend fun pending(): List<Item> = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadIfNeeded()
            items.filter { it.owner == null || it.owner == owner }
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadIfNeeded()
            items = items.filterNot { it.id == id }
            persist()
        }
    }
}
