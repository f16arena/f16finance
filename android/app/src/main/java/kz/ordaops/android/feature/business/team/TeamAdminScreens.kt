package kz.ordaops.android.feature.business.team

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.ordaops.android.feature.business.HeroColors
import kz.ordaops.android.feature.business.HeroNumberCard
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.operator.money.StatRow
import kz.ordaops.android.feature.operator.money.TintedIcon
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

// ── Правила зарплаты ─────────────────────────────────────────────────────────

/**
 * Ставки смен по точкам, надбавка за стаж, история правок. Только просмотр:
 * правка ставок остаётся на сайте. Изменение ставки не трогает уже выплаченные
 * недели — прошлые смены считаются по своей версии. Порт `SalaryRulesScreen`.
 */
@Composable
fun SalaryRulesScreen() {
    val vm = rememberResource("salary-rules", Unit, { kz.ordaops.android.core.net.ApiRequest("/api/admin/salary-rules") }, SalaryRuleBook::from)
    val state by vm.state.collectAsStateWithLifecycle()
    StateContainer(
        state = state, onRetry = vm::reload,
        isEmpty = { it.rules.isEmpty() },
        emptyTitle = "Правил нет",
        emptyMessage = "Пока не задано ни одной ставки — смены считаются по значению по умолчанию.",
    ) { book ->
        val c = Orda.colors
        OwnerScreenColumn {
            HeroNumberCard(
                title = "Потолок смены",
                value = book.activeRules.maxOfOrNull { it.ceilingPerShift } ?: 0.0,
                change = null, changeCaption = "",
                footer = listOf(
                    "Правил действует" to "${book.activeRules.size}",
                    "Надбавка за стаж" to if (book.maxSeniorityPercent > 0) "до ${BizFormat.percent(book.maxSeniorityPercent)}" else "нет",
                ),
                colors = HeroColors.Accent, onClick = {},
            )
            Text("Самая высокая ставка среди действующих правил. Изменение ставки не пересчитывает уже выплаченные недели.", color = c.textDim, fontSize = 13.sp)

            if (book.activeTiers.isNotEmpty()) {
                OwnerSection("Надбавка за стаж") {
                    Text("Процент к ставке, общий для всех точек", color = c.textDim, fontSize = 13.sp)
                    book.activeTiers.forEach { tier ->
                        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            TintedIcon(Icons.Filled.CalendarMonth, Color(0xFF8B5CF6), 38)
                            Text("Отработал ${tier.tenureLabel}", color = c.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Text(BizFormat.percent(tier.bonusPercent, signed = true), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            val rules = book.rules.sortedWith(compareByDescending<SalaryRule> { it.isActive }.thenBy { it.companyCode }.thenBy { it.isNight })
            rules.forEach { rule -> SalaryRuleCard(rule, book) }

            if (book.history.isNotEmpty()) {
                OwnerSection("Кто менял ставки", trailing = { Text("последние правки", color = c.textDim, fontSize = 13.sp) }) {
                    book.history.take(12).forEach { change ->
                        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            PersonInitial(change.actorEmail ?: "Система", true, 36)
                            Column(Modifier.weight(1f)) {
                                Text(change.actorEmail ?: "Система", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(
                                    listOfNotNull(
                                        change.actionLabel, change.companyCode?.takeIf { it.isNotEmpty() }?.uppercase(),
                                        change.shiftType?.let { if (it == "night") "ночь" else "день" },
                                        change.createdAt?.let { WeekMath.dateTime(it) },
                                    ).joinToString(" · "),
                                    color = c.textDim, fontSize = 13.sp, maxLines = 1,
                                )
                            }
                            change.baseDelta?.let { Text(signedMoney(it), color = if (it > 0) c.negative else c.positive, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SalaryRuleCard(rule: SalaryRule, book: SalaryRuleBook) {
    val c = Orda.colors
    OwnerSection(
        book.companyName(rule.companyCode),
        trailing = {
            Text(
                if (!rule.isActive) "отключено" else if (rule.isNight) "ночь" else "день",
                color = if (!rule.isActive) c.textDim else if (rule.isNight) c.accent else c.warning,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
        },
    ) {
        if (!rule.isActive) Text(if (rule.isNight) "ночная смена" else "дневная смена", color = c.textDim, fontSize = 13.sp)
        rule.terms.forEach { term ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(term.text, color = c.textMuted, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(term.amount, color = if (term.isBonus) c.positive else c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        TeamDivider()
        StatRow("Максимум за смену", money(rule.ceilingPerShift), emphasized = true)
        rule.effectiveFrom?.let { from -> WeekMath.parse(from)?.let { Text("Действует с ${WeekMath.dayMonth(it)} ${it.year}", color = c.textDim, fontSize = 12.sp) } }
        val versions = book.versionHistory(rule.id)
        if (versions.size > 1) {
            TeamDivider()
            Text("Ставка по датам", color = c.textDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            versions.take(4).forEach { v ->
                Row(Modifier.fillMaxWidth()) {
                    Text(WeekMath.parse(v.effectiveFrom)?.let { "${WeekMath.dayMonth(it)} ${it.year}" } ?: "—", color = c.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(v.basePerShift?.let { money(it) } ?: "—", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ── Кадры ────────────────────────────────────────────────────────────────────

private enum class HrFilter(val title: String) { Working("Работают"), Dismissed("Уволены"), All("Все") }

/** Вся команда в одном списке — штат и операторы; карточка человека, восстановление уволенного. Порт `HRScreen`. */
@Composable
fun HrScreen() {
    val vm = rememberResource("hr", Unit, { kz.ordaops.android.core.net.ApiRequest("/api/admin/hr") }, HrPerson::list)
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf(HrFilter.Working.name) }
    var search by rememberSaveable { mutableStateOf("") }

    val selected = state.data?.firstOrNull { "${it.kind}:${it.id}" == selectedKey }
    if (selected != null) {
        BackHandler { selectedKey = null }
        HrPersonDetail(selected, onChanged = { vm.reload() })
        return
    }
    StateContainer(state = state, onRetry = vm::reload, isEmpty = { it.isEmpty() }, emptyTitle = "Никого нет", emptyMessage = "Здесь вся команда: и штат, и операторы точек.") { people ->
        val c = Orda.colors
        val filter = HrFilter.valueOf(mode)
        val items = remember(people, filter, search) {
            people.filter {
                when (filter) { HrFilter.Working -> it.isActive; HrFilter.Dismissed -> it.isDismissed; HrFilter.All -> true }
            }.filter {
                search.isBlank() || it.fullName.contains(search, true) || (it.position?.contains(search, true) ?: false) || (it.phone?.contains(search) ?: false)
            }.sortedForHr()
        }
        val recent = people.count { it.leftWithin(90) }
        OwnerScreenColumn {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SummaryCell("Работают", "${people.count { it.isActive }}", c.positive, Modifier.weight(1f))
                SummaryCell("Уволены", "${people.count { it.isDismissed }}", c.textMuted, Modifier.weight(1f))
                SummaryCell("Ушли за 90 дн.", "$recent", if (recent == 0) c.textMuted else c.warning, Modifier.weight(1f))
            }
            PillSegment(HrFilter.entries.map { it.name to it.title }, mode) { mode = it }
            OutlinedTextField(
                value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Имя, должность или телефон") },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.md),
                colors = kz.ordaops.android.feature.operator.shift.ordaFieldColors(),
            )
            if (items.isEmpty()) {
                TeamHint(if (filter == HrFilter.Dismissed) "Уволенных нет" else "Никого не найдено")
            } else {
                OwnerSection(filter.title, trailing = { Text("${items.size}", color = c.textDim, fontSize = 14.sp) }) {
                    items.forEachIndexed { i, p ->
                        if (i > 0) TeamDivider()
                        HrPersonRow(p) { selectedKey = "${p.kind}:${p.id}" }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCell(title: String, value: String, tint: Color, modifier: Modifier) {
    Column(modifier.background(Orda.colors.surface, androidx.compose.foundation.shape.RoundedCornerShape(Radius.lg)).padding(Spacing.md)) {
        Text(value, color = tint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(title, color = Orda.colors.textDim, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun HrPersonRow(p: HrPerson, onClick: () -> Unit) {
    val c = Orda.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        PersonInitial(p.fullName, !p.isDismissed)
        Column(Modifier.weight(1f)) {
            Text(p.fullName, color = if (p.isDismissed) c.textDim else c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(p.position ?: p.roleLabel, color = c.textDim, fontSize = 13.sp, maxLines = 1)
        }
        if (p.isDismissed) {
            Column(horizontalAlignment = Alignment.End) {
                Text("уволен", color = c.negative, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                WeekMath.parse(p.leftOn)?.let { Text("${WeekMath.dayMonth(it)} ${it.year}", color = c.textDim, fontSize = 12.sp) }
            }
        } else {
            val months = p.tenureMonths()
            if (months != null && months > 0) Text("$months ${plural(months, "месяц", "месяца", "месяцев")}", color = c.textDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HrPersonDetail(p: HrPerson, onChanged: () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    var confirming by remember { mutableStateOf(false) }
    val c = Orda.colors
    val canRestore = env.can("hr.restore")

    OwnerScreenColumn {
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.md), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PersonInitial(p.fullName, !p.isDismissed, 84)
            Text(p.fullName, color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(p.position ?: p.roleLabel, color = c.textMuted, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(if (p.isDismissed) "уволен" else "работает", color = if (p.isDismissed) c.negative else c.positive, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(if (p.isOperator) "оператор" else "штат", color = c.textDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (p.isHybrid) Text("и штат, и смены", color = c.info, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (p.isDismissed && canRestore) {
            OwnerSection("Человек вернулся?") {
                TeamHint("Восстановление вернёт доступ и сохранит стаж, историю смен и долги. Заводить заново — значит всё это потерять.", c.textMuted)
                TeamError(action.error)
                TeamActionRow(Icons.Filled.Restore, "Восстановить", c.positive, loading = action.busy) { confirming = true }
            }
        }
        if (p.isDismissed) {
            OwnerSection("Увольнение") {
                WeekMath.parse(p.leftOn)?.let { TeamInfoRow(Icons.Filled.CalendarMonth, c.negative, "Последний день", "${WeekMath.dayMonth(it)} ${it.year}", c.negative) }
                p.dismissalTypeLabel?.let { TeamInfoRow(Icons.Filled.Description, Color(0xFFF59E0B), "Основание", it) }
                p.dismissedByName?.let { TeamInfoRow(Icons.Filled.AccountBox, Color(0xFF3B82F6), "Оформил", it) }
                p.dismissalReason?.takeIf { it.isNotEmpty() }?.let { Text("«$it»", color = c.textMuted, fontSize = 15.sp) }
            }
        }
        OwnerSection("В компании") {
            WeekMath.parse(p.hireDate)?.let { TeamInfoRow(Icons.Filled.CalendarMonth, Color(0xFF8B5CF6), "Принят", "${WeekMath.dayMonth(it)} ${it.year}") }
            p.tenureMonths()?.let { TeamInfoRow(Icons.Filled.Schedule, Color(0xFF14B8A6), if (p.isDismissed) "Проработал" else "Стаж", "$it ${plural(it, "месяц", "месяца", "месяцев")}") }
            TeamInfoRow(Icons.Filled.AccountBox, Color(0xFF3B82F6), "Роль", p.roleLabel)
            p.monthlySalary?.takeIf { it > 0 }?.let { TeamInfoRow(Icons.Filled.AttachMoney, Color(0xFF10B981), "Оклад", money(it)) }
        }
        if (!p.phone.isNullOrEmpty() || !p.email.isNullOrEmpty()) {
            OwnerSection("Контакты") {
                p.phone?.takeIf { it.isNotEmpty() }?.let { TeamInfoRow(Icons.Filled.Phone, Color(0xFF10B981), "Телефон", it) }
                p.email?.takeIf { it.isNotEmpty() }?.let { TeamInfoRow(Icons.Filled.Email, Color(0xFF3B82F6), "Почта", it) }
            }
        }
        OwnerSection("Доступ в систему") {
            TeamInfoRow(Icons.Filled.Key, Color(0xFFF59E0B), "Учётная запись", if (p.hasLogin) "есть" else "нет", if (p.hasLogin) c.text else c.textDim)
            p.lastLogin?.let { TeamInfoRow(Icons.Filled.History, Color(0xFF14B8A6), "Последний вход", WeekMath.dateTime(it)) }
            // Уволенный с живым доступом — открытая дверь: он всё ещё может зайти в кассу и отчёты.
            if (p.isDismissed && p.hasLogin) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Icon(Icons.Filled.Warning, null, tint = c.negative)
                    Text("Уволен, но вход не закрыт", color = c.negative, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    if (confirming) {
        ConfirmDialog(
            "Восстановить ${p.fullName}?", "Вернутся вход в систему и назначения на точки. Долги и история останутся за человеком.", "Восстановить",
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                action.launch(scope, onSuccess = { onChanged() }) { env.api.restorePerson(p.kind, p.id) }
            },
        )
    }
}

// ── Структура команды ────────────────────────────────────────────────────────

/** Точки, кто на них работает, руководители и разрывы структуры. Порт `StructureScreen`. */
@Composable
fun StructureScreen() {
    val vm = rememberResource("structure", Unit, { kz.ordaops.android.core.net.ApiRequest("/api/admin/structure") }, TeamStructure::from)
    val state by vm.state.collectAsStateWithLifecycle()
    StateContainer(
        state = state, onRetry = vm::reload,
        isEmpty = { it.operators.isEmpty() && it.companies.isEmpty() },
        emptyIcon = Icons.Filled.AccountTree, emptyTitle = "Структуры нет", emptyMessage = "Здесь появятся точки, их смены и руководители.",
    ) { s ->
        val c = Orda.colors
        val orphans = s.unassignedOperators
        val leaderless = s.operatorsWithoutLead
        OwnerScreenColumn {
            HeroNumberCard(
                title = "Операторов в структуре", value = s.operators.size.toDouble(), change = null, changeCaption = "",
                footer = listOf("Точек" to "${s.companies.size}", "Без точки" to "${orphans.size}", "Без руководителя" to "${leaderless.size}"),
                colors = HeroColors.Accent, onClick = {},
            )
            Text(if (orphans.isEmpty()) "все прикреплены к точкам" else "есть разрывы — смотрите ниже", color = if (orphans.isEmpty()) c.positive else c.warning, fontSize = 13.sp)
            if (orphans.isNotEmpty()) GapCard("Не прикреплены к точке", "не попадут ни в график, ни в расчёт смены", c.warning, orphans)
            if (leaderless.isNotEmpty()) GapCard("Без руководителя", "за смену спросить не с кого", c.textDim, leaderless)

            s.companies.forEach { company ->
                val team = s.staffedBy(company.id).sortedWith(
                    compareByDescending<TeamOperator> { s.assignment(it.id, company.id)?.isSenior ?: false }.thenBy { it.displayName },
                )
                OwnerSection(company.name, trailing = {
                    Text(
                        listOfNotNull(company.code?.takeIf { it.isNotEmpty() }?.uppercase(), "${team.size} ${plural(team.size, "человек", "человека", "человек")}").joinToString(" · "),
                        color = c.textDim, fontSize = 13.sp,
                    )
                }) {
                    if (team.isEmpty()) TeamHint("На точке никого нет", c.warning)
                    team.forEach { m ->
                        val a = s.assignment(m.id, company.id)
                        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            PersonInitial(m.displayName, true, 36)
                            Column(Modifier.weight(1f)) {
                                Text(m.displayName, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(a?.roleLabel ?: "Оператор", color = c.textDim, fontSize = 13.sp)
                            }
                            if (a?.isPrimary == true) Text("основная", color = c.info, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            if (a?.isSenior == true) Icon(imageVector = Icons.Filled.Star, contentDescription = "Старший", tint = c.warning, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }

            if (s.leads.isNotEmpty()) {
                OwnerSection("Руководители", trailing = { Text("кто за кого отвечает", color = c.textDim, fontSize = 13.sp) }) {
                    s.leads.forEach { lead ->
                        val team = s.subordinates(lead.id)
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                PersonInitial(lead.fullName, true, 36)
                                Column(Modifier.weight(1f)) {
                                    Text(lead.fullName, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(lead.roleLabel, color = c.textDim, fontSize = 13.sp)
                                }
                                Text("${team.size}", color = if (team.isEmpty()) c.textDim else c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                            if (team.isNotEmpty()) Text(team.mapNotNull { it.operatorName }.joinToString(", "), color = c.textDim, fontSize = 12.sp, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GapCard(title: String, subtitle: String, accent: Color, people: List<TeamOperator>) {
    val c = Orda.colors
    OwnerSection(title) {
        Text(subtitle, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        people.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                PersonInitial(m.displayName, true, 32)
                Text(m.displayName, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
                m.position?.takeIf { it.isNotEmpty() }?.let { Text(it, color = c.textDim, fontSize = 12.sp) }
            }
        }
    }
}
