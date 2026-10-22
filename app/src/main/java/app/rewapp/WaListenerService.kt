package app.rewapp

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

class WaListenerService : NotificationListenerService() {

    companion object {
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

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        // Solo para diagnóstico: ayuda a entender cuándo WhatsApp cierra la notificación de un chat.
        try {
            val settings = Store.settings()
            if (!settings.debug || !Keys.allowed(sbn.packageName, settings)) return
            if (Replier.findReplyAction(sbn.notification) == null) return
            val title = Keys.cleanTitle(sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
            Store.log(LogEntry(System.currentTimeMillis(), title, "", "-", "Diag: notificación cerrada (motivo $reason) [${Keys.of(sbn)}]"))
        } catch (_: Exception) {
        }
    }

    private fun debugLog(settings: Settings, contact: String, text: String, outcome: String) {
        if (settings.debug) Store.log(LogEntry(System.currentTimeMillis(), contact, text, "-", outcome))
    }

    private fun handle(sbn: StatusBarNotification) {
        val settings = Store.settings()
        if (!settings.enabled) return
        if (!Keys.allowed(sbn.packageName, settings)) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val action = Replier.findReplyAction(n) ?: return

        val extras = n.extras
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val name = Keys.cleanTitle(rawTitle)
        if (name.isEmpty()) return
        val key = Keys.of(sbn)

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null) {
            // Notificación que WhatsApp arma después de nuestra propia respuesta: el título pasa a ser "Tú".
            val me = style.user.name?.toString()
            if (name == me || name == "Tú" || name == "You") {
                Replier.remember(key, action)
                debugLog(settings, name, "", "Ignorado: notificación de un mensaje propio [$key]")
                return
            }
            // Solo cuentan los remitentes que no somos nosotros (si no, "Tú" + contacto parecería un grupo).
            val senders = style.messages.filter { !Keys.isMine(style, it) }
                .mapNotNull { it.person?.name?.toString() }.toSet()
            if (style.isGroupConversation || senders.size > 1) {
                debugLog(settings, name, "", "Ignorado: es un grupo [$key]")
                return
            }
        }

        // Siempre guardamos la acción más reciente: sirve para insistir más tarde.
        Replier.remember(key, action)

        val incoming: List<Incoming>
        val mine: List<String>
        if (style != null && style.messages.isNotEmpty()) {
            val (mineMsgs, theirs) = style.messages.partition { Keys.isMine(style, it) }
            incoming = theirs.mapNotNull { m -> m.text?.toString()?.let { Incoming(it, m.timestamp) } }
            mine = mineMsgs.mapNotNull { it.text?.toString() }
        } else {
            incoming = listOfNotNull(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { Incoming(it, sbn.postTime) })
            mine = emptyList()
        }

        // Leer, decidir y guardar el estado es una sola operación, para no pisarse con el worker.
        Store.locked {
            val now = System.currentTimeMillis()
            val state0 = Engine.freshState(Store.state(key), key, settings, now).copy(name = name)

            // Si el dueño escribió algo que no mandó la app, la conversación sigue en manos humanas.
            val ownerWrote = mine.any { !Engine.isSentByUs(it, state0.lastSent) }
            if (ownerWrote && state0.followUpDueAt > 0) {
                Store.putState(state0.withoutFollowUp())
                Store.log(LogEntry(now, name, "", "-", "Insistencia cancelada: respondiste a mano"))
                FollowUps.schedule(applicationContext)
                return@locked
            }

            val last = incoming.filter { it.time > state0.lastProcessedMsgTime }.maxByOrNull { it.time }
            if (last == null) {
                Store.putState(state0)
                return@locked
            }

            // Si es el eco de lo que mandamos nosotros, solo lo marcamos como visto.
            if (Engine.isSentByUs(last.text, state0.lastSent)) {
                Store.putState(state0.copy(lastProcessedMsgTime = last.time))
                debugLog(settings, name, last.text, "Ignorado: eco de un mensaje enviado por la app")
                return@locked
            }
            process(key, name, last, state0, settings, now)
        }
    }

    private fun process(key: String, name: String, msg: Incoming, state0: ContactState, settings: Settings, now: Long) {
        // Cualquier mensaje de la persona cancela la insistencia pendiente.
        var st = state0.withoutFollowUp().copy(lastProcessedMsgTime = msg.time, lastActivityAt = now)

        // Una reacción (👍, ❤️...) es señal de que la persona está activa, pero no dispara reglas.
        if (Engine.isReaction(msg.text)) {
            Store.putState(st)
            Store.log(LogEntry(now, name, msg.text, "-", "Ignorado: es una reacción"))
            FollowUps.schedule(applicationContext)
            return
        }

        val rule = Engine.pick(Store.rules(), st, msg.text, name)
        if (rule == null) {
            Store.putState(st)
            Store.log(LogEntry(now, name, msg.text, "-", "Sin regla que coincida" + if (settings.debug) " [$key]" else ""))
            FollowUps.schedule(applicationContext)
            return
        }

        val reply = Engine.fill(rule.reply, name)
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
            val ok = Replier.send(ctx, key, reply)
            Store.log(
                LogEntry(
                    System.currentTimeMillis(), name, msg.text, rule.name,
                    (if (ok) "Respondido" else "No se pudo responder (no hay respuesta rápida disponible)") +
                        if (settings.debug) " [$key]" else "",
                ),
            )
        }, settings.replyDelaySec * 1000L)
        FollowUps.schedule(ctx)
    }
}
