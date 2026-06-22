package hu.codingo.priuscan

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes raw CAN dump frames ("#XXX [len] BB..") to a dedicated .txt file (separate
 * from the JSONL sensor log). No throttle. Lazily opens on first write. Safety
 * auto-stop at 10 min or 50 MB. Updates the CanService.dump* flows for the UI.
 */
class DumpLogger(private val ctx: Context) {
    private var bw: BufferedWriter? = null
    private var file: File? = null
    private var startedAt = 0L
    private var bytes = 0L

    @Synchronized
    fun write(line: String) {
        val w = bw ?: open()
        // prefix each frame with ms since the dump started, so dumps are time-correlatable for
        // signal reverse-engineering (the firmware stays timestamp-free; the head unit stamps it)
        val out = "${System.currentTimeMillis() - startedAt} $line"
        try { w.write(out); w.write("\n") } catch (_: Exception) {}
        bytes += out.length + 1
        CanService.dumpBytes.value = bytes
        if (System.currentTimeMillis() - startedAt > 600_000L || bytes > 50_000_000L)
            CanService.setDump(false)   // safety auto-stop (10 min / 50 MB)
    }

    private fun open(): BufferedWriter {
        val dir = DataLogger.logsDir(ctx)
        val name = "priuscan-dump-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        file = File(dir, name)
        startedAt = System.currentTimeMillis(); bytes = 0L
        CanService.dumpFile.value = null
        return file!!.bufferedWriter().also { bw = it }
    }

    @Synchronized
    fun stop() {
        val w = bw ?: return
        try { w.flush(); w.close() } catch (_: Exception) {}
        bw = null
        CanService.dumpFile.value = file?.absolutePath
    }

    val isOpen: Boolean @Synchronized get() = bw != null

    companion object {
        fun shareIntent(ctx: Context, path: String): Intent? {
            val f = File(path); if (!f.exists()) return null
            val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            return Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
