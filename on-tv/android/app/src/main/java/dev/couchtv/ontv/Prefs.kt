package dev.couchtv.ontv

import android.content.Context
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * The home screen's own settings (its Settings page saves them as "couch:prefs"), read here for
 * what Android does itself: the screensaver and the two remote buttons.
 */
object Prefs {
    private lateinit var ctx: Context
    private val ALL_CATS = setOf("Landscapes", "Cities", "Space", "Underwater")
    const val DREAM = "dev.couchtv.ontv/.AerialDream"

    fun init(c: Context) { ctx = c.applicationContext }

    fun json(): JSONObject = ctx.getSharedPreferences("page", Context.MODE_PRIVATE).getString("couch:prefs", null)
        ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    fun aerialCats(): Set<String> = json().optJSONArray("aerialCats")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
        ?.takeIf { it.isNotEmpty() } ?: ALL_CATS

    /** Words that pick live sport out of the guide (Settings, Live Sport). */
    fun sportWords(): List<String> = json().optJSONArray("sportWords")?.let { a -> (0 until a.length()).map { a.optString(it) } }
        ?: listOf("Formula 1", "F1", "Osijek")

    fun sportOn(): Boolean = json().optBoolean("sport", true)
    fun tvNowOn(): Boolean = json().optBoolean("tvnow", true)

    fun button(which: String): String = json().optString(if (which == "philips") "philipsButton" else "rakutenButton")
        .ifEmpty { if (which == "philips") "com.stremio.one" else "com.spotify.tv.android" }

    /** Called when the page saves its settings. */
    fun changed() {
        Aerials.changed()
        applyScreensaver()
    }

    /** Our aerials as the TV's screensaver (or none), and how long the TV waits before starting it. */
    fun applyScreensaver() {
        val p = json()
        val on = p.optBoolean("screensaver", true)
        val minutes = p.optInt("screensaverAfter", 10)
        try {
            val r = ctx.contentResolver
            Settings.Secure.putInt(r, "screensaver_enabled", if (on) 1 else 0)
            if (on) {
                Settings.Secure.putString(r, "screensaver_components", DREAM)
                Settings.Secure.putString(r, "screensaver_default_component", DREAM)
            }
        } catch (e: SecurityException) { Log.w("couchtv", "screensaver: needs the secure-settings permission (Connect ADB once)") }
        // The wait is a system setting; ADB can set it when connected.
        if (on && Adb.connected) runCatching { Adb.shell("settings put system screen_off_timeout ${minutes * 60_000}") }
    }
}
