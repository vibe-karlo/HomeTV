package dev.couchtv.ontv

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder

/**
 * The bridge, running on the TV. Same endpoint shapes as the computer bridge (bridge/API.md) so one
 * phone remote works with both, plus pairing (/api/pair), ADB setup (/api/adb) and settings.
 *
 * Every /api call needs a token except /api/health (which says only whether you are paired) and
 * /api/pair*. No CORS headers: the phone remote is served from here, so it is same-origin.
 */
class Server(private val ctx: Context, port: Int) : NanoWSD(port) {
    private val assets = ctx.assets

    override fun useGzipWhenAccepted(r: Response) = false   // gzip would hold back server-sent events

    override fun serve(session: IHTTPSession): Response {
        if (session.uri.startsWith("/api/ws") && session.headers["upgrade"]?.lowercase() == "websocket" && !authed(session))
            return json(401, err("pair this phone first"))
        return super.serve(session)
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = Socket(handshake)

    private val current = ThreadLocal<IHTTPSession>()   // each request has its own thread: lets asset() see If-None-Match

    override fun serveHttp(s: IHTTPSession): Response = try {
        current.set(s)
        val uri = s.uri
        when {
            uri.startsWith("/api/") -> api(s)
            uri == "/setup" || uri == "/setup/" -> asset("www/ontv/setup.html")
            uri.startsWith("/ontv/") -> asset("www/ontv/" + uri.removePrefix("/ontv/"))
            uri == "/tv" -> redirect("/tv/")
            uri.startsWith("/tv/") -> staticOrIndex("www/tv", uri.removePrefix("/tv/"), inject = false)
            uri.startsWith("/aerials/file/") -> aerial(s, uri.removePrefix("/aerials/file/"))
            // Show artwork, shrunk and kept on the TV (pictures only, so no pairing needed).
            uri == "/img" -> Img.get(s.parameters["u"]?.firstOrNull().orEmpty(), s.parameters["w"]?.firstOrNull()?.toIntOrNull() ?: 640)
                ?.let { newFixedLengthResponse(Response.Status.OK, "image/webp", java.io.ByteArrayInputStream(it), it.size.toLong())
                    .apply { addHeader("Cache-Control", "max-age=604800") } }
                ?: newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            // App banners for the phone's app list (pictures only, so no pairing needed).
            uri.startsWith("/art/banner/") -> AppArt.banner(uri.removePrefix("/art/banner/"))
                ?.let { newFixedLengthResponse(Response.Status.OK, "image/jpeg", java.io.ByteArrayInputStream(it), it.size.toLong()) }
                ?: newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            // Artwork of what you watch: only for the home screen and paired phones.
            uri.startsWith("/watchnext/art/") || uri.startsWith("/approws/art/") || uri == "/now/art" -> if (!authed(s)) json(401, err("pair this phone first")) else
                (when {
                    uri == "/now/art" -> Media.art()
                    uri.startsWith("/approws/") -> AppRows.art(uri.removePrefix("/approws/art/"))?.readBytes()
                    else -> WatchNext.art(uri.removePrefix("/watchnext/art/"))?.readBytes()
                })
                    ?.let { newFixedLengthResponse(Response.Status.OK, "image/jpeg", java.io.ByteArrayInputStream(it), it.size.toLong()).noStore() }
                    ?: newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            // The phone remote pairs by itself, so it gets no pairing shim.
            else -> staticOrIndex("www/remote", uri.removePrefix("/"), inject = false)
        }
    } catch (e: ApiError) {
        json(e.status, err(e.message ?: "error"))
    } catch (e: Exception) {
        Log.w(TAG, "request ${s.uri}", e)
        json(500, err(e.message ?: e.javaClass.simpleName))
    }

    // ---------------------------------------------------------------- auth

    private fun tokenOf(s: IHTTPSession): String? {
        s.headers["authorization"]?.let { if (it.startsWith("Bearer ", true)) return it.substring(7).trim() }
        s.headers["x-couch-token"]?.let { return it }
        s.parameters["token"]?.firstOrNull()?.let { return it }
        s.headers["cookie"]?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("couchtv_token=") }?.let { return it.substringAfter('=') }
        return null
    }

