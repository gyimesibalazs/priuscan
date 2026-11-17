package hu.codingo.priuscan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Top-view car (the pre-rendered car_00.png "all doors closed" silhouette) centred, with a card
 * per wheel beside its corner + the spare card under the trunk. Per card: sensor ID, pressure
 * (big), temperature, low-battery glyph and the raw D2 flags. Dimmed when missing/stale.
 * (No OK/LOW verdict: there is no pressure threshold in the app yet - Phase 4 alerts.)
 */
@Composable
fun TpmsCarView(readings: Map<Wheel, TireReading>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp = remember {
        try { ctx.assets.open("car_00.png").use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }
        catch (_: Exception) { null }
    }
    val now = System.currentTimeMillis()
    val ids by CanService.tpmsIds.collectAsState()
    @Composable fun card(w: Wheel, m: Modifier) = WheelCard(readings[w], ids[w], now, m)
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        if (maxWidth >= 760.dp) {
            // wide: [FL / RL] car [FR / RR], spare centred underneath
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.height(340.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceAround) {
                        card(Wheel.FL, Modifier.width(CARD_W)); card(Wheel.RL, Modifier.width(CARD_W))
                    }
                    if (bmp != null)
                        Image(bmp, contentDescription = null, modifier = Modifier.fillMaxHeight(), contentScale = ContentScale.Fit)
                    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceAround) {
                        card(Wheel.FR, Modifier.width(CARD_W)); card(Wheel.RR, Modifier.width(CARD_W))
                    }
                }
                Spacer(Modifier.height(12.dp))
                card(Wheel.SPARE, Modifier.width(CARD_W))
            }
        } else {
            // narrow: car on top, then a 2-column grid of wheel cards + the spare
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (bmp != null)
                    Image(bmp, contentDescription = null, modifier = Modifier.height(200.dp), contentScale = ContentScale.Fit)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    card(Wheel.FL, Modifier.weight(1f)); card(Wheel.FR, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    card(Wheel.RL, Modifier.weight(1f)); card(Wheel.RR, Modifier.weight(1f))
                }
                card(Wheel.SPARE, Modifier.fillMaxWidth(0.5f))
            }
        }
    }
}

private val CARD_W = 260.dp

@Composable
private fun WheelCard(r: TireReading?, id: String?, now: Long, modifier: Modifier) {
    val c = pc
    val stale = r == null || r.stale(now)
    PCard(modifier) {
        // sensor ID instead of a position label - the position is obvious from the layout
        Text(id ?: "–", color = c.dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        Row {
            Text(
                if (r == null) "–" else "%.2f".format(r.bar), Modifier.alignByBaseline(),
                color = if (stale) c.dim else c.accent, fontWeight = FontWeight.Bold, fontSize = 38.sp,
                style = NumStyle, maxLines = 1, softWrap = false,
            )
            Spacer(Modifier.width(6.dp))
            Text("bar", Modifier.alignByBaseline(), color = if (stale) c.dim else c.text,
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (r == null) "–°C" else "${r.tempC}°C",
                color = if (stale) c.dim else c.text, fontSize = 17.sp, style = NumStyle,
            )
            // empty-battery icon when the sensor reports a weak battery (D2 bit4)
            if (r != null && r.lowBatt) {
                Spacer(Modifier.size(8.dp))
                EmptyBatteryIcon(c.amberText)
            }
        }
        // raw D2 flag byte (hex) shown when non-zero - the D2 bits are not fully
        // decoded yet, so per TPMS_PROTOCOL.md we surface the raw value for the
        // calibration drive (watch which bit flips on a known event).
        if (r != null && r.flags != 0) {
            Text(
                "D2 0x%02X".format(r.flags),
                color = c.dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** A small empty (low) battery glyph drawn with Canvas - no icon dependency. */
@Composable
private fun EmptyBatteryIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 18.dp, height = 11.dp)) {
        val w = size.width
        val h = size.height
        val bodyW = w * 0.82f
        val s = h * 0.16f
        // body outline (empty inside)
        drawRoundRect(
            color = color,
            topLeft = Offset(s / 2, s / 2),
            size = Size(bodyW - s, h - s),
            cornerRadius = CornerRadius(s, s),
            style = Stroke(width = s),
        )
        // positive terminal nub on the right
        drawRect(color = color, topLeft = Offset(bodyW, h * 0.3f), size = Size(w - bodyW, h * 0.4f))
    }
}
