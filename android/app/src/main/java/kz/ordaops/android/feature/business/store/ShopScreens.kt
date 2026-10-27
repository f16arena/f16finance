package kz.ordaops.android.feature.business.store

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// Настройки магазина, смены, настройки чека и реклама. Порт по текущему вебу
// (`app/(main)/store/{settings,shifts,receipt-settings,advertising}`).

/** Выбранная точка-магазин и список магазинов: конфиг грузится один раз, выбор помним до выхода из раздела. */
internal class ShopPick(val shops: List<ShopCompany>, val selected: ShopCompany?, val select: (String) -> Unit)

/**
 * Точки-магазины из `/store/config`: включённые и не закрытые (закрытая точка — только
 * история). Стартовая — точка по умолчанию из настроек, иначе первая.
 */
@Composable
internal fun rememberShopPick(vm: StoreExtraViewModel): ShopPick {
    val config by vm.config.collectAsState()
    LaunchedEffect(Unit) { vm.loadConfig() }
    var picked by remember { mutableStateOf<String?>(null) }
    val shops = config.data?.shops.orEmpty()
    val selected = shops.firstOrNull { it.id == picked } ?: shops.firstOrNull { it.id == config.data?.defaultCompanyId } ?: shops.firstOrNull()
    return ShopPick(shops, selected) { picked = it }
}

/** Переключатель точки, если их несколько. */
@Composable
internal fun ShopChips(pick: ShopPick) {
    if (pick.shops.size > 1) {
        ChipRow { pick.shops.forEach { c -> StoreChip(c.name, on = c.id == pick.selected?.id) { pick.select(c.id) } } }
    }
}

// ── Настройки магазина ───────────────────────────────────────────────────────

