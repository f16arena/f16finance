package kz.ordaops.android.feature.business.team

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonElement
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.HeroColors
import kz.ordaops.android.feature.business.HeroNumberCard
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.operator.money.StatRow
import kz.ordaops.android.feature.operator.money.TintedIcon
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

// ── Модель: GET /api/admin/operator-analytics ────────────────────────────────

/** Деньги человека за месяц. */
data class RosterMoney(
    val turnover: Double,
    val shifts: Int,
    val averagePerShift: Double,
    val share: Double,
    val autoDebts: Double,
    val manualMinus: Double,
    val manualPlus: Double,
    val advances: Double,
    val netEffect: Double,
) {
    val hasDeductions: Boolean get() = autoDebts > 0.01 || manualMinus > 0.01
}

data class RosterPerson(
    val id: String,
    val name: String,
    val isActive: Boolean,
    val position: String?,
    val phone: String?,
    val hireDate: String?,
    /** Самый срочный из документов человека (`ГГГГ-ММ-ДД`). */
    val nearestExpiry: String?,
) {
    fun tenureDays(today: LocalDate = LocalDate.now()): Int? =
        WeekMath.parse(hireDate)?.let { ChronoUnit.DAYS.between(it, today).toInt() }

    /** «12 дн.», «5 мес.», «2 г. 3 мес.». */
    fun tenureLabel(today: LocalDate = LocalDate.now()): String? {
        val days = tenureDays(today)?.takeIf { it >= 0 } ?: return null
        if (days < 31) return "$days дн."
        val months = days / 30
        if (months < 12) return "$months мес."
        val years = months / 12
        val rest = months % 12
        return if (rest == 0) "$years г." else "$years г. $rest мес."
    }

    fun daysToExpiry(today: LocalDate = LocalDate.now()): Int? =
        WeekMath.parse(nearestExpiry)?.let { ChronoUnit.DAYS.between(today, it).toInt() }

    /** Просрочен или кончается в течение месяца. */
    fun documentNeedsAttention(today: LocalDate = LocalDate.now()): Boolean = (daysToExpiry(today) ?: Int.MAX_VALUE) <= 30

    fun expiryLabel(today: LocalDate = LocalDate.now()): String? {
        val days = daysToExpiry(today) ?: return null
        return when {
            days < 0 -> "документ просрочен ${-days} ${plural(-days, "день", "дня", "дней")} назад"
            days == 0 -> "документ кончается сегодня"
            days <= 30 -> "документ кончается через $days ${plural(days, "день", "дня", "дней")}"
            else -> WeekMath.parse(nearestExpiry)?.let { "документ до ${WeekMath.dayMonth(it)} ${it.year}" }
        }
    }
}

data class OperatorRoster(val people: List<RosterPerson>, val money: Map<String, RosterMoney>) {
    companion object {
        fun from(root: JsonElement): OperatorRoster {
            val d = root.unwrapData().asObject()
            val profiles = d?.get("profiles").asObjects().associateBy { it.str("operator_id") ?: "" }
            // Ближайший срок на человека: список всех документов — работа за столом, здесь нужен сигнал.
            val soonest = mutableMapOf<String, String>()
            for (doc in d?.get("documents").asObjects()) {
                val id = doc.str("operator_id") ?: continue
                val date = doc.str("expiry_date")?.take(10)?.takeIf { WeekMath.parse(it) != null } ?: continue
                val current = soonest[id]
                if (current == null || date < current) soonest[id] = date
            }
            val rows = d?.get("money").asObject()?.get("rows").asObjects()
            val money = rows.mapNotNull { row ->
                val id = row.str("operator_id") ?: return@mapNotNull null
                id to RosterMoney(
                    turnover = row.dbl("turnover"), shifts = row.int("shifts"), averagePerShift = row.dbl("avg_per_shift"),
                    share = row.dbl("share"), autoDebts = row.dbl("auto_debts"), manualMinus = row.dbl("manual_minus"),
                    manualPlus = row.dbl("manual_plus"), advances = row.dbl("advances"), netEffect = row.dbl("net_effect"),
                )
            }.toMap()
            val people = d?.get("operators").asObjects().map { o ->
                val id = o.str("id") ?: ""
                val p = profiles[id]
                RosterPerson(
                    id = id, name = o.str("name") ?: o.str("short_name") ?: "Без имени", isActive = o.bool("is_active", true),
                    position = p?.str("position"), phone = p?.str("phone"), hireDate = p?.str("hire_date"), nearestExpiry = soonest[id],
                )
            }
            return OperatorRoster(people, money)
        }
    }
}

