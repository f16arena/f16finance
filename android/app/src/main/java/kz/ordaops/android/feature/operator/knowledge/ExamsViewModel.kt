package kz.ordaops.android.feature.operator.knowledge

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load
import kz.ordaops.android.feature.operator.common.OperatorEnv

/** Отправка ответа на вопрос: идёт ли, чем кончилась. */
@Immutable
data class ExamSend(val isSending: Boolean = false, val error: String? = null)

/**
 * Экзамены оператора. Попытка живёт здесь, а не в `remember`: на повороте
 * экзамен не перезапускается и не мигает загрузкой.
 */
class ExamsViewModel(private val env: OperatorEnv) : ViewModel() {
    private val api get() = env.api
    private val listRequest = ApiRequest("/api/operator/exams")

    private val _list = MutableStateFlow(Loadable<List<OperatorExam>>())
    val list: StateFlow<Loadable<List<OperatorExam>>> = _list.asStateFlow()

    private val _attempt = MutableStateFlow(Loadable<OperatorExam>())
    val attempt: StateFlow<Loadable<OperatorExam>> = _attempt.asStateFlow()

    private val _send = MutableStateFlow(ExamSend())
    val send: StateFlow<ExamSend> = _send.asStateFlow()

    private var attemptId: String? = null
    private var listJob: Job? = null
    private var attemptJob: Job? = null

    fun refresh() {
        if (listJob?.isActive == true) return
        listJob = viewModelScope.launch {
            _list.load(
                cached = { api.cached<ExamListResponse>(listRequest)?.data },
                fresh = { api.send<ExamListResponse>(listRequest).data },
            )
        }
    }

    /** Открыть экзамен. Тот же, что уже открыт, не сбрасываем — так поворот не теряет вопрос. */
    fun openExam(id: String) {
        if (attemptId != id) {
            attemptId = id
            attemptJob?.cancel()
            _attempt.value = Loadable()
            _send.value = ExamSend()
        }
        if (_attempt.value.hasData || attemptJob?.isActive == true) return
        loadAttempt(id)
    }

    fun retry() {
        attemptId?.let { loadAttempt(it) }
    }

    /** Вышли из экзамена: следующий вход начнёт с чистого листа, а не со старого вопроса. */
    fun closeExam() {
        attemptId = null
        attemptJob?.cancel()
        _attempt.value = Loadable()
        _send.value = ExamSend()
    }

    private fun loadAttempt(id: String) {
        attemptJob = viewModelScope.launch {
            _attempt.load(
                fresh = {
                    api.send<ExamAttemptResponse>(ApiRequest("/api/operator/exams/$id")).data
                        ?: throw IllegalStateException("Экзамен не найден")
                },
            )
        }
    }

    /** Ответ на текущий вопрос; в ответе сразу следующее состояние, второй запрос не нужен. */
    fun answer(index: Int, choice: Int? = null, text: String? = null) {
        val id = attemptId ?: return
        if (_send.value.isSending) return
        viewModelScope.launch {
            _send.value = ExamSend(isSending = true)
            try {
                val body = buildJsonObject {
                    put("index", index)
                    if (choice != null) put("choice", choice)
                    if (text != null) put("text", text)
                }
                val next = api.send<ExamAttemptResponse>(ApiRequest.json("/api/operator/exams/$id", body)).data
                if (next != null && attemptId == id) _attempt.update { Loadable(data = next) }
                _send.value = ExamSend()
            } catch (e: Exception) {
                _send.value = ExamSend(error = e.userMessage())
            }
        }
    }
}
