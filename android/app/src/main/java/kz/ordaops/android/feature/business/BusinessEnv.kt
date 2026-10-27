package kz.ordaops.android.feature.business

import androidx.compose.runtime.staticCompositionLocalOf
import kz.ordaops.android.core.access.PageAccess
import kz.ordaops.android.core.net.ApiClient

/** Вкладки кабинета, на которые можно перейти с главной. */
enum class BusinessTab { Platform, Home, Analytics, Services, Talk, Profile }

/**
 * Всё, что нужно экранам кабинета: клиент API, права, общее состояние и переходы.
 * Раздаётся через [LocalBusiness], как `LocalOperatorEnv` у оператора.
 */
class BusinessEnv(
    val api: ApiClient,
    val access: PageAccess,
    val vm: BusinessViewModel,
    /** Открыть раздел каталога: нативный экран поверх вкладки или сайт во вкладке Chrome. */
    val open: (String) -> Unit,
    /** Перейти на вкладку (с главной — в аналитику и сервисы). */
    val goTab: (BusinessTab) -> Unit,
    val signOut: () -> Unit,
) {
    val resolver get() = access.resolver

    /** Число расходов, ждущих решения, — значок на «Сервисах» и плитке. */
    fun can(capability: String): Boolean = resolver.can(capability)
}

val LocalBusiness = staticCompositionLocalOf<BusinessEnv> { error("BusinessEnv не передан") }
