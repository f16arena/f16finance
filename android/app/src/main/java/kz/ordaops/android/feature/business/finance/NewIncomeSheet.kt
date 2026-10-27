package kz.ordaops.android.feature.business.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.business.AnalyticsPeriod
import kz.ordaops.android.feature.operator.shift.AmountField
import kz.ordaops.android.feature.operator.shift.FormSection
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.feature.operator.shift.PillSegment
import kz.ordaops.android.feature.operator.shift.parseAmount
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

/**
 * Новый доход за смену. Поля — по профилю точки, как на сайте (а не по кодам F16, как
 * было на айфоне): у точки с зонами выручки — суммы по зонам (наличные/безнал) пачкой
 * `createIncomeBatch`; «онлайн» — только у клуба; ночная смена — только если она есть
 * в графике. Закрытые точки не предлагаются. Дубль (409) — не ошибка, а «сохранить всё равно».
 */
@Composable
fun AddIncomeSheet(vm: LedgerViewModel, onDismiss: () -> Unit) {
    val companies by vm.openCompanies.collectAsState()
    val operators by vm.operators.collectAsState()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(AnalyticsPeriod.businessToday().toString()) }
    var companyId by remember { mutableStateOf("") }
    var operatorId by remember { mutableStateOf("") }
    var shift by remember { mutableStateOf("day") }
    var cash by remember { mutableStateOf("") }
    var kaspi by remember { mutableStateOf("") }
    var card by remember { mutableStateOf("") }
    var online by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var zoneCash by remember { mutableStateOf(mapOf<String, String>()) }
    var zoneCashless by remember { mutableStateOf(mapOf<String, String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var duplicate by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadOperators() }
    LaunchedEffect(companies) { if (companyId.isEmpty() && companies.size == 1) companyId = companies[0].id }

    val company = companies.firstOrNull { it.id == companyId }
    val profile = PointLogic.profile(company)
    val zones = PointLogic.activeZones(company)
    val byZone = zones.isNotEmpty()
    // Смена точки сбрасывает суммы: чужие зоны и поля не должны уехать в новую запись.
    LaunchedEffect(companyId) {
        cash = ""; kaspi = ""; card = ""; online = ""
        zoneCash = emptyMap(); zoneCashless = emptyMap()
        if (!profile.hasNightShift) shift = "day"
    }
    val zoneAmounts = zones.map { z -> z to ZoneAmount(parseAmount(zoneCash[z.key].orEmpty()), parseAmount(zoneCashless[z.key].orEmpty())) }

    val draft = IncomeDraft(
        date = date, companyId = companyId, operatorId = operatorId.ifEmpty { null }, shift = shift,
        cash = parseAmount(cash), kaspi = parseAmount(kaspi), card = parseAmount(card),
        online = if (profile.showOnline) parseAmount(online) else 0.0,
        comment = comment, zone = PointLogic.singleZone(company),
    )
    val total = if (byZone) zoneAmounts.sumOf { it.second.total } else draft.total
    val blocker = if (byZone) {
        when {
            date.isEmpty() -> "Дата обязательна"
            companyId.isEmpty() -> "Точка обязательна"
            operatorId.isEmpty() -> "Оператор обязателен"
            total <= 0 -> "Укажите сумму хотя бы по одной зоне: ${zones.joinToString(", ") { it.label }}"
            else -> null
        }
    } else draft.validationMessage

    fun save(force: Boolean) {
        error = null
        saving = true
        scope.launch {
            val result = if (byZone) {
                vm.createZoneIncome(zoneIncomeBatchBody(date, companyId, operatorId, shift, comment, zoneAmounts))
            } else {
                vm.createIncome(draft, force)
            }
            when (result) {
                SaveOutcome.Saved -> { haptics.success(); onDismiss() }
                SaveOutcome.Duplicate -> duplicate = true
                is SaveOutcome.Failed -> error = result.message
            }
            saving = false
        }
    }

    OrdaSheet("Новый доход", onDismiss) {
        FormSection("Смена") {
            DateField("Дата", date, { date = it })
            PickerField("Точка", companies.map { Option(it.id, it.name) }, companyId, { companyId = it })
            PickerField("Оператор", operators.filter { it.isActive }.map { Option(it.id, it.title) }, operatorId, { operatorId = it })
            if (profile.hasNightShift) PillSegment(listOf("day" to "День", "night" to "Ночь"), shift) { shift = it }
        }
        FormSection("Суммы") {
            if (byZone) {
                zones.forEach { z ->
                    Text(z.label, color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    AmountField("Наличные", zoneCash[z.key].orEmpty(), { zoneCash = zoneCash + (z.key to it) })
                    AmountField("Безналичный", zoneCashless[z.key].orEmpty(), { zoneCashless = zoneCashless + (z.key to it) })
                }
            } else {
                AmountField("Наличные", cash, { cash = it })
                AmountField("Безналичный", kaspi, { kaspi = it })
                AmountField("Карта", card, { card = it })
                if (profile.showOnline) AmountField("Онлайн", online, { online = it })
            }
            TextAreaField("Комментарий", comment, { comment = it }, placeholder = "Необязательно", minLines = 1)
        }
        Row(
            Modifier.fillMaxWidth().background(Orda.colors.positive.copy(alpha = 0.12f), RoundedCornerShape(Radius.md)).padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Итого за смену", color = Orda.colors.textMuted, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(money(total), color = Orda.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (duplicate) {
            Column(
                Modifier.fillMaxWidth().background(Orda.colors.warning.copy(alpha = 0.12f), RoundedCornerShape(Radius.md)).padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text("Такая выручка уже заведена", color = Orda.colors.text, fontWeight = FontWeight.SemiBold)
                Text(
                    "За эту дату и смену уже есть запись с теми же суммами. Обычно это повторная отправка. " +
                        "Если выручку правда сдавали дважды — сохраните всё равно.",
                    color = Orda.colors.textMuted, fontSize = 13.sp,
                )
                OutlinedButton(onClick = { save(true) }, enabled = !saving) { Text("Сохранить всё равно") }
            }
        }
        blocker?.let { Text(it, color = Orda.colors.warning, fontSize = 14.sp) }
        error?.let { ErrorBanner(it) }
        PrimaryButton(if (saving) "Сохраняем…" else "Сохранить", Modifier.fillMaxWidth(), enabled = blocker == null, loading = saving) { save(false) }
    }
}
