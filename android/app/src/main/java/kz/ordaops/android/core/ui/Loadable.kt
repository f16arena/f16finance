package kz.ordaops.android.core.ui

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kz.ordaops.android.core.net.ApiError
import kz.ordaops.android.core.net.userMessage

/**
 * Состояние данных экрана — одно на все разделы.
 *
 * Главное правило: загруженное не выбрасываем. Обновление идёт поверх
 * показанного (`isRefreshing`), а ошибка при уже показанных данных — тихая
 * плашка, а не пустой экран.
 */
@Immutable
data class Loadable<out T>(
    val data: T? = null,
    /** Первая загрузка: показать нечего. */
    val isLoading: Boolean = false,
    /** Обновление поверх показанного. */
    val isRefreshing: Boolean = false,
    val error: String? = null,
    /** Ошибка из-за связи — экран может сказать «показаны сохранённые данные». */
    val isOffline: Boolean = false,
) {
    val hasData: Boolean get() = data != null
}

/**
 * Загрузить «сначала кэш, потом сеть», не сбрасывая показанное.
 * `cached` — последний удачный ответ (может быть null), `fresh` — запрос к сайту.
 */
suspend fun <T> MutableStateFlow<Loadable<T>>.load(
    cached: (suspend () -> T?)? = null,
    fresh: suspend () -> T,
) {
    val hadData = value.hasData
    if (!hadData && cached != null) {
        runCatching { cached() }.getOrNull()?.let { c -> update { it.copy(data = c) } }
    }
    update {
        if (it.hasData) it.copy(isRefreshing = true, error = null) else it.copy(isLoading = true, error = null)
    }
    try {
        val result = fresh()
        update { Loadable(data = result) }
    } catch (e: CancellationException) {
        update { it.copy(isLoading = false, isRefreshing = false) }
        throw e
    } catch (e: Exception) {
        update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                error = e.userMessage(),
                isOffline = e is ApiError.Transport,
            )
        }
    }
}
