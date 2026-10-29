package dev.couchtv.ontv

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Makes the guide's F1 broadcasts readable: the race's own name from the F1 calendar (Jolpica, the free
 * Ergast successor), the session on its own line, and a different official photo for each session, from
 * the race's page on formula1.com and the articles it links. Calendar read once a day, pictures once per race.
 */
object F1 {
    private const val TAG = "couchtv"
    private const val UA = "Mozilla/5.0 HomeTV-personal/2"
    private const val DAY = 86_400_000L
    private val SESSIONS = listOf("FirstPractice" to "Practice 1", "SecondPractice" to "Practice 2", "ThirdPractice" to "Practice 3",
        "SprintQualifying" to "Sprint Qualifying", "Sprint" to "Sprint", "Qualifying" to "Qualifying", "Race" to "Race")
    // formula1.com's page names where they are not the place or the country.
    private val PAGE = mapOf("americas" to "united-states", "catalunya" to "barcelona-catalunya", "silverstone" to "great-britain",
        "yas_marina" to "united-arab-emirates", "red_bull_ring" to "austria", "spa" to "belgium", "zandvoort" to "netherlands",
        "monza" to "italy", "interlagos" to "brazil", "rodriguez" to "mexico", "losail" to "qatar", "marina_bay" to "singapore",
        "baku" to "azerbaijan", "albert_park" to "australia", "suzuka" to "japan", "shanghai" to "china", "villeneuve" to "canada")

    fun isF1(title: String) = Regex("(^|[^a-z0-9])f1([^a-z0-9]|$)|formula 1", RegexOption.IGNORE_CASE).containsMatchIn(title)

    /** Adds "title" (the race), "session" and "image" to an F1 broadcast; leaves it as it was when the calendar has no match. */
    fun dress(e: JSONObject): JSONObject {
        val start = e.optLong("start")
        val race = races().firstOrNull { r -> val (a, b) = weekend(r); start in a..b } ?: return e
        val session = session(e.optString("title"), race, start)
        val o = JSONObject(e.toString()).put("title", "F1 " + race.optString("raceName")).put("session", session ?: "")
        val pics = pictures(race)
        if (pics.isNotEmpty()) o.put("image", pics[(SESSIONS.indexOfFirst { it.second == session }.coerceAtLeast(0)) % pics.size])
        return o
    }

    private fun time(d: JSONObject?): Long = d?.let {
        runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US).parse(it.optString("date") + "T" + it.optString("time", "12:00:00Z"))!!.time }.getOrNull()
    } ?: 0L

    private fun sessionTime(r: JSONObject, key: String) = if (key == "Race") time(r) else time(r.optJSONObject(key))

    private fun weekend(r: JSONObject): Pair<Long, Long> {
        val first = SESSIONS.map { sessionTime(r, it.first) }.filter { it > 0 }.minOrNull() ?: time(r)
        return (first - DAY / 2) to (time(r) + DAY / 2)
    }

    /** From the title's words when it says, else the session nearest the broadcast. */
    private fun session(title: String, r: JSONObject, start: Long): String? {
        val t = title.lowercase()
        Regex("(practice|trening)\\s*(\\d)").find(t)?.let { return "Practice " + it.groupValues[2] }
        if ("sprint" in t && ("qual" in t || "kval" in t)) return "Sprint Qualifying"
        if ("sprint" in t) return "Sprint"
        if ("qual" in t || "kval" in t) return "Qualifying"
        if ("race" in t || "utrka" in t) return "Race"
        return SESSIONS.map { it.second to sessionTime(r, it.first) }.filter { it.second > 0 }
            .minByOrNull { Math.abs(it.second - start) }?.takeIf { Math.abs(it.second - start) < 3 * 3600_000L }?.first
    }

    @Volatile private var calendar: JSONArray? = null
    @Volatile private var calendarAt = 0L

    @Synchronized private fun races(): List<JSONObject> {
        val now = System.currentTimeMillis()
        val list = calendar?.takeIf { now - calendarAt < DAY } ?: try {
            val year = SimpleDateFormat("yyyy", Locale.US).format(java.util.Date())
            val got = JSONObject(get("https://api.jolpi.ca/ergast/f1/$year/races.json"))
                .getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races")
            Store.writeJson("f1-calendar.json", got); calendarAt = now; got
        } catch (e: Exception) {
            Log.w(TAG, "f1 calendar: ${e.message}")
            calendarAt = now - DAY + 3600_000L   // try again in an hour
            (Store.readJson("f1-calendar.json") as? JSONArray) ?: JSONArray()
        }
        calendar = list
        return (0 until list.length()).map { list.getJSONObject(it) }
    }

    /** The race page's header photo and its articles' photos, kept per race. */
    private fun pictures(r: JSONObject): List<String> {
        val key = r.optString("season") + "-" + r.optString("round")
        val known = (Store.readJson("f1-pictures.json") as? JSONObject) ?: JSONObject()
        known.optJSONObject(key)?.let { hit ->
            if (System.currentTimeMillis() - hit.optLong("at") < DAY) return hit.optJSONArray("urls")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        }
        val out = LinkedHashSet<String>()
        val page = page(r)
        try {
            val html = get("https://www.formula1.com/en/racing/${r.optString("season")}/$page")
            ogImage(html)?.let { out += it }
            val articles = Regex("/en/latest/article/[A-Za-z0-9.-]+").findAll(html).map { it.value }.distinct()
                .filterNot { "bet" in it || "quiz" in it }.take(8).toList()
            for (a in articles) {
                if (out.size >= SESSIONS.size) break
                runCatching { ogImage(get("https://www.formula1.com$a", head = true)) }.getOrNull()?.let { out += it }
            }
        } catch (e: Exception) { Log.w(TAG, "f1 pictures $page: ${e.message}") }
        if (out.isNotEmpty()) Store.writeJson("f1-pictures.json", known.put(key, JSONObject().put("at", System.currentTimeMillis()).put("urls", JSONArray(out.toList()))))
        return out.toList()
    }

    private fun page(r: JSONObject): String {
        val c = r.optJSONObject("Circuit") ?: return ""
        PAGE.entries.firstOrNull { it.key in c.optString("circuitId") }?.let { return it.value }
        val loc = c.optJSONObject("Location")
        return when (val place = slug(loc?.optString("locality").orEmpty())) {
            "las-vegas", "miami", "monaco", "madrid" -> if (place == "madrid") "spain" else place
            else -> slug(loc?.optString("country").orEmpty())
        }
    }

    private fun slug(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFKD)
        .replace(Regex("[^\\p{ASCII}]"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun ogImage(html: String): String? =
        Regex("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)").find(html)?.groupValues?.get(1)
            ?.takeUnless { Regex("default|logo|placeholder", RegexOption.IGNORE_CASE).containsMatchIn(it) }?.replace("&amp;", "&")
            ?.replace(Regex("/c_lfill,w_\\d+/"), "/c_lfill,w_1600/")   // the TV shows it at 1280 px, not 3392

    /** A page, or only its <head> (formula1.com pages run to half a megabyte). */
    private fun get(url: String, head: Boolean = false): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 12000; c.setRequestProperty("user-agent", UA)
        try {
            if (c.responseCode != 200) throw java.io.IOException("$url: ${c.responseCode}")
            if (!head) return c.inputStream.bufferedReader().readText()
            val sb = StringBuilder()
            c.inputStream.bufferedReader().use { r -> for (line in r.lineSequence()) { sb.append(line).append('\n'); if ("</head>" in line || sb.length > 300_000) break } }
            return sb.toString()
        } finally { c.disconnect() }
    }
}
