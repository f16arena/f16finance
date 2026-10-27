package kz.ordaops.android.feature.business.team

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

/**
 * Данные для входа операторов (веб `/pass`). Те же действия, что на сайте: сменить
 * пароль, сменить логин, отправить логин и пароль в Telegram, скопировать.
 *
 * Пароли существуют только в памяти экрана и только те, что придуманы в этой сессии:
 * сервер хранит хеш, прочитать старый пароль нельзя — как и на сайте. Ничего не
 * пишем на диск и не логируем.
 */
@Composable
fun PassScreen() {
    val env = LocalBusiness.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm = rememberResource("operators", Unit, { TeamRequests.operators }, TeamOperator::list)
    val state by vm.state.collectAsStateWithLifecycle()
    val issued = remember { mutableStateMapOf<String, String>() }
    val shown = remember { mutableStateMapOf<String, Boolean>() }
    val sent = remember { mutableStateMapOf<String, Boolean>() }
    val bulk = rememberActionState()
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf<TeamOperator?>(null) }
    var loginFor by remember { mutableStateOf<TeamOperator?>(null) }

    val canReset = env.can("operators.reset_password")
    val canEditLogin = env.can("operators.edit_login")
    val canSend = env.can("operators.send_credentials_telegram")
    val canBulkSend = env.can("operators.bulk_send_credentials_telegram")
    val canCopy = env.can("operators.copy_profile_data") || env.can("pass.copy_credentials")
    val c = Orda.colors
    val loginUrl = kz.ordaops.android.BuildConfig.API_BASE_URL.trimEnd('/') + "/login"

    StateContainer(
        state = state,
        onRetry = vm::reload,
        isEmpty = { list -> list.none { it.isActive } },
        emptyTitle = "Нет активных операторов",
        emptyMessage = "Операторы появятся здесь, когда их заведут в разделе «Операторы».",
    ) { all ->
        val people = remember(all) { all.filter { it.isActive }.sortedForRoster() }
        OwnerScreenColumn {
            TeamHeroCard(
                title = "Данные для входа",
                value = "${people.size}",
                footer = listOf(
                    "С Telegram" to "${people.count { it.hasTelegram }}",
                    "Новых паролей" to "${issued.size}",
                ),
                caption = "активных операторов",
            )
            TeamHint("Пароль виден только сразу после смены: сервер хранит его в зашифрованном виде. Запишите или отправьте оператору.")
            notice?.let { TeamHint(it, c.positive) }
            TeamError(bulk.error)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (canReset) TeamPill(if (bulk.busy) "Генерируем…" else "Пароли всем") { if (!bulk.busy) confirmAll = true }
                if (canBulkSend) {
                    TeamPill("Всем в Telegram", tint = c.info) {
                        val targets = people.filter { issued[it.id] != null && it.hasTelegram }
                        if (targets.isEmpty()) {
                            notice = "Сначала сгенерируйте пароли. Без Telegram отправить нельзя."
                        } else {
                            bulk.launch(scope) {
                                var ok = 0
                                var fail = 0
                                for (p in targets) {
                                    try {
                                        env.api.sendOperatorCredentials(p.id, p.telegramChatId.orEmpty(), p.username.orEmpty(), issued[p.id].orEmpty(), p.displayName)
                                        sent[p.id] = true
                                        ok++
                                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        fail++
                                    }
                                    delay(300)
                                }
                                notice = "Отправлено: $ok, ошибок: $fail"
                            }
                        }
                    }
                }
                if (canCopy) {
                    TeamPill("Копировать всё", tint = c.textMuted) {
                        copyToClipboard(context, "Данные для входа", passClipboardText(people, issued, loginUrl))
                        notice = "Все данные скопированы"
                    }
                }
            }

            OwnerSection("Операторы") {
                people.forEachIndexed { index, p ->
                    if (index > 0) TeamDivider()
                    PassRow(
                        person = p,
                        password = issued[p.id],
                        visible = shown[p.id] == true,
                        sentToTelegram = sent[p.id] == true,
                        canReset = canReset,
                        canEditLogin = canEditLogin,
                        canSend = canSend,
                        canCopy = canCopy,
                        onToggle = { shown[p.id] = shown[p.id] != true },
                        onReset = { resetting = p },
                        onLogin = { loginFor = p },
                        onSend = {
                            bulk.launch(scope) {
                                env.api.sendOperatorCredentials(p.id, p.telegramChatId.orEmpty(), p.username.orEmpty(), issued[p.id].orEmpty(), p.displayName)
                                sent[p.id] = true
                                notice = "Данные отправлены: ${p.displayName}"
                            }
                        },
                        onCopy = {
                            copyToClipboard(context, "Данные для входа", passClipboardText(listOf(p), issued, loginUrl))
                            notice = "Скопировано: ${p.displayName}"
                        },
                    )
                }
            }
        }

        if (confirmAll) {
            ConfirmDialog(
                title = "Новые пароли всем?",
                message = "Каждому активному оператору с входом будет придуман новый пароль. Старые перестанут работать.",
                confirm = "Сгенерировать",
                destructive = true,
                onDismiss = { confirmAll = false },
                onConfirm = {
                    confirmAll = false
                    bulk.launch(scope) {
                        var ok = 0
                        var fail = 0
                        for (p in people) {
                            val userId = p.authUserId
                            if (userId == null) { fail++; continue }
                            val password = generatePassword()
                            try {
                                env.api.resetOperatorPassword(userId, password)
                                issued[p.id] = password
                                shown[p.id] = true
                                sent.remove(p.id)
                                ok++
                            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                fail++
                            }
                            delay(300)
                        }
                        notice = "Сгенерировано паролей: $ok, ошибок: $fail"
                    }
                },
            )
        }
        resetting?.let { ResetPasswordSheet(it, onDismiss = { resetting = null }, onDone = { vm.reload() }) }
        loginFor?.let { ChangeLoginSheet(it, onDismiss = { loginFor = null }, onDone = { vm.reload() }) }
    }
}

