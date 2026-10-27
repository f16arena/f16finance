package kz.ordaops.android.feature.business.finance

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.AnalyticsPeriod
import kz.ordaops.android.feature.business.LocalBusiness
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
import java.time.LocalDate

// Листы выписки: новый доход, новый расход, правка. Порт AddIncomeSheet,
// AddExpenseSheet, EditIncomeSheet / EditExpenseSheet.

/** Форма расхода держит сессию мастера: документ уходит на сервер сразу, как выбран. */
private class ExpenseFormModel(private val api: FinanceApi) {
    var documentUrls by mutableStateOf<List<String>>(emptyList())
    var documentName by mutableStateOf<String?>(null)
    var uploading by mutableStateOf(false)
    var uploadError by mutableStateOf<String?>(null)
    var submitError by mutableStateOf<String?>(null)
    var submitting by mutableStateOf(false)
    private var sessionId: String? = null

    private suspend fun ensureSession(): String = sessionId ?: api.startExpenseSession().also { sessionId = it }

    suspend fun attach(bytes: ByteArray, name: String, mime: String) {
        if (uploading) return
        uploading = true
        uploadError = null
        try {
            documentUrls = api.uploadExpenseDocument(ensureSession(), bytes, name, mime)
            documentName = name
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            uploadError = e.userMessage()
        } finally {
            uploading = false
        }
    }

    suspend fun submit(draft: ExpenseDraft, series: Pair<SeriesKind, List<SeriesRow>>? = null): Boolean {
        if (submitting) return false
        submitting = true
        submitError = null
        return try {
            if (series == null) api.submitExpense(ensureSession(), draft)
            else api.submitExpenseSeries(ensureSession(), draft, series.first, series.second)
            sessionId = null
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            submitError = e.userMessage()
            // Истёкшую или использованную сессию сервер отдаёт как конфликт: повтор с ней обречён.
            if (e is kz.ordaops.android.core.net.ApiError.Conflict) sessionId = null
            false
        } finally {
            submitting = false
        }
    }
}

/**
 * Новый расход. Расход нельзя записать одним запросом — каждый подтверждён
 * документом, поэтому через мастер (сессия → файл → поля → отправка).
 */
