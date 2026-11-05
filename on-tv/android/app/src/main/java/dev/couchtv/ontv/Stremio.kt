package dev.couchtv.ontv

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What you are in the middle of in your Stremio account, on any device. Sign-in is link.stremio.com, the
 * "link a device" flow of Stremio's own TV app: the TV shows a code, the phone signs in, and the TV receives a
 * session key. Only that key is kept, on the TV. These are the calls stremio-core makes: not a documented API,
 * so when they fail the row just keeps what it last knew.
 * Idea and calls from tvOS Launcher's StremioCloud (Apache 2.0, techgenius1).
 */
object Stremio {
    const val PKG = "com.stremio.one"
    private const val API = "https://api.strem.io/api/"
    private const val LINK = "https://link.stremio.com/api/v2/"
    private const val KEY = "stremio_auth"
    private const val CACHE = "stremio-cw.json"
    private const val RECENT = 60       // only the most recently changed items can be in the middle of being watched
    private const val MOST = 12
    private const val PENDING = 101     // link.stremio.com: nobody has signed in with this code yet
    private const val SESSION_GONE = 1  // api.strem.io: signed out elsewhere

    @Volatile private var readAt = 0L
    private val reading = AtomicBoolean(false)

    fun signedIn() = !Store.str(KEY).isNullOrEmpty()

    // ---------- sign-in ----------
    fun startLink(): JSONObject {
        val r = JSONObject(http(LINK + "create?type=Create")).optJSONObject("result")
        val code = r?.optString("code").orEmpty().ifEmpty { throw ApiError(502, "Stremio gave no code") }
        return JSONObject().put("code", code).put("link", "link.stremio.com/$code")
    }

    /** True once someone signed in with this code. */
    fun pollLink(code: String): Boolean {
        val o = JSONObject(http(LINK + "read?type=Read&code=" + Uri.encode(code)))
        val auth = o.optJSONObject("result")?.optString("authKey").orEmpty()
        if (auth.isNotEmpty()) { Store.putStr(KEY, auth); readAt = 0; return true }
        val err = o.optJSONObject("error")
        if (err != null && err.optInt("code") != PENDING) throw ApiError(502, "Stremio: " + err.optString("message"))
        return false
    }

    fun signOut() {
        val auth = Store.str(KEY)
        forget()
        if (!auth.isNullOrEmpty()) Thread { runCatching { http(API + "logout", JSONObject().put("authKey", auth)) } }.start()
    }

    private fun forget() {
        Store.putStr(KEY, null)
        Store.writeJson(CACHE, JSONArray())
        readAt = 0
    }

