package kz.ordaops.android.feature.operator.money

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import java.time.LocalDate

/**
 * Состояние денег, графика и старшего смены — урезанный `CabinetStore.swift`.
 * Обычный класс с Compose-состоянием: создаётся через `remember`, жизнь —
 * пока открыт экран.
 */
class MoneyStore(private val api: ApiClient) {
    var overview by mutableStateOf<MoneyOverview?>(null)
        private set
    var salary by mutableStateOf<OperatorSalary?>(null)
        private set
    var incidents by mutableStateOf<List<OperatorIncident>>(emptyList())
        private set
    var schedule by mutableStateOf<OperatorSchedule?>(null)
        private set
    /** `null` — человек не старший (или ещё не спрашивали). */
    var leadDesk by mutableStateOf<LeadDesk?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Какую неделю зарплаты смотрим: понедельник, «2026-08-17». */
    var salaryWeek by mutableStateOf(currentWeekStart())
        private set
    /** Какую неделю графика смотрим. */
    var scheduleWeek by mutableStateOf(currentWeekStart())
        private set

    val isLead: Boolean get() = leadDesk != null
    val leadPendingCount: Int get() = leadDesk?.awaitingProposal?.size ?: 0

    /** Старшинство не меняется по ходу дня — спрашиваем один раз. */
    private var didProbeLead = false

    /**
     * Загрузка, которая не должна ронять экран, но и не должна молчать: старые
     * данные при отказе остаются, а причина доходит до экрана.
     */
    private suspend fun <T> attempt(work: suspend () -> T): T? = try {
        work().also { error = null }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = e.operatorText()
        null
    }

    // ── Загрузка ─────────────────────────────────────────────────────────────

    suspend fun loadOverview() {
        val request = ApiRequest("/api/operator/overview")
        // Экран открывается на вчерашних цифрах и тут же обновляется.
        if (overview == null) api.cached<MoneyOverview>(request)?.let { overview = it }
        attempt { api.send<MoneyOverview>(request) }?.let { overview = it }
        if (!didProbeLead) {
            didProbeLead = true
            loadLeadDesk()
        }
    }

    private fun salaryRequest() = ApiRequest("/api/operator/salary", query = mapOf("weekStart" to salaryWeek))

    suspend fun loadSalary() {
        val request = salaryRequest()
        if (salary == null) api.cached<OperatorSalary>(request)?.let { salary = it }
        attempt { api.send<OperatorSalary>(request) }?.let { salary = it }
    }

    suspend fun loadIncidents() {
        attempt { api.send<OperatorIncidentList>(ApiRequest("/api/operator/incidents")).items }?.let { incidents = it }
    }

    /** Сдвинуть неделю зарплаты. Вперёд дальше текущей не пускаем: там ещё не работали. */
    fun shiftSalaryWeek(by: Int) {
        val current = parseDate(salaryWeek) ?: return
        val next = currentWeekStart(current.plusWeeks(by.toLong()))
        if (next > currentWeekStart() || next == salaryWeek) return
        salaryWeek = next
    }

    private fun scheduleRequest() = ApiRequest("/api/operator/shifts", query = mapOf("weekStart" to scheduleWeek))

    suspend fun loadSchedule() {
        val request = scheduleRequest()
        if (schedule == null) api.cached<OperatorSchedule>(request)?.let { schedule = it }
        attempt { api.send<OperatorSchedule>(request) }?.let { schedule = it }
    }

    /** Шаг по неделям графика. Вперёд ходить можно: график публикуют заранее. */
    fun shiftScheduleWeek(by: Int) {
        val current = parseDate(scheduleWeek) ?: return
        scheduleWeek = currentWeekStart(current.plusWeeks(by.toLong()))
    }

    /** Молча: отказ здесь означает «не старший», и жаловаться не на что. */
    suspend fun loadLeadDesk() {
        try {
            leadDesk = api.send<LeadDesk>(ApiRequest("/api/operator/lead"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Не старший — раздела нет; прежнее значение (если было) оставляем.
        }
    }

    // ── Действия: текст ошибки или null, если всё прошло ─────────────────────

    private suspend fun act(work: suspend () -> Unit): String? = try {
        work()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.operatorText()
    }

    /** Подтвердить, что видел график недели и выходишь. */
    suspend fun confirmScheduleWeek(responseId: String): String? = act {
        api.sendUnit(
            ApiRequest.json(
                "/api/operator/shifts",
                buildJsonObject {
                    put("action", "confirmWeek")
                    put("responseId", responseId)
                },
            ),
        )
        loadSchedule()
    }

    /** Сказать, что на смену выйти не получится: заявка уходит руководителю. */
    suspend fun reportShiftIssue(responseId: String, shiftDate: String, shiftType: String, reason: String): String? = act {
        api.sendUnit(
            ApiRequest.json(
                "/api/operator/shifts",
                buildJsonObject {
                    put("action", "reportIssue")
                    put("responseId", responseId)
                    put("shiftDate", shiftDate)
                    put("shiftType", shiftType)
                    put("reason", reason)
                },
            ),
        )
        loadSchedule()
    }

    /** Предложение старшего по заявке: оставить, снять или заменить. Последнее слово — за руководителем. */
    suspend fun submitLeadProposal(requestId: String, action: String, note: String?, replacementOperatorId: String?): String? = act {
        api.sendUnit(
            ApiRequest.json(
                "/api/operator/lead",
                buildJsonObject {
                    put("action", "submitLeadProposal")
                    put("requestId", requestId)
                    put("proposalAction", action)
                    if (!note.isNullOrEmpty()) put("proposalNote", note)
                    if (!replacementOperatorId.isNullOrEmpty()) put("replacementOperatorId", replacementOperatorId)
                },
            ),
        )
        loadLeadDesk()
    }

    companion object {
        fun today(): LocalDate = LocalDate.now()
    }
}
