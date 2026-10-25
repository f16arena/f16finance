package kz.ordaops.android.platform.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.WorkManager
import kz.ordaops.android.R
import kz.ordaops.android.feature.operator.shift.pluralize
import kz.ordaops.android.platform.DeepLinks
import kz.ordaops.android.ui.components.money

/** Виджет «Смена»: кто на точке, выручка своей смены, заработок за неделю и кнопка «Продать». */
class ShiftWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetSnapshot.load(context)
        provideContent { Content(context, snapshot) }
    }
}

private val text = ColorProvider(R.color.widget_text)
private val dim = ColorProvider(R.color.widget_dim)
private val accent = ColorProvider(R.color.widget_accent)

@Composable
private fun Content(context: Context, s: WidgetSnapshot) {
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_bg))
            .padding(14.dp)
            // Вся карточка ведёт в смену; «Продать» ниже перехватывает своё нажатие.
            .clickable(actionStartActivity(DeepLinks.intent(context, "Shift"))),
    ) {
        Text(
            s.company.ifBlank { "Orda" },
            style = TextStyle(color = dim, fontSize = 12.sp),
            maxLines = 1,
        )
        Text(
            when (s.state) {
                WidgetSnapshot.State.SignedOut -> "Войдите в Orda"
                WidgetSnapshot.State.Closed -> "Смена закрыта"
                WidgetSnapshot.State.Mine -> "Ваша смена идёт"
                WidgetSnapshot.State.Other -> "На смене: ${s.who.ifBlank { "коллега" }}"
            },
            style = TextStyle(color = text, fontSize = 17.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        if (s.state == WidgetSnapshot.State.Mine) {
            Text(
                "${money(s.revenue)} · ${s.salesCount} ${pluralize(s.salesCount, "чек", "чека", "чеков")}",
                style = TextStyle(color = accent, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
        if (s.state != WidgetSnapshot.State.SignedOut && s.weekEarned > 0) {
            Text(
                "Заработано за неделю: ${money(s.weekEarned)}",
                style = TextStyle(color = dim, fontSize = 12.sp),
                maxLines = 1,
            )
        }
        if (s.state != WidgetSnapshot.State.SignedOut) {
            Spacer(GlanceModifier.height(8.dp))
            Box(
                GlanceModifier
                    .background(ImageProvider(R.drawable.widget_button_bg))
                    .padding(horizontal = 18.dp, vertical = 7.dp)
                    .clickable(actionStartActivity(DeepLinks.intent(context, "Sale"))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Продать",
                    style = TextStyle(color = ColorProvider(androidx.compose.ui.graphics.Color.White), fontSize = 14.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

class ShiftWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShiftWidget()

    // Периодический опрос нужен, только пока виджет лежит на рабочем столе.
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(WidgetRefreshWorker.NAME)
    }
}
