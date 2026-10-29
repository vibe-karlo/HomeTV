package dev.couchtv.ontv

import android.content.Context
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** The TV's apps and their landscape banners, for the home screen's dock and the phone's app list. */
object AppArt {
    private lateinit var ctx: Context
    private val banners = ConcurrentHashMap<String, ByteArray>()
    private val icons = ConcurrentHashMap<String, ByteArray>()
    private val FLAP = Color.parseColor("#131614")

    // The app list and its names, read once and again only when an app is installed, updated or removed:
    // reading every app's name is slow on this TV, and the dock, the phone and every banner asked for it.
    @Volatile private var apps: List<ResolveInfo>? = null
    private val labels = ConcurrentHashMap<String, String>()

    fun init(c: Context) {
        ctx = c.applicationContext
        val f = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED); addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
            addAction(android.content.Intent.ACTION_PACKAGE_CHANGED); addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ctx.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                apps = null; labels.clear()
                i.data?.schemeSpecificPart?.let { banners.remove(it); icons.remove(it) }
            }
        }, f)
    }

    fun tvApps(): List<ResolveInfo> = apps ?: Tv.tvLaunchables().filter { it.activityInfo.packageName != ctx.packageName }
        .sortedBy { label(it).lowercase() }.also { apps = it }

    fun label(ri: ResolveInfo) = labels.getOrPut(ri.activityInfo.packageName) { ri.loadLabel(ctx.packageManager).toString() }

    fun banner(pkg: String): ByteArray? {
        banners[pkg]?.let { return it }
        val pm = ctx.packageManager
        val ri = tvApps().firstOrNull { it.activityInfo.packageName == pkg } ?: return null
        // The dock draws a banner about 260 px wide: 400 leaves room for its focus zoom, at a third of 640's pixels.
        val bmp = Bitmap.createBitmap(400, 225, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(FLAP)
        val banner = ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm)
        if (banner != null) { banner.setBounds(0, 0, 400, 225); banner.draw(c) } else iconTile(ri.loadIcon(pm), c)
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it); bmp.recycle() }.toByteArray()
        banners[pkg] = bytes
        return bytes
    }

    private fun iconTile(icon: Drawable, c: Canvas) {
        val n = 432
        val sq = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val sc = Canvas(sq)
        if (icon is AdaptiveIconDrawable) {
            icon.background?.apply { setBounds(0, 0, n, n); draw(sc) }; icon.foreground?.apply { setBounds(0, 0, n, n); draw(sc) }
        } else { icon.setBounds(0, 0, n, n); icon.draw(sc) }
        val edge = sq.getPixel(2, n / 2)
        c.drawColor(edge)
        val size = 138   // the icon centred on the 400 x 225 banner, at the same share of it as before
        c.drawBitmap(sq, null, Rect(200 - size / 2, 112 - size / 2, 200 + size / 2, 112 + size / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        sq.recycle()
    }

    fun icon(pkg: String): ByteArray? {
        icons[pkg]?.let { return it }
        val d = try { ctx.packageManager.getApplicationIcon(pkg) } catch (e: Exception) { return null }
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 128, 128); d.draw(Canvas(bmp))
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray()
        icons[pkg] = bytes
        return bytes
    }
}
