package dev.couchtv.ontv

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What is on the TV now, in the same shape as the computer bridge's GET /api/now, and the push
 * to everyone listening (server-sent events and WebSockets).
 */
object Now {
    @Volatile var who: String? = null
    @Volatile var playedItem: JSONObject? = null          // the last thing HomeTV opened (a show, a Continue Watching card)
    @Volatile var sleepEnds: Long? = null

    /**
     * A card opened from the home screen (Continue Watching, an app's row): the app it opened in will most
     * likely play it, which is all there is to go on when the app names nothing (Netflix).
     */
    fun opened(card: JSONObject, pkg: String) {
        val title = card.optString("title").trim()
        if (title.isEmpty() || pkg.isEmpty()) return
        playedItem = JSONObject().put("title", title).put("episode", card.optString("episode")).put("episode_title", card.optString("episode_title"))
            .put("art", card.optString("bg").takeIf { it.startsWith("/watchnext/art/") || it.startsWith("/approws/art/") } ?: JSONObject.NULL)
            .put("package", pkg).put("started", iso()).put("at", System.currentTimeMillis())
    }
    @Volatile var front: String? = null
        private set
    @Volatile private var media: JSONObject? = null
    @Volatile private var lastSig = ""

    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val loop = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var started = false

    /** A phone joined: read the TV at once and send it what is on, even if nothing changed since the last phone left. */
    fun listen(fn: (String) -> Unit): Boolean { val added = listeners.add(fn); used(); if (added && started) loop.execute { lastSig = ""; tick() }; return added }
    fun unlisten(fn: (String) -> Unit) = listeners.remove(fn)

    fun iso(t: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(t))

    fun payload(): JSONObject {
        val p = media
        return JSONObject()
            .put("ok", true)
            .put("tv_connected", true)                      // the server is on the TV: if it answers, the TV is there
            .put("power", if (Tv.isOn) "on" else "off")
            .put("app", Apps.describe(front)?.let { JSONObject(it) } ?: JSONObject.NULL)
            .put("volume", JSONObject(Tv.volume()))
            .put("playing", p ?: JSONObject.NULL)
            .put("sleep", JSONObject().put("ends", sleepEnds?.let { iso(it) } ?: JSONObject.NULL))
            .put("waking", false)
            .put("who", who ?: JSONObject.NULL)
            .put("picture", JSONObject.NULL)
            .put("mode", "on-tv")
            .put("adb", Adb.connected)
            .put("keyboard", keyboard)
    }

    @Volatile private var usedAt = 0L
    /** A phone pressed something or just joined: watch for text fields for the next minute. */
    fun used() { usedAt = System.currentTimeMillis() }

    /** True while the TV shows its on-screen keyboard: a text field wants typing, so phones open theirs. */
    @Volatile var keyboard = false
        private set
    private val kbdLoop = Executors.newSingleThreadScheduledExecutor()

    /** Sends the current state to every listener. */
    fun push() {
        val msg = payload().toString()
        listeners.forEach { runCatching { it(msg) } }
    }

    private val soonQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    /** A change the TV shows a moment after the press (volume): one fresh look then, however many presses came in between. */
    fun soon() {
        if (!started) { push(); return }
        if (soonQueued.compareAndSet(false, true)) loop.schedule({ soonQueued.set(false); lastSig = ""; tick() }, 350, TimeUnit.MILLISECONDS)
    }

    /** Every 4 s, while a phone listens: front app, power, volume and what is playing. */
    @Synchronized fun start() {
        if (started) return
        started = true
        loop.scheduleWithFixedDelay({ tick() }, 1, 4, TimeUnit.SECONDS)
        // Only while a phone listens: one quick read of the input method each second.
        kbdLoop.scheduleWithFixedDelay({
            try {
                // No text fields on our own home screen, and each read costs this small TV a little.
                // Only for a minute after the phone was used: a remote left open through a film asks nothing.
                if (listeners.isEmpty() || System.currentTimeMillis() - usedAt > 60_000 || !Adb.connected || front == null || front in Apps.HOME_PACKAGES || front == BuildConfig.APPLICATION_ID) {
                    if (keyboard) { keyboard = false; push() }
                    return@scheduleWithFixedDelay
                }
                val shown = Adb.shell("dumpsys input_method | grep -m1 mInputShown", 3000).contains("mInputShown=true")
                if (shown != keyboard) { keyboard = shown; push() }
            } catch (e: Exception) { }
        }, 2, 2, TimeUnit.SECONDS)
    }

