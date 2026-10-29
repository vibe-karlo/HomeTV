package dev.couchtv.ontv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android TV's Watch Next list: what Netflix, YouTube and other apps say you are in the middle of,
 * with a link that opens that title or episode. Only "continue" and "next episode" entries count;
 * new seasons and watchlist entries are promotions. Needs READ_TV_LISTINGS (asked for on the TV,
 * and granted over ADB when that is connected). Adapted from Talkies' WatchNext (MIT, TechPP).
 */
object WatchNext {
    private const val TAG = "couchtv"
    private const val MAX = 16
    private lateinit var ctx: Context
    private val dir by lazy { File(ctx.filesDir, "watchnext").apply { mkdirs() } }
    @Volatile private var at = 0L
    @Volatile private var cached: List<JSONObject> = emptyList()
    @Volatile private var links: Map<String, Pair<String, String?>> = emptyMap()   // id to (package, intent URI)

    const val PERMISSION = "android.permission.READ_TV_LISTINGS"

    fun init(c: Context) { ctx = c.applicationContext }

    fun allowed(): Boolean = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** The list, read again at most every 20 s (the home screen asks each time it comes back). */
    @Synchronized fun items(): List<JSONObject> {
        if (System.currentTimeMillis() - at < 20_000) return cached.map { JSONObject(it.toString()) }
        cached = read(); at = System.currentTimeMillis()
        return cached.map { JSONObject(it.toString()) }
    }

    private fun read(): List<JSONObject> {
        if (!allowed()) return emptyList()
        val cols = arrayOf(
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME, TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI, TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION)
        val out = mutableListOf<JSONObject>()
        val keep = HashSet<String>()
        val newLinks = HashMap<String, Pair<String, String?>>()
        try {
            ctx.contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")?.use { c ->
                while (c.moveToNext() && out.size < MAX) {
                    val pkg = c.getString(0) ?: continue
                    val type = if (c.isNull(5)) -1 else c.getInt(5)
                    if (type != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE && type != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT) continue
                    if (!c.isNull(11) && c.getInt(11) == 0) continue
                    if (ctx.packageManager.getLeanbackLaunchIntentForPackage(pkg) == null) continue
                    val title = str(c, 1)
                    if (title.isEmpty()) continue
                    val season = str(c, 3); val number = str(c, 4); val epTitle = str(c, 2)
                    val ep = when {
                        season.isNotEmpty() && number.isNotEmpty() -> "S$season E$number"
                        number.isNotEmpty() -> "Episode $number"
                        epTitle.isNotEmpty() && epTitle != title -> epTitle
                        else -> ""
                    }
                    val id = "wn:" + Integer.toHexString("$pkg\n$title".hashCode())
                    if (out.any { it.optString("id") == id }) continue   // one card per show
                    val pos = if (c.isNull(7)) 0L else c.getLong(7)
                    val dur = if (c.isNull(12)) 0L else c.getLong(12)
                    val artUri = str(c, 8).ifEmpty { str(c, 9) }
                    val art = if (artUri.isEmpty()) null else cachedArt(dir, artUri, 960)
                    art?.let { keep.add(it) }
                    val app = Apps.BY_PACKAGE[pkg]?.name ?: label(pkg)
                    newLinks[id] = pkg to str(c, 10).ifEmpty { null }
                    out += JSONObject().put("id", id).put("kind", "watchnext").put("title", title)
                        .put("episode", ep).put("episode_title", if (epTitle != title) epTitle else "")
                        .put("description", str(c, 13).take(240))
                        .put("package", pkg).put("service", app)
                        .put("progress", if (pos > 0 && dur > 0) (pos.toDouble() / dur).coerceIn(0.0, 1.0) else JSONObject.NULL)
                        .put("at", if (c.isNull(6)) 0L else c.getLong(6))
                        .put("bg", art?.let { "/watchnext/art/$it" } ?: JSONObject.NULL)
                        .put("genres", JSONArray())
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "watch next: no permission (${e.message})"); return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "watch next: ${e.message}"); return emptyList()
        }
        links = newLinks
        dir.listFiles()?.forEach { if (it.name !in keep && !it.name.endsWith(".part")) it.delete() }
        return out
    }

    /** Opens the title in its app, at the episode the app named; the app itself when it gave no link. */
    fun open(id: String): JSONObject {
        if (id !in links) items()
        val (pkg, uri) = links[id] ?: throw ApiError(404, "That is no longer in Continue Watching")
        if (uri != null) {
            try {
                val i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                if (i.`package` == null && i.component == null) i.setPackage(pkg)
                Tv.startIntent(i)
                return JSONObject().put("ok", true).put("opened", pkg)
            } catch (e: Exception) { Log.w(TAG, "watch next open: ${e.message}") }
        }
        Tv.launchPackage(pkg)
        return JSONObject().put("ok", true).put("opened", pkg)
    }

    fun art(name: String): File? = File(dir, name).takeIf { it.isFile && it.parentFile == dir }

    private fun label(pkg: String) = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString().trim()
    } catch (e: Exception) { pkg }

    private fun str(c: android.database.Cursor, i: Int) = if (c.isNull(i)) "" else (c.getString(i) ?: "").trim()

    /** The artwork's file name when it is already in `dir`, without fetching anything. */
    fun cachedOnly(dir: File, uri: String, width: Int): String? =
        (Integer.toHexString(uri.hashCode()) + "_$width.jpg").takeIf { File(dir, it).isFile }

    /** The artwork as a JPEG of at most `width` px in `dir`, fetched once per address. Content URIs only open here, on the TV. */
    fun cachedArt(dir: File, uri: String, width: Int): String? {
        val name = Integer.toHexString(uri.hashCode()) + "_$width.jpg"
        val f = File(dir, name)
        if (f.isFile) return name
        return try {
            stream(uri)?.use { input ->
                val b = BitmapFactory.decodeStream(input) ?: return null
                val s = if (b.width > width) Bitmap.createScaledBitmap(b, width, maxOf(1, b.height * width / b.width), true) else b
                val tmp = File(dir, "$name.part")
                tmp.outputStream().use { s.compress(Bitmap.CompressFormat.JPEG, 84, it) }
                if (tmp.renameTo(f)) name else null
            }
        } catch (e: Exception) { Log.w(TAG, "watch next art: ${e.javaClass.simpleName}"); null }
    }

    private fun stream(uri: String): InputStream? {
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            val h = URL(uri).openConnection() as HttpURLConnection
            h.connectTimeout = 10_000; h.readTimeout = 15_000
            return h.inputStream
        }
        return ctx.contentResolver.openInputStream(Uri.parse(uri))
    }
}
