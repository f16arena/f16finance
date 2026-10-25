package kz.ordaops.android.feature.operator.shift

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

// Листы открытия и закрытия смены — ShiftScreen.swift (OpenShiftSheet / CloseShiftSheet).

/** Открытие смены: единственное, что надо ввести, — сколько денег в кассе на старте. */
@Composable
internal fun OpenShiftSheet(store: OperatorStore, onDismiss: () -> Unit) {
    var cashText by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ShiftKind.Day) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    // Пустое поле и «0» — разные вещи: ноль в кассе бывает, а «не указал» — повод не пускать дальше.
    val normalized = cashText.replace(',', '.').trim()
    val parsedCash = normalized.toDoubleOrNull()?.takeIf { it >= 0 }

    OrdaSheet("Открытие смены", onDismiss) {
        Text("Сколько денег в кассе на старте?", color = Orda.colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        AmountField("Старт кассы", cashText, { cashText = it })
        Text(
            "Эта сумма — точка отсчёта. По ней при закрытии сойдётся касса.",
            color = Orda.colors.textDim,
            fontSize = 14.sp,
        )
        FormSection("Смена") {
            PillSegment(listOf(ShiftKind.Day to "Дневная", ShiftKind.Night to "Ночная"), kind) { kind = it }
        }
        error?.let { ErrorBanner(it) }
        if (parsedCash == null) {
            Text("Введите сумму в кассе — даже если там ноль", color = Orda.colors.textDim, fontSize = 13.sp)
        }
        PrimaryButton(
            "Открыть смену",
            Modifier.fillMaxWidth(),
            enabled = parsedCash != null,
            loading = submitting,
        ) {
            val cash = parsedCash ?: return@PrimaryButton
            submitting = true
            error = null
            scope.launch {
                val failure = store.openShift(cash, kind)
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
}

/** Закрытие смены: пересчёт кассы, Kaspi и поправки — те же поля, что в программе на точке. */
@Composable
internal fun CloseShiftSheet(store: OperatorStore, onDismiss: () -> Unit) {
    var cashText by remember { mutableStateOf("") }
    var coinsText by remember { mutableStateOf("") }
    var kaspiText by remember { mutableStateOf("") }
    /** Ночная смена: часть Kaspi проходит до полуночи, часть после. */
    var kaspiBeforeText by remember { mutableStateOf("") }
    var kaspiOnlineText by remember { mutableStateOf("") }
    var debtsText by remember { mutableStateOf("") }
    // Старт кассы уже вводили при открытии — подставляем, а не просим вспоминать.
    var startCashText by remember {
        mutableStateOf(store.shift?.openingCash?.takeIf { it > 0 }?.toLong()?.toString() ?: "")
    }
    var wiponText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    val isNight = store.shift?.shiftType == "night"
    val blocking = store.blockingChecklists
    val totals = store.totals

    val kaspiAfter = maxOf(0.0, parseAmount(kaspiText) - parseAmount(kaspiBeforeText))
    // Тот же расчёт, что в программе на точке: всё, что пришло, минус то, что лежало в ящике до смены, минус комиссия.
    val reportTotal = parseAmount(cashText) + parseAmount(coinsText) + parseAmount(kaspiText) + parseAmount(debtsText) -
        parseAmount(startCashText) - parseAmount(wiponText)
    // Расхождение — по всем наличным: в кассе лежат и купюры, и мелочь.
    val cashDifference = if (cashText.isEmpty() && coinsText.isEmpty()) null
    else parseAmount(cashText) + parseAmount(coinsText) - totals.expectedCash

    OrdaSheet("Закрытие смены", onDismiss) {
        // Итог крупно и расхождение по наличным — видно до отправки, а не в отчёте наутро.
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF0B1F3B), Color(0xFF1B3B6E))),
                    RoundedCornerShape(Radius.xl),
                )
                .padding(Spacing.xl),
        ) {
            Text("Итог по факту", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
            Text(money(reportTotal), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(top = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                SummaryStat("Наличными", money(parseAmount(cashText) + parseAmount(coinsText)))
                SummaryStat("Безналично", money(parseAmount(kaspiText) + parseAmount(kaspiOnlineText)))
            }
            if (cashDifference != null && abs(cashDifference) >= 1) {
                Text(
                    if (cashDifference > 0) "Излишек ${money(cashDifference)}" else "Недостача ${money(abs(cashDifference))}",
                    color = if (cashDifference > 0) Color(0xFFFBBF24) else Color(0xFFFCA5A5),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(top = Spacing.md)
                        .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        FormSection("Наличные") {
            // Купюры и мелочь врозь — как в программе на точке: мелочь остаётся в кассе на размен.
            AmountField("Купюры", cashText, { cashText = it })
            AmountField("Мелочь", coinsText, { coinsText = it })
            ExpectedRow("По системе", totals.expectedCash)
        }

        FormSection("Kaspi") {
            AmountField(if (isNight) "Всего за смену" else "За смену", kaspiText, { kaspiText = it })
            if (isNight) AmountField("Из них до 00:00", kaspiBeforeText, { kaspiBeforeText = it })
            AmountField("Онлайн", kaspiOnlineText, { kaspiOnlineText = it })
            ExpectedRow("По системе", totals.expectedKaspi)
            if (isNight) {
                // Ночная выручка делится между двумя календарными днями: без разделения весь Kaspi ложится на дату закрытия.
                Text(
                    "После полуночи: ${money(kaspiAfter)}. Так же считает программа на точке и дневной отчёт Kaspi.",
                    color = Orda.colors.textDim,
                    fontSize = 13.sp,
                )
            }
        }

        // Долги, старт кассы и wipon — то же, что спрашивает программа на точке.
        FormSection("Поправки") {
            AmountField("Долги за смену", debtsText, { debtsText = it })
            AmountField("Старт кассы", startCashText, { startCashText = it })
            AmountField("Wipon", wiponText, { wiponText = it })
        }

        FormSection("Комментарий") {
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("необязательно") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(Radius.md),
                colors = ordaFieldColors(),
            )
        }

        if (blocking.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Orda.colors.warning.copy(alpha = 0.10f), RoundedCornerShape(Radius.lg))
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Сначала завершите чек-листы", color = Orda.colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                blocking.forEach { Text(it.title, color = Orda.colors.textDim, fontSize = 13.sp) }
            }
        }

        error?.let { ErrorBanner(it) }
        if (blocking.isNotEmpty()) {
            Text("Смену закроете после чек-листов", color = Orda.colors.textDim, fontSize = 13.sp)
        }
        PrimaryButton("Закрыть смену", Modifier.fillMaxWidth(), enabled = blocking.isEmpty(), loading = submitting) {
            submitting = true
            error = null
            scope.launch {
                // Мелочь уходит и в комментарий: сервер хранит одну сумму наличных,
                // а разбивка нужна тому, кто утром разбирает расхождение.
                var comment = notes.trim()
                if (parseAmount(coinsText) > 0) {
                    val coins = "Мелочь: ${money(parseAmount(coinsText))}"
                    comment = if (comment.isEmpty()) coins else "$comment\n$coins"
                }
                val failure = store.closeShift(
                    cash = parseAmount(cashText) + parseAmount(coinsText),
                    kaspi = parseAmount(kaspiText) + parseAmount(kaspiOnlineText),
                    kaspiBeforeMidnight = if (isNight) parseAmount(kaspiBeforeText) else 0.0,
                    kaspiAfterMidnight = if (isNight) kaspiAfter else 0.0,
                    notes = comment.ifEmpty { null },
                    report = ShiftReportDraft(
                        date = todayString(),
                        shift = if (isNight) "night" else "day",
                        shiftId = store.shift?.id,
                        cash = parseAmount(cashText),
                        coins = parseAmount(coinsText),
                        kaspiPos = parseAmount(kaspiText),
                        kaspiOnline = parseAmount(kaspiOnlineText),
                        kaspiBeforeMidnight = if (isNight) parseAmount(kaspiBeforeText) else null,
                        debts = parseAmount(debtsText),
                        startCash = parseAmount(startCashText),
                        wipon = parseAmount(wiponText),
                        comment = comment.ifEmpty { null },
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
}

@Composable
private fun SummaryStat(label: String, value: String) {
    Column {
        Text(label, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Сколько ждёт система — серой строкой под полями. */
@Composable
private fun ExpectedRow(label: String, value: Double) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Orda.colors.textDim, fontSize = 14.sp)
        Text(money(value), color = Orda.colors.textDim, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

// ── «Не смогу выйти» ─────────────────────────────────────────────────────────

/** «Понедельник, 3 ноября» по дате `yyyy-MM-dd`; нераспознанное — как есть. */
private fun dayTitle(date: String): String = runCatching {
    LocalDate.parse(date.take(10)).format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("ru")))
        .replaceFirstChar { it.uppercase() }
}.getOrDefault(date)

/**
 * Заявка «не смогу выйти» по конкретной смене — ShiftIssueSheet.swift. Это не
 * отказ и не самовольная замена: заявка уходит руководителю, а решение
 * принимает он. Причина обязательна: без неё не понять, искать замену на один
 * день или человек заболел на неделю.
 */
@Composable
fun ShiftIssueSheet(
    responseId: String,
    shiftDate: String,
    shiftType: String,
    companyName: String,
    onDone: suspend () -> Unit,
    onDismiss: () -> Unit,
) {
    val env = LocalOperatorEnv.current
    var reason by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Готовые причины: набирать текст стоя в дороге неудобно, руководителю нужна суть.
    val presets = listOf("Заболел", "Семейные обстоятельства", "Учёба", "Не успеваю доехать")
    val canSubmit = reason.trim().length >= 3 && !saving

    OrdaSheet("Не смогу выйти", onDismiss) {
        OrdaCard {
            Text("Смена", color = Orda.colors.textDim, fontSize = 13.sp)
            Text(
                dayTitle(shiftDate) + if (shiftType == "night") ", ночная" else ", дневная",
                color = Orda.colors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(companyName, color = Orda.colors.textMuted, fontSize = 13.sp)
        }
        OrdaCard {
            Text("Причина", color = Orda.colors.textDim, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                presets.forEach { preset ->
                    val on = reason == preset
                    Text(
                        preset,
                        color = if (on) Orda.colors.brand else Orda.colors.textDim,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .background(if (on) Orda.colors.brand.copy(alpha = 0.16f) else Orda.colors.surfaceRaised, RoundedCornerShape(50))
                            .clickable { reason = preset }
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs + 2.dp),
                    )
                }
            }
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Что случилось") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(Radius.md),
                colors = ordaFieldColors(),
            )
            Text(
                "Руководитель увидит заявку и решит, кем заменить. Смена остаётся за вами, пока он не решит иначе.",
                color = Orda.colors.textMuted,
                fontSize = 13.sp,
            )
        }
        error?.let { ErrorBanner(it) }
        PrimaryButton("Отправить руководителю", Modifier.fillMaxWidth(), enabled = canSubmit, loading = saving) {
            saving = true
            error = null
            scope.launch {
                try {
                    env.api.sendUnit(
                        ApiRequest.json(
                            "/api/operator/shifts",
                            buildJsonObject {
                                put("action", "reportIssue")
                                put("responseId", responseId)
                                put("shiftDate", shiftDate)
                                put("shiftType", shiftType)
                                put("reason", reason.trim())
                            },
                        ),
                    )
                    onDone()
                    onDismiss()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.userMessage()
                } finally {
                    saving = false
                }
            }
        }
    }
}