    private var ticks = 0
    /**
     * Front app, power, volume and what is playing. With no phone listening there is nothing to show
     * them to, so only the ADB connection is looked after (no process reads every 4 s while you watch).
     */
    private val tickLock = Any()
    // Its own lock, not Now's: start() runs on the main thread and must never wait behind an ADB reconnect.
    private fun tick(): Unit = synchronized(tickLock) {
        try {
            ticks++
            if (!Adb.connected && (Adb.everAllowed || Adb.paired) && ticks % 15 == 1) runCatching { Adb.ensure() }
            if (listeners.isEmpty()) { media = null; lastSig = ""; return }
            front = Tv.frontPackage()
            // The media session is a cheap call and is read every time; without notification access
            // it is a heavy dumpsys, so only every other time and only while a phone is there to show it.
            if (Media.allowed()) media = if (listeners.isEmpty()) null else runCatching { Media.playing(front) }.getOrNull()?.put("updated", iso())
            else if (ticks % 2 == 0) media = if (listeners.isEmpty()) null else readMedia()
            val v = Tv.volume()
            val sig = listOf(front, Tv.isOn, v["level"], v["muted"], media?.optString("title"), media?.optString("subtitle"), media?.optString("state"), media?.opt("duration_ms"),
                sleepEnds, who, Adb.connected).joinToString("|")
            if (sig != lastSig) { lastSig = sig; push() }
        } catch (e: Exception) { android.util.Log.w("couchtv", "now loop: ${e.message}") }
    }

    private fun readMedia(): JSONObject? {
        val pkg = front ?: return null
        if (pkg in Apps.HOME_PACKAGES) return null
        val (uptime, all) = Tv.sessions()
        val s = all.filter { it.pkg == pkg && it.state != null }.sortedWith(compareBy({ !it.active }, { it.state == "NONE" })).firstOrNull()
            ?: return null
        val state = Tv.STATES[s.state] ?: "stopped"
        var pos = s.position
        if (pos != null && pos >= 0 && uptime != null && s.updated != null && state == "playing") pos += ((uptime - s.updated!!) * (if (s.speed == 0.0) 1.0 else s.speed)).toLong()
        if (pos != null && pos < 0) pos = null
        val item = playedItem?.takeIf { it.optString("package") == pkg }
        var title = s.title
        var source = "tv"
        if (title == null && item != null) { title = item.optString("title"); source = "bridge" }
        val (poster, backdrop) = if (item != null && Finder.norm(item.optString("title")) == Finder.norm(title)) item.opt("poster") as? String to item.opt("backdrop") as? String
            else Finder.artwork(title)
        val sameAsPlayed = item != null && Finder.norm(item.optString("title")) == Finder.norm(title)
        return JSONObject().put("title", title ?: JSONObject.NULL)
            .put("episode_title", s.subtitle ?: (if (sameAsPlayed) item!!.opt("episode_title") else null) ?: JSONObject.NULL)
            .put("season", if (sameAsPlayed) item!!.opt("season") else JSONObject.NULL)
            .put("episode", if (sameAsPlayed) item!!.opt("episode") else JSONObject.NULL)
            .put("service", Apps.BY_PACKAGE[pkg]?.id ?: JSONObject.NULL)
            .put("poster", poster ?: JSONObject.NULL).put("backdrop", backdrop ?: JSONObject.NULL)
            .put("state", state).put("position_ms", pos ?: JSONObject.NULL).put("updated", iso())
            .put("duration_ms", JSONObject.NULL).put("duration_source", JSONObject.NULL)
            .put("source", source).put("started", item?.opt("started") ?: JSONObject.NULL)
    }

    // ---------------------------------------------------------------- keep watching

    fun continueItems(whoFilter: String?): JSONArray {
        val h = Store.history()
        val out = JSONArray()
        val seen = HashSet<String>()
        for (i in 0 until h.length()) {
            val it = h.getJSONObject(i)
            val w = it.optString("who").takeIf { s -> s.isNotEmpty() && s != "null" }
            if (whoFilter != null && whoFilter != "both" && w != null && w != whoFilter) continue
            val key = Finder.norm(it.optString("title"))
            if (key.isEmpty() || !seen.add(key)) continue
            val pkg = it.optString("package")
            out.put(JSONObject().put("id", "h-$i").put("title", it.optString("title"))
                .put("episode_title", it.opt("episode_title") ?: JSONObject.NULL).put("season", it.opt("season") ?: JSONObject.NULL)
                .put("episode", it.opt("episode") ?: JSONObject.NULL).put("app", Apps.describe(pkg)?.let { d -> JSONObject(d) } ?: JSONObject.NULL)
                .put("service", it.opt("service") ?: JSONObject.NULL).put("poster", it.opt("poster") ?: JSONObject.NULL)
                .put("backdrop", it.opt("backdrop") ?: JSONObject.NULL).put("progress", JSONObject.NULL).put("kind", "continue")
                .put("last_played", it.opt("when") ?: JSONObject.NULL).put("source", "bridge"))
            if (out.length() >= 20) break
        }
        return out
    }
}
