package dev.couchtv.ontv

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The TV's own Philips API (JointSpace 6, the one the Philips remote app uses), on this TV at
 * https://127.0.0.1:1926. Paired once with a PIN the TV shows; then Ambilight: on or off, its style,
 * and its brightness. Things ADB cannot do.
 */
object Philips {
    private const val TAG = "couchtv"
    private const val BASE = "https://127.0.0.1:1926/6/"
    // The key the Philips apps sign the PIN with (public: pylips and Home Assistant use it too).
    private val SECRET = Base64.decode("ZmVay1EQVFOaZhwQ4Kv81ypLAZNczV9sG4KkseXWn1NEk6cXmPKO/MCa9sryslvLCFMnNe4Z4CPXzToowvhHvA==", Base64.DEFAULT)
    private const val BRIGHTNESS = 2130968768
    // Styles by the settings-menu list they live in: video, music, lounge colours.
    private const val VIDEO = 2130968778; private const val AUDIO = 2130968775; private const val LOUNGE = 2130968776
    data class Style(val id: String, val name: String, val group: String, val node: Int, val item: Int, val setting: String)
    val STYLES = listOf(
        Style("video-standard", "Standard", "Follow video", VIDEO, 1, "STANDARD"),
        Style("video-natural", "Natural", "Follow video", VIDEO, 2, "NATURAL"),
        Style("video-vivid", "Vivid", "Follow video", VIDEO, 4, "VIVID"),
        Style("video-game", "Game", "Follow video", VIDEO, 5, "GAME"),
        Style("audio-lumina", "Lumina", "Follow music", AUDIO, 101, "ENERGY_ADAPTIVE_BRIGHTNESS"),
        Style("lounge-hot-lava", "Hot Lava", "Lounge light", LOUNGE, 201, "HOT_LAVA"),
        Style("lounge-deep-water", "Deep Water", "Lounge light", LOUNGE, 202, "DEEP_WATER"),
        Style("lounge-fresh-nature", "Fresh Nature", "Lounge light", LOUNGE, 203, "FRESH_NATURE"),
        Style("lounge-warm-white", "Warm White", "Lounge light", LOUNGE, 207, "ISF"))

    private val device get() = JSONObject().put("device_name", Store.tvName().ifEmpty { "HomeTV" }).put("device_os", "Android")
        .put("app_name", "HomeTV").put("type", "native").put("app_id", BuildConfig.APPLICATION_ID).put("id", creds().optString("id"))

    private fun creds(): JSONObject = (Store.readJson("philips.json") as? JSONObject) ?: JSONObject()
    fun paired() = creds().optBoolean("paired")

