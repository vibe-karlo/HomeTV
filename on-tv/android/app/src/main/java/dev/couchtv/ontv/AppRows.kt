package dev.couchtv.ontv

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.tv.TvContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The rows apps put on Android TV's home screen ("preview channels"): MAXtv's "Sljedeće", EON's
 * picks, Netflix's and YouTube's rows. Each card keeps the app's own artwork and a link that opens it
 * in that app. Read with the same TV listings permission as Watch Next. Which rows show is chosen in
 * Settings, App Rows; until then, the rows each app asked Android TV to show.
 * Adapted from Talkies' Channels (MIT, TechPP).
 */
object AppRows {
    private const val TAG = "couchtv"
    private const val MAX_ITEMS = 16
    private const val ENGAGED = "last_engagement_time_utc_millis"
    private lateinit var ctx: Context
    private val dir by lazy { File(ctx.filesDir, "approws").apply { mkdirs() } }
    @Volatile private var links: Map<String, Pair<String, String?>> = emptyMap()   // card id to (package, intent URI)
    @Volatile private var at = 0L
    @Volatile private var cached: JSONArray = JSONArray()

    fun init(c: Context) { ctx = c.applicationContext }

    data class Row(val key: String, val pkg: String, val name: String, val title: String, val id: Long, val count: Int, val browsable: Boolean)

