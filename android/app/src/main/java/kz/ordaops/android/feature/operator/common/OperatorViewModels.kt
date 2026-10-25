package kz.ordaops.android.feature.operator.common

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * ViewModel раздела оператора с зависимостями из `LocalOperatorEnv`.
 *
 * Живёт, пока жива Activity, — переживает поворот экрана и пересоздание, в
 * отличие от `remember`. Ключ включает оператора: на общем телефоне сменщик не
 * увидит чужое состояние.
 *
 * ```
 * val vm = operatorViewModel { env -> TasksViewModel(env.api, env.outbox) }
 * val state by vm.state.collectAsStateWithLifecycle()
 * ```
 */
@Composable
inline fun <reified VM : ViewModel> operatorViewModel(
    key: String? = null,
    crossinline create: (OperatorEnv) -> VM,
): VM {
    val env = LocalOperatorEnv.current
    return viewModel(
        key = "${VM::class.java.name}:${env.operatorId}:${key.orEmpty()}",
        factory = viewModelFactory { initializer { create(env) } },
    )
}
