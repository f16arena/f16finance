package kz.ordaops.android.feature.operator.money

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load
import java.time.YearMonth

/**
 * Деньги, график, старший смены и «Как я работаю» — один ViewModel на пакет
 * (урезанный `CabinetStore.swift`). Живёт, пока жива Activity: выбранная неделя,
 * месяц, черновики причин и предложений переживают поворот.
 *
 * Эндпоинты прежние, поменялось только место жизни состояния. Показанное не
 * сбрасывается: смена недели оставляет прошлую до прихода новой (см. [Loadable]).
 */
class MoneyViewModel(private val api: ApiClient) : ViewModel() {
    private val _overview = MutableStateFlow(Loadable<MoneyOverview>())
    private val _salary = MutableStateFlow(Loadable<OperatorSalary>())
    private val _incidents = MutableStateFlow(Loadable<List<OperatorIncident>>())
    private val _schedule = MutableStateFlow(Loadable<OperatorSchedule>())
    private val _lead = MutableStateFlow(Loadable<LeadDesk>())
    private val _sales = MutableStateFlow(Loadable<SalesQualityMonth>())

    /** Какую неделю зарплаты смотрим: понедельник, «2026-08-17». */
    private val _salaryWeek = MutableStateFlow(currentWeekStart())
    val salaryWeek: StateFlow<String> = _salaryWeek.asStateFlow()

    /** Какую неделю графика смотрим. */
    private val _scheduleWeek = MutableStateFlow(currentWeekStart())
    val scheduleWeek: StateFlow<String> = _scheduleWeek.asStateFlow()

    /** Месяц «Как я работаю», «2026-08». */
    private val _salesMonth = MutableStateFlow(YearMonth.now().toString())
    val salesMonth: StateFlow<String> = _salesMonth.asStateFlow()

    internal val overview: StateFlow<Loadable<MoneyOverview>> = _overview.asStateFlow()
    internal val sales: StateFlow<Loadable<SalesQualityMonth>> = _sales.asStateFlow()
    internal val lead: StateFlow<Loadable<LeadDesk>> = _lead.asStateFlow()

