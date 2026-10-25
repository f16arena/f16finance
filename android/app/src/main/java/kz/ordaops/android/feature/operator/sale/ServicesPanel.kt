package kz.ordaops.android.feature.operator.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kz.ordaops.android.feature.operator.shift.SaleService
import kz.ordaops.android.feature.operator.shift.SaleServiceVariant
import kz.ordaops.android.feature.operator.shift.ServiceSession
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ServiceColor = Color(0xFFAF52DE)
private const val WARN_MS = 5 * 60_000L

/** «0:42» / «1:05:09» — сколько осталось. */
private fun left(ms: Long): String {
    val total = Math.max(0L, (ms + 999) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return (if (h > 0) "$h:%02d".format(m) else "$m") + ":%02d".format(s)
}

/** Кнопки услуг одной лентой над списком: «⏱ Теннис · 30 мин · 500 ₸». */
@Composable
internal fun ServiceButtonsRow(services: List<SaleService>, onAdd: (SaleService, SaleServiceVariant) -> Unit) {
    val buttons = services.flatMap { s -> s.variants.map { s to it } }
    if (buttons.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
    ) {
        items(buttons, key = { it.second.id }) { (service, variant) ->
            Row(
                Modifier
                    .pressScale()
                    .background(ServiceColor.copy(alpha = 0.14f), RoundedCornerShape(Radius.md))
                    .clickable { onAdd(service, variant) }
                    .padding(horizontal = Spacing.md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "⏱ ${service.name} · ${variant.name} · ${money(variant.price)}",
                    color = Orda.colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * «Идут сейчас»: таймеры с обратным отсчётом. За 5 минут — короткий сигнал, когда
 * время вышло — сигнал ошибки (один раз на таймер). «Продлить» кладёт в чек
 * продление; деньги — обычной оплатой.
 */
@Composable
internal fun RunningTimersRow(
    sessions: List<ServiceSession>,
    services: List<SaleService>,
    onExtend: (SaleService, SaleServiceVariant, ServiceSession) -> Unit,
    onFinish: (String) -> Unit,
) {
    if (sessions.isEmpty()) return
    val haptics = rememberHaptics()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var extending by remember { mutableStateOf<ServiceSession?>(null) }
    val warned = remember { mutableSetOf<String>() }

    LaunchedEffect(sessions.size) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    // Ключ включает ends_at: после продления сигнал снова сработает на новом конце.
    LaunchedEffect(now) {
        for (s in sessions) {
            val rest = (s.endsAtMillis ?: continue) - now
            val endKey = "${s.id}:${s.endsAt}:end"
            val warnKey = "${s.id}:${s.endsAt}:warn"
            if (rest <= 0 && warned.add(endKey)) {
                warned.add(warnKey)
                haptics.error()
            } else if (rest in 1..WARN_MS && warned.add(warnKey)) {
                haptics.tick()
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(bottom = Spacing.sm), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Идут сейчас", color = Orda.colors.textDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = Spacing.lg))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(sessions, key = { it.id }) { s ->
                val rest = (s.endsAtMillis ?: now) - now
                val over = rest <= 0
                val warn = !over && rest <= WARN_MS
                val color = when {
                    over -> Orda.colors.negative
                    warn -> Orda.colors.warning
                    else -> ServiceColor
                }
                val service = services.firstOrNull { it.id == s.serviceId }
                val timed = service?.variants?.filter { (it.durationMinutes ?: 0) > 0 }.orEmpty()
                val until = s.endsAtMillis?.let { SimpleDateFormat("HH:mm", Locale("ru")).format(Date(it)) } ?: ""
                Row(
                    Modifier
                        .background(Orda.colors.surface, RoundedCornerShape(Radius.md))
                        .border(2.dp, color, RoundedCornerShape(Radius.md))
                        .padding(start = Spacing.md, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Icon(Icons.Filled.Timer, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                    Column {
                        Text(s.title, color = Orda.colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text("до $until", color = Orda.colors.textDim, fontSize = 12.sp)
                    }
                    Text(if (over) "вышло" else left(rest), color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (timed.isNotEmpty()) {
                        Row(
                            Modifier.background(Orda.colors.surfaceRaised, RoundedCornerShape(10.dp)).clickable { extending = s }.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = Orda.colors.text, modifier = Modifier.size(16.dp))
                            Text("Продлить", color = Orda.colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Row(
                        Modifier.background(Orda.colors.surfaceRaised, RoundedCornerShape(10.dp)).clickable { onFinish(s.id) }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, tint = Orda.colors.text, modifier = Modifier.size(16.dp))
                        Text("Завершить", color = Orda.colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    extending?.let { session ->
        val service = services.firstOrNull { it.id == session.serviceId }
        val timed = service?.variants?.filter { (it.durationMinutes ?: 0) > 0 }.orEmpty()
        AlertDialog(
            onDismissRequest = { extending = null },
            title = { Text("Продлить: ${session.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    timed.forEach { v ->
                        TextButton(
                            onClick = {
                                if (service != null) onExtend(service, v, session)
                                extending = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("+ ${v.name} · ${money(v.price)}") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { extending = null }) { Text("Отмена") } },
        )
    }
}
