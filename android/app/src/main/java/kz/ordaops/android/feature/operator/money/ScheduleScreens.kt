package kz.ordaops.android.feature.operator.money

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.ordaops.android.feature.operator.common.operatorViewModel
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.WavyProgress
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

/**
 * График смен — недельный календарь. Порт `ScheduleScreen.swift`: семь
 * колонок, занятые дни заполнены, выходные видны провалом.
 */
@Composable
fun ScheduleTab() {
    val vm = operatorViewModel { env -> MoneyViewModel(env.api) }
    val state by vm.schedule.collectAsStateWithLifecycle()
    val week by vm.scheduleWeek.collectAsStateWithLifecycle()
    val isLead by vm.isLead.collectAsStateWithLifecycle()
    val leadPending by vm.leadPendingCount.collectAsStateWithLifecycle()
    val confirming by vm.confirming.collectAsStateWithLifecycle()
    val actionError by vm.actionError.collectAsStateWithLifecycle()
    val issue by vm.issue.collectAsStateWithLifecycle()
    var showLead by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refreshLead()
        vm.refreshSchedule()
    }

    if (showLead) {
        SubScreen("Старший смены", onBack = { showLead = false }) { LeadDeskContent(vm) }
        return
    }

    AdaptiveBox { wide ->
        Column(Modifier.fillMaxWidth()) {
            ScreenHeader("Мой график") {
                // Шаг по неделям: график публикуют заранее, «что у меня на следующей» — первый вопрос после «что сегодня».
                // Заголовок — выбранная неделя сразу; данные подъедут, а прежние до тех пор остаются на экране.
                PeriodStepper(
                    title = weekTitle(week),
                    caption = state.data?.takeIf { it.weekStart == week }?.caption,
                    onBack = { vm.shiftScheduleWeek(-1) },
                    onForward = { vm.shiftScheduleWeek(1) },
                )
            }
            StateContainer(state, onRetry = vm::refreshSchedule, modifier = Modifier.weight(1f)) { content ->
                ScheduleList(
                    content = content,
                    wide = wide,
                    isLead = isLead,
                    leadPending = leadPending,
                    confirming = confirming,
                    actionError = actionError,
                    onOpenLead = { showLead = true },
                    onConfirm = vm::confirmScheduleWeek,
                    onReport = { day -> vm.openIssue(day.date.toString(), day.shiftType, day.companyName.orEmpty()) },
                )
            }
        }
    }

    issue?.let { draft ->
        ShiftIssueSheet(
            draft = draft,
            onDismiss = vm::dismissIssue,
            onSubmit = { reason -> vm.submitIssue(state.data?.responseId, reason) },
        )
    }
}

