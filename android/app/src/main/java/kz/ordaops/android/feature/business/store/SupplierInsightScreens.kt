package kz.ordaops.android.feature.business.store

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.business.LetterBadge
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// Поставщики, прогноз запаса и аналитика склада. Порт SuppliersScreen /
// AddSupplierSheet / StockForecastScreen / StoreAnalyticsScreen.

// ── Поставщики ───────────────────────────────────────────────────────────────

@Composable
fun SuppliersScreen() {
    val env = LocalBusiness.current
    val vm = rememberStoreViewModel()
    val state by vm.suppliers.collectAsState()
    val overview by vm.overview.collectAsState()
    val toast = rememberToast()
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val canCreate = env.can("store-suppliers.create")
    LaunchedEffect(Unit) {
        vm.loadSuppliers()
        vm.loadOverview()
    }
    val companies = overview.data?.locations?.mapNotNull { l -> l.companyId?.let { StoreCompany(it, l.companyName ?: "Точка") } }?.distinctBy { it.id }.orEmpty()

    StoreScaffold(toast.message) {
        StateContainer(state, onRetry = vm::loadSuppliers, emptyTitle = "Поставщиков нет", emptyMessage = "Заведите первого — он понадобится при приёмке.", isEmpty = { it.isEmpty() }) { list ->
            val debt = list.sumOf { it.openDebtsAmount }
            val overdue = list.sumOf { it.overdueAmount }
            StoreLazy {
                item {
                    StoreCard(accent = if (overdue > 0) StoreTone.loss else if (debt > 0) StoreTone.waits else null) {
                        StatLine("Поставщиков", "${list.size}")
                        // Просрочка — потеря доверия поставщика (rose); долг в срок — ждёт оплаты (amber); ноль не кричит.
                        StatLine("Должны поставщикам", if (debt > 0) money(debt) else "—", if (debt > 0) StoreTone.waits else StoreTone.neutral)
                        if (overdue > 0) StatLine("Из них просрочено", money(overdue), StoreTone.loss)
                    }
                }
                // Просроченные первыми, дальше по размеру долга.
                items(list.sortedWith(compareByDescending<Supplier> { it.overdueAmount }.thenByDescending { it.openDebtsAmount }), key = { it.id }) { s ->
                    RowCard {
                        Column(Modifier.fillMaxWidth().clickable { selectedId = if (selectedId == s.id) null else s.id }.padding(vertical = Spacing.sm)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                LetterBadge(s.name, if (s.isOverdue) StoreTone.loss else if (s.hasDebt) StoreTone.waits else Orda.colors.brand)
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(
                                        if (s.receiptsCount > 0) "${s.receiptsCount} ${plural(s.receiptsCount, "приёмка", "приёмки", "приёмок")} · ${money(s.receiptsTotal)}" else "приёмок не было",
                                        color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1,
                                    )
                                }
                                if (s.hasDebt) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(money(s.openDebtsAmount), color = if (s.isOverdue) StoreTone.loss else StoreTone.waits, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        if (s.isOverdue) Text("просрочено ${money(s.overdueAmount)}", color = StoreTone.loss, fontSize = 12.sp)
                                        else if (s.nextDueDate != null) Text("оплатить до ${fmtDay(s.nextDueDate)}", color = StoreTone.waits, fontSize = 12.sp)
                                    }
                                }
                            }
                            if (selectedId == s.id) {
                                Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    if (!s.organizationName.isNullOrBlank()) StatLine("Организация", s.organizationName)
                                    if (!s.binIin.isNullOrBlank()) StatLine("БИН / ИИН", s.binIin)
                                    if (!s.contactName.isNullOrBlank()) StatLine("Контакт", s.contactName)
                                    if (s.lastReceiptDate != null) StatLine("Последняя приёмка", fmtDay(s.lastReceiptDate))
                                    if (s.openDebtsCount > 0) StatLine("Неоплаченных накладных", "${s.openDebtsCount}", StoreTone.waits)
                                    if (!s.phone.isNullOrBlank()) {
                                        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, "tel:${s.phone}".toUri())) } }) { Text("Позвонить ${s.phone}") }
                                    }
                                }
                            }
                        }
                    }
                }
                item { Box(Modifier.padding(bottom = 80.dp)) }
            }
            if (canCreate && companies.isNotEmpty()) {
                Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.BottomEnd) {
                    ExtendedFloatingActionButton(
                        onClick = { adding = true },
                        icon = { Icon(Icons.Filled.Add, null) },
                        text = { Text("Поставщик") },
                        containerColor = Orda.colors.brand,
                        contentColor = Color.White,
                    )
                }
            }
        }
    }
    if (adding) {
        SupplierSheet(vm, companies, onDone = { adding = false; toast.show("Поставщик добавлен") }, onDismiss = { adding = false })
    }
}

