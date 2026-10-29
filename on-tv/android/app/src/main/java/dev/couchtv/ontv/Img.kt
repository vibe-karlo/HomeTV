package dev.couchtv.ontv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Show artwork, shrunk to the size the home screen draws it and kept on the TV. A card shows a
 * backdrop 418 px wide: decoding the 1920 px original for it costs 8 MB of memory each, which is
 * what filled this 2 GB TV. Only the artwork hosts the home screen uses are fetched.
 */
object Img {
    private const val TAG = "couchtv"
    private const val MAX_BYTES = 150L * 1024 * 1024
    private val HOSTS = setOf("images.metahub.space", "images.justwatch.com", "image.tmdb.org", "images.rtl.hr", "image.dnevnik.hr", "media.formula1.com")
    private val WIDTHS = listOf(320, 480, 640, 1280, 1920)
    private lateinit var dir: File
    private val decode = Semaphore(2)                       // two decodes at a time keeps memory flat
    private val locks = ConcurrentHashMap<String, Any>()
    @Volatile private var written = 0

    fun init(c: Context) { dir = File(c.cacheDir, "img").apply { mkdirs() } }

    /**
     * Shrinks the artwork the home screen will ask for first: card backdrops and logos, and heroes.
     * Run right after the screen goes off (App), in the minute or two before deep standby.
     */
    fun warm() {
        val rows = (Store.readJson("shows.json") as? org.json.JSONObject)?.optJSONArray("rows") ?: return
        for (r in 0 until rows.length()) {
            val items = rows.getJSONObject(r).optJSONArray("items") ?: continue
            for (i in 0 until minOf(10, items.length())) {
                if (Tv.isOn) return                       // someone turned the TV on: stop
                val it = items.getJSONObject(i)
                get(it.optString("bg"), 480); get(it.optString("logo"), 320)
                if (i < 4) get(it.optString("bg"), 1280)
            }
        }
    }

    /** The image as WebP bytes at about this width, or null when it cannot be had. */
    fun get(url: String, wantW: Int): ByteArray? {
        val host = runCatching { URL(url).host }.getOrNull() ?: return null
        if (host !in HOSTS) return null
        val w = WIDTHS.firstOrNull { it >= wantW } ?: WIDTHS.last()
        val key = sha("$w|$url")
        val f = File(dir, "$key.webp")
        if (f.isFile) { f.setLastModified(System.currentTimeMillis()); return f.readBytes() }
        synchronized(locks.getOrPut(key) { Any() }) {
            if (f.isFile) return f.readBytes()
            val raw = fetch(url) ?: return null
            val out = decode.acquireAndRun { shrink(raw, w) } ?: return null
            val tmp = File(dir, "$key.tmp"); tmp.writeBytes(out); tmp.renameTo(f)
            if (++written % 40 == 0) trim()
            locks.remove(key)
            return out
        }
    }

    private fun shrink(raw: ByteArray, w: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scaled = if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, (bmp.height.toLong() * w / bmp.width).toInt(), true).also { bmp.recycle() } else bmp
        // Lossy WebP keeps logos' transparency and is a fraction of the JPEG/PNG size.
        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        return java.io.ByteArrayOutputStream().also { scaled.compress(fmt, 82, it); scaled.recycle() }.toByteArray()
    }

    private fun fetch(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000; c.instanceFollowRedirects = true
        c.setRequestProperty("user-agent", "couch-tv-on-tv/2")
        return try { if (c.responseCode == 200) c.inputStream.readBytes() else null }
        catch (e: Exception) { Log.w(TAG, "img $url: ${e.message}"); null }
        finally { c.disconnect() }
    }

    /** Oldest first, until the cache is under its size. */
    private fun trim() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= MAX_BYTES) break; total -= f.length(); f.delete() }
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private inline fun <T> Semaphore.acquireAndRun(block: () -> T): T { acquire(); try { return block() } finally { release() } }
}
