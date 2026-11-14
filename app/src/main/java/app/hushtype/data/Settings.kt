package app.hushtype.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which model polishes transcripts. */
enum class CleanupEngine(val displayName: String, val serviceName: String) {
    /** Gemini Nano on the phone: free, private and offline, on phones that have it. */
    ON_DEVICE("On-device", "Gemini Nano"),
    /** Claude Haiku over the Anthropic API, with the user's own key. */
    CLAUDE("Claude", "Claude"),
    /** Type exactly what the speech recognizer heard. */
    OFF("Off", "Nothing"),
}

/** When the floating bubble shows. */
enum class BubbleMode { WHEN_TYPING, ALWAYS }

/** Small app preferences. The dictionary and snippets live in [VocabularyRepository], not here. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    /** Bumps on every change, so Compose screens can recompose. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _changes.value++ }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    var onboardingDone by bool("onboardingDone", false)
    var engineChosen by bool("engineChosen", false)
    var engine: CleanupEngine
        get() = runCatching { CleanupEngine.valueOf(prefs.getString("engine", null)!!) }.getOrDefault(CleanupEngine.ON_DEVICE)
        set(value) = prefs.edit().putString("engine", value.name).putBoolean("engineChosen", true).apply()

    var bubbleMode: BubbleMode
        get() = runCatching { BubbleMode.valueOf(prefs.getString("bubbleMode", null)!!) }.getOrDefault(BubbleMode.WHEN_TYPING)
        set(value) = prefs.edit().putString("bubbleMode", value.name).apply()

    /** 0 = left edge, 1 = right edge. */
    var bubbleRight by bool("bubbleRight", true)
    /** Vertical position as a fraction of the screen height. */
    var bubbleY: Float
        get() = prefs.getFloat("bubbleY", 0.42f)
        set(value) = prefs.edit().putFloat("bubbleY", value).apply()
    var bubbleSize: Float
        get() = prefs.getFloat("bubbleSize", 1f)
        set(value) = prefs.edit().putFloat("bubbleSize", value).apply()

    var haptics by bool("haptics", true)
    var sounds by bool("sounds", false)
    var learnFromCorrections by bool("learn", true)
    var keepHistory by bool("keepHistory", true)
    var useNearbyText by bool("context", true)
    var preferOffline by bool("preferOffline", true)
    /** BCP-47 tag, or empty for the phone's language. */
    var language: String
        get() = prefs.getString("language", "") ?: ""
        set(value) = prefs.edit().putString("language", value).apply()

    /** Bubble hidden for now from its long-press menu; comes back on the next field. */
    var bubbleSnoozedUntil: Long
        get() = prefs.getLong("snoozedUntil", 0)
        set(value) = prefs.edit().putLong("snoozedUntil", value).apply()

    private fun bool(key: String, default: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) =
            prefs.edit().putBoolean(key, value).apply()
    }
}
