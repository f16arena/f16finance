package kz.ordaops.android.platform.widget

import android.content.Context
import androidx.core.content.edit
import androidx.glance.appwidget.updateAll

/**
 * Снимок смены для виджета. Виджет живёт в процессе лаунчера и не должен знать про
 * сеть и авторизацию, поэтому приложение (или фоновая задача) кладёт сюда готовые
 * цифры, а виджет только рисует. Токенов тут нет — только то, что и так на экране смены.
 */
data class WidgetSnapshot(
    val state: State = State.SignedOut,
    val company: String = "",
    /** Кто на смене: для своей смены — «вы», для чужой — имя. */
    val who: String = "",
    val revenue: Double = 0.0,
    val salesCount: Int = 0,
    val weekEarned: Double = 0.0,
    val updatedAt: Long = 0L,
) {
    enum class State { SignedOut, Closed, Mine, Other }

    companion object {
        private const val PREFS = "orda.widget"

        fun load(context: Context): WidgetSnapshot {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return WidgetSnapshot(
                state = runCatching { State.valueOf(p.getString("state", null) ?: "") }.getOrDefault(State.SignedOut),
                company = p.getString("company", "") ?: "",
                who = p.getString("who", "") ?: "",
                revenue = Double.fromBits(p.getLong("revenue", 0.0.toBits())),
                salesCount = p.getInt("sales", 0),
                weekEarned = Double.fromBits(p.getLong("week", 0.0.toBits())),
                updatedAt = p.getLong("updatedAt", 0L),
            )
        }

        /** Записывает снимок и перерисовывает виджеты. Не трогает диск, если ничего не изменилось. */
        suspend fun save(context: Context, value: WidgetSnapshot) {
            val old = load(context)
            if (old.copy(updatedAt = 0) == value.copy(updatedAt = 0)) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString("state", value.state.name)
                putString("company", value.company)
                putString("who", value.who)
                putLong("revenue", value.revenue.toBits())
                putInt("sales", value.salesCount)
                putLong("week", value.weekEarned.toBits())
                putLong("updatedAt", System.currentTimeMillis())
            }
            ShiftWidget().updateAll(context)
        }

        /** Выход из аккаунта: чужие цифры на рабочем столе оставлять нельзя. */
        suspend fun clear(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
            ShiftWidget().updateAll(context)
        }
    }
}
