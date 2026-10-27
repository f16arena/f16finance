package kz.ordaops.android.feature.business.ops

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.operator.money.HeroCard
import kz.ordaops.android.feature.operator.money.SoftDivider
import kz.ordaops.android.feature.operator.money.StatRow
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// «Чеки» и «Возвраты» кассы: /api/pos/receipts и /api/pos/return.

private const val PAGE_LIMIT = 15

/** Все чеки периода: страницы подтягиваем, пока не наберём `total` (но не больше [PAGE_LIMIT] страниц). */
private data class ReceiptsData(val receipts: List<PosReceipt>, val total: Int) {
    val isTruncated: Boolean get() = receipts.size < total
}

private class PosReceiptsVm(private val api: ApiClient) : ViewModel() {
    private val _state = MutableStateFlow(Loadable<ReceiptsData>())
    val state: StateFlow<Loadable<ReceiptsData>> = _state.asStateFlow()
    private val _period = MutableStateFlow(PosPeriod.Today)
    val period: StateFlow<PosPeriod> = _period.asStateFlow()
    private val _company = MutableStateFlow("")
    val company: StateFlow<String> = _company.asStateFlow()
    private var job: Job? = null

    init { load() }

    fun select(period: PosPeriod) { if (_period.value != period) { _period.value = period; reload() } }
    fun selectCompany(id: String) { if (_company.value != id) { _company.value = id; reload() } }

    private fun reload() {
        _state.update { Loadable() }
        load()
    }

    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            val (from, to) = _period.value.range()
            val company = _company.value
            _state.load {
                val collected = mutableListOf<PosReceipt>()
                var total = 0
                var page = 1
                while (page <= PAGE_LIMIT) {
                    val query = buildMap {
                        put("date_from", from); put("date_to", to); put("page", page.toString())
                        if (company.isNotEmpty()) put("company_id", company)
                    }
                    val root = api.send<JsonObject>(ApiRequest("/api/pos/receipts", query = query))
                    val chunk = root.items("data").map(PosReceipt::parse)
                    collected += chunk
                    total = root.int("total") ?: collected.size
                    if (chunk.isEmpty() || collected.size >= total) break
                    page++
                }
                ReceiptsData(collected, maxOf(total, collected.size))
            }
        }
    }
}

private val Emerald = Color(0xFF059669)

private fun PosPaymentKind.icon(): ImageVector = when (this) {
    PosPaymentKind.Cash -> Icons.Filled.Payments
    PosPaymentKind.Kaspi -> Icons.Filled.QrCode
    PosPaymentKind.Card -> Icons.Filled.CreditCard
    PosPaymentKind.Online -> Icons.Filled.Language
    PosPaymentKind.Mixed -> Icons.Filled.ViewColumn
}

// ── Чеки ─────────────────────────────────────────────────────────────────────

/**
 * Чеки кассы за сегодня, вчера или 7 дней, по всем точкам или одной. Выручка —
 * главная цифра, остальное — расшифровка; если чеков больше, чем загружено, говорим об этом прямо.
 */
