package kz.ordaops.android.platform.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import kz.ordaops.android.OrdaApplication
import kz.ordaops.android.core.auth.AuthStore
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.feature.operator.shift.OperatorOverview
import kz.ordaops.android.feature.operator.shift.ShiftState
import java.util.concurrent.TimeUnit

/**
 * Раз в 30 минут подтягивает смену для виджета, пока приложение закрыто.
 * Ходит тем же клиентом и той же сессией, что и приложение; если вход не
 * восстановился или нет сети — молча оставляет прошлый снимок.
 */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? OrdaApplication ?: return Result.success()
        // Вход восстанавливается асинхронно в OrdaApplication.onCreate — даём ему закончить.
        var waited = 0
        while (app.auth.state.value.phase.let { it == AuthStore.Phase.Restoring || it == AuthStore.Phase.LoadingRole } && waited < 20) {
            delay(500)
            waited++
        }
        if (app.auth.state.value.phase != AuthStore.Phase.SignedIn) return Result.success()

        val old = WidgetSnapshot.load(applicationContext)
        return try {
            val shift = app.api.send<ShiftState>(ApiRequest("/api/operator/shift/current"))
            val week = runCatching { app.api.send<OperatorOverview>(ApiRequest("/api/operator/overview")) }.getOrNull()
            val open = shift.shift?.takeIf { it.isOpen }
            val state = when {
                open == null -> WidgetSnapshot.State.Closed
                shift.isMine -> WidgetSnapshot.State.Mine
                else -> WidgetSnapshot.State.Other
            }
            WidgetSnapshot.save(
                applicationContext,
                old.copy(
                    state = state,
                    who = open?.operatorName.orEmpty(),
                    revenue = if (state == WidgetSnapshot.State.Mine) shift.totals.netTotal else 0.0,
                    salesCount = if (state == WidgetSnapshot.State.Mine) shift.totals.salesCount else 0,
                    weekEarned = week?.week?.netAmount ?: old.weekEarned,
                ),
            )
            Result.success()
        } catch (_: Exception) {
            // Нет связи — не повод считать задачу проваленной: следующий запуск через 30 минут.
            Result.success()
        }
    }

    companion object {
        const val NAME = "orda.widget.refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
