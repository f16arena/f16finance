package kz.ordaops.android.feature.operator.work

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiError
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.operator.common.OperatorEnv

/** Связи нет — то же сообщение, что и на айфоне. */
const val OFFLINE_NOTICE = "Связи нет — сохранено на устройстве и уйдёт само, как появится сеть."

/**
 * Текст отказа в операторских сценариях. Сервер отвечает кодами вроде
 * `point-shift-no-open` — кассиру их показывать нельзя, переводим в объяснение.
 */
fun Throwable.operatorMessage(): String {
    if (this is ApiError) {
        when (this) {
            is ApiError.Conflict -> when (code) {
                "point-shift-no-open" -> return "Сначала откройте смену — без неё продавать нельзя."
                "point-shift-already-open" -> return "На точке уже открыта смена."
                "point-shift-required-checklists-missing" -> return "Перед закрытием завершите обязательные чек-листы."
                "act-not-open" -> return "Акт ревизии уже закрыт."
                else -> {}
            }
            is ApiError.BadRequest -> if (code == "opening-cash-required") {
                return "Перед открытием смены укажите, сколько денег в кассе."
            }
            else -> {}
        }
    }
    return userMessage()
}

/**
 * Хранилище задач, чек-листов и знаний — часть CabinetStore.swift.
 * Состояние в `mutableStateOf`: экраны перерисовываются сами.
 */
class CabinetStore(private val env: OperatorEnv, context: Context) {
    private val api get() = env.api
    private val checklistOutbox = ChecklistOutbox(context.applicationContext)

    var tasks by mutableStateOf<List<OperatorTask>>(emptyList())
        private set
    var isLoadingTasks by mutableStateOf(false)
        private set

    var knowledge by mutableStateOf<KnowledgeCenter?>(null)
        private set
    var isLoadingKnowledge by mutableStateOf(false)
        private set

    var shift by mutableStateOf<ShiftHead?>(null)
        private set

    /** Пройденные, но не ушедшие чек-листы — строка «ждёт связи». */
    var undelivered by mutableStateOf<List<ChecklistOutbox.Item>>(emptyList())
        private set

    /** Последняя ошибка загрузки: старые данные остаются, а причина доходит до экрана. */
    var error by mutableStateOf<String?>(null)

    /** Действие легло в очередь без связи — экран обязан сказать об этом словами. */
    var deferredNotice by mutableStateOf<String?>(null)

    val activeTasks: List<OperatorTask> get() = tasks.filter { !it.isDone }

    private val tasksRequest = ApiRequest("/api/operator/tasks")
    private val knowledgeRequest = ApiRequest("/api/operator/knowledge")
    private val shiftRequest = ApiRequest("/api/operator/shift/current")

    suspend fun prepareOutbox() {
        checklistOutbox.setOwner(env.operatorId)
        refreshUndelivered()
    }

    // ── Задачи ───────────────────────────────────────────────────────────────

    suspend fun loadCachedTasks() {
        if (tasks.isEmpty()) api.cached<OperatorTaskList>(tasksRequest)?.let { tasks = it.tasks }
    }

    suspend fun loadTasks() {
        isLoadingTasks = true
        try {
            tasks = api.send<OperatorTaskList>(tasksRequest).tasks
            error = null
        } catch (e: Exception) {
            error = e.operatorMessage()
        } finally {
            isLoadingTasks = false
        }
    }

    /**
     * Ответ по задаче. Оператор не закрывает задачу сам — «выполнено» ставит
     * руководитель. Повтор ведёт к тому же результату, поэтому ответ можно
     * отложить до сети. Возвращает текст ошибки или null.
     */
    suspend fun respondToTask(task: OperatorTask, response: TaskResponse, note: String? = null): String? = try {
        val body = buildJsonObject {
            put("action", "respondTask")
            put("taskId", task.id)
            put("response", response.raw)
            if (!note.isNullOrEmpty()) put("note", note)
        }
        val sent = env.outbox.perform(
            "/api/operator/tasks", HttpMethod.POST, body.toString(), "Ответ на задачу", "task:${task.id}",
        )
        deferredNotice = if (sent) null else OFFLINE_NOTICE
        loadTasks()
        null
    } catch (e: Exception) {
        e.operatorMessage()
    }

    // ── Знания ───────────────────────────────────────────────────────────────

    suspend fun loadCachedKnowledge() {
        if (knowledge == null) api.cached<KnowledgeCenter>(knowledgeRequest)?.let { knowledge = it }
    }

    suspend fun loadKnowledge() {
        isLoadingKnowledge = true
        try {
            knowledge = api.send<KnowledgeCenter>(knowledgeRequest)
        } catch (e: Exception) {
            error = e.operatorMessage()
        } finally {
            isLoadingKnowledge = false
        }
    }

    /** Подтвердить прочтение. Версию передаём обязательно: правка текста не должна остаться незамеченной. */
    suspend fun confirmArticle(article: KnowledgeArticle): String? = try {
        val body = buildJsonObject {
            put("article_id", article.id)
            put("version", article.version)
        }
        val sent = env.outbox.perform(
            "/api/operator/knowledge/confirm", HttpMethod.POST, body.toString(),
            "Подтверждение прочтения", "knowledge:${article.id}:${article.version}",
        )
        deferredNotice = if (sent) null else OFFLINE_NOTICE
        loadKnowledge()
        null
    } catch (e: Exception) {
        e.operatorMessage()
    }

