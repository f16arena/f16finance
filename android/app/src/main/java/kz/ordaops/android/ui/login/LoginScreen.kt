package kz.ordaops.android.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.ordaops.android.core.auth.AuthStore
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

/** Вход: оператор — логином, сотрудник — почтой. Различает сервер по «@». */
@Composable
fun LoginScreen(state: AuthStore.State, onSignIn: (String, String) -> Unit, onForgot: () -> Unit) {
    var login by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val canSubmit = login.isNotBlank() && password.isNotEmpty() && !state.isSigningIn

    Column(
        Modifier
            .fillMaxSize()
            .background(Orda.colors.background)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("Orda", color = Orda.colors.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("Вход в рабочее место", color = Orda.colors.textDim, fontSize = 16.sp)

            state.signOutReason?.let { ErrorBanner(it) }
            state.signInError?.let { ErrorBanner(it) }

            OutlinedTextField(
                value = login,
                onValueChange = { login = it },
                label = { Text("Логин или почта") },
                singleLine = true,
                shape = RoundedCornerShape(Radius.md),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Пароль") },
                singleLine = true,
                shape = RoundedCornerShape(Radius.md),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (canSubmit) onSignIn(login, password) }),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(
                "Войти",
                modifier = Modifier.fillMaxWidth(),
                enabled = canSubmit,
                loading = state.isSigningIn,
                onClick = { onSignIn(login, password) },
            )
            TextButton(onClick = onForgot, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Забыли пароль?", color = Orda.colors.brandBright)
            }
        }
    }
}
