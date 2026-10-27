package kz.ordaops.android.feature.business.team

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.business.RoundAction
import kz.ordaops.android.feature.business.HeroColors
import kz.ordaops.android.feature.business.BizFormat
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kz.ordaops.android.feature.operator.shift.AmountField
import kz.ordaops.android.feature.operator.shift.parseAmount

// ── Зарплата ─────────────────────────────────────────────────────────────────

/**
 * Зарплата за неделю: итоги, кто сколько получит, что уже выплачено; ниже —
 * административный состав на окладе. Порт `SalaryScreen`.
 */
@Composable
fun SalaryScreen() {
    val env = LocalBusiness.current
    val week = rememberSaveable { mutableStateOf(WeekMath.currentWeekStart()) }
    val vm = rememberResource("salary", week.value, TeamRequests::salary, SalaryWeekReport::from)
    val staffVm = rememberResource("staff-salary", Unit, { TeamRequests.staffSalary }, StaffSalarySummary::from)
    val state by vm.state.collectAsStateWithLifecycle()
    val staffState by staffVm.state.collectAsStateWithLifecycle()
    var operatorRow by remember { mutableStateOf<SalaryRow?>(null) }
    var staffRow by remember { mutableStateOf<StaffSalaryRow?>(null) }

    LaunchedEffect(week.value) { vm.setParam(week.value) }

    // Деньги по оператору: аванс, премия, штраф, погашение долга — строка открывается, если разрешено хоть одно.
    val canAdvance = env.can("salary.create_advance") || env.can("salary.create_adjustment") || env.can("salary.mark_debt_paid") ||
        env.can("salary.create_payment") || env.can("salary.void_payment")
    val canPayStaff = env.can("salary.create_payment") || env.can("salary.create_adjustment")

    StateContainer(
        state = state,
        onRetry = { vm.reload(); staffVm.reload() },
        onRefresh = { vm.reload(); staffVm.reload() },
    ) { report ->
        OwnerScreenColumn {
            PeriodStepper(
                title = WeekMath.rangeTitle(week.value),
                caption = if (week.value == WeekMath.currentWeekStart()) "эта неделя" else null,
                canGoForward = !WeekMath.isCurrentOrFuture(week.value),
                onBack = { week.value = WeekMath.shifted(week.value, -1) },
                onForward = { week.value = WeekMath.shifted(week.value, 1) },
            )
            SalaryHero(report.totals)

            val rows = report.operators.filter { it.hasActivity }
            OwnerSection("Начисления", trailing = {
                Text("${rows.size} ${plural(rows.size, "человек", "человека", "человек")}", color = Orda.colors.textDim, fontSize = 13.sp)
            }) {
                if (rows.isEmpty()) {
                    TeamHint("На этой неделе начислений нет")
                } else {
                    rows.forEachIndexed { i, row ->
                        if (i > 0) TeamDivider()
                        SalaryRowView(row, onClick = if (canAdvance) ({ operatorRow = row }) else null)
                    }
                }
            }

            OwnerSection("Из чего складывается") {
                val t = report.totals
                StatRow("Начислено", money(t.gross))
                if (t.bonus > 0) StatRow("Бонусы", signedMoney(t.bonus), Orda.colors.positive)
                if (t.fine > 0) StatRow("Штрафы", signedMoney(-t.fine), Orda.colors.negative)
                if (t.debt > 0) StatRow("Долги", signedMoney(-t.debt), Orda.colors.negative)
                TeamDivider()
                StatRow("Итого к выплате", money(t.net), emphasized = true)
            }

            staffState.data?.takeIf { it.rows.isNotEmpty() }?.let { summary ->
                OwnerSection(
                    if (summary.selfOnly) "Моя зарплата" else "Административный состав",
                    trailing = { Text("период ${summary.periodLabel}", color = Orda.colors.textDim, fontSize = 13.sp) },
                ) {
                    summary.rows.forEachIndexed { i, row ->
                        if (i > 0) TeamDivider()
                        // Строка из списка операторов — витрина: у неё нет ни оклада, ни записи в staff.
                        val tap = if (canPayStaff && !row.isFromOperator) ({ staffRow = row }) else null
                        StaffSalaryRowView(row, tap)
                    }
                    if (!summary.selfOnly && summary.rows.size > 1) {
                        TeamDivider()
                        StatRow("Итого к выплате", money(summary.toPayTotal), emphasized = true)
                    }
                }
            }
        }
    }

    operatorRow?.let { picked ->
        // Берём свежую строку: после выплаты лист обновляется на новых цифрах.
        val fresh = state.data?.operators?.firstOrNull { it.operatorId == picked.operatorId } ?: picked
        val weekStart = state.data?.weekStart?.ifEmpty { null } ?: week.value
        val weekEnd = state.data?.weekEnd?.ifEmpty { null } ?: WeekMath.days(week.value).last().let(WeekMath::format)
        OperatorSalarySheet(fresh, weekStart, weekEnd, onDismiss = { operatorRow = null }, onChanged = { vm.refreshNow() })
    }
    staffRow?.let { picked ->
        val fresh = staffState.data?.rows?.firstOrNull { it.id == picked.id } ?: picked
        StaffSalarySheet(fresh, onDismiss = { staffRow = null }, onChanged = { staffVm.refreshNow() })
    }
}

