package kz.ordaops.android.feature.operator.work

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiError
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load
import kz.ordaops.android.feature.operator.common.OperatorEnv

// ── Задачи ───────────────────────────────────────────────────────────────────

/** Задачи, уже разложенные для списка: делим один раз, а не на каждой перерисовке. */
@Immutable
data class TasksUi(val active: List<OperatorTask>, val done: List<OperatorTask>) {
    val isEmpty: Boolean get() = active.isEmpty() && done.isEmpty()

    companion object {
        fun of(list: OperatorTaskList) = TasksUi(
            active = list.tasks.filter { !it.isDone },
            done = list.tasks.filter { it.isDone },
        )
    }
}

/**
 * Состояние отправки ответа по задаче. `completed` растёт с каждым успехом —
 * экран по нему закрывает диалог и даёт вибро-отклик, не храня «событий».
 */
@Immutable
data class TaskAction(val sending: Boolean = false, val error: String? = null, val completed: Int = 0)

/** Задачи оператора. Логика и эндпоинты — прежние (бывший CabinetStore). */
class TasksViewModel(private val env: OperatorEnv) : ViewModel() {
    private val api get() = env.api
    private val request = ApiRequest("/api/operator/tasks")

    private val _state = MutableStateFlow(Loadable<TasksUi>())
    val state: StateFlow<Loadable<TasksUi>> = _state.asStateFlow()

    /** Ответ ушёл в очередь без связи — экран обязан сказать об этом словами. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _action = MutableStateFlow(TaskAction())
    val action: StateFlow<TaskAction> = _action.asStateFlow()

    private var inFlight = 0
    private var loadJob: Job? = null

    fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch { loadTasks() }
    }

    private suspend fun loadTasks() {
        _state.load(
            cached = { api.cached<OperatorTaskList>(request)?.let(TasksUi::of) },
            fresh = { TasksUi.of(api.send<OperatorTaskList>(request)) },
        )
    }

    fun clearActionError() = _action.update { it.copy(error = null) }

    /**
     * Ответ по задаче. Оператор не закрывает задачу сам — «выполнено» ставит
     * руководитель. Повтор ведёт к тому же результату, поэтому ответ можно
     * отложить до сети.
     */
    fun respond(task: OperatorTask, response: TaskResponse, note: String? = null) {
        viewModelScope.launch {
            inFlight++
            _action.update { it.copy(sending = true, error = null) }
            val failure = try {
                val body = buildJsonObject {
                    put("action", "respondTask")
                    put("taskId", task.id)
                    put("response", response.raw)
                    if (!note.isNullOrEmpty()) put("note", note)
                }
                val sent = env.outbox.perform(
                    "/api/operator/tasks", HttpMethod.POST, body.toString(), "Ответ на задачу", "task:${task.id}",
                )
                _notice.value = if (sent) null else OFFLINE_NOTICE
                null
            } catch (e: Exception) {
                e.operatorMessage()
            }
            inFlight--
            _action.update {
                if (failure == null) it.copy(sending = inFlight > 0, completed = it.completed + 1)
                else it.copy(sending = inFlight > 0, error = failure)
            }
            if (failure == null) loadTasks()
        }
    }
}

// ── Чек-листы ────────────────────────────────────────────────────────────────

/** Прохождение одного чек-листа. Живёт во ViewModel: поворот не должен сбрасывать ответы. */
@Immutable
data class ChecklistRunUi(
    val template: ChecklistTemplate,
    val runId: String? = null,
    val answers: Map<String, ChecklistAnswer> = emptyMap(),
    val isStarting: Boolean = true,
    val isSubmitting: Boolean = false,
    /** Начали без связи: ответы уйдут одной пачкой позже. */
    val isOffline: Boolean = false,
    /** Чек-лист сложен в очередь — показываем это вместо начислений. */
    val isDeferred: Boolean = false,
    val error: String? = null,
    val result: ChecklistRunResult? = null,
)

class ChecklistsViewModel(private val env: OperatorEnv, context: Context) : ViewModel() {
    private val api get() = env.api
    private val outbox = ChecklistOutbox(context.applicationContext)
    private val knowledgeRequest = ApiRequest("/api/operator/knowledge")
    private val shiftRequest = ApiRequest("/api/operator/shift/current")

    private val _knowledge = MutableStateFlow(Loadable<KnowledgeCenter>())
    val knowledge: StateFlow<Loadable<KnowledgeCenter>> = _knowledge.asStateFlow()

    private val _shift = MutableStateFlow<ShiftHead?>(null)
    val shift: StateFlow<ShiftHead?> = _shift.asStateFlow()

