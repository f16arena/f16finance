package kz.ordaops.android.feature.business.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kz.ordaops.android.core.net.ApiError
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load
import kz.ordaops.android.feature.business.AnalyticsPeriod
import kz.ordaops.android.feature.business.Company

/** Что рисует выписка: доходы или расходы. */
enum class LedgerKind { Income, Expense }

/** Загруженные записи периода. Нужный список зависит от [LedgerKind]. */
data class LedgerData(
    val incomes: List<IncomeRow> = emptyList(),
    val expenses: List<ExpenseRow> = emptyList(),
)

/** Исход сохранения дохода: дубль — не ошибка, а вопрос «сохранить всё равно?». */
sealed interface SaveOutcome {
    data object Saved : SaveOutcome
    data object Duplicate : SaveOutcome
    data class Failed(val message: String) : SaveOutcome
}

/**
 * Состояние выписки «Доходы / Расходы» и форм добавления. Порт кусков
 * `BusinessStore` (loadIncomes/loadExpenses/createIncome) и мастера расхода.
 */
class LedgerViewModel(
    val kind: LedgerKind,
    private val api: FinanceApi,
    companies: StateFlow<List<Company>>,
) : ViewModel() {

    val companies: StateFlow<List<Company>> = companies

    private val _period = MutableStateFlow<AnalyticsPeriod>(AnalyticsPeriod.ThisMonth)
    val period: StateFlow<AnalyticsPeriod> = _period.asStateFlow()

    private val _state = MutableStateFlow(Loadable<LedgerData>())
    val state: StateFlow<Loadable<LedgerData>> = _state.asStateFlow()

    private val _operators = MutableStateFlow<List<FinOperator>>(emptyList())
    val operators: StateFlow<List<FinOperator>> = _operators.asStateFlow()

    private val _categories = MutableStateFlow<List<ExpenseCategory>>(emptyList())
    val categories: StateFlow<List<ExpenseCategory>> = _categories.asStateFlow()

    private val _vendors = MutableStateFlow<List<ExpenseVendor>>(emptyList())
    val vendors: StateFlow<List<ExpenseVendor>> = _vendors.asStateFlow()

    private var job: Job? = null

    fun setPeriod(p: AnalyticsPeriod) {
        if (p == _period.value) return
        _period.value = p
        // Цифры другого периода под новыми датами читались бы как ответ.
        _state.value = Loadable(isLoading = true)
        reload()
    }

    /** Перезапрос под текущий период; прошлый запрос отменяется. */
    fun reload() {
        job?.cancel()
        job = viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val (from, to) = _period.value.bounds()
        if (kind == LedgerKind.Income) {
            _state.load(
                cached = { api.cachedIncomes(from, to)?.let { LedgerData(incomes = it) } },
                fresh = { LedgerData(incomes = api.incomes(from, to)) },
            )
        } else {
            _state.load(
                cached = { api.cachedExpenses(from, to)?.let { LedgerData(expenses = it) } },
                fresh = { LedgerData(expenses = api.expenses(from, to)) },
            )
        }
    }

    /** Операторы нужны для правки дохода и формы нового; грузим один раз, тихо. */
    fun loadOperators() {
        if (_operators.value.isNotEmpty()) return
        viewModelScope.launch { runCatching { api.operators() }.onSuccess { _operators.value = it } }
    }

    /** Категории и доверенные поставщики — для формы расхода. */
    fun loadExpenseRefs() {
        viewModelScope.launch {
            if (_categories.value.isEmpty()) runCatching { api.categories() }.onSuccess { _categories.value = it }
            if (_vendors.value.isEmpty()) runCatching { api.vendors() }.onSuccess { _vendors.value = it }
        }
    }

    suspend fun createIncome(draft: IncomeDraft, force: Boolean): SaveOutcome = try {
        api.createIncome(draft, force)
        load()
        SaveOutcome.Saved
    } catch (e: ApiError.Conflict) {
        if (e.code == "duplicate") SaveOutcome.Duplicate else SaveOutcome.Failed(e.userMessage())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        SaveOutcome.Failed(e.userMessage())
    }

    suspend fun saveIncomeEdit(edit: IncomeEdit): String? = guarded { api.updateIncome(edit); load() }

    suspend fun saveExpenseEdit(edit: ExpenseEdit): String? = guarded { api.updateExpense(edit); load() }

    suspend fun delete(id: String): String? = guarded {
        if (kind == LedgerKind.Income) api.deleteIncome(id) else api.deleteExpense(id)
        load()
    }

    /** Выполнить и вернуть текст ошибки (или null). */
    private suspend fun guarded(block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }
}
