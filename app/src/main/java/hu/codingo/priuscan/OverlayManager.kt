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
class OverlayManager(private val ctx: Context, private val prefs: Prefs) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var alertView: View? = null
    private var statusView: TextView? = null
    private var doorImg: DoorImageView? = null
    private var doorContainer: View? = null
    private var doorLp: WindowManager.LayoutParams? = null
    // a containert egyszer hozzuk letre es ujrahasznaljuk; csukaskor csak
    // levesszuk a WindowManager-rol (nem epitunk ujat minden ciklusban)
    @Volatile private var doorsVisible = false

    private fun canDraw() = Settings.canDrawOverlays(ctx)

    private fun baseParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    // ---------------- statusz-csik (mindig lathato) ----------------
    // A fejegyseg ROM-ja a sajat statuszsoraban NEM mutatja a harmadik felеs
    // notification-ikonokat, ezert a kivalasztott ertekeket sajat overlay-kent
    // rajzoljuk a kepernyo tetejere, a rendszersav ala.

    fun updateStatus(text: String) {
        if (text.isEmpty()) { hideStatus(); return }
        if (!canDraw()) { hideStatus(); return }
        statusView?.let { it.text = text; return }
        val tv = TextView(ctx).apply {
            this.text = text
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(20, 6, 24, 8)
            setBackgroundColor(0xCC0E1216.toInt())
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        val lp = baseParams().apply {
            // hozzaerheto (huzhato), de nem fogad fokuszt
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.statusX >= 0) prefs.statusX else 8
            y = if (prefs.statusY >= 0) prefs.statusY else statusBarHeight()
        }
        // szabadon huzhato barhova; az uj poziciot elmentjuk
        tv.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0f; var startY = 0f; var baseX = 0; var baseY = 0; var moved = false
            override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startX = e.rawX; startY = e.rawY; baseX = lp.x; baseY = lp.y; moved = false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        lp.x = baseX + (e.rawX - startX).toInt()
                        lp.y = baseY + (e.rawY - startY).toInt()
                        if (kotlin.math.abs(e.rawX - startX) > 6 || kotlin.math.abs(e.rawY - startY) > 6) moved = true
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (moved) { prefs.statusX = lp.x; prefs.statusY = lp.y }
                    }
                }
                return true
            }
        })
        try { wm.addView(tv, lp); statusView = tv } catch (_: Exception) {}
    }

    private fun hideStatus() {
        statusView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        statusView = null
    }

    private fun statusBarHeight(): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    }

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
        val box = ensureDoorContainer() ?: return
        if (!doorsVisible) {
            doorsVisible = true
            box.animate().cancel()
            if (box.parent == null) {
                try {
                    wm.addView(box, doorLp)
                    box.translationY = -700f   // felulrol becsuszas
                } catch (_: Exception) {
                    doorsVisible = false
                    return
                }
            }
            box.animate().translationY(0f).setDuration(250).start()
        }
        doorImg?.setMask(mask)
    }

    /** A container + kep-nezet egyszeri felepitese; ujrahasznaljuk minden ciklusban. */
    private fun ensureDoorContainer(): View? {
        doorContainer?.let { return it }
        // Elore renderelt felulnezeti PNG-k kombinacionkent (DoorImageView).
        // Nincs GL/Filament -> nem fagy, gyors.
        val content: View = DoorImageView(ctx).also { doorImg = it }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xCC101418.toInt())
            setPadding(28, 20, 28, 24)
            addView(TextView(ctx).apply {
                text = "NYITOTT AJTÓ"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFFFF5252.toInt())
                gravity = Gravity.CENTER
            })
            addView(content, LinearLayout.LayoutParams(440, 420))
        }
        doorContainer = box
        doorLp = baseParams().apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0
        }
        return box
    }

    private fun hideDoors() {
        doorsVisible = false
        val box = doorContainer ?: return
        if (box.parent == null) return
        box.animate().cancel()
        box.animate().translationY(-700f).setDuration(200).withEndAction {
            // a withEndAction cancel-re is lefuthat: csak akkor vegyuk le, ha
            // tenyleg rejtve maradunk (kozben nem nyilt ki ujra)
            if (!doorsVisible) {
                try { if (box.parent != null) wm.removeView(box) } catch (_: Exception) {}
            }
        }.start()
    }

    fun removeAll() {
        removeAlert()
        hideStatus()
        doorsVisible = false
        doorContainer?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        doorContainer = null
        doorImg = null
        doorLp = null
    }
}