@Composable
fun AddExpenseSheet(vm: LedgerViewModel, onDismiss: () -> Unit) {
    val env = LocalBusiness.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val companies by vm.openCompanies.collectAsState()
    val operators by vm.operators.collectAsState()
    val categories by vm.categories.collectAsState()
    val vendors by vm.vendors.collectAsState()
    val model = remember { ExpenseFormModel(FinanceApi(env.api)) }

    var date by remember { mutableStateOf(AnalyticsPeriod.businessToday().toString()) }
    var companyId by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf("") }
    var operatorId by remember { mutableStateOf("") }
    var itemName by remember { mutableStateOf("") }
    var cash by remember { mutableStateOf("") }
    var kaspi by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ExpenseDocumentKind.Receipt) }
    var vendorId by remember { mutableStateOf("") }
    var payee by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var backdated by remember { mutableStateOf(false) }
    // Серия: налог за полгода одним платежом, а в отчётах он лежит по месяцам.
    var seriesOn by remember { mutableStateOf(false) }
    var seriesKind by remember { mutableStateOf(SeriesKind.Month) }
    var seriesCount by remember { mutableStateOf("6") }
    var seriesMode by remember { mutableStateOf(SeriesAmountMode.PerPeriod) }

    LaunchedEffect(Unit) { vm.loadExpenseRefs(); vm.loadOperators() }
    LaunchedEffect(companies) { if (companyId.isEmpty() && companies.size == 1) companyId = companies[0].id }

    // Себестоимость заводит только владелец: её место в приёмке, где есть накладная.
    val session = env.access.resolver.session
    val canPickCogs = session.isSuperAdmin || session.staffRole == "owner" || env.access.resolver.isAllAccess
    val selectable = if (canPickCogs) categories else categories.filter { !it.isCogs }
    val picksCogs = selectable.firstOrNull { it.id == categoryId }?.isCogs == true

    val draft = ExpenseDraft(
        date = date, companyId = companyId, operatorId = operatorId.ifEmpty { null }, categoryId = categoryId,
        categoryName = selectable.firstOrNull { it.id == categoryId }?.name.orEmpty(),
        amountCash = parseAmount(cash), amountKaspi = parseAmount(kaspi), itemName = itemName, comment = comment,
        documentKind = kind, documentUrls = model.documentUrls,
        whitelistVendorId = vendorId.ifEmpty { null }, oneOffPayee = payee, oneOffReason = reason, backdatedConfirmed = backdated,
    )
    val seriesRows = if (seriesOn) {
        runCatching { ExpenseSeriesLogic.rows(date, seriesKind, seriesCount.toIntOrNull() ?: 0, draft.amountCash, draft.amountKaspi, seriesMode) }.getOrDefault(emptyList())
    } else emptyList()
    val weekAgo = AnalyticsPeriod.businessToday().minusDays(7)
    val isBackdated = runCatching {
        if (seriesOn) seriesRows.any { LocalDate.parse(it.date).isBefore(weekAgo) } else LocalDate.parse(date).isBefore(weekAgo)
    }.getOrDefault(false)
    // Подтверждение нужно только для старых дат: без галочки такая дата не пройдёт.
    val effective = if (isBackdated) draft else draft.copy(backdatedConfirmed = false)
    val today = AnalyticsPeriod.businessToday()
    val seriesBlocker = if (!seriesOn) null else ExpenseSeriesLogic.problem(seriesRows, seriesKind)
        ?: if (seriesRows.any { LocalDate.parse(it.date).isAfter(today) }) "Дата периода не может быть в будущем" else null
    val blocker = effective.validationMessage ?: seriesBlocker ?: if (isBackdated && !backdated) "Подтвердите, что расход задним числом" else null

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { readDocument(context, uri) }
            if (picked == null) model.uploadError = "Не удалось прочитать файл" else model.attach(picked.first, picked.second, picked.third)
        }
    }

    OrdaSheet("Новый расход", onDismiss) {
        FormSection("Что и когда") {
            DateField("Дата", date, { date = it })
            PickerField("Точка", companies.map { Option(it.id, it.name) }, companyId, { companyId = it })
            PickerField("Категория", selectable.map { Option(it.id, it.name) }, categoryId, { categoryId = it })
            if (operators.isNotEmpty()) PickerField("Кто потратил", operators.map { Option(it.id, it.title) }, operatorId, { operatorId = it }, placeholder = "Не указан")
            if (categories.isNotEmpty() && selectable.size < categories.size) {
                FieldHint("Статьи себестоимости здесь не заводятся — для них есть приёмка товара.")
            }
            if (picksCogs) {
                FieldHint(
                    "Себестоимость обычно приходит из приёмки. Заводите её здесь, только если накладной нет — иначе товар посчитается дважды.",
                    warning = true,
                )
            }
            TextLineField("Что купили", itemName, { itemName = it }, placeholder = "Например: картриджи для принтера")
        }
        FormSection("Сколько") {
            AmountField("Наличные", cash, { cash = it })
            AmountField("Безналичный", kaspi, { kaspi = it })
            FieldHint("Комментарий — не меньше 20 символов: через полгода по нему будут вспоминать, за что заплатили.")
            TextAreaField("Зачем и кому", comment, { comment = it })
        }
        FormSection("Серия") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Разнести на несколько периодов", color = Orda.colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(checked = seriesOn, onCheckedChange = { seriesOn = it })
            }
            if (seriesOn) {
                FieldHint("Дата выше — начало серии: каждая запись получит дату своего периода (март — в марте, апрель — в апреле).")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SeriesKind.entries.forEach { k -> DocChip(k.title, seriesKind == k, Modifier.weight(1f)) { seriesKind = k } }
                }
                TextLineField("Сколько периодов (до ${seriesKind.maxPeriods})", seriesCount, { seriesCount = it.filter(Char::isDigit).take(2) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SeriesAmountMode.entries.forEach { m -> DocChip(m.title, seriesMode == m, Modifier.weight(1f)) { seriesMode = m } }
                }
                seriesRows.forEach { r ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(r.label, color = Orda.colors.textMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(money(r.total), color = Orda.colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (seriesRows.isNotEmpty()) Text("Итого: ${money(seriesRows.sumOf { it.total })}", color = Orda.colors.textMuted, fontSize = 13.sp)
            }
        }
        FormSection("Чем подтверждён") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExpenseDocumentKind.entries.take(3).forEach { k ->
                    DocChip(k.title, kind == k, Modifier.weight(1f)) { kind = k }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DocChip("Доверенный поставщик", kind == ExpenseDocumentKind.Whitelist, Modifier.weight(1f)) { kind = ExpenseDocumentKind.Whitelist }
                DocChip("Разовый платёж", kind == ExpenseDocumentKind.OneOff, Modifier.weight(1f)) { kind = ExpenseDocumentKind.OneOff }
            }
            when {
                kind.requiresFile -> {
                    FieldHint("Сфотографируйте документ или выберите файл из галереи.")
                    OutlinedButton(onClick = { picker.launch("*/*") }, enabled = !model.uploading, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when {
                                model.uploading -> "Загружаем документ…"
                                model.documentName != null -> "Приложено: ${model.documentName} · заменить"
                                else -> "Приложить документ"
                            },
                        )
                    }
                    model.uploadError?.let { ErrorBanner(it) }
                }
                kind == ExpenseDocumentKind.Whitelist -> {
                    if (vendors.isEmpty()) {
                        FieldHint("Доверенных поставщиков нет. Их заводят в разделе «Доверенные поставщики» — платёж такому поставщику не требует чека.")
                    }
                    PickerField("Поставщик", vendors.map { Option(it.id, it.name) }, vendorId, { vendorId = it }, placeholder = "Не выбран")
                }
                else -> {
                    TextLineField("Кому заплатили", payee, { payee = it })
                    FieldHint("Причина — не меньше 30 символов. Это единственный расход без подтверждения — объяснение читают при разборе.")
                    TextAreaField("Причина", reason, { reason = it })
                }
            }
        }
        if (isBackdated) {
            Row(
                Modifier.fillMaxWidth().background(Orda.colors.warning.copy(alpha = 0.12f), RoundedCornerShape(Radius.md)).padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Это расход задним числом — подтверждаю", color = Orda.colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(checked = backdated, onCheckedChange = { backdated = it })
            }
        }
        blocker?.let { Text(it, color = Orda.colors.warning, fontSize = 14.sp) }
        model.submitError?.let { ErrorBanner(it) }
        PrimaryButton(
            if (model.submitting) "Сохраняем…" else "Сохранить расход",
            Modifier.fillMaxWidth(),
            enabled = blocker == null && !model.uploading,
            loading = model.submitting,
        ) {
            scope.launch {
                val series = if (seriesOn) seriesKind to seriesRows else null
                if (model.submit(effective, series)) {
                    haptics.success()
                    vm.reload()
                    onDismiss()
                } else {
                    haptics.error()
                }
            }
        }
    }
}

