package dev.couchtv.ontv

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A real photo for an NK Osijek match: the club's newest article about that opponent on nk-osijek.hr
 * (previews, tickets, reports), its share picture. Each match gets its own, kept on the TV for a day.
 */
object Osijek {
    private const val TAG = "couchtv"
    private const val SITE = "https://nk-osijek.hr"
    private const val UA = "Mozilla/5.0 HomeTV-personal/2"
    // Words in a guide title that are not the opponent.
    private val NOT = setOf("osijek", "nk", "hnl", "supersport", "prva", "liga", "kup", "kolo", "uzivo", "live", "vs", "prijenos",
        "nogomet", "utakmica", "hrvatski", "hrvatske", "europa", "konferencijska", "conference", "league", "uefa", "kvalifikacije", "skupina")

    fun isOsijek(title: String) = "osijek" in title.lowercase()

    /** The photo for this match title, or null (the home screen then uses its own Osijek picture). */
    fun picture(title: String): String? {
        val who = opponent(title) ?: return null
        val known = (Store.readJson("osijek-pictures.json") as? JSONObject) ?: JSONObject()
        known.optJSONObject(who)?.let { if (System.currentTimeMillis() - it.optLong("at") < 86_400_000L) return it.optString("url").ifEmpty { null } }
        val url = try {
            // Newest first: the article id grows with each one.
            val links = listOf("$SITE/vijesti/", "$SITE/").flatMap { page ->
                Regex("href=\"(/vijesti/(\\d+)/([a-z0-9-]+)/)\"").findAll(get(page)).map { Triple(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3]) }.toList()
            }.distinctBy { it.second }.sortedByDescending { it.second }
            links.filter { who in it.third }.take(4).firstNotNullOfOrNull { ogImage(get(SITE + it.first)) }
        } catch (e: Exception) { Log.w(TAG, "osijek picture: ${e.message}"); null }
        Store.writeJson("osijek-pictures.json", known.put(who, JSONObject().put("url", url ?: "").put("at", System.currentTimeMillis())))
        return url
    }

    /** "SuperSport HNL: Osijek - Hajduk" to "hajduk"; "Slaven Belupo - Osijek" to "slaven-belupo". */
    private fun opponent(title: String): String? {
        val sides = title.substringAfterLast(':').split(Regex("\\s+(-|–|vs\\.?|v)\\s+", RegexOption.IGNORE_CASE))
        val other = sides.firstOrNull { !isOsijek(it) && it.isNotBlank() } ?: title
        val words = slug(other).split('-').filter { it.length > 1 && it !in NOT && !it.all(Char::isDigit) }
        return words.takeIf { it.isNotEmpty() }?.joinToString("-")
    }

    private fun slug(s: String) = java.text.Normalizer.normalize(s.lowercase().replace("đ", "d"), java.text.Normalizer.Form.NFKD)
        .replace(Regex("[^\\p{ASCII}]"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun ogImage(html: String) = Regex("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)").find(html)?.groupValues?.get(1)
        ?.takeUnless { "nophoto" in it || "logo" in it.lowercase() }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 10000; c.setRequestProperty("user-agent", UA)
        try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }
}
