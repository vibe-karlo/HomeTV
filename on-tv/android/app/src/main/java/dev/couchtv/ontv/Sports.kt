package dev.couchtv.ontv

import android.util.Log
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Live sport you follow, from TvProfil's free live-sport guide (tvprofil.net/xmltv, for home use):
 * the next two days of live broadcasts on Croatian sport channels. Fetched at most every 30 minutes.
 * F1 is on RTL, which is not a sport channel, so RTL and RTL 2's own daily guides are read too.
 * MAXSport and Arena Sport are on MAXtv; everything else opens in EON, and F1 always opens in EON.
 */
object Sports {
    private const val FEED = "https://tvprofil.net/xmltv/data/epg_sport_live_tvprofil.net.xml"
    private const val EVERY_MS = 30 * 60_000L
    /**
     * Channels with a free daily guide on TvProfil, by the name the home screen shows. Settings, Live TV
     * picks which go in the slider; RTL and RTL 2 are always read, for F1.
     */
    val CHANNELS = linkedMapOf("htv1.hr" to "HRT 1", "htv2.hr" to "HRT 2", "htv3.hr" to "HRT 3", "htv4.hr" to "HRT 4",
        "rtl.hr" to "RTL", "rtl2.hr" to "RTL 2", "nova.hr" to "Nova TV", "doma-tv.movie" to "Doma TV",
        "hbo.movie" to "HBO", "cinestar-tv.movie" to "CineStar TV", "scifi.movie" to "SciFi", "cmc.music" to "CMC",
        "eurosport.sport" to "Eurosport", "sportklub-hr.sport" to "Sport Klub", "tv-arena-sport-1-hr.sport" to "Arena Sport 1")
    val NOW_CHANNELS = listOf("RTL", "Nova TV")
    @Volatile private var fetched = emptySet<String>()
    // Not worth a place in the slider: shopping, trailers, the end of the day.
    private val FILLER = listOf("tv prodaja", "telestar", "najava", "kraj programa", "reklam", "infokanal")
    const val MAXTV = "hr.telekom.android.maxtvp"
    const val EON = "uc.flex.hr.smarttv"
    @Volatile private var cache: JSONArray? = null
    @Volatile private var at = 0L

    /** Broadcasts whose title matches one of the words, not yet over, soonest first. */
    fun events(words: List<String>): JSONArray {
        val all = all()
        val now = System.currentTimeMillis()
        val keys = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val out = (0 until all.length()).map { all.getJSONObject(it) }.filter { e ->
            val t = e.optString("title").lowercase()
            e.optLong("stop") > now && keys.any { k -> matches(t, k) }
        }.map { e ->
            val t = e.optString("title").lowercase()
            if (matches(t, "f1") || t.contains("formula 1")) JSONObject(e.toString()).put("opens", EON) else e
        }.sortedBy { it.optLong("start") }
        return JSONArray(out.take(8))
    }

    /** What is on now on each channel (or next, when now is filler), with artwork from TMDB when it has some. */
    fun nowOn(channels: List<String>): JSONArray {
        val all = all()
        val now = System.currentTimeMillis()
        val out = JSONArray()
        for (ch in channels) {
            val list = (0 until all.length()).map { all.getJSONObject(it) }.filter { it.optString("channel") == ch && it.optLong("stop") > now }
                .sortedBy { it.optLong("start") }
            val pick = list.firstOrNull { e -> FILLER.none { e.optString("title").lowercase().contains(it) } } ?: continue
            if (pick.optLong("start") - now > 3 * 3600_000L) continue   // nothing worth showing soon
            val o = JSONObject(pick.toString()).put("kind", "tv")
            art(ch, cleanTitle(pick.optString("title")))?.let { o.put("image", it) }
            out.put(o)
        }
        return out
    }

    /** "Love Island Adria 1, ep. 36." to "Love Island Adria". */
    private fun cleanTitle(t: String) = t.replace(Regex(",?\\s*ep\\.\\s*\\d+\\.?.*$"), "").replace(Regex("\\s+\\d+$"), "").trim()

    /**
     * The show's picture: the channel's own show page (its share picture, the official key art), else a
     * strict TMDB match. Remembered on the TV for a week, "none" included, so each show is looked up once.
     */
    private fun art(channel: String, titleIn: String): String? {
        // Nova's short news bulletins share the Dnevnik's page.
        val title = if (channel == "Nova TV" && titleIn.startsWith("Vijesti")) "Dnevnik Nove TV" else titleIn
        if (title.isEmpty()) return null
        val known = (Store.readJson("tvart.json") as? JSONObject) ?: JSONObject()
        val hit = known.optJSONObject(title)
        if (hit != null && System.currentTimeMillis() - hit.optLong("at") < 7 * 86_400_000L) return hit.optString("url").ifEmpty { null }
        val url = channelArt(channel, title) ?: tmdbArt(title)
        if (url != null || hit == null) Store.writeJson("tvart.json", known.put(title, JSONObject().put("url", url ?: "").put("at", System.currentTimeMillis())))
        return url
    }

