package kz.ordaops.android.feature.operator.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.operator.OperatorSection
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.feature.operator.common.operatorViewModel
import kz.ordaops.android.ui.components.CookieShape
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.theme.Navy
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing
import java.time.YearMonth
import java.time.format.TextStyle

private enum class ProfileRoute { Lead, SalesQuality }

/** Подписи и цвета пунктов профиля, которые ведут в другие разделы оператора. */
private fun sectionHint(section: OperatorSection): String? = when (section) {
    OperatorSection.Schedule -> "смены на неделю вперёд"
    OperatorSection.Money -> "зарплата, авансы, долги"
    OperatorSection.Arena -> "станции, сессии и продления"
    OperatorSection.Audit -> "пересчёт товара по актам"
    OperatorSection.Checklists -> "приём, обход, закрытие смены"
    OperatorSection.Knowledge -> "регламенты точки"
    OperatorSection.Chat -> "общий для всей точки"
    OperatorSection.Messages -> "лично сменщику или управляющему"
    else -> null
}

/**
 * Профиль оператора — порт `OperatorProfileScreen`: кто я, неделя, что ждёт
 * отправки, разделы и выход. `more` — разделы, которых нет в нижней панели.
 *
 * Пропущено: смена пароля, уведомления, вход по QR, экзамены, оформление и
 * биометрия — этих экранов на Android пока нет.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperatorProfileContent(more: List<OperatorSection>, onOpen: (OperatorSection) -> Unit) {
    val env = LocalOperatorEnv.current
    val vm = operatorViewModel { e -> MoneyViewModel(e.api) }
    val overview by vm.overview.collectAsStateWithLifecycle()
    val isLead by vm.isLead.collectAsStateWithLifecycle()
    val leadPending by vm.leadPendingCount.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var route by rememberSaveable { mutableStateOf<ProfileRoute?>(null) }
    var confirmingLogout by rememberSaveable { mutableStateOf(false) }
    val pending by env.outbox.pendingCount.collectAsStateWithLifecycle()
    var flushing by remember { mutableStateOf(false) }

    // Обзор заодно выясняет, старший ли человек, — пункт меню появляется только у старших.
    LaunchedEffect(Unit) { vm.refreshOverview() }

    when (route) {
        ProfileRoute.Lead -> {
            SubScreen("Старший смены", onBack = { route = null }) { LeadDeskContent(vm) }
            return
        }
        ProfileRoute.SalesQuality -> {
            SubScreen("Как я работаю", onBack = { route = null }) { SalesQualityContent(vm) }
            return
        }
        null -> Unit
    }

    val data = overview.data
    val name = data?.operatorName?.takeIf { it != "Оператор" } ?: env.resolver.session.displayName ?: "Оператор"
    val c = Orda.colors

    PullToRefreshBox(
        isRefreshing = overview.isRefreshing,
        onRefresh = vm::refreshOverview,
        modifier = Modifier.background(c.background),
    ) {
        AdaptiveBox {
            ScreenList {
                item(key = "header", contentType = "header") {
                    ProfileHeader(name, env.resolver.session.roleLabel ?: "Оператор", data?.week)
                }

                // Отложенное — сверху, пока оно есть: человек не должен уйти со смены, не зная, что часть работы на телефоне.
                if (pending > 0) {
                    item(key = "pending", contentType = "pending") {
                        OrdaCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                TintedIcon(Icons.Filled.Cached, c.warning, 42)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Ждёт отправки: $pending ${pluralize(pending, "действие", "действия", "действий")}",
                                        color = c.text,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "Сохранено на устройстве и уйдёт при связи. Не удаляйте приложение.",
                                        color = c.textDim,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        flushing = true
                                        env.outbox.flush()
                                        flushing = false
                                    }
                                },
                                enabled = !flushing,
                            ) { Text(if (flushing) "Отправляю…" else "Отправить сейчас") }
                        }
                    }
                }

                fun sectionRow(section: OperatorSection): (@Composable () -> Unit)? =
                    if (section in more) ({ LinkRow(section.icon, c.brand, section.title, sectionHint(section)) { onOpen(section) } }) else null

                val work = buildList<@Composable () -> Unit> {
                    sectionRow(OperatorSection.Schedule)?.let { add(it) }
                    if (isLead) {
                        add {
                            LinkRow(
                                Icons.Filled.ManageAccounts, c.warning, "Старший смены", "заявки команды и готовность недели",
                                badge = leadPending,
                            ) { route = ProfileRoute.Lead }
                        }
                    }
                    sectionRow(OperatorSection.Money)?.let { add(it) }
                    add { LinkRow(Icons.Filled.Insights, Color(0xFF0D9488), "Как я работаю", "оценка за месяц и доплата за качество") { route = ProfileRoute.SalesQuality } }
                    sectionRow(OperatorSection.Arena)?.let { add(it) }
                    sectionRow(OperatorSection.Audit)?.let { add(it) }
                    sectionRow(OperatorSection.Checklists)?.let { add(it) }
                }
                profileGroup("work", "Работа", work)
                profileGroup("knowledge", "Знания", listOfNotNull(sectionRow(OperatorSection.Knowledge)))
                profileGroup("comms", "Общение", listOfNotNull(sectionRow(OperatorSection.Chat), sectionRow(OperatorSection.Messages)))

                item(key = "logout", contentType = "logout") {
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
                }
                item(key = "delete-account", contentType = "delete-account") { DeleteAccountCard() }
            }
        }
    }

    if (confirmingLogout) {
        AlertDialog(
            onDismissRequest = { confirmingLogout = false },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("Неотправленные чеки останутся на устройстве.") },
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

private fun androidx.compose.foundation.lazy.LazyListScope.profileGroup(id: String, title: String, rows: List<@Composable () -> Unit>) {
    if (rows.isEmpty()) return
    item(key = "$id-title", contentType = "group-title") {
        Text(title, color = Orda.colors.textDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
    item(key = "$id-card", contentType = "group-card") {
        OrdaCard {
            rows.forEachIndexed { index, row ->
                if (index > 0) SoftDivider()
                row()
            }
        }
    }
}

@Composable
private fun ProfileHeader(name: String, roleLabel: String, week: SalaryWeek?) {
    val initials = remember(name) {
        name.split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().toString() }.uppercase().ifEmpty { "О" }
    }
    val net = remember(week?.netAmount) { week?.let { money(it.netAmount) } }
    HeroCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(
                Modifier.size(84.dp).background(Color.White, CookieShape).border(3.dp, Orda.colors.brandBright, CookieShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(initials, color = Navy, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Text(name, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
            Text(
                roleLabel,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.background(Color.White.copy(alpha = 0.16f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (week != null && net != null) {
                Row(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
                    HeroStat(net, "за неделю", Modifier.weight(1f))
                    HeroStat(week.statusLabel, "выплата", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center)
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

// ── Как я работаю ────────────────────────────────────────────────────────────

/**
 * «Как я работаю» — оценка работы с покупателями за месяц. Экран личный:
 * рейтинга команды нет намеренно. Порт `SalesQualityScreen.swift`.
 * `GET /api/operator/sales-kpi?month=YYYY-MM` отвечает `{ data: … }`.
 * Месяц живёт во ViewModel: поворот его не сбрасывает.
 */
