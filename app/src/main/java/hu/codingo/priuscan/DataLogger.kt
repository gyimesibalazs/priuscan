package hu.codingo.priuscan

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Local data logging without a DB/HA: it writes the raw firmware JSON lines into
 * a daily JSONL file (priuscan-YYYYMMDD.jsonl). On day change (and on app restart)
 * it gzips the plain files of previous days (.jsonl.gz), so storage stays small.
 * The files live in the external app folder (getExternalFilesDir/logs) -> accessible
 * with a file manager and shareable via FileProvider, without a separate storage permission.
 *
 * log() is called from the serial reader (IO) thread; the throttle limits the write
 * rate (the cell data is ~1 Hz anyway). Gzipping runs on a background thread.
 */
class DataLogger(ctx: Context) {
    private val dir = logsDir(ctx)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    private var lastWriteMs = 0L
    private var curDay = ""
    private var curFile: File? = null

    /** Logs one raw JSON line if at least throttleMs has elapsed since the last one. */
    @Synchronized
    fun log(line: String, throttleMs: Long = 1000L) {
        val now = System.currentTimeMillis()
        if (now - lastWriteMs < throttleMs) return
        lastWriteMs = now
        val day = dayFmt.format(Date(now))
        if (day != curDay) rotate(day)
        try { curFile?.appendText(line.trimEnd() + "\n") } catch (_: Exception) {}
    }

    private fun rotate(newDay: String) {
        curDay = newDay
        curFile = File(dir, "priuscan-$newDay.jsonl")
        // gzip every plain .jsonl that is NOT today's (consistent across restarts too)
        val keep = curFile!!.name
        val stale = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jsonl") && it.name != keep }
            ?: emptyList()
        if (stale.isNotEmpty()) Thread { stale.forEach { gzipAndRemove(it) } }.start()
    }

    private fun gzipAndRemove(src: File) {
        try {
            val gz = File(src.parentFile, src.name + ".gz")
            GZIPOutputStream(FileOutputStream(gz)).use { out -> src.inputStream().use { it.copyTo(out) } }
            src.delete()
        } catch (_: Exception) {}
    }

    companion object {
        fun logsDir(ctx: Context): File =
            File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }

        fun files(ctx: Context): List<File> =
            logsDir(ctx).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

        fun totalBytes(ctx: Context): Long = files(ctx).sumOf { it.length() }

        fun deleteAll(ctx: Context): Int {
            val fs = files(ctx); fs.forEach { it.delete() }; return fs.size
        }

        fun humanSize(bytes: Long): String = when {
            bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1e9)
            bytes >= 1_000_000L     -> "%.1f MB".format(bytes / 1e6)
            bytes >= 1_000L         -> "%.0f kB".format(bytes / 1e3)
            else                    -> "$bytes B"
        }

        /** Share Intent for all log files (with FileProvider URIs), or null if empty. */
        fun shareIntent(ctx: Context): Intent? {
            val fs = files(ctx)
            if (fs.isEmpty()) return null
            val auth = "${ctx.packageName}.fileprovider"
            val uris = ArrayList<Uri>(fs.map { FileProvider.getUriForFile(ctx, auth, it) })
            return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/gzip"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