    /** Пройденные, но не ушедшие чек-листы — строка «ждёт связи». */
    private val _undelivered = MutableStateFlow<List<ChecklistOutbox.Item>>(emptyList())
    val undelivered: StateFlow<List<ChecklistOutbox.Item>> = _undelivered.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _run = MutableStateFlow<ChecklistRunUi?>(null)
    val run: StateFlow<ChecklistRunUi?> = _run.asStateFlow()

    private var openJob: Job? = null

    /** Экран открыли — самое время дослать: связь могла вернуться, пока человек ходил по точке. */
    fun onOpen() {
        if (openJob?.isActive == true) return
        openJob = viewModelScope.launch {
            outbox.setOwner(env.operatorId)
            refreshUndelivered()
            flush()
            loadKnowledge()
            loadShift()
        }
    }

    fun refresh() {
        if (openJob?.isActive == true) return
        openJob = viewModelScope.launch {
            loadKnowledge()
            loadShift()
        }
    }

    fun flushNow() {
        viewModelScope.launch { flush() }
    }

    private suspend fun loadKnowledge() {
        _knowledge.load(
            cached = { api.cached<KnowledgeCenter>(knowledgeRequest) },
            fresh = { api.send<KnowledgeCenter>(knowledgeRequest) },
        )
    }

    private suspend fun loadShift() {
        if (_shift.value == null) api.cached<ShiftHead>(shiftRequest)?.let { _shift.value = it }
        runCatching { api.send<ShiftHead>(shiftRequest) }.onSuccess { _shift.value = it }
    }

    private suspend fun refreshUndelivered() {
        _undelivered.value = outbox.pending()
    }

    /**
     * Проиграть отложенное: запуск → ответы → завершение. Порядок обязателен —
     * запуск даёт идентификатор. Сорвалось на середине — запись остаётся:
     * повторный запуск того же шаблона сервер отдаёт тем же запуском смены.
     */
    private suspend fun flush(): Int {
        val queue = outbox.pending()
        if (queue.isEmpty()) return 0
        var sent = 0
        for (item in queue) {
            try {
                val started = startRun(item.templateId)
                saveRun(started.runId, item.answers, deferrable = false)
                completeRun(started.runId)
                outbox.remove(item.id)
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
        ).let { sent -> if (!sent) _notice.value = OFFLINE_NOTICE }
    }

    private suspend fun completeRun(runId: String): ChecklistRunResult =
        api.send(
            ApiRequest.json(
                "/api/operator/checklist/run/$runId/complete",
                buildJsonObject { put("status", "completed") },
            ),
        )

    // ── Прохождение ──────────────────────────────────────────────────────────

    /** Запуск чек-листа в текущей смене. Без открытой смены сервер ответит 409. */
    fun startChecklist(template: ChecklistTemplate) {
        if (_run.value?.template?.id == template.id) return
        _run.value = ChecklistRunUi(template)
        viewModelScope.launch {
            try {
                val id = startRun(template.id).runId
                _run.update { it?.copy(runId = id, isStarting = false) }
            } catch (e: ApiError.Transport) {
                // Без связи чек-лист всё равно проходят: обход делают там, где сети нет.
                _run.update { it?.copy(isOffline = true, isStarting = false) }
            } catch (e: Exception) {
                _run.update { it?.copy(error = e.operatorMessage(), isStarting = false) }
            }
        }
    }

    fun closeRun() {
        _run.value = null
    }

    fun answer(answer: ChecklistAnswer) {
        _run.update { it?.copy(answers = it.answers + (answer.itemId to answer)) }
    }

    fun submit() {
        val current = _run.value ?: return
        if (current.isSubmitting) return
        _run.update { it?.copy(isSubmitting = true, error = null) }
        val payload = current.answers.values.toList()
        val id = current.runId
        viewModelScope.launch {
            // Запуска нет — значит начинали без связи. Складываем целиком: идентификатора,
            // к которому можно привязать ответы, нет.
            if (id == null) {
                outbox.add(ChecklistOutbox.Item(templateId = current.template.id, title = current.template.title, answers = payload))
                refreshUndelivered()
                _notice.value = "Чек-лист сохранён на устройстве и уйдёт при связи."
                _run.update { it?.copy(isSubmitting = false, isDeferred = true) }
                return@launch
            }
            try {
                _notice.value = null
                saveRun(id, payload)
                val result = completeRun(id)
                loadKnowledge()
                _run.update { it?.copy(isSubmitting = false, result = result) }
            } catch (e: Exception) {
                _run.update { it?.copy(isSubmitting = false, error = e.operatorMessage()) }
            }
        }
    }
}
