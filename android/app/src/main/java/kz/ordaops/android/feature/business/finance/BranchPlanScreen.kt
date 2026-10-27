package kz.ordaops.android.feature.business.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.business.team.ConfirmDialog
import kz.ordaops.android.feature.business.team.SubmitButton
import kz.ordaops.android.feature.business.team.TeamError
import kz.ordaops.android.feature.business.team.TeamField
import kz.ordaops.android.feature.business.team.TeamHint
import kz.ordaops.android.feature.business.team.TeamPill
import kz.ordaops.android.feature.business.team.launch
import kz.ordaops.android.feature.business.team.rememberActionState
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

private const val BranchPath = "/api/admin/branch-plan"

/** Снимок для «есть несохранённые изменения»: те же поля, что уходят на сервер. */
private fun BranchDraft.snapshot(): String = name + "|" + payloadJson().toString()

private fun fmtNum(v: Double): String =
    if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()

private fun one(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

/**
 * Финмодель новой точки (веб `/branch-plan`): вложения, тарифы и зоны, расходы,
 * три сценария и окупаемость. Считается на устройстве теми же формулами, что на
 * сайте ([calcBranch]); на сервере хранятся только черновики.
 *
 * Не перенесены каталоги комплектующих и оборудования («+ Из каталога…» на сайте):
 * это справочник цен, на телефоне комплектующие вводятся своими строками.
 */
@Composable
fun BranchPlanScreen() {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    var drafts by remember { mutableStateOf<List<BranchDraftItem>>(emptyList()) }
    var draft by remember { mutableStateOf(defaultBranchDraft()) }
    var saved by remember { mutableStateOf(draft.snapshot()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    val action = rememberActionState()
    val canEdit = env.can("branch-plan.edit")
    val canDelete = env.can("branch-plan.delete")
    val dirty = draft.snapshot() != saved

    suspend fun refreshList() {
        drafts = parseBranchDraftList(env.api.send<JsonElement>(ApiRequest(BranchPath)))
    }

    suspend fun open(id: String) {
        val loaded = parseBranchDraftResponse(env.api.send<JsonElement>(ApiRequest(BranchPath, query = mapOf("id" to id))))
            ?: error("Не удалось загрузить черновик")
        draft = loaded
        saved = loaded.snapshot()
    }

    LaunchedEffect(tick) {
        loading = true
        loadError = null
        try {
            refreshList()
            // Как на сайте: открываем последний сохранённый, чтобы правки не терялись.
            drafts.firstOrNull()?.let { open(it.id) }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.userMessage()
        } finally {
            loading = false
        }
    }

    if (loading) {
        OwnerScreenColumn { TeamHint("Загружаем финмодель…") }
        return
    }
    if (loadError != null) {
        OwnerScreenColumn { TeamError(loadError); TeamPill("Повторить") { tick++ } }
        return
    }

    val calc = remember(draft) { calcBranch(draft) }
    val c = Orda.colors

    OwnerScreenColumn {
        TeamField("Название модели", draft.name, { draft = draft.copy(name = it) }, placeholder = "Например: Филиал на Абая")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            TeamPill("Черновики (${drafts.size})") { showList = !showList }
            TeamPill("Новая модель", tint = c.textMuted) { draft = defaultBranchDraft(); saved = draft.snapshot() }
            if (dirty) Text("не сохранено", color = c.warning, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (showList) {
            OwnerSection("Сохранённые модели") {
                if (drafts.isEmpty()) TeamHint("Пока нет сохранённых моделей.")
                drafts.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, color = if (item.id == draft.id) c.brand else c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            item.updatedAt?.let { Text(it.take(10), color = c.textDim, fontSize = 12.sp) }
                        }
                        TeamPill("Открыть") {
                            action.launch(scope, onSuccess = { showList = false }) { open(item.id) }
                        }
                    }
                }
            }
        }
        TeamError(action.error)
        notice?.let { TeamHint(it, c.positive) }

        // Итог ожидаемого сценария
        HeroSummary(
            title = "Прибыль в месяц",
            value = money(calc.profit),
            colors = if (calc.profit > 0) FinanceColors.HeroPositive else FinanceColors.HeroNegative,
            caption = calc.paybackMonths?.let { "Окупаемость: ${one(it)} мес" } ?: "Прибыль ≤ 0 — окупаемость не наступит",
            footer = listOf(
                "Вложения" to money(calc.totalCapex),
                "Выручка" to money(calc.revenue),
                "Расходы" to money(calc.opex),
            ),
        )
        Text("ПК: ${money(calc.pcCapex)} · прочее: ${money(calc.otherCapex)} · расходы: фикс ${money(calc.opexFixed)}, % ${money(calc.opexPercent)}", color = c.textDim, fontSize = 12.sp)
        calc.breakEvenHours?.let {
            Text("Точка безубыточности: ${one(it)} ч на устройство в сутки при текущих тарифах и расходах.", color = c.textMuted, fontSize = 14.sp)
        }
        OwnerSection("Накопительный кэш, 24 месяца") {
            TrendLine(calc.cashFlow.map { Bar("мес ${it.month}", it.cash.toDouble()) }, color = c.brand)
            if (draft.ramp.enabled) TeamHint("С учётом постепенного выхода на нагрузку.")
        }

        ScenariosSection(draft, calc) { draft = it }
        RampSection(draft) { draft = it }
        PcSection(draft, calc) { draft = it }
        CapexSection(draft, calc) { draft = it }
        TariffSection(draft) { draft = it }
        ZoneSection(draft, calc) { draft = it }
        OpexSection(draft, calc) { draft = it }

        if (canEdit) {
            SubmitButton(if (draft.id != null) "Сохранить" else "Сохранить как новую", action.busy, null) {
                action.launch(scope, onSuccess = { notice = "Сохранено" }) {
                    val body = buildJsonObject {
                        put("action", "save")
                        draft.id?.let { put("id", it) }
                        put("name", draft.name)
                        put("payload", draft.payloadJson())
                    }
                    val res = env.api.send<JsonElement>(ApiRequest.json(BranchPath, body))
                    val newId = ((res as? JsonObject)?.get("data") as? JsonObject)?.get("id").let { (it as? JsonPrimitive)?.contentOrNull }
                    draft = draft.copy(id = newId ?: draft.id)
                    saved = draft.snapshot()
                    runCatching { refreshList() }
                }
            }
        }
        if (draft.id != null && canDelete) {
            TeamPill("Удалить модель", tint = c.negative) { confirmDelete = true }
        }
        TeamHint("Сценарии: «что если выручка −30%, расходы +15%». Ramp-up: первые месяцы редко дают 100% загрузки. Точка безубыточности показывает, реалистична ли модель.")
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Удалить модель?",
            message = "Черновик «${draft.name}» будет удалён без возможности восстановить.",
            confirm = "Удалить",
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                val id = draft.id ?: return@ConfirmDialog
                action.launch(scope, onSuccess = { notice = "Модель удалена" }) {
                    env.api.sendUnit(ApiRequest.json(BranchPath, buildJsonObject { put("action", "delete"); put("id", id) }))
                    draft = defaultBranchDraft()
                    saved = draft.snapshot()
                    runCatching { refreshList() }
                }
            },
        )
    }
}

