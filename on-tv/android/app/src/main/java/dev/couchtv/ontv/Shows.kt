package dev.couchtv.ontv

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The home screen's shows: Popular on Netflix (JustWatch, this country), Trending Series and Movies
 * (Stremio's Cinemeta), and for each one where it opens: Netflix when TMDB lists it there for this
 * country, otherwise Stremio. Cached on the TV and refreshed every few hours.
 */
object Shows {
    private const val TAG = "couchtv"
    private const val FRESH_MS = 6 * 3600_000L
    private const val NETFLIX_TMDB = 8
    private const val FORMAT = 3      // bump when the cached shape changes (2: ratings, 3: all genres)
    private val pool = Executors.newFixedThreadPool(6)
    @Volatile private var refreshing = false

    /** The cached rows straight away (refreshing in the background when old), or a fresh fetch the first time. */
    fun rows(): JSONObject {
        val cached = (Store.readJson("shows.json") as? JSONObject)?.takeIf { it.optInt("v") == FORMAT }
        if (cached != null) {
            if (System.currentTimeMillis() - cached.optLong("at") > FRESH_MS) refreshLater()
            return cached
        }
        return refresh()
    }

    fun refreshLater() {
        if (refreshing) return
        refreshing = true
        Thread { try { refresh() } catch (e: Exception) { Log.w(TAG, "shows: ${e.message}") } finally { refreshing = false } }.start()
    }

    @Synchronized fun refresh(): JSONObject {
        val netflix = netflixPopular()
        val series = cinemeta("series")
        val movies = cinemeta("movie")
        val all = (netflix + series + movies).map { it.optString("id") }.distinct()
        val opens = all.map { id -> pool.submit(Callable { id to opensIn(id) }) }.associate { it.get() }
        for (c in netflix + series + movies) c.put("opens", opens[c.optString("id")] ?: "stremio")
        val out = JSONObject().put("at", System.currentTimeMillis()).put("v", FORMAT)
            .put("rows", JSONArray()
                .put(row("netflix", "Popular on Netflix", netflix))
                .put(row("series", "Trending Series", series))
                .put(row("movies", "Trending Movies", movies)))
        Store.writeJson("shows.json", out)
        return out
    }

    private fun row(id: String, title: String, items: List<JSONObject>) =
        JSONObject().put("id", id).put("title", title).put("items", JSONArray(items))

    // ---------------------------------------------------------------- sources

    private fun netflixPopular(): List<JSONObject> = try {
        val q = """query P(${'$'}country: Country!, ${'$'}first: Int!, ${'$'}filter: TitleFilter) {
            popularTitles(country: ${'$'}country, first: ${'$'}first, filter: ${'$'}filter, sortBy: POPULAR) { edges { node { objectType
            ... on MovieOrShow { content(country: ${'$'}country, language: "en") { externalIds { imdbId } } } } } } }"""
        val body = JSONObject().put("query", q).put("variables", JSONObject().put("country", Store.country()).put("first", 24)
            .put("filter", JSONObject().put("packages", JSONArray().put("nfx"))))
        val edges = JSONObject(http("https://apis.justwatch.com/graphql", body.toString()))
            .getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges")
        val ids = (0 until edges.length()).mapNotNull {
            val n = edges.getJSONObject(it).getJSONObject("node")
            val imdb = n.optJSONObject("content")?.optJSONObject("externalIds")?.optString("imdbId")?.takeIf { s -> s.startsWith("tt") }
            imdb?.let { i -> (if (n.optString("objectType") == "SHOW") "series" else "movie") to i }
        }
        ids.map { (kind, imdb) -> pool.submit(Callable { meta(kind, imdb) }) }.mapNotNull { it.get() }
    } catch (e: Exception) { Log.w(TAG, "netflix popular: ${e.message}"); emptyList() }

    private fun cinemeta(kind: String): List<JSONObject> = try {
        val metas = JSONObject(http("https://v3-cinemeta.strem.io/catalog/$kind/top.json")).getJSONArray("metas")
        (0 until minOf(30, metas.length())).map { card(metas.getJSONObject(it), kind) }   // extra, so hidden genres still leave full rows
    } catch (e: Exception) { Log.w(TAG, "cinemeta $kind: ${e.message}"); emptyList() }

    private fun meta(kind: String, imdb: String): JSONObject? = try {
        JSONObject(http("https://v3-cinemeta.strem.io/meta/$kind/$imdb.json")).optJSONObject("meta")?.let { card(it, kind) }
    } catch (e: Exception) { null }

    private fun card(m: JSONObject, kind: String): JSONObject {
        val id = m.optString("imdb_id").ifEmpty { m.optString("id") }
        val genres = m.optJSONArray("genres") ?: m.optJSONArray("genre") ?: JSONArray()
        return JSONObject().put("id", id).put("type", kind).put("title", m.optString("name"))
            .put("year", m.optString("releaseInfo").ifEmpty { m.optString("year") }.take(4))
            .put("genres", JSONArray((0 until genres.length()).map { genres.optString(it) }))
            .put("description", m.optString("description").take(240))
            .put("rating", m.optString("imdbRating"))
            .put("bg", "https://images.metahub.space/background/medium/$id/img")
            .put("logo", "https://images.metahub.space/logo/medium/$id/img")
    }

    /** "netflix" when TMDB lists the title on Netflix (subscription) in this country, else "stremio". */
    private fun opensIn(imdb: String): String {
        val key = BuildConfig.TMDB_KEY.ifEmpty { return "stremio" }
        return try {
            val f = JSONObject(http("https://api.themoviedb.org/3/find/$imdb?external_source=imdb_id&api_key=$key"))
            val (kind, hits) = if (f.getJSONArray("tv_results").length() > 0) "tv" to f.getJSONArray("tv_results") else "movie" to f.getJSONArray("movie_results")
            if (hits.length() == 0) return "stremio"
            val id = hits.getJSONObject(0).getInt("id")
            val flat = JSONObject(http("https://api.themoviedb.org/3/$kind/$id/watch/providers?api_key=$key"))
                .optJSONObject("results")?.optJSONObject(Store.country())?.optJSONArray("flatrate") ?: return "stremio"
            if ((0 until flat.length()).any { flat.getJSONObject(it).optInt("provider_id") == NETFLIX_TMDB }) "netflix" else "stremio"
        } catch (e: Exception) { "stremio" }
    }

    // ---------------------------------------------------------------- opening a show

    /** Netflix through JustWatch's deep link (the same path as "play"), Stremio straight to the show's page. */
    fun open(b: JSONObject): JSONObject {
        val id = b.optString("id"); val type = b.optString("type", "series"); val title = b.optString("title")
        val opens = b.optString("opens", "stremio")
        var used = opens
        if (opens == "netflix") {
            try { Finder.play(title, null, null, "netflix", null) }
            catch (e: Exception) { Log.w(TAG, "netflix open: ${e.message}"); used = "stremio"; openStremio(type, id) }
        } else openStremio(type, id)
        remember(b, used)
        return JSONObject().put("ok", true).put("opened", used)
    }

    private fun openStremio(type: String, id: String) {
        if (!Tv.hasPackage(STREMIO)) throw ApiError(404, "Stremio is not installed on the TV")
        Tv.startUri("stremio:///detail/$type/$id", STREMIO)
    }

    /** Shows opened from the home screen make up its Continue Watching row. */
    private fun remember(b: JSONObject, used: String) {
        Store.putStr("cw_removed", (removed() - b.optString("id")).joinToString(","))
        Store.addHistory(JSONObject().put("title", b.optString("title")).put("imdb", b.optString("id")).put("type", b.optString("type"))
            .put("bg", b.optString("bg")).put("logo", b.optString("logo")).put("year", b.optString("year")).put("rating", b.optString("rating"))
            .put("genres", b.optJSONArray("genres") ?: JSONArray()).put("description", b.optString("description"))
            .put("opens", used).put("package", if (used == "netflix") "com.netflix.ninja" else STREMIO)
            .put("service", used).put("when", Now.iso()).put("home", true))
    }

    /** Taken off Continue Watching by hand; it comes back when the show is opened again. */
    private fun removed(): MutableSet<String> = Store.str("cw_removed")?.split(',')?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()

    /**
     * Taken off Continue Watching by hand. A Watch Next card remembers when it was last watched, so it
     * comes back once its app says you watched it again; a show opened from here comes back when opened again.
     */
    fun forget(id: String, at: Long = 0) {
        if (id.isEmpty()) return
        if (id.startsWith("wn:")) {
            val m = wnRemoved().put(id, at)
            Store.writeJson("wn-removed.json", m)
            return
        }
        Store.putStr("cw_removed", (removed() + id).joinToString(","))
    }

    private fun wnRemoved(): JSONObject = (Store.readJson("wn-removed.json") as? JSONObject) ?: JSONObject()

    /**
     * What you are in the middle of: first what the TV's apps report (Android's Watch Next list, with
     * progress and a link to the episode), then shows opened from this home screen that are not there yet.
     */
    fun continueRow(): JSONObject {
        val items = JSONArray()
        val titles = HashSet<String>()
        val gone = wnRemoved()
        for (w in WatchNext.items()) {
            if (gone.has(w.optString("id")) && w.optLong("at") <= gone.optLong(w.optString("id"))) continue
            if (!titles.add(Finder.norm(w.optString("title")))) continue
            items.put(w)
        }
        val h = Store.history()
        val seen = HashSet<String>(removed())
        for (i in 0 until h.length()) {
            val it = h.getJSONObject(i)
            if (!it.optBoolean("home")) continue
            val id = it.optString("imdb")
            if (id.isEmpty() || !seen.add(id) || !titles.add(Finder.norm(it.optString("title")))) continue
            items.put(JSONObject().put("id", id).put("type", it.optString("type")).put("title", it.optString("title"))
                .put("year", it.optString("year")).put("genres", it.optJSONArray("genres") ?: JSONArray())
                .put("description", it.optString("description")).put("bg", it.optString("bg")).put("logo", it.optString("logo"))
                .put("rating", it.optString("rating"))
                .put("source", it.optString("opens")).put("opens", it.optString("opens")))
            if (items.length() >= 16) break
        }
        return JSONObject().put("id", "continue").put("title", "Continue Watching").put("items", items)
    }

    private const val STREMIO = "com.stremio.one"

    private fun http(url: String, post: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.setRequestProperty("user-agent", "couch-tv-on-tv/2")
        if (post != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(post.toByteArray()) }
        }
        try {
            if (c.responseCode !in 200..299) throw ApiError(502, "${URL(url).host} answered ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }
}