/** Какие точки — магазины. У каждого свои товары, склад, документы и касса: данные изолированы. */
@Composable
fun StoreSettingsScreen() {
    val vm = rememberStoreExtraViewModel()
    val state by vm.config.collectAsState()
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf<Set<String>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.loadConfig() }
    LaunchedEffect(state.data) {
        val cfg = state.data
        if (cfg != null && enabled == null) enabled = cfg.companies.filter { it.storeEnabled }.map { it.id }.toSet()
    }
    StoreScaffold(toast.message) {
        StateContainer(state, onRetry = vm::loadConfig, emptyTitle = "Нет точек", emptyMessage = "Создайте их в настройках компании.", isEmpty = { it.companies.isEmpty() }) { cfg ->
            val current = enabled ?: emptySet()
            val saved = cfg.companies.filter { it.storeEnabled }.map { it.id }.toSet()
            val dirty = current != saved
            StoreLazy {
                item {
                    StoreCard {
                        SectionTitle("Точки-магазины", "Выбрано ${current.size} из ${cfg.companies.size}")
                        Text("Включите точки, которые работают как магазин — можно несколько. У каждого магазина свои товары, склад, техкарты, документы и касса.", color = Orda.colors.textDim, fontSize = 13.sp)
                    }
                }
                items(cfg.companies, key = { it.id }) { c ->
                    RowCard {
                        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text(if (c.archived) "точка закрыта" else c.code ?: if (c.id in current) "Магазин" else "Не магазин", color = Orda.colors.textDim, fontSize = 13.sp)
                            }
                            Switch(
                                checked = c.id in current,
                                enabled = cfg.canManage && !c.archived,
                                onCheckedChange = { on -> enabled = if (on) current + c.id else current - c.id },
                            )
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(vertical = Spacing.md)) {
                        FormError(error)
                        if (!cfg.canManage) Text("Менять может только владелец или менеджер.", color = Orda.colors.textDim, fontSize = 13.sp)
                        if (cfg.canManage) {
                            PrimaryButton(if (dirty) "Сохранить" else "Всё сохранено", Modifier.fillMaxWidth(), enabled = dirty, loading = saving) {
                                saving = true
                                error = null
                                scope.launch {
                                    val e = vm.saveEnabled(current)
                                    saving = false
                                    if (e == null) {
                                        enabled = null
                                        toast.show("Настройки магазина сохранены")
                                    } else {
                                        error = e
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Смены ────────────────────────────────────────────────────────────────────

/**
 * Смены точки-магазина: кто работал, сколько выручил и сошлась ли касса. Расхождение
 * считает касса при закрытии (`cash_report_diff`): недостача — красным, излишек — янтарным.
 */
@Composable
fun StoreShiftsScreen() {
    val vm = rememberStoreExtraViewModel()
    val pick = rememberShopPick(vm)
    val state by vm.shifts.collectAsState()
    var status by remember { mutableStateOf("closed") }
    var detailId by remember { mutableStateOf<String?>(null) }
    val companyId = pick.selected?.id
    LaunchedEffect(companyId, status) { if (companyId != null) vm.loadShifts(companyId, status) }
    BackHandler(enabled = detailId != null) { detailId = null }

    StoreScaffold(null) {
        if (detailId != null) {
            ShiftDetailView(vm, detailId!!, onBack = { detailId = null })
        } else if (companyId == null) {
            EmptyState(Icons.Filled.Storefront, "Точка магазина не выбрана", "Смены показываются по точке-магазину. Включите её в настройках магазина.")
        } else {
            StateContainer(state, onRetry = { vm.loadShifts(companyId, status) }, emptyTitle = if (status == "open") "Открытых смен сейчас нет" else "Смен пока нет", emptyMessage = "Смена появляется, когда кассир открывает её в кассе.", isEmpty = { it.isEmpty() }) { shifts ->
                val sum = ShiftSummary.of(shifts)
                StoreLazy {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            ShopChips(pick)
                            PillSegment(listOf("closed" to "Закрытые", "open" to "Открытые", "all" to "Все"), status) { status = it }
                            StoreCard(accent = if (sum.shortCount > 0) StoreTone.loss else null) {
                                StatLine("Смен", "${shifts.size}" + if (sum.opened > 0) " · ${sum.opened} открыто" else "")
                                StatLine("Выручка", money(sum.revenue), if (sum.revenue > 0) StoreTone.good else StoreTone.neutral)
                                StatLine("Средняя смена", money(kotlin.math.round(sum.average)))
                                // Недостача красная, излишек янтарный; когда всё сошлось — спокойно и серым.
                                when {
                                    sum.shortCount > 0 -> StatLine("Касса не сошлась", "−${money(kotlin.math.abs(sum.shortSum))} в ${sum.shortCount} ${plural(sum.shortCount, "смене", "сменах", "сменах")}", StoreTone.loss)
                                    sum.overCount > 0 -> StatLine("Излишек в кассе", "+${money(sum.overSum)} в ${sum.overCount}", StoreTone.waits)
                                    else -> StatLine("Касса", "сошлась", StoreTone.neutral)
                                }
                                if (sum.shortCount > 0 && sum.overCount > 0) StatLine("Излишек", "+${money(sum.overSum)} в ${sum.overCount}", StoreTone.waits)
                            }
                        }
                    }
                    items(shifts, key = { it.id }) { s ->
                        RowCard {
                            Column(Modifier.fillMaxWidth().clickable { detailId = s.id }.padding(vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.operatorName ?: "—", color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                                    StatusCaption(if (s.isOpen) "Открыта" else "Закрыта", if (s.isOpen) StoreTone.good else StoreTone.neutral)
                                }
                                Text("${fmtDayTime(s.openedAt)} → ${fmtDayTime(s.closedAt)}", color = Orda.colors.textDim, fontSize = 13.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                                    ShiftMetric("Выручка", s.sales?.let { money(it) } ?: "—", if ((s.sales ?: 0.0) > 0) StoreTone.good else Orda.colors.textDim)
                                    ShiftMetric("Наличные", money(if (s.isOpen) s.liveCash else s.closingCash), Orda.colors.text)
                                    ShiftMetric("Безнал", money(if (s.isOpen) s.liveKaspi else s.closingKaspi), Orda.colors.text)
                                }
                                // Расхождение кассы: «+» излишек (янтарный), «−» недостача (красный).
                                val d = s.cashDiff
                                if (d != null && s.hasDiff) {
                                    Text(
                                        (if (d < 0) "Недостача −" else "Излишек +") + money(kotlin.math.abs(d)),
                                        color = if (d < 0) StoreTone.loss else StoreTone.waits,
                                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShiftMetric(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(label, color = Orda.colors.textDim, fontSize = 11.sp)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ShiftDetailView(vm: StoreExtraViewModel, id: String, onBack: () -> Unit) {
    val state by vm.shiftDetail.collectAsState()
    LaunchedEffect(id) { vm.loadShiftDetail(id) }
    StateContainer(state, onRetry = { vm.loadShiftDetail(id) }, isEmpty = { false }) { d ->
        val salesTotal = d.sales.sumOf { it.total }
        val returnsTotal = d.returns.sumOf { it.total }
        StoreLazy {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    TextButton(onClick = onBack) { Text("← К сменам") }
                    StoreCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.shift.operatorName ?: "Смена", color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            StatusCaption(if (d.shift.isOpen) "Открыта" else "Закрыта", if (d.shift.isOpen) StoreTone.good else StoreTone.neutral)
                        }
                        StatLine("Открыта", fmtDayTime(d.shift.openedAt))
                        if (!d.shift.isOpen) StatLine("Закрыта", fmtDayTime(d.shift.closedAt))
                        StatLine("Продажи", money(salesTotal), if (salesTotal > 0) StoreTone.good else StoreTone.neutral)
                        StatLine("Возвраты", money(returnsTotal), if (returnsTotal > 0) StoreTone.loss else StoreTone.neutral)
                        val diff = d.shift.cashDiff
                        if (diff != null && d.shift.hasDiff) {
                            StatLine(if (diff < 0) "Недостача кассы" else "Излишек кассы", money(kotlin.math.abs(diff)), if (diff < 0) StoreTone.loss else StoreTone.waits)
                        }
                        if (d.shift.isOpen) Text("Смена открыта — суммы по продажам в реальном времени.", color = Orda.colors.textDim, fontSize = 13.sp)
                    }
                    SectionTitle("Продажи", "${d.sales.size}")
                    if (d.sales.isEmpty()) InlineEmpty(Icons.Filled.Schedule, "За смену продаж не было")
                }
            }
            items(d.sales, key = { it.id }) { s ->
                RowCard {
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Text(fmtHourMinute(s.soldAt), color = Orda.colors.textDim, fontSize = 13.sp)
                        Column(Modifier.weight(1f)) {
                            Text(if (s.names.isNotEmpty()) s.names.joinToString(", ") else "Продажа", color = Orda.colors.text, fontSize = 14.sp, maxLines = 2)
                            Text(listOfNotNull(paymentLabel(s.payment).takeIf { s.payment.isNotEmpty() }, s.operatorName).joinToString(" · "), color = Orda.colors.textDim, fontSize = 12.sp)
                        }
                        Text(money(s.total), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (d.returns.isNotEmpty()) {
                item { SectionTitle("Возвраты", "${d.returns.size}") }
                items(d.returns, key = { it.id }) { r ->
                    RowCard {
                        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            Text(listOfNotNull(fmtHourMinute(r.returnedAt), paymentLabel(r.payment), r.comment).joinToString(" · "), color = Orda.colors.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text("−${money(r.total)}", color = StoreTone.loss, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// ── Настройки чека ───────────────────────────────────────────────────────────

/** Реквизиты и подвал чека точки: налогоплательщик, ККМ, ОФД, НДС, язык. */
@Composable
fun ReceiptSettingsScreen() {
    val env = LocalBusiness.current
    val vm = rememberStoreExtraViewModel()
    val pick = rememberShopPick(vm)
    val state by vm.receiptSettings.collectAsState()
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    val canEdit = env.can("store-receipt-settings.edit")
    val companyId = pick.selected?.id
    LaunchedEffect(companyId) { if (companyId != null) vm.loadReceiptSettings(companyId) }

    StoreScaffold(toast.message) {
        if (companyId == null) {
            EmptyState(Icons.Filled.Storefront, "Нет точек-магазинов", "Включите точку в настройках магазина.")
        } else {
            StateContainer(state, onRetry = { vm.loadReceiptSettings(companyId) }, isEmpty = { false }) { page ->
                // Форма живёт, пока не сменилась точка или серверные данные.
                var form by remember(page.companyId, page.form) { mutableStateOf(page.form) }
                var vat by remember(page.companyId, page.form) { mutableStateOf(formatQty(page.form.vatRate)) }
                var saving by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                StoreLazy {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.padding(bottom = Spacing.xxl)) {
                            ShopChips(pick)
                            StoreCard {
                                SectionTitle("Налогоплательщик")
                                TextInput("Наименование", form.taxPayerName, { form = form.copy(taxPayerName = it) })
                                TextInput("БИН / ИИН", form.taxPayerBin, { form = form.copy(taxPayerBin = it) }, keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                                TextInput("Адрес точки", form.pointAddress, { form = form.copy(pointAddress = it) })
                                SwitchRow("Плательщик НДС", form.isVatPayer) { form = form.copy(isVatPayer = it) }
                                if (form.isVatPayer) NumberInput("Ставка НДС", vat, { vat = it; form = form.copy(vatRate = parseNumber(it)) }, suffix = "%")
                            }
                            StoreCard {
                                SectionTitle("Касса и ОФД")
                                TextInput("Заводской номер ККМ", form.kkmFactoryNumber, { form = form.copy(kkmFactoryNumber = it) })
                                TextInput("Регистрационный номер ККМ", form.kkmRegistrationNumber, { form = form.copy(kkmRegistrationNumber = it) })
                                TextInput("Оператор фискальных данных", form.ofdName, { form = form.copy(ofdName = it) })
                                TextInput("Ссылка проверки чека", form.ofdCheckUrl, { form = form.copy(ofdCheckUrl = it) }, keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
                            }
                            StoreCard {
                                SectionTitle("Вид чека")
                                PillSegment(listOf("ru" to "Русский", "kk" to "Қазақша", "both" to "Оба"), form.receiptLanguage) { form = form.copy(receiptLanguage = it) }
                                TextInput("Текст внизу чека", form.receiptFooterText, { form = form.copy(receiptFooterText = it) }, singleLine = false)
                                TextInput("Ссылка на отзыв", form.reviewUrl, { form = form.copy(reviewUrl = it) }, keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
                                SwitchRow("Спрашивать ИИН покупателя", form.requireBuyerIin) { form = form.copy(requireBuyerIin = it) }
                                SwitchRow("Маркировка", form.markingEnabled) { form = form.copy(markingEnabled = it) }
                                SwitchRow("Национальный каталог товаров", form.nktEnabled) { form = form.copy(nktEnabled = it) }
                            }
                            FormError(error)
                            if (canEdit) {
                                PrimaryButton("Сохранить", Modifier.fillMaxWidth(), loading = saving) {
                                    saving = true
                                    error = null
                                    scope.launch {
                                        val e = vm.saveReceiptSettings(companyId, form)
                                        saving = false
                                        if (e == null) toast.show("Настройки чека сохранены") else error = e
                                    }
                                }
                            } else {
                                Text("У вас нет права менять настройки чека.", color = Orda.colors.textDim, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Orda.colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ── Реклама ──────────────────────────────────────────────────────────────────

/**
 * Реклама на втором экране кассы: включить и выключить ролик, подписать, поменять
 * длительность показа, удалить. Загрузка новых файлов — на сайте: ролик больше лимита
 * тела запроса, и файл идёт в хранилище напрямую по одноразовой ссылке.
 */
@Composable
fun AdvertisingScreen() {
    val env = LocalBusiness.current
    val vm = rememberStoreExtraViewModel()
    val pick = rememberShopPick(vm)
    val state by vm.ads.collectAsState()
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<AdItem?>(null) }
    var deleting by remember { mutableStateOf<AdItem?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val canEdit = env.can("store-advertising.edit")
    val canDelete = env.can("store-advertising.delete")
    val companyId = pick.selected?.id
    LaunchedEffect(companyId) { if (companyId != null) vm.loadAds(companyId) }

    StoreScaffold(toast.message) {
        if (companyId == null) {
            EmptyState(Icons.Filled.Storefront, "Нет точек-магазинов", "Включите точку в настройках магазина.")
        } else {
            StateContainer(state, onRetry = { vm.loadAds(companyId) }, emptyTitle = "Рекламы пока нет", emptyMessage = "Ролики и картинки добавляются на сайте, в разделе «Реклама».", isEmpty = { it.isEmpty() }) { ads ->
                StoreLazy {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            ShopChips(pick)
                            FormError(error)
                            Text("Показывается на втором экране кассы по кругу, включённое — по порядку.", color = Orda.colors.textDim, fontSize = 13.sp)
                        }
                    }
                    items(ads, key = { it.id }) { ad ->
                        RowCard {
                            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                kz.ordaops.android.feature.operator.money.TintedIcon(Icons.Filled.Campaign, if (ad.isActive) StoreTone.good else StoreTone.neutral, 40, corner = 12.dp)
                                Column(Modifier.weight(1f).clickable(enabled = canEdit) { editing = ad }) {
                                    Text(ad.title ?: if (ad.isVideo) "Видео" else "Картинка", color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(listOfNotNull(if (ad.isVideo) "видео" else "картинка", ad.durationSec?.let { "$it с" }).joinToString(" · "), color = Orda.colors.textDim, fontSize = 13.sp)
                                }
                                Switch(checked = ad.isActive, enabled = canEdit, onCheckedChange = { on ->
                                    scope.launch {
                                        val e = vm.setAdActive(companyId, ad.id, on)
                                        if (e != null) error = e
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { ad ->
        var title by remember(ad.id) { mutableStateOf(ad.title.orEmpty()) }
        var duration by remember(ad.id) { mutableStateOf(ad.durationSec?.toString().orEmpty()) }
        var saving by remember(ad.id) { mutableStateOf(false) }
        OrdaSheet("Ролик", { editing = null }) {
            TextInput("Название", title, { title = it })
            if (!ad.isVideo) NumberInput("Сколько секунд показывать", duration, { duration = it.filter { c -> c.isDigit() } })
            PrimaryButton("Сохранить", Modifier.fillMaxWidth(), loading = saving) {
                saving = true
                scope.launch {
                    val e = vm.renameAd(companyId.orEmpty(), ad.id, title, duration.toIntOrNull())
                    saving = false
                    if (e == null) {
                        editing = null
                        toast.show("Сохранено")
                    } else {
                        error = e
                    }
                }
            }
            if (canDelete) TextButton(onClick = { deleting = ad; editing = null }) { Text("Удалить", color = StoreTone.loss) }
        }
    }
    deleting?.let { ad ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить «${ad.title ?: "ролик"}»?") },
            text = { Text("Файл исчезнет с втор��го экрана кассы.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        val e = vm.deleteAd(companyId.orEmpty(), ad.id)
                        if (e == null) toast.show("Удалено") else error = e
                    }
                }) { Text("Удалить", color = StoreTone.loss) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
    }
}
