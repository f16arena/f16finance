package kz.ordaops.android.feature.operator.money

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import kz.ordaops.android.ui.components.WavyProgress
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.time.LocalDate
import java.time.format.TextStyle

/** Смена конкретного дня недели, если она есть. */
private data class Day(
    val date: LocalDate,
    val shift: ScheduledShift?,
    val companyName: String?,
) {
    val isToday: Boolean get() = date == LocalDate.now()
    val isPast: Boolean get() = date.isBefore(LocalDate.now())
    val isWorking: Boolean get() = shift != null
}

/**
 * График смен — недельный календарь. Порт `ScheduleScreen.swift`: семь
 * колонок, занятые дни заполнены, выходные видны провалом.
 */
@Composable
fun ScheduleTab() {
    val env = LocalOperatorEnv.current
    val store = remember { MoneyStore(env.api) }
    val scope = rememberCoroutineScope()
    var showLead by rememberSaveable { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<Day?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { store.loadLeadDesk() }
    LaunchedEffect(store.scheduleWeek) { store.loadSchedule() }

    if (showLead) {
        SubScreen("Старший смены", onBack = { showLead = false }) { LeadDeskContent(store) }
        return
    }

    val schedule = store.schedule
    val groups = schedule?.groups.orEmpty()
    val start = parseDate(schedule?.weekStart) ?: parseDate(store.scheduleWeek) ?: LocalDate.now()
    val shifts = groups.flatMap { g -> g.shifts.map { it to g.companyName } }
    val days = (0L..6L).map { offset ->
        val date = start.plusDays(offset)
        val match = shifts.firstOrNull { it.first.date == date.toString() }
        Day(date, match?.first, match?.second)
    }
    val workingCount = days.count { it.isWorking }
    val nightCount = days.count { it.shift?.isNight == true }
    val nextWorking = days.firstOrNull { it.isWorking && !it.isPast }?.date
    // Заявку можно подать только по опубликованной неделе: она привязана к публикации.
    val response = groups.mapNotNull { it.response }.firstOrNull()
    val canReport = response != null

    RefreshColumn(
        refreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                store.loadSchedule()
                refreshing = false
            }
        },
    ) {
        Text("Мой график", color = Orda.colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        store.error?.let { ErrorBanner(it) }
        actionError?.let { ErrorBanner(it) }

        if (schedule == null) {
            if (store.error == null) Box(Modifier.fillMaxWidth().height(220.dp).background(Orda.colors.surface, RoundedCornerShape(20.dp)))
        } else {
            // Шаг по неделям: график публикуют заранее, «что у меня на следующей» — первый вопрос после «что сегодня».
            PeriodStepper(
                title = "${dayMonth(days.first().date)} — ${dayMonth(days.last().date)}",
                caption = "$workingCount ${pluralize(workingCount, "смена", "смены", "смен")} на неделе",
                onBack = { store.shiftScheduleWeek(-1) },
                onForward = { store.shiftScheduleWeek(1) },
            )

            WeekGrid(days, canReport) { reporting = it }

            // Для старшего: заявки команды ждут именно здесь, рядом с графиком.
            if (store.isLead && store.leadPendingCount > 0) {
                LeadBanner(store.leadPendingCount) { showLead = true }
            }

            // График опубликован — его надо принять: снимает вечное «я не знал про смену».
            groups.forEach { group ->
                val groupResponse = group.response
                if (groupResponse != null && group.needsConfirmation) {
                    OrdaCard {
                        Text("График опубликован", color = Orda.colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(group.companyName, color = Orda.colors.textMuted, fontSize = 12.sp)
                        Text(
                            "Подтвердите, что видели неделю и выходите. Без этого у руководителя вы числитесь не ознакомленным.",
                            color = Orda.colors.textDim,
                            fontSize = 14.sp,
                        )
                        PrimaryButton("Ознакомлен, выхожу", loading = busy) {
                            scope.launch {
                                busy = true
                                actionError = null
                                actionError = store.confirmScheduleWeek(groupResponse.id)
                                busy = false
                            }
                        }
                    }
                }
            }

            RequestsCard(groups.flatMap { it.requests }.filter { it.isOpen })

            SummaryCard(workingCount, nightCount, nextWorking)

            // Точки перечисляем отдельно: оператор может работать на нескольких.
            if (groups.size > 1) {
                OrdaCard {
                    SectionLabel("Точки")
                    groups.forEach { g ->
                        StatRow(g.companyName, "${g.shifts.size} ${pluralize(g.shifts.size, "смена", "смены", "смен")}")
                    }
                }
            }

            if (days.none { it.isWorking }) {
                OrdaCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CalendarMonth, null, tint = Orda.colors.textMuted)
                        Text(
                            "На эту неделю смен не назначено. График публикует руководитель.",
                            color = Orda.colors.textMuted,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }

    reporting?.let { day ->
        val r = response
        if (r != null) {
            ShiftIssueSheet(
                shiftDate = day.date.toString(),
                shiftType = day.shift?.shiftType ?: "day",
                companyName = day.companyName.orEmpty(),
                onDismiss = { reporting = null },
                onSubmit = { reason -> store.reportShiftIssue(r.id, day.date.toString(), day.shift?.shiftType ?: "day", reason) },
            )
        } else {
            reporting = null
        }
    }
}

@Composable
private fun WeekGrid(days: List<Day>, canReport: Boolean, onReport: (Day) -> Unit) {
    OrdaCard {
        // Постоянные семь колонок: неделя читается как форма, выходные видно провалом.
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            days.forEach { day ->
                Text(
                    day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, RuLocale).uppercase(),
                    color = if (day.isToday) Orda.colors.brand else Orda.colors.textDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            days.forEach { day ->
                // Нажатие на свою будущую смену — «не смогу выйти». Прошедшую менять поздно.
                val tappable = day.isWorking && !day.isPast && canReport
                DayCell(day, Modifier.weight(1f).let { if (tappable) it.clickable { onReport(day) } else it })
            }
        }
        if (canReport) {
            Text("Нажмите на будущую смену, если не сможете выйти.", color = Orda.colors.textMuted, fontSize = 12.sp)
        }
    }
}

/** Рабочий день заливается, выходной остаётся пустым; сегодняшний обведён. */
@Composable
private fun DayCell(day: Day, modifier: Modifier) {
    val c = Orda.colors
    val shift = day.shift
    val accent = if (shift?.isNight == true) c.accent else c.brand
    val shape = RoundedCornerShape(Radius.sm)
    Column(
        modifier
            .height(62.dp)
            .background(if (shift == null) c.surfaceRaised.copy(alpha = 0.5f) else accent.copy(alpha = if (day.isPast) 0.18f else 0.28f), shape)
            .then(if (day.isToday) Modifier.border(BorderStroke(2.dp, c.brand), shape) else Modifier)
            .alpha(if (day.isPast && shift == null) 0.45f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "${day.date.dayOfMonth}",
            color = if (day.isToday) c.brand else if (shift == null) c.textDim else c.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (shift != null) {
            Icon(
                if (shift.isNight) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                contentDescription = shift.typeLabel,
                tint = accent,
                modifier = Modifier.padding(top = 4.dp).size(14.dp),
            )
        } else {
            // Высота одинаковая, чтобы сетка не «плясала» по дням.
            Box(Modifier.padding(top = 8.dp).size(4.dp).background(c.textDim.copy(alpha = 0.25f), CircleShape))
        }
    }
}

@Composable
private fun LeadBanner(count: Int, onClick: () -> Unit) {
    val c = Orda.colors
    OrdaCard(Modifier.pressScale().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(Icons.Filled.ManageAccounts, null, tint = c.warning, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text("Заявки команды: $count", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("Ждут вашего предложения по замене", color = c.textMuted, fontSize = 12.sp)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = c.textMuted)
        }
    }
}

@Composable
private fun RequestsCard(requests: List<ShiftChangeRequest>) {
    if (requests.isEmpty()) return
    val c = Orda.colors
    OrdaCard {
        Text("Заявки на замену", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("Решение принимает руководитель", color = c.textMuted, fontSize = 12.sp)
        requests.forEach { request ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        dayTitle(request.shiftDate) + if (request.shiftType == "night") ", ночная" else ", дневная",
                        color = c.text,
                        fontSize = 15.sp,
                    )
                    if (!request.reason.isNullOrEmpty()) Text(request.reason, color = c.textMuted, fontSize = 12.sp, maxLines = 2)
                }
                StatusChip(request.statusLabel, c.warning)
            }
        }
    }
}

/** Сводка недели: сколько смен, из них ночных и когда ближайшая. */
@Composable
private fun SummaryCard(working: Int, night: Int, next: LocalDate?) {
    HeroCard {
        Text("Смен на неделе", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text("$working", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.padding(top = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            Column {
                Text("Ночных", color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
                Text("$night", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Column {
                Text("Ближайшая", color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
                Text(
                    next?.let { "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, RuLocale)}, ${dayMonth(it)}" } ?: "—",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// ── «Не смогу выйти» ─────────────────────────────────────────────────────────

private val IssuePresets = listOf("Заболел", "Семейные обстоятельства", "Учёба", "Не успеваю доехать")

/**
 * «Не смогу выйти» по конкретной смене: заявка уходит руководителю, решение и
 * подмену принимает он. Причина обязательна — без неё руководитель не знает,
 * искать ли замену на день или человек заболел на неделю.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShiftIssueSheet(
    shiftDate: String,
    shiftType: String,
    companyName: String,
    onDismiss: () -> Unit,
    onSubmit: suspend (String) -> String?,
) {
    val c = Orda.colors
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val canSubmit = reason.trim().length >= 3 && !saving

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.elevated,
    ) {
        Column(Modifier.padding(horizontal = Spacing.lg).padding(bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("Не смогу выйти", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            OrdaCard {
                SectionLabel("Смена")
                Text(
                    dayTitle(shiftDate) + if (shiftType == "night") ", ночная" else ", дневная",
                    color = c.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (companyName.isNotEmpty()) Text(companyName, color = c.textMuted, fontSize = 12.sp)
            }
            // Готовые причины: набирать текст стоя в дороге неудобно.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                IssuePresets.forEach { preset ->
                    val selected = reason == preset
                    Text(
                        preset,
                        color = if (selected) c.brand else c.textDim,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .background(if (selected) c.brand.copy(alpha = 0.16f) else c.surfaceRaised, CircleShape)
                            .clickable { reason = preset }
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs + 2.dp),
                    )
                }
            }
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Что случилось") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Руководитель увидит заявку и решит, кем заменить. Смена остаётся за вами, пока он не решит иначе.",
                color = c.textMuted,
                fontSize = 12.sp,
            )
            error?.let { ErrorBanner(it) }
            PrimaryButton("Отправить руководителю", modifier = Modifier.fillMaxWidth(), enabled = canSubmit, loading = saving) {
                scope.launch {
                    saving = true
                    error = null
                    val failure = onSubmit(reason.trim())
                    saving = false
                    if (failure != null) error = failure else onDismiss()
                }
            }
        }
    }
}

// ── Старший смены ────────────────────────────────────────────────────────────

/**
 * «Старший смены» — заявки команды и готовность недели. Замену ищет не офис, а
 * старший на точке: он предлагает решение, руководитель утверждает.
 */
@Composable
internal fun LeadDeskContent(store: MoneyStore) {
    val scope = rememberCoroutineScope()
    var deciding by remember { mutableStateOf<LeadShiftRequest?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { store.loadLeadDesk() }
    val desk = store.leadDesk

    RefreshColumn(
        refreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                store.loadLeadDesk()
                refreshing = false
            }
        },
    ) {
        if (desk == null) {
            Box(Modifier.fillMaxWidth().padding(Spacing.xxl), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Orda.colors.brand)
            }
        } else {
            desk.companies.forEach { ReadinessCard(it) }

            LeadSection("Ждут решения", desk.awaitingProposal, "Заявок нет — команда выходит по графику.", actionable = true) { deciding = it }
            if (desk.awaitingDecision.isNotEmpty()) LeadSection("У руководителя", desk.awaitingDecision, "", false) {}
            if (desk.settled.isNotEmpty()) LeadSection("Закрытые", desk.settled.take(10), "", false) {}
        }
    }

    deciding?.let { request ->
        LeadProposalSheet(
            request = request,
            candidates = store.leadDesk?.replacements(request.companyID, request.operatorID).orEmpty(),
            onDismiss = { deciding = null },
            onSubmit = { action, note, replacement ->
                store.submitLeadProposal(request.id, action, note, replacement)
            },
        )
    }
}

/** Кто принял опубликованную неделю, а кто ещё нет — первое, что старший спрашивает в понедельник. */
@Composable
private fun ReadinessCard(company: LeadCompany) {
    val c = Orda.colors
    OrdaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(company.name, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            when {
                company.isDraft -> StatusChip("график не опубликован", c.textDim)
                company.pending == 0 -> StatusChip("неделя принята", c.positive)
                else -> StatusChip("не подтвердили: ${company.pending}", c.warning)
            }
        }
        if (!company.isDraft) {
            WavyProgress(
                progress = { company.confirmed.toFloat() / maxOf(company.total, 1) },
                modifier = Modifier.fillMaxWidth(),
                color = if (company.pending == 0) c.positive else c.warning,
                trackColor = c.surfaceRaised,
            )
            Text("Подтвердили ${company.confirmed} из ${company.total}", color = c.textMuted, fontSize = 12.sp)
        }
        if (company.issues > 0) {
            SoftDivider()
            StatRow("Заявок открыто", "${company.issues}")
        }
    }
}

@Composable
private fun LeadSection(
    title: String,
    requests: List<LeadShiftRequest>,
    empty: String,
    actionable: Boolean,
    onPick: (LeadShiftRequest) -> Unit,
) {
    if (requests.isEmpty()) {
        if (empty.isNotEmpty()) {
            SectionLabel(title)
            OrdaCard { Text(empty, color = Orda.colors.textMuted, fontSize = 14.sp) }
        }
        return
    }
    SectionLabel(title)
    requests.forEach { request ->
        LeadRequestCard(request, actionable, Modifier.let { if (actionable) it.clickable { onPick(request) } else it })
    }
}

@Composable
private fun LeadRequestCard(request: LeadShiftRequest, actionable: Boolean, modifier: Modifier) {
    val c = Orda.colors
    OrdaCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(request.operatorName, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (actionable) Icon(Icons.Filled.ChevronRight, null, tint = c.textMuted)
        }
        Text(
            dayTitle(request.shiftDate) + (if (request.isNight) ", ночная" else ", дневная") + " · " + request.companyName,
            color = c.textDim,
            fontSize = 14.sp,
        )
        if (!request.reason.isNullOrEmpty()) Text(request.reason, color = c.textMuted, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            StatusChip(request.statusLabel, if (request.isOpen) c.warning else c.textDim)
            request.proposalLabel?.let { StatusChip(it, c.info) }
        }
        if (!request.resolutionNote.isNullOrEmpty()) {
            Text("Решение: ${request.resolutionNote}", color = c.textMuted, fontSize = 12.sp)
        }
    }
}

/** Предложение старшего по одной заявке: замена / оставить / снять. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeadProposalSheet(
    request: LeadShiftRequest,
    candidates: List<LeadTeamMember>,
    onDismiss: () -> Unit,
    onSubmit: suspend (action: String, note: String?, replacement: String?) -> String?,
) {
    val c = Orda.colors
    val scope = rememberCoroutineScope()
    var choice by remember { mutableStateOf("replace") }
    var replacement by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val canSubmit = !saving && (choice != "replace" || replacement != null)
    val hint = when (choice) {
        "keep" -> "Причина не тянет на замену — смена остаётся за человеком."
        "remove" -> "Смену снять, никем не закрывать. Точка останется без этого человека."
        else -> "Кто выйдет вместо него. Предложение уходит руководителю на утверждение."
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.elevated,
    ) {
        Column(
            Modifier
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xxl)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Предложение", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            OrdaCard {
                Text(request.operatorName, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    dayTitle(request.shiftDate) + (if (request.isNight) ", ночная" else ", дневная") + " · " + request.companyName,
                    color = c.textDim,
                    fontSize = 14.sp,
                )
                if (!request.reason.isNullOrEmpty()) Text(request.reason, color = c.textMuted, fontSize = 14.sp)
            }
            OrdaCard {
                SectionLabel("Что предлагаете")
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    listOf("replace" to "Замена", "keep" to "Оставить", "remove" to "Снять").forEach { (key, label) ->
                        val selected = choice == key
                        Text(
                            label,
                            color = if (selected) Color.White else c.textDim,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .background(if (selected) c.brand else c.surfaceRaised, RoundedCornerShape(Radius.sm))
                                .clickable { choice = key }
                                .padding(vertical = Spacing.sm),
                        )
                    }
                }
                Text(hint, color = c.textMuted, fontSize = 12.sp)
            }
            if (choice == "replace") {
                // Кандидаты — только состав этой точки: чужого сервер всё равно не примет.
                OrdaCard {
                    SectionLabel("Кто выйдет")
                    if (candidates.isEmpty()) {
                        Text(
                            "На этой точке больше никого нет — предложите «оставить» или «снять», и руководитель решит.",
                            color = c.textMuted,
                            fontSize = 14.sp,
                        )
                    } else {
                        candidates.forEachIndexed { index, member ->
                            if (index > 0) SoftDivider()
                            Row(
                                Modifier.fillMaxWidth().clickable { replacement = member.operatorID }.padding(vertical = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(member.operatorName, color = c.text, fontSize = 16.sp)
                                    Text(member.roleLabel, color = c.textMuted, fontSize = 12.sp)
                                }
                                Icon(
                                    if (replacement == member.operatorID) Icons.Filled.CheckCircle else Icons.Filled.Circle,
                                    null,
                                    tint = if (replacement == member.operatorID) c.brand else c.textMuted.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Комментарий руководителю (необязательно)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { ErrorBanner(it) }
            PrimaryButton("Отправить руководителю", modifier = Modifier.fillMaxWidth(), enabled = canSubmit, loading = saving) {
                scope.launch {
                    saving = true
                    error = null
                    val failure = onSubmit(choice, note.trim(), if (choice == "replace") replacement else null)
                    saving = false
                    if (failure != null) error = failure else onDismiss()
                }
            }
        }
    }
}