@Composable
fun ReceiptsListScreen() {
    val env = LocalBusiness.current
    val vm: PosReceiptsVm = viewModel(
        key = "ops:pos-receipts:${env.api.organizationId}",
        factory = viewModelFactory { initializer { PosReceiptsVm(env.api) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val company by vm.company.collectAsStateWithLifecycle()
    val companies = rememberCompanyOptions()
    var query by rememberSaveable { mutableStateOf("") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PillSegment(PosPeriod.entries.map { it to it.title }, period) { vm.select(it) }
            // Выбор точки — только если их несколько: на одной это шум.
            if (companies.size > 1) {
                PillSegment(listOf("" to "Все точки") + companies.map { it.id to it.name }, company) { vm.selectCompany(it) }
            }
        }
        StateContainer(
            state = state,
            onRetry = vm::load,
            modifier = Modifier.weight(1f),
            isEmpty = { it.receipts.isEmpty() },
            emptyIcon = Icons.Filled.Receipt,
            emptyTitle = "Чеков нет",
            emptyMessage = "За ${period.title.lowercase()} " + (companies.firstOrNull { it.id == company }?.let { "на точке «${it.name}» " } ?: "") + "продаж не было.",
        ) { data ->
            val summary = remember(data) { PosSalesSummary.of(data.receipts) }
            val sources = remember(data) { PosSalesSummary.sources(data.receipts) }
            val visible = remember(data, query) { filterReceipts(data.receipts, query) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                item(key = "hero") {
                    HeroCard {
                        Text("Выручка · ${period.title.lowercase()}", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                        Text(money(summary.revenue), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        val perCheck = (summary.itemsSold / maxOf(summary.count, 1) * 10).let { Math.round(it) / 10.0 }
                        Text("в среднем ${pointsText(perCheck)} товара в чеке", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                        Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                            HeroFact("Чеков", "${data.total}")
                            HeroFact("Средний чек", money(summary.averageCheck))
                            HeroFact("Скидки", money(summary.discounts + summary.loyaltyDiscounts))
                        }
                    }
                }
                if (data.isTruncated) {
                    item(key = "trunc") {
                        // Суммы посчитаны по загруженным чекам — скажем прямо, а не покажем заниженную выручку как полную.
                        Text(
                            "Загружено ${data.receipts.size} из ${data.total} чеков — суммы и средний чек посчитаны по ним",
                            color = Orda.colors.warning, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().background(Orda.colors.warning.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(Spacing.md),
                        )
                    }
                }
                item(key = "pay") { PaymentsSection(summary) }
                item(key = "src") { SourcesSection(sources) }
                item(key = "list-head") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text("Чеки · ${visible.size}", color = Orda.colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        OpsSearchField(query, { query = it }, "Номер чека или сумма")
                    }
                }
                if (visible.isEmpty()) {
                    item(key = "none") { EmptyState(Icons.Filled.Receipt, "По запросу ничего не найдено") }
                } else {
                    items(visible.take(30), key = { it.id }, contentType = { "receipt" }) { r -> ReceiptRow(r) { openId = r.id } }
                    if (visible.size > 30) item(key = "more") {
                        Text("Показаны первые 30 — уточните поиск по номеру или сумме", color = Orda.colors.textDim, fontSize = 12.sp)
                    }
                }
            }
        }
    }
    state.data?.receipts?.firstOrNull { it.id == openId }?.let { ReceiptSheet(it) { openId = null } }
}

@Composable
private fun PaymentsSection(summary: PosSalesSummary) {
    val methods = listOf(
        Triple("Наличные", summary.cash, Icons.Filled.Payments to Color(0xFF2563EB)),
        Triple("Безналичный", summary.kaspi, Icons.Filled.QrCode to Color(0xFF7C3AED)),
        Triple("Карта", summary.card, Icons.Filled.CreditCard to Color(0xFF0891B2)),
        Triple("Онлайн", summary.online, Icons.Filled.Language to Color(0xFFF59E0B)),
    ).filter { it.second > 0 }
    val total = maxOf(methods.sumOf { it.second }, 1.0)
    OpsSection("Чем платили", count = summary.cashlessShare?.let { "безнал ${kz.ordaops.android.feature.business.BizFormat.percent(it * 100)}" }) {
        if (methods.isEmpty()) Text("Оплат нет", color = Orda.colors.textDim)
        methods.forEach { (title, amount, style) ->
            OpsRow(style.first, style.second, title, subtitle = kz.ordaops.android.feature.business.BizFormat.percent(amount / total * 100), trailing = money(amount))
        }
    }
}

@Composable
private fun SourcesSection(sources: List<PosSourceShare>) {
    OpsSection("Кто пробил", count = "по каналу продажи") {
        if (sources.isEmpty()) Text("Данных о канале нет", color = Orda.colors.textDim)
        sources.forEach { s ->
            OpsRow(Icons.Filled.AccountBalanceWallet, Orda.colors.brand, s.label, subtitle = "${s.count} ${plural(s.count, "чек", "чека", "чеков")}", trailing = money(s.amount))
        }
    }
}

@Composable
private fun ReceiptRow(receipt: PosReceipt, onClick: () -> Unit) {
    val subtitle = listOfNotNull(
        dateTimeLabelOf(receipt), receipt.paymentKind.label,
        "${receipt.items.size} ${plural(receipt.items.size, "позиция", "позиции", "позиций")}",
    ).joinToString(" · ")
    OpsRow(
        receipt.paymentKind.icon(), Emerald, "№${receipt.shortNumber}", subtitle = subtitle,
        trailing = money(receipt.total), trailingSub = if (receipt.discount + receipt.loyaltyDiscount > 0) "скидка" else null,
        onClick = onClick,
    )
}

private fun dateTimeLabelOf(r: PosReceipt): String? = r.soldAt?.let { dateTimeLabel(it.toString()) }

/** Чек подробно: сумма, состав, оплата, бонусы, комментарий. */
@Composable
private fun ReceiptSheet(receipt: PosReceipt, onDismiss: () -> Unit) {
    OrdaSheet("Чек №${receipt.shortNumber}", onDismiss) {
        HeroCard {
            Text("${receipt.sourceLabel}", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
            Text(money(receipt.total), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            dateTimeLabelOf(receipt)?.let { Text("пробит $it", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp) }
            Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                HeroFact("Оплата", receipt.paymentKind.label)
                if (receipt.customerId != null) HeroFact("Покупатель", "по карте лояльности")
            }
        }
        OpsSection("Состав", count = "${receipt.items.size} поз.") {
            if (receipt.items.isEmpty()) Text("Позиции не сохранились", color = Orda.colors.textDim)
            receipt.items.forEach { line ->
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Column(Modifier.weight(1f)) {
                        Text(line.name, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                        Text("${pointsText(line.quantity)} × ${money(line.unitPrice)}", color = Orda.colors.textDim, fontSize = 13.sp)
                    }
                    Text(money(line.totalPrice), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        OpsSection("Оплата") {
            if (receipt.cash > 0) StatRow("Наличные", money(receipt.cash))
            if (receipt.kaspi > 0) StatRow("Безналичный", money(receipt.kaspi))
            if (receipt.card > 0) StatRow("Карта", money(receipt.card))
            if (receipt.online > 0) StatRow("Онлайн", money(receipt.online))
            if (receipt.discount > 0) { SoftDivider(); StatRow("Скидка", signedMoney(-receipt.discount), valueColor = Orda.colors.warning) }
            if (receipt.loyaltyDiscount > 0) StatRow("Оплачено баллами", signedMoney(-receipt.loyaltyDiscount), valueColor = Orda.colors.warning)
        }
        if (receipt.hasLoyalty) {
            OpsSection("Бонусы") {
                if (receipt.pointsEarned > 0) StatRow("Начислено", pointsText(receipt.pointsEarned), valueColor = Orda.colors.positive)
                if (receipt.pointsSpent > 0) StatRow("Списано", pointsText(receipt.pointsSpent), valueColor = Orda.colors.warning)
            }
        }
        receipt.comment?.takeIf { it.isNotBlank() }?.let { OpsSection("Комментарий") { Text(it, color = Orda.colors.textMuted, fontSize = 15.sp) } }
    }
}

// ── Возвраты ─────────────────────────────────────────────────────────────────

/**
 * Возврат по чеку: найти по последним шести символам номера, отметить позиции,
 * назвать причину. Оформлять может только тот, у кого есть право «Оформить возврат».
 */
@Composable
fun ReturnsScreen() {
    val env = LocalBusiness.current
    val canReturn = env.can("pos-returns.return")
    val haptics = rememberHaptics()
    val find = rememberOpsSubmit()
    val submit = rememberOpsSubmit()
    var number by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var sale by remember { mutableStateOf<PosSaleForReturn?>(null) }
    var quantities by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var done by remember { mutableStateOf<Pair<String, Double>?>(null) }

    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        val result = done
        if (result != null) {
            item {
                OpsSection("Возврат оформлен") {
                    Icon(Icons.Filled.CheckCircle, null, tint = Orda.colors.positive, modifier = Modifier.size(40.dp))
                    Text(money(result.second), color = Orda.colors.positive, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("№ ${result.first.takeLast(12).uppercase()}", color = Orda.colors.textDim, fontSize = 13.sp)
                    Text("Сумма уже учтена в смене и записана в журнал.", color = Orda.colors.textMuted, fontSize = 14.sp)
                    PrimaryButton("Оформить ещё возврат", Modifier.fillMaxWidth()) {
                        number = ""; reason = ""; sale = null; quantities = emptyMap(); done = null
                    }
                }
            }
            return@LazyColumn
        }
        item {
            OpsSection("Какой чек возвращаем") {
                Text("Последние 6 символов номера — они напечатаны внизу чека.", color = Orda.colors.textDim, fontSize = 13.sp)
                OpsField("Номер чека", number, { number = it.uppercase() }, placeholder = "Например, 1C54E4", singleLine = true)
                find.error?.let { Text(it, color = Orda.colors.negative, fontSize = 14.sp) }
                PrimaryButton("Найти", Modifier.fillMaxWidth(), enabled = number.isNotBlank(), loading = find.busy) {
                    val (key, value) = returnLookupQuery(number)
                    sale = null; quantities = emptyMap(); submit.error = null
                    find.run(
                        { sale = PosSaleForReturn.parse(env.api.send<JsonObject>(ApiRequest("/api/pos/return", query = mapOf(key to value))).obj("data") ?: JsonObject(emptyMap())) },
                        {},
                    )
                }
            }
        }
        val found = sale
        if (found != null) {
            item {
                HeroCard {
                    Text("Чек №${found.shortNumber}", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                    Text(money(found.total), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text(if (found.hasPreviousReturns) "по чеку уже были возвраты" else found.soldAt?.let { dateTimeLabel(it.toString()) }.orEmpty(), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                    HeroFact("Оплата", found.paymentKind.label)
                }
            }
            item {
                OpsSection("Что возвращают") {
                    if (found.returnable.isEmpty()) {
                        Text("Все товары по этому чеку уже возвращены", color = Orda.colors.positive, fontSize = 15.sp)
                    }
                    found.returnable.forEach { line ->
                        val qty = quantities[line.id] ?: 0.0
                        val selected = qty > 0
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().pressScale().clickable {
                                    quantities = quantities + (line.id to if (selected) 0.0 else line.returnableQuantity)
                                }.padding(vertical = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            ) {
                                Icon(if (selected) Icons.Filled.CheckCircle else Icons.Filled.Circle, null, tint = if (selected) Orda.colors.brand else Orda.colors.textDim, modifier = Modifier.size(22.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(line.name, color = Orda.colors.text, fontSize = 15.sp, maxLines = 2)
                                    val sub = buildList {
                                        add("доступно ${pointsText(line.returnableQuantity)} × ${money(line.unitPrice)}")
                                        if (line.returnedQuantity > 0) add("уже вернули ${pointsText(line.returnedQuantity)}")
                                    }.joinToString(" · ")
                                    Text(sub, color = Orda.colors.textDim, fontSize = 12.sp)
                                }
                                Text(money(line.unitPrice * (if (selected) qty else line.returnableQuantity)), color = if (selected) Orda.colors.negative else Orda.colors.textMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                            // Шаг количества — только там, где выбор вообще есть.
                            if (selected && line.returnableQuantity > 1) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 34.dp)) {
                                    Text("Возвращаем ${pointsText(qty)} из ${pointsText(line.returnableQuantity)}", color = Orda.colors.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    Text("−", color = Orda.colors.brand, fontSize = 24.sp, modifier = Modifier.clickable(enabled = qty > 1) { quantities = quantities + (line.id to qty - 1) }.padding(horizontal = Spacing.md))
                                    Text("+", color = Orda.colors.brand, fontSize = 24.sp, modifier = Modifier.clickable(enabled = qty < line.returnableQuantity) { quantities = quantities + (line.id to qty + 1) }.padding(horizontal = Spacing.md))
                                }
                            }
                        }
                    }
                }
            }
            val amount = returnAmount(found, quantities)
            if (amount > 0) {
                item {
                    OpsSection("Почему возвращают") {
                        Text("Причину увидят в журнале смены.", color = Orda.colors.textDim, fontSize = 13.sp)
                        OpsField("Причина", reason, { reason = it }, placeholder = "Например: не подошёл размер", minLines = 2)
                        SoftDivider()
                        StatRow("К возврату", money(amount), valueColor = Orda.colors.negative, emphasized = true)
                        Text("Деньги вернутся тем же способом, каким платил клиент — ${found.paymentKind.label.lowercase()}.", color = Orda.colors.textDim, fontSize = 12.sp)
                        submit.error?.let { ErrorBanner(it) }
                        if (canReturn) {
                            PrimaryButton("Оформить возврат", Modifier.fillMaxWidth(), enabled = reason.isNotBlank(), loading = submit.busy) {
                                submit.run(
                                    {
                                        val root = env.api.send<JsonObject>(ApiRequest.json("/api/pos/return", returnBody(found, quantities, reason)))
                                        val data = root.obj("data") ?: root
                                        done = (data.str("return_id") ?: "") to (data.num("return_amount") ?: amount)
                                    },
                                    { haptics.success() },
                                )
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Warning, null, tint = Orda.colors.warning, modifier = Modifier.size(18.dp))
                                Text("Оформить возврат может сотрудник с правом «Оформить возврат»", color = Orda.colors.warning, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
