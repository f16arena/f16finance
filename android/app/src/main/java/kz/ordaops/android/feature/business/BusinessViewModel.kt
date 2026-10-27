package kz.ordaops.android.feature.business

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiError
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load

/**
 * Состояние кабинета владельца и сотрудника: дашборд, очередь расходов, точки,
 * общий фильтр аналитики. Порт нужных кусков `BusinessStore`, `AnalyticsStore`
 * и `OwnerHomeStore`.
 *
 * Живёт, пока жива Activity: поворот и возврат из другого приложения цифры не
 * сбрасывают. Прошлый ответ показываем сразу (кэш `ApiClient`), свежий приходит
 * следом — экран не начинается с пустых карточек.
 */
class BusinessViewModel(
    private val api: ApiClient,
    private val prefs: SharedPreferences?,
    /** Организация — фильтр одной организации не должен переехать в другую. */
    private val organizationKey: String,
    /** Очередь расходов видна только с этим правом; без него не дёргаем сервер зря. */
    private val canSeePending: Boolean,
) : ViewModel() {

    // ── Дашборд, очередь, точки ──────────────────────────────────────────────

    private val _dashboard = MutableStateFlow(Loadable<BusinessDashboard>())
    val dashboard: StateFlow<Loadable<BusinessDashboard>> = _dashboard.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingExpense>>(emptyList())
    val pending: StateFlow<List<PendingExpense>> = _pending.asStateFlow()

    private val _companies = MutableStateFlow<List<Company>>(emptyList())
    val companies: StateFlow<List<Company>> = _companies.asStateFlow()

    /** Дашборд, очередь и точки — одним заходом. Возврат в приложение — повод звать снова. */
    fun bootstrap() {
        viewModelScope.launch {
            coroutineScope {
                launch { loadDashboard() }
                launch { loadPending() }
                launch { loadCompanies() }
            }
        }
    }

    suspend fun loadDashboard() {
        val request = ApiRequest("/api/admin/dashboard")
        _dashboard.load(
            cached = { api.cached<BizData<BusinessDashboard>>(request)?.data },
            fresh = { api.send<BizData<BusinessDashboard>>(request).data },
        )
    }

    suspend fun loadPending() {
        if (!canSeePending) return
        try {
            _pending.value = api.send<BizList<PendingExpense>>(ApiRequest("/api/admin/expenses/pending")).data
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Нет права смотреть очередь — это настройка доступа, а не сбой.
            _pending.value = emptyList()
        }
    }

    suspend fun loadCompanies() {
        runCatching { api.send<BizList<Company>>(ApiRequest("/api/admin/companies")).data }
            .onSuccess { _companies.value = it }
    }

    /** Опрос дашборда раз в 30 секунд, пока вызывающий жив (`LaunchedEffect`). */
    suspend fun pollDashboard() {
        while (true) {
            delay(30_000)
            loadDashboard()
        }
    }

    fun refreshAll() {
        bootstrap()
        reloadHome()
        reloadAnalytics()
    }

    // ── Общий фильтр аналитики ───────────────────────────────────────────────

    private val _filter = MutableStateFlow(restoreFilter())
    val filter: StateFlow<AnalyticsFilter> = _filter.asStateFlow()

    /** Учитывать ли отдельную кассу («F16 Extra») в итогах. Одна настройка на все отчёты. */
    private val _includeExtra = MutableStateFlow(prefs?.getBoolean(KEY_EXTRA, false) ?: false)
    val includeExtra: StateFlow<Boolean> = _includeExtra.asStateFlow()

    private val _analytics = MutableStateFlow(Loadable<OwnerAnalytics>())
    val analytics: StateFlow<Loadable<OwnerAnalytics>> = _analytics.asStateFlow()

    /** Точки, известные по прошлому ответу: список для фильтра не пропадает, пока грузится новый период. */
    private val _companyOptions = MutableStateFlow<List<OwnerAnalytics.CompanyOption>>(emptyList())
    val companyOptions: StateFlow<List<OwnerAnalytics.CompanyOption>> = _companyOptions.asStateFlow()

    private var analyticsJob: Job? = null

    fun setPeriod(period: AnalyticsPeriod) = updateFilter { it.copy(period = period) }

    fun setCompare(compare: AnalyticsCompare) = updateFilter { it.copy(compare = compare) }

    /** Пустой набор — все точки. */
    fun setCompanies(ids: Set<String>) = updateFilter { it.copy(companyIds = ids) }

    fun setIncludeExtra(value: Boolean) {
        if (_includeExtra.value == value) return
        _includeExtra.value = value
        prefs?.edit { putBoolean(KEY_EXTRA, value) }
        reloadAnalytics()
        reloadHome()
    }

    private fun updateFilter(change: (AnalyticsFilter) -> AnalyticsFilter) {
        val old = _filter.value
        val new = change(old)
        if (new == old) return
        _filter.value = new
        saveFilter(new)
        reloadAnalytics()
        // У главной свой период, но точки общие с аналитикой.
        if (new.companyIds != old.companyIds) reloadHome()
    }

    /**
     * Перезапросить под текущий фильтр. Прошлый запрос отменяется: медленный
     * ответ за «год» не должен прийти после быстрого за «сегодня» и подменить его.
     */
    fun reloadAnalytics() {
        analyticsJob?.cancel()
        analyticsJob = viewModelScope.launch { loadAnalytics() }
    }

    private fun analyticsQuery(): OwnerAnalyticsQuery {
        val f = _filter.value
        val (from, to) = f.period.bounds()
        return OwnerAnalyticsQuery(from, to, f.companyIds.toList(), f.compare, _includeExtra.value)
    }

    private suspend fun loadAnalytics() {
        val query = analyticsQuery()
        val request = ApiRequest("/api/admin/owner-analytics", query = query.queryParams)
        // Прошлый ответ на этот же фильтр — сразу, пока идёт свежий.
        api.cached<BizData<OwnerAnalytics>>(request)?.data?.let { cached -> _analytics.update { it.copy(data = cached) } }
        _analytics.update {
            if (it.hasData) it.copy(isRefreshing = true, error = null) else it.copy(isLoading = true, error = null)
        }
        try {
            val result = api.send<BizData<OwnerAnalytics>>(request).data
            _analytics.value = Loadable(data = result)
            applyCompanies(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _analytics.update {
                // Цифры другого периода оставлять нельзя: над ними уже новые даты, и они читались бы как ответ.
                val stale = it.data?.period?.let { p -> p.from != query.from || p.to != query.to } == true
                it.copy(
                    data = if (stale) null else it.data,
                    isLoading = false,
                    isRefreshing = false,
                    error = e.userMessage(),
                    isOffline = e is ApiError.Transport,
                )
            }
        }
    }

    private fun applyCompanies(result: OwnerAnalytics) {
        _companyOptions.value = result.companies
        // Точку могли удалить или отнять доступ — выбор без неё.
        val known = result.companies.map { it.id }.toSet()
        val current = _filter.value
        if (!known.containsAll(current.companyIds)) {
            val pruned = current.copy(companyIds = current.companyIds.intersect(known))
            _filter.value = pruned
            saveFilter(pruned)
            reloadAnalytics()
        }
    }

    // ── Главная: «сегодня» и «этот месяц» ────────────────────────────────────

    private val _homeToday = MutableStateFlow<OwnerAnalytics?>(null)
    val homeToday: StateFlow<OwnerAnalytics?> = _homeToday.asStateFlow()

    private val _homeMonth = MutableStateFlow<OwnerAnalytics?>(null)
    val homeMonth: StateFlow<OwnerAnalytics?> = _homeMonth.asStateFlow()

    private val _homeLoading = MutableStateFlow(false)
    val homeLoading: StateFlow<Boolean> = _homeLoading.asStateFlow()

    private val _homeError = MutableStateFlow<String?>(null)
    val homeError: StateFlow<String?> = _homeError.asStateFlow()

    private var homeJob: Job? = null

    fun reloadHome() {
        homeJob?.cancel()
        homeJob = viewModelScope.launch { loadHome() }
    }

    /**
     * У главной свой неизменный период: владелец открывает приложение, чтобы
     * увидеть сегодня, а не то, что крутил в аналитике вчера. Хватает лёгкого ответа.
     */
    private suspend fun loadHome() {
        _homeLoading.value = true
        try {
            val ids = _filter.value.companyIds.toList()
            val extra = _includeExtra.value
            val (todayFrom, todayTo) = AnalyticsPeriod.Today.bounds()
            val (monthFrom, monthTo) = AnalyticsPeriod.ThisMonth.bounds()
            val todayRequest = homeRequest(OwnerAnalyticsQuery(todayFrom, todayTo, ids, includeExtra = extra, lite = true))
            val monthRequest = homeRequest(OwnerAnalyticsQuery(monthFrom, monthTo, ids, includeExtra = extra, lite = true))

            // Прошлые цифры — сразу: открыл приложение и видишь выручку, а не пустую карточку.
            if (_homeToday.value == null) _homeToday.value = api.cached<BizData<OwnerAnalytics>>(todayRequest)?.data
            if (_homeMonth.value == null) _homeMonth.value = api.cached<BizData<OwnerAnalytics>>(monthRequest)?.data

            // Два запроса независимы: сбой одного не должен гасить другой.
            var error: String? = null
            coroutineScope {
                val t = async { runCatching { api.send<BizData<OwnerAnalytics>>(todayRequest).data } }
                val m = async { runCatching { api.send<BizData<OwnerAnalytics>>(monthRequest).data } }
                val (today, month) = t.await() to m.await()
                today.onSuccess { _homeToday.value = it }.onFailure { error = it.userMessage() }
                month.onSuccess { _homeMonth.value = it }.onFailure { error = it.userMessage() }
                (today.exceptionOrNull() ?: month.exceptionOrNull())?.let { if (it is CancellationException) throw it }
            }
            _homeError.value = error
        } finally {
            _homeLoading.value = false
        }
    }

    private fun homeRequest(q: OwnerAnalyticsQuery) = ApiRequest("/api/admin/owner-analytics", query = q.queryParams)

    // ── Хранение фильтра ─────────────────────────────────────────────────────

    private fun restoreFilter(): AnalyticsFilter {
        val p = prefs ?: return AnalyticsFilter()
        // Период переживает перезапуск, выбор точек — нет: один раз нажали на строку
        // точки — и приложение неделями открывалось бы «по одной Арене», а итоги
        // расходились бы с сайтом без видимой причины.
        val period = AnalyticsPeriod.fromKey(p.getString("period.$organizationKey", null)) ?: AnalyticsPeriod.ThisMonth
        val compare = AnalyticsCompare.entries.firstOrNull { it.wire == p.getString("compare.$organizationKey", null) }
            ?: AnalyticsCompare.Previous
        return AnalyticsFilter(period = period, compare = compare)
    }

    private fun saveFilter(f: AnalyticsFilter) {
        prefs?.edit {
            putString("period.$organizationKey", f.period.key)
            putString("compare.$organizationKey", f.compare.wire)
        }
    }

    companion object {
        private const val KEY_EXTRA = "includeExtra"

        /** Та же проверка, что на сервере (`isExtraCompany`): код `extra` или имя «F16 Extra». */
        fun isExtra(company: Company): Boolean =
            company.code?.lowercase() == "extra" || company.name == "F16 Extra"

        /** Подпись выбранных точек: «Все точки», «F16 Arena», «3 точки». */
        fun companiesTitle(ids: Set<String>, options: List<OwnerAnalytics.CompanyOption>): String = when {
            ids.isEmpty() -> "Все точки"
            ids.size == 1 -> options.firstOrNull { it.id == ids.first() }?.name ?: "1 точка"
            else -> "${ids.size} ${BizFormat.pointsWord(ids.size)}"
        }
    }
}
