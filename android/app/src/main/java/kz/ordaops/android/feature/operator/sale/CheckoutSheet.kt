package kz.ordaops.android.feature.operator.sale

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.operator.shift.AmountField
import kz.ordaops.android.feature.operator.shift.FormSection
import kz.ordaops.android.feature.operator.shift.OperatorStore
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.feature.operator.shift.PaymentMethod
import kz.ordaops.android.feature.operator.shift.PointCustomer
import kz.ordaops.android.feature.operator.shift.parseAmount
import kz.ordaops.android.feature.operator.shift.pluralize
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import kotlin.math.abs

/** Количество без хвоста: «3», но «1,5». */
internal fun quantityText(value: Double): String {
    if (value == Math.rint(value)) return value.toLong().toString()
    return String.format(java.util.Locale("ru"), "%.3f", value).trimEnd('0').trimEnd(',')
}

/** Купюры, которыми чаще всего платят: ближайшие суммы не меньше чека (как на кассе десктопа). */
internal fun cashSuggestions(total: Double): List<Long> {
    val out = mutableListOf<Long>()
    for (bill in longArrayOf(500, 1000, 2000, 5000, 10000, 20000)) {
        val v = Math.ceil(total / bill).toLong() * bill
        if (v > total && v !in out) out += v
    }
    return out.take(4)
}

