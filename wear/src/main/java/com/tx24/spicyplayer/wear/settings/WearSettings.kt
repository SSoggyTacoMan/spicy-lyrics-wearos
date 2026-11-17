package com.tx24.spicyplayer.wear.settings

import android.content.Context
import android.content.SharedPreferences

/** How the watch shows the lyrics, kept on the watch: they're the watch's own screen, not the phone's. */
internal enum class TextSize(val label: String, val scale: Float) {
    Small("Small", 0.86f),
    Medium("Medium", 1f),
    Large("Large", 1.16f),
    Huge("Huge", 1.32f),
}

internal enum class Alignment(val label: String) {
    Center("Centered"),
    Start("Left"),
}

internal enum class BackgroundStyle(val label: String) {
    Song("The song's colours"),
    Cover("Blurred cover"),
    Black("Black"),
}

internal enum class EffectLevel(val label: String) {
    Full("Full"),
    Lite("Lite"),
    None("None"),
}

/**
 * The watch's own display settings, in the watch's SharedPreferences so they survive the phone
 * being away. Main thread; reads are cheap enough for the frame path.
 */
internal class WearSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("wear_settings", 0)

    var textSize by enumPref("text_size", TextSize.Medium)
    var alignment by enumPref("alignment", Alignment.Center)
    var background by enumPref("background", BackgroundStyle.Song)
    var effects by enumPref("effects", EffectLevel.Full)
    var romanization by boolPref("romanization", true)
    var translation by boolPref("translation", true)
    var backgroundVocals by boolPref("background_vocals", true)
    var keepScreenOn by boolPref("keep_screen_on", true)
    var tapToSeek by boolPref("tap_to_seek", true)
    var haptics by boolPref("haptics", false)
    /** The watch's own lyric delay, on top of the phone's: positive shows the lyrics earlier. */
    var delayMs by intPref("delay_ms", 0)
    /** The background drifts slowly. Off is still, for battery. */
    var motion by boolPref("motion", true)

    private fun boolPref(key: String, fallback: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getBoolean(key, fallback)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
    }

    private fun intPref(key: String, fallback: Int) = object : kotlin.properties.ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getInt(key, fallback)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) {
            prefs.edit().putInt(key, value).apply()
        }
    }

    private inline fun <reified T : Enum<T>> enumPref(key: String, fallback: T) = object : kotlin.properties.ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T {
            val name = prefs.getString(key, null) ?: return fallback
            return runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
        }

        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
            prefs.edit().putString(key, value.name).apply()
        }
    }
}