    // ---------- the cards ----------
    /** The Continue Watching cards, newest first. A last look takes at most 2.5 s; a slow Stremio fills in next time. */
    fun items(): List<JSONObject> {
        if (!signedIn() || !Tv.hasPackage(PKG)) return emptyList()
        if (System.currentTimeMillis() - readAt > 60_000 && reading.compareAndSet(false, true)) {
            val t = Thread {
                try { Store.writeJson(CACHE, read()); readAt = System.currentTimeMillis() }
                catch (e: Exception) { android.util.Log.w("couchtv", "stremio: ${e.javaClass.simpleName}") }
                finally { reading.set(false) }
            }
            t.start(); t.join(2500)
        }
        val a = Store.readJson(CACHE) as? JSONArray ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    fun open(b: JSONObject) {
        if (!Tv.hasPackage(PKG)) throw ApiError(404, "Stremio is not installed on the TV")
        val id = b.optString("imdb").ifEmpty { throw ApiError(400, "id is required") }
        val video = b.optString("video").ifEmpty { id }
        // For a show this is the episode you were on, with its streams.
        Tv.startUri("stremio:///detail/" + Uri.encode(b.optString("type", "movie")) + "/" + Uri.encode(id, ":") + "/" + Uri.encode(video, ":"), PKG)
        Now.opened(b, PKG)
    }

    private fun read(): JSONArray {
        val meta = call("datastoreMeta", JSONObject().put("collection", "libraryItem")) as? JSONArray ?: return JSONArray()
        val ids = (0 until meta.length()).mapNotNull { meta.optJSONArray(it) }.filter { it.length() >= 2 }
            .sortedByDescending { time(it.opt(1)) }.take(RECENT).map { it.optString(0) }
        if (ids.isEmpty()) return JSONArray()
        val lib = call("datastoreGet", JSONObject().put("collection", "libraryItem").put("ids", JSONArray(ids)).put("all", false)) as? JSONArray
            ?: return JSONArray()
        val cards = (0 until lib.length()).mapNotNull { lib.optJSONObject(it) }.filter { resuming(it) }.mapNotNull { card(it) }
        return JSONArray(cards.sortedByDescending { it.optLong("at") }.take(MOST))
    }

    /** Stremio's own rule (stremio-core, is_in_continue_watching): has a position, not removed, not live. */
    private fun resuming(o: JSONObject): Boolean {
        val st = o.optJSONObject("state") ?: return false
        val type = o.optString("type")
        if (type == "other" || type == "tv" || o.optJSONObject("behaviorHints")?.optBoolean("isLive") == true) return false
        return (!o.optBoolean("removed") || o.optBoolean("temp")) && st.optLong("timeOffset") > 0
    }

    private fun card(o: JSONObject): JSONObject? {
        val id = o.optString("_id").ifEmpty { return null }
        val st = o.getJSONObject("state")
        val video = if (st.isNull("video_id")) "" else st.optString("video_id")
        val pos = st.optLong("timeOffset"); val dur = st.optLong("duration")
        val poster = if (o.isNull("poster")) "" else o.optString("poster")
        val imdb = id.startsWith("tt")
        val bg = when {
            imdb -> "https://images.metahub.space/background/medium/$id/img"
            poster.contains("images.metahub.space") -> poster
            else -> null
        }
        // Stremio sets the position to 1 ms when it moves on to the next, unstarted episode: no progress to show.
        return JSONObject().put("id", "sm:$id").put("imdb", id).put("kind", "stremio").put("type", o.optString("type", "movie"))
            .put("title", o.optString("name")).put("episode", episode(id, video)).put("video", video)
            .put("opens", "stremio").put("source", "stremio").put("package", PKG).put("service", "Stremio")
            .put("progress", if (pos > 1000 && dur > 0) (pos.toDouble() / dur).coerceIn(0.0, 1.0) else JSONObject.NULL)
            .put("at", maxOf(time(st.opt("lastWatched")), time(o.opt("_mtime"))))
            .put("bg", bg ?: JSONObject.NULL).put("logo", if (imdb) "https://images.metahub.space/logo/medium/$id/img" else JSONObject.NULL)
            .put("genres", JSONArray())
    }

    /** From the video id: "tt0903747:2:4" is S2 E4, "kitsu:123:5" is E5. */
    private fun episode(id: String, video: String): String {
        if (!video.startsWith("$id:")) return ""
        val n = video.substring(id.length + 1).split(':').map { it.toIntOrNull() ?: return "" }
        return when (n.size) { 2 -> "S${n[0]} E${n[1]}"; 1 -> "E${n[0]}"; else -> "" }
    }

    /** Stremio's times are ISO strings or epoch milliseconds. */
    private fun time(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        is String -> runCatching { java.time.Instant.parse(v).toEpochMilli() }.getOrDefault(0L)
        else -> 0L
    }

    // ---------- HTTP ----------
    private fun call(method: String, body: JSONObject): Any? {
        val auth = Store.str(KEY).orEmpty().ifEmpty { throw ApiError(401, "not signed in to Stremio") }
        val o = JSONObject(http(API + method, body.put("authKey", auth)))
        val err = o.optJSONObject("error")
        if (err != null) {
            if (err.optInt("code") == SESSION_GONE) forget()
            throw ApiError(502, "Stremio $method: " + err.optString("message"))
        }
        return o.opt("result")
    }

    private fun http(url: String, post: JSONObject? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 15000
            c.setRequestProperty("accept", "application/json")
            if (post != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(post.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.bufferedReader().readText() }.orEmpty()
            if (code !in 200..299) throw ApiError(502, "Stremio answered $code")
            return text
        } finally { c.disconnect() }
    }
}