    private fun authed(s: IHTTPSession) = Auth.valid(tokenOf(s))

    // ---------------------------------------------------------------- API

    private fun api(s: IHTTPSession): Response {
        val path = s.uri.removeSuffix("/")
        val m = s.method
        // Always read the body first: an unread body would be taken as the start of the next request
        // on a kept-alive connection (seen in testing after a 401).
        bodies[s] = readBody(s)
        if (m == Method.OPTIONS) return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
        // open endpoints
        when (path) {
            "/api/health" -> {
                val a = authed(s)
                val o = JSONObject().put("ok", true).put("mode", "on-tv").put("version", BuildConfig.VERSION_NAME).put("auth", if (a) "ok" else "required")
                if (a) o.put("tv_connected", true).put("adb", Adb.connected)
                return json(200, o)
            }
            // Turns the screen on, never off, so it needs no pairing: the iPhone Shortcut calls it after
            // its Wake-on-LAN packet has woken the TV's network from deep standby.
            "/api/wake" -> {
                if (m != Method.POST) throw ApiError(405, "POST only")
                val on = Tv.powerOn()
                Now.push()
                return json(if (on) 200 else 503, ok().put("power", if (Tv.isOn) "on" else "off"))
            }
            "/api/pair/start" -> {
                if (m != Method.POST) throw ApiError(405, "POST only")
                Auth.newPin()
                App.showPin()
                return json(200, ok().put("digits", 6).put("expires_in", 180).put("note", "The code is on the TV now."))
            }
            "/api/pair" -> {
                if (m != Method.POST) throw ApiError(405, "POST only")
                val b = body(s)
                Thread.sleep(400)   // slows guessing a little more
                val token = Auth.pair(b.optString("pin"), b.optString("name").ifEmpty { null })
                    ?: return json(401, err(if (Auth.currentPin() == null) "That code has expired. Ask the TV for a new one." else "That code is not right. Check the TV."))
                Now.push()
                return json(200, ok().put("token", token))
            }
        }
        if (!authed(s)) return json(401, err("pair this phone first: open this page on your phone and type the code from the TV"))

        return when ("$m $path") {
            // The home screen: its rows (Continue Watching first), the aerials on the TV, and this TV's address.
            "GET /api/home-data" -> {
                val shows = Shows.rows()
                val rows = JSONArray().put(Shows.continueRow())
                // The rows the TV's apps publish (MAXtv, EON, Netflix...), after what you are in the middle of.
                AppRows.shown().let { a -> for (i in 0 until a.length()) rows.put(a.get(i)) }
                shows.optJSONArray("rows")?.let { r -> for (i in 0 until r.length()) rows.put(r.get(i)) }
                json(200, ok().put("rows", rows).put("aerials", Aerials.list()).put("address", App.lanAddress() ?: JSONObject.NULL)
                    .put("sport", Sports.payload().optJSONArray("sport")).put("tvnow", Sports.payload().optJSONArray("tvnow"))
                    .put("port", Store.port).put("version", BuildConfig.VERSION_NAME))
            }
            "POST /api/open-show" -> json(200, Shows.open(body(s)))
            "GET /api/sport" -> Sports.payload().let { p -> json(200, ok().put("sport", p.optJSONArray("sport")).put("tvnow", p.optJSONArray("tvnow"))) }
            "POST /api/continue/remove" -> { val b = body(s); Shows.forget(b.optString("id"), b.optLong("at")); json(200, ok()) }
            // Continue Watching again, when the home screen comes back from an app.
            "GET /api/continue-row" -> json(200, ok().put("row", Shows.continueRow()).put("watchNext", WatchNext.allowed()))
            "POST /api/watchnext/open" -> json(200, WatchNext.open(body(s).optString("id")))
            // App rows: every one there is (for Settings), the shown ones again, and opening a card.
            "GET /api/app-rows" -> json(200, ok().put("rows", AppRows.choices()).put("allowed", WatchNext.allowed()))
            "GET /api/app-rows/shown" -> { AppRows.stale(); json(200, ok().put("rows", AppRows.shown())) }
            "POST /api/approw/open" -> json(200, AppRows.open(body(s).optString("id")))
            // Ambilight, through the TV's own Philips API (paired once with a PIN the TV shows).
            "GET /api/ambilight" -> json(200, ok(Philips.ambilight()))
            "POST /api/ambilight" -> json(200, ok(Philips.setAmbilight(body(s))))
            "POST /api/philips/pair/start" -> { Philips.pairStart(); json(200, ok().put("note", "The TV shows a PIN for 60 seconds.")) }
            "POST /api/philips/pair" -> { Philips.pairFinish(body(s).optString("pin").filter { it.isDigit() }); json(200, ok(Philips.ambilight())) }
            "GET /api/tv-guide" -> json(200, ok().put("allowed", TvGuide.allowed()).put("channels", TvGuide.nowNext()))
            // The TV's apps in the home screen's dock order, without the ones hidden there.
            "GET /api/tv-apps" -> {
                val p = Prefs.json()
                val order = p.optJSONArray("dockOrder")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                val hidden = p.optJSONArray("hidden")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
                val all = AppArt.tvApps().map { it.activityInfo.packageName to AppArt.label(it) }.filter { it.first !in hidden }
                    .sortedBy { order.indexOf(it.first).let { i -> if (i < 0) 999 else i } }
                json(200, ok().put("apps", JSONArray(all.map { (pkg, name) -> JSONObject().put("package", pkg).put("name", name).put("banner", "/art/banner/$pkg") })))
            }
            // A pairing code for a new phone, shown in the home screen's Settings instead of the setup screen.
            "POST /api/pair/new" -> json(200, ok().put("pin", Auth.newPin()).put("expires_in", 180))
            "POST /api/key" -> {
                val b = body(s)
                val key = b.optString("key").ifEmpty { throw ApiError(400, "key is required") }
                Tv.key(key, b.optBoolean("hold"))
                json(200, ok().put("key", key))
            }
            "POST /api/text" -> {
                val t = body(s).optString("text").ifEmpty { throw ApiError(400, "text is required") }
                Tv.text(t); json(200, ok())
            }
            "POST /api/launch" -> launch(body(s).optString("app"))
            "POST /api/power" -> power(body(s).optString("state"))
            "POST /api/volume" -> volume(body(s))
            // The TV's sources (HDMI by device name, AV, Live TV), and switching to one.
            "GET /api/inputs" -> json(200, ok().put("inputs", Inputs.list()))
            "POST /api/inputs/open" -> { Inputs.open(body(s).optString("id")); json(200, ok()) }
            // Play, pause, skip and seek in the app that is playing.
            "POST /api/media" -> { Media.control(body(s).optString("action"), Now.front); json(200, ok()) }
            "GET /api/weather" -> json(200, ok(Weather.get() ?: throw ApiError(503, "No weather right now.")))
            // ---- on-TV extras ----
            "GET /api/adb" -> json(200, ok(JSONObject(Adb.status())))
            "POST /api/adb/connect" -> { val tls = body(s).optString("route") == "tls"; if (tls) Adb.disconnect(); Thread { runCatching { Adb.ensure(interactive = true, preferTls = tls) }.onFailure { Log.w(TAG, "adb connect", it) }; Now.push() }.start(); json(200, ok(JSONObject(Adb.status()))) }
            "POST /api/adb/pair" -> {
                val b = body(s)
                val code = b.optString("code").filter { it.isDigit() }.ifEmpty { throw ApiError(400, "code is required") }
                val r = JSONObject(Adb.pair(code, b.optInt("port").takeIf { it > 0 }))
                if (r.optBoolean("ok")) { Adb.disconnect(); r.put("connected", Adb.ensure(interactive = true, preferTls = true)).put("route", Adb.route ?: JSONObject.NULL) }
                r.put("adb", JSONObject(Adb.status()))
                json(if (r.optBoolean("ok")) 200 else 502, r)
            }
            "GET /api/settings" -> json(200, settings())
            "POST /api/settings" -> { saveSettings(body(s)); json(200, settings()) }
            "POST /api/phones/forget" -> { Auth.forgetAll(); json(200, ok().put("note", "Every phone must pair again.")) }
            else -> json(404, err("no such endpoint: $m $path"))
        }
    }

