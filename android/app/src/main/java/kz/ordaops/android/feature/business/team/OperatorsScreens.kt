package kz.ordaops.android.feature.business.team

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.ordaops.android.feature.business.HeroColors
import kz.ordaops.android.feature.business.HeroNumberCard
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.operator.money.TintedIcon
import kz.ordaops.android.feature.operator.shift.FormSection
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.operator.shift.PillSegment as FormPill

// ── Операторы ────────────────────────────────────────────────────────────────

/** Люди на точках: список с поиском, по тапу — карточка человека. Порт `OperatorsScreen`. */
@Composable
fun OperatorsScreen() {
    val env = LocalBusiness.current
    val vm = rememberResource("operators", Unit, { TeamRequests.operators }, TeamOperator::list)
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var showInactive by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val canCreate = env.can("operators.create")

    val selected = state.data?.firstOrNull { it.id == selectedId }
    if (selected != null) {
        BackHandler { selectedId = null }
        OperatorDetail(selected, onChanged = { vm.reload() })
    } else {
        StateContainer(
            state = state,
            onRetry = vm::reload,
            isEmpty = { it.isEmpty() },
            emptyTitle = "Операторов нет",
            emptyMessage = "Заведите операторов в разделе доступа.",
        ) { people ->
            val filtered = remember(people, search, showInactive) {
                people.filter { showInactive || it.isActive }
                    .filter {
                        search.isBlank() || it.displayName.contains(search, ignoreCase = true) ||
                            (it.position?.contains(search, ignoreCase = true) ?: false)
                    }
                    .sortedForRoster()
            }
            OwnerScreenColumn {
                Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = search, onValueChange = { search = it },
                        modifier = Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("Имя или должность") },
                        leadingIcon = { Icon(Icons.Filled.Search, null, tint = Orda.colors.textDim) },
                        shape = RoundedCornerShape(Radius.md),
                        colors = kz.ordaops.android.feature.operator.shift.ordaFieldColors(),
                    )
                    if (canCreate) {
                        IconButton(onClick = { adding = true }, modifier = Modifier.background(Orda.colors.brand, CircleShape)) {
                            Icon(Icons.Filled.Add, "Новый оператор", tint = Color.White)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Switch(checked = showInactive, onCheckedChange = { showInactive = it })
                    Text("С уволенными", color = Orda.colors.textMuted, fontSize = 14.sp)
                }
                if (filtered.isEmpty()) {
                    TeamHint(if (search.isBlank()) "Действующих операторов нет." else "Никого не найдено. Попробуйте другой запрос.")
                } else {
                    OwnerSection("Команда", trailing = { Text("${filtered.size}", color = Orda.colors.textDim, fontSize = 14.sp) }) {
                        filtered.forEachIndexed { index, person ->
                            if (index > 0) TeamDivider()
                            OperatorRow(person) { selectedId = person.id }
                        }
                    }
                }
            }
        }
    }
    if (adding) AddOperatorSheet(onDismiss = { adding = false }, onDone = { vm.refreshNow() })
}

/** Строка оператора: аватар, имя, оборот за 30 дней. */
@Composable
private fun OperatorRow(person: TeamOperator, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        PersonInitial(person.displayName, person.isActive, 42)
        Column(Modifier.weight(1f)) {
            Text(
                person.displayName, maxLines = 1, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                color = if (person.isActive) Orda.colors.text else Orda.colors.textDim,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                if (person.isAdminStaff) "${person.position ?: "Администрация"} · администрация" else person.position ?: "Оператор",
                color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(money(person.stats.totalTurnover), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                "${person.stats.totalShifts} ${plural(person.stats.totalShifts, "смена", "смены", "смен")}",
                color = Orda.colors.textDim, fontSize = 13.sp,
            )
        }
        if (!person.isActive) Icon(Icons.Filled.PersonOff, null, tint = Orda.colors.textDim, modifier = Modifier.size(16.dp))
    }
}

