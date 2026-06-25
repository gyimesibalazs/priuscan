package hu.codingo.priuscan

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater off GitHub Releases (mirrors the firmware OTA pattern). The latest release carries:
 *   - app-debug.apk            the new APK (user-confirmed install),
 *   - update.json              {"versionCode":N,"versionName":"x.y","fw":NNN},
 *   - <region>.bgf …           the geofence sets — downloaded into filesDir/geofence so the data can
 *                              grow WITHOUT a full APK update; they override the APK's baked-in copies.
 *
 * check() compares update.json's versionCode with the installed one and syncs any new/changed .bgf.
 * update() downloads the APK and hands it to the system package installer (the user taps Install).
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val REPO = "gyimesibalazs/priuscan"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val UA = "priuscan-app"

    enum class State { IDLE, CHECKING, AVAILABLE, DOWNLOADING, UPTODATE, ERROR }
    val state = MutableStateFlow(State.IDLE)
    val progress = MutableStateFlow(0)               // 0..100 while downloading the APK
    val msg = MutableStateFlow("")                   // error / status text
    val latestName = MutableStateFlow<String?>(null) // available versionName (when AVAILABLE)
    val geofenceMsg = MutableStateFlow("")           // "+N" regions synced

    private var apkUrl: String? = null
    private var apkSize = 0L
    private var bgfAssets: List<Triple<String, String, Long>> = emptyList()  // name, url, size

    fun check(ctx: Context) {
        if (state.value == State.CHECKING || state.value == State.DOWNLOADING) return
        Thread { runCatching { doCheck(ctx) }.onFailure { fail(it) } }.start()
    }

    fun update(ctx: Context) {
        if (state.value == State.DOWNLOADING) return
        Thread { runCatching { doUpdate(ctx) }.onFailure { fail(it) } }.start()
    }

    private fun fail(e: Throwable) {
        Log.e(TAG, "update failed", e); state.value = State.ERROR; msg.value = e.message ?: "error"
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            connectTimeout = 15000; readTimeout = 30000
        }

    private fun httpGet(url: String): ByteArray = open(url).inputStream.use { it.readBytes() }

    private fun installedVersion(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }

    private fun doCheck(ctx: Context) {
        state.value = State.CHECKING; msg.value = ""
        val conn = open(API)
        if (conn.responseCode == 404) { state.value = State.UPTODATE; return }  // no releases published yet
        val rel = JSONObject(String(conn.inputStream.use { it.readBytes() }))
        val assets = rel.getJSONArray("assets")
        var updateJson: String? = null
        val bgf = ArrayList<Triple<String, String, Long>>()
        apkUrl = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            val url = a.getString("browser_download_url")
            val size = a.optLong("size")
            when {
                name.endsWith("update.json") -> updateJson = url
                name.endsWith(".apk") -> { apkUrl = url; apkSize = size }
                name.endsWith(".bgf") -> bgf.add(Triple(name.removeSuffix(".bgf"), url, size))
            }
        }
        bgfAssets = bgf
        syncGeofence(ctx)                                       // pull any new/changed geofence data

        var newVer = 0L; var newName = ""
        updateJson?.let { JSONObject(String(httpGet(it))) }?.let { newVer = it.optLong("versionCode"); newName = it.optString("versionName") }
        latestName.value = newName
        state.value = if (newVer > installedVersion(ctx) && apkUrl != null) State.AVAILABLE else State.UPTODATE
        Log.i(TAG, "check: installed=${installedVersion(ctx)} latest=$newVer apk=${apkUrl != null} bgfSync=${geofenceMsg.value}")
    }

    private fun doUpdate(ctx: Context) {
        val url = apkUrl ?: throw IllegalStateException("no APK asset")
        state.value = State.DOWNLOADING; progress.value = 0
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val apk = File(dir, "priuscan-update.apk")
        download(url, apk, apkSize, track = true)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        state.value = State.IDLE
    }

    /** Download .bgf the device doesn't already have (bundled OR previously downloaded) into filesDir. */
    private fun syncGeofence(ctx: Context) {
        if (bgfAssets.isEmpty()) return
        val dir = File(ctx.filesDir, "geofence").apply { mkdirs() }
        val bundled = HashMap<String, Long>()
        runCatching {
            ctx.assets.list("geofence")?.forEach { f ->
                if (f.endsWith(".bgf")) ctx.assets.openFd("geofence/$f").use { bundled[f.removeSuffix(".bgf")] = it.length }
            }
        }
        var fetched = 0
        for ((name, url, size) in bgfAssets) {
            val local = File(dir, "$name.bgf")
            if (bundled[name] == size || (local.exists() && local.length() == size)) continue
            runCatching { download(url, local, size, track = false); fetched++ }
                .onFailure { Log.w(TAG, "bgf $name failed", it) }
        }
        if (fetched > 0) { BelteruletGeofence.reload(ctx); geofenceMsg.value = "+$fetched" }
    }

    private fun download(url: String, dst: File, expected: Long, track: Boolean) {
        val c = open(url)
        val total = if (expected > 0) expected else c.contentLengthLong
        c.inputStream.use { ins ->
            dst.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                while (ins.read(buf).also { n = it } > 0) {
                    out.write(buf, 0, n); done += n
                    if (track && total > 0) progress.value = (done * 100 / total).toInt()
                }
            }
        }
    }
}
