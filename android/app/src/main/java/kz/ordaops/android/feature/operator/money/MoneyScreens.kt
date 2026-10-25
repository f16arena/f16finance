package kz.ordaops.android.feature.operator.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.ordaops.android.feature.operator.common.operatorViewModel
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.WavyProgress
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

/**
 * «Мои деньги» — зарплата за неделю: из чего сложилась, что выплачено, что
 * удержано. Порт `MoneyScreen` из OperatorMoneyScreens.swift.
 */
@Composable
fun MoneyTab() {
    val vm = operatorViewModel { env -> MoneyViewModel(env.api) }
    val state by vm.money.collectAsStateWithLifecycle()
    val week by vm.salaryWeek.collectAsStateWithLifecycle()
    var showAllDebts by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshMoney() }

    if (showAllDebts) {
        AllDebtsScreen(state.data?.allDebtWeeks.orEmpty(), state.data?.totalDebt.orEmpty(), onBack = { showAllDebts = false })
        return
    }

    AdaptiveBox { wide ->
        Column(Modifier.fillMaxWidth()) {
            ScreenHeader("Мои деньги") {
                PeriodStepper(
                    title = weekTitle(week),
                    caption = if (week == currentWeekStart()) "текущая неделя" else null,
                    canGoForward = week < currentWeekStart(),
                    onBack = { vm.shiftSalaryWeek(-1) },
                    onForward = { vm.shiftSalaryWeek(1) },
                )
            }
            StateContainer(state, onRetry = vm::refreshMoney, modifier = Modifier.weight(1f)) { content ->
                MoneyList(content, wide, onShowAll = { showAllDebts = true })
            }
        }
    }
}

@Composable
private fun MoneyList(c: MoneyContent, wide: Boolean, onShowAll: () -> Unit) {
    ScreenList {
        if (wide) {
            // На широком экране главная цифра и «из чего сложилось» читаются рядом.
            item(key = "top", contentType = "top") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Box(Modifier.weight(1f)) { SalaryHeroCard(c) }
                    Box(Modifier.weight(1f)) { BreakdownCard(c.breakdown) }
                }
            }
        } else {
            item(key = "hero", contentType = "hero") { SalaryHeroCard(c) }
            item(key = "breakdown", contentType = "breakdown") { BreakdownCard(c.breakdown) }
        }
        if (c.shifts.isNotEmpty()) {
            item(key = "shifts-head", contentType = "shifts-head") { ShiftsHeader(c.shiftsCaption) }
            items(c.shifts, key = { "shift-${it.key}" }, contentType = { "shift" }) { SalaryShiftRow(it) }
        }
        if (c.chart.isNotEmpty()) item(key = "chart", contentType = "chart") { ShiftsChart(c.chart) }
        if (c.hasDebts) item(key = "debts", contentType = "debts") { DebtsCard(c, onShowAll) }
        if (c.incidents.isNotEmpty()) item(key = "incidents", contentType = "incidents") { IncidentsCard(c.incidents) }
    }
}

/** Главная цифра на синей карточке, как баланс в банке: сколько получу, сколько уже выплачено. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SalaryHeroCard(c: MoneyContent) {
    HeroCard {
        Text(c.heroTitle, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(c.heroAmount, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        if (c.paidFraction != null) {
            WavyProgress(
                progress = { c.paidFraction },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
            )
            Text(c.paidCaption.orEmpty(), color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        }
        // FlowRow: при крупном шрифте подписи переносятся, а не выпадают за край.
        FlowRow(
            Modifier.padding(top = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            HeroFooter("Неделя", c.weekRange)
            HeroFooter("Статус", c.statusLabel)
        }
    }
}

@Composable
private fun HeroFooter(label: String, value: String) {
    Column {
        Text(label, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BreakdownCard(rows: List<BreakdownRow>) {
    val c = Orda.colors
    OrdaCard {
        SectionLabel("Из чего сложилось")
        rows.forEach { row ->
            if (row.divider) SoftDivider()
            StatRow(
                row.label,
                row.value,
                valueColor = when (row.tone) {
                    Tone.Normal -> c.text
                    Tone.Muted -> c.textMuted
                    Tone.Positive -> c.positive
                    Tone.Negative -> c.negative
                    Tone.Warning -> c.warning
                },
                emphasized = row.emphasized,
            )
        }
    }
}

/** За что начислено — по сменам: спор с управляющим начинается ровно отсюда. */
@Composable
private fun ShiftsHeader(caption: String?) {
    Column {
        SectionLabel("За что начислено")
        if (caption != null) Text(caption, color = Orda.colors.textMuted, fontSize = 12.sp)
    }
}