/** Границы месяца для запроса: `ГГГГ-ММ-01` — `ГГГГ-ММ-<последний день>`. */
fun monthBounds(month: YearMonth): Pair<String, String> =
    WeekMath.format(month.atDay(1)) to WeekMath.format(month.atEndOfMonth())

// ── Экран ────────────────────────────────────────────────────────────────────

/** Команда: стаж, сроки документов и деньги за месяц по каждому оператору. Порт `OperatorRosterScreen`. */
@Composable
fun OperatorRosterScreen() {
    val bounds = remember { monthBounds(YearMonth.now()) }
    val vm = rememberResource(
        "operator-roster", bounds,
        { (from, to) -> ApiRequest("/api/admin/operator-analytics", query = mapOf("from" to from, "to" to to)) },
        OperatorRoster::from,
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showInactive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    val selected = state.data?.people?.firstOrNull { it.id == selectedId }
    if (selected != null) {
        BackHandler { selectedId = null }
        RosterPersonCard(selected, state.data?.money?.get(selected.id))
        return
    }
    StateContainer(
        state = state, onRetry = vm::reload,
        isEmpty = { it.people.isEmpty() },
        emptyTitle = "Операторов нет", emptyMessage = "Заведите операторов — здесь появятся стаж и сроки документов.",
    ) { roster ->
        val c = Orda.colors
        val needle = query.trim().lowercase()
        // Сначала те, у кого горит документ, потом по стажу: список нужен, чтобы что-то сделать, а не чтобы полюбоваться алфавитом.
        val people = remember(roster, showInactive, needle) {
            roster.people.filter { showInactive || it.isActive }
                .filter { needle.isEmpty() || it.name.lowercase().contains(needle) || (it.position ?: "").lowercase().contains(needle) }
                .sortedWith(compareByDescending<RosterPerson> { it.documentNeedsAttention() }.thenByDescending { it.tenureDays() ?: -1 })
        }
        val attention = people.count { it.documentNeedsAttention() }
        OwnerScreenColumn {
            if (attention > 0) {
                Row(
                    Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(24.dp)).padding(Spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    TintedIcon(Icons.Filled.Warning, c.warning, 44, corner = 22.dp)
                    Column {
                        Text("$attention ${plural(attention, "документ", "документа", "документов")} требует внимания", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Просрочен или кончается в течение месяца. Такие люди наверху списка.", color = c.textMuted, fontSize = 13.sp)
                    }
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Имя или должность") }, shape = RoundedCornerShape(Radius.md),
                colors = kz.ordaops.android.feature.operator.shift.ordaFieldColors(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Switch(checked = showInactive, onCheckedChange = { showInactive = it })
                Text("Показывать уволенных", color = c.textMuted, fontSize = 14.sp)
            }
            if (people.isEmpty()) {
                TeamHint(if (query.isEmpty()) "Операторов нет" else "По запросу «$query» никого.")
            } else {
                OwnerSection("Команда", trailing = { Text("${people.size}", color = c.textDim, fontSize = 14.sp) }) {
                    people.forEachIndexed { i, p ->
                        if (i > 0) TeamDivider()
                        RosterRow(p, roster.money[p.id]) { selectedId = p.id }
                    }
                }
            }
        }
    }
}

@Composable
private fun RosterRow(p: RosterPerson, m: RosterMoney?, onClick: () -> Unit) {
    val c = Orda.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        PersonInitial(p.name, p.isActive)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (!p.isActive) Text("уволен", color = c.textDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            p.position?.takeIf { it.isNotEmpty() }?.let { Text(it, color = c.textDim, fontSize = 13.sp) }
            // В списке — одна цифра, по которой людей и сравнивают: средняя смена.
            if (m != null && m.shifts > 0) {
                Text("ср. смена ${money(m.averagePerShift)} · ${m.shifts} ${plural(m.shifts, "смена", "смены", "смен")}", color = c.textDim, fontSize = 13.sp, maxLines = 1)
            }
            p.expiryLabel()?.let { Text(it, color = if (p.documentNeedsAttention()) c.warning else c.textDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        }
        p.tenureLabel()?.let {
            Column(horizontalAlignment = Alignment.End) {
                Text(it, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("стаж", color = c.textDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun RosterPersonCard(p: RosterPerson, m: RosterMoney?) {
    val context = LocalContext.current
    val c = Orda.colors
    OwnerScreenColumn {
        Column(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PersonInitial(p.name, p.isActive, 72)
            Text(p.name, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            p.position?.takeIf { it.isNotEmpty() }?.let { Text(it, color = c.textMuted, fontSize = 15.sp) }
            if (!p.isActive) Text("уволен", color = c.textDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        // Средняя смена — главной цифрой: оборот зависит от числа смен, и сравнивать по нему нечестно.
        if (m != null) {
            HeroNumberCard(
                title = "Средняя смена за месяц", value = m.averagePerShift, change = null, changeCaption = "",
                footer = buildList {
                    add("Смен" to "${m.shifts}")
                    add("Оборот" to money(m.turnover))
                    if (m.share > 0) add("Доля" to BizFormat.percent(m.share * 100))
                },
                colors = HeroColors.Accent, onClick = {},
            )
            if (m.manualPlus > 0.01 || m.hasDeductions || m.advances > 0.01) {
                OwnerSection("Премии и удержания") {
                    if (m.manualPlus > 0.01) MoneyLine("Премии", Icons.Filled.CardGiftcard, c.positive, signedMoney(m.manualPlus), c.positive)
                    if (m.manualMinus > 0.01) MoneyLine("Штрафы", Icons.Filled.Report, c.negative, signedMoney(-m.manualMinus), c.negative)
                    if (m.autoDebts > 0.01) MoneyLine("Долги", Icons.Filled.CreditCard, Color(0xFFF59E0B), signedMoney(-m.autoDebts), c.negative)
                    if (m.advances > 0.01) MoneyLine("Авансы", Icons.Filled.Payments, Color(0xFF3B82F6), money(m.advances), c.text)
                    if (m.hasDeductions || m.manualPlus > 0.01) {
                        TeamDivider()
                        StatRow("Итого", signedMoney(m.netEffect), if (m.netEffect < 0) c.negative else c.positive, emphasized = true)
                    }
                }
            }
        }
        OwnerSection("О сотруднике") {
            p.tenureLabel()?.let { StatRow("Стаж", it) }
            WeekMath.parse(p.hireDate)?.let { StatRow("Принят", "${WeekMath.dayMonth(it)} ${it.year}") }
            p.phone?.takeIf { it.isNotEmpty() }?.let { phone ->
                // Телефон нажимается: человека с горящим документом проще набрать сразу.
                Row(Modifier.fillMaxWidth().clickable {
                    val digits = phone.filter { it.isDigit() || it == '+' }
                    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, "tel:$digits".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) {
                    Text("Телефон", color = c.textMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(phone, color = c.brand, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        p.expiryLabel()?.let { label ->
            OwnerSection("Документы") {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    TintedIcon(if (p.documentNeedsAttention()) Icons.Filled.Warning else Icons.Filled.Description, if (p.documentNeedsAttention()) c.warning else Color(0xFF3B82F6), 40)
                    Column {
                        Text(label, color = if (p.documentNeedsAttention()) c.warning else c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("Сроки заводятся на сайте, в карточке оператора.", color = c.textDim, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MoneyLine(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        TintedIcon(icon, tint, 36)
        Text(title, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
