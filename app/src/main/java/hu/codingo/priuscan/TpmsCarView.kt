package hu.codingo.priuscan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
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
 * Top-view car (the pre-rendered car_00.png "all doors closed" silhouette) with a
 * tyre chip at each corner + the spare in the middle. Per chip: pressure on top
 * (big), temperature below (small). Grey when missing/stale, white otherwise.
 * (Target-range colouring belongs to Phase 4 alerts.)
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
    // cap width so the corner tyre values sit close to the car (not flung to the
    // far edges on a wide landscape screen), centered. Spare goes UNDER the car (the trunk).
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(280.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(bmp, contentDescription = null, modifier = Modifier.fillMaxHeight(), contentScale = ContentScale.Fit)
                }
                WheelChip(readings[Wheel.FL], ids[Wheel.FL], now, Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp))
                WheelChip(readings[Wheel.FR], ids[Wheel.FR], now, Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 8.dp))
                WheelChip(readings[Wheel.RL], ids[Wheel.RL], now, Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp))
                WheelChip(readings[Wheel.RR], ids[Wheel.RR], now, Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp))
            }
            // spare: under the trunk (below the rear of the car)
            WheelChip(readings[Wheel.SPARE], ids[Wheel.SPARE], now, Modifier.padding(top = 4.dp, bottom = 8.dp))
        }
    }
}

@Composable
private fun WheelChip(r: TireReading?, id: String?, now: Long, modifier: Modifier) {
    val stale = r == null || r.stale(now)
    val color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground
    // sensor ID instead of a position label - the position is obvious from the layout
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(id ?: "–", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(
            if (r == null) "– bar" else "%.2f bar".format(r.bar),
            color = color, fontWeight = FontWeight.Bold, fontSize = 20.sp, fontFamily = FontFamily.Monospace,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (r == null) "–°C" else "${r.tempC}°C",
                color = color, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            )
            // empty-battery icon when the sensor reports a weak battery (D2 bit4)
            if (r != null && r.lowBatt) {
                Spacer(Modifier.size(5.dp))
                EmptyBatteryIcon(Color(0xFFFFB74D))
            }
        }
        // raw D2 flag byte (hex) shown when non-zero - the D2 bits are not fully
        // decoded yet, so per TPMS_PROTOCOL.md we surface the raw value for the
        // calibration drive (watch which bit flips on a known event).
        if (r != null && r.flags != 0) {
            Text(
                "D2 0x%02X".format(r.flags),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
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
