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
    /** Última acción de respuesta vista para cada contacto. */
    private val actions = ConcurrentHashMap<String, NotificationCompat.Action>()

    fun remember(contact: String, action: NotificationCompat.Action) {
        actions[contact] = action
    }

    fun findReplyAction(n: Notification): NotificationCompat.Action? {
        for (i in 0 until NotificationCompat.getActionCount(n)) {
            val a = NotificationCompat.getAction(n, i) ?: continue
            val inputs = a.remoteInputs
            if (inputs != null && inputs.isNotEmpty() && a.actionIntent != null) return a
        }
        return null
    }

    private fun findFromActive(contact: String): NotificationCompat.Action? {
        val service = WaListenerService.instance ?: return null
        val active = runCatching { service.activeNotifications }.getOrNull() ?: return null
        val settings = Store.settings()
        for (sbn in active) {
            val pkg = sbn.packageName
            if (pkg != WaListenerService.PKG_BUSINESS && !(settings.includeWhatsApp && pkg == WaListenerService.PKG_NORMAL)) continue
            val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            if (title != contact) continue
            findReplyAction(sbn.notification)?.let { return it }
        }
        return null
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
    fun send(ctx: Context, contact: String, text: String): Boolean {
        val cached = actions[contact]
        if (cached != null) {
            try {
                if (fire(ctx, cached, text)) return true
            } catch (_: PendingIntent.CanceledException) {
                actions.remove(contact)
            }
        }
        val fresh = findFromActive(contact) ?: return false
        return try {
            val ok = fire(ctx, fresh, text)
            if (ok) actions[contact] = fresh
            ok
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }
}
