package app.rewapp

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import java.util.concurrent.ConcurrentHashMap

/** Responde a una conversación usando la "respuesta rápida" de la notificación de WhatsApp. */
object Replier {
    /** Última acción de respuesta vista para cada conversación (por clave). */
    private val actions = ConcurrentHashMap<String, NotificationCompat.Action>()

    fun remember(key: String, action: NotificationCompat.Action) {
        actions[key] = action
    }

    fun findReplyAction(n: Notification): NotificationCompat.Action? {
        for (i in 0 until NotificationCompat.getActionCount(n)) {
            val a = NotificationCompat.getAction(n, i) ?: continue
            val inputs = a.remoteInputs
            if (inputs != null && inputs.isNotEmpty() && a.actionIntent != null) return a
        }
        return null
    }

    private fun activeFor(key: String): List<android.service.notification.StatusBarNotification> {
        val service = WaListenerService.instance ?: return emptyList()
        val active = runCatching { service.activeNotifications }.getOrNull() ?: return emptyList()
        val settings = Store.settings()
        return active.filter { Keys.allowed(it.packageName, settings) && Keys.of(it) == key }
    }

    private fun findFromActive(key: String): NotificationCompat.Action? {
        for (sbn in activeFor(key)) {
            findReplyAction(sbn.notification)?.let { return it }
        }
        return null
    }

    /**
     * ¿Hay señales de que la conversación siguió sin que la app lo registrara?
     * Mira la notificación del chat (si todavía está): un mensaje de la persona más nuevo que el último
     * procesado, o un mensaje del dueño que no mandó la app.
     */
    fun hasUnseenActivity(key: String, lastProcessed: Long, lastSent: List<String>): Boolean {
        for (sbn in activeFor(key)) {
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification) ?: continue
            for (m in style.messages) {
                val text = m.text?.toString() ?: continue
                if (Keys.isMine(style, m)) {
                    if (!Engine.isSentByUs(text, lastSent)) return true
                } else if (m.timestamp > lastProcessed && !Engine.isSentByUs(text, lastSent)) {
                    return true
                }
            }
        }
        return false
    }

    private fun fire(ctx: Context, action: NotificationCompat.Action, text: String): Boolean {
        val inputs = action.remoteInputs ?: return false
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val bundle = Bundle()
        for (ri in inputs) bundle.putCharSequence(ri.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, intent, bundle)
        val pi = action.actionIntent ?: return false
        pi.send(ctx, 0, intent)
        return true
    }

    /** true si se pudo enviar. */
    fun send(ctx: Context, key: String, text: String): Boolean {
        val cached = actions[key]
        if (cached != null) {
            try {
                if (fire(ctx, cached, text)) return true
            } catch (_: PendingIntent.CanceledException) {
                actions.remove(key)
            }
        }
        val fresh = findFromActive(key) ?: return false
        return try {
            val ok = fire(ctx, fresh, text)
            if (ok) actions[key] = fresh
            ok
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }
}