/** Карточка человека: показатели за 30 дней, доступ, контакты. */
@Composable
private fun OperatorDetail(person: TeamOperator, onChanged: () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    var confirmToggle by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf(false) }
    var promote by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    val canToggle = env.can("operators.toggle_active") && !person.isAdminStaff
    val canReset = env.can("operators.reset_password")
    val canDismiss = env.can("hr.dismiss") && !person.isAdminStaff
    val canLink = env.can("operators.edit")
    val canEditLogin = env.can("operators.edit_login")
    val canPromote = env.can("operators.promote")
    val c = Orda.colors

    OwnerScreenColumn {
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.md), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PersonInitial(person.displayName, person.isActive, 84)
            Text(person.displayName, color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(person.position ?: "Оператор", color = c.textMuted, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(if (person.isActive) "работает" else "не работает", color = if (person.isActive) c.positive else c.textDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (person.hasTelegram) Text("telegram", color = c.info, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (person.isAdminStaff) Text("администрация", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        HeroNumberCard(
            title = "Оборот за 30 дней",
            value = person.stats.totalTurnover,
            change = null,
            changeCaption = "",
            footer = listOf(
                "Смен" to "${person.stats.totalShifts}",
                "За смену" to money(person.stats.avgPerShift),
                "Долги" to money(person.stats.totalDebts),
            ),
            colors = if (person.isActive) HeroColors.Accent else listOf(Color(0xFF64748B), Color(0xFF475569)),
            onClick = {},
        )

        if (!person.hasStaffLink) StaffLinkCard(person, canLink, onLinked = onChanged)

        if (canToggle || canReset || canEditLogin || canPromote || canDismiss) {
            OwnerSection(if (person.isActive) "Доступ открыт" else "Доступ закрыт") {
                Text(
                    if (person.isActive) "Может входить в программу точки и в приложение" else "Войти не может. Смены и выплаты сохранены",
                    color = c.textDim, fontSize = 13.sp,
                )
                TeamError(action.error)
                if (person.isAdminStaff) {
                    TeamHint("Это учётка администрации: нужна для долгов кассы, поэтому её нельзя уволить или закрыть.")
                }
                if (canDismiss) {
                    TeamActionRow(
                        if (person.isActive) Icons.Filled.PersonOff else Icons.Filled.PersonAdd,
                        if (person.isActive) "Уволить" else "Восстановить",
                        if (person.isActive) c.negative else c.positive,
                    ) { dismissing = true }
                }
                if (canReset) TeamActionRow(Icons.Filled.Key, "Сбросить пароль", Color(0xFFF59E0B)) { resetting = true }
                if (canEditLogin) TeamActionRow(Icons.Filled.Badge, "Изменить логин", Color(0xFF3B82F6)) { login = true }
                if (canPromote) TeamActionRow(Icons.Filled.ArrowCircleUp, "Повысить в должности", Color(0xFF8B5CF6)) { promote = true }
                if (canToggle) {
                    TeamActionRow(
                        if (person.isActive) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        if (person.isActive) "Закрыть доступ" else "Открыть доступ",
                        if (person.isActive) c.negative else c.positive,
                        loading = action.busy,
                    ) { confirmToggle = true }
                }
            }
        }

        OwnerSection("Данные", trailing = { if (canLink) TeamPill("Изменить") { editing = true } }) {
            if (!person.phone.isNullOrEmpty()) TeamInfoRow(Icons.Filled.Phone, Color(0xFF10B981), "Телефон", person.phone)
            if (!person.username.isNullOrEmpty()) TeamInfoRow(Icons.Filled.Badge, Color(0xFF3B82F6), "Логин", person.username)
            if (!person.hireDate.isNullOrEmpty()) TeamInfoRow(Icons.Filled.CalendarMonth, Color(0xFF8B5CF6), "Принят", WeekMath.dayMonth(person.hireDate).let { d -> WeekMath.parse(person.hireDate)?.let { "$d ${it.year}" } ?: d })
            if (!person.lastLogin.isNullOrEmpty()) TeamInfoRow(Icons.Filled.Schedule, Color(0xFF14B8A6), "Был в системе", WeekMath.dateTime(person.lastLogin))
            if (person.stats.totalBonuses > 0) TeamInfoRow(Icons.Filled.Cake, Color(0xFFF59E0B), "Бонусы за 30 дней", money(person.stats.totalBonuses), c.positive)
        }
    }

    if (confirmToggle) {
        ConfirmDialog(
            title = if (person.isActive) "Закрыть доступ?" else "Открыть доступ?",
            message = if (person.isActive) "${person.name} не сможет войти ни в программу точки, ни в приложение. Смены и выплаты останутся."
            else "${person.name} снова сможет входить под своим логином.",
            confirm = if (person.isActive) "Закрыть" else "Открыть",
            destructive = person.isActive,
            onDismiss = { confirmToggle = false },
            onConfirm = {
                confirmToggle = false
                action.launch(scope, onSuccess = { onChanged() }) { env.api.setOperatorActive(person.id, !person.isActive) }
            },
        )
    }
    if (resetting) ResetPasswordSheet(person, onDismiss = { resetting = false }, onDone = onChanged)
    if (login) ChangeLoginSheet(person, onDismiss = { login = false }, onDone = onChanged)
    if (promote) PromoteSheet(person, onDismiss = { promote = false }, onDone = onChanged)
    if (editing) EditOperatorSheet(person.id, onDismiss = { editing = false }, onDone = onChanged)
    if (dismissing) {
        DismissSheet(
            kind = "operator", personId = person.id, personName = person.displayName, isDismissed = !person.isActive,
            onDismiss = { dismissing = false }, onDone = onChanged,
        )
    }
}

/** Нет карточки сотрудника — человек выпадает из системы; подсказка и кнопка «Завести карточку». */
@Composable
private fun StaffLinkCard(person: TeamOperator, canLink: Boolean, onLinked: () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    val c = Orda.colors
    Column(
        Modifier.fillMaxWidth().background(c.warning.copy(alpha = 0.10f), RoundedCornerShape(20.dp)).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(Icons.Filled.Warning, null, tint = c.warning)
            Text("Нет карточки сотрудника", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        TeamHint("Смены этого человека открываются без хозяина, а подтверждения регламентов не сохраняются — они привязаны к карточке.", c.textMuted)
        TeamError(action.error)
        if (canLink) {
            TeamPill(if (action.busy) "Заводим…" else "Завести карточку") {
                if (!action.busy) action.launch(scope, onSuccess = { onLinked() }) { env.api.linkOperatorToStaff(person.id) }
            }
            TeamHint("Заведём минимальную: имя и должность. Оклад и роль — это уже повышение, оно отдельно.")
        }
    }
}

// ── Листы ────────────────────────────────────────────────────────────────────

/** Оформление нового оператора: карточка, затем (по желанию) вход и отправка доступов. */
@Composable
private fun AddOperatorSheet(onDismiss: () -> Unit, onDone: suspend () -> Unit) {
    val env = LocalBusiness.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    var name by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var position by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var makeAccount by remember { mutableStateOf(true) }
    var username by remember { mutableStateOf("") }
    var usernameTouched by remember { mutableStateOf(false) }
    var telegram by remember { mutableStateOf("") }
    var account by remember { mutableStateOf<OperatorAccount?>(null) }
    var sent by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val canAccount = env.can("operators.create_account")
    val canTelegram = env.can("operators.send_credentials_telegram")
    val c = Orda.colors

    val problem = FormChecks.operatorProblem(name, phone, email)
        ?: if (makeAccount && canAccount) OperatorUsername.validationMessage(username) else null

    OrdaSheet(if (account == null) "Новый оператор" else "Доступы", onDismiss = onDismiss) {
        val created = account
        if (created != null) {
            Column(
                Modifier.fillMaxWidth().background(c.warning.copy(alpha = 0.10f), RoundedCornerShape(Radius.lg)).padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text("Запишите или отправьте сейчас", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                TeamHint("Пароль показывается один раз и больше нигде не хранится. Закроете экран — восстановить его будет нельзя, только сбросить.", c.textMuted)
                kz.ordaops.android.feature.operator.money.StatRow("Логин", created.username)
                kz.ordaops.android.feature.operator.money.StatRow("Пароль", created.password)
                TeamPill(if (copied) "Скопировано" else "Скопировать") {
                    copyToClipboard(context, "Доступ оператора", "Логин: ${created.username}\nПароль: ${created.password}")
                    copied = true
                }
            }
            if (canTelegram && telegram.isNotBlank()) {
                if (sent) {
                    Text("Отправлено в Telegram", color = c.positive, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                } else {
                    SubmitButton("Отправить в Telegram", action.busy, null) {
                        action.launch(scope, onSuccess = { sent = true }) {
                            env.api.sendOperatorCredentials(created.operatorId, telegram.trim(), created.username, created.password, name.trim())
                        }
                    }
                }
                TeamError(action.error)
            }
            TeamPill("Готово") { onDismiss() }
        } else {
            TeamField("Имя для смен и отчётов", name, {
                name = it
                // Пока логин не трогали руками, держим его в согласии с именем.
                if (!usernameTouched) username = OperatorUsername.suggestion(it)
            }, placeholder = "Как называть в системе")
            TeamField("Полное имя", fullName, { fullName = it }, placeholder = "Для документов")
            TeamField("Должность", position, { position = it }, placeholder = "Кассир, старший смены")
            TeamField("Телефон", phone, { phone = it }, placeholder = "+7 700 000 00 00", keyboard = KeyboardType.Phone)
            TeamField("Почта", email, { email = it }, placeholder = "Необязательно", keyboard = KeyboardType.Email)

            if (canAccount) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Сразу завести вход в программу", color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(checked = makeAccount, onCheckedChange = { makeAccount = it })
                }
                if (makeAccount) {
                    TeamField("Логин", username, { username = it.trim(); usernameTouched = true }, placeholder = "latinicey")
                    TeamHint(OperatorUsername.validationMessage(username) ?: "Вход в программу точки: логин и пароль. Пароль придумает сервер.",
                        if (OperatorUsername.validationMessage(username) != null) c.warning else c.textDim)
                    if (canTelegram) TeamField("Telegram для отправки доступов", telegram, { telegram = it }, placeholder = "chat id, необязательно")
                }
            }
            TeamError(action.error)
            SubmitButton("Оформить", action.busy, problem) {
                action.launch(scope) {
                    val trimmed = name.trim()
                    env.api.createOperator(trimmed, fullName, position, phone, email)
                    onDone()
                    if (makeAccount && canAccount) {
                        // Карточка уже заведена: если вход не создался — сказать прямо, а не делать вид, что не вышло ничего.
                        val list = TeamOperator.list(env.api.send<kotlinx.serialization.json.JsonElement>(TeamRequests.operators))
                        val createdOp = list.firstOrNull { it.name == trimmed }
                            ?: error("Оператор заведён, но вход создать не удалось: не нашли его в списке. Заведите вход из карточки.")
                        try {
                            account = env.api.createOperatorAccount(createdOp.id, username, trimmed)
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            throw IllegalStateException("Оператор заведён, но вход создать не удалось: ${e.userMessage()}")
                        }
                    } else {
                        onDismiss()
                    }
                }
            }
        }
    }
}

/** Правка карточки оператора («Редактировать» на сайте). */
@Composable
private fun EditOperatorSheet(operatorId: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    var card by remember { mutableStateOf<OperatorCard?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(operatorId) {
        try {
            card = OperatorCard.from(env.api.send<kotlinx.serialization.json.JsonElement>(TeamRequests.operatorCard(operatorId)))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.userMessage()
        }
    }

    OrdaSheet("Данные оператора", onDismiss = onDismiss) {
        val value = card
        when {
            value == null && loadError != null -> TeamError(loadError)
            value == null -> TeamHint("Загружаем карточку…")
            else -> {
                var draft by remember(value) { mutableStateOf(value) }
                TeamField("Имя для смен и отчётов", draft.name, { draft = draft.copy(name = it) })
                TeamField("Короткое имя", draft.shortName, { draft = draft.copy(shortName = it) })
                TeamField("Полное имя", draft.fullName, { draft = draft.copy(fullName = it) })
                TeamField("Должность", draft.position, { draft = draft.copy(position = it) })
                TeamField("Телефон", draft.phone, { draft = draft.copy(phone = it) }, keyboard = KeyboardType.Phone)
                TeamField("Почта", draft.email, { draft = draft.copy(email = it) }, keyboard = KeyboardType.Email)
                TeamField("Telegram (chat id)", draft.telegramChatId, { draft = draft.copy(telegramChatId = it) })
                TeamField("Принят (ГГГГ-ММ-ДД)", draft.hireDate, { draft = draft.copy(hireDate = it) })
                TeamField("День рождения (ГГГГ-ММ-ДД)", draft.birthDate, { draft = draft.copy(birthDate = it) })
                TeamField("Адрес", draft.address, { draft = draft.copy(address = it) })
                TeamField("Контакт на случай ЧП: имя", draft.emergencyName, { draft = draft.copy(emergencyName = it) })
                TeamField("Контакт на случай ЧП: телефон", draft.emergencyPhone, { draft = draft.copy(emergencyPhone = it) }, keyboard = KeyboardType.Phone)
                TeamField("Заметки", draft.notes, { draft = draft.copy(notes = it) }, singleLine = false)
                TeamError(action.error)
                SubmitButton("Сохранить", action.busy, if (draft.name.isBlank()) "Укажите имя" else null) {
                    action.launch(scope, onSuccess = { onDone(); onDismiss() }) { env.api.saveOperatorCard(draft) }
                }
            }
        }
    }
}

/** Сброс пароля: придумывает приложение, показывается один раз, при Telegram уходит туда же. */
@Composable
private fun ResetPasswordSheet(person: TeamOperator, onDismiss: () -> Unit, onDone: () -> Unit) {
    val env = LocalBusiness.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    var password by remember { mutableStateOf(generatePassword()) }
    var issued by remember { mutableStateOf(false) }
    var sentToTelegram by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val c = Orda.colors

    OrdaSheet(if (issued) "Пароль сменён" else "Сброс пароля", onDismiss = onDismiss) {
        Text(person.displayName, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (person.username != null) TeamHint("логин ${person.username}")
        if (person.authUserId == null) {
            TeamHint("У этого оператора нет входа в программу. Сначала выдайте ему учётную запись.", c.warning)
        } else {
            FormSection("Новый пароль") {
                Row(Modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(Radius.md)).padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Text(password, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.weight(1f))
                    if (!issued) TeamPill("Другой") { password = generatePassword() }
                }
                TeamHint("Придуман приложением: пароли, придуманные людьми, оказываются одинаковыми у всей смены.")
            }
            if (issued) {
                Text(
                    if (sentToTelegram) "${person.displayName} получит логин и пароль сообщением. На этом экране их больше не будет."
                    else "Запишите или скопируйте — второй раз пароль не показывается.",
                    color = c.positive, fontSize = 14.sp,
                )
                TeamPill(if (copied) "Скопировано" else "Скопировать") { copyToClipboard(context, "Пароль", password); copied = true }
            } else {
                TeamHint(if (person.hasTelegram) "Логин и пароль уйдут оператору в Telegram." else "Telegram не привязан — пароль передайте из рук в руки.")
            }
            notice?.let { TeamHint(it, c.warning) }
            TeamError(action.error)
            if (issued) {
                TeamPill("Готово") { onDismiss() }
            } else {
                SubmitButton("Сменить пароль", action.busy, null) {
                    val userId = person.authUserId ?: return@SubmitButton
                    action.launch(scope, onSuccess = { issued = true; onDone() }) {
                        env.api.resetOperatorPassword(userId, password)
                        // Отправка — best-effort: пароль уже сменён, молчать об этом нельзя.
                        val chat = person.telegramChatId
                        if (!chat.isNullOrEmpty()) {
                            try {
                                env.api.sendOperatorCredentials(person.id, chat, person.username.orEmpty(), password, person.displayName)
                                sentToTelegram = true
                            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                notice = "Пароль сменён, но в Telegram не ушёл — передайте его сами."
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Смена логина оператора. */
@Composable
private fun ChangeLoginSheet(person: TeamOperator, onDismiss: () -> Unit, onDone: () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    var username by remember { mutableStateOf("") }
    val clean = username.trim().lowercase()
    OrdaSheet("Логин", onDismiss = onDismiss) {
        Text(person.displayName, color = Orda.colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        TeamHint("Новый логин для входа")
        TeamField("Логин", username, { username = it }, placeholder = "например: aidos")
        TeamHint("Строчные латинские буквы и цифры. Пароль не меняется.")
        TeamError(action.error)
        SubmitButton("Сохранить логин", action.busy, if (clean.length < 3) "Логин короче трёх символов" else null) {
            action.launch(scope, onSuccess = { onDone(); onDismiss() }) { env.api.changeOperatorLogin(person.id, clean) }
        }
    }
}

/** Повышение оператора до сотрудника. Оклад необязателен — без него сервер оставит прежний расчёт. */
@Composable
private fun PromoteSheet(person: TeamOperator, onDismiss: () -> Unit, onDone: () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    var role by remember { mutableStateOf("other") }
    var salary by remember { mutableStateOf("") }
    OrdaSheet("Повышение", onDismiss = onDismiss) {
        Text(person.displayName, color = Orda.colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        FormPill(listOf("manager" to "Управляющий", "marketer" to "Маркетолог", "other" to "Сотрудник"), role) { role = it }
        TeamField("Оклад в месяц", salary, { salary = it.filter { ch -> ch.isDigit() } }, placeholder = "не менять", keyboard = KeyboardType.Number)
        TeamHint("Оператор станет сотрудником: смены и выплаты сохранятся, а доступ будет по должности.")
        TeamError(action.error)
        SubmitButton("Повысить", action.busy, null) {
            val amount = FormChecks.amount(salary)
            action.launch(scope, onSuccess = { onDone(); onDismiss() }) {
                env.api.promoteOperator(person.id, role, amount.takeIf { it > 0 })
            }
        }
    }
}

/** Увольнение или восстановление человека. Это закрытие доступа, не удаление: история остаётся. */
@Composable
fun DismissSheet(kind: String, personId: String, personName: String, isDismissed: Boolean, onDismiss: () -> Unit, onDone: () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    var reason by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("voluntary") }
    var cascade by remember { mutableStateOf(true) }
    val c = Orda.colors
    val types = listOf(
        "voluntary" to "По собственному", "mutual_agreement" to "По соглашению",
        "cause" to "За нарушение", "contract_end" to "Кончился договор", "other" to "Другое",
    )
    OrdaSheet(if (isDismissed) "Восстановление" else "Увольнение", onDismiss = onDismiss) {
        Text(personName, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (isDismissed) {
            TeamHint("Человек снова сможет входить и появится в графике. История смен и выплат не менялась — она сохранялась всё это время.")
        } else {
            FormSection("Причина увольнения") {
                types.forEach { (id, title) -> TeamChoiceRow(title, type == id) { type = id } }
            }
            TeamField("Комментарий", reason, { reason = it }, placeholder = "Что произошло", singleLine = false)
            TeamHint("Обязателен: через полгода «уволен» без объяснения не отличить от ошибки.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Закрыть и связанную запись", color = c.text, fontSize = 15.sp)
                    TeamHint("У человека бывают две записи — оператор и сотрудник. Закрыть надо обе, иначе одна дверь останется открытой.")
                }
                Switch(checked = cascade, onCheckedChange = { cascade = it })
            }
            TeamHint("Смены, выручка и ведомости останутся: это не удаление, а закрытие доступа. Отчётность прошлых недель не изменится.", c.warning)
        }
        TeamError(action.error)
        val problem = if (!isDismissed && reason.trim().length < 3) "Опишите причину — хотя бы три символа" else null
        SubmitButton(if (isDismissed) "Восстановить" else "Уволить", action.busy, problem) {
            action.launch(scope, onSuccess = { onDone(); onDismiss() }) {
                if (isDismissed) env.api.restorePerson(kind, personId)
                else env.api.dismissPerson(kind, personId, reason.trim(), type, cascade)
            }
        }
    }
}
