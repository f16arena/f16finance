package kz.ordaops.android.feature.operator.money

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kz.ordaops.android.OrdaApplication
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.theme.Orda

/**
 * Удаление аккаунта — как AccountSheet.swift: требование магазинов «вошёл в
 * приложении — можешь из него и уйти». Тот же `DELETE /api/me/account`.
 *
 * Пишем прямо, что произойдёт: рабочие записи (смены, выручка, ведомости)
 * остаются у точки — на них стоит бухгалтерия и деньги других людей.
 */
@Composable
internal fun DeleteAccountCard() {
    val env = LocalOperatorEnv.current
    val app = LocalContext.current.applicationContext as OrdaApplication
    val scope = rememberCoroutineScope()
    val c = Orda.colors
    var confirming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    OrdaCard {
        Text("Удаление аккаунта", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Вход перестанет работать, личные данные — телефон, почта, Telegram — будут стёрты. Отменить нельзя.",
            color = c.textMuted, fontSize = 13.sp,
        )
        Text(
            "Смены, выручка и зарплатные ведомости останутся: они принадлежат точке, а не учётной записи. Владелец получит уведомление.",
            color = c.textDim, fontSize = 13.sp,
        )
        error?.let { Text(it, color = c.negative, fontSize = 13.sp) }
        OutlinedButton(onClick = { confirming = true }, enabled = !deleting) {
            Text(if (deleting) "Удаляем…" else "Удалить аккаунт", color = c.negative)
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Удалить аккаунт?") },
            text = { Text("Вход перестанет работать сразу. Восстановить аккаунт сможет только владелец — заведя новый.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    if (deleting) return@TextButton
                    deleting = true
                    error = null
                    scope.launch {
                        try {
                            env.api.sendUnit(ApiRequest("/api/me/account", HttpMethod.DELETE))
                            // Сессия мертва: токен указывает на удалённого пользователя.
                            // И быстрый вход забываем — возвращаться больше некуда.
                            withContext(Dispatchers.IO) { app.quickVault.clear() }
                            env.signOut()
                        } catch (e: Exception) {
                            error = e.userMessage()
                        } finally {
                            deleting = false
                        }
                    }
                }) { Text("Удалить навсегда", color = c.negative) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Отмена") } },
        )
    }
}
