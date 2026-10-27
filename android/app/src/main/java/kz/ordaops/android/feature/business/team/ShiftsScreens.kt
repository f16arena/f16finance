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
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.ordaops.android.feature.business.HeroColors
import kz.ordaops.android.feature.business.HeroNumberCard
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.operator.money.PeriodStepper
import kz.ordaops.android.feature.operator.money.StatRow
import kz.ordaops.android.feature.operator.money.StatusChip
import kz.ordaops.android.feature.operator.money.TintedIcon
import kz.ordaops.android.feature.operator.shift.FormSection
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.time.LocalDate
import kz.ordaops.android.feature.operator.shift.PillSegment as FormPill

// ── График смен ──────────────────────────────────────────────────────────────

private data class ShiftSlot(val companyId: String, val companyName: String, val day: LocalDate)

/**
 * Недельный график по точкам: заявки на замену сверху, дни строками, по тапу на
 * день — поставить человека в смену. Порт `ScheduleWeekScreen`.
 */
@Composable
fun ShiftsScreen() {
    val env = LocalBusiness.current
    val week = rememberSaveable { mutableStateOf(WeekMath.currentWeekStart()) }
    val vm = rememberResource("schedule", week.value, TeamRequests::schedule, ShiftSchedule::from)
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ShiftSlot?>(null) }
    var deciding by remember { mutableStateOf<ShiftIssue?>(null) }
    val canEdit = env.can("shifts.create")
    val today = LocalDate.now()

    LaunchedEffect(week.value) { vm.setParam(week.value) }

    StateContainer(state = state, onRetry = vm::reload) { schedule ->
        OwnerScreenColumn {
            PeriodStepper(
                title = WeekMath.rangeTitle(week.value),
                caption = if (week.value == WeekMath.currentWeekStart()) "эта неделя" else null,
                onBack = { week.value = WeekMath.shifted(week.value, -1) },
                onForward = { week.value = WeekMath.shifted(week.value, 1) },
            )
            // Заявки выше сетки: если кто-то не выйдет в четверг, это важнее, чем остальные дни.
            val open = schedule.openRequests
            if (open.isNotEmpty()) {
                OwnerSection(if (open.size == 1) "Заявка на замену" else "Заявок на замену: ${open.size}") {
                    open.forEachIndexed { i, issue ->
                        if (i > 0) TeamDivider()
                        IssueRow(issue, schedule.companies.firstOrNull { it.id == issue.companyId }?.name ?: "Точка", enabled = canEdit) { deciding = issue }
                    }
                    if (!canEdit) TeamHint("Решение по заявке принимает тот, кто ведёт график.")
                }
            }
            if (schedule.companies.isEmpty()) {
                OwnerSection("График") { TeamHint("Точек не заведено") }
            } else {
                schedule.companies.forEach { company ->
                    OwnerSection(company.name) {
                        WeekMath.days(week.value).forEach { day ->
                            DayRow(
                                day, today,
                                schedule.shiftsOn(WeekMath.format(day), company.id),
                                onClick = if (canEdit) ({ editing = ShiftSlot(company.id, company.name, day) }) else null,
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { slot ->
        AssignShiftSheet(slot, onDismiss = { editing = null }, onDone = { vm.refreshNow() })
    }
    deciding?.let { issue ->
        val names = state.data?.replacementCandidates(issue).orEmpty()
        ResolveIssueSheet(issue, names, onDismiss = { deciding = null }, onDone = { vm.refreshNow() })
    }
}

@Composable
private fun IssueRow(issue: ShiftIssue, companyName: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Orda.colors
    Column(
        Modifier.fillMaxWidth().let { if (enabled) it.clickable(onClick = onClick) else it }.padding(vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(issue.operatorName, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(
            WeekMath.dayTitle(issue.shiftDate) + (if (issue.isNight) ", ночная" else ", дневная") + " · " + companyName,
            color = c.textDim, fontSize = 13.sp,
        )
        issue.reason?.takeIf { it.isNotEmpty() }?.let { Text(it, color = c.textMuted, fontSize = 13.sp) }
        issue.proposalLabel?.let { StatusChip(it, c.info) }
    }
}

@Composable
private fun DayRow(day: LocalDate, today: LocalDate, shifts: List<RosterShift>, onClick: (() -> Unit)?) {
    val c = Orda.colors
    val isToday = day == today
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(
            Modifier.size(46.dp).background(if (isToday) c.brand else c.surfaceRaised, CircleShape),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(WeekMath.weekdayShort(day).uppercase(), color = if (isToday) Color.White else c.textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("${day.dayOfMonth}", color = if (isToday) Color.White else c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (shifts.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.ErrorOutline, null, tint = c.warning, modifier = Modifier.size(16.dp))
                    Text("никого не поставили", color = c.warning, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                shifts.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Icon(
                            if (s.isNight) Icons.Filled.DarkMode else Icons.Filled.LightMode, null,
                            tint = if (s.isNight) Color(0xFF6366F1) else Color(0xFFF59E0B), modifier = Modifier.size(14.dp),
                        )
                        Text(s.operatorName, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Поставить человека в смену или освободить её (пустое имя — «в этот день никого»). */
@Composable
private fun AssignShiftSheet(slot: ShiftSlot, onDismiss: () -> Unit, onDone: suspend () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    val opsVm = rememberResource("operators", Unit, { TeamRequests.operators }, TeamOperator::list)
    val ops by opsVm.state.collectAsStateWithLifecycle()
    var shiftType by remember { mutableStateOf("day") }
    var name by remember { mutableStateOf("") }
    val canClear = env.can("shifts.delete")
    val people = ops.data.orEmpty().filter { it.isActive }

    OrdaSheet("Смена", onDismiss = onDismiss) {
        Text("${WeekMath.dayTitle(WeekMath.format(slot.day))} · ${slot.companyName}", color = Orda.colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        FormPill(listOf("day" to "Дневная", "night" to "Ночная"), shiftType) { shiftType = it }
        FormSection("Кто выходит") {
            // Пустое имя освобождает смену — так же это делает сайт.
            TeamChoiceRow("Никто — освободить смену", name.isEmpty()) { name = "" }
            if (people.isEmpty()) TeamHint(if (ops.isLoading) "Загружаем операторов…" else "Операторов нет")
            people.forEach { p -> TeamChoiceRow(p.displayName, name == p.displayName) { name = p.displayName } }
        }
        if (name.isEmpty() && !canClear) TeamHint("Снимать людей со смены может тот, кому выдано это право. Выберите человека, чтобы поставить его.")
        TeamHint("График виден оператору после публикации недели — её делают на сайте.")
        TeamError(action.error)
        SubmitButton(
            if (name.isEmpty()) "Освободить смену" else "Поставить в смену", action.busy,
            if (name.isEmpty() && !canClear) "Нет права освобождать смены" else null,
        ) {
            action.launch(scope, onSuccess = { onDone(); onDismiss() }) {
                env.api.saveShift(slot.companyId, WeekMath.format(slot.day), shiftType, name)
            }
        }
    }
}

/** Решение руководителя по заявке «не смогу выйти»: замена, оставить, снять или отклонить. */
@Composable
private fun ResolveIssueSheet(issue: ShiftIssue, candidates: List<String>, onDismiss: () -> Unit, onDone: suspend () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    // Предложение старшего подставляем как готовый ответ: чаще всего руководитель с ним и соглашается.
    var choice by remember { mutableStateOf(issue.leadAction ?: "replace") }
    var replacement by remember { mutableStateOf(issue.replacementName ?: candidates.firstOrNull()) }
    var note by remember { mutableStateOf("") }
    val c = Orda.colors
    val canSubmit = choice != "replace" || replacement != null

    OrdaSheet("Заявка", onDismiss = onDismiss) {
        Text(issue.operatorName, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(WeekMath.dayTitle(issue.shiftDate) + if (issue.isNight) ", ночная" else ", дневная", color = c.textDim, fontSize = 14.sp)
        issue.reason?.takeIf { it.isNotEmpty() }?.let { Text(it, color = c.textMuted, fontSize = 14.sp) }
        issue.proposalLabel?.let { label ->
            Column(Modifier.fillMaxWidth().background(c.info.copy(alpha = 0.10f), RoundedCornerShape(Radius.md)).padding(Spacing.md)) {
                Text(label, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                issue.leadNote?.takeIf { it.isNotEmpty() }?.let { Text(it, color = c.textMuted, fontSize = 13.sp) }
                issue.leadOperatorName?.let { Text(it, color = c.textDim, fontSize = 12.sp) }
            }
        }
        FormSection("Решение") {
            FormPill(listOf("replace" to "Замена", "keep" to "Оставить", "remove" to "Снять"), choice) { choice = it }
            TeamHint(
                when (choice) {
                    "keep" -> "Смена остаётся за человеком: причина не тянет на замену."
                    "remove" -> "Смена освободится, и в этот день на точке никого не будет."
                    else -> "Тот, кого выберете, встанет в график вместо него."
                },
            )
        }
        if (choice == "replace") {
            FormSection("Кто выйдет") {
                if (candidates.isEmpty()) {
                    TeamHint("На этой неделе на точке больше никого нет в графике. Поставьте человека в смену вручную или выберите «оставить».")
                }
                candidates.forEach { n -> TeamChoiceRow(n, replacement == n) { replacement = n } }
            }
        }
        TeamField("Комментарий", note, { note = it }, placeholder = "Увидит оператор", singleLine = false)
        TeamError(action.error)
        SubmitButton("Применить к графику", action.busy, if (!canSubmit) "Выберите, кто выйдет вместо него" else null) {
            action.launch(scope, onSuccess = { onDone(); onDismiss() }) {
                env.api.resolveShiftIssue(issue.id, "resolved", choice, replacement.takeIf { choice == "replace" }, note)
            }
        }
        // Отклонить — не то же самое, что «оставить смену»: причина не принята, и в истории это должно читаться именно так.
        TeamPill("Отклонить заявку", c.textMuted) {
            if (!action.busy) action.launch(scope, onSuccess = { onDone(); onDismiss() }) {
                env.api.resolveShiftIssue(issue.id, "dismissed", "keep", null, note)
            }
        }
    }
}

// ── Отчёты смен ──────────────────────────────────────────────────────────────

/** Журнал смен точек: выручка, расхождение кассы, переоткрытие. Порт `ShiftReportsScreen`. */
@Composable
fun ShiftReportsScreen() {
    val vm = rememberResource("shift-reports", Unit, { TeamRequests.shiftReports() }, ShiftReport::list)
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = state.data?.firstOrNull { it.id == selectedId }

    if (selected != null) {
        BackHandler { selectedId = null }
        ShiftReportDetail(selected, onChanged = { vm.reload() })
        return
    }
    StateContainer(
        state = state, onRetry = vm::reload,
        isEmpty = { it.isEmpty() }, emptyTitle = "Смен нет", emptyMessage = "Здесь появятся отчёты закрытых смен.",
    ) { shifts ->
        val open = shifts.count { it.isOpen }
        val discrepancies = shifts.count { !it.isOpen && isMaterialDiscrepancy(it.cashDifference) }
        OwnerScreenColumn {
            HeroNumberCard(
                title = "Выручка за смены",
                value = shifts.sumOf { it.totals.sales },
                change = null, changeCaption = "",
                footer = listOf("Смен" to "${shifts.size}", "Открыто сейчас" to "$open", "С расхождением" to "$discrepancies"),
                colors = HeroColors.Accent, onClick = {},
            )
            Text(
                if (discrepancies == 0) "касса везде сошлась" else "$discrepancies ${plural(discrepancies, "смена", "смены", "смен")} с расхождением",
                color = if (discrepancies == 0) Orda.colors.positive else Orda.colors.warning, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
            OwnerSection("Смены") {
                shifts.forEachIndexed { i, s ->
                    if (i > 0) TeamDivider()
                    ShiftReportRow(s) { selectedId = s.id }
                }
            }
        }
    }
}

/** Подпись и цвет расхождения: недостача красная, излишек янтарный, ноль зелёный. */
@Composable
private fun discrepancyColor(diff: Double): Color {
    val c = Orda.colors
    return when {
        kotlin.math.abs(diff) <= 1 -> c.positive
        diff < 0 -> c.negative
        else -> c.warning
    }
}

@Composable
private fun ShiftReportRow(shift: ShiftReport, onClick: () -> Unit) {
    val c = Orda.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        TintedIcon(if (shift.isOpen) Icons.Filled.PlayArrow else Icons.Filled.Check, if (shift.isOpen) c.info else c.positive, 40, corner = 12.dp)
        Column(Modifier.weight(1f)) {
            Text(shift.companyName ?: "Точка", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                listOfNotNull(shift.typeLabel, shift.openedAt?.let { WeekMath.dateTime(it) }, "${shift.totals.count} ${plural(shift.totals.count, "чек", "чека", "чеков")}").joinToString(" · "),
                color = c.textDim, fontSize = 13.sp, maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(money(shift.totals.sales), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val diff = shift.cashDifference
            // Расхождение подписью под суммой: недостача красным, излишек янтарным.
            if (diff != null && isMaterialDiscrepancy(diff)) {
                Text(signedMoney(diff), color = discrepancyColor(diff), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ShiftReportDetail(shift: ShiftReport, onChanged: () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    var reopening by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    val c = Orda.colors
    val canReopen = env.can("shifts-reports.reopen")
    // Сервер пускает только последнюю смену точки и только в течение суток после закрытия.
    val reopenable = !shift.isOpen && (WeekMath.hoursSince(shift.closedAt) ?: Long.MAX_VALUE) < 24

    OwnerScreenColumn {
        OwnerSection(shift.companyName ?: "Точка", trailing = { StatusChip(shift.statusLabel, if (shift.isOpen) c.info else c.positive) }) {
            Text(shift.typeLabel, color = c.textMuted, fontSize = 14.sp)
            TeamDivider()
            shift.openedAt?.let { StatRow("Открыта", WeekMath.dateTime(it)) }
            shift.closedAt?.let { StatRow("Закрыта", WeekMath.dateTime(it)) }
            if (shift.hasZReport) StatRow("Z-отчёт", "сформирован", c.positive)
        }
        if (canReopen && reopenable) {
            OwnerSection("Ошиблись при закрытии?") {
                TeamHint("Смену можно переоткрыть в течение суток после закрытия — добавить забытый чек или поправить кассу. Дальше правки идут через отчёты.")
                TeamError(action.error)
                TeamPill("Переоткрыть смену") { reopening = true }
            }
        }
        HeroNumberCard(
            title = "Продажи за смену", value = shift.totals.sales, change = null, changeCaption = "",
            footer = listOf("Наличные" to money(shift.totals.cash), "Kaspi" to money(shift.totals.kaspi), "Чеков" to "${shift.totals.count}"),
            colors = HeroColors.Accent, onClick = {},
        )
        OwnerSection("Касса") {
            StatRow("На начало смены", money(shift.openingCash))
            StatRow("Продано наличными", money(shift.totals.cash))
            TeamDivider()
            StatRow("Должно быть", money(shift.openingCash + shift.totals.cash))
            StatRow("Фактически", money(shift.closingCash), emphasized = true)
            val diff = shift.cashDifference
            if (diff != null) {
                TeamDivider()
                StatRow(
                    if (shift.cashReportDiff != null) "По отчёту кассы" else if (diff < 0) "Недостача" else "Излишек",
                    signedMoney(diff), discrepancyColor(diff), emphasized = true,
                )
                if (shift.cashReportDiff != null && isMaterialDiscrepancy(diff)) {
                    TeamHint(if (diff < 0) "Недостача: по итогам закрытия в кассе не хватает денег." else "Излишек: по итогам закрытия в кассе больше денег, чем по системе.", discrepancyColor(diff))
                }
            } else {
                TeamHint("Расхождение считается после закрытия")
            }
        }
    }

    if (reopening) {
        OrdaSheet("Переоткрыть смену?", onDismiss = { reopening = false }) {
            TeamHint("Смена снова станет открытой: в неё можно будет добавить чек и поправить кассу. Причина уйдёт в журнал.")
            TeamField("Причина", reason, { reason = it }, singleLine = false)
            TeamError(action.error)
            SubmitButton("Переоткрыть", action.busy, if (reason.trim().length < 3) "Причина обязательна: через месяц по журналу должно быть понятно, зачем смену открыли заново." else null) {
                action.launch(scope, onSuccess = { reopening = false; onChanged() }) { env.api.reopenShift(shift.id, reason.trim()) }
            }
        }
    }
}

// ── Дни рождения ─────────────────────────────────────────────────────────────

/** Ближайшие дни рождения команды. Порт `BirthdaysScreen`. */
@Composable
fun BirthdaysScreen() {
    val vm = rememberResource("birthdays", Unit, { TeamRequests.birthdays }, BirthdayList::from)
    val state by vm.state.collectAsStateWithLifecycle()
    StateContainer(
        state = state, onRetry = vm::reload,
        isEmpty = { it.items.isEmpty() },
        emptyIcon = Icons.Filled.Cake,
        emptyTitle = "Дат нет",
        emptyMessage = "Здесь появятся дни рождения команды.",
    ) { list ->
        val c = Orda.colors
        OwnerScreenColumn {
            if (list.today.isNotEmpty()) OwnerSection("Сегодня") { list.today.forEachIndexed { i, b -> if (i > 0) TeamDivider(); BirthdayRow(b) } }
            if (list.thisWeek.isNotEmpty()) OwnerSection("На этой неделе") { list.thisWeek.forEachIndexed { i, b -> if (i > 0) TeamDivider(); BirthdayRow(b) } }
            OwnerSection("Дальше") {
                if (list.later.isEmpty()) TeamHint("Больше дат в ближайший месяц нет")
                list.later.forEachIndexed { i, b -> if (i > 0) TeamDivider(); BirthdayRow(b) }
            }
            if (list.withoutBirthDate > 0) {
                Text(
                    "У ${list.withoutBirthDate} ${plural(list.withoutBirthDate, "человека", "человек", "человек")} дата не заполнена",
                    color = c.textDim, fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun BirthdayRow(person: Birthday) {
    val c = Orda.colors
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        PersonInitial(person.name, true, 36)
        Column(Modifier.weight(1f)) {
            Text(person.name, color = c.text, fontSize = 15.sp, maxLines = 1)
            Text(listOfNotNull(person.position, person.companyName).joinToString(" · "), color = c.textDim, fontSize = 13.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(person.whenLabel, color = if (person.isToday) c.brand else c.textMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            person.age?.let { Text("$it ${plural(it, "год", "года", "лет")}", color = c.textDim, fontSize = 13.sp) }
        }
    }
}
