package kz.ordaops.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kz.ordaops.android.OrdaApplication
import kz.ordaops.android.core.access.Workspace
import kz.ordaops.android.core.auth.AuthStore
import kz.ordaops.android.feature.operator.OperatorRoot
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.LoadingView
import kz.ordaops.android.ui.login.LoginScreen
import kz.ordaops.android.ui.login.PasswordResetDialog
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

/** Корень: вход → права → рабочее место по должности. Как RootView.swift. */
@Composable
fun RootScreen(app: OrdaApplication) {
    val state by app.auth.state.collectAsState()
    var resetOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Orda.colors.background)) {
        when (state.phase) {
            AuthStore.Phase.Restoring -> LoadingView()
            AuthStore.Phase.SignedOut -> {
                LoginScreen(state, onSignIn = app.auth::signIn, onForgot = { resetOpen = true }, onQuickEntry = app.auth::signInWithQuickEntry)
                if (resetOpen) PasswordResetDialog(app, onDismiss = { resetOpen = false })
            }
            AuthStore.Phase.LoadingRole -> LoadingView("Проверяем доступ…")
            AuthStore.Phase.SignedIn -> {
                PushRegistration(app)
                WorkspaceView(app, state)
            }
        }
    }
}

@Composable
private fun WorkspaceView(app: OrdaApplication, state: AuthStore.State) {
    val resolver = state.resolver
    if (resolver == null) {
        // Роль не загрузилась (обычно сеть). Даём повторить, не выкидывая из аккаунта.
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(
                icon = Icons.Filled.SignalWifiOff,
                title = "Не удалось получить доступы",
                message = state.roleError ?: "Проверьте связь и попробуйте ещё раз.",
                actionTitle = "Повторить",
                onAction = app.auth::reloadRole,
            )
            TextButton(onClick = app.auth::signOut) { Text("Выйти из аккаунта", color = Orda.colors.negative) }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        state.roleError?.let {
            ErrorBanner(it, Modifier.statusBarsPadding().padding(Spacing.md).fillMaxWidth())
        }
        when (resolver.workspace) {
            Workspace.Operator -> OperatorRoot(resolver, app.api, onSignOut = app.auth::signOut)
            else -> NotYetOnAndroid(
                name = resolver.session.displayName,
                onSignOut = app.auth::signOut,
            )
        }
    }
}

/** Владелец, сотрудник, платформа — переносятся после оператора. */
@Composable
private fun NotYetOnAndroid(name: String?, onSignOut: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(
            icon = Icons.Filled.Lock,
            title = "Пока только для операторов",
            message = "Android-версия сейчас умеет режим оператора. Кабинет владельца и сотрудников — на сайте и в iPhone-приложении.",
        )
        name?.let { Text("Вы вошли как $it", color = Orda.colors.textDim) }
        TextButton(onClick = onSignOut) { Text("Выйти из аккаунта", color = Orda.colors.negative) }
    }
}

/**
 * После входа: разрешение на уведомления и адрес устройства для push. Спрашиваем
 * здесь, а не на экране входа — до входа непонятно, о чём будут уведомлять.
 */
@Composable
private fun PushRegistration(app: OrdaApplication) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!kz.ordaops.android.platform.ShiftNotification.canPost(context) && android.os.Build.VERSION.SDK_INT >= 33) {
            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        kz.ordaops.android.platform.Push.register(context)
    }
}
