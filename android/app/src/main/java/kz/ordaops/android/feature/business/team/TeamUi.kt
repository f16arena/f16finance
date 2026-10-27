package kz.ordaops.android.feature.business.team

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.operator.money.TintedIcon
import kz.ordaops.android.feature.operator.shift.ordaFieldColors
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

// Общие детали экранов «Команды»: загрузчик ответов, кружок с буквой, поля,
// строки действий. Свои копии нужны там, где общие закрыты или другой формы.

/**
 * ViewModel «загрузить ответ и показать»: сначала кэш `ApiClient`, потом сеть,
 * показанное не пропадает. `P` — то, от чего зависит запрос (неделя), для
 * экранов без параметра — `Unit`.
 */
class ResourceVm<P : Any, T>(
    private val api: ApiClient,
    initial: P,
    private val request: (P) -> ApiRequest,
    private val parse: (JsonElement) -> T,
) : ViewModel() {
    var param: P = initial
        private set

    private val _state = MutableStateFlow(Loadable<T>())
    val state: StateFlow<Loadable<T>> = _state.asStateFlow()

    private var job: Job? = null

    init {
        reload()
    }

    /** Другая неделя / другой параметр: старое не сбрасываем, пока не пришло новое. */
    fun setParam(value: P) {
        if (value == param) return
        param = value
        _state.value = Loadable()
        reload()
    }

    fun reload() {
        job?.cancel()
        val current = param
        job = viewModelScope.launch {
            val req = request(current)
            _state.load(
                cached = { api.cached<JsonElement>(req)?.let(parse) },
                fresh = { parse(api.send<JsonElement>(req)) },
            )
        }
    }

    /** Перезагрузка из корутины действия — ждёт завершения, чтобы лист закрылся на свежих цифрах. */
    suspend fun refreshNow() {
        val req = request(param)
        try {
            _state.load(fresh = { parse(api.send<JsonElement>(req)) })
        } catch (e: CancellationException) {
            throw e
        }
    }
}

/** ViewModel-загрузчик с ключом по организации и типу — на общем устройстве чужое не покажем. */
@Composable
fun <P : Any, T> rememberResource(
    key: String,
    initial: P,
    request: (P) -> ApiRequest,
    parse: (JsonElement) -> T,
): ResourceVm<P, T> {
    val env = LocalBusiness.current
    return viewModel(
        key = "team:$key:${env.api.organizationId.orEmpty()}",
        factory = viewModelFactory { initializer { ResourceVm(env.api, initial, request, parse) } },
    )
}

// ── Человек ──────────────────────────────────────────────────────────────────

private val AvatarPalette = listOf(
    Color(0xFF10B981), Color(0xFF3B82F6), Color(0xFFF59E0B),
    Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF14B8A6),
)

/** Цвет закреплён за именем: у одного человека он один на всех экранах. */
fun avatarColor(name: String): Color {
    var hash = 0
    for (ch in name) hash = (hash * 31 + ch.code) and 0x7FFFFFFF
    return AvatarPalette[hash % AvatarPalette.size]
}

/** Кружок с первой буквой имени. */
@Composable
fun PersonInitial(name: String, isActive: Boolean = true, size: Int = 40) {
    val tint = if (isActive) avatarColor(name) else Orda.colors.textDim
    Box(Modifier.size(size.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
        Text(
            name.trim().take(1).uppercase(),
            color = tint, fontSize = (size * 0.4f).sp, fontWeight = FontWeight.Bold,
        )
    }
}

// ── Строки и поля ────────────────────────────────────────────────────────────

/** Действие в карточке человека: иконка в квадрате, название, шеврон (или индикатор). */
@Composable
fun TeamActionRow(icon: ImageVector, title: String, tint: Color, loading: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale()
            .clickable(enabled = !loading, onClick = onClick)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        TintedIcon(icon, tint, 40, corner = 12.dp)
        Text(
            title,
            color = if (tint == Orda.colors.negative) Orda.colors.negative else Orda.colors.text,
            fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
        )
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Orda.colors.brand)
        } else {
            Icon(Icons.Filled.ChevronRight, null, tint = Orda.colors.textDim, modifier = Modifier.size(18.dp))
        }
    }
}

