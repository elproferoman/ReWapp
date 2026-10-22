package app.rewapp

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Persistencia simple en SharedPreferences (JSON). Todo pasa por un único candado. */
object Store {
    private var prefs: SharedPreferences? = null
    private val lock = Any()
    private const val MAX_LOG = 200

    fun init(ctx: Context) {
        if (prefs == null) {
            prefs = ctx.applicationContext.getSharedPreferences("rewapp", Context.MODE_PRIVATE)
            // v2: cambió la clave con la que se identifica a cada contacto; el historial viejo ya no sirve.
            if (prefs!!.getInt("schema", 1) < 2) {
                prefs!!.edit().remove("states").putInt("schema", 2).apply()
            }
        }
    }

    private fun p(): SharedPreferences = prefs ?: error("Store no inicializado")

    fun <T> locked(block: () -> T): T = synchronized(lock) { block() }

    // ---- Ajustes ----
    fun settings(): Settings = locked { Settings.fromJson(p().getString("settings", null)) }

    fun saveSettings(s: Settings) = locked {
        p().edit().putString("settings", s.toJson().toString()).apply()
    }

    // ---- Reglas ----
    fun rules(): List<Rule> = locked {
        val raw = p().getString("rules", null)
        if (raw == null) {
            val seed = Seed.rules()
            saveRules(seed)
            seed
        } else {
            parseRules(raw)
        }
    }

    fun saveRules(rules: List<Rule>) = locked {
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        p().edit().putString("rules", arr.toString()).apply()
    }

    private fun parseRules(raw: String): List<Rule> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { Rule.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun exportRules(): String {
        val arr = JSONArray()
        rules().forEach { arr.put(it.toJson()) }
        return arr.toString(2)
    }

    /** Devuelve la cantidad de reglas importadas, o -1 si el archivo no es válido. */
    fun importRules(raw: String): Int = locked {
        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Rule.fromJson(arr.getJSONObject(it)) }
        }.getOrNull() ?: return@locked -1
        saveRules(parsed)
        parsed.size
    }

    // ---- Estado por contacto ----
    private fun readStates(): MutableMap<String, ContactState> {
        val raw = p().getString("states", null) ?: return mutableMapOf()
        return runCatching {
            val arr = JSONArray(raw)
            val map = mutableMapOf<String, ContactState>()
            for (i in 0 until arr.length()) {
                val s = ContactState.fromJson(arr.getJSONObject(i))
                map[s.key] = s
            }
            map
        }.getOrDefault(mutableMapOf())
    }

    private fun writeStates(map: Map<String, ContactState>) {
        val arr = JSONArray()
        map.values.forEach { arr.put(it.toJson()) }
        p().edit().putString("states", arr.toString()).apply()
    }

    fun state(key: String): ContactState? = locked { readStates()[key] }

    fun allStates(): List<ContactState> = locked { readStates().values.toList() }

    fun putState(s: ContactState) = locked {
        val map = readStates()
        map[s.key] = s
        writeStates(map)
    }

    /** Lee, transforma y guarda el estado de un contacto en una sola operación. */
    fun updateState(key: String, block: (ContactState?) -> ContactState?) = locked {
        val map = readStates()
        val next = block(map[key])
        if (next == null) map.remove(key) else map[key] = next
        writeStates(map)
    }

    fun clearStates() = locked { p().edit().remove("states").apply() }

    // ---- Registro de actividad ----
    fun log(e: LogEntry) = locked {
        val current = logs().toMutableList()
        current.add(0, e)
        val arr = JSONArray()
        current.take(MAX_LOG).forEach { arr.put(it.toJson()) }
        p().edit().putString("log", arr.toString()).apply()
    }

    fun logs(): List<LogEntry> = locked {
        val raw = p().getString("log", null) ?: return@locked emptyList()
        runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { LogEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun clearLog() = locked { p().edit().remove("log").apply() }
}

/** Reglas de ejemplo para arrancar (el escáner corporal). */
object Seed {
    const val START = "seed_inicio"
    const val OPT1 = "seed_opcion1"
    const val OPT2 = "seed_opcion2"
    const val OPT3 = "seed_opcion3"

    fun rules(): List<Rule> = listOf(
        Rule(
            id = START,
            name = "Anuncio escáner – bienvenida",
            trigger = "escáner|escaner",
            reply = "¡Hola {nombre}! 👋 Gracias por escribirnos por el escáner corporal.\n\n" +
                "Elegí una opción respondiendo con el número:\n" +
                "1️⃣ Agendar mi turno\n" +
                "2️⃣ Precio y qué incluye\n" +
                "3️⃣ Dirección y horarios",
            followUpEnabled = true,
            followUpDelayMinutes = 60,
            followUpMessage = "¡Hola {nombre}! ¿Pudiste ver las opciones? Respondé 1, 2 o 3 y te ayudo enseguida 🙂",
        ),
        Rule(
            id = OPT1,
            name = "Opción 1 – Agendar",
            matchType = MatchType.EXACT,
            trigger = "1",
            afterRuleId = START,
            reply = "Perfecto 🙌 Para agendar tu turno decime qué día y en qué horario te queda mejor.",
            followUpEnabled = true,
            followUpDelayMinutes = 120,
            followUpMessage = "¿Seguís interesado en tu turno? Decime día y horario y te lo reservo 😊",
        ),
        Rule(
            id = OPT2,
            name = "Opción 2 – Precio",
            matchType = MatchType.EXACT,
            trigger = "2",
            afterRuleId = START,
            reply = "[COMPLETAR: precio y qué incluye el escáner corporal]",
        ),
        Rule(
            id = OPT3,
            name = "Opción 3 – Dirección y horarios",
            matchType = MatchType.EXACT,
            trigger = "3",
            afterRuleId = START,
            reply = "[COMPLETAR: dirección y horarios]",
        ),
    )
}
