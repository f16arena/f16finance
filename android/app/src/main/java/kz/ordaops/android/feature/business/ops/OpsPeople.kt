package kz.ordaops.android.feature.business.ops

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonObject
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.operator.money.HeroCard
import kz.ordaops.android.feature.operator.money.SoftDivider
import kz.ordaops.android.feature.operator.money.StatRow
import kz.ordaops.android.feature.operator.money.StatusChip
import kz.ordaops.android.feature.operator.sale.ScannerPane
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// Инциденты и клиенты: списки, карточки, постановка записи, баллы лояльности.

private const val INCIDENTS_PATH = "/api/admin/incidents"
private const val CUSTOMERS_PATH = "/api/admin/customers"

private fun parseIncidents(root: JsonObject): List<OpsIncident> {
    val rows = root.obj("data")?.arr("incidents") ?: root.arr("incidents")
    return rows.map(OpsIncident::parse).filter { it.id.isNotEmpty() }
}

// ── Инциденты ────────────────────────────────────────────────────────────────

/** Штрафы, бонусы и заметки по команде. Порт `IncidentsScreen`: сводка, фильтр «ждут решения», решение, запись. */
@Composable
fun IncidentsScreen() {
    val env = LocalBusiness.current
    val canCreate = env.can("incidents.create")
    val loader = rememberOpsLoader("incidents", ApiRequest(INCIDENTS_PATH), ::parseIncidents)
    val state by loader.state.collectAsStateWithLifecycle()
    var onlyPending by rememberSaveable { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    val all = state.data.orEmpty()
    val totals = incidentTotals(all)
    val shown = state.copy(
        data = state.data?.let { list ->
            (if (onlyPending) list.filter { it.isPending } else list).sortedByDescending { it.occurredAt.orEmpty() }
        },
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.data != null) {
                Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    HeroCard {
                        Text("Бонусы минус штрафы", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                        Text(signedMoney(totals.net), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Text(if (totals.pending > 0) "${totals.pending} ждут решения" else "всё разобрано", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                        Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                            HeroFact("Штрафы", money(totals.fines))
                            HeroFact("Бонусы", money(totals.bonuses))
                        }
                    }
                    PillSegment(listOf(false to "Все", true to "Ждут решения"), onlyPending) { onlyPending = it }
                }
            }
            StateContainer(
                state = shown,
                onRetry = loader::refresh,
                modifier = Modifier.weight(1f),
                isEmpty = { it.isEmpty() },
                emptyIcon = Icons.Filled.Shield,
                emptyTitle = if (onlyPending) "Нет ожидающих решения" else "Инцидентов нет",
                emptyMessage = "Здесь появятся штрафы, бонусы и заметки по команде.",
            ) { rows ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = 96.dp)) {
                    items(rows, key = { it.id }, contentType = { "incident" }) { item -> IncidentRow(item) { detailId = item.id } }
                }
            }
        }
        if (canCreate) BoxScopeFab(Modifier.align(Alignment.BottomEnd), description = "Новый инцидент") { adding = true }
    }
    all.firstOrNull { it.id == detailId }?.let { IncidentSheet(it, onDismiss = { detailId = null }, onChanged = loader::refresh) }
    if (adding) IncidentEditorSheet(onDismiss = { adding = false }, onSaved = { adding = false; loader.refresh() })
}

