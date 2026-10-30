package dev.couchtv.ontv

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * The TV home screen: the same page as the computer-bridge mode (tv-home/web), served by this
 * app's own server at 127.0.0.1. It is the home screen only when "Use as home screen" is switched
 * on in setup (the HOME filter is on an alias that is off by default).
 */
class HomeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var ready = false
    private val base get() = "http://127.0.0.1:${Store.port}"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        App.startService(this)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().setCookie(base, "couchtv_token=${Auth.internalToken}; path=/")
        build()
        hideBars()
        // Continue Watching reads Android's Watch Next list. ADB grants this silently; without ADB, ask once.
        if (!WatchNext.allowed() && !Store.bool("asked_tv_listings")) {
            Store.putBool("asked_tv_listings", true)
            runCatching { requestPermissions(arrayOf(WatchNext.PERMISSION), 7) }
        }
    }

    private fun build() {
        val w = WebView(this).apply {
            setBackgroundColor(GROUND)
            isFocusable = true; isFocusableInTouchMode = true
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.textZoom = 100
            addJavascriptInterface(Native(), "TVOS")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { ready = true; view.requestFocus() }
                override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                    // The server may still be starting: try again shortly.
                    if (req.isForMainFrame) { ready = false; main.postDelayed({ load() }, 700) }
                }
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = true
                // Android may end the page's renderer when memory runs out. Build a fresh WebView
                // instead of crashing or leaving a dead screen; while away, only when Home is back (onStart).
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    if (view !== web) return true   // a page let go on purpose (release): nothing to rebuild
                    android.util.Log.w("couchtv", "home renderer gone (crash=${detail.didCrash()}), rebuilding")
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                    view.destroy(); web = null; ready = false
                    if (shown) main.post { recreate() }
                    return true
                }
                override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                    val u = req.url
                    if (u.host != "tvos.invalid") return null
                    val pkg = u.lastPathSegment ?: return null
                    val isIcon = u.pathSegments.firstOrNull() == "icon"
                    val img = (if (isIcon) AppArt.icon(pkg) else AppArt.banner(pkg)) ?: return null
                    return WebResourceResponse(if (isIcon) "image/png" else "image/jpeg", null, ByteArrayInputStream(img))
                }
            }
        }
        web = w
        setContentView(w)
        load()
    }

    // ---------------------------------------------------------------- letting the page go during a film

    // Away in another app (a film, a match) for a while: the page and its renderer give their memory to that app,
    // which on this 2 GB TV is what playback and a picture-in-picture camera run short of. Home draws it again
    // from the copy the page keeps, in about a second. The screensaver and Android's own screens keep it:
    // waking or backing out of those goes straight back to the home screen.
    private var shown = false
    private val release = Runnable { releaseIfAway() }

    override fun onStart() {
        super.onStart()
        shown = true
        main.removeCallbacks(release)
        if (web == null) build()
    }

    override fun onStop() {
        super.onStop()
        shown = false
        main.removeCallbacks(release)
        main.postDelayed(release, RELEASE_AFTER)
    }

    override fun onDestroy() {
        main.removeCallbacks(release)
        super.onDestroy()
    }

    private fun releaseIfAway() {
        if (shown || web == null) return
        Thread {
            val front = runCatching { Tv.frontPackage() }.getOrNull()
            val away = !AerialDream.dreaming && front != null && front !in Apps.HOME_PACKAGES && front !in NOT_AN_APP
            main.post {
                if (shown || web == null) return@post
                main.removeCallbacks(release)
                if (away) letGo() else main.postDelayed(release, RELEASE_AFTER)
            }
        }.start()
    }

    private fun letGo() {
        val w = web ?: return
        android.util.Log.i("couchtv", "home page let go while away")
        web = null; ready = false
        val renderer = if (android.os.Build.VERSION.SDK_INT >= 29) w.webViewRenderProcess else null
        (w.parent as? android.view.ViewGroup)?.removeView(w)
        w.destroy()
        // The renderer process holds most of the memory: end it too (Android 10 and later can).
        if (android.os.Build.VERSION.SDK_INT >= 29) renderer?.terminate()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 7) js("window.tv && tv.resume()")
    }

    private fun load() { web?.loadUrl("$base/tv/") }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) js("window.tv && tv.lowMemory && tv.lowMemory()")
        // Away and Android is short of memory: let the page go now rather than after the wait.
        if (!shown && level >= TRIM_MEMORY_MODERATE) { main.removeCallbacks(release); releaseIfAway() }
    }

    // Behind Netflix the page's clock, slider and fetches stop, so the app in front gets the CPU.
    override fun onPause() { super.onPause(); web?.onPause(); web?.pauseTimers() }
    override fun onResume() { super.onResume(); web?.resumeTimers(); web?.onResume(); hideBars(); if (ready) js("window.tv && tv.resume()") }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); js("window.tv && tv.home()") }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) { if (e.action == KeyEvent.ACTION_UP && ready) js("window.tv && tv.back()"); return true }
        // Long press MENU opens setup, so the home screen can always reach it.
        if (e.keyCode == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP && e.isLongPress) { startActivity(Intent(this, SetupActivity::class.java)); return true }
        return super.dispatchKeyEvent(e)
    }

    private fun js(code: String) { web?.takeIf { ready }?.evaluateJavascript(code, null) }

    private fun hideBars() {
        window.decorView.windowInsetsController?.let {
            it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------------------------------------------------------- what the page can ask for (window.TVOS)

    inner class Native {
        @JavascriptInterface fun apps(): String {
            val out = JSONArray()
            for (ri in tvApps()) {
                val ai = ri.activityInfo
                out.put(JSONObject().put("package", ai.packageName).put("label", AppArt.label(ri))
                    .put("hasBanner", ai.banner != 0 || ai.applicationInfo.banner != 0))
            }
            return out.toString()
        }
        @JavascriptInterface fun launch(pkg: String): Boolean {
            val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg) ?: return false
            main.post { runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            return true
        }
        @JavascriptInterface fun openSettings(): Boolean { main.post { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }; return true }
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun keep(key: String, value: String) {
            getSharedPreferences("page", MODE_PRIVATE).edit().putString(key, value).commit()
            if (key == "couch:prefs") Thread { runCatching { Prefs.changed() } }.start()
        }
        @JavascriptInterface fun kept(key: String): String? = getSharedPreferences("page", MODE_PRIVATE).getString(key, null)
    }

    private fun tvApps(): List<ResolveInfo> = AppArt.tvApps()

    companion object {
        private val GROUND = Color.parseColor("#0D0C0B")
        private const val RELEASE_AFTER = 10 * 60_000L
        // In front but not an app you watch: Android's own screens and settings over the home screen.
        private val NOT_AN_APP = setOf("android", "com.android.systemui", "com.android.tv.settings")
    }
}
