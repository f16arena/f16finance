package kz.ordaops.android.feature.business.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load
import kz.ordaops.android.feature.business.AnalyticsPeriod
import kz.ordaops.android.feature.business.BizFormat
import kz.ordaops.android.feature.business.BusinessViewModel
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.operator.money.TintedIcon
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

/** Очередь расходов на решение: одобрить / отклонить с причиной. Порт `BusinessStore.approve/decline`. */
class ApprovalsViewModel(private val api: FinanceApi, private val shell: BusinessViewModel) : ViewModel() {
    private val _state = MutableStateFlow(Loadable<List<PendingItem>>())
    val state: StateFlow<Loadable<List<PendingItem>>> = _state.asStateFlow()

    fun reload() {
        viewModelScope.launch { _state.load(fresh = { api.pending() }) }
    }

    /** Тихое обновление раз в 30 секунд: расходы заводят на точках, пока владелец смотрит. */
    suspend fun poll() {
        while (true) {
            delay(30_000)
            runCatching { api.pending() }.onSuccess { list -> _state.update { Loadable(data = list) } }
        }
    }

    /** Текст ошибки или `null`. Одобренный уходит из списка сразу, значок на «Сервисах» обновляется. */
    suspend fun approve(item: PendingItem): String? = decide(item) { api.approve(item.id) }

    suspend fun decline(item: PendingItem, reason: String?): String? = decide(item) { api.decline(item.id, reason) }

    private suspend fun decide(item: PendingItem, call: suspend () -> Unit): String? = try {
        call()
        _state.update { s -> s.copy(data = s.data?.filterNot { it.id == item.id }) }
        shell.loadPending()
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }
}

/** «Ожидают одобрения»: решение принимается прямо из карточки. */
@Composable
fun ApprovalsScreen() {
    val env = LocalBusiness.current
    val vm = financeViewModel { e, api -> ApprovalsViewModel(api, e.vm) }
    val state by vm.state.collectAsState()
    val companies by env.vm.companies.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var error by remember { mutableStateOf<String?>(null) }
    var declining by remember { mutableStateOf<PendingItem?>(null) }
    val canApprove = env.can("expenses-pending.approve")
    val canDecline = env.can("expenses-pending.decline")

    LaunchedEffect(Unit) { vm.reload() }
    LaunchedEffect(Unit) { vm.poll() }

    StateContainer(
        state, onRetry = vm::reload, modifier = Modifier.fillMaxSize(),
        isEmpty = { it.isEmpty() },
        emptyIcon = Icons.Filled.Verified, emptyTitle = "Всё согласовано", emptyMessage = "Расходов на одобрении нет.",
    ) { list ->
        OwnerScreenColumn {
            HeroSummary(
                title = "Ждут решения",
                value = money(list.sumOf { it.total }),
                caption = "${list.size} ${BizFormat.expensesWord(list.size)} на согласовании",
                footer = listOf("Наличные" to money(list.sumOf { it.cashAmount }), "Kaspi" to money(list.sumOf { it.kaspiAmount })),
            )
            list.forEach { item ->
                PendingCard(
                    item, companies.firstOrNull { it.id == item.companyId }?.name, canApprove, canDecline,
                    onApprove = {
                        scope.launch {
                            val failure = vm.approve(item)
                            if (failure == null) haptics.success() else { error = failure; haptics.error() }
                        }
                    },
                    onDecline = { declining = item },
                )
            }
            error?.let { ErrorBanner(it) }
        }
    }

    declining?.let { item ->
        var reason by remember { mutableStateOf("") }
        OrdaSheet(money(item.total), onDismiss = { declining = null }) {
            Text("Почему отклоняете?", color = Orda.colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Причину увидит тот, кто подал расход. Без неё человек не поймёт, что исправить.", color = Orda.colors.textMuted, fontSize = 14.sp)
            TextAreaField("Причина", reason, { reason = it }, minLines = 3)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Orda.colors.negative)
                    .clickable {
                        val r = reason
                        declining = null
                        scope.launch {
                            val failure = vm.decline(item, r.ifBlank { null })
                            if (failure == null) haptics.success() else { error = failure; haptics.error() }
                        }
                    }.padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
            ) { Text("Отклонить расход", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Карточка расхода с решением: статья, крупная сумма, кто и где подал, две большие кнопки. */
@Composable
private fun PendingCard(
    item: PendingItem,
    companyName: String?,
    canApprove: Boolean,
    canDecline: Boolean,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
) {
    val category = item.category?.takeIf { it.isNotEmpty() } ?: "Без категории"
    Column(
        Modifier.fillMaxWidth().background(Orda.colors.surface, RoundedCornerShape(22.dp)).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            TintedIcon(expenseIcon(category), FinanceColors.categoryTint(category), 46, corner = 23.dp)
            Column {
                Text(category, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val where = listOfNotNull(companyName, item.date?.let { AnalyticsPeriod.rangeLabel(it, it) }).filter { it.isNotEmpty() }.joinToString(" · ")
                Text(where, color = Orda.colors.textDim, fontSize = 13.sp, maxLines = 1)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("−" + money(item.total), color = Orda.colors.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            // Способ оплаты подписью: при смешанной оплате видно, сколько брать из кассы, а сколько с Kaspi.
            Text(
                when {
                    item.cashAmount > 0 && item.kaspiAmount > 0 ->
                        "Смешанная оплата: наличные ${money(item.cashAmount)} · Kaspi ${money(item.kaspiAmount)}"
                    item.kaspiAmount > 0 -> "Kaspi"
                    else -> "Наличные"
                },
                color = Orda.colors.textDim, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
        }
        item.payee?.takeIf { it.isNotEmpty() }?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Person, null, tint = Orda.colors.textMuted, modifier = Modifier.size(16.dp))
                Text(it, color = Orda.colors.textMuted, fontSize = 14.sp, maxLines = 1)
            }
        }
        (item.reason ?: item.comment)?.takeIf { it.isNotEmpty() }?.let {
            Text(
                it, color = Orda.colors.textMuted, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(Orda.colors.surfaceRaised, RoundedCornerShape(14.dp)).padding(Spacing.md),
            )
        }
        // Кнопки — только при правах: сервер всё равно проверит, но предлагать отклоняемое — обман.
        if (canApprove || canDecline) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (canDecline) DecisionButton("Отклонить", Icons.Filled.Close, Orda.colors.negative, filled = false, Modifier.weight(1f), onDecline)
                if (canApprove) DecisionButton("Одобрить", Icons.Filled.Check, Orda.colors.positive, filled = true, Modifier.weight(1f), onApprove)
            }
        } else {
            Text("У вас нет права решать по расходам — только просмотр.", color = Orda.colors.textDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DecisionButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    filled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.pressScale().clip(CircleShape).background(if (filled) tint else tint.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (filled) Color.White else tint, modifier = Modifier.size(18.dp))
        Text("  $title", color = if (filled) Color.White else tint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
