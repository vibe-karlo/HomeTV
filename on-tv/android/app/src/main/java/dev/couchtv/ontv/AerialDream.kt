package dev.couchtv.ontv

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The screensaver: the aerials kept on the TV, with only the time over them. */
class AerialDream : DreamService() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var video: VideoView
    private lateinit var veil: View
    private lateinit var place: TextView
    private lateinit var caption: TextView
    private lateinit var clock: TextView
    private var clips: List<Pair<java.io.File, JSONObject>> = emptyList()
    private var idx = -1
    private var current: JSONObject? = null
    private var shownPoi = -2

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        veil = View(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(veil, FrameLayout.LayoutParams(-1, -1))

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        place = text(30f, Typeface.BOLD, 1f)
        caption = text(17f, Typeface.NORMAL, 0.78f).apply { setPadding(0, dp(6), 0, 0); maxWidth = dp(520) }
        info.addView(place); info.addView(caption)
        root.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(56), dp(48), 0, 0) })
        clock = text(30f, Typeface.BOLD, 1f)
        root.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(48), dp(56), 0) })
        info.visibility = View.GONE   // only the videos and the time, no words over them
        setContentView(root)

        video.setOnPreparedListener { mp -> mp.setVolume(0f, 0f); mp.start(); veil.animate().alpha(0f).setDuration(1400).start() }
        video.setOnCompletionListener { next() }
        video.setOnErrorListener { _, _, _ -> main.postDelayed({ next() }, 1000); true }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        dreaming = true
        clips = Aerials.cachedFiles().shuffled()
        next()
        main.post(tick)
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        dreaming = false
        main.removeCallbacksAndMessages(null)
        runCatching { video.stopPlayback() }
    }

    private fun next() {
        if (clips.isEmpty()) { veil.alpha = 0f; place.text = ""; caption.text = ""; return }
        veil.animate().alpha(1f).setDuration(600).withEndAction {
            idx = (idx + 1) % clips.size
            val (f, c) = clips[idx]
            current = c; shownPoi = -2
            place.text = c.optString("label"); caption.text = ""
            video.setVideoPath(f.absolutePath)
        }.start()
    }

    private val tick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat(if (Prefs.json().optString("clock") == "12h") "h:mm a" else "H:mm", Locale.getDefault()).format(Date())
            current?.optJSONArray("poi")?.let { poi ->
                val t = runCatching { video.currentPosition / 1000 }.getOrDefault(0)
                var i = -1
                for (k in 0 until poi.length()) if (poi.getJSONArray(k).getInt(0) <= t) i = k
                if (i != shownPoi) {
                    shownPoi = i
                    val words = if (i >= 0) poi.getJSONArray(i).getString(1) else ""
                    caption.animate().alpha(0f).setDuration(400).withEndAction { caption.text = words; caption.animate().alpha(0.78f).setDuration(500).start() }.start()
                }
            }
            main.postDelayed(this, 1000)
        }
    }

    private fun text(sp: Float, style: Int, alpha: Float) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTypeface(Typeface.create("sans-serif", style)); setTextColor(Color.WHITE)
        this.alpha = alpha; setShadowLayer(dp(10).toFloat(), 0f, dp(1).toFloat(), Color.argb(115, 0, 0, 0))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** On screen now: the home screen behind it keeps its page, since waking goes straight back to it. */
        @Volatile var dreaming = false
            private set
    }
}