    /**
     * «Объясни проще»: сервер пересказывает статью простыми словами и отвечает
     * строго по тексту. Успех — текст ответа, неудача — сообщение.
     */
    suspend fun explainArticle(id: String, question: String?): Result<String> = try {
        val body = buildJsonObject {
            put("article_id", id)
            if (!question.isNullOrEmpty()) put("question", question)
        }
        Result.success(api.send<ExplainResponse>(ApiRequest.json("/api/operator/knowledge/explain", body)).text)
    } catch (e: Exception) {
        Result.failure(IllegalStateException(e.operatorMessage()))
    }

    // ── Смена: чья она, нужно чек-листам ─────────────────────────────────────

    suspend fun loadShift() {
        if (shift == null) api.cached<ShiftHead>(shiftRequest)?.let { shift = it }
        runCatching { api.send<ShiftHead>(shiftRequest) }.onSuccess { shift = it }
    }

    // ── Чек-листы ────────────────────────────────────────────────────────────

    suspend fun refreshUndelivered() {
        undelivered = checklistOutbox.pending()
    }

    /**
     * Отложить пройденный чек-лист до связи. Кладём целиком: без связи нет
     * запуска, а значит и идентификатора, к которому можно привязать ответы.
     */
    suspend fun deferChecklist(template: ChecklistTemplate, answers: List<ChecklistAnswer>) {
        checklistOutbox.add(ChecklistOutbox.Item(templateId = template.id, title = template.title, answers = answers))
        refreshUndelivered()
        deferredNotice = "Чек-лист сохранён на устройстве и уйдёт при связи."
    }

    /**
     * Проиграть отложенное: запуск → ответы → завершение. Порядок обязателен —
     * запуск даёт идентификатор. Сорвалось на середине — запись остаётся:
     * повторный запуск того же шаблона сервер отдаёт тем же запуском смены.
     */
    suspend fun flushChecklists(): Int {
        val queue = checklistOutbox.pending()
        if (queue.isEmpty()) return 0
        var sent = 0
        for (item in queue) {
            try {
                val started = startRun(item.templateId)
                saveRun(started.runId, item.answers, deferrable = false)
                completeRun(started.runId)
                checklistOutbox.remove(item.id)
                sent++
            } catch (e: Exception) {
                // Связь снова пропала — остальное подождёт своей очереди.
                break
            }
        }
        refreshUndelivered()
        if (sent > 0) loadKnowledge()
        return sent
    }

    /** Запуск чек-листа в текущей смене. Без открытой смены сервер ответит 409. */
    private suspend fun startRun(templateId: String): ChecklistRunStart =
        api.send(ApiRequest.json("/api/operator/checklist/run", buildJsonObject { put("template_id", templateId) }))

    private suspend fun saveRun(runId: String, answers: List<ChecklistAnswer>, deferrable: Boolean = true) {
        // Ответы шлём целиком, повтор перезаписывает их теми же значениями —
        // поэтому в очереди одна запись на запуск, новая заменяет прежнюю.
        val body = JsonObject(mapOf("answers" to JsonArray(answers.map { it.payload() })))
        // При проигрывании очереди откладывать нельзя: завершение без ответов запишет пустой чек-лист.
        if (!deferrable) {
            api.sendUnit(ApiRequest.json("/api/operator/checklist/run/$runId", body, HttpMethod.PATCH))
            return
        }
        env.outbox.perform(
            "/api/operator/checklist/run/$runId", HttpMethod.PATCH, body.toString(), "Ответы чек-листа", "checklist:$runId",
        ).let { sent -> if (!sent) deferredNotice = OFFLINE_NOTICE }
    }

    private suspend fun completeRun(runId: String): ChecklistRunResult =
        api.send(
            ApiRequest.json(
                "/api/operator/checklist/run/$runId/complete",
                buildJsonObject { put("status", "completed") },
            ),
        )

    /** Успех — id запуска. Неудача: `offline = true`, если нет связи (тогда проходят как обычно, а сдают потом). */
    suspend fun startChecklist(template: ChecklistTemplate): StartOutcome = try {
        StartOutcome.Started(startRun(template.id).runId)
    } catch (e: ApiError.Transport) {
        StartOutcome.Failed(e.operatorMessage(), offline = true)
    } catch (e: Exception) {
        StartOutcome.Failed(e.operatorMessage(), offline = false)
    }

    suspend fun saveChecklist(runId: String, answers: List<ChecklistAnswer>): String? = try {
        deferredNotice = null
        saveRun(runId, answers)
        null
    } catch (e: Exception) {
        e.operatorMessage()
    }

    suspend fun completeChecklist(runId: String): Result<ChecklistRunResult> = try {
        val result = completeRun(runId)
        loadKnowledge()
        Result.success(result)
    } catch (e: Exception) {
        Result.failure(IllegalStateException(e.operatorMessage()))
    }

    sealed class StartOutcome {
        data class Started(val runId: String) : StartOutcome()
        data class Failed(val message: String, val offline: Boolean) : StartOutcome()
    }
}
