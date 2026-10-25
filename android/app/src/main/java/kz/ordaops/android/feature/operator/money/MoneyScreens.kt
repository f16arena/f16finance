package kz.ordaops.android.feature.operator.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing
import java.time.LocalDate
import java.time.format.TextStyle
import kotlin.math.abs

/**
 * «Мои деньги» — зарплата за неделю: из чего сложилась, что выплачено, что
 * удержано. Порт `MoneyScreen` из OperatorMoneyScreens.swift.
 */
@Composable
fun MoneyTab() {
    val env = LocalOperatorEnv.current
    val store = remember { MoneyStore(env.api) }
    val scope = rememberCoroutineScope()
    var showAllDebts by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun reload() = coroutineScope {
        launch { store.loadSalary() }
        launch { store.loadIncidents() }
        launch { store.loadOverview() }
    }

    LaunchedEffect(Unit) {
        launch { store.loadOverview() }
        launch { store.loadIncidents() }
    }
    // Переключили неделю — грузим её расчёт.
    LaunchedEffect(store.salaryWeek) { store.loadSalary() }

    if (showAllDebts) {
        AllDebtsScreen(
            weeks = groupDebtsByWeek(store.overview?.recentDebts.orEmpty().filter { it.amount > 0 }),
            currentWeek = store.salaryWeek,
            onBack = { showAllDebts = false },
        )
        return
    }

    RefreshColumn(
        refreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                reload()
                refreshing = false
            }
        },
    ) {
        Text("Мои деньги", color = Orda.colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        store.error?.let { ErrorBanner(it) }

        PeriodStepper(
            title = weekTitle(store.salaryWeek),
            caption = if (store.salaryWeek == currentWeekStart()) "текущая неделя" else null,
            canGoForward = store.salaryWeek < currentWeekStart(),
            onBack = { store.shiftSalaryWeek(-1) },
            onForward = { store.shiftSalaryWeek(1) },
        )

        val week = store.salary?.week ?: store.overview?.week
        if (week == null) {
            if (store.error == null) {
                Box(Modifier.fillMaxWidth().height(140.dp).background(Orda.colors.surface, RoundedCornerShape(20.dp)))
            }
        } else {
            SalaryHeroCard(week)
            BreakdownCard(week)
            ShiftsCard(store.salary?.shifts.orEmpty().filter { it.salary > 0 })
            val shiftBars = store.salary?.shifts.orEmpty()
            if (shiftBars.any { it.salary > 0 }) ShiftsChart(shiftBars)
            DebtsCard(store, onShowAll = { showAllDebts = true })
            IncidentsCard(store.incidents)
        }
    }
}

