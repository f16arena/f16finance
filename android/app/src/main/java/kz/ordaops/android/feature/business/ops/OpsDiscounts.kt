package kz.ordaops.android.feature.business.ops

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.operator.money.HeroCard
import kz.ordaops.android.feature.operator.money.SoftDivider
import kz.ordaops.android.feature.operator.money.StatRow
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// «Скидки»: скидки в процентах, фиксированные и промокоды — RetailModels.swift / DiscountsScreen.

private const val DISCOUNTS_PATH = "/api/admin/discounts"

enum class DiscountKind(val raw: String, val title: String) {
    Percent("percent", "Скидка %"),
    Fixed("fixed", "Фиксированная"),
    PromoCode("promo_code", "Промокод");

    val isPercentBased: Boolean get() = this != Fixed

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Percent
    }
}

enum class DiscountState(val title: String) {
    Live("Действует"), Scheduled("Запланирована"), Expired("Истекла"), Exhausted("Лимит выбран"), Disabled("Выключена");
}

/** Скидка или промокод — `Discount`. Сроки — даты без времени `yyyy-MM-dd`. */
data class Discount(
    val id: String,
    val companyId: String?,
    val name: String,
    val kind: DiscountKind,
    val value: Double,
    val promoCode: String?,
    val minOrderAmount: Double,
    val isActive: Boolean,
    val validFrom: String?,
    val validTo: String?,
    val usageLimit: Int?,
    val usageCount: Int,
) {
    /** Действует на всю организацию (не привязана к точке). */
    val isGlobal: Boolean get() = companyId == null

    val valueLabel: String get() = if (kind.isPercentBased) BizFormat.percent(value) else money(value)

    val isExhausted: Boolean get() = usageLimit != null && usageLimit > 0 && usageCount >= usageLimit

    /** Сколько дней до конца; отрицательное — уже закончилась; null — без срока. */
    fun daysLeft(today: LocalDate = LocalDate.now()): Int? =
        parseDay(validTo)?.let { ChronoUnit.DAYS.between(today, it).toInt() }

    fun state(today: LocalDate = LocalDate.now()): DiscountState {
        if (!isActive) return DiscountState.Disabled
        daysLeft(today)?.let { if (it < 0) return DiscountState.Expired }
        parseDay(validFrom)?.let { if (ChronoUnit.DAYS.between(today, it) > 0) return DiscountState.Scheduled }
        if (isExhausted) return DiscountState.Exhausted
        return DiscountState.Live
    }

    companion object {
        fun parse(o: JsonObject) = Discount(
            id = o.str("id") ?: "",
            companyId = o.str("company_id"),
            name = o.str("name") ?: "Без названия",
            kind = DiscountKind.of(o.str("type")),
            value = o.num("value") ?: 0.0,
            promoCode = o.str("promo_code"),
            minOrderAmount = o.num("min_order_amount") ?: 0.0,
            isActive = o.bool("is_active") ?: true,
            validFrom = o.str("valid_from"),
            validTo = o.str("valid_to"),
            usageLimit = o.int("usage_limit"),
            usageCount = o.int("usage_count") ?: 0,
        )
    }
}

/** Скидки, разложенные по состоянию: что действует, что запланировано, что остановилось. */
class DiscountBoard(val items: List<Discount>, today: LocalDate = LocalDate.now()) {
    private val byState = items.groupBy { it.state(today) }
    val live: List<Discount> = byState[DiscountState.Live].orEmpty().sortedByDescending { it.value }
    val scheduled: List<Discount> = byState[DiscountState.Scheduled].orEmpty().sortedBy { parseDay(it.validFrom) ?: LocalDate.MAX }
    val stopped: List<Discount> =
        byState[DiscountState.Expired].orEmpty().sortedByDescending { parseDay(it.validTo) ?: LocalDate.MIN } +
            byState[DiscountState.Exhausted].orEmpty() + byState[DiscountState.Disabled].orEmpty()
    val livePromoCodes: List<Discount> get() = live.filter { it.kind == DiscountKind.PromoCode }
    val deepestPercent: Discount? get() = live.filter { it.kind.isPercentBased }.maxByOrNull { it.value }

    /** Заканчиваются в ближайшую неделю. */
    val expiringSoon: List<Discount> =
        live.filter { d -> d.daysLeft(today)?.let { it in 0..7 } ?: false }.sortedBy { it.daysLeft(today) }
}