/** Оплата: состав чека, способ, суммы, клиент — CheckoutSheet.swift. */
@Composable
internal fun CheckoutSheet(store: OperatorStore, onDismiss: () -> Unit) {
    var method by remember { mutableStateOf(PaymentMethod.Cash) }
    var cashText by remember { mutableStateOf(store.cartTotal.let { Math.round(it).toString() }) }
    var kaspiText by remember { mutableStateOf("0") }
    /** «Получено от покупателя» при оплате наличными; пусто — без сдачи. */
    var givenText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    /** Клиент чека: карта лояльности. Без него бонусы не начисляются. */
    var customer by remember { mutableStateOf<PointCustomer?>(null) }
    var pickingCustomer by remember { mutableStateOf(false) }
    var showLines by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val total = store.cartTotal

    // Для одиночного способа оплаты суммы очевидны — не заставляем вводить то, что и так известно.
    fun select(option: PaymentMethod) {
        method = option
        when (option) {
            PaymentMethod.Cash -> { cashText = Math.round(total).toString(); kaspiText = "0"; givenText = "" }
            PaymentMethod.Kaspi -> { cashText = "0"; kaspiText = Math.round(total).toString() }
            PaymentMethod.Mixed -> if (parseAmount(cashText) + parseAmount(kaspiText) == 0.0) { cashText = ""; kaspiText = "" }
        }
    }

    OrdaSheet("Оплата", onDismiss) {
        // Сумма к оплате — крупно, как в банке перед переводом.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("К оплате", color = Orda.colors.textDim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(money(total), color = Orda.colors.text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Row(
                Modifier.clickable { showLines = !showLines }.padding(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${store.cartCount} ${pluralize(store.cartCount, "позиция", "позиции", "позиций")}",
                    color = Orda.colors.brand,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    if (showLines) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = Orda.colors.brand,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Состав чека — строками, для сверки. Править его — в самом чеке.
        if (showLines) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Orda.colors.surface, RoundedCornerShape(Radius.lg))
                    .padding(horizontal = Spacing.lg),
            ) {
                store.cart.forEachIndexed { index, line ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Orda.colors.border))
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(line.name, color = Orda.colors.text, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(
                            "${quantityText(line.quantity)} × ${money(line.unitPrice)}",
                            color = Orda.colors.textDim,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = Spacing.sm),
                        )
                        Text(money(line.total), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        FormSection("Чем платят") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PaymentMethod.entries.forEach { option ->
                    val icon = when (option) {
                        PaymentMethod.Cash -> Icons.Filled.Payments
                        PaymentMethod.Kaspi -> Icons.Filled.QrCode
                        PaymentMethod.Mixed -> Icons.Filled.AccountBalanceWallet
                    }
                    MethodTile(option, icon, method == option, Modifier.weight(1f)) { select(option) }
                }
            }
            if (method == PaymentMethod.Mixed) {
                AmountField("Наличными", cashText, { cashText = it })
                AmountField("Kaspi", kaspiText, { kaspiText = it })
            }
        }

        if (method == PaymentMethod.Cash) {
            val given = parseAmount(givenText)
            val short = givenText.isNotBlank() && given < total - 0.5
            FormSection("Получено от покупателя") {
                AmountField("Получено", givenText, { givenText = it }, placeholder = Math.round(total).toString())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    cashSuggestions(total).forEach { bill ->
                        val on = Math.round(given) == bill
                        Box(
                            Modifier
                                .weight(1f)
                                .background(if (on) Orda.colors.brand else Orda.colors.surfaceRaised, RoundedCornerShape(12.dp))
                                .clickable { givenText = bill.toString(); haptics.tick() }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                String.format(java.util.Locale("ru"), "%,d", bill).replace(' ', ' '),
                                color = if (on) Color.White else Orda.colors.text,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().background(Orda.colors.surface, RoundedCornerShape(Radius.md)).padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (short) "Не хватает" else "Сдача", color = Orda.colors.textMuted, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(
                        money(if (short) total - given else (given - total).coerceAtLeast(0.0)),
                        color = if (short) Orda.colors.negative else Orda.colors.positive,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        if (method == PaymentMethod.Mixed) {
            val entered = parseAmount(cashText) + parseAmount(kaspiText)
            if (abs(entered - total) >= 1) {
                Text(
                    if (entered < total) "Не хватает ${money(total - entered)}" else "Больше суммы чека на ${money(entered - total)}",
                    color = Orda.colors.warning,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Карта лояльности лежит у человека на брелке: без привязки к чеку бонусы не начисляются.
        FormSection("Клиент") {
            val picked = customer
            if (picked != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Orda.colors.surface, RoundedCornerShape(Radius.md))
                        .padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(picked.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(picked.subtitle.ifEmpty { "без карты" }, color = Orda.colors.textDim, fontSize = 13.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${picked.loyaltyPoints.toInt()}", color = Orda.colors.positive, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("бонусов", color = Orda.colors.textDim, fontSize = 12.sp)
                    }
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Убрать клиента",
                        tint = Orda.colors.textDim,
                        modifier = Modifier.clickable { customer = null },
                    )
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Orda.colors.surface, RoundedCornerShape(Radius.md))
                        .clickable { pickingCustomer = true }
                        .padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = Orda.colors.brand)
                    Text("Клиент по карте", color = Orda.colors.brand, fontSize = 16.sp)
                }
            }
        }

        error?.let { ErrorBanner(it) }
        val cashGiven = parseAmount(givenText)
        val cashShort = method == PaymentMethod.Cash && givenText.isNotBlank() && cashGiven < total - 0.5
        val payLabel = when {
            method != PaymentMethod.Cash -> "Провести продажу"
            givenText.isNotBlank() && cashGiven > total + 0.5 -> "Оплатить · сдача ${money(cashGiven - total)}"
            else -> "Оплатить без сдачи"
        }
        PrimaryButton(payLabel, Modifier.fillMaxWidth(), enabled = !cashShort, loading = submitting) {
            submitting = true
            error = null
            scope.launch {
                val failure = store.checkout(
                    method, parseAmount(cashText), parseAmount(kaspiText), customer?.id,
                    cashReceived = if (method == PaymentMethod.Cash && givenText.isNotBlank()) cashGiven else null,
                )
                submitting = false
                if (failure != null) {
                    error = failure
                    haptics.error()
                } else {
                    haptics.success()
                    onDismiss()
                }
            }
        }
    }

    if (pickingCustomer) {
        CustomerPickerSheet(onPick = { customer = it }, onDismiss = { pickingCustomer = false })
    }
}

/** Способ оплаты — крупной плиткой: у стойки жмут не глядя, мелкий переключатель промахивали. */
@Composable
private fun MethodTile(option: PaymentMethod, icon: ImageVector, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .background(if (on) Orda.colors.brand else Orda.colors.surfaceRaised, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (on) Color.White else Orda.colors.text)
        Text(option.label, color = if (on) Color.White else Orda.colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Подтверждение проведённой продажи и ссылка на чек (публичная страница по QR). */
@Composable
internal fun SaleReceiptSheet(
    feedback: OperatorStore.SaleFeedback,
    settings: SaleSettings,
    printState: PrintState,
    onPrint: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    OrdaSheet("", onDismiss) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(
                if (feedback.wasQueued) Icons.Filled.Sync else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (feedback.wasQueued) Orda.colors.warning else Orda.colors.positive,
                modifier = Modifier.size(64.dp),
            )
            Text(money(feedback.total), color = Orda.colors.text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text(
                if (feedback.wasQueued) "Сохранено на устройстве" else "Продажа проведена",
                color = Orda.colors.textMuted,
                fontSize = 16.sp,
            )
            feedback.cashReceived?.let { received ->
                Text(
                    "получено ${money(received)} · СДАЧА ${money(feedback.change)}",
                    color = Orda.colors.positive,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            if (feedback.wasQueued) {
                Text(
                    "Чек уйдёт на сервер, как только появится связь.",
                    color = Orda.colors.textDim,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
            feedback.receiptUrl?.takeIf { it.isNotEmpty() }?.let { url ->
                Row(
                    Modifier
                        .background(Orda.colors.surfaceRaised, RoundedCornerShape(Radius.md))
                        .clickable {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            context.startActivity(Intent.createChooser(send, "Отправить чек").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, tint = Orda.colors.text)
                    Text("Отправить чек", color = Orda.colors.text, fontWeight = FontWeight.SemiBold)
                }
            }
            if (settings.canPrint) {
                Row(
                    Modifier
                        .background(Orda.colors.surfaceRaised, RoundedCornerShape(Radius.md))
                        .clickable(enabled = printState.status != PrintStatus.Printing, onClick = onPrint)
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Icon(Icons.Filled.Print, contentDescription = null, tint = Orda.colors.text)
                    Text(
                        when (printState.status) {
                            PrintStatus.Printing -> "Печатаю…"
                            PrintStatus.Done -> "Напечатано · ещё раз"
                            else -> "Печать чека"
                        },
                        color = Orda.colors.text,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                printState.error?.let { ErrorBanner(it) }
            }
            PrimaryButton("Готово", Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}