@Composable
private fun ScheduleList(
    content: ScheduleContent,
    wide: Boolean,
    isLead: Boolean,
    leadPending: Int,
    confirming: Boolean,
    actionError: String?,
    onOpenLead: () -> Unit,
    onConfirm: (String) -> Unit,
    onReport: (DayUi) -> Unit,
) {
    val canReport = content.responseId != null
    ScreenList {
        if (actionError != null) item(key = "error", contentType = "error") { ErrorBanner(actionError) }

        if (wide) {
            item(key = "top", contentType = "top") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Box(Modifier.weight(3f)) { WeekGrid(content.days, canReport, onReport) }
                    Box(Modifier.weight(2f)) { SummaryCard(content) }
                }
            }
        } else {
            item(key = "grid", contentType = "grid") { WeekGrid(content.days, canReport, onReport) }
        }

        // Для старшего: заявки команды ждут именно здесь, рядом с графиком.
        if (isLead && leadPending > 0) item(key = "lead", contentType = "lead") { LeadBanner(leadPending, onOpenLead) }

        // График опубликован — его надо принять: снимает вечное «я не знал про смену».
        items(content.confirmations, key = { "confirm-${it.responseId}" }, contentType = { "confirm" }) { confirm ->
            OrdaCard {
                Text("График опубликован", color = Orda.colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(confirm.companyName, color = Orda.colors.textMuted, fontSize = 12.sp)
                Text(
                    "Подтвердите, что видели неделю и выходите. Без этого у руководителя вы числитесь не ознакомленным.",
                    color = Orda.colors.textDim,
                    fontSize = 14.sp,
                )
                PrimaryButton("Ознакомлен, выхожу", loading = confirming) { onConfirm(confirm.responseId) }
            }
        }

        if (content.requests.isNotEmpty()) item(key = "requests", contentType = "requests") { RequestsCard(content.requests) }

        if (!wide) item(key = "summary", contentType = "summary") { SummaryCard(content) }

        // Точки перечисляем отдельно: оператор может работать на нескольких.
        if (content.groupCounts.isNotEmpty()) {
            item(key = "groups", contentType = "groups") {
                OrdaCard {
                    SectionLabel("Точки")
                    content.groupCounts.forEach { StatRow(it.name, it.count) }
                }
            }
        }

        if (content.workingCount == 0) {
            item(key = "empty", contentType = "empty") {
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
}

@Composable
private fun WeekGrid(days: List<DayUi>, canReport: Boolean, onReport: (DayUi) -> Unit) {
    val large = isLargeFont()
    OrdaCard {
        // Постоянные семь колонок: неделя читается как форма, выходные видно провалом.
        // При крупном шрифте семь колонок не помещаются — колонки фиксированной ширины и прокрутка вбок.
        Row(
            if (large) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            days.forEach { day ->
                // Нажатие на свою будущую смену — «не смогу выйти». Прошедшую менять поздно.
                val tappable = day.isWorking && !day.isPast && canReport
                Column(
                    if (large) Modifier.width(56.dp) else Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        day.weekday,
                        color = if (day.isToday) Orda.colors.brand else Orda.colors.textDim,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    DayCell(day, Modifier.fillMaxWidth().let { if (tappable) it.clickable { onReport(day) } else it })
                }
            }
        }
        if (canReport) {
            Text("Нажмите на будущую смену, если не сможете выйти.", color = Orda.colors.textMuted, fontSize = 12.sp)
        }
    }
}

/** Рабочий день заливается, выходной остаётся пустым; сегодняшний обведён. */
@Composable
private fun DayCell(day: DayUi, modifier: Modifier) {
    val c = Orda.colors
    val accent = if (day.isNight) c.accent else c.brand
    val shape = RoundedCornerShape(Radius.sm)
    Column(
        modifier
            // Минимум, не фикс: при крупном шрифте число дня не обрезается.
            .heightIn(min = 62.dp)
            .background(if (!day.isWorking) c.surfaceRaised.copy(alpha = 0.5f) else accent.copy(alpha = if (day.isPast) 0.18f else 0.28f), shape)
            .then(if (day.isToday) Modifier.border(BorderStroke(2.dp, c.brand), shape) else Modifier)
            .alpha(if (day.isPast && !day.isWorking) 0.45f else 1f)
            .padding(vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            day.dayNumber,
            color = if (day.isToday) c.brand else if (!day.isWorking) c.textDim else c.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (day.isWorking) {
            Icon(
                if (day.isNight) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                contentDescription = day.typeLabel,
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
private fun RequestsCard(requests: List<RequestUi>) {
    val c = Orda.colors
    OrdaCard {
        Text("Заявки на замену", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("Решение принимает руководитель", color = c.textMuted, fontSize = 12.sp)
        requests.forEach { request ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Column(Modifier.weight(1f)) {
                    Text(request.title, color = c.text, fontSize = 15.sp)
                    if (request.reason != null) Text(request.reason, color = c.textMuted, fontSize = 12.sp, maxLines = 2)
                }
                StatusChip(request.statusLabel, c.warning)
            }
        }
    }
}

/** Сводка недели: сколько смен, из них ночных и когда ближайшая. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(content: ScheduleContent) {
    HeroCard {
        Text("Смен на неделе", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text("${content.workingCount}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        FlowRow(
            Modifier.padding(top = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Column {
                Text("Ночных", color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
                Text("${content.nightCount}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Column {
                Text("Ближайшая", color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
                Text(content.nextText, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
 * Текст причины в `rememberSaveable`: переживает поворот, пока лист открыт.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShiftIssueSheet(
    draft: IssueDraft,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val c = Orda.colors
    var reason by rememberSaveable(draft.shiftDate) { mutableStateOf("") }
    val canSubmit = reason.trim().length >= 3 && !draft.saving
    val title = remember(draft.shiftDate, draft.shiftType) {
        dayTitle(draft.shiftDate) + if (draft.shiftType == "night") ", ночная" else ", дневная"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.elevated,
    ) {
        // imePadding + прокрутка: клавиатура не закрывает поле и кнопку, крупный шрифт не режется.
        Column(
            Modifier
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Не смогу выйти", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            OrdaCard {
                SectionLabel("Смена")
                Text(title, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                if (draft.companyName.isNotEmpty()) Text(draft.companyName, color = c.textMuted, fontSize = 12.sp)
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
            draft.error?.let { ErrorBanner(it) }
            PrimaryButton("Отправить руководителю", modifier = Modifier.fillMaxWidth(), enabled = canSubmit, loading = draft.saving) {
                onSubmit(reason.trim())
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
internal fun LeadDeskContent(vm: MoneyViewModel) {
    val state by vm.lead.collectAsStateWithLifecycle()
    val proposal by vm.proposal.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshLead() }

    AdaptiveBox {
        StateContainer(state, onRetry = vm::refreshLead) { desk ->
            ScreenList {
                items(desk.companies, key = { "company-${it.id}-${it.name}" }, contentType = { "readiness" }) { ReadinessCard(it) }

                leadSection(
                    "await", "Ждут решения", desk.awaitingProposal, "Заявок нет — команда выходит по графику.", actionable = true,
                    onPick = { vm.openProposal(it.id) },
                )
                leadSection("decision", "У руководителя", desk.awaitingDecision, "", false) {}
                leadSection("settled", "Закрытые", desk.settled.take(10), "", false) {}
            }
        }
    }

    // Заявку берём из актуального состава: после отправки она уходит из «Ждут решения».
    proposal?.let { draft ->
        val request = state.data?.requests?.firstOrNull { it.id == draft.requestId }
        if (request != null) {
            LeadProposalSheet(
                request = request,
                candidates = state.data?.replacements(request.companyID, request.operatorID).orEmpty(),
                draft = draft,
                onDismiss = vm::dismissProposal,
                onSubmit = vm::submitProposal,
            )
        }
    }
}

/** Кто принял опубликованную неделю, а кто ещё нет — первое, что старший спрашивает в понедельник. */
@Composable
private fun ReadinessCard(company: LeadCompany) {
    val c = Orda.colors
    OrdaCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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

private fun androidx.compose.foundation.lazy.LazyListScope.leadSection(
    id: String,
    title: String,
    requests: List<LeadShiftRequest>,
    empty: String,
    actionable: Boolean,
    onPick: (LeadShiftRequest) -> Unit,
) {
    if (requests.isEmpty()) {
        if (empty.isNotEmpty()) {
            item(key = "$id-title", contentType = "section") { SectionLabel(title) }
            item(key = "$id-empty", contentType = "empty") { OrdaCard { Text(empty, color = Orda.colors.textMuted, fontSize = 14.sp) } }
        }
        return
    }
    item(key = "$id-title", contentType = "section") { SectionLabel(title) }
    items(requests, key = { "$id-${it.id}" }, contentType = { "request" }) { request ->
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
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
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
    draft: ProposalDraft,
    onDismiss: () -> Unit,
    onSubmit: (choice: String, replacement: String?, note: String) -> Unit,
) {
    val c = Orda.colors
    // Выбор и комментарий переживают поворот, пока лист открыт.
    var choice by rememberSaveable(request.id) { mutableStateOf("replace") }
    var replacement by rememberSaveable(request.id) { mutableStateOf<String?>(null) }
    var note by rememberSaveable(request.id) { mutableStateOf("") }
    val canSubmit = !draft.saving && (choice != "replace" || replacement != null)
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xxl),
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
                            textAlign = TextAlign.Center,
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
            draft.error?.let { ErrorBanner(it) }
            PrimaryButton("Отправить руководителю", modifier = Modifier.fillMaxWidth(), enabled = canSubmit, loading = draft.saving) {
                onSubmit(choice, replacement, note.trim())
            }
        }
    }
}
