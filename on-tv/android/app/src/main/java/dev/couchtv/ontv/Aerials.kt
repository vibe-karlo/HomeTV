package dev.couchtv.ontv

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Apple's aerial films, kept on the TV. Apple serves them with a certificate from its own root,
 * which Android does not trust, so downloads trust that one root (bundled, res/raw) on top of the
 * system's. A few clips stay on the TV, one is swapped for a new one each day, and the home screen
 * and the screensaver play them from here: never waiting on the internet.
 */
object Aerials {
    private const val TAG = "couchtv"
    private const val KEEP = 4
    private const val MAX_CLIP = 260L * 1024 * 1024
    private const val SPARE = 400L * 1024 * 1024        // never fill the TV's storage
    private lateinit var ctx: Context
    private lateinit var dir: File
    private val work = Executors.newSingleThreadScheduledExecutor()
    private val catalog: List<JSONObject> by lazy {
        val a = JSONArray(ctx.assets.open("aerials.json").bufferedReader().readText())
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    fun init(c: Context) {
        ctx = c.applicationContext
        dir = File(ctx.filesDir, "aerials").apply { mkdirs() }
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        work.scheduleWithFixedDelay({ runCatching { fill() }.onFailure { Log.w(TAG, "aerials: ${it.message}") } }, 20, 3600, TimeUnit.SECONDS)
        work.scheduleWithFixedDelay({ runCatching { rotate() } }, 24, 24, TimeUnit.HOURS)
    }

    /** Settings changed (places): top up with clips from the places now chosen. */
    fun changed() { work.execute { runCatching { fill() } } }

    fun file(name: String): File? = File(dir, name).takeIf { it.isFile && it.parentFile == dir && !name.endsWith(".part") }

    /** The clips on the TV from the chosen places (all clips on the TV when none match yet). */
    fun list(): JSONArray {
        val cats = Prefs.aerialCats()
        val here = cached()
        val chosen = here.filter { it.optString("cat") in cats }.ifEmpty { here }
        return JSONArray(chosen.map { JSONObject(it.toString()).put("url", "/aerials/file/" + name(it)) })
    }

    fun cachedFiles(): List<Pair<File, JSONObject>> {
        val cats = Prefs.aerialCats()
        val here = cached()
        return here.filter { it.optString("cat") in cats }.ifEmpty { here }.map { File(dir, name(it)) to it }
    }

    private fun cached() = catalog.filter { File(dir, name(it)).isFile }
    private fun name(c: JSONObject) = c.optString("url").substringAfterLast('/')

    @Synchronized private fun fill() {
        // The first two clips download straight away; after that, never while a streaming app is in
        // front, so a 200 MB download does not compete with what you are watching.
        if (cached().size >= 2 && Tv.isOn) {
            val front = Tv.frontPackage()
            if (front != null && front != BuildConfig.APPLICATION_ID && front !in Apps.HOME_PACKAGES) return
        }
        val cats = Prefs.aerialCats()
        var want = KEEP - cached().count { it.optString("cat") in cats }
        val candidates = catalog.filter { it.optString("cat") in cats && !File(dir, name(it)).isFile }.shuffled()
        for (c in candidates) {
            if (want <= 0) break
            if (download(c)) want--
        }
        // Over the limit (places changed): drop clips from places no longer chosen, oldest first.
        val extra = cached().sortedBy { File(dir, name(it)).lastModified() }
        var count = extra.size
        for (c in extra) if (count > KEEP + 1 && c.optString("cat") !in cats) { File(dir, name(c)).delete(); count-- }
    }

    @Synchronized private fun rotate() {
        val oldest = cached().minByOrNull { File(dir, name(it)).lastModified() } ?: return
        if (cached().size >= KEEP) File(dir, name(oldest)).delete()
        fill()
    }

    private fun download(c: JSONObject): Boolean {
        val out = File(dir, name(c)); val part = File(dir, name(c) + ".part")
        val conn = URL(c.optString("url")).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = apple.socketFactory
        conn.connectTimeout = 10_000; conn.readTimeout = 30_000
        try {
            val size = conn.contentLengthLong
            if (conn.responseCode != 200 || size <= 0 || size > MAX_CLIP || dir.usableSpace < size + SPARE) return false
            conn.inputStream.use { inp -> part.outputStream().use { inp.copyTo(it, 1 shl 16) } }
            if (part.length() != size) { part.delete(); return false }
            part.renameTo(out)
            Log.i(TAG, "aerial kept: ${c.optString("label")} (${size / 1_000_000} MB)")
            return true
        } catch (e: Exception) {
            part.delete(); Log.w(TAG, "aerial download ${c.optString("label")}: ${e.message}"); return false
        } finally { conn.disconnect() }
    }

    /** The system's trusted roots plus Apple Root CA (fingerprint B0:B1:73:0E:…:F0:24, from apple.com/appleca). */
    private val apple: SSLContext by lazy {
        val apple = ctx.resources.openRawResource(R.raw.apple_root_ca).use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("apple-root", apple) }
        val appleTm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }.trustManagers[0] as X509TrustManager
        val systemTm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }.trustManagers[0] as X509TrustManager
        val both = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, type: String) = systemTm.checkClientTrusted(chain, type)
            override fun checkServerTrusted(chain: Array<X509Certificate>, type: String) {
                try { systemTm.checkServerTrusted(chain, type) } catch (e: Exception) { appleTm.checkServerTrusted(chain, type) }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = systemTm.acceptedIssuers + appleTm.acceptedIssuers
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(both), null) }
    }
}
