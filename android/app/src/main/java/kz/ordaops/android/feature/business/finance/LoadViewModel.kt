package kz.ordaops.android.feature.business.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load

/**
 * Универсальный ViewModel «загрузить и показать» для экранов раздела, у которых
 * нет своей логики: один запрос, кэш — сразу, свежее — следом.
 *
 * `fetch` может зависеть от изменяемого аргумента (период, неделя) — его хранит
 * вызывающий и передаёт через замыкание; [reload] отменяет прошлый запрос,
 * чтобы медленный ответ за старый период не подменил новый.
 */
open class LoadViewModel<T>(
    private val fetchFn: (suspend () -> T)? = null,
    private val cached: (suspend () -> T?)? = null,
) : ViewModel() {
    /** Запрос свежего ответа. Наследник с изменяемым аргументом переопределяет его. */
    protected open suspend fun fetch(): T = fetchFn!!.invoke()

    private val _state = MutableStateFlow(Loadable<T>())
    val state: StateFlow<Loadable<T>> = _state.asStateFlow()
    private var job: Job? = null

    /** Забыть показанное (смена периода): цифры другого периода читались бы как ответ. */
    fun reset() {
        job?.cancel()
        _state.value = Loadable(isLoading = true)
    }

    fun reload() {
        job?.cancel()
        job = viewModelScope.launch { _state.load(cached = cached, fresh = { fetch() }) }
    }

    /** Подменить показанное локально (оптимистичное обновление после действия). */
    protected fun mutate(change: (T) -> T) {
        _state.value = _state.value.let { s -> s.copy(data = s.data?.let(change)) }
    }
}
