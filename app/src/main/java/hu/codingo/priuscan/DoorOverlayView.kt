package hu.codingo.priuscan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Prius XW30 felulnezeti rajz (valos 2.56 hossz/szelesseg arannyal).
 * Nyitott ajtok kifordult piros panelkent (ablak-kivagassal), csukottak
 * zold illesztesi vonalkent. Csomagterajto a far moge kihajtva.
 *
 * Bitek: 0x80 vezeto (BE), 0x40 utas (JE), 0x04 hatsok (a CAN nem bontja
 * oldalra), hatchBit = csomagter - ALAPERTELMEZESBEN 0x01, az elso dump
 * sessionbol verifikalando es itt allithato!
 */
class DoorOverlayView(ctx: Context) : View(ctx) {

    var mask: Int = 0
        set(v) { field = v; invalidate() }

    var hatchBit: Int = 0x01

    // design ter: x 170..510 (340 szeles), y 35..500 (465 magas)
    private val designW = 340f
    private val designH = 465f

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFFE8EBEE.toInt()
    }
    private val bodyEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = 0xFF8C969E.toInt()
    }
    private val detail = Paint(bodyEdge).apply { strokeWidth = 1.2f; color = 0xFFB6BEC5.toInt() }
    private val crease = Paint(bodyEdge).apply { strokeWidth = 1f; color = 0xFFC2C9CF.toInt() }
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF36444E.toInt()
    }
    private val dark = Paint(glass).apply { color = 0xFF37434C.toInt() }
    private val seamClosed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.5f; color = 0xFF1D9E75.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val panelOpen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFFE24B4A.toInt()
    }
    private val panelEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f; color = 0xFFA32D2D.toInt()
    }
    private val panelWin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0x8C7A2020.toInt()
    }
    private val swingArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 0.8f; color = 0x80E24B4A.toInt()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width / designW, height / designH)
        c.save()
        c.translate((width - designW * s) / 2f, (height - designH * s) / 2f)
        c.scale(s, s)
        c.translate(-170f, -35f)
        drawCar(c)
        c.restore()
    }

    private fun drawCar(c: Canvas) {
        // karosszeria
        val outline = Path().apply {
            moveTo(340f, 42f)
            cubicTo(308f, 43f, 278f, 55f, 268f, 77f)
            cubicTo(260f, 93f, 256f, 118f, 256f, 152f)
            lineTo(256f, 200f)
            cubicTo(256f, 262f, 258f, 312f, 262f, 354f)
            cubicTo(266f, 390f, 288f, 409f, 318f, 411f)
            lineTo(362f, 411f)
            cubicTo(392f, 409f, 414f, 390f, 418f, 354f)
            cubicTo(422f, 312f, 424f, 262f, 424f, 200f)
            lineTo(424f, 152f)
            cubicTo(424f, 118f, 420f, 93f, 412f, 77f)
            cubicTo(402f, 55f, 372f, 43f, 340f, 42f)
            close()
        }
        c.drawPath(outline, body)
        c.drawPath(outline, bodyEdge)

        // lokharito iv + fenyszorok + motorhazteto eleк
        c.drawPath(Path().apply {
            moveTo(276f, 71f); cubicTo(306f, 59f, 374f, 59f, 404f, 71f)
        }, detail)
        c.drawPath(Path().apply {
            moveTo(270f, 78f); cubicTo(284f, 68f, 302f, 63f, 318f, 61f)
            lineTo(314f, 74f); cubicTo(300f, 76f, 284f, 80f, 274f, 87f); close()
        }, dark)
        c.drawPath(Path().apply {
            moveTo(410f, 78f); cubicTo(396f, 68f, 378f, 63f, 362f, 61f)
            lineTo(366f, 74f); cubicTo(380f, 76f, 396f, 80f, 406f, 87f); close()
        }, dark)
        c.drawPath(Path().apply {
            moveTo(286f, 90f); cubicTo(293f, 112f, 295f, 130f, 293f, 144f)
        }, crease)
        c.drawPath(Path().apply {
            moveTo(394f, 90f); cubicTo(387f, 112f, 385f, 130f, 387f, 144f)
        }, crease)

        // uvegek: szelvedo, oldaluvegek, hatso (hatch) uveg
        c.drawPath(Path().apply {
            moveTo(270f, 148f); cubicTo(308f, 138f, 372f, 138f, 410f, 148f)
            lineTo(396f, 202f); cubicTo(368f, 194f, 312f, 194f, 284f, 202f); close()
        }, glass)
        c.drawPath(Path().apply {
            moveTo(283f, 207f); cubicTo(275f, 232f, 273f, 264f, 275f, 293f)
            lineTo(284f, 296f); cubicTo(282f, 264f, 284f, 232f, 289f, 209f); close()
        }, glass)
        c.drawPath(Path().apply {
            moveTo(397f, 207f); cubicTo(405f, 232f, 407f, 264f, 405f, 293f)
            lineTo(396f, 296f); cubicTo(398f, 264f, 396f, 232f, 391f, 209f); close()
        }, glass)
        c.drawPath(Path().apply {
            moveTo(289f, 300f); cubicTo(314f, 306f, 366f, 306f, 391f, 300f)
            lineTo(400f, 350f); cubicTo(366f, 360f, 314f, 360f, 280f, 350f); close()
        }, glass)
        // spoiler-el + cápauszony antenna
        c.drawPath(Path().apply {
            moveTo(276f, 358f); cubicTo(310f, 366f, 370f, 366f, 404f, 358f)
        }, detail)
        c.drawCircle(340f, 386f, 3.5f, dark)

        // tukrok
        c.drawPath(Path().apply {
            moveTo(256f, 152f); lineTo(238f, 146f); quadTo(230f, 148f, 234f, 157f)
            lineTo(256f, 165f); close()
        }, body)
        c.drawPath(Path().apply {
            moveTo(256f, 152f); lineTo(238f, 146f); quadTo(230f, 148f, 234f, 157f)
            lineTo(256f, 165f); close()
        }, bodyEdge)
        c.drawPath(Path().apply {
            moveTo(424f, 152f); lineTo(442f, 146f); quadTo(450f, 148f, 446f, 157f)
            lineTo(424f, 165f); close()
        }, body)
        c.drawPath(Path().apply {
            moveTo(424f, 152f); lineTo(442f, 146f); quadTo(450f, 148f, 446f, 157f)
            lineTo(424f, 165f); close()
        }, bodyEdge)

        // --- ajtok ---
        val driver = mask and 0x80 != 0
        val pass = mask and 0x40 != 0
        val rear = mask and 0x04 != 0
        val hatch = mask and hatchBit != 0

        door(c, hx = 256f, hy = 154f, len = 98f, leftSide = true, open = driver)
        door(c, hx = 424f, hy = 154f, len = 98f, leftSide = false, open = pass)
        door(c, hx = 257f, hy = 256f, len = 80f, leftSide = true, open = rear)
        door(c, hx = 423f, hy = 256f, len = 80f, leftSide = false, open = rear)
        drawHatch(c, hatch)
    }

    private fun door(c: Canvas, hx: Float, hy: Float, len: Float, leftSide: Boolean, open: Boolean) {
        if (!open) {
            c.drawPath(Path().apply {
                moveTo(hx, hy + 2f)
                cubicTo(hx, hy + len * 0.33f, hx, hy + len * 0.66f,
                        hx + (if (leftSide) 1f else -1f), hy + len - 2f)
            }, seamClosed)
            return
        }
        // nyitasi iv
        c.drawArc(
            RectF(hx - len, hy - len, hx + len, hy + len),
            if (leftSide) 90f else 35f, 55f, false, swingArc
        )
        // kifordult ajtopanel + ablak-kivagas
        c.save()
        c.rotate(if (leftSide) 55f else -55f, hx, hy)
        val rect = if (leftSide) RectF(hx - 11f, hy, hx + 7f, hy + len)
                   else RectF(hx - 7f, hy, hx + 11f, hy + len)
        c.drawRoundRect(rect, 8f, 8f, panelOpen)
        c.drawRoundRect(rect, 8f, 8f, panelEdge)
        val win = RectF(rect.centerX() - 4f, hy + len * 0.13f,
                        rect.centerX() + 4f, hy + len * 0.62f)
        c.drawRoundRect(win, 4f, 4f, panelWin)
        c.restore()
    }

    private fun drawHatch(c: Canvas, open: Boolean) {
        if (!open) {
            c.drawPath(Path().apply {
                moveTo(280f, 366f); cubicTo(312f, 373f, 368f, 373f, 400f, 366f)
            }, seamClosed)
            return
        }
        // lengesvonalak a far sarkaitol
        c.drawPath(Path().apply {
            moveTo(296f, 414f); cubicTo(292f, 428f, 288f, 438f, 287f, 446f)
        }, swingArc)
        c.drawPath(Path().apply {
            moveTo(384f, 414f); cubicTo(388f, 428f, 392f, 438f, 393f, 446f)
        }, swingArc)
        // kihajtott csomagterajto panel + uveg
        val panel = Path().apply {
            moveTo(298f, 448f); lineTo(382f, 448f)
            cubicTo(394f, 448f, 400f, 455f, 398f, 466f)
            lineTo(396f, 478f); cubicTo(394f, 488f, 386f, 492f, 374f, 492f)
            lineTo(306f, 492f); cubicTo(294f, 492f, 286f, 488f, 284f, 478f)
            lineTo(282f, 466f); cubicTo(280f, 455f, 286f, 448f, 298f, 448f)
            close()
        }
        c.drawPath(panel, panelOpen)
        c.drawPath(panel, panelEdge)
        c.drawPath(Path().apply {
            moveTo(300f, 456f); lineTo(380f, 456f)
            cubicTo(386f, 456f, 389f, 460f, 388f, 466f)
            lineTo(387f, 470f); cubicTo(386f, 474f, 382f, 476f, 376f, 476f)
            lineTo(304f, 476f); cubicTo(298f, 476f, 294f, 474f, 293f, 470f)
            lineTo(292f, 466f); cubicTo(291f, 460f, 294f, 456f, 300f, 456f)
            close()
        }, panelWin)
    }
}
