package hu.codingo.priuscan

import android.content.Context
import android.util.Log
import com.github.luben.zstd.Zstd
import com.uber.h3core.H3Core
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
    private val regions = ArrayList<Region>()

    @Volatile var ready = false
        private set

    private class Region(
        val resMax: Int, val resMin: Int,
        val minLat: Float, val minLng: Float, val maxLat: Float, val maxLng: Float,
        val cells: LongArray,   // sorted ascending -> binary search
    )

    /** Load once (e.g. from CanService.onCreate). Safe to call repeatedly. */
    @Synchronized
    fun init(ctx: Context) {
        if (ready || regions.isNotEmpty()) return
        try {
            h3 = H3Core.newSystemInstance()
        } catch (e: Throwable) {
            Log.e(TAG, "H3 native lib failed to load -> geofence disabled", e); return
        }
        try {
            val am = ctx.assets
            val files = am.list("geofence")?.filter { it.endsWith(".bgf") }?.sorted() ?: emptyList()
            var cells = 0
            for (f in files) {
                am.open("geofence/$f").use { ins -> parse(ins.readBytes()) }?.let { regions.add(it); cells += it.cells.size }
            }
            ready = regions.isNotEmpty()
            Log.i(TAG, "loaded ${regions.size} region(s), $cells cells (ready=$ready)")
        } catch (e: Throwable) {
            Log.e(TAG, "geofence asset load failed", e)
        }
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

    /** True if (lat,lng) is inside a built-up area. False if outside, unknown, or disabled. */
    fun isInside(lat: Double, lng: Double): Boolean {
        val core = h3 ?: return false
        for (r in regions) {
            if (lat < r.minLat || lat > r.maxLat || lng < r.minLng || lng > r.maxLng) continue
            // the compacted set is mixed-resolution -> probe the res9 cell then walk up to resMin
            var c = core.latLngToCell(lat, lng, r.resMax)
            var res = r.resMax
            while (res >= r.resMin) {
                if (Arrays.binarySearch(r.cells, c) >= 0) return true
                if (res > r.resMin) c = core.cellToParent(c, res - 1)
                res--
            }
        }
        return false
    }
}
