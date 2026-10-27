package kz.ordaops.android.feature.business.finance

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiJson
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.AnalyticsPeriod
import kz.ordaops.android.feature.business.BizData
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.BusinessViewModel
import kz.ordaops.android.feature.business.HorizontalRow
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerFootnote
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

/** Настройки + расчёт за полугодие (с учётом фильтра способов оплаты). */
data class TaxBoard(val settings: TaxSettingsReply, val summary: TaxSummary, val rowsFiltered: Boolean)

class TaxViewModel(private val api: FinanceApi) : LoadViewModel<TaxBoard>() {
    private var half: TaxHalf = TaxLogic.nextPayment(AnalyticsPeriod.businessToday().toString())
    private var extraIds: Set<String> = emptySet()

    private val _saving = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saving.asStateFlow()

    override suspend fun fetch(): TaxBoard = coroutineScope {
        val settings = api.api.send<TaxSettingsReply>(ApiRequest("/api/admin/tax/settings"))
        val tax = settings.tax
        val query = buildMap {
            put("from", half.from); put("to", half.to)
            tax.rate?.let { put("rate", it.toString()) }
            if (tax.includeExtra) put("include_extra", "1")
        }
        val summary = api.api.send<BizData<TaxSummary>>(ApiRequest("/api/admin/tax/summary", query = query)).data
        // Фильтр способов оплаты считает только сайт, а не сервер: пересчитываем по сырым приходам, как он.
        val custom = tax.taxableFilter.values.any { !it.isAll }
        if (!custom) return@coroutineScope TaxBoard(settings, summary, false)
        val rows = runCatching {
            api.api.send<BizData<TaxRows>>(
                ApiRequest("/api/admin/reports/bundle", query = mapOf("from" to half.from, "to" to half.to, "include_extra" to "1", "rows" to "current")),
            ).data.incomes
        }.getOrNull() ?: return@coroutineScope TaxBoard(settings, summary, false)
        TaxBoard(settings, TaxLogic.recompute(summary, rows, tax.taxableFilter, extraIds, tax.includeExtra), true)
    }

    fun show(h: TaxHalf, extra: Set<String>) {
        if (h == half && extra == extraIds && state.value.hasData) return
        half = h; extraIds = extra
        reset(); reload()
    }

    val currentHalf get() = half

    /** Сохранить изменённые поля налоговых настроек (присланное ложится поверх сохранённого). */
    suspend fun save(change: JsonObject): String? = try {
        api.api.sendUnit(ApiRequest.json("/api/admin/tax/settings", buildJsonObject { put("tax", change) }, HttpMethod.PATCH))
        reload(); null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.userMessage() }
}

/** «Налоги»: ИПН на упрощёнке по полугодиям, соцплатежи, нагрузка, пороги, реквизиты платежей. */
@Composable
fun TaxScreen() {
    val env = LocalBusiness.current
    val vm = financeViewModel("tax") { _, api -> TaxViewModel(api) }
    val state by vm.state.collectAsState()
    val companies by env.vm.companies.collectAsState()
    val today = AnalyticsPeriod.businessToday().toString()
    val halves = remember(today) { TaxLogic.halves(today) }
    var halfKey by rememberSaveable { mutableStateOf(TaxLogic.nextPayment(today).let { "${it.year}-${it.half}" }) }
    val half = halves.first { "${it.year}-${it.half}" == halfKey }
    val extraIds = remember(companies) { companies.filter(BusinessViewModel::isExtra).map { it.id }.toSet() }
    LaunchedEffect(halfKey, extraIds) { vm.show(half, extraIds) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Spacing.lg)) {
            HorizontalRow { halves.forEach { InsightChip(it.label, "${it.year}-${it.half}" == halfKey) { halfKey = "${it.year}-${it.half}" } } }
        }
        StateContainer(state, onRetry = vm::reload, modifier = Modifier.weight(1f)) { board ->
            OwnerScreenColumn { TaxContent(board, vm, half, today, companies, extraIds) }
        }
    }
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("tax", text))
}

