package app.rewapp

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class MatchType(val label: String) {
    CONTAINS("Contiene"),
    EXACT("Es exactamente"),
    STARTS_WITH("Empieza con"),
}

/** Una regla: si llega un mensaje que coincide con [trigger], se responde [reply]. */
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val matchType: MatchType = MatchType.CONTAINS,
    /** Opciones separadas por "|" o por salto de línea. Vacío = cualquier mensaje. */
    val trigger: String = "",
    val reply: String = "",
    /** Si no es null, la regla solo aplica cuando la última regla respondida a ese contacto fue esta. */
    val afterRuleId: String? = null,
    val oncePerContact: Boolean = true,
    val followUpEnabled: Boolean = false,
    val followUpDelayMinutes: Int = 60,
    val followUpMessage: String = "",
    val followUpMaxAttempts: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("matchType", matchType.name)
        put("trigger", trigger)
        put("reply", reply)
        put("afterRuleId", afterRuleId ?: JSONObject.NULL)
        put("oncePerContact", oncePerContact)
        put("followUpEnabled", followUpEnabled)
        put("followUpDelayMinutes", followUpDelayMinutes)
        put("followUpMessage", followUpMessage)
        put("followUpMaxAttempts", followUpMaxAttempts)
    }

    companion object {
        fun fromJson(o: JSONObject) = Rule(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name"),
            enabled = o.optBoolean("enabled", true),
            matchType = runCatching { MatchType.valueOf(o.optString("matchType")) }.getOrDefault(MatchType.CONTAINS),
            trigger = o.optString("trigger"),
            reply = o.optString("reply"),
            afterRuleId = if (o.isNull("afterRuleId")) null else o.optString("afterRuleId").ifBlank { null },
            oncePerContact = o.optBoolean("oncePerContact", true),
            followUpEnabled = o.optBoolean("followUpEnabled", false),
            followUpDelayMinutes = o.optInt("followUpDelayMinutes", 60).coerceAtLeast(1),
            followUpMessage = o.optString("followUpMessage"),
            followUpMaxAttempts = o.optInt("followUpMaxAttempts", 1).coerceIn(1, 5),
        )
    }
}

/** Estado de la conversación con un contacto. */
data class ContactState(
    val key: String,
    /** Nombre para mostrar y para {nombre}. */
    val name: String = "",
    val lastRuleId: String? = null,
    val firedRuleIds: Set<String> = emptySet(),
    val lastProcessedMsgTime: Long = 0,
    val lastActivityAt: Long = 0,
    /** Cuándo toca insistir (0 = nada pendiente). */
    val followUpDueAt: Long = 0,
    val followUpRuleId: String? = null,
    val followUpAttempts: Int = 0,
    /** Textos (normalizados) que mandamos nosotros, para ignorar el "eco" en la notificación. */
    val lastSent: List<String> = emptyList(),
) {
    fun withoutFollowUp() = copy(followUpDueAt = 0, followUpRuleId = null, followUpAttempts = 0)

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("name", name)
        put("lastRuleId", lastRuleId ?: JSONObject.NULL)
        put("fired", JSONArray(firedRuleIds.toList()))
        put("lastProcessedMsgTime", lastProcessedMsgTime)
        put("lastActivityAt", lastActivityAt)
        put("followUpDueAt", followUpDueAt)
        put("followUpRuleId", followUpRuleId ?: JSONObject.NULL)
        put("followUpAttempts", followUpAttempts)
        put("lastSent", JSONArray(lastSent))
    }

    companion object {
        fun fromJson(o: JSONObject): ContactState {
            val fired = mutableSetOf<String>()
            o.optJSONArray("fired")?.let { for (i in 0 until it.length()) fired += it.getString(i) }
            val sent = mutableListOf<String>()
            o.optJSONArray("lastSent")?.let { for (i in 0 until it.length()) sent += it.getString(i) }
            return ContactState(
                key = o.getString("key"),
                name = o.optString("name"),
                lastRuleId = if (o.isNull("lastRuleId")) null else o.optString("lastRuleId"),
                firedRuleIds = fired,
                lastProcessedMsgTime = o.optLong("lastProcessedMsgTime"),
                lastActivityAt = o.optLong("lastActivityAt"),
                followUpDueAt = o.optLong("followUpDueAt"),
                followUpRuleId = if (o.isNull("followUpRuleId")) null else o.optString("followUpRuleId"),
                followUpAttempts = o.optInt("followUpAttempts"),
                lastSent = sent,
            )
        }
    }
}

data class Settings(
    /** Interruptor general. Arranca apagado hasta que la persona revise las reglas. */
    val enabled: Boolean = false,
    val replyDelaySec: Int = 3,
    /** También responder en WhatsApp común (no Business). */
    val includeWhatsApp: Boolean = false,
    /** Después de tantos días sin actividad, el contacto se trata como nuevo. */
    val forgetDays: Int = 7,
    /** Registrar también los mensajes ignorados. */
    val debug: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("replyDelaySec", replyDelaySec)
        put("includeWhatsApp", includeWhatsApp)
        put("forgetDays", forgetDays)
        put("debug", debug)
    }

    companion object {
        fun fromJson(raw: String?): Settings {
            if (raw == null) return Settings()
            return runCatching {
                val o = JSONObject(raw)
                Settings(
                    enabled = o.optBoolean("enabled", false),
                    replyDelaySec = o.optInt("replyDelaySec", 3).coerceAtLeast(0),
                    includeWhatsApp = o.optBoolean("includeWhatsApp", false),
                    forgetDays = o.optInt("forgetDays", 7).coerceAtLeast(1),
                    debug = o.optBoolean("debug", false),
                )
            }.getOrDefault(Settings())
        }
    }
}

data class LogEntry(
    val time: Long,
    val contact: String,
    val incoming: String,
    val rule: String,
    val outcome: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("time", time)
        put("contact", contact)
        put("incoming", incoming)
        put("rule", rule)
        put("outcome", outcome)
    }

    companion object {
        fun fromJson(o: JSONObject) = LogEntry(
            o.optLong("time"), o.optString("contact"), o.optString("incoming"),
            o.optString("rule"), o.optString("outcome"),
        )
    }
}