// ── Ввод ─────────────────────────────────────────────────────────────────────

/** Числовое поле: хранит набранный текст («1.» не схлопывается), наружу отдаёт число. */
@Composable
private fun NumField(label: String, value: Double, modifier: Modifier = Modifier, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(fmtNum(value)) }
    LaunchedEffect(value) { if (bpNum(text) != value) text = fmtNum(value) }
    TeamField(
        label, text, { text = it; onValue(bpNum(it)) },
        modifier = modifier, keyboard = KeyboardType.Decimal,
    )
}

@Composable
private fun RowCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Orda.colors.surfaceRaised, RoundedCornerShape(Radius.md)).padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) { content() }
}

@Composable
private fun TwoCols(left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        left(Modifier.weight(1f))
        right(Modifier.weight(1f))
    }
}

@Composable
private fun Total(label: String, value: Double, tint: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text("$label  ", color = Orda.colors.textMuted, fontSize = 14.sp)
        Text(money(value), color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

// ── Секции ───────────────────────────────────────────────────────────────────

@Composable
private fun ScenariosSection(d: BranchDraft, calc: BranchCalc, set: (BranchDraft) -> Unit) {
    val c = Orda.colors
    OwnerSection("Сценарии") {
        TeamHint("Множители выручки и расходов к базе — оценка рисков и запаса.")
        data class Col(val title: String, val tint: Color, val cfg: BpScenarioCfg, val res: BpScenarioResult, val save: (BpScenarioCfg) -> BranchDraft)
        listOf(
            Col("Лучший", c.positive, d.scenarios.best, calc.best) { d.copy(scenarios = d.scenarios.copy(best = it)) },
            Col("Ожидаемый", c.warning, d.scenarios.expected, calc.expected) { d.copy(scenarios = d.scenarios.copy(expected = it)) },
            Col("Худший", c.negative, d.scenarios.worst, calc.worst) { d.copy(scenarios = d.scenarios.copy(worst = it)) },
        ).forEach { col ->
            RowCard {
                Text(col.title, color = col.tint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                TwoCols(
                    { NumField("Выручка ×", col.cfg.revenueMult, it) { v -> set(col.save(col.cfg.copy(revenueMult = v))) } },
                    { NumField("Расходы ×", col.cfg.opexMult, it) { v -> set(col.save(col.cfg.copy(opexMult = v))) } },
                )
                SummaryLine("Выручка / мес", money(col.res.revenue))
                SummaryLine("Расходы / мес", money(col.res.opex))
                SummaryLine("Прибыль / мес", money(col.res.profit), col.tint, bold = true)
                SummaryLine("Окупаемость", col.res.payback?.let { "${one(it)} мес" } ?: "—", col.tint, bold = true)
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String, tint: Color = Orda.colors.text, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Orda.colors.textMuted, fontSize = 14.sp)
        Text(value, color = tint, fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun RampSection(d: BranchDraft, set: (BranchDraft) -> Unit) {
    OwnerSection("Постепенный выход на нагрузку") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Switch(checked = d.ramp.enabled, onCheckedChange = { set(d.copy(ramp = d.ramp.copy(enabled = it))) })
            Text(if (d.ramp.enabled) "Включено" else "Выключено", color = Orda.colors.textMuted, fontSize = 14.sp)
        }
        if (d.ramp.enabled) {
            TeamHint("Доля целевой загрузки по месяцам (1.0 = 100%). После последнего значения дальше идёт оно же.")
            d.ramp.months.chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    pair.forEachIndexed { i, m ->
                        val idx = row * 2 + i
                        NumField("Мес ${idx + 1}", m, Modifier.weight(1f)) { v ->
                            set(d.copy(ramp = d.ramp.copy(months = d.ramp.months.mapIndexed { j, x -> if (j == idx) v else x })))
                        }
                    }
                    if (pair.size == 1) Column(Modifier.weight(1f)) {}
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TeamPill("+ Месяц") { set(d.copy(ramp = d.ramp.copy(months = d.ramp.months + 1.0))) }
                if (d.ramp.months.size > 1) TeamPill("− Месяц", tint = Orda.colors.negative) { set(d.copy(ramp = d.ramp.copy(months = d.ramp.months.dropLast(1)))) }
            }
        }
    }
}

@Composable
private fun PcSection(d: BranchDraft, calc: BranchCalc, set: (BranchDraft) -> Unit) {
    fun update(id: String, f: (BpPcConfig) -> BpPcConfig) = set(d.copy(pcConfigs = d.pcConfigs.map { if (it.id == id) f(it) else it }))
    OwnerSection("Конфигурации ПК", trailing = {
        TeamPill("+ Конфигурация") { set(d.copy(pcConfigs = d.pcConfigs + BpPcConfig(bpUid(), "Новая конфигурация", 1.0, emptyList()))) }
    }) {
        TeamHint("Цена одного ПК — сумма комплектующих.")
        d.pcConfigs.forEach { cfg ->
            RowCard {
                TeamField("Название", cfg.name, { v -> update(cfg.id) { it.copy(name = v) } })
                TwoCols(
                    { NumField("Количество", cfg.quantity, it) { v -> update(cfg.id) { x -> x.copy(quantity = v) } } },
                    { Column(it) { Text("Цена 1 ПК", color = Orda.colors.textDim, fontSize = 12.sp); Text(money(cfg.unitPrice), color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) } },
                )
                Text("Подитог ${money(cfg.unitPrice * bpNum(cfg.quantity))}", color = Orda.colors.brand, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Комплектующие", color = Orda.colors.textMuted, fontSize = 13.sp)
                cfg.components.forEach { k ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        TeamField("Название", k.name, { v -> update(cfg.id) { x -> x.copy(components = x.components.map { if (it.id == k.id) it.copy(name = v) else it }) } }, modifier = Modifier.weight(1.4f))
                        NumField("Цена", k.price, Modifier.weight(1f)) { v -> update(cfg.id) { x -> x.copy(components = x.components.map { if (it.id == k.id) it.copy(price = v) else it }) } }
                        TeamPill("×", tint = Orda.colors.negative) { update(cfg.id) { x -> x.copy(components = x.components.filter { it.id != k.id }) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TeamPill("+ Комплектующее") { update(cfg.id) { x -> x.copy(components = x.components + BpComponent(bpUid(), "", 0.0)) } }
                    TeamPill("Удалить ПК", tint = Orda.colors.negative) { set(d.copy(pcConfigs = d.pcConfigs.filter { it.id != cfg.id })) }
                }
            }
        }
        Total("Итого ПК:", calc.pcCapex, Orda.colors.brand)
    }
}

@Composable
private fun CapexSection(d: BranchDraft, calc: BranchCalc, set: (BranchDraft) -> Unit) {
    fun update(id: String, f: (BpCapexRow) -> BpCapexRow) = set(d.copy(capex = d.capex.map { if (it.id == id) f(it) else it }))
    OwnerSection("Прочие стартовые вложения", trailing = {
        TeamPill("+ Статья") { set(d.copy(capex = d.capex + BpCapexRow(bpUid(), "", 0.0, 1.0))) }
    }) {
        TeamHint("Мебель, периферия, ремонт, депозит, оборудование зала.")
        d.capex.forEach { r ->
            RowCard {
                TeamField("Статья", r.name, { v -> update(r.id) { it.copy(name = v) } })
                TwoCols(
                    { NumField("Цена, ₸", r.unitPrice, it) { v -> update(r.id) { x -> x.copy(unitPrice = v) } } },
                    { NumField("Кол-во", r.quantity, it) { v -> update(r.id) { x -> x.copy(quantity = v) } } },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Подитог ${money(bpNum(r.unitPrice) * bpNum(r.quantity))}", color = Orda.colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    TeamPill("Удалить", tint = Orda.colors.negative) { set(d.copy(capex = d.capex.filter { it.id != r.id })) }
                }
            }
        }
        Total("Итого прочее:", calc.otherCapex, Orda.colors.brand)
    }
}

@Composable
private fun TariffSection(d: BranchDraft, set: (BranchDraft) -> Unit) {
    fun update(id: String, f: (BpTariff) -> BpTariff) = set(d.copy(tariffs = d.tariffs.map { if (it.id == id) f(it) else it }))
    OwnerSection("Тарифы", trailing = {
        TeamPill("+ Тариф") { set(d.copy(tariffs = d.tariffs + BpTariff(bpUid(), "", 0.0, 0.0, 0.0))) }
    }) {
        TeamHint("₸ за час = цена ÷ (оплаченные + бонусные часы).")
        d.tariffs.forEach { t ->
            RowCard {
                TeamField("Название", t.name, { v -> update(t.id) { it.copy(name = v) } }, placeholder = "2+1")
                TwoCols(
                    { NumField("Оплач. часы", t.paidHours, it) { v -> update(t.id) { x -> x.copy(paidHours = v) } } },
                    { NumField("Бонус. часы", t.bonusHours, it) { v -> update(t.id) { x -> x.copy(bonusHours = v) } } },
                )
                NumField("Цена, ₸", t.price) { v -> update(t.id) { x -> x.copy(price = v) } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${money(t.rate)} / час", color = Orda.colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    TeamPill("Удалить", tint = Orda.colors.negative) { set(d.copy(tariffs = d.tariffs.filter { it.id != t.id })) }
                }
            }
        }
    }
}

@Composable
private fun ZoneSection(d: BranchDraft, calc: BranchCalc, set: (BranchDraft) -> Unit) {
    fun update(id: String, f: (BpZone) -> BpZone) = set(d.copy(zones = d.zones.map { if (it.id == id) f(it) else it }))
    OwnerSection("Зоны и загрузка", trailing = {
        TeamPill("+ Зона") { set(d.copy(zones = d.zones + BpZone(bpUid(), "", 0.0, 0.0, emptyList()))) }
    }) {
        TeamHint("Сколько устройств, средняя загрузка (ч в сутки) и доли тарифов в зоне.")
        d.zones.forEach { z ->
            RowCard {
                TeamField("Название", z.name, { v -> update(z.id) { it.copy(name = v) } }, placeholder = "Премиум")
                TwoCols(
                    { NumField("Устройств", z.deviceCount, it) { v -> update(z.id) { x -> x.copy(deviceCount = Math.round(v).toDouble()) } } },
                    { NumField("Загрузка, ч/сутки", z.occupancyHours, it) { v -> update(z.id) { x -> x.copy(occupancyHours = v) } } },
                )
                if (d.tariffs.isNotEmpty()) {
                    Text("Доля тарифов, % (сумма ≈ 100)", color = Orda.colors.textMuted, fontSize = 13.sp)
                    d.tariffs.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            pair.forEach { t ->
                                val pct = z.mix.firstOrNull { it.tariffId == t.id }?.sharePct ?: 0.0
                                NumField(t.name.ifBlank { "тариф" }, pct, Modifier.weight(1f)) { v ->
                                    update(z.id) { x ->
                                        val rest = x.mix.filter { it.tariffId != t.id }
                                        x.copy(mix = if (v > 0) rest + BpZoneMix(t.id, v) else rest)
                                    }
                                }
                            }
                            if (pair.size == 1) Column(Modifier.weight(1f)) {}
                        }
                    }
                }
                calc.zones.firstOrNull { it.zone.id == z.id }?.let {
                    Text("Выручка зоны ${money(it.perMonth)} / мес", color = Orda.colors.positive, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                TeamPill("Удалить зону", tint = Orda.colors.negative) { set(d.copy(zones = d.zones.filter { it.id != z.id })) }
            }
        }
    }
}

@Composable
private fun OpexSection(d: BranchDraft, calc: BranchCalc, set: (BranchDraft) -> Unit) {
    fun update(id: String, f: (BpOpex) -> BpOpex) = set(d.copy(opex = d.opex.map { if (it.id == id) f(it) else it }))
    OwnerSection("Ежемесячные расходы", trailing = {
        TeamPill("+ Статья") { set(d.copy(opex = d.opex + BpOpex(bpUid(), "", 0.0, false))) }
    }) {
        TeamHint("Фиксированная сумма или процент от выручки.")
        d.opex.forEach { o ->
            RowCard {
                TeamField("Статья", o.name, { v -> update(o.id) { it.copy(name = v) } }, placeholder = "Аренда")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Switch(checked = o.percentOfRevenue, onCheckedChange = { v -> update(o.id) { it.copy(percentOfRevenue = v) } })
                    Text(if (o.percentOfRevenue) "% от выручки" else "Фиксированная", color = Orda.colors.textMuted, fontSize = 14.sp)
                }
                NumField(if (o.percentOfRevenue) "Процент" else "Сумма, ₸", o.amount) { v -> update(o.id) { x -> x.copy(amount = v) } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "В месяц " + money(if (o.percentOfRevenue) bpNum(o.amount) / 100 * calc.revenue else bpNum(o.amount)),
                        color = Orda.colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                    TeamPill("Удалить", tint = Orda.colors.negative) { set(d.copy(opex = d.opex.filter { it.id != o.id })) }
                }
            }
        }
        Total("Итого в месяц:", calc.opex, Orda.colors.negative)
    }
}