@Composable
internal fun HeroFact(label: String, value: String) {
    Column {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun IncidentRow(incident: OpsIncident, onClick: () -> Unit) {
    val tint = if (incident.isBonus) Orda.colors.positive else if (incident.isFine) Orda.colors.negative else Orda.colors.textDim
    OpsRow(
        icon = if (incident.isBonus) Icons.Filled.CardGiftcard else if (incident.isFine) Icons.Filled.Warning else Icons.Filled.NoteAlt,
        tint = tint,
        title = incident.title,
        subtitle = listOfNotNull(incident.subjectName, incident.companyName).joinToString(" · "),
        trailing = incident.netAmount.takeIf { it != 0.0 }?.let(::signedMoney),
        trailingColor = if (incident.netAmount > 0) Orda.colors.positive else Orda.colors.negative,
        trailingSub = if (incident.isPending) "ждёт" else null,
        onClick = onClick,
    )
}

/** Карточка инцидента; если он ждёт решения и есть право `incidents.update` — три решения. */
@Composable
private fun IncidentSheet(incident: OpsIncident, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val env = LocalBusiness.current
    val canDecide = env.can("incidents.update") && incident.status == "draft"
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    OrdaSheet("Инцидент", onDismiss) {
        submit.error?.let { ErrorBanner(it) }
        OrdaCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(incident.title, color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                StatusChip(incident.statusLabel, if (incident.isPending) Orda.colors.warning else Orda.colors.positive)
            }
            Text(incident.kindLabel, color = Orda.colors.textDim, fontSize = 13.sp)
            incident.details?.takeIf { it.isNotBlank() }?.let { Text(it, color = Orda.colors.textMuted, fontSize = 15.sp) }
            SoftDivider()
            incident.subjectName?.let { StatRow("Сотрудник", it) }
            incident.companyName?.let { StatRow("Точка", it) }
            dateTimeLabel(incident.occurredAt)?.let { StatRow("Когда", it) }
            if (incident.fineAmount > 0) StatRow("Штраф", money(incident.fineAmount), valueColor = Orda.colors.negative)
            if (incident.bonusAmount > 0) StatRow("Бонус", money(incident.bonusAmount), valueColor = Orda.colors.positive)
            if (incident.photoCount > 0) StatRow("Фото", "${incident.photoCount}")
        }
        if (canDecide) {
            // Разбор инцидента — решение: признать, оспорить или отменить.
            IncidentDecision.entries.forEach { decision ->
                PrimaryButton(decision.title, Modifier.fillMaxWidth(), loading = submit.busy) {
                    submit.run(
                        { env.api.sendUnit(ApiRequest.json("$INCIDENTS_PATH/${incident.id}", incidentStatusBody(decision), HttpMethod.PATCH)) },
                        { haptics.success(); onChanged(); onDismiss() },
                    )
                }
            }
        }
    }
}

/** Регистрация инцидента на месте: событие записывают сразу, а не по памяти через день. */
@Composable
private fun IncidentEditorSheet(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val env = LocalBusiness.current
    val companies = rememberCompanyOptions()
    val staff = rememberStaffPeople()
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    var kind by rememberSaveable { mutableStateOf(IncidentKind.Violation.raw) }
    var title by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf("") }
    var company by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf("") }
    var severity by rememberSaveable { mutableStateOf(IncidentSeverity.Normal.raw) }
    var amount by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(companies) { if (company.isEmpty() && companies.size == 1) company = companies[0].id }

    val kindValue = IncidentKind.entries.first { it.raw == kind }
    val value = parseAmountInput(amount)
    val draft = IncidentDraft(
        companyId = company, kind = kindValue, title = title, details = details,
        severity = IncidentSeverity.entries.first { it.raw == severity },
        fineAmount = if (kindValue == IncidentKind.Violation) value else 0.0,
        bonusAmount = if (kindValue == IncidentKind.Bonus) value else 0.0,
        subjectStaffId = subject.ifEmpty { null },
    )
    OrdaSheet("Новый инцидент", onDismiss) {
        PillSegment(IncidentKind.entries.map { it.raw to it.title }, kind) { kind = it }
        OpsField("Что произошло", title, { title = it }, placeholder = "Коротко: суть")
        OpsField("Подробности", details, { details = it }, placeholder = "Необязательно", minLines = 2)
        OpsPicker("Точка", listOf("" to "Не выбрана") + companies.map { it.id to it.name }, company, { company = it })
        // Инцидент бывает про точку, а не про человека: разбитая витрина ничья.
        OpsPicker("Кого касается", listOf("" to "Никого конкретно") + staff.map { it.id to it.name }, subject, { subject = it })
        Text("Серьёзность", color = Orda.colors.textDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        PillSegment(IncidentSeverity.entries.map { it.raw to it.title }, severity) { severity = it }
        // Деньги — только там, где они бывают: у заметки суммы нет.
        if (kindValue != IncidentKind.Note) {
            OpsField(
                if (kindValue == IncidentKind.Bonus) "Премия" else "Штраф", amount,
                { amount = it.filter { c -> c.isDigit() || c == ',' || c == '.' } },
                keyboardType = KeyboardType.Decimal, singleLine = true, suffix = "₸",
            )
            Text("Сумма попадёт в расчёт зарплаты за неделю.", color = Orda.colors.textDim, fontSize = 12.sp)
        }
        draft.validationMessage?.let { Text(it, color = Orda.colors.warning, fontSize = 14.sp) }
        submit.error?.let { ErrorBanner(it) }
        PrimaryButton("Записать", Modifier.fillMaxWidth(), enabled = draft.isValid, loading = submit.busy) {
            submit.run({ env.api.sendUnit(ApiRequest.json(INCIDENTS_PATH, draft.body())) }, { haptics.success(); onSaved() })
        }
    }
}

