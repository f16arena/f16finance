package kz.ordaops.android.feature.operator.common

import androidx.compose.runtime.staticCompositionLocalOf
import kz.ordaops.android.core.access.AccessResolver
import kz.ordaops.android.core.net.ApiClient

/**
 * Всё, что нужно экранам оператора: клиент API, общая очередь действий и
 * права. Раздаётся через `LocalOperatorEnv`, чтобы не тащить через каждый экран.
 */
class OperatorEnv(
    val api: ApiClient,
    val outbox: ActionOutbox,
    val resolver: AccessResolver,
    /** Открыть другой раздел (из карточки на главной, из профиля). */
    val open: (String) -> Unit,
    val signOut: () -> Unit,
) {
    /** Хозяин очередей — оператор, а не учётка: на общем телефоне меняется человек. */
    val operatorId: String? get() = resolver.session.operatorId
}

val LocalOperatorEnv = staticCompositionLocalOf<OperatorEnv> { error("OperatorEnv не передан") }