@Composable
private fun PassRow(
    person: TeamOperator,
    password: String?,
    visible: Boolean,
    sentToTelegram: Boolean,
    canReset: Boolean,
    canEditLogin: Boolean,
    canSend: Boolean,
    canCopy: Boolean,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    onLogin: () -> Unit,
    onSend: () -> Unit,
    onCopy: () -> Unit,
) {
    val c = Orda.colors
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            PersonInitial(person.displayName, person.isActive, 40)
            Column(Modifier.weight(1f)) {
                Text(person.displayName, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (person.authUserId == null) "Нет аккаунта входа" else "логин ${person.username ?: "—"}",
                    color = if (person.authUserId == null) c.warning else c.textMuted, fontSize = 13.sp,
                )
            }
            if (person.hasTelegram) Text("telegram", color = c.info, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (!person.phone.isNullOrEmpty()) Text(person.phone, color = c.textDim, fontSize = 13.sp)
        if (person.authUserId == null) {
            TeamHint("Создайте аккаунт оператора (суперадмин), затем можно задать логин и пароль.")
        } else {
            if (password != null) {
                Row(
                    Modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(Radius.md)).padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (visible) password else "••••••••", color = c.positive, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
                    )
                    TeamPill(if (visible) "Скрыть" else "Показать", tint = c.textMuted, onClick = onToggle)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (canReset) TeamPill(if (password == null) "Сменить пароль" else "Новый пароль", onClick = onReset)
                if (canEditLogin) TeamPill("Логин", tint = c.info, onClick = onLogin)
                if (password != null && canSend && person.hasTelegram) {
                    TeamPill(if (sentToTelegram) "Отправлено" else "В Telegram", tint = c.info, onClick = onSend)
                }
                if (password != null && canCopy) TeamPill("Копировать", tint = c.textMuted, onClick = onCopy)
            }
        }
    }
}

/**
 * Текст для передачи оператору, как «Копировать» на сайте. Пароль подставляется только
 * тот, что придуман в этой сессии, иначе точки.
 */
internal fun passClipboardText(people: List<TeamOperator>, passwords: Map<String, String>, loginUrl: String): String {
    val sb = StringBuilder("ДАННЫЕ ДЛЯ ВХОДА ОПЕРАТОРОВ\nСсылка для входа: $loginUrl\n\n")
    for (p in people) {
        sb.append(p.displayName).append('\n')
        sb.append("  Логин: ").append(p.username ?: "нет (аккаунт входа не создан)").append('\n')
        sb.append("  Пароль: ").append(passwords[p.id] ?: "••••••••").append('\n')
        sb.append("  Телефон: ").append(p.phone ?: "не указан").append('\n')
        sb.append("  Telegram: ").append(p.telegramChatId ?: "не указан").append('\n')
        sb.append("----\n")
    }
    return sb.toString()
}