// ── Клиенты ──────────────────────────────────────────────────────────────────

private fun parseCustomers(root: JsonObject): List<OpsCustomer> = root.items("data").map(OpsCustomer::parse).filter { it.id.isNotEmpty() }

/** База клиентов с лояльностью: поиск, карточка, новый клиент, корректировка баллов. Порт `CustomersScreen`. */
@Composable
fun CustomersScreen() {
    val env = LocalBusiness.current
    val canCreate = env.can("customers.create")
    val loader = rememberOpsLoader("customers", ApiRequest(CUSTOMERS_PATH), ::parseCustomers)
    val state by loader.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val shown = state.copy(data = state.data?.filter { it.matches(query) })

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) { OpsSearchField(query, { query = it }, "Имя, телефон или карта") }
            StateContainer(
                state = shown,
                onRetry = loader::refresh,
                modifier = Modifier.weight(1f),
                isEmpty = { it.isEmpty() },
                emptyIcon = Icons.Filled.Group,
                emptyTitle = if (query.isEmpty()) "Клиентов нет" else "Никого не найдено",
                emptyMessage = if (query.isEmpty()) "База наполнится с первыми продажами по карте." else "Попробуйте другой запрос.",
            ) { rows ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = 96.dp)) {
                    items(rows, key = { it.id }, contentType = { "customer" }) { c ->
                        val visits = "${c.visitsCount} ${plural(c.visitsCount, "визит", "визита", "визитов")}"
                        Row(
                            Modifier.fillMaxWidth().clickable { detailId = c.id }.padding(vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            Initials(c.initials.ifEmpty { "?" })
                            Column(Modifier.weight(1f)) {
                                Text(c.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text("${c.phone ?: c.cardNumber ?: "без контакта"} · $visits", color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1)
                            }
                            Text(money(c.totalSpent), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        if (canCreate) BoxScopeFab(Modifier.align(Alignment.BottomEnd), description = "Новый клиент") { adding = true }
    }
    state.data?.firstOrNull { it.id == detailId }?.let { CustomerSheet(it, onDismiss = { detailId = null }, onChanged = loader::refresh) }
    if (adding) AddCustomerSheet(onDismiss = { adding = false }, onSaved = { adding = false; loader.refresh() })
}

@Composable
internal fun Initials(text: String, size: Int = 40) {
    Box(Modifier.size(size.dp).background(Orda.colors.brand.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
        Text(text, color = Orda.colors.brand, fontSize = (size * 0.4f).sp, fontWeight = FontWeight.Bold)
    }
}

/** Карточка клиента: потрачено, визиты, чек, баллы, контакты; «Баллы» — по праву `customers.adjust_points`. */
@Composable
private fun CustomerSheet(customer: OpsCustomer, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val env = LocalBusiness.current
    val canAdjust = env.can("customers.adjust_points")
    var adjusting by remember { mutableStateOf(false) }
    OrdaSheet(customer.name, onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Initials(customer.initials.ifEmpty { "?" }, 64)
            Column(Modifier.weight(1f)) {
                Text(customer.name, color = Orda.colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                customer.companyName?.let { Text(it, color = Orda.colors.textDim, fontSize = 14.sp) }
            }
        }
        HeroCard {
            Text("Потрачено", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
            Text(money(customer.totalSpent), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                HeroFact("Визитов", "${customer.visitsCount}")
                HeroFact("Средний чек", money(customer.averageCheck))
                HeroFact("Баллы", pointsText(customer.loyaltyPoints))
            }
        }
        if (canAdjust) {
            PrimaryButton("Начислить или списать баллы", Modifier.fillMaxWidth()) { adjusting = true }
        }
        OpsSection("Контакты") {
            customer.phone?.takeIf { it.isNotEmpty() }?.let { OpsRow(Icons.Filled.Phone, Color(0xFF10B981), "Телефон", trailing = it) }
            customer.cardNumber?.takeIf { it.isNotEmpty() }?.let { OpsRow(Icons.Filled.CreditCard, Color(0xFF3B82F6), "Карта", trailing = it) }
            OpsRow(Icons.Filled.Star, Color(0xFFF59E0B), "Баллы", trailing = pointsText(customer.loyaltyPoints))
        }
    }
    if (adjusting) AdjustPointsSheet(customer, onDismiss = { adjusting = false }, onDone = { adjusting = false; onChanged() })
}

/** Корректировка бонусов: «начислите, у меня не прошло» решается на месте. Причина остаётся в журнале. */
@Composable
private fun AdjustPointsSheet(customer: OpsCustomer, onDismiss: () -> Unit, onDone: () -> Unit) {
    val env = LocalBusiness.current
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    var adding by rememberSaveable { mutableStateOf(true) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    val amount = parsePointsInput(amountText)
    val resulting = resultingPoints(customer.loyaltyPoints, amount, adding)
    OrdaSheet("Баллы", onDismiss) {
        Text(customer.name, color = Orda.colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Сейчас ${pointsText(customer.loyaltyPoints)} ${plural(customer.loyaltyPoints.toInt(), "балл", "балла", "баллов")}",
            color = Orda.colors.textDim, fontSize = 13.sp,
        )
        PillSegment(listOf(true to "Начислить", false to "Списать"), adding) { adding = it }
        OpsField("Баллов", amountText, { amountText = it.filter(Char::isDigit) }, keyboardType = KeyboardType.Number, singleLine = true)
        if (amount > 0) {
            StatRow("Станет", pointsText(maxOf(0.0, resulting)), valueColor = if (resulting < 0) Orda.colors.negative else Orda.colors.text, emphasized = true)
            if (resulting < 0) Text("У клиента столько баллов нет — списать больше, чем есть, не выйдет.", color = Orda.colors.negative, fontSize = 12.sp)
        }
        OpsField("Причина", reason, { reason = it }, placeholder = "Например: не прошло начисление по чеку", minLines = 2)
        Text(
            "Причина остаётся в журнале: корректировка баллов — это деньги, и через месяц никто не вспомнит, почему их начислили.",
            color = Orda.colors.textDim, fontSize = 12.sp,
        )
        submit.error?.let { ErrorBanner(it) }
        PrimaryButton(if (adding) "Начислить" else "Списать", Modifier.fillMaxWidth(), enabled = amount > 0 && resulting >= 0, loading = submit.busy) {
            submit.run(
                { env.api.sendUnit(ApiRequest.json(CUSTOMERS_PATH, adjustPointsBody(customer.id, if (adding) amount else -amount, reason))) },
                { haptics.success(); onDone() },
            )
        }
    }
}

/** Карту оформляют при человеке, у кассы: имя обязательно, карту можно привязать позже. */
@Composable
private fun AddCustomerSheet(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val env = LocalBusiness.current
    val companies = rememberCompanyOptions()
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var card by rememberSaveable { mutableStateOf("") }
    var company by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    OrdaSheet("Новый клиент", onDismiss) {
        OpsField("Имя", name, { name = it }, placeholder = "Иван Иванов", singleLine = true)
        OpsField("Телефон", phone, { phone = it }, placeholder = "+7 777 123 45 67", keyboardType = KeyboardType.Phone, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(Modifier.weight(1f)) { OpsField("Штрихкод карты", card, { card = it }, singleLine = true) }
            Icon(Icons.Filled.QrCodeScanner, "Сканировать", tint = Orda.colors.brand, modifier = Modifier.size(32.dp).clickable { scanning = true })
        }
        Text("Необязательно: карту можно привязать позже, клиента найдут по телефону.", color = Orda.colors.textDim, fontSize = 12.sp)
        if (companies.size > 1) OpsPicker("Точка", listOf("" to "Без привязки") + companies.map { it.id to it.name }, company, { company = it })
        submit.error?.let { ErrorBanner(it) }
        PrimaryButton("Завести клиента", Modifier.fillMaxWidth(), enabled = name.isNotBlank(), loading = submit.busy) {
            submit.run(
                { env.api.sendUnit(ApiRequest.json(CUSTOMERS_PATH, createCustomerBody(name, phone, card, company))) },
                { haptics.success(); onSaved() },
            )
        }
    }
    if (scanning) {
        OrdaSheet("Карта клиента", { scanning = false }) { ScannerPane(onCode = { card = it; scanning = false }) }
    }
}