/** Новый поставщик: появляется в момент приёмки — машина у дверей, накладная в руках, а в списке его нет. */
@Composable
private fun SupplierSheet(vm: StoreViewModel, companies: List<StoreCompany>, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var company by remember { mutableStateOf(companies.firstOrNull()) }
    var name by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var bin by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val problem = when {
        name.isBlank() -> "Укажите название"
        company == null -> "Не определена точка"
        else -> null
    }
    OrdaSheet("Новый поставщик", onDismiss) {
        if (companies.size > 1) PickerField("Точка", company, companies, { it.name }) { company = it }
        TextInput("Название", name, { name = it })
        TextInput("Контактное лицо", contact, { contact = it })
        TextInput("Телефон", phone, { phone = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Phone)
        TextInput("БИН / ИИН", bin, { bin = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
        BlockerHint(problem)
        FormError(error)
        PrimaryButton("Добавить", Modifier.fillMaxWidth(), enabled = problem == null, loading = saving) {
            saving = true
            scope.launch {
                val e = vm.createSupplier(name, company!!.id, contact, phone, bin)
                saving = false
                if (e == null) onDone() else error = e
            }
        }
    }
}

// ── Прогноз запаса ───────────────────────────────────────────────────────────

/**
 * Прогноз: что закончится раньше всего. Сервер делит остаток на скорость расхода
 * за 30 дней — это другой вопрос, чем «что продаётся лучше всего».
 */
@Composable
fun StockForecastScreen() {
    val vm = rememberStoreViewModel()
    val state by vm.forecast.collectAsState()
    var urgentOnly by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadForecast() }
    StoreScaffold(null) {
        StateContainer(state, onRetry = { vm.loadForecast() }, emptyTitle = "Прогноза пока нет", emptyMessage = "Появится, когда накопятся продажи.", isEmpty = { it.isEmpty() }) { rows ->
            val urgent = rows.count { it.isUrgent }
            val q = search.trim()
            val shown = rows.filter { (!urgentOnly || it.isUrgent) && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) }
                .sortedWith(compareBy({ it.daysLeft == null }, { it.daysLeft ?: Int.MAX_VALUE }))
            StoreLazy {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        StoreSearchField(search, { search = it }, placeholder = "Название товара")
                        if (urgent > 0) ChipRow { StoreChip("Срочно: $urgent", on = urgentOnly, tint = StoreTone.loss) { urgentOnly = !urgentOnly } }
                    }
                }
                if (shown.isEmpty()) item { EmptyState(Icons.Filled.TrendingDown, "Ничего не найдено") }
                items(shown, key = { it.itemId }) { r ->
                    val tint = when (r.status) {
                        "critical" -> StoreTone.loss
                        "warning", "low" -> StoreTone.waits
                        "no_sales" -> StoreTone.neutral
                        else -> StoreTone.good
                    }
                    RowCard {
                        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            ItemBadge(r.name, tint)
                            Column(Modifier.weight(1f)) {
                                Text(r.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(
                                    "осталось ${formatQty(r.balance)}" + if (r.dailyVelocity > 0) " · уходит ${formatQty(Math.round(r.dailyVelocity * 10) / 10.0)} в день" else "",
                                    color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(r.daysLeft?.let { "$it ${plural(it, "день", "дня", "дней")}" } ?: "—", color = tint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(r.statusLabel, color = Orda.colors.textDim, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Аналитика склада ─────────────────────────────────────────────────────────

/** Продажи за окно, топ проданного и «мёртвый груз» — остаток без единой продажи. */
@Composable
fun StoreAnalyticsScreen() {
    val vm = rememberStoreViewModel()
    val state by vm.analytics.collectAsState()
    var days by remember { mutableStateOf(30) }
    LaunchedEffect(days) { vm.loadAnalytics(days) }
    StoreScaffold(null) {
        StateContainer(state, onRetry = { vm.loadAnalytics(days) }, emptyTitle = "Данных нет", isEmpty = { false }) { a ->
            StoreLazy {
                item { PillSegment(listOf(7 to "7 дней", 30 to "30 дней", 90 to "90 дней"), days) { days = it } }
                item {
                    StoreCard {
                        Text("Продано за $days ${plural(days, "день", "дня", "дней")}", color = Orda.colors.textDim, fontSize = 14.sp)
                        Text(money(a.totalSales), color = Orda.colors.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        StatLine("Продаж", "${a.salesCount}")
                    }
                }
                item {
                    StoreCard {
                        SectionTitle("Что продаётся", "по выручке")
                        if (a.topSold.isEmpty()) InlineEmpty(Icons.Filled.LocalShipping, "Продаж за период нет")
                        val max = a.topSold.maxOfOrNull { it.amount } ?: 1.0
                        a.topSold.take(10).forEachIndexed { i, s ->
                            if (i > 0) RowDivider()
                            Column(Modifier.padding(vertical = Spacing.xs)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.name, color = Orda.colors.text, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                    Text(money(s.amount), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Text("${formatQty(s.quantity)} шт", color = Orda.colors.textDim, fontSize = 12.sp)
                                Box(Modifier.fillMaxWidth().padding(top = 2.dp).height(4.dp).background(Orda.colors.surfaceRaised, CircleShape)) {
                                    Box(Modifier.fillMaxWidth((s.amount / max).toFloat().coerceIn(0.02f, 1f)).height(4.dp).background(Orda.colors.brand, CircleShape))
                                }
                            }
                        }
                    }
                }
                item {
                    StoreCard {
                        SectionTitle("Лежит без движения", "есть остаток, продаж не было")
                        if (a.stale.isEmpty()) InlineEmpty(Icons.Filled.LocalShipping, "Всё, что лежит, продавалось", StoreTone.good)
                        a.stale.take(15).forEachIndexed { i, t ->
                            if (i > 0) RowDivider()
                            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                                Text(t.name, color = Orda.colors.text, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                Text(formatQtyUnit(t.quantity, t.unit), color = StoreTone.waits, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}
