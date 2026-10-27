package kz.ordaops.android.platform

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kz.ordaops.android.MainActivity

/**
 * Переход в раздел извне: из уведомления смены, ярлыка на иконке, плитки в
 * шторке. Раздел оператора — имя `OperatorSection` («Sale», «Shift», «Audit»…);
 * раздел кабинета владельца — идентификатор страницы каталога прав («income»,
 * «store-warehouse») из `orda.page` либо из ссылки `orda://page/<страница>`.
 */
object DeepLinks {
    const val EXTRA_SECTION = "orda.section"
    const val EXTRA_PAGE = "orda.page"

    /** Куда просили перейти; OperatorRoot забирает значение и обнуляет. */
    val pendingSection = MutableStateFlow<String?>(null)

    /** Куда просили перейти в кабинете владельца; BusinessRoot забирает значение и обнуляет. */
    val pendingPage = MutableStateFlow<String?>(null)

    fun consume(intent: Intent?) {
        intent?.getStringExtra(EXTRA_SECTION)?.let { pendingSection.value = it }
        (intent?.getStringExtra(EXTRA_PAGE) ?: pageFromUri(intent?.data?.scheme, intent?.data?.host, intent?.data?.path))
            ?.let { pendingPage.value = it }
    }

    /** `orda://page/finance.income` → `finance.income`; чужие ссылки — null. */
    fun pageFromUri(scheme: String?, host: String?, path: String?): String? {
        if (scheme != "orda" || host != "page") return null
        return path?.trim('/')?.takeIf { it.isNotEmpty() }
    }

    fun pageIntent(context: Context, pageId: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_PAGE, pageId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun intent(context: Context, section: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_SECTION, section)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