    /** Step 1: the TV shows a PIN for 60 s. */
    fun pairStart() {
        val id = (1..16).map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[SecureRandom().nextInt(62)] }.joinToString("")
        Store.writeJson("philips.json", JSONObject().put("id", id))
        val r = JSONObject(request("POST", "pair/request", JSONObject().put("scope", JSONArray(listOf("read", "write", "control"))).put("device", device).toString(), auth = false))
        if (r.optString("error_id") != "SUCCESS") throw ApiError(502, "The TV did not start pairing: ${r.optString("error_text")}")
        Store.writeJson("philips.json", JSONObject().put("id", id).put("key", r.optString("auth_key")).put("ts", r.optLong("timestamp")))
    }

    /** Step 2: the PIN from the TV's screen. */
    fun pairFinish(pin: String) {
        val c = creds()
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(SECRET, "HmacSHA1")) }
        val hex = mac.doFinal((c.optLong("ts").toString() + pin).toByteArray()).joinToString("") { "%02x".format(it) }
        val sig = Base64.encodeToString(hex.toByteArray(), Base64.NO_WRAP)
        val body = JSONObject().put("auth", JSONObject().put("auth_AppId", "1").put("pin", pin).put("auth_timestamp", c.optLong("ts")).put("auth_signature", sig))
            .put("device", device)
        val r = JSONObject(request("POST", "pair/grant", body.toString()).ifEmpty { "{}" })
        if (r.optString("error_id") != "SUCCESS") throw ApiError(400, "That PIN did not work. Ask the TV for a new one.")
        Store.writeJson("philips.json", c.put("paired", true))
    }

    fun forget() = Store.writeJson("philips.json", JSONObject())

    /** Ambilight as the phone shows it. */
    fun ambilight(): JSONObject {
        if (!paired()) return JSONObject().put("paired", false)
        val on = JSONObject(request("GET", "ambilight/power")).optString("power") == "On"
        val cfg = JSONObject(request("GET", "ambilight/currentconfiguration"))
        val style = STYLES.firstOrNull { it.setting == cfg.optString("menuSetting") }
        return JSONObject().put("paired", true).put("on", on).put("style", style?.id ?: JSONObject.NULL)
            .put("styleName", cfg.optString("stringValue")).put("brightness", node(BRIGHTNESS)?.optInt("value") ?: JSONObject.NULL)
            .put("styles", JSONArray(STYLES.map { JSONObject().put("id", it.id).put("name", it.name).put("group", it.group) }))
    }

    /** Any of: on (true/false), style (an id from STYLES), brightness (0 to 10). */
    fun setAmbilight(b: JSONObject): JSONObject {
        if (!paired()) throw ApiError(409, "Pair HomeTV with the TV's Philips settings first (Settings, Phone Remote on the TV, or the setup page).")
        if (b.has("on")) request("POST", "ambilight/power", JSONObject().put("power", if (b.getBoolean("on")) "On" else "Off").toString())
        b.optString("style").takeIf { it.isNotEmpty() }?.let { id ->
            val s = STYLES.firstOrNull { it.id == id } ?: throw ApiError(400, "unknown style")
            update(s.node, JSONObject().put("selected_item", s.item))
        }
        if (b.has("brightness")) update(BRIGHTNESS, JSONObject().put("value", b.getInt("brightness").coerceIn(0, 10)))
        return ambilight()
    }

    private fun node(id: Int): JSONObject? = runCatching {
        JSONObject(request("POST", "menuitems/settings/current", JSONObject().put("nodes", JSONArray().put(JSONObject().put("nodeid", id))).toString()))
            .getJSONArray("values").getJSONObject(0).getJSONObject("value").getJSONObject("data")
    }.getOrNull()

    private fun update(id: Int, data: JSONObject) {
        request("POST", "menuitems/settings/update", JSONObject().put("values", JSONArray().put(JSONObject().put("value",
            JSONObject().put("Nodeid", id).put("Controllable", true).put("Available", true).put("data", data)))).toString())
    }

    // The TV's certificate is its own, self-signed; this only ever talks to 127.0.0.1.
    private val ssl by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            }), null)
        }.socketFactory
    }

    /** One call, with HTTP digest authentication (the TV asks with a 401 first). */
    @Synchronized private fun request(method: String, path: String, body: String? = null, auth: Boolean = true): String {
        var header: String? = null
        for (attempt in 0..1) {
            val c = (URL(BASE + path).openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = ssl; hostnameVerifier = javax.net.ssl.HostnameVerifier { h, _ -> h == "127.0.0.1" }
                connectTimeout = 4000; readTimeout = 8000; requestMethod = method
                header?.let { setRequestProperty("Authorization", it) }
                if (body != null) { doOutput = true; setRequestProperty("content-type", "application/json"); outputStream.use { it.write(body.toByteArray()) } }
            }
            try {
                val code = c.responseCode
                if (code == 401 && auth && attempt == 0) { header = digest(c.getHeaderField("WWW-Authenticate") ?: throw ApiError(502, "The TV asked for a login it did not describe"), method, path); continue }
                // The TV forgot HomeTV (reset, or pairing removed): forget it too, so the phone offers pairing again.
                if (code == 401) { Log.w(TAG, "philips: not allowed on $path"); if (auth) forget(); throw ApiError(409, "The TV no longer knows HomeTV. Pair again.") }
                if (code !in 200..299) throw ApiError(502, "The TV answered $code to $path")
                return c.inputStream.bufferedReader().readText()
            } finally { c.disconnect() }
        }
        throw ApiError(502, "The TV did not accept the login")
    }

    private fun digest(challenge: String, method: String, path: String): String {
        val c = creds()
        val f = Regex("(\\w+)=\"?([^\",]*)\"?").findAll(challenge).associate { it.groupValues[1] to it.groupValues[2] }
        val md5 = { s: String -> MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
        val uri = "/6/$path"
        val ha1 = md5("${c.optString("id")}:${f["realm"]}:${c.optString("key")}")
        val ha2 = md5("$method:$uri")
        val nc = "00000001"; val cnonce = java.lang.Long.toHexString(SecureRandom().nextLong())
        val qop = f["qop"]?.split(',')?.firstOrNull()?.trim()
        val resp = if (qop != null) md5("$ha1:${f["nonce"]}:$nc:$cnonce:$qop:$ha2") else md5("$ha1:${f["nonce"]}:$ha2")
        return "Digest username=\"${c.optString("id")}\", realm=\"${f["realm"]}\", nonce=\"${f["nonce"]}\", uri=\"$uri\", response=\"$resp\"" +
            (if (qop != null) ", qop=$qop, nc=$nc, cnonce=\"$cnonce\"" else "") + (f["opaque"]?.let { ", opaque=\"$it\"" } ?: "") + ", algorithm=MD5"
    }
}
