package dev.couchtv.ontv

import android.content.pm.PackageManager
import android.util.Log

/**
 * A Home press longer than 0.4 s counts as a long press, and the Philips remote's Home often is. Android then asks
 * the system's own launcher for its app list. With that launcher switched off the request goes to TV Settings, which
 * has no such screen, so nothing happens. No app can hear that key (a key-filtering accessibility service breaks held
 * volume on this board), but Android logs the request: read that one line and go home.
 * READ_LOGS is granted once over ADB (Adb.onConnected); without it a held Home just does nothing, as before.
 */
object HomeHold {
    private const val PERMISSION = "android.permission.READ_LOGS"
    private var started = false

    @Synchronized fun start() {
        if (started || App.instance.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return
        started = true
        Thread({
            while (true) {
                runCatching { watch() }.onFailure { Log.w("couchtv", "home hold: ${it.message}") }
                Thread.sleep(5000)
            }
        }, "home-hold").start()
    }

    private fun watch() {
        // Only new lines, from the system's log and this one tag, so the reader wakes rarely.
        val since = "${System.currentTimeMillis() / 1000}.000"
        val p = ProcessBuilder("logcat", "-b", "system", "-T", since, "-s", "ActivityTaskManager:I").redirectErrorStream(true).start()
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                if ("act=android.intent.action.ALL_APPS" in line && "pkg=com.android.tv.settings" in line && App.homeEnabled(App.instance)) {
                    Log.i("couchtv", "held Home: going home")
                    Tv.startHome()
                }
            }
        } finally { p.destroy() }
    }
}
