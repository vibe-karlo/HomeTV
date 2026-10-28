package dev.couchtv.ontv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * HomeTV's own screen on the TV, in the home screen's style. Two forms:
 *  - the pairing card (when a phone asks to pair): the address and a large code, nothing else, and it
 *    closes by itself once the phone is paired;
 *  - setup (the HomeTV tile): status on the left, the few actions on the right.
 */
class SetupActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var pinOnly = false
    private var phonesAtOpen = 0
    private var status: TextView? = null
    private lateinit var codeView: TextView
    private lateinit var hint: TextView
    private val rows = mutableListOf<Pair<TextView, () -> String>>()
    private val tick = object : Runnable { override fun run() { refresh(); main.postDelayed(this, 1500) } }
    private val pinListener: (String?) -> Unit = { main.post { refresh() } }
    private val medium by lazy { runCatching { resources.getFont(R.font.switzer_medium) }.getOrDefault(Typeface.DEFAULT) }
    private val semibold by lazy { runCatching { resources.getFont(R.font.switzer_semibold) }.getOrDefault(Typeface.DEFAULT_BOLD) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        App.startService(this)
        build(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); build(i) }

    private fun build(i: Intent?) {
        pinOnly = i?.getBooleanExtra("pin", false) == true
        rows.clear(); status = null
        phonesAtOpen = Auth.phoneCount()
        if (Auth.currentPin() == null) Auth.newPin()
        setContentView(if (pinOnly) pairCard() else setup())
        refresh()
    }

    // ---------------------------------------------------------------- the pairing card

    private fun pairCard(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(FLOOR) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(56), dp(44), dp(56), dp(40))
            background = GradientDrawable().apply { setColor(SURFACE); cornerRadius = dp(26).toFloat() }
        }
        card.addView(text("Pair a phone", 26f, INK, semibold))
        hint = text("", 17f, INK_2, medium).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0); setLineSpacing(0f, 1.25f) }
        card.addView(hint)
        codeView = text("", 64f, INK, semibold).apply { letterSpacing = 0.06f; setPadding(0, dp(24), 0, dp(18)) }
        card.addView(codeView)
        card.addView(text("It works once, for 3 minutes. Back closes this.", 15f, INK_3, medium))
        root.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        return root
    }

    // ---------------------------------------------------------------- setup

    private fun setup(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(FLOOR); setPadding(dp(64), dp(56), dp(64), dp(48)) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(48), 0) }
        left.addView(text("HomeTV", 34f, INK, semibold))
        hint = text("", 17f, INK_2, medium).apply { setPadding(0, dp(20), 0, 0); setLineSpacing(0f, 1.25f) }
        left.addView(hint)
        codeView = text("", 40f, INK, semibold).apply { letterSpacing = 0.05f; setPadding(0, dp(12), 0, 0) }
        left.addView(codeView)
        val st = text("", 15f, INK_3, medium).apply { setPadding(0, dp(24), 0, 0); setLineSpacing(0f, 1.25f) }
        status = st
        left.addView(st)
        root.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val connect = row(list, { if (Adb.connected) "Debugging connected" else "Connect debugging" }) {
            st.text = "Connecting. If the TV asks “Allow debugging?”, tick Always allow and press Allow."
            Thread { Adb.ensure(interactive = true); main.post { refresh() } }.start()
        }
        row(list, { "New phone code" }) { Auth.newPin(); refresh() }
        row(list, { if (App.homeEnabled(this)) "Stop being the home screen" else "Use as home screen" }) { App.setHomeEnabled(this, !App.homeEnabled(this)); refresh() }
        row(list, { "Sign out all phones" }) { Auth.forgetAll(); refresh() }
        row(list, { "Developer options" }) { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } }
        root.addView(list, LinearLayout.LayoutParams(dp(400), -2))
        connect.requestFocus()
        return root
    }

    private fun row(parent: LinearLayout, label: () -> String, act: () -> Unit): TextView {
        val bg = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.TRANSPARENT) }
        val v = text(label(), 17f, INK, medium).apply {
            isFocusable = true; isClickable = true; background = bg
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setOnFocusChangeListener { _, has -> bg.setColor(if (has) FOCUS else Color.TRANSPARENT); setTextColor(if (has) FOCUS_INK else INK) }
            setOnClickListener { act() }
        }
        parent.addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        rows += v to label
        return v
    }

    // ---------------------------------------------------------------- state

    override fun onResume() { super.onResume(); Auth.onPin(pinListener); main.post(tick) }
    override fun onPause() { super.onPause(); Auth.offPin(pinListener); main.removeCallbacks(tick) }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (pinOnly && e.keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { finish(); return true }
        return super.dispatchKeyEvent(e)
    }

    private fun refresh() {
        val ip = App.lanAddress()
        val where = if (ip != null) "$ip:${Store.port}" else null
        val p = Auth.currentPin()
        codeView.text = if (p != null) "${p.take(3)} ${p.drop(3)}" else "––– –––"
        if (pinOnly) {
            hint.text = if (where != null) "On your phone, open $where\nand type this code." else "This TV is not on a network yet."
            // Paired, or the code ran out: the card has done its job.
            if (Auth.phoneCount() > phonesAtOpen || p == null) finish()
            return
        }
        hint.text = if (where != null) "Phone remote: open $where on a phone at home and type this code." else "This TV is not on a network yet."
        val s = Adb.status()
        val adb = when {
            Adb.connected -> "Debugging is connected: phones can press keys, type and turn the TV off."
            s["state"] == "waiting_for_allow" || s["state"] == "connecting" -> "Waiting for “Allow debugging?” on the TV."
            else -> "Debugging is off: phones can open apps and change the volume, not press keys."
        }
        val n = Auth.phoneCount()
        status?.text = "$adb\n${if (n == 1) "1 phone paired" else "$n phones paired"}. Turning the TV on still needs its own remote."
        rows.forEach { (v, label) -> v.text = label() }
    }

    private fun text(t: String, sp: Float, colour: Int, face: Typeface) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(colour); typeface = face; includeFontPadding = false
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val FLOOR = Color.parseColor("#0D0C0B")
        private val SURFACE = Color.parseColor("#1D1B18")
        private val INK = Color.parseColor("#F6F3EE")
        private val INK_2 = Color.parseColor("#B8B4AE")
        private val INK_3 = Color.parseColor("#8C8883")
        private val FOCUS = Color.parseColor("#F0EDE8")
        private val FOCUS_INK = Color.parseColor("#15130F")
    }
}
