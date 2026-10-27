package kz.ordaops.android.feature.business.ops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.operator.chat.ChatTab
import kz.ordaops.android.feature.operator.chat.MessagesTab
import kz.ordaops.android.feature.operator.common.ActionOutbox
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.feature.operator.common.OperatorEnv

// «Чат команды» и «Личные сообщения» открываются из «Сервисов» тем же экраном, что у
// оператора: переписка одна и та же, различается только тот, кто пишет. Окружение
// оператора собираем из окружения кабинета.

@Composable
private fun WithChatEnv(content: @Composable () -> Unit) {
    val env = LocalBusiness.current
    val context = LocalContext.current.applicationContext
    val chatEnv = remember(env.resolver, env.api) {
        OperatorEnv(api = env.api, outbox = ActionOutbox(context, env.api), resolver = env.resolver, open = env.open, signOut = env.signOut)
    }
    CompositionLocalProvider(LocalOperatorEnv provides chatEnv) { content() }
}

/** Общий чат команды. Свои сообщения сотрудника узнаются по учётке входа, а не по `operatorId`. */
@Composable
fun TeamChatPage() = WithChatEnv { ChatTab() }

/** Личные сообщения: список бесед и переписка. */
@Composable
fun MessagesPage() = WithChatEnv { MessagesTab() }
