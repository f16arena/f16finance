package kz.ordaops.android.feature.business.team

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing
import kz.ordaops.android.feature.operator.shift.PillSegment as FormPill

// ── Сотрудники (административная команда) ────────────────────────────────────

/** Административный состав: список, карточка с окладом, выплатами и доступом. Порт `StaffScreen`. */
@Composable
fun StaffScreen() {
    val env = LocalBusiness.current
    val vm = rememberResource("staff", Unit, { TeamRequests.staff }, StaffList::from)
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showInactive by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val canAdd = env.can("staff.create")

    val selected = state.data?.staff?.firstOrNull { it.id == selectedId }
    if (selected != null) {
        BackHandler { selectedId = null }
        StaffDetail(selected, state.data!!)
    } else {
        StateContainer(
            state = state, onRetry = vm::reload,
            isEmpty = { it.staff.isEmpty() },
            emptyTitle = "Сотрудников нет", emptyMessage = "Здесь появится административная команда.",
        ) { list ->
            val members = remember(list, showInactive) { list.staff.filter { showInactive || it.isActive }.sortedForStaff() }
            OwnerScreenColumn {
                Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Switch(checked = showInactive, onCheckedChange = { showInactive = it })
                    Text("С уволенными", color = Orda.colors.textMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (canAdd) {
                        IconButton(onClick = { adding = true }, modifier = Modifier.background(Orda.colors.brand, CircleShape)) {
                            Icon(Icons.Filled.PersonAdd, "Новый сотрудник", tint = Color.White)
                        }
                    }
                }
                OwnerSection("Сотрудники", trailing = { Text("${members.size}", color = Orda.colors.textDim, fontSize = 14.sp) }) {
                    if (members.isEmpty()) TeamHint("Действующих сотрудников нет.")
                    members.forEachIndexed { i, m ->
                        if (i > 0) TeamDivider()
                        StaffRow(m, list.paidThisMonth(m.id)) { selectedId = m.id }
                    }
                }
            }
        }
    }
    if (adding) AddStaffSheet(onDismiss = { adding = false }, onDone = { vm.refreshNow() })
}