@Composable
private fun DocChip(title: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(Radius.sm),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (on) Orda.colors.brand else Orda.colors.surfaceRaised,
            contentColor = if (on) androidx.compose.ui.graphics.Color.White else Orda.colors.text,
        ),
    ) { Text(title, fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.SemiBold) }
}

/** Прочитать выбранный файл целиком: байты, имя, тип. */
private fun readDocument(context: android.content.Context, uri: Uri): Triple<ByteArray, String, String>? = runCatching {
    val resolver = context.contentResolver
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val mime = resolver.getType(uri) ?: "image/jpeg"
    var name = "document"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) name = c.getString(0) ?: name
    }
    Triple(bytes, name, mime)
}.getOrNull()

/** Исправление дохода: дата, оператор, суммы, комментарий. «Kaspi до полуночи» уходит как было. */
@Composable
fun EditIncomeSheet(vm: LedgerViewModel, row: IncomeRow, onDismiss: () -> Unit) {
    val operators by vm.operators.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var edit by remember { mutableStateOf(IncomeEdit(row)) }
    var cash by remember { mutableStateOf(amountText(row.cashAmount)) }
    var kaspi by remember { mutableStateOf(amountText(row.kaspiAmount)) }
    var card by remember { mutableStateOf(amountText(row.cardAmount)) }
    var online by remember { mutableStateOf(amountText(row.onlineAmount)) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadOperators() }

    val current = edit.copy(cash = parseAmount(cash), kaspi = parseAmount(kaspi), card = parseAmount(card), online = parseAmount(online))
    OrdaSheet("Исправить доход", onDismiss) {
        DateField("Дата", current.date, { edit = edit.copy(date = it) })
        PickerField("Оператор", operators.map { Option(it.id, it.title) }, current.operatorId.orEmpty(), { edit = edit.copy(operatorId = it.ifEmpty { null }) })
        AmountField("Наличные", cash, { cash = it })
        AmountField("Безналичный", kaspi, { kaspi = it })
        AmountField("Карта", card, { card = it })
        AmountField("Онлайн", online, { online = it })
        TextAreaField("Комментарий", current.comment, { edit = edit.copy(comment = it) }, minLines = 1)
        Text("Итого: ${money(current.total)}", color = Orda.colors.textMuted, fontSize = 14.sp)
        current.problem?.let { Text(it, color = Orda.colors.warning, fontSize = 14.sp) }
        error?.let { ErrorBanner(it) }
        PrimaryButton("Сохранить", Modifier.fillMaxWidth(), enabled = current.problem == null, loading = saving) {
            saving = true
            error = null
            scope.launch {
                val failure = vm.saveIncomeEdit(current)
                saving = false
                if (failure == null) { haptics.success(); onDismiss() } else { error = failure; haptics.error() }
            }
        }
    }
}

