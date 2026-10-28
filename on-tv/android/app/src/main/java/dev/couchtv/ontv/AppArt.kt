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

    fun init(c: Context) { ctx = c.applicationContext }

    fun tvApps(): List<ResolveInfo> = Tv.tvLaunchables().filter { it.activityInfo.packageName != ctx.packageName }
        .sortedBy { it.loadLabel(ctx.packageManager).toString().lowercase() }

    fun label(ri: ResolveInfo) = ri.loadLabel(ctx.packageManager).toString()

    fun banner(pkg: String): ByteArray? {
        banners[pkg]?.let { return it }
        val pm = ctx.packageManager
        val ri = tvApps().firstOrNull { it.activityInfo.packageName == pkg } ?: return null
        val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(FLAP)
        val banner = ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm)
        if (banner != null) { banner.setBounds(0, 0, 640, 360); banner.draw(c) } else iconTile(ri.loadIcon(pm), c)
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
        val size = 220
        c.drawBitmap(sq, null, Rect(320 - size / 2, 180 - size / 2, 320 + size / 2, 180 + size / 2), Paint(Paint.FILTER_BITMAP_FLAG))
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