@Composable
private fun StaffRow(member: StaffMember, paid: Double, onClick: () -> Unit) {
    val c = Orda.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        PersonInitial(member.fullName, member.isActive)
        Column(Modifier.weight(1f)) {
            Text(member.fullName, color = if (member.isActive) c.text else c.textDim, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(member.roleLabel, color = c.textDim, fontSize = 13.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(money(member.monthlySalary), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (member.monthlySalary > 0) {
                Text("выплачено ${money(paid)}", color = if (paid >= member.monthlySalary) c.positive else c.textDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StaffDetail(member: StaffMember, list: StaffList) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    var confirmReset by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val c = Orda.colors
    val paid = list.paidThisMonth(member.id)
    val payments = list.payments.filter { it.staffId == member.id }.sortedByDescending { it.payDate.orEmpty() }
    val canSendAccess = env.can("staff.reset_password")

    OwnerScreenColumn {
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.md), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PersonInitial(member.fullName, member.isActive, 84)
            Text(member.fullName, color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(member.roleLabel, color = c.textMuted, fontSize = 15.sp)
            Text(if (member.isActive) "работает" else "не работает", color = if (member.isActive) c.positive else c.textDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (canSendAccess) {
            OwnerSection("Доступ") {
                if (!member.email.isNullOrEmpty()) Text(member.email, color = c.textDim, fontSize = 14.sp)
                else TeamHint("У сотрудника не заполнена почта — письмо отправить некуда.")
                result?.let { Text(it, color = c.positive, fontSize = 13.sp) }
                TeamError(action.error)
                TeamActionRow(Icons.Filled.Email, "Отправить доступ на почту", c.info, loading = action.busy) {
                    if (member.email.isNullOrEmpty() || !member.isActive) {
                        action.error = if (!member.isActive) "Уволенному сотруднику доступ не отправляется." else "У сотрудника не заполнена почта."
                    } else confirmReset = true
                }
            }
        }
        HeroNumberCard(
            title = "Осталось выплатить", value = maxOf(member.monthlySalary - paid, 0.0), change = null, changeCaption = "",
            footer = listOf("Начислено" to money(member.monthlySalary), "Выплачено" to money(paid)),
            colors = HeroColors.Accent, onClick = {},
        )
        if (!member.phone.isNullOrEmpty() || !member.email.isNullOrEmpty()) {
            OwnerSection("Контакты") {
                if (!member.phone.isNullOrEmpty()) TeamInfoRow(Icons.Filled.Phone, Color(0xFF10B981), "Телефон", member.phone)
                if (!member.email.isNullOrEmpty()) TeamInfoRow(Icons.Filled.Email, Color(0xFF3B82F6), "Почта", member.email)
            }
        }
        OwnerSection("Выплаты", trailing = { if (payments.isNotEmpty()) Text("${payments.size}", color = c.textDim, fontSize = 13.sp) }) {
            if (payments.isEmpty()) TeamHint("Выплат ещё не было")
            payments.take(20).forEachIndexed { i, p ->
                if (i > 0) TeamDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    TintedIcon(Icons.Filled.Payments, Color(0xFF10B981), 40, corner = 12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(WeekMath.parse(p.payDate)?.let { "${WeekMath.dayMonth(it)} ${it.year}" } ?: "—", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        p.comment?.takeIf { it.isNotEmpty() }?.let { Text(it, color = c.textDim, fontSize = 12.sp, maxLines = 1) }
                    }
                    Text(money(p.amount), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            "Отправить письмо?",
            "Если вход уже есть, сотрудник задаст новый пароль по ссылке — старый перестанет работать.",
            "Отправить",
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                // Один маршрут на оба случая: если входа нет — сервер шлёт приглашение, если есть — ссылку на смену пароля.
                action.launch(scope, onSuccess = {}) { result = env.api.sendStaffAccessEmail(member.id, invite = false) }
            },
        )
    }
}

/** Приём нового сотрудника. Оклад обязателен: без него человек не попадёт ни в одну ведомость. */
@Composable
private fun AddStaffSheet(onDismiss: () -> Unit, onDone: suspend () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    var fullName by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("other") }
    var salary by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    // Управляющего и бухгалтера назначает только владелец.
    val canAssignAdmin = env.resolver.session.staffRole == "owner" || env.resolver.isAllAccess
    val roles = buildList {
        add("other" to "Сотрудник")
        if (canAssignAdmin) { add("manager" to "Управляющий"); add("accountant" to "Бухгалтер") }
    }
    val amount = FormChecks.amount(salary)
    val problem = when {
        fullName.isBlank() -> "Укажите имя и фамилию"
        amount <= 0 -> "Укажите оклад — без него сервер сотрудника не примет"
        else -> null
    }
    OrdaSheet("Новый сотрудник", onDismiss = onDismiss) {
        TeamField("Имя и фамилия", fullName, { fullName = it }, placeholder = "Айгерим Сатыбалдиева")
        Text("Должность", color = Orda.colors.textDim, fontSize = 13.sp)
        FormPill(roles, role) { role = it }
        if (!canAssignAdmin) TeamHint("Управляющего и бухгалтера назначает владелец.")
        TeamField("Оклад за месяц", salary, { salary = it.filter { ch -> ch.isDigit() } }, placeholder = "0", keyboard = KeyboardType.Number)
        TeamHint("Без оклада сотрудник не попадёт в ведомость — сервер такого не примет.")
        TeamField("Телефон", phone, { phone = it }, keyboard = KeyboardType.Phone)
        TeamField("Почта", email, { email = it }, keyboard = KeyboardType.Email)
        TeamHint("Почта нужна для входа в систему: приглашение отправляется на неё. Без почты карточка заведётся, но доступа у человека не будет.")
        TeamError(action.error)
        SubmitButton("Принять на работу", action.busy, problem) {
            action.launch(scope, onSuccess = { onDone(); onDismiss() }) {
                env.api.createStaff(fullName.trim(), role, amount, phone.trim(), email.trim())
            }
        }
    }
}