    /** Every app row with something in it, by app then name. */
    fun list(): List<Row> {
        if (!WatchNext.allowed()) return emptyList()
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, Row>()
        val cols = arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_TYPE, TvContract.Channels.COLUMN_BROWSABLE)
        try {
            ctx.contentResolver.query(TvContract.Channels.CONTENT_URI, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(3) != TvContract.Channels.TYPE_PREVIEW) continue
                    val pkg = c.getString(1) ?: continue
                    val name = c.getString(2) ?: continue
                    if (pkg == ctx.packageName || pm.getLeanbackLaunchIntentForPackage(pkg) == null) continue
                    val id = c.getLong(0)
                    val n = count(id)
                    if (n == 0) continue
                    val key = "$pkg|$name"
                    val old = found[key]
                    if (old != null) { if (n > old.count) found[key] = old.copy(id = id, count = n); continue }   // some apps publish a row twice
                    val app = label(pkg)
                    val title = if (name.equals(app, true) || app.lowercase().contains(name.lowercase())) app else "$app · $name"
                    found[key] = Row(key, pkg, name, title, id, n, !c.isNull(4) && c.getInt(4) == 1)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "app rows: ${e.message}") }
        return found.values.sortedWith(compareBy({ it.pkg }, { it.name }))
    }

    /** The rows to show: the ones picked in Settings, or (nothing picked yet) the ones the apps asked for. Read at most every 2 min. */
    @Synchronized fun shown(): JSONArray {
        if (System.currentTimeMillis() - at < 120_000) return JSONArray(cached.toString())
        val picked = Prefs.json().optJSONArray("appRows")?.let { a -> (0 until a.length()).map { a.optString(it) } }
        val keep = HashSet<String>()
        val newLinks = HashMap<String, Pair<String, String?>>()
        val out = JSONArray()
        val all = list()
        val rows = if (picked != null) picked.mapNotNull { k -> all.firstOrNull { it.key == k } } else all.filter { it.browsable }
        for (r in rows) {
            val items = items(r, keep, newLinks)
            if (items.length() > 0) out.put(JSONObject().put("id", "app:" + r.key).put("title", r.title).put("items", items))
        }
        links = newLinks
        dir.listFiles()?.forEach { if (it.name !in keep && !it.name.endsWith(".part")) it.delete() }
        cached = out; at = System.currentTimeMillis()
        return JSONArray(out.toString())
    }

    /** For Settings: every row, and whether it shows. */
    fun choices(): JSONArray {
        val picked = Prefs.json().optJSONArray("appRows")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
        return JSONArray(list().map { r ->
            JSONObject().put("key", r.key).put("title", r.title).put("app", label(r.pkg)).put("count", r.count)
                .put("shown", picked?.contains(r.key) ?: r.browsable)
        })
    }

    fun changed() { at = 0 }

    private fun items(r: Row, keep: MutableSet<String>, newLinks: MutableMap<String, Pair<String, String?>>): JSONArray {
        val out = JSONArray()
        try {
            ctx.contentResolver.query(TvContract.buildPreviewProgramsUriForChannel(r.id), null, null, null, null)?.use { c ->
                val col = { n: String -> c.getColumnIndex(n) }
                val iTitle = col(TvContract.PreviewPrograms.COLUMN_TITLE); val iDesc = col(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION)
                val iPoster = col(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI); val iThumb = col(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI)
                val iLink = col(TvContract.PreviewPrograms.COLUMN_INTENT_URI); val iBrowsable = col(TvContract.PreviewPrograms.COLUMN_BROWSABLE)
                val iSeason = col(TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER); val iEp = col(TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER)
                val iEpTitle = col(TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE); val iWeight = col(TvContract.PreviewPrograms.COLUMN_WEIGHT)
                val iLive = col(TvContract.PreviewPrograms.COLUMN_LIVE); val iStart = col(TvContract.PreviewPrograms.COLUMN_START_TIME_UTC_MILLIS)
                val iEnd = col(TvContract.PreviewPrograms.COLUMN_END_TIME_UTC_MILLIS); val iEngaged = col(ENGAGED)
                // What you watched last first, where the app says when; then the app's own weight; then its order.
                val order = mutableListOf<Triple<Int, Long, Long>>()
                while (c.moveToNext()) {
                    if (iBrowsable >= 0 && !c.isNull(iBrowsable) && c.getInt(iBrowsable) == 0) continue
                    order += Triple(c.position, num(c, iEngaged), num(c, iWeight))
                }
                order.sortWith(compareByDescending<Triple<Int, Long, Long>> { it.second }.thenByDescending { it.third })
                for ((pos) in order) {
                    if (out.length() >= MAX_ITEMS) break
                    c.moveToPosition(pos)
                    val title = str(c, iTitle)
                    if (title.isEmpty()) continue
                    val season = str(c, iSeason); val ep = str(c, iEp); val epTitle = str(c, iEpTitle)
                    val episode = when {
                        season.isNotEmpty() && ep.isNotEmpty() -> "S$season E$ep"
                        ep.isNotEmpty() -> "Episode $ep"
                        else -> ""
                    }
                    val artUri = str(c, iThumb).ifEmpty { str(c, iPoster) }
                    val art = if (artUri.isEmpty()) null else WatchNext.cachedArt(dir, artUri, 960)
                    art?.let { keep.add(it) }
                    val id = "ar:" + Integer.toHexString("${r.key}\n$title\n$artUri".hashCode())
                    newLinks[id] = r.pkg to str(c, iLink).ifEmpty { null }
                    val start = num(c, iStart); val end = num(c, iEnd)
                    out.put(JSONObject().put("id", id).put("kind", "approw").put("title", title)
                        .put("episode", episode).put("episode_title", if (epTitle != title) epTitle else "")
                        .put("description", str(c, iDesc).take(240)).put("package", r.pkg).put("service", label(r.pkg))
                        .put("live", iLive >= 0 && !c.isNull(iLive) && c.getInt(iLive) == 1)
                        .put("start", if (start > 0) start else JSONObject.NULL).put("stop", if (end > 0) end else JSONObject.NULL)
                        .put("bg", art?.let { "/approws/art/$it" } ?: JSONObject.NULL).put("genres", JSONArray()))
                }
            }
        } catch (e: Exception) { Log.w(TAG, "app row ${r.key}: ${e.message}") }
        return out
    }

    /** Opens a card in its app, at the title the app linked; the app itself when it gave no link. */
    fun open(id: String): JSONObject {
        if (id !in links) { changed(); shown() }
        val (pkg, uri) = links[id] ?: throw ApiError(404, "That is no longer in the row")
        if (uri != null) {
            try {
                val i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                if (i.`package` == null && i.component == null) i.setPackage(pkg)
                Tv.startIntent(i)
                return JSONObject().put("ok", true).put("opened", pkg)
            } catch (e: Exception) { Log.w(TAG, "app row open: ${e.message}") }
        }
        Tv.launchPackage(pkg)
        return JSONObject().put("ok", true).put("opened", pkg)
    }

    fun art(name: String): File? = File(dir, name).takeIf { it.isFile && it.parentFile == dir }

    private fun count(id: Long): Int = try {
        ctx.contentResolver.query(TvContract.buildPreviewProgramsUriForChannel(id), arrayOf(TvContract.PreviewPrograms._ID), null, null, null)
            ?.use { it.count } ?: 0
    } catch (e: Exception) { 0 }

    private fun label(pkg: String) = Apps.BY_PACKAGE[pkg]?.name ?: try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString().trim()
    } catch (e: Exception) { pkg }

    private fun num(c: Cursor, i: Int) = if (i < 0 || c.isNull(i)) 0L else c.getLong(i)
    private fun str(c: Cursor, i: Int) = if (i < 0 || c.isNull(i)) "" else (c.getString(i) ?: "").trim().takeIf { it != "null" } ?: ""
}