@Composable
private fun TaxContent(board: TaxBoard, vm: TaxViewModel, half: TaxHalf, today: String, companies: List<kz.ordaops.android.feature.business.Company>, extraIds: Set<String>) {
    val env = LocalBusiness.current
    val tax = board.settings.tax
    val s = board.summary
    val canEdit = board.settings.canEdit
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun save(change: JsonObject) { scope.launch { error = vm.save(change) } }

    error?.let { ErrorBanner(it) }
    if (tax.rate == null) {
        ToneCard("warning", "Выберите ставку маслихата", "Ставка 2–6 % зависит от региона. Пока она не выбрана, расчёт идёт по минимальной (${TaxLogic.rateLabel(s.constants.minRate)}).")
    }
    HeroSummary(
        title = "К оплате за ${half.label.lowercase()}",
        value = money(s.total),
        caption = "ИПН ${TaxLogic.rateLabel(s.rate)} от оборота ${money(s.revenue)} + соцплатежи за себя",
        footer = listOf("ИПН" to money(s.ipn), "Соцплатежи" to money(s.selfSocial), "Нагрузка" to BizFormat.percent(s.effectiveRate)),
    )
    if (board.rowsFiltered) OwnerFootnote("Оборот пересчитан с учётом настройки «какие способы оплаты входят в налог» по точкам.")
    OwnerSection("Сроки") {
        val declaration = TaxLogic.daysUntil(today, half.declarationDue)
        val payment = TaxLogic.daysUntil(today, half.paymentDue)
        LineRow("Декларация 910.00 до ${half.declarationDue.let { RuDates.dm(it) + "." + it.take(4) }}", dueText(declaration))
        LineRow("Оплата до ${half.paymentDue.let { RuDates.dm(it) + "." + it.take(4) }}", dueText(payment), bold = true)
        LineRow("Откладывать в месяц", money(TaxLogic.monthlyReserve(s)), bold = true)
        OwnerFootnote("Чтобы налог не «съел» оборотные деньги, переводите эту сумму на отдельный счёт ежемесячно.")
    }
    if (s.months.isNotEmpty()) {
        OwnerSection("По месяцам") {
            s.months.forEach { m ->
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(RuDates.monthTitle(m.month), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(money(m.total), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("оборот ${money(m.revenue)} · ИПН ${money(m.ipn)} · соцплатежи ${money(m.social)}", color = Orda.colors.textDim, fontSize = 12.sp)
                }
            }
        }
    }
    OwnerSection("Полная нагрузка") {
        LineRow("ИПН с упрощёнки", money(s.burden.ipn))
        LineRow("Соцплатежи за себя", money(s.burden.selfSocial))
        LineRow("Налоги за работников${if (s.payroll.employees > 0) " (${s.payroll.employees})" else ""}", money(s.burden.payrollTaxes))
        LineRow("Всего", money(s.burden.total), bold = true)
        LineRow("Доля в обороте", BizFormat.percent(s.burden.effectiveRate))
    }
    val y = s.year
    if (y.simplifiedLimit > 0) {
        OwnerSection("Пороги года") {
            LineRow("Оборот с начала года", money(y.yearRevenue))
            LineRow("Темп до конца года", money(y.projected))
            ThresholdRow("Порог НДС", y.vatThreshold, y.vatRemaining, y.vatRisk, y.yearRevenue)
            ThresholdRow("Лимит упрощёнки", y.simplifiedLimit, y.simplifiedRemaining, y.simplifiedRisk, y.yearRevenue)
            OwnerFootnote("Прогноз линейный и сезонность не учитывает: это сигнал «пора считать точнее», а не план.")
        }
    }
    OwnerSection("Ставка и реквизиты", trailing = { if (canEdit) TextButton(onClick = { showSettings = true }) { Text("Изменить") } }) {
        LineRow("Ставка маслихата", tax.rate?.let { TaxLogic.rateLabel(it) } ?: "не выбрана")
        LineRow("Организация", tax.companyFullName.ifBlank { "—" })
        LineRow("БИН/ИИН", tax.bin.ifBlank { "—" })
        LineRow("ОКЭД", tax.oked.ifBlank { "—" })
        LineRow("Плательщик НДС", if (tax.vatPayer) "да" else "нет")
        if (extraIds.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Точки вне общих итогов — в обороте", color = Orda.colors.textDim, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(checked = tax.includeExtra, enabled = canEdit, onCheckedChange = { v -> save(buildJsonObject { put("includeExtra", v) }) })
            }
        }
    }
    if (companies.isNotEmpty()) {
        OwnerSection("Что входит в оборот") {
            OwnerFootnote("По каждой точке — какие способы оплаты считаются выручкой для налога.")
            companies.forEach { c ->
                val cfg = tax.taxableFilter[c.id] ?: TaxChannels()
                val excluded = c.id in extraIds && !tax.includeExtra
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.name + if (excluded) " · не в обороте" else "", color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    HorizontalRow {
                        listOf("cash" to "Наличные", "kaspi" to "Терминал", "online" to "Онлайн", "card" to "Карта").forEach { (key, label) ->
                            val on = when (key) { "cash" -> cfg.cash; "kaspi" -> cfg.kaspi; "online" -> cfg.online; else -> cfg.card }
                            InsightChip(label, on) {
                                if (!canEdit) return@InsightChip
                                val next = when (key) { "cash" -> cfg.copy(cash = !on); "kaspi" -> cfg.copy(kaspi = !on); "online" -> cfg.copy(online = !on); else -> cfg.copy(card = !on) }
                                // Фильтр уходит целиком: сервер сливает присланные поля поверх сохранённых.
                                val filter = tax.taxableFilter + (c.id to next)
                                save(buildJsonObject { put("taxableFilter", buildJsonObject { filter.forEach { (id, ch) -> put(id, ApiJson.encodeToJsonElement(TaxChannels.serializer(), ch)) } }) })
                            }
                        }
                    }
                }
            }
        }
    }
    OwnerSection("Реквизиты платежей в бюджет") {
        OwnerFootnote("Скопируйте в банк → «Платежи в бюджет». Получатель — УГД по месту регистрации ИП.")
        TaxLogic.requisites.forEach { r ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(r.name, color = Orda.colors.text, fontSize = 15.sp)
                Text(r.deadline, color = Orda.colors.textDim, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    InsightChip("КБК ${r.kbk}", false) { copy(context, r.kbk) }
                    InsightChip("КНП ${r.knp}", false) { copy(context, r.knp) }
                }
            }
        }
    }
    if (showSettings) TaxSettingsSheet(tax, s.constants, onDismiss = { showSettings = false }) { change -> vm.save(change) }
}