/** Главная цифра: сколько отдать за неделю и сколько уже отдано. */
@Composable
private fun SalaryHero(totals: SalaryTotals) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Brush.linearGradient(HeroColors.Accent), shape).padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text("К выплате за неделю", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(money(totals.net), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ProgressBar(totals.paidRatio.toFloat(), Color.White, Color.White.copy(alpha = 0.25f), 8)
            Text(
                "выплачено ${money(totals.paid)} · ${BizFormat.percent(totals.paidRatio * 100)}",
                color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            HeroFooter("Осталось", money(totals.remaining))
            HeroFooter("Рассчитано", "${totals.paidOperators} из ${totals.activeOperators}")
        }
    }
}

@Composable
private fun HeroFooter(label: String, value: String) {
    Column {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun ProgressBar(fraction: Float, fill: Color, track: Color, heightDp: Int = 6) {
    Box(Modifier.fillMaxWidth().height(heightDp.dp).background(track, CircleShape)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(heightDp.dp).background(fill, CircleShape))
    }
}

/** Строка зарплаты оператора: имя, смены, сумма и статус выплаты. */
@Composable
private fun SalaryRowView(row: SalaryRow, onClick: (() -> Unit)?) {
    val c = Orda.colors
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        PersonInitial(row.operatorName, row.isActive)
        Column(Modifier.weight(1f)) {
            Text(row.operatorName, color = if (row.isActive) c.text else c.textDim, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text("${row.week.shifts} ${plural(row.week.shifts, "смена", "смены", "смен")}", color = c.textDim, fontSize = 13.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(money(row.week.net), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                row.statusText,
                color = when (row.week.status) { "paid" -> c.positive; "partial" -> c.warning; else -> c.textDim },
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        }
    }
}

/** Строка админ-сотрудника: оклад пополам, что прибавили и что удержали. */
@Composable
internal fun StaffSalaryRowView(row: StaffSalaryRow, onClick: (() -> Unit)?) {
    val c = Orda.colors
    val deltas = buildList {
        if (row.bonuses > 0.01) add(Triple("бонусы", row.bonuses, c.positive))
        if (row.fines > 0.01) add(Triple("штрафы", -row.fines, c.negative))
        if (row.debts > 0.01) add(Triple("долги", -row.debts, c.negative))
        if (row.advances > 0.01) add(Triple("авансы", -row.advances, c.warning))
    }
    val subtitle = when {
        // Строка из оператора-админа оклада не имеет: «0 ₸/мес» соврало бы, что человек работает бесплатно.
        row.isFromOperator -> "из списка операторов"
        !row.isActive && row.dismissalDate != null -> "уволен ${row.dismissalDate}"
        else -> {
            val salary = "оклад ${money(row.monthlySalary)}/мес"
            if (row.extraDays.isEmpty()) salary
            else "$salary · ${row.extraDays.size} ${plural(row.extraDays.size, "доп. выход", "доп. выхода", "доп. выходов")}"
        }
    }
    Column(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            PersonInitial(row.name, row.isActive)
            Column(Modifier.weight(1f)) {
                Text(row.name, color = if (row.isActive) c.text else c.textDim, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(subtitle, color = c.textDim, fontSize = 13.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(money(row.toPay), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (row.paidThisMonth > 0.01) Text("выплачено ${money(row.paidThisMonth)}", color = c.textDim, fontSize = 12.sp)
            }
        }
        if (!row.isActive) StatusChip("Уволен", c.warning) else if (row.monthClosed) StatusChip("Месяц закрыт", c.positive)
        if (deltas.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                deltas.forEach { (label, amount, color) -> Text("$label ${signedMoney(amount)}", color = color, fontSize = 12.sp) }
            }
        }
    }
}

// ── Действия по зарплате ─────────────────────────────────────────────────────

enum class MoneyMode { Split, Single, Optional }
enum class PointMode { None, Optional, Required }

/** Действие над человеком в зарплате: выплата, аванс, премия, штраф, доп. выход. */
data class SalaryAction(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val submitTitle: String,
    val note: String,
    val money: MoneyMode,
    val point: PointMode,
    val prefill: Double = 0.0,
    val overpayLimit: Double? = null,
)

data class SalaryFormValues(
    val cash: Double,
    val kaspi: Double,
    val amount: Double,
    val companyId: String?,
    val date: String,
    val comment: String,
)

/** Остаток к выплате крупно, прогресс и подвал. Как счёт в банковском приложении. */
@Composable
private fun SalaryBalanceCard(title: String, remaining: Double, total: Double, paid: Double, footer: List<Pair<String, String>>) {
    val c = Orda.colors
    Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(24.dp)).padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, color = c.textMuted, fontSize = 14.sp)
        Text(money(remaining), color = c.text, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (total > 0) {
            ProgressBar((paid / total).toFloat(), c.brand, c.surfaceRaised)
            Text("выплачено ${money(paid)} из ${money(total)}", color = c.textDim, fontSize = 13.sp)
        }
        if (footer.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                footer.forEach { (label, value) ->
                    Column { Text(label, color = c.textDim, fontSize = 12.sp); Text(value, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/** Расчёт построчно. */
@Composable
private fun SalaryBreakdownCard(title: String, lines: List<SalaryLine>) {
    OwnerSection(title) {
        lines.forEach { line ->
            val (text, color) = when (line.sign) {
                SalaryLine.Sign.Plus -> signedMoney(line.value) to Orda.colors.positive
                SalaryLine.Sign.Minus -> signedMoney(-line.value) to Orda.colors.negative
                else -> money(line.value) to Orda.colors.text
            }
            if (line.sign == SalaryLine.Sign.Total) TeamDivider()
            StatRow(line.label, text, color, emphasized = line.sign == SalaryLine.Sign.Total)
        }
    }
}

/** Лист «зарплата оператора за неделю»: остаток, действия, расчёт, история с откатом. */
@Composable
private fun OperatorSalarySheet(row: SalaryRow, weekStart: String, weekEnd: String, onDismiss: () -> Unit, onChanged: suspend () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    var opened by remember { mutableStateOf<SalaryAction?>(null) }
    var voidPayment by remember { mutableStateOf<SalaryPayment?>(null) }
    var voidAdjustment by remember { mutableStateOf<SalaryAdjustment?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val week = row.week
    val c = Orda.colors

    val actions = buildList {
        if (env.can("salary.create_payment")) add(SalaryAction("payment", "Выплатить", Icons.Filled.Payments, "Выплатить",
            "Выплата закроет неделю. Выданный аванс зачтётся в неё, а не повиснет отдельным долгом.",
            MoneyMode.Split, PointMode.None, prefill = maxOf(week.remaining, 0.0)))
        if (env.can("salary.create_advance")) add(SalaryAction("advance", "Аванс", Icons.Filled.TrendingUp, "Выдать аванс",
            "Аванс сразу станет расходом точки и уменьшит остаток к выплате за неделю.", MoneyMode.Split, PointMode.Required))
        if (env.can("salary.create_adjustment")) {
            add(SalaryAction("bonus", "Премия", Icons.Filled.CardGiftcard, "Начислить премию", "Премия прибавится к расчёту недели.", MoneyMode.Single, PointMode.Optional))
            add(SalaryAction("fine", "Штраф", Icons.Filled.Report, "Удержать штраф", "Штраф вычтется из расчёта недели.", MoneyMode.Single, PointMode.Optional))
        }
    }

    val current = opened
    if (current != null) BackHandler { opened = null }

    OrdaSheet(if (current == null) "Зарплата" else current.title, onDismiss = onDismiss) {
        if (current != null) {
            SalaryActionForm(current, row.operatorName, onBack = { opened = null }) { v ->
                when (current.id) {
                    "payment" -> env.api.paySalaryWeek(row.operatorId, weekStart, v.date, v.cash, v.kaspi, v.comment, withAdvance = week.advance > 0)
                    "advance" -> env.api.createSalaryAdvance(row.operatorId, v.companyId.orEmpty(), weekStart, v.date, v.cash, v.kaspi, v.comment)
                    else -> env.api.createSalaryAdjustment(row.operatorId, v.companyId, v.date, v.amount, current.id, v.comment)
                }
                onChanged()
                onDismiss()
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                PersonInitial(row.operatorName, row.isActive, 48)
                Column {
                    Text(row.operatorName, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("неделя ${WeekMath.rangeTitle(weekStart)} · ${week.statusLabel.lowercase()}", color = c.textDim, fontSize = 13.sp)
                }
            }
            SalaryBalanceCard(
                "Осталось выплатить", maxOf(week.remaining, 0.0), week.net, week.paid,
                listOf("К выплате" to money(week.net), "Смен" to "${week.shifts}"),
            )
            if (actions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    actions.forEach { a -> RoundAction(a.icon, a.title) { opened = a } }
                }
            }
            notice?.let { Text(it, color = c.positive, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            TeamError(action.error)

            if (week.debt > 0 && env.can("salary.mark_debt_paid")) {
                Row(
                    Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(Radius.lg)).padding(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    TintedIcon(Icons.Filled.Report, c.warning, 42, corner = 21.dp)
                    Column(Modifier.weight(1f)) {
                        Text("Долг ${money(week.debt)}", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Отмечайте, только когда деньги вернули", color = c.textDim, fontSize = 13.sp)
                    }
                    TeamPill("Погашен") {
                        if (!action.busy) action.launch(scope, onSuccess = { onChanged(); onDismiss() }) { env.api.markOperatorDebtsPaid(row.operatorId, weekStart) }
                    }
                }
            }

            SalaryBreakdownCard("Расчёт недели", week.breakdown())

            val canVoidPayment = env.can("salary.void_payment")
            val canVoidAdjustment = env.can("salary.void_adjustment")
            val events = buildList {
                week.payments.forEach { add(SalaryEvent("p." + it.id, it.date, "Выплата", it.comment, it.total, false, c.brand, !it.isActive, if (it.isActive && canVoidPayment) ({ voidPayment = it }) else null)) }
                week.adjustments.forEach { a ->
                    add(SalaryEvent("a." + a.id, a.date, a.kindLabel, a.comment, a.amount, a.isAddition,
                        if (a.isAddition) c.positive else if (a.kind == "advance") c.warning else c.negative,
                        !a.isActive, if (a.isActive && canVoidAdjustment) ({ voidAdjustment = a }) else null))
                }
            }.sortedByDescending { it.date }
            if (events.isNotEmpty()) {
                OwnerSection("История") {
                    events.forEachIndexed { i, e -> if (i > 0) TeamDivider(); SalaryEventRow(e) }
                }
            }

            if (env.can("salary.send_telegram")) {
                TeamActionRow(Icons.Filled.Send, "Отправить расчёт в Telegram", Color(0xFF0284C7), loading = action.busy) {
                    action.launch(scope, onSuccess = { notice = "Расчёт отправлен в Telegram" }) { env.api.sendSalaryToTelegram(row.operatorId, weekStart, weekEnd) }
                }
            }
        }
    }

    voidPayment?.let { p ->
        ConfirmDialog(
            "Отменить выплату?",
            "Сумма вернётся в остаток к выплате. Деньги, отданные человеку, программа вернуть не может — это делаете вы.",
            "Отменить ${money(p.total)}", destructive = true,
            onDismiss = { voidPayment = null },
            onConfirm = {
                voidPayment = null
                action.launch(scope, onSuccess = { onChanged(); onDismiss() }) { env.api.voidSalaryPayment(p.id, row.operatorId, weekStart) }
            },
        )
    }
    voidAdjustment?.let { a ->
        ConfirmDialog(
            "Отменить корректировку?",
            "Расчёт недели пересчитается. Запись останется в истории как отменённая.",
            "Отменить: ${a.kindLabel.lowercase()} ${money(a.amount)}", destructive = true,
            onDismiss = { voidAdjustment = null },
            onConfirm = {
                voidAdjustment = null
                action.launch(scope, onSuccess = { onChanged(); onDismiss() }) { env.api.voidSalaryAdjustment(a.id, row.operatorId, weekStart) }
            },
        )
    }
}

private data class SalaryEvent(
    val id: String, val date: String, val title: String, val detail: String?, val amount: Double,
    val isAddition: Boolean, val tint: Color, val isVoided: Boolean, val onVoid: (() -> Unit)?,
)

@Composable
private fun SalaryEventRow(e: SalaryEvent) {
    val c = Orda.colors
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        TintedIcon(if (e.isAddition) Icons.Filled.AddCircle else Icons.Filled.ArrowDownward, e.tint, 40, corner = 12.dp)
        Column(Modifier.weight(1f)) {
            Text(e.title + if (e.isVoided) " · отменена" else "", color = if (e.isVoided) c.textDim else c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(listOfNotNull(WeekMath.dayMonth(e.date), e.detail?.takeIf { it.isNotEmpty() }).joinToString(" · "), color = c.textDim, fontSize = 12.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                signedMoney(if (e.isAddition) e.amount else -e.amount),
                color = if (e.isVoided) c.textDim else if (e.isAddition) c.positive else c.text,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                style = androidx.compose.ui.text.TextStyle(textDecoration = if (e.isVoided) androidx.compose.ui.text.style.TextDecoration.LineThrough else null),
            )
            if (e.onVoid != null) Text("Отменить", color = c.negative, fontSize = 12.sp, modifier = Modifier.clickable { e.onVoid.invoke() }.padding(top = 2.dp))
        }
    }
}

/** Лист «зарплата админ-сотрудника за половину месяца». */
@Composable
private fun StaffSalarySheet(row: StaffSalaryRow, onDismiss: () -> Unit, onChanged: suspend () -> Unit) {
    val env = LocalBusiness.current
    var opened by remember { mutableStateOf<SalaryAction?>(null) }
    val slot = row.openSlot
    val c = Orda.colors

    val actions = buildList {
        if (env.can("salary.create_payment") && slot != null) add(SalaryAction("payment", "Выплатить", Icons.Filled.Payments, "Выплатить",
            "Выплата станет расходом точки и закроет корректировки этой половины месяца.",
            MoneyMode.Split, PointMode.Required, prefill = maxOf(row.toPay, 0.0), overpayLimit = row.toPay))
        if (env.can("salary.create_adjustment")) {
            add(SalaryAction("advance", "Аванс", Icons.Filled.TrendingUp, "Выдать аванс", "Аванс сразу станет расходом точки и уменьшит остаток к выплате.", MoneyMode.Single, PointMode.Required))
            add(SalaryAction("bonus", "Премия", Icons.Filled.CardGiftcard, "Начислить премию", "Премия прибавится к расчёту половины месяца.", MoneyMode.Single, PointMode.None))
            add(SalaryAction("fine", "Штраф", Icons.Filled.Report, "Удержать штраф", "Штраф вычтется из расчёта половины месяца.", MoneyMode.Single, PointMode.None))
        }
        if (env.can("staff.add_extra_day")) add(SalaryAction("extraDay", "Доп. выход", Icons.Filled.CalendarMonth, "Записать доп. выход",
            "Оклад платят за месяц, а выход сверх нормы — отдельные деньги. Сумму возьмём из ставки смены на его точке; своя нужна редко.",
            MoneyMode.Optional, PointMode.None))
    }
    val current = opened
    if (current != null) BackHandler { opened = null }

    OrdaSheet(if (current == null) "Зарплата" else current.title, onDismiss = onDismiss) {
        if (current != null) {
            SalaryActionForm(current, row.name, onBack = { opened = null }) { v ->
                when (current.id) {
                    "payment" -> env.api.payStaffSalary(row.id, v.companyId.orEmpty(), v.date, slot ?: return@SalaryActionForm, v.cash, v.kaspi, row.toPay, v.comment)
                    "extraDay" -> env.api.addStaffExtraDay(row.id, v.date, v.amount.takeIf { it > 0 })
                    else -> env.api.createStaffAdjustment(row.id, if (current.id == "advance") v.companyId else null, current.id, v.amount, v.date, v.comment)
                }
                onChanged()
                onDismiss()
            }
        } else {
            val subtitle = buildList {
                row.role?.takeIf { it.isNotEmpty() }?.let { add(roleTitle(it)) }
                if (!row.isActive && row.dismissalDate != null) add("уволен ${row.dismissalDate}")
            }.joinToString(" · ").ifEmpty { "оклад" }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                PersonInitial(row.name, row.isActive, 48)
                Column {
                    Text(row.name, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = c.textDim, fontSize = 13.sp)
                }
            }
            SalaryBalanceCard(
                if (slot == null) "Месяц закрыт" else "К выплате за период",
                if (slot == null) 0.0 else maxOf(row.toPay, 0.0),
                if (slot == null) row.paidThisMonth else row.paidThisMonth + maxOf(row.toPay, 0.0),
                row.paidThisMonth,
                if (row.monthlySalary > 0) listOf("Оклад" to money(row.monthlySalary), "Половина" to money(row.half)) else emptyList(),
            )
            if (actions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    actions.forEach { a -> RoundAction(a.icon, a.title) { opened = a } }
                }
            }
            SalaryBreakdownCard("Расчёт половины месяца", row.breakdown())
            if (row.extraDays.isNotEmpty()) {
                OwnerSection("Доп. выходы", trailing = { Text("${row.extraDays.size}", color = c.textDim, fontSize = 13.sp) }) {
                    row.extraDays.forEachIndexed { i, d ->
                        if (i > 0) TeamDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            TintedIcon(Icons.Filled.CalendarMonth, c.brand, 40, corner = 12.dp)
                            Text(WeekMath.dayMonth(d.date), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("+" + money(d.amount), color = c.positive, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// ── Форма действия ───────────────────────────────────────────────────────────

/**
 * Форма одного действия: сумма (нал + Kaspi, одна или «по ставке»), точка, дата,
 * комментарий. Сумму сервер считает заново — здесь только то, что ввёл человек.
 */
@Composable
private fun SalaryActionForm(
    action: SalaryAction,
    personName: String,
    onBack: () -> Unit,
    submit: suspend (SalaryFormValues) -> Unit,
) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val state = rememberActionState()
    // Закрытые точки в выборе не показываем: деньги из их кассы уже не уходят.
    val companiesVm = rememberResource(
        "companies-open", Unit,
        { kz.ordaops.android.core.net.ApiRequest("/api/admin/companies", query = mapOf("open" to "1")) },
        { root -> root.unwrapData().asObjects().map { ScheduleCompany(it.str("id") ?: "", it.str("name") ?: "Точка") } },
    )
    val companies = companiesVm.state.collectAsStateWithLifecycle().value.data.orEmpty()
    var cashText by remember { mutableStateOf(if (action.prefill > 0 && action.money == MoneyMode.Split) formatInput(action.prefill) else "") }
    var kaspiText by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var companyId by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf(WeekMath.format(LocalDate.now())) }
    var comment by remember { mutableStateOf("") }
    val c = Orda.colors

    LaunchedEffect(companies) {
        if (action.point == PointMode.Required && companyId == null) companyId = companies.firstOrNull()?.id
    }

    val cash = parseAmount(cashText)
    val kaspi = parseAmount(kaspiText)
    val total = if (action.money == MoneyMode.Split) cash + kaspi else parseAmount(amountText)
    val problem = when {
        action.money != MoneyMode.Optional && total <= 0 -> "Укажите сумму"
        action.point == PointMode.Required && companyId.isNullOrEmpty() -> "Выберите точку — деньги уходят из её кассы"
        else -> null
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (action.money == MoneyMode.Optional && total <= 0) "по ставке" else money(total),
            color = c.text, fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        )
        Text(personName, color = c.textDim, fontSize = 14.sp)
    }
    if (action.prefill > 0 && action.money == MoneyMode.Split && kotlin.math.abs(total - action.prefill) > 0.5) {
        TeamPill("Весь остаток · ${money(action.prefill)}") { cashText = formatInput(action.prefill); kaspiText = "" }
    }
    FormSection("Сумма") {
        when (action.money) {
            MoneyMode.Split -> {
                AmountField("Наличными", cashText, { cashText = it })
                AmountField("Kaspi", kaspiText, { kaspiText = it })
            }
            MoneyMode.Single -> AmountField(action.title, amountText, { amountText = it })
            MoneyMode.Optional -> AmountField("Своя сумма", amountText, { amountText = it }, placeholder = "по ставке")
        }
    }
    action.overpayLimit?.let { limit ->
        if (total > limit + 0.5) TeamHint("Больше расчёта на ${money(total - limit)} — разница уйдёт авансом и вычтется из следующей выплаты.", c.warning)
    }
    FormSection("Подробности") {
        if (action.point != PointMode.None) {
            Text("Точка", color = c.textMuted, fontSize = 13.sp)
            if (action.point == PointMode.Optional) TeamChoiceRow("Не указана", companyId == null) { companyId = null }
            companies.forEach { company -> TeamChoiceRow(company.name, companyId == company.id) { companyId = company.id } }
        }
        TeamDateField("Дата", date) { date = it }
        TeamField("Комментарий", comment, { comment = it }, singleLine = false)
    }
    TeamHint(action.note)
    TeamError(state.error)
    SubmitButton(action.submitTitle, state.busy, problem) {
        state.launch(scope) {
            submit(SalaryFormValues(cash, kaspi, total, companyId, date, comment.trim()))
        }
    }
    TeamPill("Назад", c.textMuted) { onBack() }
}

private fun formatInput(value: Double): String =
    if (value == Math.floor(value)) value.toLong().toString() else "%.2f".format(java.util.Locale.US, value)

/** Поле даты: показывает «5 мая 2026», по тапу — календарь. Значение — ISO `ГГГГ-ММ-ДД`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamDateField(label: String, iso: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val c = Orda.colors
    val parsed = WeekMath.parse(iso)
    Row(
        Modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(Radius.md)).clickable { open = true }.padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.textMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(parsed?.let { "${WeekMath.dayMonth(it)} ${it.year}" } ?: iso, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
    if (open) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = (parsed ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { onChange(WeekMath.format(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())) }
                    open = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } },
        ) { DatePicker(state = picker) }
    }
}
