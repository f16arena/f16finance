package kz.ordaops.android.feature.business.pos

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.operator.sale.PrintState
import kz.ordaops.android.feature.operator.sale.PrintStatus
import kz.ordaops.android.feature.operator.sale.cashSuggestions
import kz.ordaops.android.feature.operator.sale.quantityText
import kz.ordaops.android.feature.operator.shift.AmountField
import kz.ordaops.android.feature.operator.shift.FormSection
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.feature.operator.shift.PaymentMethod
import kz.ordaops.android.feature.operator.shift.PointCustomer
import kz.ordaops.android.feature.operator.shift.ordaFieldColors
import kz.ordaops.android.feature.operator.shift.parseAmount
import kz.ordaops.android.feature.operator.shift.pluralize
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.LoadingView
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

/**
 * Оплата веб-кассы: скидка в процентах, клиент и баллы, способ оплаты, «получено — сдача»
 * для наличных, комментарий. Цифры считает [PosMath] теми же формулами, что и сайт; сервер
 * пересчитывает чек по своим ценам и остаткам.
 */
@Composable
internal fun PosCheckoutSheet(vm: PosViewModel, onDismiss: () -> Unit) {
    var method by rememberSaveable { mutableStateOf(PaymentMethod.Cash) }
    var cashText by rememberSaveable { mutableStateOf("") }
    var kaspiText by rememberSaveable { mutableStateOf("") }
    var givenText by rememberSaveable { mutableStateOf("") }
    var discountText by rememberSaveable { mutableStateOf("") }
    var pointsText by rememberSaveable { mutableStateOf("") }
    var comment by rememberSaveable { mutableStateOf("") }
    var customer by remember { mutableStateOf<PointCustomer?>(null) }
    var pickingCustomer by remember { mutableStateOf(false) }
    var showLines by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    val loyalty = vm.catalog?.loyalty
    val subtotal = vm.cartTotal
    val discount = PosMath.discountAmount(subtotal, parseAmount(discountText))
    val after = PosMath.afterDiscount(subtotal, discount)
    val maxPoints = customer?.let { PosMath.maxRedeemable(it.loyaltyPoints, loyalty, after) } ?: 0.0
    val points = if (customer == null) 0.0 else parseAmount(pointsText).coerceIn(0.0, maxPoints)
    val loyaltyDiscount = PosMath.loyaltyDiscount(points, loyalty, after)
    val payable = PosMath.payable(after, loyaltyDiscount)

    // Для одиночного способа суммы очевидны; вводятся только в смешанной оплате.
    val cash = when (method) {
        PaymentMethod.Cash -> payable
        PaymentMethod.Kaspi -> 0.0
        PaymentMethod.Mixed -> parseAmount(cashText)
    }
    val kaspi = when (method) {
        PaymentMethod.Cash -> 0.0
        PaymentMethod.Kaspi -> payable
        PaymentMethod.Mixed -> parseAmount(kaspiText)
    }
    val given = parseAmount(givenText)
    val cashShort = method == PaymentMethod.Cash && givenText.isNotBlank() && given < payable - 0.5

    OrdaSheet("Оплата", onDismiss) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("К оплате", color = Orda.colors.textDim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(money(payable), color = Orda.colors.text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            if (discount > 0 || loyaltyDiscount > 0) {
                Text(
                    "чек ${money(subtotal)} · скидка −${money(discount + loyaltyDiscount)}",
                    color = Orda.colors.textDim,
                    fontSize = 13.sp,
                )
            }
            Text(
                "${vm.cartCount} ${pluralize(vm.cartCount, "позиция", "позиции", "позиций")}",
                color = Orda.colors.brand,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { showLines = !showLines }.padding(Spacing.xs),
            )
        }

        if (showLines) {
            Column(
                Modifier.fillMaxWidth().background(Orda.colors.surface, RoundedCornerShape(Radius.lg)).padding(horizontal = Spacing.lg),
            ) {
                vm.cart.forEachIndexed { index, line ->
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

        FormSection("Скидка") {
            OutlinedTextField(
                value = discountText,
                onValueChange = { text -> discountText = text.filter { it.isDigit() || it == ',' || it == '.' }.take(5) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Скидка, %") },
                placeholder = { Text("0") },
                suffix = { Text("%", color = Orda.colors.textDim) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(Radius.md),
                colors = ordaFieldColors(),
            )
        }

        FormSection("Чем платят") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PaymentMethod.entries.forEach { option ->
                    val icon = when (option) {
                        PaymentMethod.Cash -> Icons.Filled.Payments
                        PaymentMethod.Kaspi -> Icons.Filled.QrCode
                        PaymentMethod.Mixed -> Icons.Filled.AccountBalanceWallet
                    }
                    MethodTile(option, icon, method == option, Modifier.weight(1f)) { method = option }
                }
            }
            if (method == PaymentMethod.Mixed) {
                AmountField("Наличными", cashText, { cashText = it })
                AmountField("Безналичный", kaspiText, { kaspiText = it })
                val entered = cash + kaspi
                if (Math.abs(entered - payable) >= 1) {
                    Text(
                        if (entered < payable) "Не хватает ${money(payable - entered)}" else "Больше суммы чека на ${money(entered - payable)}",
                        color = Orda.colors.warning,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        if (method == PaymentMethod.Cash) {
            FormSection("Получено от покупателя") {
                AmountField("Получено", givenText, { givenText = it }, placeholder = Math.round(payable).toString())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    cashSuggestions(payable).forEach { bill ->
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
                                String.format(java.util.Locale("ru"), "%,d", bill),
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
                    Text(if (cashShort) "Не хватает" else "Сдача", color = Orda.colors.textMuted, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(
                        money(if (cashShort) payable - given else (given - payable).coerceAtLeast(0.0)),
                        color = if (cashShort) Orda.colors.negative else Orda.colors.positive,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Клиент: карта лояльности. Без него бонусы не начисляются и не списываются.
        FormSection("Клиент") {
            val picked = customer
            if (picked != null) {
                Row(
                    Modifier.fillMaxWidth().background(Orda.colors.surface, RoundedCornerShape(Radius.md)).padding(Spacing.md),
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
                        modifier = Modifier.clickable { customer = null; pointsText = "" },
                    )
                }
                if (loyalty?.isActive == true && maxPoints > 0) {
                    OutlinedTextField(
                        value = pointsText,
                        onValueChange = { text -> pointsText = text.filter { it.isDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Списать баллов (до ${maxPoints.toLong()})") },
                        placeholder = { Text("0") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(Radius.md),
                        colors = ordaFieldColors(),
                    )
                    if (loyaltyDiscount > 0) Text("Баллами −${money(loyaltyDiscount)}", color = Orda.colors.warning, fontSize = 13.sp)
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

        OutlinedTextField(
            value = comment,
            onValueChange = { comment = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Комментарий к чеку") },
            shape = RoundedCornerShape(Radius.md),
            colors = ordaFieldColors(),
        )

        error?.let { ErrorBanner(it) }
        val payLabel = when {
            payable <= 0 -> "Нечего оплачивать"
            method != PaymentMethod.Cash -> "Провести продажу"
            givenText.isNotBlank() && given > payable + 0.5 -> "Оплатить · сдача ${money(given - payable)}"
            else -> "Оплатить без сдачи"
        }
        PrimaryButton(payLabel, Modifier.fillMaxWidth(), enabled = !cashShort && payable > 0, loading = submitting) {
            val issue = PosMath.paymentIssue(method, cash, kaspi, payable)
            if (issue != null) {
                error = issue
                haptics.error()
                return@PrimaryButton
            }
            submitting = true
            error = null
            scope.launch {
                val failure = vm.checkout(
                    PosCheckout(
                        method = method,
                        cash = cash,
                        kaspi = kaspi,
                        discountAmount = discount,
                        customerId = customer?.id,
                        customerName = customer?.name,
                        pointsSpent = points,
                        loyaltyDiscount = loyaltyDiscount,
                        comment = comment,
                        cashReceived = if (method == PaymentMethod.Cash && givenText.isNotBlank()) given else null,
                    ),
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
        PosCustomerSheet(vm, onPick = { customer = it; pointsText = "" }, onDismiss = { pickingCustomer = false })
    }
}

/** Способ оплаты — крупной плиткой: у стойки жмут не глядя. */
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

/** Выбор клиента точки: поиск по карте, телефону или имени от двух знаков (`/api/admin/customers`). */
@Composable
private fun PosCustomerSheet(vm: PosViewModel, onPick: (PointCustomer) -> Unit, onDismiss: () -> Unit) {
    var customers by remember { mutableStateOf<List<PointCustomer>>(emptyList()) }
    var search by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Пауза перед запросом: иначе он уходит на каждую цифру карты.
    LaunchedEffect(search) {
        if (search.trim().length < 2) {
            customers = emptyList()
            loadError = null
            return@LaunchedEffect
        }
        delay(300)
        loading = true
        try {
            customers = vm.searchCustomers(search)
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.userMessage()
        } finally {
            loading = false
        }
    }

    OrdaSheet("Клиент", onDismiss) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Карта, телефон или имя") },
            singleLine = true,
            shape = RoundedCornerShape(Radius.md),
            colors = ordaFieldColors(),
        )
        when {
            loadError != null && customers.isEmpty() -> ErrorBanner(loadError.orEmpty())
            loading && customers.isEmpty() -> LoadingView()
            search.trim().length < 2 -> Text("Введите хотя бы два знака.", color = Orda.colors.textDim, fontSize = 14.sp)
            customers.isEmpty() -> EmptyState(Icons.Filled.PersonSearch, "Никого не нашлось", "Проверьте номер карты или телефон.")
            else -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                customers.forEach { customer ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(customer); onDismiss() }
                            .padding(vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(customer.name, color = Orda.colors.text, fontSize = 16.sp)
                            if (customer.subtitle.isNotEmpty()) Text(customer.subtitle, color = Orda.colors.textDim, fontSize = 13.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${customer.loyaltyPoints.toInt()}",
                                color = if (customer.loyaltyPoints > 0) Orda.colors.positive else Orda.colors.textDim,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("бонусов", color = Orda.colors.textDim, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** «Оплата проведена»: сумма, сдача, чек на почту, печать. */
@Composable
internal fun PosReceiptSheet(
    vm: PosViewModel,
    sale: PosReceipt,
    canPrint: Boolean,
    printState: PrintState,
    onPrint: () -> Unit,
    onDismiss: () -> Unit,
) {
    var email by rememberSaveable(sale.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var emailResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()
    val looksValid = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$").matches(email.trim())

    OrdaSheet("", onDismiss) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Orda.colors.positive, modifier = Modifier.size(64.dp))
            Text(money(sale.total), color = Orda.colors.text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text(
                "Оплата проведена" + (sale.saleId?.let { " · чек #${it.takeLast(6)}" } ?: ""),
                color = Orda.colors.textMuted,
                fontSize = 16.sp,
            )
            if (sale.totalDiscount > 0) {
                Text("скидка −${money(sale.totalDiscount)}", color = Orda.colors.textDim, fontSize = 14.sp)
            }
            sale.cashReceived?.let { received ->
                Text(
                    "получено ${money(received)} · СДАЧА ${money(sale.change)}",
                    color = Orda.colors.positive,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }

            // Чек на почту покупателя: адрес проверяет сервер, здесь только грубая подсказка.
            if (sale.saleId != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; emailResult = null },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Почта покупателя") },
                        leadingIcon = { Icon(Icons.Filled.Mail, null, tint = Orda.colors.textDim) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        shape = RoundedCornerShape(Radius.md),
                        colors = ordaFieldColors(),
                    )
                    Text(
                        if (sending) "…" else "Отправить",
                        color = if (looksValid && !sending) Orda.colors.brand else Orda.colors.textDim,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable(enabled = looksValid && !sending) {
                                sending = true
                                scope.launch {
                                    emailResult = vm.sendReceiptEmail(sale.saleId, email)
                                    sending = false
                                }
                            }
                            .padding(Spacing.sm),
                    )
                }
                emailResult?.let { (ok, text) ->
                    Text(text, color = if (ok) Orda.colors.positive else Orda.colors.negative, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }

            if (canPrint) {
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
            PrimaryButton("Новая продажа", Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}