/** Исправление расхода: точка, дата, статья, суммы, комментарий. */
@Composable
fun EditExpenseSheet(vm: LedgerViewModel, row: ExpenseRow, onDismiss: () -> Unit) {
    val companies by vm.companies.collectAsState()
    val categories by vm.categories.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val initial = remember(row) { ExpenseEdit.of(row) }
    if (initial == null) {
        OrdaSheet("Исправить расход", onDismiss) { Text("У записи нет точки — исправить её отсюда нельзя.", color = Orda.colors.textMuted) }
        return
    }
    var edit by remember { mutableStateOf(initial) }
    var cash by remember { mutableStateOf(amountText(row.cashAmount)) }
    var kaspi by remember { mutableStateOf(amountText(row.kaspiAmount)) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadExpenseRefs() }

    val current = edit.copy(cash = parseAmount(cash), kaspi = parseAmount(kaspi))
    // Статья правится по названию; текущей может не быть в справочнике — оставляем как есть.
    val names = (categories.map { it.name } + listOf(current.category)).filter { it.isNotBlank() }.distinct()
    OrdaSheet("Исправить расход", onDismiss) {
        DateField("Дата", current.date, { edit = edit.copy(date = it) })
        PickerField("Точка", companies.map { Option(it.id, it.name) }, current.companyId, { if (it.isNotEmpty()) edit = edit.copy(companyId = it) })
        PickerField("Категория", names.map { Option(it, it) }, current.category, { edit = edit.copy(category = it) })
        AmountField("Наличные", cash, { cash = it })
        AmountField("Безналичный", kaspi, { kaspi = it })
        TextAreaField("Комментарий", current.comment, { edit = edit.copy(comment = it) }, minLines = 1)
        current.problem?.let { Text(it, color = Orda.colors.warning, fontSize = 14.sp) }
        error?.let { ErrorBanner(it) }
        PrimaryButton("Сохранить", Modifier.fillMaxWidth(), enabled = current.problem == null, loading = saving) {
            saving = true
            error = null
            scope.launch {
                val failure = vm.saveExpenseEdit(current)
                saving = false
                if (failure == null) { haptics.success(); onDismiss() } else { error = failure; haptics.error() }
            }
        }
    }
}

/** Сумма в поле: целые без «.0», дробные через запятую; ноль — пустое поле. */
internal fun amountText(value: Double): String = when {
    value == 0.0 -> ""
    value == Math.floor(value) -> value.toLong().toString()
    else -> value.toString().replace('.', ',')
}
