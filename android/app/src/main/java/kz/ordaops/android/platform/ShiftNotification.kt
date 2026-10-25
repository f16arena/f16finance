package kz.ordaops.android.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kz.ordaops.android.R
import kz.ordaops.android.ui.components.money

/**
 * Смена в шторке — Android-ответ на Live Activity с айфона: пока своя смена
 * открыта, висит уведомление с таймером, выручкой и кнопками «Продать» и
 * «Смена». Смахнуть его нельзя — оно пропадает, когда смену закрыли.
 */
object ShiftNotification {
    private const val CHANNEL = "shift"
    private const val ID = 1001

    data class Snapshot(
        val companyName: String,
        val openedAtMillis: Long?,
        val revenue: Double,
        val salesCount: Int,
        val isNight: Boolean,
        val pendingSales: Int,
    )

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(context: Context, s: Snapshot) {
        if (!canPost(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, DeepLinks.intent(context, "Shift"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val sell = PendingIntent.getActivity(
            context, 1, DeepLinks.intent(context, "Sale"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = buildString {
            append(money(s.revenue))
            append(" · ")
            append(checks(s.salesCount))
            // Неотправленные чеки — главное, что нельзя забыть, уходя со смены.
            if (s.pendingSales > 0) append(" · ждут связи: ${s.pendingSales}")
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_orda)
            .setContentTitle((if (s.isNight) "Ночная смена · " else "Смена · ") + s.companyName)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setColor(0xFF2563EB.toInt())
            .setContentIntent(open)
            .addAction(0, "Продать", sell)
            .addAction(0, "Смена", open)
        s.openedAtMillis?.let { builder.setWhen(it).setUsesChronometer(true).setShowWhen(true) }
        try {
            NotificationManagerCompat.from(context).notify(ID, builder.build())
        } catch (_: SecurityException) {
            // Разрешение отозвали между проверкой и показом — молча пропускаем.
        }
    }

    fun hide(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Смена", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Открытая смена: время, выручка, быстрые кнопки"
                setShowBadge(false)
            },
        )
    }

    private fun checks(n: Int): String {
        val m10 = n % 10
        val m100 = n % 100
        val word = when {
            m10 == 1 && m100 != 11 -> "чек"
            m10 in 2..4 && (m100 < 12 || m100 > 14) -> "чека"
            else -> "чеков"
        }
        return "$n $word"
    }
}
