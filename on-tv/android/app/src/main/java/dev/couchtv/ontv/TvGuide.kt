package dev.couchtv.ontv

import android.content.Context
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The TV's own channel list and guide, as apps put them into Android's TV listings. Readable with
 * READ_EPG_DATA, which this TV hands to any app that asks (a "normal" permission here). On this TV:
 * MAXtv's 245 channels with their official logos (in internal_provider_data) but no programmes, and
 * the tuner's channels, empty with no antenna. EON puts nothing here.
 */
object TvGuide {
    private const val TAG = "couchtv"
    private const val PERMISSION = "com.android.providers.tv.permission.READ_EPG_DATA"
    private lateinit var ctx: Context

    fun init(c: Context) { ctx = c.applicationContext }

    fun allowed() = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Every broadcast channel (not the apps' preview rows), in the TV's own order. */
    fun channels(): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val cols = arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INPUT_ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER,
            TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_TYPE, TvContract.Channels.COLUMN_BROWSABLE,
            TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_SERVICE_TYPE)
        try {
            ctx.contentResolver.query(TvContract.Channels.CONTENT_URI, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(4) == TvContract.Channels.TYPE_PREVIEW) continue
                    val id = c.getLong(0)
                    out += JSONObject().put("id", id).put("input", c.getString(1) ?: "").put("number", c.getString(2) ?: "")
                        .put("name", c.getString(3) ?: "").put("type", c.getString(4) ?: "").put("browsable", c.getInt(5) == 1)
                        .put("package", c.getString(6) ?: "").put("service", c.getString(7) ?: "")
                        .put("logo", TvContract.buildChannelLogoUri(id).toString())
                }
            }
        } catch (e: Exception) { Log.w(TAG, "tv guide channels: ${e.message}") }
        return out.sortedWith(compareBy({ it.optString("number").toDoubleOrNull() ?: 9e9 }, { it.optString("name") }))
    }

    /** What is on now and next on each channel, from the guide its app or the tuner collected. */
    fun nowNext(hours: Int = 4, all: Boolean = false): JSONArray {
        val now = System.currentTimeMillis()
        val cols = arrayOf(TvContract.Programs.COLUMN_TITLE, TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
            TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, TvContract.Programs.COLUMN_SHORT_DESCRIPTION, TvContract.Programs.COLUMN_POSTER_ART_URI,
            TvContract.Programs.COLUMN_THUMBNAIL_URI, TvContract.Programs.COLUMN_CANONICAL_GENRE)
        // The guide takes no filter from other apps: ask channel by channel for a time window instead.
        return JSONArray(channels().filter { all || it.optBoolean("browsable") }.map { ch ->
            val p = ArrayList<JSONObject>()
            try {
                ctx.contentResolver.query(TvContract.buildProgramsUriForChannel(ch.optLong("id"), now, now + hours * 3600_000L), cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) p += JSONObject().put("title", c.getString(0) ?: "").put("start", c.getLong(1)).put("stop", c.getLong(2))
                        .put("desc", c.getString(3) ?: "").put("image", c.getString(4) ?: c.getString(5) ?: "").put("genre", c.getString(6) ?: "")
                }
            } catch (e: Exception) { Log.w(TAG, "tv guide ${ch.optString("name")}: ${e.message}") }
            p.sortBy { it.optLong("start") }
            JSONObject(ch.toString()).put("now", p.getOrNull(0) ?: JSONObject.NULL).put("next", p.getOrNull(1) ?: JSONObject.NULL)
        })
    }
}
