package hu.codingo.priuscan

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Ket overlay:
 *  - riaszto popup (warning/alert szoveggel, auto-eltuno, koppintasra zar)
 *  - ajto-overlay: felulrol becsuszo Prius vonalrajz a nyitott ajtokkal,
 *    addig latszik, amig ajto van nyitva
 */
class OverlayManager(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var alertView: View? = null
    private var doorView: DoorOverlayView? = null
    private var door3D: Prius3DView? = null
    private var doorContainer: View? = null

    private fun canDraw() = Settings.canDrawOverlays(ctx)

    private fun baseParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    // ---------------- riaszto popup ----------------

    fun showAlert(level: Int, text: String) {
        if (!canDraw()) return
        removeAlert()
        val tv = TextView(ctx).apply {
            this.text = text
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(48, 32, 48, 32)
            setBackgroundColor(if (level >= 2) 0xE6C62828.toInt() else 0xE6EF6C00.toInt())
            setOnClickListener { removeAlert() }
        }
        val lp = baseParams().apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }
        try {
            wm.addView(tv, lp)
            alertView = tv
            // szint 1: 8 s utan eltunik; szint 2: 20 s (vagy koppintas)
            main.postDelayed({ removeAlert() }, if (level >= 2) 20000L else 8000L)
        } catch (_: Exception) {}
    }

    private fun removeAlert() {
        alertView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        alertView = null
    }

    // ---------------- ajto overlay ----------------

    fun updateDoors(mask: Int) {
        if (mask == 0) {
            hideDoors()
            return
        }
        if (!canDraw()) return
        if (doorContainer == null) {
            // elsodlegesen a 3D modell; ha a GL init elhasal a fejegysegen,
            // visszaesunk a 2D rajzra
            val content: View = try {
                Prius3DView(ctx).also { door3D = it }
            } catch (_: Throwable) {
                DoorOverlayView(ctx).also { doorView = it }
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setBackgroundColor(0xCC101418.toInt())
                setPadding(40, 24, 40, 32)
                addView(TextView(ctx).apply {
                    text = "NYITOTT AJTÓ"
                    textSize = 18f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(0xFFFF5252.toInt())
                    gravity = Gravity.CENTER
                })
                val w = if (door3D != null) 400 else 360
                val h = if (door3D != null) 600 else 560
                addView(content, LinearLayout.LayoutParams(w, h))
            }
            val lp = baseParams().apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 0
            }
            try {
                wm.addView(box, lp)
                doorContainer = box
                // felulrol becsuszas
                box.translationY = -700f
                box.animate().translationY(0f).setDuration(250).start()
            } catch (_: Exception) {
                door3D = null
                doorView = null
                return
            }
        }
        door3D?.setMask(mask)
        doorView?.mask = mask
    }

    private fun hideDoors() {
        val v = doorContainer ?: return
        doorContainer = null
        doorView = null
        door3D = null
        v.animate().translationY(-700f).setDuration(200).withEndAction {
            try { wm.removeView(v) } catch (_: Exception) {}
        }.start()
    }

    fun removeAll() {
        removeAlert()
        doorContainer?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        doorContainer = null
        doorView = null
    }
}
