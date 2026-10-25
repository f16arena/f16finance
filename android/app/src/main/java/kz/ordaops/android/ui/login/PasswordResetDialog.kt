package kz.ordaops.android.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import kz.ordaops.android.BuildConfig
import kz.ordaops.android.OrdaApplication
import kz.ordaops.android.core.auth.SessionClient
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

/**
 * Сброс пароля внутри приложения — как PasswordResetSheet.swift: почта →
 * код из письма → новый пароль. Без ухода на сайт.
 */
@Composable
fun PasswordResetDialog(app: OrdaApplication, onDismiss: () -> Unit) {
    val client = remember { SessionClient(BuildConfig.API_BASE_URL) }
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Orda.colors.elevated,
        title = { Text(if (step == 2) "Пароль изменён" else "Сброс пароля", color = Orda.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                error?.let { ErrorBanner(it) }
                when (step) {
                    0 -> {
                        Text("Пришлём код на почту. Операторам без почты пароль меняет администратор.", color = Orda.colors.textDim)
                        OutlinedTextField(
                            email, { email = it }, label = { Text("Почта") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    1 -> {
                        Text("Если почта заведена в компании, письмо с кодом уже в пути.", color = Orda.colors.textDim)
                        OutlinedTextField(
                            code, { code = it }, label = { Text("Код из письма") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            password, { password = it }, label = { Text("Новый пароль") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> Text("Войдите с новым паролем.", color = Orda.colors.textDim)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && when (step) {
                    0 -> email.contains("@")
                    1 -> code.isNotBlank() && password.length >= 6
                    else -> true
                },
                onClick = {
                    when (step) {
                        0 -> run { client.requestPasswordReset(email); step = 1 }
                        1 -> run { client.confirmPasswordReset(email, code, password); step = 2 }
                        else -> onDismiss()
                    }
                },
            ) { Text(if (step == 0) "Прислать код" else if (step == 1) "Сохранить" else "Готово") }
        },
        dismissButton = { if (step < 2) TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
