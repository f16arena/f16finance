package kz.ordaops.android.feature.business

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.BuildConfig
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load
import kz.ordaops.android.feature.operator.money.DeleteAccountCard
import kz.ordaops.android.feature.operator.money.HeroCard
import kz.ordaops.android.feature.operator.money.LinkRow
import kz.ordaops.android.feature.operator.money.SoftDivider
import kz.ordaops.android.feature.operator.money.SubScreen
import kz.ordaops.android.feature.operator.money.TintedIcon
import kz.ordaops.android.ui.components.CookieShape
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.theme.Navy
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

private enum class ProfileRoute { Notifications }

/**
 * Профиль владельца и сотрудника — как профиль в банковском приложении: кто я,
 * доступ тремя цифрами, настройки строками с иконками, выход. Порт
 * `BusinessProfileScreen` (`ProfileScreen.swift`).
 *
 * Пропущено: «Мои данные» (контакты и фото), карточки «Моя зарплата» и «Мои
 * задачи», оформление и крупный шрифт (Android берёт тему и масштаб из системы),
 * запрос биометрии при возврате в приложение.
 */
@Composable
fun BusinessProfileScreen(env: BusinessEnv) {
    val context = LocalContext.current
    var route by rememberSaveable { mutableStateOf<ProfileRoute?>(null) }
    var changingPassword by rememberSaveable { mutableStateOf(false) }
    var confirmingLogout by rememberSaveable { mutableStateOf(false) }
    val c = Orda.colors
    val session = env.resolver.session

    if (route == ProfileRoute.Notifications) {
        SubScreen("Уведомления", onBack = { route = null }) { NotificationPrefsContent(env.api) }
        return
    }

    OwnerScreenColumn {
        val name = session.displayName ?: "Пользователь"
        Box(Modifier.padding(top = Spacing.md)) {
            HeroCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Box(
                        Modifier.size(84.dp).background(Color.White, CookieShape).border(3.dp, c.brandBright, CookieShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(BizFormat.initials(name, "?"), color = Navy, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                    session.roleLabel?.takeIf { it.isNotEmpty() }?.let { label ->
                        Text(
                            label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.background(Color.White.copy(alpha = 0.16f), androidx.compose.foundation.shape.CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        // Доступ — тремя цифрами в ряд, а не табличкой.
        val groups = remember(env.access) { Services.groups(env.access) }
        Row(
            Modifier.fillMaxWidth().background(c.surface, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccessStat(groups.size.toString(), "разделов", Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(36.dp).background(c.border))
            AccessStat(groups.sumOf { it.items.size }.toString(), "страниц", Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(36.dp).background(c.border))
            AccessStat(if (env.resolver.isAllAccess) "Полный" else "Частичный", "доступ", Modifier.weight(1f))
        }

        OwnerSection("Аккаунт") {
            LinkRow(Icons.Filled.Key, Color(0xFFD97706), "Сменить пароль") { changingPassword = true }
            SoftDivider()
            LinkRow(Icons.Filled.Notifications, c.negative, "Уведомления", "что присылать") { route = ProfileRoute.Notifications }
        }

        OwnerSection("О приложении") {
            LinkRow(Icons.Filled.PrivacyTip, Navy.takeIf { !c.isDark } ?: c.textMuted, "Политика конфиденциальности") { NativePage.openWeb(context, "/privacy") }
            SoftDivider()
            LinkRow(Icons.AutoMirrored.Filled.Article, Navy.takeIf { !c.isDark } ?: c.textMuted, "Условия использования") { NativePage.openWeb(context, "/terms") }
            SoftDivider()
            Row(Modifier.padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                kz.ordaops.android.ui.components.OrdaMark(size = 40.dp, ringColor = if (c.isDark) Color.White else Navy)
                Column {
                    Text("ORDA CONTROL", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = c.textDim, fontSize = 13.sp)
                }
            }
        }

        OrdaCard {
            Row(
                Modifier.fillMaxWidth().pressScale().clickable { confirmingLogout = true }.padding(vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = c.negative)
                Text("Выйти из аккаунта", color = c.negative, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        DeleteAccountCard(api = env.api, signOut = env.signOut)
    }

    if (changingPassword) ChangePasswordDialog(env.api) { changingPassword = false }
    if (confirmingLogout) {
        AlertDialog(
            onDismissRequest = { confirmingLogout = false },
            title = { Text("Выйти из аккаунта?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLogout = false
                    env.signOut()
                }) { Text("Выйти", color = c.negative) }
            },
            dismissButton = { TextButton(onClick = { confirmingLogout = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun AccessStat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = Orda.colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Orda.colors.textDim, fontSize = 12.sp)
    }
}

// ── Смена пароля ─────────────────────────────────────────────────────────────

/** Что мешает сменить пароль; null — можно отправлять. Правила как в ChangePasswordSheet.swift. */
fun passwordProblem(current: String, next: String, repeated: String): String? = when {
    current.isEmpty() -> "Введите текущий пароль"
    next.length < 8 -> "Новый пароль — не короче 8 символов"
    next == current -> "Новый пароль должен отличаться от текущего"
    repeated != next -> "Пароли не совпадают"
    else -> null
}

/**
 * Смена своего пароля. Текущий спрашиваем обязательно: телефон бывает
 * разблокирован и лежит на стойке. `POST /api/auth/change-password`.
 */
@Composable
private fun ChangePasswordDialog(api: ApiClient, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val problem = passwordProblem(current, next, repeated)
    val transform: VisualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (done) "Пароль изменён" else "Сменить пароль") },
        text = {
            if (done) {
                Text("Следующий вход — уже с новым паролем. На других устройствах вход останется прежним, пока не выйдете.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(current, { current = it }, label = { Text("Текущий пароль") }, singleLine = true, visualTransformation = transform)
                    OutlinedTextField(next, { next = it }, label = { Text("Новый пароль (от 8 символов)") }, singleLine = true, visualTransformation = transform)
                    OutlinedTextField(repeated, { repeated = it }, label = { Text("Ещё раз") }, singleLine = true, visualTransformation = transform)
                    // Несовпадение показываем сразу: набирают вслепую, и промах во втором поле — самая частая причина «не сохраняется».
                    if (repeated.isNotEmpty() && repeated != next) Text("Пароли не совпадают.", color = Orda.colors.warning, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(reveal, { reveal = it }, colors = SwitchDefaults.colors(checkedTrackColor = Orda.colors.brand))
                        Text("Показать пароли", modifier = Modifier.padding(start = Spacing.sm), fontSize = 14.sp)
                    }
                    error?.let { ErrorBanner(it) }
                }
            }
        },
        confirmButton = {
            if (done) {
                TextButton(onClick = onDismiss) { Text("Готово") }
            } else {
                TextButton(
                    enabled = !busy && problem == null,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                api.sendUnit(
                                    ApiRequest.json(
                                        "/api/auth/change-password",
                                        buildJsonObject { put("current_password", current); put("new_password", next) },
                                    ),
                                )
                                done = true
                            } catch (e: Exception) {
                                error = e.userMessage()
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(if (busy) "Сохраняем…" else "Сменить") }
            }
        },
        dismissButton = { if (!done) TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
    )
}

// ── Уведомления ──────────────────────────────────────────────────────────────

/** Ответ `GET /api/me/notification-prefs`. */
@Serializable
data class NotificationPrefs(val prefs: List<Pref> = emptyList(), val events: List<String> = emptyList()) {
    @Serializable
    data class Pref(val channel: String = "", @SerialName("event_type") val eventType: String = "", val enabled: Boolean = true)

    /** Включено ли событие. По умолчанию да: человек не настраивал — значит получает всё, как и раньше. */
    fun isEnabled(event: String, channel: String = "push"): Boolean =
        prefs.firstOrNull { it.eventType == event && it.channel == channel }?.enabled ?: true
}

/** Человеческие названия событий — те же слова, что видит человек в жизни. */
object NotificationEventLabels {
    fun title(event: String): String = when (event) {
        "team_chat_message" -> "Сообщения в командном чате"
        "dm" -> "Личные сообщения"
        "announcement" -> "Объявления"
        "mention" -> "Когда упомянули меня"
        "shift_assigned" -> "Назначили смену"
        "shift_changed" -> "Смену перенесли"
        "task_assigned" -> "Поручили задачу"
        "task_commented" -> "Ответили в задаче"
        "debt_overdue" -> "Долг просрочен"
        "debt_added" -> "Новый долг"
        "birthday" -> "Дни рождения"
        "holiday" -> "Праздники"
        "news_post" -> "Новости"
        else -> event
    }

    /** Разделы: без них тринадцать переключателей читаются как список без порядка. */
    fun group(event: String): String = when (event) {
        "team_chat_message", "dm", "announcement", "mention" -> "Общение"
        "shift_assigned", "shift_changed" -> "Смены"
        "task_assigned", "task_commented" -> "Задачи"
        "debt_overdue", "debt_added" -> "Деньги"
        else -> "Прочее"
    }

    val order = listOf("Общение", "Смены", "Задачи", "Деньги", "Прочее")
}

internal class NotificationPrefsViewModel(private val api: ApiClient) : ViewModel() {
    private val _state = MutableStateFlow(Loadable<NotificationPrefs>())
    val state: StateFlow<Loadable<NotificationPrefs>> = _state.asStateFlow()

    /** Что сейчас переключается — чтобы не дать нажать дважды подряд. */
    private val _saving = MutableStateFlow<Set<String>>(emptySet())
    val saving: StateFlow<Set<String>> = _saving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val request = ApiRequest("/api/me/notification-prefs")
            _state.load(cached = { api.cached<NotificationPrefs>(request) }, fresh = { api.send<NotificationPrefs>(request) })
        }
    }

    fun toggle(event: String, value: Boolean) {
        viewModelScope.launch {
            _saving.update { it + event }
            try {
                api.sendUnit(
                    ApiRequest.json(
                        "/api/me/notification-prefs",
                        buildJsonObject { put("channel", "push"); put("eventType", event); put("enabled", value) },
                    ),
                )
                // Перечитываем, а не правим на месте: настройка живёт на сервере, и расхождение экрана с правдой хуже секунды ожидания.
                _error.value = null
                val request = ApiRequest("/api/me/notification-prefs")
                _state.value = Loadable(data = api.send<NotificationPrefs>(request))
            } catch (e: Exception) {
                _error.value = e.userMessage()
            } finally {
                _saving.update { it - event }
            }
        }
    }
}

/** Что присылать на телефон: переключатели по разделам. Порт `NotificationPrefsScreen.swift`. */
@Composable
private fun NotificationPrefsContent(api: ApiClient) {
    val vm: NotificationPrefsViewModel = viewModel(factory = viewModelFactory { initializer { NotificationPrefsViewModel(api) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.load() }
    val c = Orda.colors
    StateContainer(state = state, onRetry = vm::load) { prefs ->
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            OwnerFootnote("Выключенное сюда не приходит. Всё остальное — как раньше.")
            error?.let { ErrorBanner(it) }
            NotificationEventLabels.order.forEach { group ->
                val events = prefs.events.filter { NotificationEventLabels.group(it) == group }
                if (events.isEmpty()) return@forEach
                Text(group, color = c.textDim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                OrdaCard {
                    events.forEachIndexed { index, event ->
                        if (index > 0) SoftDivider()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            TintedIcon(groupIcon(group), groupTint(group), 36, corner = 18.dp)
                            Text(NotificationEventLabels.title(event), color = c.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = prefs.isEnabled(event),
                                onCheckedChange = { vm.toggle(event, it) },
                                enabled = event !in saving,
                                colors = SwitchDefaults.colors(checkedTrackColor = c.positive),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun groupIcon(group: String) = when (group) {
    "Общение" -> Icons.Filled.Forum
    "Смены" -> Icons.Filled.CalendarMonth
    "Задачи" -> Icons.Filled.Checklist
    "Деньги" -> Icons.Filled.Payments
    else -> Icons.Filled.Notifications
}

private fun groupTint(group: String) = when (group) {
    "Общение" -> Color(0xFF3B82F6)
    "Смены" -> Color(0xFF8B5CF6)
    "Задачи" -> Color(0xFFF97316)
    "Деньги" -> Color(0xFF10B981)
    else -> Color(0xFF64748B)
}
