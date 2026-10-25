package kz.ordaops.android.platform

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Плитка «Продать» в быстрых настройках: из шторки сразу на кассу, не ища
 * приложение на рабочем столе.
 */
class SellTileService : TileService() {
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = DeepLinks.intent(this, "Sale")
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