    private fun launch(target: String): Response {
        if (target.isEmpty()) throw ApiError(400, "app is required")
        if ("://" in target || target.startsWith("intent:")) {
            Tv.startUri(target, Apps.packageForLink(target)?.takeIf { Tv.hasPackage(it) }); Thread.sleep(1500)
            return json(200, ok().put("app", Apps.describe(Tv.frontPackage())?.let { JSONObject(it) } ?: JSONObject.NULL))
        }
        val app = Apps.BY_ID[target] ?: Apps.BY_PACKAGE[target]
        if (app?.id == "home") {
            if (Adb.connected) Tv.key("home") else App.openHome()
            return json(200, ok().put("app", JSONObject(Apps.describe(BuildConfig.APPLICATION_ID)!!)))
        }
        val pkg = app?.pkg ?: if ("." in target) target else throw ApiError(400, "unknown app: $target")
        if (!Tv.hasPackage(pkg)) throw ApiError(404, "${app?.name ?: pkg} is not installed on the TV")
        Tv.launchPackage(pkg)
        val got = if (Adb.connected) Tv.waitForApp(setOf(pkg), 8000) else pkg
        if (got == null) throw ApiError(504, "asked the TV to open ${app?.name ?: pkg} but it did not come up")
        Now.push()
        return json(200, ok().put("app", JSONObject(Apps.describe(got)!!)))
    }