    private fun slug(s: String) = java.text.Normalizer.normalize(s.lowercase().replace("đ", "d"), java.text.Normalizer.Form.NFKD)
        .replace(Regex("[^\\p{ASCII}]"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** RTL's shows live on Voyo, Nova's on novatv.dnevnik.hr, at addresses made from the title. */
    private fun channelArt(channel: String, title: String): String? {
        val s = slug(title)
        val pages = when (channel) {
            "RTL", "RTL 2" -> listOf("https://voyo.rtl.hr/sadrzaj/$s", "https://www.rtl.hr/emisije/$s", "https://www.rtl.hr/tv-emisije/$s")
            "Nova TV" -> listOf("https://novatv.dnevnik.hr/serije/$s/", "https://novatv.dnevnik.hr/zabavni/$s/", "https://novatv.dnevnik.hr/emisije/$s/", "https://novatv.dnevnik.hr/$s/")
            else -> emptyList()
        }
        for (page in pages) {
            val html = try {
                val c = URL(page).openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 8000; c.setRequestProperty("user-agent", "Mozilla/5.0 HomeTV-personal/2")
                if (c.responseCode != 200) { c.disconnect(); continue }
                // The share picture is in <head>: stop reading there (Voyo pages run to 1.5 MB).
                val head = StringBuilder()
                c.inputStream.bufferedReader().use { r ->
                    for (line in r.lineSequence()) { head.append(line).append('\n'); if ("</head>" in line || head.length > 400_000) break }
                }
                c.disconnect(); head.toString()
            } catch (e: Exception) { continue }
            val img = Regex("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)").find(html)?.groupValues?.get(1)
                ?: Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image").find(html)?.groupValues?.get(1)
            if (img != null && !Regex("logo|default|placeholder", RegexOption.IGNORE_CASE).containsMatchIn(img)) return img.replace("&amp;", "&")
        }
        return null
    }

    /** TMDB, TV shows only, and only when the name really is the show's name. */
    private fun tmdbArt(title: String): String? {
        val key = BuildConfig.TMDB_KEY.ifEmpty { return null }
        val norm = { s: String -> slug(s) }
        return try {
            val q = java.net.URLEncoder.encode(title, "UTF-8")
            val c = URL("https://api.themoviedb.org/3/search/tv?query=$q&language=hr-HR&api_key=$key").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 8000
            val res = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("results") ?: JSONArray()
            c.disconnect()
            (0 until res.length()).map { res.getJSONObject(it) }.firstOrNull {
                it.optString("backdrop_path").startsWith("/") && (norm(it.optString("name")) == norm(title) || norm(it.optString("original_name")) == norm(title))
            }?.let { "https://image.tmdb.org/t/p/w1280" + it.optString("backdrop_path") }
        } catch (e: Exception) { null }
    }

    // "F1" as a word (not inside "WTA 500 F1RST"), longer words anywhere.
    private fun matches(title: String, key: String) =
        if (key.length <= 3) Regex("(^|[^a-z0-9])" + Regex.escape(key) + "([^a-z0-9]|$)").containsMatchIn(title) else title.contains(key)

    @Synchronized private fun all(): JSONArray {
        // A channel picked since the last read needs its guide now, not in half an hour.
        cache?.let { if (System.currentTimeMillis() - at < EVERY_MS && fetched.containsAll(Prefs.tvNowChannels())) return it }
        return try {
            val list = fetch(FEED)
            // RTL and RTL 2, today and tomorrow (CET days, as the guide names its files).
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Europe/Zagreb") }
            val want = (Prefs.tvNowChannels() + listOf("RTL", "RTL 2")).toSet()
            for ((id, name) in CHANNELS) if (name in want) for (d in 0..1) runCatching {
                val date = day.format(java.util.Date(System.currentTimeMillis() + d * 86_400_000L))
                val got = fetch("https://tvprofil.net/xmltv/data/$id/${date}_${id}_tvprofil.net.xml")
                for (i in 0 until got.length()) list.put(got.getJSONObject(i).put("channel", name).put("opens", EON))
            }
            cache = list; at = System.currentTimeMillis(); fetched = want
            Store.writeJson("sport.json", list)
            list
        } catch (e: Exception) {
            Log.w("couchtv", "sport guide: ${e.message}")
            cache ?: (Store.readJson("sport.json") as? JSONArray) ?: JSONArray()
        }
    }

    private fun fetch(url: String): JSONArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 20000
        c.setRequestProperty("user-agent", "couch-tv-on-tv/2 (home use)")
        try { return c.inputStream.use { parse(it) } } finally { c.disconnect() }
    }

    private fun parse(inp: java.io.InputStream): JSONArray {
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val p = Xml.newPullParser().apply { setInput(inp, "UTF-8") }
        val out = JSONArray()
        var cur: JSONObject? = null
        var field: String? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "programme" -> {
                        val ch = p.getAttributeValue(null, "channel").orEmpty()
                        cur = JSONObject().put("channel", ch)
                            .put("start", runCatching { fmt.parse(p.getAttributeValue(null, "start"))!!.time }.getOrDefault(0L))
                            .put("stop", runCatching { fmt.parse(p.getAttributeValue(null, "stop"))!!.time }.getOrDefault(0L))
                            .put("opens", appFor(ch))
                    }
                    "title", "desc", "category" -> field = p.name
                }
                XmlPullParser.TEXT -> if (cur != null && field != null && !cur.has(field)) cur.put(field, p.text.trim().removeSuffix("\u00AE").trim())   // RTL marks titles with ®
                XmlPullParser.END_TAG -> when (p.name) {
                    "programme" -> { cur?.let { out.put(it) }; cur = null }
                    "title", "desc", "category" -> field = null
                }
            }
        }
        return out
    }

    /** MAXSport and Arena Sport are MAXtv's channels; the rest open in EON. */
    fun appFor(channel: String): String {
        val c = channel.lowercase()
        return if (c.startsWith("maxsport") || c.startsWith("max sport") || c.startsWith("arena sport")) MAXTV else EON
    }
}
