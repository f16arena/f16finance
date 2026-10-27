package kz.ordaops.android.feature.business.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonElement
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.operator.money.StatRow
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// Карточки «про меня» на главной сотрудника и в профиле: своя зарплата и свои контакты.

/**
 * «Моя зарплата» — своя строка сводки за текущую половину месяца. Если строки
 * нет, говорим почему: «не связан с карточкой» и «оклад не заведён» — разные беды.
 */
@Composable
fun MySalaryCard() {
    val vm = rememberResource("staff-salary", Unit, { TeamRequests.staffSalary }, StaffSalarySummary::from)
    val state by vm.state.collectAsStateWithLifecycle()
    val summary = state.data
    val row = summary?.myRow
    val c = Orda.colors

    OwnerSection("Моя зарплата", trailing = {
        if (summary != null) Text("период ${summary.periodLabel}", color = c.textDim, fontSize = 13.sp)
    }) {
        when {
            row != null -> {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                    Text(money(row.toPay), color = c.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("к выплате", color = c.textMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
                // Спорят обычно не о полутора окладах, а о вычетах — их показываем поимённо.
                StatRow("Половина оклада", money(row.half))
                if (row.bonuses > 0.01) StatRow("Бонусы", signedMoney(row.bonuses), c.positive)
                if (row.fines > 0.01) StatRow("Штрафы", signedMoney(-row.fines), c.negative)
                if (row.debts > 0.01) StatRow("Долги", signedMoney(-row.debts), c.negative)
            }
            summary != null -> TeamHint(
                when (summary.meLinked) {
                    true -> "Оклад по вам не заведён. Если он должен быть — скажите владельцу."
                    false -> "Аккаунт не связан с карточкой сотрудника — попросите владельца привязать её, тогда здесь появится ваш расчёт."
                    null -> "Не удалось загрузить: на сайте ещё старая версия расчёта"
                },
                c.textMuted,
            )
            state.error != null -> TeamHint("Не удалось загрузить: ${state.error}", c.textMuted)
            else -> TeamHint("Загружаем расчёт…", c.textMuted)
        }
    }
}

/**
 * «Мои данные»: контакты правит сам человек. Имя, должность и ставка показаны, но
 * не правятся — на них считается зарплата. Загрузка фото пока только на сайте.
 */
@Composable
fun MyContactsContent() {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    var profile by remember { mutableStateOf<MyProfile?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var telegram by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    val c = Orda.colors

    LaunchedEffect(Unit) {
        try {
            val p = MyProfile.from(env.api.send<JsonElement>(TeamRequests.myProfile))
            profile = p
            phone = p.phone.orEmpty(); email = p.email.orEmpty(); telegram = p.telegramChatId.orEmpty()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.userMessage()
        }
    }

    OwnerScreenColumn {
        val p = profile
        when {
            p == null && loadError != null -> TeamError(loadError)
            p == null -> TeamHint("Загружаем…")
            else -> {
                val changed = phone != p.phone.orEmpty() || email != p.email.orEmpty() || (p.supportsTelegram && telegram != p.telegramChatId.orEmpty())
                val problem = when {
                    email.isNotEmpty() && !FormChecks.isEmailValid(email) -> "Проверьте почту"
                    phone.isNotEmpty() && !FormChecks.isPhoneValid(phone) -> "Проверьте телефон"
                    !changed -> "Нечего сохранять"
                    else -> null
                }
                OwnerSection("Мои данные") {
                    Text(p.fullName ?: "Без имени", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (!p.position.isNullOrEmpty()) {
                        StatRow("Должность", p.position)
                        TeamHint("Должность и имя меняет владелец — по ним считается зарплата.")
                    }
                    TeamField("Телефон", phone, { phone = it; saved = false }, placeholder = "+7 700 000 00 00", keyboard = KeyboardType.Phone)
                    TeamField("Почта", email, { email = it; saved = false }, placeholder = "name@example.com", keyboard = KeyboardType.Email)
                    if (p.supportsTelegram) TeamField("Telegram для уведомлений", telegram, { telegram = it; saved = false }, placeholder = "chat id")
                    if (saved) Text("Сохранено", color = c.positive, fontSize = 14.sp)
                    TeamError(action.error)
                    SubmitButton("Сохранить", action.busy, problem) {
                        action.launch(scope, onSuccess = {
                            saved = true
                            profile = p.copy(phone = phone, email = email, telegramChatId = if (p.supportsTelegram) telegram else p.telegramChatId)
                        }) {
                            env.api.saveMyProfile(phone.trim(), email.trim(), if (p.supportsTelegram) telegram.trim() else null)
                        }
                    }
                    TeamHint("Сменить фото пока можно на сайте.")
                }
            }
        }
    }
}