@Composable
private fun SalaryShiftRow(shift: ShiftRowUi) {
    OrdaCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(shift.title, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(shift.label, color = Orda.colors.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(shift.amount, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        // Из чего сложилась смена; выручка рядом — процент считается от неё.
        if (shift.details != null) Text(shift.details, color = Orda.colors.textDim, fontSize = 12.sp)
    }
}

/** Смены недели столбцами; сегодняшняя выделена, остальные приглушены. */
@Composable
private fun ShiftsChart(bars: List<ChartBarUi>) {
    val c = Orda.colors
    OrdaCard {
        SectionLabel("Смены недели")
        Row(
            Modifier.fillMaxWidth().height(130.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEach { bar ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(bar.fraction)
                                .background(
                                    if (bar.highlighted) c.brand else c.brand.copy(alpha = 0.35f),
                                    RoundedCornerShape(6.dp),
                                ),
                        )
                    }
                    Text(
                        bar.dayLabel,
                        color = if (bar.highlighted) c.brand else c.textDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }
        }
    }
}

/**
 * Долг выбранной недели. Экран про неделю — значит и карточка про неделю, но
 * весь непогашенный остаток прятать нельзя, это деньги: он строкой ниже и
 * открывается отдельным списком.
 */
@Composable
private fun DebtsCard(content: MoneyContent, onShowAll: () -> Unit) {
    val c = Orda.colors
    val weekEmpty = content.weekDebts.isEmpty()
    OrdaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Долг за неделю",
                color = if (weekEmpty) c.textDim else c.negative,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                content.weekDebtTotal,
                color = if (weekEmpty) c.textMuted else c.negative,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (weekEmpty) {
            Text("За эту неделю долгов нет.", color = c.textMuted, fontSize = 12.sp)
        } else {
            content.weekDebts.forEach { DebtLine(it) }
        }
        // Остальное — не за эту неделю, но никуда не делось.
        if (content.hasOtherDebts) {
            SoftDivider()
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onShowAll).padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Всего непогашено", color = c.textDim, fontSize = 15.sp)
                    Text("включая прошлые недели", color = c.textMuted, fontSize = 12.sp)
                }
                Text(content.totalDebt, color = c.negative, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Filled.ChevronRight, null, tint = c.textMuted)
            }
        }
    }
}

@Composable
private fun DebtLine(debt: DebtLineUi, indent: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (indent) Spacing.lg else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(debt.title, color = Orda.colors.textDim, fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f))
        Text(debt.amount, color = Orda.colors.negative, fontSize = 12.sp)
    }
}

@Composable
private fun IncidentsCard(incidents: List<IncidentUi>) {
    OrdaCard {
        SectionLabel("Штрафы и поощрения")
        incidents.forEach { incident ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(incident.title, color = Orda.colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (incident.amount != null) {
                        Text(
                            incident.amount,
                            color = if (incident.isPenalty) Orda.colors.negative else Orda.colors.positive,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (incident.description != null) {
                    Text(incident.description, color = Orda.colors.textDim, fontSize = 12.sp, maxLines = 2)
                }
            }
        }
    }
}

/**
 * Все непогашенные долги — по неделям. Отдельным списком: на экране недели
 * разговор про период, здесь — про остаток целиком. Недели свёрнуты: в одной
 * записи лежит весь список товаров.
 */
@Composable
private fun AllDebtsScreen(weeks: List<DebtWeekUi>, total: String, onBack: () -> Unit) {
    val c = Orda.colors
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    SubScreen("Непогашенный долг", onBack) {
        AdaptiveBox {
            ScreenList {
                item(key = "total", contentType = "total") {
                    OrdaCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Всего непогашено", color = c.textDim, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text(total, color = c.negative, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                items(weeks, key = { "week-${it.key}" }, contentType = { "week" }) { group ->
                    val open = group.key in expanded
                    OrdaCard {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { expanded = if (open) expanded - group.key else expanded + group.key }
                                .padding(vertical = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (open) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null, tint = c.textMuted)
                            Column(Modifier.weight(1f).padding(start = Spacing.xs)) {
                                Text(group.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(group.caption, color = c.textMuted, fontSize = 12.sp)
                            }
                            Text(group.total, color = c.negative, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (open) group.lines.forEach { DebtLine(it, indent = true) }
                    }
                }
                item(key = "hint", contentType = "hint") {
                    Text("Долг гасят на точке: отметить оплату может управляющий.", color = c.textMuted, fontSize = 12.sp)
                }
            }
        }
    }
}