    private fun power(state: String): Response {
        when (state) {
            "on" -> if (!Tv.powerOn()) throw ApiError(503, "The TV app cannot wake a sleeping TV. Turn it on with its own remote.")
            "off" -> if (Tv.isOn) Tv.powerOff()
            "toggle" -> if (Tv.isOn) Tv.powerOff() else if (!Tv.powerOn()) throw ApiError(503, "The TV app cannot wake a sleeping TV.")
            else -> throw ApiError(400, "state must be on, off or toggle")
        }
        Now.push()
        return json(200, ok().put("power", if (Tv.isOn) "on" else "off"))
    }

    private fun volume(b: JSONObject): Response {
        if (b.has("level")) Tv.setLevel(b.optInt("level"))
        else {
            val code = mapOf("up" to "KEYCODE_VOLUME_UP", "down" to "KEYCODE_VOLUME_DOWN", "mute" to "KEYCODE_VOLUME_MUTE")[b.optString("action")]
                ?: throw ApiError(400, "action must be up, down or mute, or send level")
            val steps = if (code.endsWith("MUTE")) 1 else b.optInt("steps", 1).coerceIn(1, 20)
            repeat(steps) { Tv.volumeKey(code); Thread.sleep(80) }
        }
        Thread.sleep(300)
        Now.push()
        return json(200, ok().put("volume", JSONObject(Tv.volume())))
    }

    private fun settings(): JSONObject = ok()
        // the TV home's own Home settings (language, place, look...), kept here as they come
        .put("settings", Store.readJson("home-settings.json") as? JSONObject ?: JSONObject())
        .put("updated", Store.str("home_settings_updated") ?: JSONObject.NULL)
        .put("people", Store.people()).put("weather", Store.weather()).put("country", Store.country()).put("tv_name", Store.tvName())
        
        .put("phones", Auth.phoneCount()).put("port", Store.port).put("adb", JSONObject(Adb.status()))

    private fun saveSettings(b: JSONObject) {
        b.optJSONObject("settings")?.let {
            if (it.toString().length > 32_000) throw ApiError(413, "settings are too big")
            Store.writeJson("home-settings.json", it)
            Store.putStr("home_settings_updated", Now.iso())
        }
        b.optJSONArray("people")?.let { arr ->
            val clean = JSONArray()
            for (i in 0 until minOf(arr.length(), 8)) {
                val p = arr.optJSONObject(i) ?: continue
                val name = p.optString("name").trim().take(30)
                if (name.isNotEmpty()) clean.put(JSONObject().put("id", p.optString("id").ifEmpty { "person${i + 1}" }.take(30)).put("name", name))
            }
            if (clean.length() > 0) Store.putStr("people", clean.toString())
        }
        b.optJSONObject("weather")?.let { w ->
            if (w.has("lat") && w.has("lon")) Store.putStr("weather", JSONObject().put("lat", w.getDouble("lat")).put("lon", w.getDouble("lon"))
                .put("place", w.optString("place").take(40)).put("timezone", w.optString("timezone", "UTC").take(40)).toString())
            Weather.clear()
        }
        b.optString("country").takeIf { Regex("[A-Z]{2}").matches(it) }?.let { Store.putStr("country", it) }
        b.optString("tv_name").trim().takeIf { it.isNotEmpty() }?.let { Store.putStr("tv_name", it.take(30)) }
    }

