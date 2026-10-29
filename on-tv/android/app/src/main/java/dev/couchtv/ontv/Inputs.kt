package dev.couchtv.ontv

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The TV's sources: each HDMI port by the name its device announces over HDMI-CEC ("PlayStation 5",
 * "Apple TV") or the name given to the port in the TV's settings, whether something is plugged in
 * and on, then Live TV. Switching opens the port through the TV's own TV app. Ported from Talkies'
 * Inputs and Devices (MIT, TechPP), which built on Luma, Matinee and CleanYourTV (all MIT).
 */
object Inputs {
    private lateinit var ctx: Context
    private val tim by lazy { ctx.getSystemService(TvInputManager::class.java) }

    fun init(c: Context) { ctx = c.applicationContext }

    fun list(): JSONArray {
        val out = JSONArray()
        val m = tim ?: return out
        val all = try { m.tvInputList } catch (e: Exception) { return out }
        val behind = HashMap<String, TvInputInfo>()            // port id to the CEC device on it
        val hdmi = mutableListOf<TvInputInfo>(); val av = mutableListOf<TvInputInfo>(); val tuners = mutableListOf<TvInputInfo>()
        for (i in all) {
            if (i.parentId != null) { behind[i.parentId] = i; continue }
            when (i.type) {
                TvInputInfo.TYPE_HDMI -> hdmi += i
                TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_COMPONENT, TvInputInfo.TYPE_SCART, TvInputInfo.TYPE_SVIDEO, TvInputInfo.TYPE_VGA -> av += i
                TvInputInfo.TYPE_TUNER -> tuners += i
            }
        }
        hdmi.sortBy { label(it) }
        hdmi.forEachIndexed { n, i ->
            val port = custom(i) ?: fix(label(i), "HDMI ${n + 1}")
            val dev = behind[i.id]
            val st = state(dev ?: i)
            val o = JSONObject().put("id", i.id).put("name", port).put("kind", "hdmi")
                .put("state", st).put("connected", st != "empty" || (dev != null && state(i) != "empty"))
            if (dev != null) cecName(dev)?.let { (name, colour) -> o.put("device", name).put("colour", colour) }
            out.put(o)
        }
        av.forEachIndexed { n, i ->
            val st = state(i)
            out.put(JSONObject().put("id", i.id).put("name", custom(i) ?: if (av.size == 1) "AV" else fix(label(i), "AV ${n + 1}"))
                .put("kind", "av").put("state", st).put("connected", st != "empty"))
        }
        // App channels register as tuners too: prefer the TV maker's own.
        val vendor = hdmi.firstOrNull()?.serviceInfo?.packageName
        val tuner = tuners.firstOrNull { it.serviceInfo.packageName == vendor }
            ?: tuners.firstOrNull { it.serviceInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
        out.put(JSONObject().put("id", tuner?.id ?: "live").put("name", "Live TV").put("kind", "tuner").put("state", "on").put("connected", true))
        return out
    }

    fun open(id: String) {
        val m = tim
        val info = try { m?.tvInputList?.firstOrNull { it.id == id } } catch (e: Exception) { null }
        val i = Intent(Intent.ACTION_VIEW)
        if (info == null || info.type == TvInputInfo.TYPE_TUNER) {
            if (info != null) i.setDataAndType(TvContract.buildChannelsUriForInput(info.id), "vnd.android.cursor.dir/channel")
            else i.setDataAndType(TvContract.Channels.CONTENT_URI, "vnd.android.cursor.dir/channel")
        } else i.setDataAndType(TvContract.buildChannelUriForPassthroughInput(info.id), "vnd.android.cursor.item/channel")
        try { Tv.startIntent(i) }
        catch (e: Exception) {
            // Some TVs only open their TV app for the plain channel list.
            if (info?.type == TvInputInfo.TYPE_TUNER || info == null) throw e
            throw ApiError(502, "The TV would not switch to ${custom(info) ?: label(info)}")
        }
    }

    private fun label(i: TvInputInfo) = runCatching { i.loadLabel(ctx).toString().trim() }.getOrDefault("")
    private fun custom(i: TvInputInfo) = runCatching { i.loadCustomLabel(ctx)?.toString()?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    /** Some makers label ports by hardware ID; those get a plain name. */
    private fun fix(l: String, fallback: String) = if (l.isEmpty() || l.contains("HW") || l.contains("/")) fallback else l

    private fun state(i: TvInputInfo) = when (runCatching { tim.getInputState(i.id) }.getOrDefault(TvInputManager.INPUT_STATE_CONNECTED)) {
        TvInputManager.INPUT_STATE_CONNECTED -> "on"
        TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "standby"
        else -> "empty"
    }

    // ---------------------------------------------------------------- what the device calls itself

    private const val SONY = 0x080046
    private const val APPLE = 0x0010FA
    private const val TUNER = 3; private const val PLAYBACK = 4; private const val AUDIO = 5; private const val RECORDER = 1

    /** The device's name and its brand colour, or null when the port reports no real name. */
    private fun cecName(dev: TvInputInfo): Pair<String, String>? {
        val raw = label(dev)
        if (raw.isEmpty() || raw.contains("HW") || raw.contains("/")) return null
        val (vendor, type) = cec(dev)
        return identify(raw, vendor, type)
    }

    /** HdmiDeviceInfo is not public to apps, so it is read by reflection; -1s when it cannot be. */
    private fun cec(dev: TvInputInfo): Pair<Int, Int> = try {
        val d = TvInputInfo::class.java.getMethod("getHdmiDeviceInfo").invoke(dev)
        if (d == null) -1 to -1 else (d.javaClass.getMethod("getVendorId").invoke(d) as Int) to (d.javaClass.getMethod("getDeviceType").invoke(d) as Int)
    } catch (e: Throwable) { -1 to -1 }

    fun identify(raw: String, vendor: Int, type: Int): Pair<String, String> {
        val n = raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        val plain = "#30d158"
        return when {
            n.matches(Regex("ps5|playstation5.*")) -> "PlayStation 5" to "#0070d1"
            n.matches(Regex("ps4|playstation4.*")) -> "PlayStation 4" to "#0070d1"
            n.startsWith("playstation") || (vendor == SONY && type == PLAYBACK && n.startsWith("ps")) -> "PlayStation" to "#0070d1"
            n.startsWith("xbox") -> (when {
                n.contains("seriesx") -> "Xbox Series X"; n.contains("seriess") -> "Xbox Series S"
                n.contains("one") -> "Xbox One"; else -> "Xbox"
            }) to "#107c10"
            n.contains("nintendo") || n == "switch" || n == "switch2" -> (if (n.contains("2")) "Nintendo Switch 2" else "Nintendo Switch") to "#e60012"
            n.contains("shield") -> "NVIDIA SHIELD" to "#76b900"
            n.contains("appletv") || (vendor == APPLE && type == PLAYBACK) -> "Apple TV" to "#aeaeb2"
            n.contains("chromecast") -> "Chromecast" to "#4285f4"
            n == "googletv" || n.contains("googletvstreamer") -> "Google TV Streamer" to "#4285f4"
            n.startsWith("firetv") || (n.startsWith("aft") && n.length <= 8) || n.contains("amazonfire") -> "Fire TV" to "#ff9900"
            type == AUDIO || n.contains("soundbar") || n.matches(Regex(".*(receiver|avr|sonos|bose|harman).*")) -> pretty(raw) to plain
            n.matches(Regex(".*(bluray|bdplayer|bdp\\d*|dvd).*")) -> pretty(raw) to plain
            type == TUNER || type == RECORDER -> pretty(raw) to plain
            else -> pretty(raw) to plain
        }
    }

    /** "NintendoSwitch" to "Nintendo Switch", "DIGITAL_RECEIVER" to "Digital Receiver"; names with spaces stay. */
    private fun pretty(raw: String): String {
        var s = raw.replace('_', ' ').trim()
        if (!s.contains(' ')) s = s.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        if (s == s.uppercase(Locale.ROOT) && s.length > 4 && !s.any { it.isDigit() })
            s = s.lowercase(Locale.ROOT).split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }
        return s
    }
}