/** Черновик скидки: то, что уходит в `createDiscount` / `updateDiscount`. */
data class DiscountDraft(
    val name: String = "",
    val kind: DiscountKind = DiscountKind.Percent,
    val value: String = "",
    val promoCode: String = "",
    val minOrderAmount: String = "",
    val validFrom: String = "",
    val validTo: String = "",
    val usageLimit: String = "",
    val companyId: String = "",
) {
    private fun number(raw: String): Double? =
        raw.replace(',', '.').replace(" ", "").replace(" ", "").trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    val amount: Double? get() = number(value)

    val validationError: String?
        get() {
            val a = amount
            return when {
                name.isBlank() -> "Название скидки обязательно"
                a == null || a < 0 -> "Укажите размер скидки"
                kind.isPercentBased && a > 100 -> "Процент не может быть больше 100"
                kind == DiscountKind.PromoCode && promoCode.isBlank() -> "Введите промокод"
                parseDay(validFrom) != null && parseDay(validTo) != null && parseDay(validTo)!!.isBefore(parseDay(validFrom)) ->
                    "Дата окончания раньше даты начала"
                else -> null
            }
        }

    private fun payload(): JsonObject = buildJsonObject {
        put("name", name.trim())
        put("type", kind.raw)
        put("value", amount ?: 0.0)
        put("min_order_amount", number(minOrderAmount) ?: 0.0)
        val code = promoCode.trim().uppercase()
        put("promo_code", if (kind == DiscountKind.PromoCode && code.isNotEmpty()) code else null)
        put("valid_from", validFrom.ifEmpty { null })
        put("valid_to", validTo.ifEmpty { null })
        val limit = number(usageLimit)
        if (limit != null && limit >= 1) put("usage_limit", limit.toInt()) else put("usage_limit", JsonNull)
    }

    fun createBody(): JsonObject = buildJsonObject {
        put("action", "createDiscount")
        put("payload", JsonObject(payload() + ("company_id" to (companyId.ifEmpty { null }?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull))))
    }

    /** `company_id` не отдаём: перевесить скидку на другую точку роут не умеет, а лишнее поле молча проигнорируется. */
    fun updateBody(id: String): JsonObject = buildJsonObject {
        put("action", "updateDiscount"); put("discountId", id); put("payload", payload())
    }

    companion object {
        fun editing(d: Discount) = DiscountDraft(
            name = d.name, kind = d.kind, value = pointsText(d.value), promoCode = d.promoCode.orEmpty(),
            minOrderAmount = if (d.minOrderAmount > 0) pointsText(d.minOrderAmount) else "",
            validFrom = parseDay(d.validFrom)?.toString().orEmpty(), validTo = parseDay(d.validTo)?.toString().orEmpty(),
            usageLimit = d.usageLimit?.toString().orEmpty(), companyId = d.companyId.orEmpty(),
        )

        /** Восемь символов без путаемых (`0/O`, `1/I`) — для промокода, который диктуют голосом. */
        fun generatedPromoCode(random: kotlin.random.Random = kotlin.random.Random.Default): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            return (0 until 8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}

fun discountToggleBody(id: String, isActive: Boolean): JsonObject = buildJsonObject {
    put("action", "updateDiscount"); put("discountId", id)
    put("payload", buildJsonObject { put("is_active", isActive) })
}

fun discountDeleteBody(id: String): JsonObject = buildJsonObject { put("action", "deleteDiscount"); put("discountId", id) }

private enum class DiscountFilter(val title: String) { Working("Действуют"), Scheduled("Запланированы"), Stopped("Не работают"), All("Все") }

private fun DiscountKind.icon(): ImageVector = when (this) {
    DiscountKind.Percent -> Icons.Filled.Percent
    DiscountKind.Fixed -> Icons.Filled.Payments
    DiscountKind.PromoCode -> Icons.Filled.ConfirmationNumber
}

private fun DiscountState.tint(): Color = when (this) {
    DiscountState.Live -> Color(0xFF16A34A)
    DiscountState.Scheduled -> Color(0xFF2563EB)
    DiscountState.Exhausted -> Color(0xFFD97706)
    else -> Color(0xFF94A3B8)
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText("promo", text))
}

@Composable
fun DiscountsScreen() {
    val env = LocalBusiness.current
    val canCreate = env.can("discounts.create")
    val loader = rememberOpsLoader("discounts", ApiRequest(DISCOUNTS_PATH)) { root -> root.items("data").map(Discount::parse).filter { it.id.isNotEmpty() } }
    val state by loader.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(DiscountFilter.Working.name) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Discount?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val current = DiscountFilter.valueOf(filter)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val board = remember(state.data) { DiscountBoard(state.data.orEmpty()) }
            if (state.data != null) {
                Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    HeroCard {
                        Text("Скидок действует", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                        Text("${board.live.size}", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        if (board.live.isEmpty()) Text("чек проходит по полной цене", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                        Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                            HeroFact("Промокоды", "${board.livePromoCodes.size}")
                            HeroFact("Скоро кончатся", "${board.expiringSoon.size}")
                            board.deepestPercent?.let { HeroFact("Максимум", it.valueLabel) }
                        }
                    }
                    PillSegment(DiscountFilter.entries.map { it.name to it.title }, filter) { filter = it }
                }
            }
            val shown = state.copy(
                data = state.data?.let {
                    when (current) {
                        DiscountFilter.Working -> board.live
                        DiscountFilter.Scheduled -> board.scheduled
                        DiscountFilter.Stopped -> board.stopped
                        DiscountFilter.All -> board.items
                    }
                },
            )
            StateContainer(
                state = shown, onRetry = loader::refresh, modifier = Modifier.weight(1f),
                isEmpty = { it.isEmpty() }, emptyIcon = Icons.Filled.LocalOffer,
                emptyTitle = if (current == DiscountFilter.All) "Скидок нет" else "Здесь пусто",
                emptyMessage = when (current) {
                    DiscountFilter.Working -> "Действующих скидок нет — чек проходит по полной цене."
                    DiscountFilter.Scheduled -> "Скидок с будущей датой начала нет."
                    DiscountFilter.Stopped -> "Все скидки в работе: ни одна не истекла и не выключена."
                    DiscountFilter.All -> "Скидки и промокоды создаются здесь же — кнопкой «+»."
                },
            ) { rows ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = 96.dp)) {
                    items(rows, key = { it.id }, contentType = { "discount" }) { d ->
                        val st = d.state()
                        val sub = buildList {
                            add(d.kind.title)
                            d.promoCode?.takeIf { it.isNotEmpty() }?.let { add(it) }
                            if (d.usageLimit != null) add("${d.usageCount} из ${d.usageLimit}")
                            else if (d.usageCount > 0) add("${d.usageCount} ${plural(d.usageCount, "применение", "применения", "применений")}")
                            d.daysLeft()?.takeIf { it in 0..7 }?.let { add(if (it == 0) "последний день" else "$it ${plural(it, "день", "дня", "дней")}") }
                        }.joinToString(" · ")
                        OpsRow(
                            d.kind.icon(), if (st == DiscountState.Live) Color(0xFF7C3AED) else Orda.colors.textDim, d.name, subtitle = sub,
                            trailing = d.valueLabel, trailingSub = st.title, onClick = { detailId = d.id },
                        )
                    }
                }
            }
        }
        if (canCreate) BoxScopeFab(Modifier.align(Alignment.BottomEnd), description = "Новая скидка") { creating = true }
    }
    state.data?.firstOrNull { it.id == detailId }?.let { d ->
        DiscountSheet(d, onDismiss = { detailId = null }, onEdit = { editing = d }, onChanged = loader::refresh)
    }
    if (creating) DiscountFormSheet(null, onDismiss = { creating = false }, onSaved = { creating = false; loader.refresh() })
    editing?.let { d -> DiscountFormSheet(d, onDismiss = { editing = null }, onSaved = { editing = null; detailId = null; loader.refresh() }) }
}

