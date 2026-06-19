package hu.codingo.priuscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.util.LruCache
import android.view.View

/**
 * Pre-rendered top-down Prius images per door combination. Each
 * combination has a PNG in assets: car_<door-mask hex>.png (rendered
 * with Blender, transparent background). Based on the lower bits of the door mask
 * we load the matching image and draw it centered, keeping the aspect ratio. No GL/Filament.
 *
 * Bit layout (matches the firmware):
 *   0x80 driver, 0x40 passenger, 0x08 rear left, 0x04 rear right, 0x01 trunk.
 */
class DoorImageView(ctx: Context) : View(ctx) {

    companion object { const val DOOR_BITS = 0xCD }

    private val cache = LruCache<Int, Bitmap>(6)
    private var mask = -1
    private var current: Bitmap? = null
    private val dst = RectF()

    fun setMask(m: Int) {
        val key = m and DOOR_BITS
        if (key == mask) return
        mask = key
        current = cache.get(key) ?: load(key)?.also { cache.put(key, it) }
        invalidate()
    }

    private fun load(key: Int): Bitmap? = try {
        context.assets.open("car_%02x.png".format(key)).use {
            BitmapFactory.decodeStream(it)
        }
    } catch (_: Exception) { null }

    override fun onDraw(c: Canvas) {
        val b = current ?: return
        val s = minOf(width / b.width.toFloat(), height / b.height.toFloat())
        val w = b.width * s
        val h = b.height * s
        dst.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        c.drawBitmap(b, null, dst, null)
    }
}