    /** Экран денег одним потоком; форматирование и группировки — вне главного потока. */
    internal val money: StateFlow<Loadable<MoneyContent>> =
        combine(_salary, _overview, _incidents, _salaryWeek) { salary, overview, incidents, week ->
            val shown = salary.data?.week ?: overview.data?.week
            val data = shown?.let { buildMoneyContent(it, salary.data, overview.data, incidents.data.orEmpty(), week) }
            val loaded = salary.data != null || overview.data != null
            val busy = salary.isLoading || overview.isLoading
            Loadable(
                data = data,
                isLoading = data == null && busy,
                isRefreshing = data != null && (salary.isRefreshing || overview.isRefreshing || incidents.isRefreshing),
                error = salary.error
                    ?: overview.error?.takeIf { data == null }
                    ?: "За эту неделю расчёта пока нет".takeIf { data == null && loaded && !busy },
                isOffline = salary.isOffline || (data == null && overview.isOffline),
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, Loadable(isLoading = true))

    internal val schedule: StateFlow<Loadable<ScheduleContent>> =
        _schedule.map { it.mapData(::buildScheduleContent) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, Loadable(isLoading = true))

    /** Старший ли человек: раздел есть только у старших. */
    val isLead: StateFlow<Boolean> = _lead.map { it.data != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val leadPendingCount: StateFlow<Int> = _lead.map { it.data?.awaitingProposal?.size ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _issue = MutableStateFlow<IssueDraft?>(null)
    internal val issue: StateFlow<IssueDraft?> = _issue.asStateFlow()

    private val _proposal = MutableStateFlow<ProposalDraft?>(null)
    internal val proposal: StateFlow<ProposalDraft?> = _proposal.asStateFlow()

    private val _confirming = MutableStateFlow(false)
    val confirming: StateFlow<Boolean> = _confirming.asStateFlow()
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private var overviewJob: Job? = null
    private var salaryJob: Job? = null
    private var incidentsJob: Job? = null
    private var scheduleJob: Job? = null
    private var leadJob: Job? = null
    private var salesJob: Job? = null

    /** Старшинство не меняется по ходу дня — спрашиваем один раз. */
    private var didProbeLead = false

    // ── Загрузка ─────────────────────────────────────────────────────────────

    /** Каждая загрузка заменяет предыдущую той же ветки: быстрые щелчки по неделям не копят запросы. */
    private fun relaunch(old: Job?, block: suspend () -> Unit): Job = viewModelScope.launch {
        old?.cancelAndJoin()
        block()
    }

    fun refreshOverview() {
        overviewJob = relaunch(overviewJob) {
            val request = ApiRequest("/api/operator/overview")
            // Экран открывается на вчерашних цифрах и тут же обновляется.
            _overview.load(cached = { api.cached<MoneyOverview>(request) }, fresh = { api.send<MoneyOverview>(request) })
        }
        if (!didProbeLead) {
            didProbeLead = true
            refreshLead()
        }
    }

    fun refreshSalary() {
        val week = _salaryWeek.value
        salaryJob = relaunch(salaryJob) {
            val request = ApiRequest("/api/operator/salary", query = mapOf("weekStart" to week))
            _salary.load(cached = { api.cached<OperatorSalary>(request) }, fresh = { api.send<OperatorSalary>(request) })
        }
    }

    fun refreshIncidents() {
        incidentsJob = relaunch(incidentsJob) {
            _incidents.load(fresh = { api.send<OperatorIncidentList>(ApiRequest("/api/operator/incidents")).items })
        }
    }

    /** Весь экран денег: расчёт недели, штрафы и обзор с долгами. */
    fun refreshMoney() {
        refreshSalary()
        refreshIncidents()
        refreshOverview()
    }

    fun refreshSchedule() {
        val week = _scheduleWeek.value
        scheduleJob = relaunch(scheduleJob) {
            val request = ApiRequest("/api/operator/shifts", query = mapOf("weekStart" to week))
            _schedule.load(cached = { api.cached<OperatorSchedule>(request) }, fresh = { api.send<OperatorSchedule>(request) })
        }
    }

    /** Отказ здесь для не-старшего обычен: ошибка остаётся в состоянии, но раздел её не показывает, пока данных нет. */
    fun refreshLead() {
        leadJob = relaunch(leadJob) {
            val request = ApiRequest("/api/operator/lead")
            _lead.load(fresh = { api.send<LeadDesk>(request) })
        }
    }

    fun refreshSales() {
        val month = _salesMonth.value
        salesJob = relaunch(salesJob) {
            val request = ApiRequest("/api/operator/sales-kpi", query = mapOf("month" to month))
            _sales.load(
                cached = { api.cached<DataEnvelope<SalesQualityMonth>>(request)?.data },
                fresh = { api.send<DataEnvelope<SalesQualityMonth>>(request).data },
            )
        }
    }

    // ── Периоды ──────────────────────────────────────────────────────────────

    /** Сдвинуть неделю зарплаты. Вперёд дальше текущей не пускаем: там ещё не работали. */
    fun shiftSalaryWeek(by: Int) {
        val current = parseDate(_salaryWeek.value) ?: return
        val next = currentWeekStart(current.plusWeeks(by.toLong()))
        if (next > currentWeekStart() || next == _salaryWeek.value) return
        _salaryWeek.value = next
        refreshSalary()
    }

    /** Шаг по неделям графика. Вперёд ходить можно: график публикуют заранее. */
    fun shiftScheduleWeek(by: Int) {
        val current = parseDate(_scheduleWeek.value) ?: return
        _scheduleWeek.value = currentWeekStart(current.plusWeeks(by.toLong()))
        refreshSchedule()
    }

    fun shiftSalesMonth(by: Int) {
        val current = YearMonth.now()
        val next = YearMonth.parse(_salesMonth.value).plusMonths(by.toLong())
        if (next > current) return
        _salesMonth.value = next.toString()
        refreshSales()
    }

    // ── Действия ─────────────────────────────────────────────────────────────

    /** Подтвердить, что видел график недели и выходишь. */
    fun confirmScheduleWeek(responseId: String) {
        if (_confirming.value) return
        viewModelScope.launch {
            _confirming.value = true
            _actionError.value = null
            try {
                api.sendUnit(
                    ApiRequest.json(
                        "/api/operator/shifts",
                        buildJsonObject {
                            put("action", "confirmWeek")
                            put("responseId", responseId)
                        },
                    ),
                )
                refreshSchedule()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionError.value = e.operatorText()
            } finally {
                _confirming.value = false
            }
        }
    }

    internal fun openIssue(date: String, shiftType: String, companyName: String) {
        _issue.value = IssueDraft(date, shiftType, companyName)
    }

    internal fun dismissIssue() {
        _issue.value = null
    }

    /** Сказать, что на смену выйти не получится: заявка уходит руководителю. */
    fun submitIssue(responseId: String?, reason: String) {
        val draft = _issue.value ?: return
        if (draft.saving) return
        if (responseId == null) {
            _issue.value = null
            return
        }
        _issue.update { it?.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                api.sendUnit(
                    ApiRequest.json(
                        "/api/operator/shifts",
                        buildJsonObject {
                            put("action", "reportIssue")
                            put("responseId", responseId)
                            put("shiftDate", draft.shiftDate)
                            put("shiftType", draft.shiftType)
                            put("reason", reason.trim())
                        },
                    ),
                )
                _issue.value = null
                refreshSchedule()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _issue.update { it?.copy(saving = false, error = e.operatorText()) }
            }
        }
    }

    internal fun openProposal(requestId: String) {
        _proposal.value = ProposalDraft(requestId)
    }

    internal fun dismissProposal() {
        _proposal.value = null
    }

    /** Предложение старшего по заявке: оставить, снять или заменить. Последнее слово — за руководителем. */
    fun submitProposal(choice: String, replacement: String?, note: String) {
        val draft = _proposal.value ?: return
        if (draft.saving) return
        _proposal.update { it?.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                api.sendUnit(
                    ApiRequest.json(
                        "/api/operator/lead",
                        buildJsonObject {
                            put("action", "submitLeadProposal")
                            put("requestId", draft.requestId)
                            put("proposalAction", choice)
                            if (note.trim().isNotEmpty()) put("proposalNote", note.trim())
                            val target = if (choice == "replace") replacement else null
                            if (!target.isNullOrEmpty()) put("replacementOperatorId", target)
                        },
                    ),
                )
                _proposal.value = null
                refreshLead()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _proposal.update { it?.copy(saving = false, error = e.operatorText()) }
            }
        }
    }
}