    // ---------------------------------------------------------------- WebSocket (/api/ws)

    /**
     * Low-latency channel. Sends {"event":"now", ...} on every change. Takes
     * {"key":"up"}, {"key":"ok","hold":true}, {"volume":"up"}, {"text":"bluey"} or {"media":"next"}; answers {"ok":true} or an error.
     */
    // The server drops a connection that sends nothing for SOCKET_READ_TIMEOUT (5 s). A ping every 3 s
    // gets a pong back from the phone, so an idle remote stays connected instead of reconnecting.
    private val sockets = java.util.concurrent.CopyOnWriteArraySet<Socket>()
    private val pinger = java.util.concurrent.Executors.newSingleThreadScheduledExecutor().apply {
        scheduleWithFixedDelay({ sockets.forEach { s -> runCatching { s.ping(PING) }.onFailure { sockets.remove(s) } } }, 3, 3, java.util.concurrent.TimeUnit.SECONDS)
    }

    private inner class Socket(h: IHTTPSession) : WebSocket(h) {
        private val fn: (String) -> Unit = { runCatching { send(JSONObject(it).put("event", "now").toString()) } }
        override fun onOpen() { sockets.add(this); Now.listen(fn); runCatching { send(Now.payload().put("event", "now").toString()) } }
        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) { sockets.remove(this); Now.unlisten(fn) }
        override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
        override fun onException(e: IOException?) { sockets.remove(this); Now.unlisten(fn) }
        override fun onMessage(frame: NanoWSD.WebSocketFrame) {
            val reply = try {
                val m = JSONObject(frame.textPayload)
                when {
                    m.has("key") -> { Tv.key(m.getString("key"), m.optBoolean("hold")); JSONObject().put("ok", true).put("key", m.getString("key")) }
                    m.has("volume") -> { volume(JSONObject().put("action", m.getString("volume"))); JSONObject().put("ok", true) }
                    m.has("text") -> { Tv.text(m.getString("text")); JSONObject().put("ok", true) }
                    m.has("media") -> { Media.control(m.getString("media"), Now.front); JSONObject().put("ok", true) }
                    else -> JSONObject().put("ok", false).put("error", "unknown message")
                }
            } catch (e: ApiError) { JSONObject().put("ok", false).put("error", e.message).put("status", e.status) }
            catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message) }
            runCatching { send(reply.toString()) }
        }
    }

    // ---------------------------------------------------------------- static files

    private fun staticOrIndex(root: String, rel: String, inject: Boolean): Response {
        val clean = URLDecoder.decode(rel, "UTF-8")
        if (clean.contains("..")) return json(400, err("bad path"))
        if (clean.isNotEmpty() && exists("$root/$clean")) return if (inject && clean == "index.html") index(root) else asset("$root/$clean")
        // single page apps: unknown paths get index.html (files with an extension get a 404)
        if (clean.substringAfterLast('/').contains('.')) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        return if (inject) index(root) else asset("$root/index.html")
    }

    /** The phone remote's index.html with the on-TV pairing script added first. */
    private fun index(root: String): Response {
        val html = assets.open("$root/index.html").bufferedReader().readText()
        val tag = "<script src=\"/ontv/shim.js\"></script>"
        val out = if (html.contains("<head>")) html.replaceFirst("<head>", "<head>$tag") else tag + html
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", out).noCache()
    }

    private fun exists(path: String) = try { assets.open(path).close(); true } catch (e: IOException) { false }

    // The page's files change only when the app is installed again: that install's time is their version.
    private val stamp by lazy { "\"" + ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString(36) + "\"" }

    /** A file of the page: fonts and pictures kept for a while, the rest checked (a quick 304) on each load. */
    private fun asset(path: String): Response = try {
        val r = if (current.get()?.headers?.get("if-none-match") == stamp) newFixedLengthResponse(Response.Status.NOT_MODIFIED, mime(path), "")
            else assets.open(path).use { it.readBytes() }.let { b -> newFixedLengthResponse(Response.Status.OK, mime(path), java.io.ByteArrayInputStream(b), b.size.toLong()) }
        r.apply {
            addHeader("ETag", stamp)
            addHeader("Cache-Control", if (Regex("\\.(woff2|ttf|jpe?g|png|webp|svg)$").containsMatchIn(path)) "max-age=86400" else "no-cache")
        }
    } catch (e: IOException) { newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found") }

    /** An aerial clip kept on the TV, with byte ranges so the home screen's video can stream and seek. */
    private fun aerial(s: IHTTPSession, name: String): Response {
        val f = Aerials.file(URLDecoder.decode(name, "UTF-8")) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        val size = f.length()
        val m = Regex("bytes=(\\d*)-(\\d*)").find(s.headers["range"] ?: "")
        if (m == null) return newFixedLengthResponse(Response.Status.OK, "video/mp4", f.inputStream(), size).apply { addHeader("Accept-Ranges", "bytes") }
        val start = m.groupValues[1].toLongOrNull() ?: (size - (m.groupValues[2].toLongOrNull() ?: size)).coerceAtLeast(0)
        val end = (if (m.groupValues[1].isNotEmpty()) m.groupValues[2].toLongOrNull() else null)?.coerceAtMost(size - 1) ?: (size - 1)
        if (start >= size) return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "").apply { addHeader("Content-Range", "bytes */$size") }
        val inp = f.inputStream().apply { skip(start) }
        return newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "video/mp4", inp, end - start + 1).apply {
            addHeader("Accept-Ranges", "bytes"); addHeader("Content-Range", "bytes $start-$end/$size")
        }
    }

    private fun mime(p: String) = when (p.substringAfterLast('.').lowercase()) {
        "html" -> "text/html; charset=utf-8"; "js", "mjs" -> "application/javascript"; "css" -> "text/css"
        "json" -> "application/json"; "webmanifest" -> "application/manifest+json"; "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"; "woff2" -> "font/woff2"
        "ico" -> "image/x-icon"; "txt" -> "text/plain"; else -> "application/octet-stream"
    }

    private fun redirect(to: String) = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply { addHeader("Location", to) }

    // ---------------------------------------------------------------- helpers

    private val bodies = java.util.Collections.synchronizedMap(java.util.WeakHashMap<IHTTPSession, JSONObject>())

    private fun body(s: IHTTPSession): JSONObject = bodies[s] ?: JSONObject()

    private fun readBody(s: IHTTPSession): JSONObject {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return JSONObject()
        if (len > 64 * 1024) throw ApiError(413, "request too large")
        val buf = ByteArray(len)
        var read = 0
        while (read < len) { val n = s.inputStream.read(buf, read, len - read); if (n < 0) break; read += n }
        val text = String(buf, 0, read, Charsets.UTF_8).trim()
        if (text.isEmpty()) return JSONObject()
        return try { JSONObject(text) } catch (e: Exception) { throw ApiError(400, "body must be JSON") }
    }

    private fun ok(from: JSONObject? = null): JSONObject = (from ?: JSONObject()).put("ok", true)
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)

    private fun json(status: Int, o: JSONObject): Response =
        newFixedLengthResponse(Status(status), "application/json", o.toString()).noStore()

    private fun Response.noCache() = apply { addHeader("Cache-Control", "no-cache") }
    private fun Response.noStore() = apply { addHeader("Cache-Control", "no-store") }

    private class Status(private val code: Int) : Response.IStatus {
        override fun getRequestStatus() = code
        override fun getDescription() = "$code " + when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
            409 -> "Conflict"; 413 -> "Payload Too Large"; 501 -> "Not Implemented"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"; 504 -> "Gateway Timeout"; else -> "Status" }
    }

    companion object { private const val TAG = "couchtv.server"; private val PING = byteArrayOf(1) }
}