private fun dueText(days: Long): String = when {
    days < 0 -> "срок прошёл"
    days == 0L -> "сегодня"
    else -> "через $days ${LedgerLogic.plural(days.toInt(), "день", "дня", "дней")}"
}

@Composable
private fun ThresholdRow(title: String, limit: Double, remaining: Double, risk: Boolean, revenue: Double) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, color = Orda.colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("осталось ${money(remaining)}", color = if (risk) Orda.colors.warning else Orda.colors.textDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        ShareBar(if (limit > 0) revenue / limit else 0.0, if (risk) Orda.colors.warning else Orda.colors.brand)
        if (risk) Text("При таком темпе порог будет пройден до конца года.", color = Orda.colors.warning, fontSize = 12.sp)
    }
}

@Composable
private fun TaxSettingsSheet(tax: TaxSettings, constants: TaxConstants, onDismiss: () -> Unit, onSave: suspend (JsonObject) -> String?) {
    val scope = rememberCoroutineScope()
    var rate by remember { mutableStateOf(tax.rate) }
    var name by remember { mutableStateOf(tax.companyFullName) }
    var bin by remember { mutableStateOf(tax.bin) }
    var oked by remember { mutableStateOf(tax.oked) }
    var vat by remember { mutableStateOf(tax.vatPayer) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    OrdaSheet("Налоговые настройки", onDismiss) {
        Text("Ставка маслихата", color = Orda.colors.textDim, fontSize = 13.sp)
        HorizontalRow { TaxLogic.rates(constants.minRate, constants.maxRate).forEach { r -> InsightChip(TaxLogic.rateLabel(r), rate == r) { rate = r } } }
        TextLineField("Организация (ИП)", name, { name = it })
        TextLineField("БИН/ИИН", bin, { bin = it.filter(Char::isDigit).take(12) })
        TextLineField("ОКЭД", oked, { oked = it.filter(Char::isDigit).take(5) })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Плательщик НДС", color = Orda.colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = vat, onCheckedChange = { vat = it })
        }
        error?.let { ErrorBanner(it) }
        PrimaryButton("Сохранить", Modifier.fillMaxWidth(), loading = saving) {
            saving = true; error = null
            scope.launch {
                val failure = onSave(buildJsonObject {
                    rate?.let { put("rate", it) }
                    put("companyFullName", name); put("bin", bin); put("oked", oked); put("vatPayer", vat)
                })
                saving = false
                if (failure == null) onDismiss() else error = failure
            }
        }
    }
}
