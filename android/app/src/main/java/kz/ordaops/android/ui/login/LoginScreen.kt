package kz.ordaops.android.ui.login

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.ordaops.android.core.auth.AuthStore
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.OrdaLogo
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

/**
 * Вход — как LoginView.swift: логотип, слоган, карточка с полями, «Не
 * получается войти?». Оператор входит логином, сотрудник почтой — различает сервер.
 */
@Composable
fun LoginScreen(state: AuthStore.State, onSignIn: (String, String) -> Unit, onForgot: () -> Unit) {
    var login by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val canSubmit = login.isNotBlank() && password.isNotEmpty() && !state.isSigningIn
    val colors = Orda.colors

    Box(Modifier.fillMaxSize().background(colors.background)) {
        // Мягкие синие пятна на фоне — как размытая подложка экрана входа на айфоне.
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(listOf(colors.brand.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width * 1.0f, size.height * 0.08f), radius = size.width * 0.8f),
                radius = size.width * 0.8f, center = Offset(size.width * 1.0f, size.height * 0.08f),
            )
            drawCircle(
                Brush.radialGradient(listOf(colors.brand.copy(alpha = 0.12f), Color.Transparent), center = Offset(0f, size.height * 0.62f), radius = size.width * 0.9f),
                radius = size.width * 0.9f, center = Offset(0f, size.height * 0.62f),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            OrdaLogo(height = 64.dp, color = if (colors.isDark) colors.text else kz.ordaops.android.ui.theme.Navy, subtitleColor = colors.textDim)
            Spacer(Modifier.height(Spacing.xl))
            Text("Бизнес под контролем.", color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(
                "Операции, деньги, люди и склад — в одном месте.",
                color = colors.textDim, fontSize = 16.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            Spacer(Modifier.height(Spacing.xxl))

            Column(
                Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .shadow(24.dp, RoundedCornerShape(Radius.xl), ambientColor = colors.brand.copy(alpha = 0.2f), spotColor = colors.brand.copy(alpha = 0.2f))
                    .background(colors.surface, RoundedCornerShape(Radius.xl))
                    .padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                state.signOutReason?.let { ErrorBanner(it) }
                state.signInError?.let { ErrorBanner(it) }

                LoginField(
                    value = login,
                    onChange = { login = it },
                    placeholder = "Логин или почта",
                    icon = Icons.Outlined.Person,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                )
                LoginField(
                    value = password,
                    onChange = { password = it },
                    placeholder = "Пароль",
                    icon = Icons.Outlined.Lock,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    actions = KeyboardActions(onGo = { if (canSubmit) onSignIn(login, password) }),
                    transformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showPassword) "Скрыть пароль" else "Показать пароль",
                                tint = colors.textMuted,
                            )
                        }
                    },
                )

                Button(
                    onClick = { onSignIn(login, password) },
                    enabled = canSubmit,
                    shape = RoundedCornerShape(Radius.md),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.brand,
                        disabledContainerColor = colors.brand.copy(alpha = 0.4f),
                        disabledContentColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    if (state.isSigningIn) {
                        CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text("Войти", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                TextButton(onClick = onForgot) {
                    Text("Не получается войти?", color = colors.textDim, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(Spacing.xl))
            Text("www.ordaops.kz", color = colors.textDim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun LoginField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    keyboard: KeyboardOptions,
    actions: KeyboardActions = KeyboardActions.Default,
    transformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Orda.colors
    // Заливка без рамки — как поля на айфоне; рамка появляется только в фокусе.
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, color = colors.textDim) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = colors.textDim) },
        trailingIcon = trailing,
        singleLine = true,
        visualTransformation = transformation,
        keyboardOptions = keyboard,
        keyboardActions = actions,
        shape = RoundedCornerShape(Radius.md),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceRaised,
            unfocusedContainerColor = colors.surfaceRaised,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = colors.brand,
            focusedTextColor = colors.text,
            unfocusedTextColor = colors.text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
