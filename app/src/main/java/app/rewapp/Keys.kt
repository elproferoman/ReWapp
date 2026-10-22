package app.rewapp

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/** Identificación estable de la conversación y utilidades sobre las notificaciones de WhatsApp. */
object Keys {
    const val PKG_BUSINESS = "com.whatsapp.w4b"
    const val PKG_NORMAL = "com.whatsapp"

    // WhatsApp a veces agrega "(3 mensajes)" al título cuando hay varios mensajes juntos.
    private val countSuffix = Regex("\\s*\\(\\d+[^)]*\\)\\s*$")

    fun cleanTitle(title: String): String = title.replace(countSuffix, "").trim()

    fun allowed(pkg: String, settings: Settings): Boolean =
        pkg == PKG_BUSINESS || (settings.includeWhatsApp && pkg == PKG_NORMAL)

    /**
     * Clave de la conversación. Se prefiere el identificador interno del chat (no cambia),
     * y solo si no existe se usa el título limpio.
     */
    fun of(sbn: StatusBarNotification): String {
        val n = sbn.notification
        val shortcut = n.shortcutId
        if (!shortcut.isNullOrBlank()) return "sc:$shortcut"
        val tag = sbn.tag
        if (!tag.isNullOrBlank()) return "tag:$tag"
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        return "t:" + cleanTitle(title)
    }

    /** true si el mensaje lo escribió el dueño del teléfono (no el contacto). */
    fun isMine(style: NotificationCompat.MessagingStyle, m: NotificationCompat.MessagingStyle.Message): Boolean {
        val p = m.person ?: return false
        val userKey = style.user.key
        val userName = style.user.name?.toString()
        return (userKey != null && p.key == userKey) ||
            (!userName.isNullOrEmpty() && p.name?.toString() == userName)
    }
}