@Composable
private fun DiscountSheet(d: Discount, onDismiss: () -> Unit, onEdit: () -> Unit, onChanged: () -> Unit) {
    val env = LocalBusiness.current
    val context = LocalContext.current
    // Глобальную скидку трогает только тот, у кого полный доступ: она действует на все точки.
    val editable = !d.isGlobal || env.resolver.isAllAccess
    val canEdit = env.can("discounts.edit") && editable
    val canDelete = env.can("discounts.delete") && editable
    val canCopy = env.can("discounts.copy_promo")
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    var confirmDelete by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val st = d.state()
    OrdaSheet(d.name, onDismiss) {
        submit.error?.let { ErrorBanner(it) }
        HeroCard {
            Text(d.kind.title, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
            Text(d.valueLabel, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(st.title, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        }
        d.promoCode?.takeIf { it.isNotEmpty() }?.let { code ->
            OpsSection("Промокод") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(code, color = Orda.colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (canCopy) TextButton(onClick = { copyToClipboard(context, code); copied = true; haptics.tick() }) { Text(if (copied) "Скопировано" else "Копировать") }
                }
            }
        }
        OpsSection("Условия") {
            if (d.minOrderAmount > 0) StatRow("Минимальный чек", money(d.minOrderAmount))
            longDay(d.validFrom)?.let { StatRow("С", it) }
            longDay(d.validTo)?.let { StatRow("До", it) }
            StatRow("Использовано", d.usageLimit?.let { "${d.usageCount} из $it" } ?: "${d.usageCount}")
            StatRow("Действует", if (d.isGlobal) "на всех точках" else "на одной точке")
        }
        if (canEdit) {
            PrimaryButton(if (d.isActive) "Выключить скидку" else "Включить скидку", Modifier.fillMaxWidth(), loading = submit.busy) {
                submit.run(
                    { env.api.sendUnit(ApiRequest.json(DISCOUNTS_PATH, discountToggleBody(d.id, !d.isActive))) },
                    { haptics.success(); onChanged(); onDismiss() },
                )
            }
            PrimaryButton("Изменить", Modifier.fillMaxWidth()) { onEdit() }
        }
        if (canDelete) {
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Удалить", color = Orda.colors.negative) }
        }
    }
    if (confirmDelete) {
        OpsConfirm(
            "Удалить скидку?", "Скидка перестанет действовать. Чеки, пробитые по ней раньше, сохранятся.", "Удалить",
            onConfirm = {
                confirmDelete = false
                submit.run({ env.api.sendUnit(ApiRequest.json(DISCOUNTS_PATH, discountDeleteBody(d.id))) }, { haptics.success(); onChanged(); onDismiss() })
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun DiscountFormSheet(discount: Discount?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val env = LocalBusiness.current
    val companies = rememberCompanyOptions()
    val canGenerate = env.can("discounts.generate_promo")
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    var draft by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver(
        save = { d -> listOf(d.name, d.kind.raw, d.value, d.promoCode, d.minOrderAmount, d.validFrom, d.validTo, d.usageLimit, d.companyId) },
        restore = { l -> DiscountDraft(l[0], DiscountKind.of(l[1]), l[2], l[3], l[4], l[5], l[6], l[7], l[8]) },
    )) { mutableStateOf(discount?.let(DiscountDraft::editing) ?: DiscountDraft()) }
    val error = draft.validationError
    OrdaSheet(if (discount == null) "Новая скидка" else "Изменить скидку", onDismiss) {
        OpsField("Название", draft.name, { draft = draft.copy(name = it) }, placeholder = "Например: Студенческая", singleLine = true)
        PillSegment(DiscountKind.entries.map { it.raw to it.title }, draft.kind.raw) { draft = draft.copy(kind = DiscountKind.of(it)) }
        OpsField(
            if (draft.kind.isPercentBased) "Размер, %" else "Размер", draft.value,
            { draft = draft.copy(value = it.filter { c -> c.isDigit() || c == ',' || c == '.' }) },
            keyboardType = KeyboardType.Decimal, singleLine = true, suffix = if (draft.kind.isPercentBased) "%" else "₸",
        )
        if (draft.kind == DiscountKind.PromoCode) {
            OpsField("Промокод", draft.promoCode, { draft = draft.copy(promoCode = it.uppercase()) }, singleLine = true)
            if (canGenerate) TextButton(onClick = { draft = draft.copy(promoCode = DiscountDraft.generatedPromoCode()) }) { Text("Придумать код") }
        }
        OpsField("Минимальный чек", draft.minOrderAmount, { draft = draft.copy(minOrderAmount = it.filter { c -> c.isDigit() || c == ',' || c == '.' }) }, keyboardType = KeyboardType.Decimal, singleLine = true, suffix = "₸", placeholder = "Без минимума")
        OpsDateField("Действует с", draft.validFrom, { draft = draft.copy(validFrom = it) })
        OpsDateField("Действует до", draft.validTo, { draft = draft.copy(validTo = it) })
        OpsField("Сколько раз можно применить", draft.usageLimit, { draft = draft.copy(usageLimit = it.filter(Char::isDigit)) }, keyboardType = KeyboardType.Number, singleLine = true, placeholder = "Без ограничения")
        if (discount == null && companies.size > 1) {
            OpsPicker("Точка", listOf("" to "Все точки") + companies.map { it.id to it.name }, draft.companyId, { draft = draft.copy(companyId = it) })
        }
        SoftDivider()
        error?.let { Text(it, color = Orda.colors.warning, fontSize = 14.sp) }
        submit.error?.let { ErrorBanner(it) }
        PrimaryButton(if (discount == null) "Создать" else "Сохранить", Modifier.fillMaxWidth(), enabled = error == null, loading = submit.busy) {
            val body = if (discount == null) draft.createBody() else draft.updateBody(discount.id)
            submit.run({ env.api.sendUnit(ApiRequest.json(DISCOUNTS_PATH, body)) }, { haptics.success(); onSaved() })
        }
    }
}
