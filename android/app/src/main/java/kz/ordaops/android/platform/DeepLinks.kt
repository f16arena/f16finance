package kz.ordaops.android.platform

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kz.ordaops.android.MainActivity

/**
 * Переход в раздел извне: из уведомления смены, ярлыка на иконке, плитки в
 * шторке. Раздел — имя `OperatorSection` («Sale», «Shift», «Audit»…).
 */
object DeepLinks {
    const val EXTRA_SECTION = "orda.section"

    /** Куда просили перейти; OperatorRoot забирает значение и обнуляет. */
    val pendingSection = MutableStateFlow<String?>(null)

    fun consume(intent: Intent?) {
        intent?.getStringExtra(EXTRA_SECTION)?.let { pendingSection.value = it }
    }

    fun intent(context: Context, section: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_SECTION, section)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
