package dev.couchtv.ontv

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The Philips and Rakuten TV buttons on the remote. Philips' key handler turns them into "open the
 * App Gallery" and "open Rakuten TV"; neither app is on this TV, so these land here and open the
 * apps chosen in Settings, Remote Buttons. No screen of its own.
 */
class ButtonActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val which = if (intent?.action == RAKUTEN) "rakuten" else "philips"
        val pkg = Prefs.button(which)
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val RAKUTEN = "org.droidtv.tv.intent.action.LAUNCH_RAKUTEN_TV"
    }
}
