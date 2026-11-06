package ai.zoya.assistant.data

import android.content.Context
import android.content.SharedPreferences
import ai.zoya.assistant.domain.ZoyaPersona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * ZoyaPrefs
 * ---------
 * Small, synchronous, process-wide settings.
 *
 * SharedPreferences rather than DataStore because the voice path must be able
 * to read a setting inside a websocket callback without suspending, and because
 * there is nothing here that needs async. Values are mirrored into StateFlows
 * so Compose updates without polling.
 */
class ZoyaPrefs(context: Context) {

    companion object {
        private const val FILE = "zoya.prefs"
        private const val KEY_VOICE = "voice"
        private const val KEY_WAKE = "wake_word"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_FACTS = "facts"
        private const val KEY_RESUME = "resume_handle"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_BROWSER = "browser_pkg"
        private const val KEY_TIMERS = "timers"
        private const val KEY_AUTOSTART = "autostart"
    }

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ voice --

    private val _voice = MutableStateFlow(sp.getString(KEY_VOICE, ZoyaPersona.DEFAULT_VOICE)!!)
    val voiceFlow: StateFlow<String> = _voice.asStateFlow()
    var voice: String
        get() = _voice.value
        set(value) {
            _voice.value = value
            sp.edit().putString(KEY_VOICE, value).apply()
        }

    // -------------------------------------------------------------- wake word --

    private val _wakeEnabled = MutableStateFlow(sp.getBoolean(KEY_WAKE, true))
    val wakeFlow: StateFlow<Boolean> = _wakeEnabled.asStateFlow()

    /**
     * Wake word is opt-in but on by default once the mic is granted, because
     * a background assistant nobody can summon is just a battery drain.
     */
    var wakeWordEnabled: Boolean
        get() = _wakeEnabled.value
        set(value) {
            _wakeEnabled.value = value
            sp.edit().putBoolean(KEY_WAKE, value).apply()
        }

    // ------------------------------------------------------------------ user --

    private val _userName = MutableStateFlow(sp.getString(KEY_USER_NAME, "")!!)
    val userNameFlow: StateFlow<String> = _userName.asStateFlow()
    var userName: String
        get() = _userName.value
        set(value) {
            _userName.value = value
            sp.edit().putString(KEY_USER_NAME, value).apply()
        }

    // ----------------------------------------------------------------- memory --

    private val _facts = MutableStateFlow(readFacts())
    val factsFlow: StateFlow<Map<String, String>> = _facts.asStateFlow()

    /** Oldest-last, capped. She remembers the last 40 things, no more. */
    val facts: Map<String, String> get() = _facts.value

    fun rememberFact(key: String, value: String) {
        val current = LinkedHashMap(_facts.value)
        current.remove(key)
        current[key] = value
        val trimmed = if (current.size > 40) {
            current.entries.drop(current.size - 40).associate { it.key to it.value }
        } else {
            current
        }
        _facts.value = trimmed
        sp.edit().putString(KEY_FACTS, serializeFacts(trimmed)).apply()
    }

    fun forgetEverything() {
        _facts.value = emptyMap()
        sp.edit().remove(KEY_FACTS).apply()
    }

    // ------------------------------------------------------------ onboarding --

    var onboarded: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(value) = sp.edit().putBoolean(KEY_ONBOARDED, value).apply()

    var autostartOnBoot: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, false)
        set(value) = sp.edit().putBoolean(KEY_AUTOSTART, value).apply()

    // ------------------------------------------------------------- session ----

    /** Opaque server handle so a reconnect does not lose the conversation. */
    var resumeHandle: String?
        get() = sp.getString(KEY_RESUME, null)
        set(value) = sp.edit().putString(KEY_RESUME, value).apply()

    /** Preferred browser package for openWebsite ("" = system default). */
    var inAppBrowserPackage: String
        get() = sp.getString(KEY_BROWSER, "") ?: ""
        set(value) = sp.edit().putString(KEY_BROWSER, value).apply()

    // ---------------------------------------------------------------- timers --

    data class Timer(val label: String, val firesAt: Long, val seconds: Long)

    private val _timers = MutableStateFlow(readTimers())
    val timersFlow: StateFlow<List<Timer>> = _timers.asStateFlow()
    val timers: List<Timer> get() = _timers.value

    fun addTimer(label: String, firesAt: Long, seconds: Long) {
        // Prefs are the source of truth so a process death does not lose them;
        // the service re-arms alarms from here on start.
        val next = (_timers.value + Timer(label, firesAt, seconds)).takeLast(16)
        _timers.value = next
        sp.edit().putString(KEY_TIMERS, serializeTimers(next)).apply()
    }

    fun removeTimer(firesAt: Long) {
        val next = _timers.value.filterNot { it.firesAt == firesAt }
        _timers.value = next
        sp.edit().putString(KEY_TIMERS, serializeTimers(next)).apply()
    }

    // --------------------------------------------------------------- internal --

    private fun readFacts(): Map<String, String> {
        val raw = sp.getString(KEY_FACTS, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.optString(it) }
        }.getOrDefault(emptyMap())
    }

    private fun serializeFacts(facts: Map<String, String>): String {
        val obj = JSONObject()
        facts.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    private fun readTimers(): List<Timer> {
        val raw = sp.getString(KEY_TIMERS, null) ?: return emptyList()
        val now = System.currentTimeMillis()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val firesAt = o.optLong("firesAt")
                if (firesAt < now - 60_000) return@mapNotNull null
                Timer(o.optString("label"), firesAt, o.optLong("seconds"))
            }
        }.getOrDefault(emptyList())
    }

    private fun serializeTimers(timers: List<Timer>): String {
        val arr = JSONArray()
        timers.forEach { t ->
            arr.put(JSONObject().put("label", t.label).put("firesAt", t.firesAt).put("seconds", t.seconds))
        }
        return arr.toString()
    }
}
