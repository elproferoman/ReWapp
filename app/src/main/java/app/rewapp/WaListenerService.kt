package app.rewapp

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

class WaListenerService : NotificationListenerService() {

    companion object {
        const val PKG_BUSINESS = "com.whatsapp.w4b"
        const val PKG_NORMAL = "com.whatsapp"

        @Volatile
        var instance: WaListenerService? = null
    }

    private data class Incoming(val text: String, val time: Long)

    private val handler = Handler(Looper.getMainLooper())

    override fun onListenerConnected() {
        instance = this
        FollowUps.schedule(applicationContext)
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (e: Exception) {
            Store.log(LogEntry(System.currentTimeMillis(), "-", "-", "-", "Error: ${e.message}"))
        }
    }

    private fun debugLog(settings: Settings, contact: String, text: String, outcome: String) {
        if (settings.debug) Store.log(LogEntry(System.currentTimeMillis(), contact, text, "-", outcome))
    }

    private fun handle(sbn: StatusBarNotification) {
        val settings = Store.settings()
        if (!settings.enabled) return
        val pkg = sbn.packageName
        if (pkg != PKG_BUSINESS && !(settings.includeWhatsApp && pkg == PKG_NORMAL)) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val action = Replier.findReplyAction(n) ?: return

        val extras = n.extras
        val contact = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if (contact.isEmpty()) return

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null) {
            val senders = style.messages.mapNotNull { it.person?.name?.toString() }.toSet()
            if (style.isGroupConversation || senders.size > 1) {
                debugLog(settings, contact, "", "Ignorado: es un grupo")
                return
            }
        }

        // Siempre guardamos la acción más reciente: sirve para insistir más tarde.
        Replier.remember(contact, action)

        val incoming: List<Incoming> = if (style != null && style.messages.isNotEmpty()) {
            val userKey = style.user.key
            val userName = style.user.name?.toString()
            style.messages.filter { m ->
                val p = m.person
                val isMe = p != null &&
                    ((userKey != null && p.key == userKey) ||
                        (!userName.isNullOrEmpty() && p.name?.toString() == userName))
                !isMe
            }.mapNotNull { m -> m.text?.toString()?.let { Incoming(it, m.timestamp) } }
        } else {
            listOfNotNull(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { Incoming(it, sbn.postTime) })
        }

        val now = System.currentTimeMillis()
        val state0 = Engine.freshState(Store.state(contact), contact, settings, now)
        val last = incoming.filter { it.time > state0.lastProcessedMsgTime }.maxByOrNull { it.time } ?: return

        // Si es el eco de lo que mandamos nosotros, solo lo marcamos como visto.
        if (Engine.normalize(last.text) in state0.lastSent) {
            Store.putState(state0.copy(lastProcessedMsgTime = last.time))
            debugLog(settings, contact, last.text, "Ignorado: eco de un mensaje enviado por la app")
            return
        }
        process(contact, last, state0, settings)
    }

    private fun process(contact: String, msg: Incoming, state0: ContactState, settings: Settings) {
        val now = System.currentTimeMillis()
        // Cualquier mensaje de la persona cancela la insistencia pendiente.
        var st = state0.withoutFollowUp().copy(lastProcessedMsgTime = msg.time, lastActivityAt = now)

        val rule = Engine.pick(Store.rules(), st, msg.text)
        if (rule == null) {
            Store.putState(st)
            Store.log(LogEntry(now, contact, msg.text, "-", "Sin regla que coincida"))
            return
        }

        val reply = Engine.fill(rule.reply, contact)
        st = st.copy(
            lastRuleId = rule.id,
            firedRuleIds = st.firedRuleIds + rule.id,
            lastSent = (st.lastSent + Engine.normalize(reply)).takeLast(6),
        )
        if (rule.followUpEnabled && rule.followUpMessage.isNotBlank()) {
            st = st.copy(
                followUpDueAt = now + settings.replyDelaySec * 1000L + rule.followUpDelayMinutes * 60_000L,
                followUpRuleId = rule.id,
                followUpAttempts = 0,
            )
        }
        Store.putState(st)

        val ctx = applicationContext
        handler.postDelayed({
            val ok = Replier.send(ctx, contact, reply)
            Store.log(
                LogEntry(
                    System.currentTimeMillis(), contact, msg.text, rule.name,
                    if (ok) "Respondido" else "No se pudo responder (no hay respuesta rápida disponible)",
                ),
            )
        }, settings.replyDelaySec * 1000L)
        FollowUps.schedule(ctx)
    }
}