/** Строка данных профиля: иконка, подпись серым, значение справа. */
@Composable
fun TeamInfoRow(icon: ImageVector, tint: Color, label: String, value: String, valueColor: Color = Orda.colors.text) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        TintedIcon(icon, tint, 36, corner = 11.dp)
        Text(label, color = Orda.colors.textMuted, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.2f, fill = false))
    }
}

/** Однострочное поле формы в общем стиле. */
@Composable
fun TeamField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(Radius.md),
        colors = ordaFieldColors(),
    )
}

/** Подсказка мелким серым под полем. */
@Composable
fun TeamHint(text: String, color: Color = Orda.colors.textDim) {
    Text(text, color = color, fontSize = 13.sp)
}

/** Строка выбора из списка: кружок-отметка и название. */
@Composable
fun TeamChoiceRow(title: String, selected: Boolean, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            Modifier.size(22.dp).background(if (selected) Orda.colors.brand else Color.Transparent, CircleShape)
                .then(if (selected) Modifier else Modifier.background(Orda.colors.surfaceRaised, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).background(Color.White, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Orda.colors.text, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = Orda.colors.textDim, fontSize = 12.sp)
        }
    }
}

/** Тонкий разделитель внутри секции. */
@Composable
fun TeamDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Orda.colors.border.copy(alpha = 0.6f)))
}

/** Кнопка-«таблетка» вторичного действия. */
@Composable
fun TeamPill(text: String, tint: Color = Orda.colors.brand, onClick: () -> Unit) {
    Text(
        text, color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(tint.copy(alpha = 0.12f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Красная строка ошибки, если она есть. */
@Composable
fun TeamError(message: String?) {
    if (message != null) kz.ordaops.android.ui.components.ErrorBanner(message)
}

/** Подтверждение опасного действия (закрыть доступ, отменить выплату). */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Orda.colors.elevated,
        title = { Text(title, color = Orda.colors.text, fontWeight = FontWeight.Bold) },
        text = { Text(message, color = Orda.colors.textMuted) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) Orda.colors.negative else Orda.colors.brand, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = Orda.colors.textMuted) } },
    )
}

/** Кнопка отправки формы с индикатором; недоступна, пока есть причина или идёт запрос. */
@Composable
fun SubmitButton(title: String, busy: Boolean, problem: String?, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (problem != null) TeamHint(problem, Orda.colors.warning)
        PrimaryButton(title, modifier = Modifier.fillMaxWidth(), enabled = problem == null, loading = busy, onClick = onClick)
    }
}

/** Состояние действия: идёт ли запрос и что вернулось. */
class ActionState {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
fun rememberActionState(): ActionState = remember { ActionState() }

/**
 * Выполнить запись: индикатор, текст ошибки сервера, затем `onSuccess` (обновить
 * список, закрыть лист). Сбой обновления после удачной записи ошибкой не считаем —
 * запись уже прошла.
 */
fun ActionState.launch(scope: CoroutineScope, onSuccess: suspend () -> Unit = {}, block: suspend () -> Unit) {
    scope.launch {
        busy = true
        error = null
        val ok = try {
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
            false
        } finally {
            busy = false
        }
        if (ok) runCatching { onSuccess() }
    }
}

/** Скопировать в буфер (логин и пароль при выдаче доступа). */
fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Пароль без похожих знаков: ноль и «o», единица и «l» неразличимы на бумажке. */
fun generatePassword(random: java.util.Random = java.security.SecureRandom()): String {
    val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
    return (0 until 10).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}

/** Градиентная карточка с главной цифрой-текстом (не деньгами): «7 из 10», «85 %». */
@Composable
fun TeamHeroCard(title: String, value: String, footer: List<Pair<String, String>>, colors: List<Color> = kz.ordaops.android.feature.business.HeroColors.Accent, caption: String? = null) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.linearGradient(colors), shape)
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(title, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(value, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (caption != null) Text(caption, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        Row(Modifier.padding(top = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            footer.forEach { (label, text) ->
                Column {
                    Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}
