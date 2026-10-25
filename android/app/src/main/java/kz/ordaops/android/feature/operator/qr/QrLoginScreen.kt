package kz.ordaops.android.feature.operator.qr

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiError
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.operator.common.operatorViewModel
import kz.ordaops.android.feature.operator.sale.ScannerPane
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

/**
 * Вход по QR — как PointQRLoginScreen.swift: программа на точке показывает
 * код, оператор сканирует его телефоном, терминал входит сам. Пароль не
 * набирается на общей клавиатуре, за которой стоит очередь.
 */
sealed interface QrResult {
    data object Approved : QrResult
    /** Код просрочен: программа на точке обновляет его каждые несколько минут. */
    data object Expired : QrResult
    data object Used : QrResult
    data object NotFound : QrResult
    /** Временный пароль ещё не сменён — QR такой вход не открывает. */
    data object MustChangePassword : QrResult
    data class Failed(val text: String) : QrResult

    val message: String
        get() = when (this) {
            Approved -> "Готово — программа на точке входит"
            Expired -> "Код просрочен. Обновите QR на терминале и отсканируйте заново."
            Used -> "Этим кодом уже вошли. Обновите QR на терминале."
            NotFound -> "Код не распознан. Наведите камеру на QR в программе точки."
            MustChangePassword -> "Сначала смените временный пароль — войдите по паролю на терминале."
            is Failed -> text
        }
}

data class QrState(val sending: Boolean = false, val result: QrResult? = null)

class QrLoginViewModel(private val api: ApiClient) : ViewModel() {
    private val _state = MutableStateFlow(QrState())
    val state: StateFlow<QrState> = _state.asStateFlow()
    /** Уже отправленный код: камера видит QR много раз в секунду. */
    private var handledNonce: String? = null

    fun confirm(scanned: String) {
        if (_state.value.sending || _state.value.result != null) return
        val nonce = nonce(scanned) ?: run {
            _state.update { it.copy(result = QrResult.NotFound) }
            return
        }
        if (nonce == handledNonce) return
        handledNonce = nonce
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            val result = try {
                api.sendUnit(ApiRequest.json("/api/operator/point-qr-confirm", buildJsonObject { put("nonce", nonce) }))
                QrResult.Approved
            } catch (e: ApiError) {
                // Сервер различает случаи — человеку нужно разное действие.
                when {
                    e is ApiError.Conflict -> QrResult.Used
                    e is ApiError.NotFound -> QrResult.NotFound
                    e is ApiError.Forbidden -> QrResult.MustChangePassword
                    e is ApiError.Server && e.status == 410 -> QrResult.Expired
                    else -> QrResult.Failed(e.userMessage)
                }
            } catch (e: Exception) {
                QrResult.Failed(e.userMessage())
            }
            _state.update { QrState(sending = false, result = result) }
        }
    }

    fun again() {
        handledNonce = null
        _state.value = QrState()
    }

    companion object {
        /** В QR — ссылка `.../operator/point-qr-confirm?n=<nonce>`; иногда сканер отдаёт сам код. */
        fun nonce(scanned: String): String? {
            val trimmed = scanned.trim()
            if (trimmed.isEmpty()) return null
            runCatching { Uri.parse(trimmed).getQueryParameter("n") }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            if (trimmed.contains(" ") || trimmed.contains("://")) return null
            return trimmed
        }
    }
}

@Composable
fun QrLoginScreen() {
    val vm = operatorViewModel { env -> QrLoginViewModel(env.api) }
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val colors = Orda.colors

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text("Вход по QR", color = colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            when (val result = state.result) {
                QrResult.Approved -> OrdaCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Icon(Icons.Filled.CheckCircle, null, tint = colors.positive, modifier = Modifier.size(32.dp))
                        Text(result.message, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(onClick = vm::again) { Text("Войти на другом терминале") }
                }
                null -> {
                    OrdaCard {
                        Text("Наведите на код", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("он показан в программе на точке", color = colors.textDim, fontSize = 13.sp)
                        ScannerPane(paused = state.sending, onCode = vm::confirm)
                        if (state.sending) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.brand)
                                Text("Подтверждаем…", color = colors.textDim, fontSize = 13.sp)
                            }
                        }
                    }
                    OrdaCard {
                        Text("Как это работает", color = colors.textDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Step(1, "На терминале откройте вход и нажмите «Войти по QR».")
                        Step(2, "Наведите камеру на код — подтверждение уйдёт само.")
                        Step(3, "Программа на точке войдёт под вами. Пароль вводить не нужно.")
                        // Срок короткий намеренно: снятый на фото QR не должен работать через час.
                        Text("Код живёт несколько минут. Если не успели — обновите его на терминале.", color = colors.textDim, fontSize = 12.sp)
                    }
                }
                else -> OrdaCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Icon(Icons.Filled.Warning, null, tint = colors.warning)
                        Text("Не вышло", color = colors.warning, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(result.message, color = colors.textMuted)
                    OutlinedButton(onClick = vm::again) { Text("Сканировать ещё раз") }
                }
            }
        }
    }
    // Вибрация на итог — один раз на результат.
    androidx.compose.runtime.LaunchedEffect(state.result) {
        when (state.result) {
            QrResult.Approved -> haptics.success()
            null -> Unit
            else -> haptics.error()
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    val colors = Orda.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).background(colors.brand.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", color = colors.brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(text, color = colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
    }
}