/** Главная цифра на синей карточке, как баланс в банке: сколько получу, сколько уже выплачено. */
@Composable
private fun SalaryHeroCard(week: SalaryWeek) {
    val remaining = if (week.paidAmount > 0) maxOf(week.remainingAmount, 0.0) else week.netAmount
    HeroCard {
        Text(
            if (week.paidAmount > 0) "Осталось получить" else "К выплате за неделю",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(money(remaining), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        if (week.paidAmount > 0 && week.netAmount > 0) {
            LinearProgressIndicator(
                progress = { (week.paidAmount / week.netAmount).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
            )
            Text(
                "Выплачено ${money(week.paidAmount)} из ${money(week.netAmount)}",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 13.sp,
            )
        }
        Row(Modifier.padding(top = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            HeroFooter("Неделя", weekRange(week))
            HeroFooter("Статус", week.statusLabel)
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

private fun weekRange(week: SalaryWeek): String {
    val start = parseDate(week.weekStart)
    val end = parseDate(week.weekEnd)
    if (start == null || end == null) return "—"
    return "${dayMonth(start)} — ${dayMonth(end)}"
}

@Composable
private fun BreakdownCard(week: SalaryWeek) {
    val c = Orda.colors
    OrdaCard {
        SectionLabel("Из чего сложилось")
        StatRow("Начислено за смены", money(week.grossAmount))
        if (week.seniorityBonusTotal > 0) {
            // Стаж уже внутри начисления за смены — справочно, к итогу отдельной суммой не идёт.
            StatRow("· из них надбавка за стаж", money(week.seniorityBonusTotal), valueColor = c.textMuted)
        }
        // Автобонус считают правила точки; без него строки не сходились с итогом.
        if (week.autoBonusTotal > 0) StatRow("Бонусы за смены", moneySigned(week.autoBonusTotal), valueColor = c.positive)
        if (week.bonusAmount > 0) StatRow("Бонусы", moneySigned(week.bonusAmount), valueColor = c.positive)
        if (week.fineAmount > 0) StatRow("Штрафы", moneySigned(-week.fineAmount), valueColor = c.negative)
        if (week.advanceAmount > 0) StatRow("Аванс", moneySigned(-week.advanceAmount), valueColor = c.warning)
        if (week.debtAmount > 0) StatRow("Удержано в счёт долга", moneySigned(-week.debtAmount), valueColor = c.negative)

        // Если сервер добавит составляющую, о которой приложение не знает, разница окажется здесь.
        val rest = week.unexplainedAmount
        if (abs(rest) >= 1) {
            StatRow(
                if (rest > 0) "Прочие начисления" else "Прочие удержания",
                moneySigned(rest),
                valueColor = if (rest > 0) c.positive else c.negative,
            )
        }
        SoftDivider()
        StatRow("Итого к выплате", money(week.netAmount), emphasized = true)
    }
}

/** За что начислено — по сменам: спор с управляющим начинается ровно отсюда. */
@Composable
private fun ShiftsCard(shifts: List<SalaryShift>) {
    if (shifts.isEmpty()) return
    OrdaCard {
        SectionLabel("За что начислено")
        Text(
            "${shifts.size} ${pluralize(shifts.size, "смена", "смены", "смен")}",
            color = Orda.colors.textMuted,
            fontSize = 12.sp,
        )
        shifts.forEachIndexed { i, shift ->
            if (i > 0) SoftDivider()
            SalaryShiftRow(shift)
        }
    }
}

@Composable
private fun SalaryShiftRow(shift: SalaryShift) {
    Column(Modifier.padding(vertical = Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(dayTitle(shift.date), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(shift.shiftLabel, color = Orda.colors.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(money(shift.salary), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        // Из чего сложилась смена; выручка рядом — процент считается от неё.
        val parts = buildList {
            if (shift.baseSalary > 0) add("ставка ${money(shift.baseSalary)}")
            if (shift.seniorityBonus > 0) {
                val percent = if (shift.seniorityPercent > 0) " (${percentText(shift.seniorityPercent)})" else ""
                add("стаж ${money(shift.seniorityBonus)}$percent")
            }
            if (shift.autoBonus > 0) add("бонус ${money(shift.autoBonus)}")
            if (shift.roleBonus > 0) add("за роль ${money(shift.roleBonus)}")
            if (shift.totalIncome > 0) add("выручка ${money(shift.totalIncome)}")
            if (!shift.companyName.isNullOrEmpty()) add(shift.companyName)
        }
        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = Orda.colors.textDim, fontSize = 12.sp)
    }
}

/** Смены недели столбцами; сегодняшняя выделена, остальные приглушены. */
@Composable
private fun ShiftsChart(shifts: List<SalaryShift>) {
    val c = Orda.colors
    val today = LocalDate.now().toString()
    val maxValue = shifts.maxOf { it.salary }.coerceAtLeast(1.0)
    OrdaCard {
        SectionLabel("Смены недели")
        Row(
            Modifier.fillMaxWidth().height(130.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            shifts.forEach { shift ->
                val highlighted = shift.date == today
                val fraction = (shift.salary / maxValue).toFloat().coerceIn(0.03f, 1f)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(fraction)
                                .background(
                                    if (highlighted) c.brand else c.brand.copy(alpha = 0.35f),
                                    RoundedCornerShape(6.dp),
                                ),
                        )
                    }
                    Text(
                        parseDate(shift.date)?.dayOfWeek?.getDisplayName(TextStyle.SHORT, RuLocale) ?: shift.date,
                        color = if (highlighted) c.brand else c.textDim,
                        fontSize = 11.sp,
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
private fun DebtsCard(store: MoneyStore, onShowAll: () -> Unit) {
    val c = Orda.colors
    val all = store.overview?.recentDebts.orEmpty().filter { it.amount > 0 }
    if (all.isEmpty()) return
    val week = all.filter { (it.weekStart ?: "") == store.salaryWeek }
    val weekTotal = week.sumOf { it.amount }
    val total = all.sumOf { it.amount }
    OrdaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Долг за неделю",
                color = if (week.isEmpty()) c.textDim else c.negative,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                money(weekTotal),
                color = if (week.isEmpty()) c.textMuted else c.negative,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (week.isEmpty()) {
            Text("За эту неделю долгов нет.", color = c.textMuted, fontSize = 12.sp)
        } else {
            week.forEach { DebtLine(it) }
        }
        // Остальное — не за эту неделю, но никуда не делось.
        if (total - weekTotal > 0) {
            SoftDivider()
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onShowAll).padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Всего непогашено", color = c.textDim, fontSize = 15.sp)
                    Text("включая прошлые недели", color = c.textMuted, fontSize = 12.sp)
                }
                Text(money(total), color = c.negative, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Filled.ChevronRight, null, tint = c.textMuted)
            }
        }
    }
}

@Composable
private fun DebtLine(debt: OperatorDebt, indent: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (indent) Spacing.lg else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(debtTitle(debt), color = Orda.colors.textDim, fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f))
        Text(money(debt.amount), color = Orda.colors.negative, fontSize = 12.sp)
    }
}

@Composable
private fun IncidentsCard(incidents: List<OperatorIncident>) {
    if (incidents.isEmpty()) return
    OrdaCard {
        SectionLabel("Штрафы и поощрения")
        incidents.take(8).forEach { incident ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        incident.title ?: if (incident.isPenalty) "Штраф" else "Поощрение",
                        color = Orda.colors.text,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    incident.amount?.let { amount ->
                        Text(
                            moneySigned(if (incident.isPenalty) -abs(amount) else abs(amount)),
                            color = if (incident.isPenalty) Orda.colors.negative else Orda.colors.positive,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (!incident.description.isNullOrEmpty()) {
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
private fun AllDebtsScreen(weeks: List<DebtWeek>, currentWeek: String, onBack: () -> Unit) {
    val c = Orda.colors
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    SubScreen("Непогашенный долг", onBack) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            OrdaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Всего непогашено", color = c.textDim, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(money(weeks.sumOf { it.total }), color = c.negative, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
            OrdaCard {
                weeks.forEachIndexed { index, group ->
                    if (index > 0) SoftDivider()
                    val open = group.key in expanded
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { expanded = if (open) expanded - group.key else expanded + group.key }
                            .padding(vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (open) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                            null,
                            tint = c.textMuted,
                        )
                        Column(Modifier.weight(1f).padding(start = Spacing.xs)) {
                            Text(group.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "${group.debts.size} ${pluralize(group.debts.size, "запись", "записи", "записей")}" +
                                    if (group.key == currentWeek) " · текущая" else "",
                                color = c.textMuted,
                                fontSize = 12.sp,
                            )
                        }
                        Text(money(group.total), color = c.negative, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (open) group.debts.forEach { DebtLine(it, indent = true) }
                }
            }
            Text("Долг гасят на точке: отметить оплату может управляющий.", color = c.textMuted, fontSize = 12.sp)
        }
    }
}
