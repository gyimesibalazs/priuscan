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
 * Two overlays:
 *  - alert popup (with warning/alert text, auto-dismissing, closes on tap)
 *  - door overlay: a Prius line drawing that slides in from the top showing the
 *    open doors, visible as long as a door is open
 */
class OverlayManager(private val ctx: Context, private val prefs: Prefs) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var alertView: View? = null
    private var alertDismiss: Runnable? = null   // pending auto-dismiss (cancelled on a new alert)
    private var statusView: TextView? = null
    private var doorImg: DoorImageView? = null
    private var doorContainer: View? = null
    private var doorLp: WindowManager.LayoutParams? = null
    // we create the container once and reuse it; on close we only
    // detach it from the WindowManager (we don't build a new one every cycle)
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

    // ---------------- status strip (always visible) ----------------
    // The head-unit ROM does NOT show third-party notification icons in its own
    // status bar, so we draw the selected values as our own overlay at the top of
    // the screen, just below the system bar.

    fun updateStatus(text: String) {
        if (text.isEmpty()) { hideStatus(); return }
        if (!canDraw()) { hideStatus(); return }
        // reuse the existing view: refresh text + style (font size/color)
        statusView?.let { it.text = text; styleStatus(it); return }
        val tv = TextView(ctx).apply {
            this.text = text
            setTypeface(typeface, Typeface.BOLD)
            setPadding(20, 6, 24, 8)
        }
        styleStatus(tv)
        val lp = baseParams().apply {
            // touchable (draggable), but does not take focus
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.statusX >= 0) prefs.statusX else 8
            y = if (prefs.statusY >= 0) prefs.statusY else statusBarHeight()
        }
        // freely draggable anywhere; we save the new position
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

    /**
     * The status strip style: font size configurable from settings, NO background (box).
     *
     * Color: the overlay sits on the system status bar, which on this head unit is
     * ALWAYS dark (the system also draws light icons on it) - so the light/dark
     * uiMode was a bad signal (black text in the daytime -> invisible). The overlay
     * window cannot query the system status bar's color, so WHITE text
     * + a strong BLACK outline (subtitle style): it stays readable on any background -
     * on a dark bar the text shows, on light content the outline shows -, and it looks
     * like the system's white icons.
     */
    private fun styleStatus(tv: TextView) {
        tv.textSize = prefs.statusFontSize
        tv.setTextColor(Color.WHITE)
        tv.background = null
        tv.setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    /** Immediate restyle after a settings save (font size). */
    fun refreshStatusStyle() { statusView?.let { styleStatus(it) } }

    /** Moves the status strip back to the position stored in Prefs (or the default). */
    fun resetStatusView() {
        val v = statusView ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = if (prefs.statusX >= 0) prefs.statusX else 8
        lp.y = if (prefs.statusY >= 0) prefs.statusY else statusBarHeight()
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    private fun statusBarHeight(): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    }

    // ---------------- alert popup ----------------

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
            // level 1: disappears after 8 s; level 2: 20 s (or a tap). Cancel the PREVIOUS alert's
            // pending dismiss first -- a stale timer must not remove a newer (higher-level) alert early.
            alertDismiss?.let { main.removeCallbacks(it) }
            Runnable { removeAlert() }.also { alertDismiss = it; main.postDelayed(it, if (level >= 2) 20000L else 8000L) }
        } catch (_: Exception) {}
    }

    private fun removeAlert() {
        alertDismiss?.let { main.removeCallbacks(it) }
        alertDismiss = null
        alertView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        alertView = null
    }

    // ---------------- door overlay ----------------

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
                    box.translationY = -700f   // slide in from the top
                } catch (_: Exception) {
                    doorsVisible = false
                    return
                }
            }
            box.animate().translationY(0f).setDuration(250).start()
        }
        doorImg?.setMask(mask)
    }

    /** One-time build of the container + image view; we reuse it every cycle. */
    private fun ensureDoorContainer(): View? {
        doorContainer?.let { return it }
        // Pre-rendered top-view PNGs per combination (DoorImageView).
        // No GL/Filament -> no freezing, fast.
        val content: View = DoorImageView(ctx).also { doorImg = it }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xCC101418.toInt())
            setPadding(28, 20, 28, 24)
            addView(TextView(ctx).apply {
                text = ctx.getString(R.string.open_door)
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
            // withEndAction can also run on cancel: only remove it if we
            // actually stay hidden (a door didn't open again in the meantime)
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