@Composable
private fun SalesQualityContent(vm: MoneyViewModel) {
    val c = Orda.colors
    val month by vm.salesMonth.collectAsStateWithLifecycle()
    val state by vm.sales.collectAsStateWithLifecycle()
    val currentMonth = remember { YearMonth.now() }
    val selected = YearMonth.parse(month)

    LaunchedEffect(Unit) { vm.refreshSales() }

    AdaptiveBox {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = Spacing.lg).padding(top = Spacing.sm)) {
                PeriodStepper(
                    title = monthTitle(selected),
                    canGoForward = selected < currentMonth,
                    onBack = { vm.shiftSalesMonth(-1) },
                    onForward = { vm.shiftSalesMonth(1) },
                )
            }
            StateContainer(state, onRetry = vm::refreshSales, modifier = Modifier.weight(1f)) { r ->
                ScreenList {
                    if (r.available) {
                        item(key = "status", contentType = "status") { StatusCard(r) }
                        r.bonus?.let { bonus -> item(key = "bonus", contentType = "bonus") { BonusCard(bonus, r.status) } }
                        if (r.strengths.isNotEmpty() || r.weaknesses.isNotEmpty()) item(key = "sides", contentType = "sides") { SidesCard(r) }
                        item(key = "hint", contentType = "hint") {
                            Text(
                                "Оценка считается по вашим сменам и сравнивается с обычным для точки — с поправкой на день недели, погоду и загрузку. Слабый день сам по себе оценку не портит.",
                                color = c.textMuted,
                                fontSize = 12.sp,
                            )
                        }
                    } else {
                        item(key = "empty", contentType = "empty") {
                            EmptyState(
                                icon = Icons.Filled.ShoppingCart,
                                title = if (r.reason == "no-shifts") "Смен в этом месяце нет" else "Продаж в этом месяце нет",
                                message = if (r.reason == "no-shifts") "Оценка появится после первых смен за прилавком."
                                else "Экран считает работу за прилавком магазина. В этом месяце продаж под вашим именем не было.",
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun monthTitle(month: YearMonth): String =
    month.month.getDisplayName(TextStyle.FULL_STANDALONE, RuLocale).replaceFirstChar { it.uppercase() } + " " + month.year

@Composable
private fun statusColor(status: String): Color = when (status) {
    "TOP" -> Orda.colors.positive
    "STRONG" -> Orda.colors.brand
    "NEEDS_TRAINING" -> Orda.colors.warning
    else -> Orda.colors.info
}

private fun statusIcon(status: String): ImageVector = when (status) {
    "TOP" -> Icons.Filled.Star
    "STRONG" -> Icons.Filled.ThumbUp
    "NEEDS_TRAINING" -> Icons.Filled.Warning
    "INSUFFICIENT_DATA" -> Icons.Filled.HourglassEmpty
    else -> Icons.Filled.Equalizer
}

@Composable
private fun StatusCard(report: SalesQualityMonth) {
    val c = Orda.colors
    val color = statusColor(report.status)
    OrdaCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(statusIcon(report.status), null, tint = color)
            Text(report.statusLabel, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(report.statusMeaning, color = c.textDim, fontSize = 14.sp)
        SoftDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            Metric("СМЕН", report.shifts)
            Metric("ЧЕКОВ", report.receipts)
        }
    }
}

@Composable
private fun Metric(title: String, value: Int) {
    Column {
        Text(title, color = Orda.colors.textMuted, fontSize = 12.sp)
        Text("$value", color = Orda.colors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BonusCard(bonus: SalesQualityBonus, status: String) {
    val c = Orda.colors
    OrdaCard {
        Text("Доплата за качество", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("За работу с покупателями, сверх зарплаты", color = c.textMuted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                money(bonus.amount),
                color = if (bonus.amount > 0) c.positive else c.textDim,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            if (bonus.amount > 0) StatusChip(if (bonus.paid) "выплачена" else "в расчёте", if (bonus.paid) c.positive else c.warning)
        }
        bonus.nextStep?.let { Text(it, color = c.textDim, fontSize = 14.sp) }
        if (bonus.strong > 0 || bonus.top > 0) {
            SoftDivider()
            ThresholdRow("Сильный", bonus.strong, status == "STRONG" || status == "TOP")
            ThresholdRow("Топ", bonus.top, status == "TOP")
        }
    }
}

@Composable
private fun ThresholdRow(title: String, amount: Double, reached: Boolean) {
    val c = Orda.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Icon(
            if (reached) Icons.Filled.CheckCircle else Icons.Filled.Circle,
            null,
            tint = if (reached) c.positive else c.textMuted.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
        Text(title, color = c.textDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(money(amount), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SidesCard(report: SalesQualityMonth) {
    val c = Orda.colors
    OrdaCard {
        if (report.strengths.isNotEmpty()) SideList("ПОЛУЧАЕТСЯ", report.strengths, Icons.Filled.TrendingUp, c.positive)
        if (report.strengths.isNotEmpty() && report.weaknesses.isNotEmpty()) SoftDivider()
        if (report.weaknesses.isNotEmpty()) SideList("НАД ЧЕМ ПОРАБОТАТЬ", report.weaknesses, Icons.Filled.TrendingDown, c.warning)
    }
}

@Composable
private fun SideList(title: String, items: List<String>, icon: ImageVector, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, color = Orda.colors.textMuted, fontSize = 12.sp)
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
                Text(item, color = Orda.colors.text, fontSize = 14.sp)
            }
        }
    }
}
