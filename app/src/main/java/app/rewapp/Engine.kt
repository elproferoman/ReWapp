package app.rewapp

import java.text.Normalizer

/** Lógica pura: normalizar texto, elegir regla, completar plantillas. */
object Engine {
    private val combiningMarks = Regex("\\p{Mn}+")
    private val spaces = Regex("\\s+")
    private val edgePunct = Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$")

    /** Minúsculas, sin tildes, sin emojis-teclado ("1️⃣" -> "1"), sin puntuación en los bordes. */
    fun normalize(s: String): String {
        val noKeycap = s.replace("⃣", "").replace("️", "")
        val noAccents = Normalizer.normalize(noKeycap, Normalizer.Form.NFD).replace(combiningMarks, "")
        return noAccents.lowercase().replace(spaces, " ").trim().replace(edgePunct, "").trim()
    }

    fun triggers(rule: Rule): List<String> =
        rule.trigger.split("|", "\n").map { normalize(it) }.filter { it.isNotEmpty() }

    fun matches(rule: Rule, message: String): Boolean {
        val msg = normalize(message)
        val ts = triggers(rule)
        if (ts.isEmpty()) return true
        return ts.any {
            when (rule.matchType) {
                MatchType.CONTAINS -> msg.contains(it)
                MatchType.EXACT -> msg == it
                MatchType.STARTS_WITH -> msg.startsWith(it)
            }
        }
    }

    /**
     * Primero se prueban las reglas "de seguimiento" (las que dependen de otra), que son más específicas,
     * y después las de inicio, en el orden de la lista.
     */
    fun pick(rules: List<Rule>, state: ContactState, message: String): Rule? {
        val active = rules.filter { it.enabled }
        val ordered = active.filter { it.afterRuleId != null } + active.filter { it.afterRuleId == null }
        for (r in ordered) {
            if (r.afterRuleId != null && state.lastRuleId != r.afterRuleId) continue
            if (r.oncePerContact && r.id in state.firedRuleIds) continue
            if (matches(r, message)) return r
        }
        return null
    }

    /** Estado del contacto, o uno nuevo si no existe o pasó el tiempo de olvido. */
    fun freshState(existing: ContactState?, key: String, settings: Settings, now: Long): ContactState {
        if (existing == null) return ContactState(key = key)
        val forgetMs = settings.forgetDays * 24L * 60 * 60 * 1000
        val expired = existing.lastActivityAt > 0 && now - existing.lastActivityAt > forgetMs
        return if (expired) ContactState(key = key) else existing
    }

    fun firstName(contact: String): String {
        val c = contact.trim()
        if (c.isEmpty() || c.first() == '+' || c.first().isDigit()) return ""
        return c.split(" ").first()
    }

    fun fill(text: String, contact: String): String {
        val name = firstName(contact)
        return if (name.isBlank()) text.replace(" {nombre}", "").replace("{nombre}", "")
        else text.replace("{nombre}", name)
    }
}
