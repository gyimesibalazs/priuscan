package hu.codingo.priuscan

import android.content.Context
import android.util.Log
import com.github.luben.zstd.Zstd
import com.uber.h3core.H3Core
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

/**
 * Built-up-area (belterület) geofence. Loads the compact H3 cell sets bundled under
 * assets/geofence/ (one .bgf per region / Geofabrik sub-region) and answers "am I inside a
 * built-up area" for a GPS fix in O(log n).
 *
 * .bgf layout: MAGIC "BGF1" | ver u8 | resMax u8 | resMin u8 | flags u8 | cellCount u32 LE |
 *              bbox 4×f32 LE (minLat,minLng,maxLat,maxLng) | zstd(per-cell delta, varint LEB128)
 *
 * H3 + zstd are native; if the .so fails to load (unexpected ABI) the geofence stays disabled
 * (ready == false) and the app simply never reports a city/road state — no crash.
 */
object BelteruletGeofence {
    private const val TAG = "Geofence"
    private var h3: H3Core? = null
    @Volatile private var regions: List<Region> = emptyList()   // atomically replaced on reload

    @Volatile var ready = false
        private set

    private class Region(
        val resMax: Int, val resMin: Int,
        val minLat: Float, val minLng: Float, val maxLat: Float, val maxLng: Float,
        val cells: LongArray,   // sorted ascending -> binary search
    )

    /** Load once (e.g. from CanService). Safe to call repeatedly. */
    @Synchronized
    fun init(ctx: Context) { if (!ready) load(ctx) }

    /** Re-scan both sources — call after the updater downloads new .bgf. */
    @Synchronized
    fun reload(ctx: Context) { ready = false; load(ctx) }

    // Downloaded sets in filesDir/geofence OVERRIDE the bundled assets/geofence baseline (by name),
    // so the geofence data can grow/refresh without a full APK update.
    private fun load(ctx: Context) {
        if (h3 == null) {
            try { h3 = H3Core.newSystemInstance() }
            catch (e: Throwable) { Log.e(TAG, "H3 native lib failed to load -> geofence disabled", e); return }
        }
        val out = ArrayList<Region>(); val seen = HashSet<String>(); var cells = 0
        File(ctx.filesDir, "geofence").listFiles { f -> f.name.endsWith(".bgf") }?.sortedBy { it.name }?.forEach { f ->
            if (seen.add(f.name)) runCatching { parse(f.readBytes()) }.getOrNull()?.let { out.add(it); cells += it.cells.size }
        }
        runCatching {
            ctx.assets.list("geofence")?.filter { it.endsWith(".bgf") }?.sorted()?.forEach { f ->
                if (seen.add(f)) ctx.assets.open("geofence/$f").use { parse(it.readBytes()) }?.let { out.add(it); cells += it.cells.size }
            }
        }.onFailure { Log.e(TAG, "geofence asset load failed", it) }
        regions = out
        ready = out.isNotEmpty()
        Log.i(TAG, "loaded ${out.size} region(s), $cells cells (ready=$ready)")
    }

    private fun parse(raw: ByteArray): Region? {
        if (raw.size < 28 || raw[0].toInt() != 'B'.code || raw[1].toInt() != 'G'.code) return null
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val resMax = raw[5].toInt() and 0xff
        val resMin = raw[6].toInt() and 0xff
        val n = bb.getInt(8)
        val minLat = bb.getFloat(12); val minLng = bb.getFloat(16)
        val maxLat = bb.getFloat(20); val maxLng = bb.getFloat(24)
        val comp = raw.copyOfRange(28, raw.size)
        val payload = Zstd.decompress(comp, Zstd.decompressedSize(comp).toInt())
        val cells = LongArray(n)
        var prev = 0L; var i = 0
        for (k in 0 until n) {
            var shift = 0; var d = 0L
            while (true) {
                val b = payload[i++].toInt() and 0xff
                d = d or ((b.toLong() and 0x7f) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            prev += d; cells[k] = prev
        }
        return Region(resMax, resMin, minLat, minLng, maxLat, maxLng, cells)
    }

    /**
     * Geofence verdict for a GPS fix:
     *   true  = inside a built-up area (belterület),
     *   false = covered by a region but on the open road (országút),
     *   null  = NOT covered by any loaded region, or the geofence is disabled (unknown — treat like
     *           "no GPS": no city-km, no headlight warning).
     */
    fun cityState(lat: Double, lng: Double): Boolean? {
        val core = h3 ?: return null
        var covered = false
        for (r in regions) {
            if (lat < r.minLat || lat > r.maxLat || lng < r.minLng || lng > r.maxLng) continue
            covered = true
            // the compacted set is mixed-resolution -> probe the res9 cell then walk up to resMin
            var c = core.latLngToCell(lat, lng, r.resMax)
            var res = r.resMax
            while (res >= r.resMin) {
                if (Arrays.binarySearch(r.cells, c) >= 0) return true
                if (res > r.resMin) c = core.cellToParent(c, res - 1)
                res--
            }
        }
        return if (covered) false else null   // in a region's bbox but no cell hit = open road; else unknown
    }
}
