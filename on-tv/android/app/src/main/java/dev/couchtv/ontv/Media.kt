package dev.couchtv.ontv

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Android's notification access, used only to see media sessions. HomeTV reads no notifications.
 * Granted once over ADB (Adb.onConnected), or by hand under Settings, Apps, Special app access.
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() { Media.changed() }
}

/**
 * What is playing, straight from the app's media session: title, episode or artist, artwork,
 * position and length, and the controls the app offers (play, pause, skip, seek). No ADB needed
 * once notification access is on; without it Now falls back to the dumpsys reading.
 * Idea from Talkies (Now playing, MIT, TechPP), which took it from Luma.
 */
object Media {
    private const val TAG = "couchtv"
    private lateinit var ctx: Context
    private val manager by lazy { ctx.getSystemService(MediaSessionManager::class.java) }
    private val listener by lazy { ComponentName(ctx, MediaListener::class.java) }
    @Volatile private var artKey: String? = null
    @Volatile private var artBytes: ByteArray? = null

    fun init(c: Context) { ctx = c.applicationContext }

    fun allowed(): Boolean = try {
        val s = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
        s.split(':').any { ComponentName.unflattenFromString(it) == listener }
    } catch (e: Exception) { false }

    fun changed() = runCatching { Now.push() }

    private fun controllers(): List<MediaController> = try {
        if (!allowed()) emptyList() else manager.getActiveSessions(listener)
    } catch (e: SecurityException) { emptyList() }

    /** The session to show: the front app's, else whichever is playing. */
    fun session(front: String?): MediaController? {
        val all = controllers().filter { it.packageName !in Apps.HOME_PACKAGES && it.packageName != ctx.packageName }
        val live = { c: MediaController -> c.playbackState?.state.let { it != null && it != PlaybackState.STATE_NONE && it != PlaybackState.STATE_STOPPED } }
        return all.firstOrNull { it.packageName == front && live(it) }
            ?: all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull(live)
    }

    /** Now's "playing" block, or null when no session says anything (Now then tries dumpsys). */
    fun playing(front: String?): JSONObject? {
        val c = session(front) ?: return null
        val md = c.metadata
        val ps = c.playbackState ?: return null
        val title = md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() }
            ?: md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        val sub = md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)?.takeIf { it.isNotBlank() }
            ?: md?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
        val state = when (ps.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING, PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "buffering"
            else -> "stopped"
        }
        var pos = ps.position
        if (pos >= 0 && ps.state == PlaybackState.STATE_PLAYING && ps.lastPositionUpdateTime > 0)
            pos += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * (if (ps.playbackSpeed == 0f) 1f else ps.playbackSpeed)).toLong()
        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val a = ps.actions
        val can = JSONObject()
            .put("toggle", a and (PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE) != 0L)
            .put("next", a and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
            .put("previous", a and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
            .put("seek", a and (PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND) != 0L || dur > 0)
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val key = "${c.packageName}|$title|$sub"
        if (art != null && key != artKey) {
            artBytes = jpeg(art); artKey = key
        } else if (art == null && key != artKey) { artBytes = null; artKey = key }
        return JSONObject().put("title", title ?: JSONObject.NULL).put("subtitle", sub ?: JSONObject.NULL)
            .put("package", c.packageName).put("service", Apps.BY_PACKAGE[c.packageName]?.id ?: JSONObject.NULL)
            .put("state", state).put("position_ms", if (pos >= 0) pos else JSONObject.NULL)
            .put("duration_ms", if (dur > 0) dur else JSONObject.NULL)
            .put("art", if (artBytes != null) "/now/art?k=" + Integer.toHexString(key.hashCode()) else JSONObject.NULL)
            .put("can", can).put("source", "session")
    }

    fun art(): ByteArray? = artBytes

    /**
     * play, pause, toggle, next, previous, forward, back (10 s; 30 s with "long"). Through the
     * session when there is one; otherwise as media keys, which needs ADB.
     */
    fun control(action: String, front: String?) {
        val c = session(front)
        if (c == null) {
            val k = when (action) {
                "toggle" -> "play_pause"; "play" -> "play"; "pause" -> "pause"; "next" -> "next"; "previous" -> "previous"
                "forward" -> "fast_forward"; "back" -> "rewind"
                else -> throw ApiError(400, "unknown action: $action")
            }
            Tv.key(k); return
        }
        val t = c.transportControls
        val ps = c.playbackState
        when (action) {
            "toggle" -> if (ps?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            "play" -> t.play()
            "pause" -> t.pause()
            "next" -> t.skipToNext()
            "previous" -> t.skipToPrevious()
            "forward", "back" -> {
                val step = if (action == "forward") 10_000L else -10_000L
                val pos = ps?.position ?: -1
                if (pos >= 0 && ps != null && ps.actions and PlaybackState.ACTION_SEEK_TO != 0L) {
                    val now = pos + if (ps.state == PlaybackState.STATE_PLAYING) SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime else 0
                    t.seekTo(maxOf(0, now + step))
                } else if (action == "forward") t.fastForward() else t.rewind()
            }
            else -> throw ApiError(400, "unknown action: $action")
        }
        Log.i(TAG, "media $action on ${c.packageName}")
        Thread { Thread.sleep(350); changed() }.start()
    }

    private fun jpeg(b: Bitmap): ByteArray? = try {
        val s = if (b.width > 480) Bitmap.createScaledBitmap(b, 480, maxOf(1, b.height * 480 / b.width), true) else b
        ByteArrayOutputStream().also { s.compress(Bitmap.CompressFormat.JPEG, 84, it) }.toByteArray()
    } catch (e: Exception) { null }
}
