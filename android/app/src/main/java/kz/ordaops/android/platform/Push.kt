package kz.ordaops.android.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.OrdaApplication
import kz.ordaops.android.R
import kz.ordaops.android.core.net.ApiRequest

/**
 * Push через Firebase. Сервер шлёт data-сообщения (lib/server/fcm.ts), а
 * уведомление рисуем сами: так у личного сообщения появляется «Ответить» прямо
 * в шторке, а нажатие ведёт в нужный раздел.
 */
object Push {
    private const val CHANNEL_MESSAGES = "messages"
    private const val CHANNEL_WORK = "work"
    const val KEY_REPLY = "reply"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Отдать токен устройства сайту — после входа и когда Firebase его сменит. */
    fun register(context: Context, token: String? = null) {
        val app = context.applicationContext as OrdaApplication
        if (token == null) {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { register(context, it) }
            return
        }
        scope.launch {
            runCatching {
                val value = token
                // Без входа сайт токен не примет — отправим после входа.
                if (app.auth.currentAccessToken() == null) return@runCatching
                app.api.sendUnit(
                    ApiRequest.json(
                        "/api/mobile/register-push",
                        buildJsonObject {
                            put("token", value)
                            put("platform", "android")
                        },
                    ),
                )
            }
        }
    }

    fun show(context: Context, data: Map<String, String>) {
        if (!ShiftNotification.canPost(context)) return
        ensureChannels(context)
        val kind = data["kind"].orEmpty()
        val title = data["title"].orEmpty()
        val body = data["body"].orEmpty()
        // Куда вести по нажатию — имя раздела оператора.
        val section = when {
            kind == "direct-message" -> "Messages"
            kind.startsWith("team-chat") -> "Chat"
            kind.startsWith("task") -> "Tasks"
            kind.contains("shift") || kind.contains("schedule") -> "Schedule"
            kind.contains("knowledge") || kind.contains("exam") -> "Knowledge"
            else -> "Shift"
        }
        val isMessage = kind == "direct-message" || kind.startsWith("team-chat")
        // Сообщения от одного человека — одно уведомление (как collapseId на айфоне).
        val id = (data["from"] ?: kind.ifEmpty { title }).hashCode()
        val open = PendingIntent.getActivity(
            context, id, DeepLinks.intent(context, section),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, if (isMessage) CHANNEL_MESSAGES else CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_stat_orda)
            .setColor(0xFF2563EB.toInt())
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(if (isMessage) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)

        if (isMessage) {
            // Вид «разговора»: Android выносит такие уведомления в отдельный блок наверху шторки.
            val sender = Person.Builder().setName(title.ifEmpty { "Сообщение" }).build()
            builder.setStyle(
                NotificationCompat.MessagingStyle(Person.Builder().setName("Я").build())
                    .addMessage(body, System.currentTimeMillis(), sender),
            ).setCategory(NotificationCompat.CATEGORY_MESSAGE)
        } else {
            builder.setContentTitle(title).setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        val from = data["from"]
        if (kind == "direct-message" && !from.isNullOrEmpty()) {
            val reply = PendingIntent.getBroadcast(
                context, id,
                Intent(context, ReplyReceiver::class.java)
                    .putExtra(ReplyReceiver.EXTRA_TO, from)
                    .putExtra(ReplyReceiver.EXTRA_ID, id)
                    .putExtra(ReplyReceiver.EXTRA_TITLE, title),
                // Изменяемый: система кладёт в него введённый текст.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            builder.addAction(
                NotificationCompat.Action.Builder(0, "Ответить", reply)
                    .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Ответ").build())
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setAllowGeneratedReplies(true)
                    .build(),
            )
        }
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_MESSAGES) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Личные сообщения и упоминания в командном чате"
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_WORK) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_WORK, "Работа", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Задачи, график, экзамены, база знаний"
                },
            )
        }
    }
}

class OrdaMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Push.register(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data.toMutableMap()
        // На случай, если сервер пришлёт notification-часть: возьмём текст оттуда.
        message.notification?.let { n ->
            n.title?.let { data.putIfAbsent("title", it) }
            n.body?.let { data.putIfAbsent("body", it) }
        }
        Push.show(this, data)
    }
}

/** «Ответить» из шторки: отправляем текст и меняем уведомление на «Отправлено». */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Push.KEY_REPLY)?.toString()?.trim()
        val to = intent.getStringExtra(EXTRA_TO)
        val id = intent.getIntExtra(EXTRA_ID, 0)
        if (text.isNullOrEmpty() || to.isNullOrEmpty()) return
        val app = context.applicationContext as OrdaApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val ok = runCatching {
                app.api.sendUnit(
                    ApiRequest.json(
                        "/api/direct-messages",
                        buildJsonObject {
                            put("recipientUserId", to)
                            put("message", text)
                        },
                    ),
                )
            }.isSuccess
            val done = NotificationCompat.Builder(context, "messages")
                .setSmallIcon(R.drawable.ic_stat_orda)
                .setContentTitle(intent.getStringExtra(EXTRA_TITLE).orEmpty())
                .setContentText(if (ok) "Вы: $text" else "Не отправлено — откройте приложение")
                .setTimeoutAfter(if (ok) 4_000 else 0)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(id, done) }
            pending.finish()
        }
    }

    companion object {
        const val EXTRA_TO = "to"
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
    }
}
