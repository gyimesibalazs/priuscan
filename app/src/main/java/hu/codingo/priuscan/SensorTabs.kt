package hu.codingo.priuscan

import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import hu.codingo.priuscan.icons.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Sensor-list tabs as responsive value-tile grids, driven by Fields.groups (no per-field layout).

/** Tile for a Field: number in the field's decimals, unit next to it (none when missing). The
 *  coolant tile is coloured by the coolant ramp; % fields carry a 0..100 bar (hero tiles only). */
@Composable
internal fun fieldSpec(state: CanState, f: Field): TileSpec {
    val v = state.d(f.key)
    return TileSpec(
        label = stringResource(f.labelRes),
        value = fmtNum(v, f.decimals),
        unit = if (v == null) "" else f.unit,
        color = if (f.key == "ct") coolantColor(v) else null,
        bar = if (f.unit == "%" && v != null) (v / 100.0).toFloat() else null,
        icon = tileIcon(f.key, f.unit),
    )
}

/** Leading icon of a tile: a few keys have their own, the rest go by unit. */
internal fun tileIcon(key: String, unit: String): ImageVector = when (key) {
    "maf", "hvAir" -> IcAir
    "thr", "pedal", "gasB" -> IcTune
    "load" -> IcMemory
    "injml" -> IcWaterDrop
    "soc" -> IcBatteryFull
    "weakB", "wblk", "rwblk" -> IcLayers
    "wz", "cwL", "rasym" -> IcInsights
    "battFan", "blower" -> IcWindPower
    "evap" -> IcAcUnit
    "comp" -> IcCyclone
    "solar" -> IcWbSunny
    "wDif" -> IcCompareArrows
    "gLat" -> IcSwapHoriz
    "gFwd" -> IcSwapVert
    "pitch" -> IcHeight
    "roll" -> IcSwapHoriz
    "yaw" -> IcRotateRight
    "steer" -> IcExplore
    "brkP", "brkPos" -> IcAdjust
    else -> when (unit) {
        "°C" -> IcThermostat
        "rpm", "km/h" -> IcSpeed
        "V", "kW", "W" -> IcBolt
        "A" -> IcElectricBolt
        "Nm" -> Icons.Outlined.Settings
        "kPa", "MPa" -> IcCompress
        "l/h" -> IcLocalGasStation
        "s" -> IcTimer
        "mΩ" -> IcElectricMeter
        "Ah", "kWh" -> IcBatteryChargingFull
        else -> IcSensors
    }
}

/** Generic group tab (engine, hybrid drive, climate). Engine: first row = hero tiles. */
@Composable
internal fun GroupTab(state: CanState, fields: List<Field>, hero: Boolean = false) = TabPage { cols ->
    TileGrid(fields.map { fieldSpec(state, it) }, cols, heroCount = if (hero) cols else 0)
}

/** Keys of the engine section; what follows them in the group is the driving section. */
private val ENGINE_KEYS = setOf("maf", "map", "iat", "thr", "injml", "run")

/** Engine + driving tab: rpm and speed as two round dials with the six live driving values beside
 *  them (the fields listed before the engine section), then the engine and the driving sections,
 *  drive mode + cruise and the exterior lights. */
@Composable
internal fun EngineDriveTab(state: CanState, fields: List<Field>) = TabPage { cols ->
    val rpmF = fields.firstOrNull { it.key == "rpm" }; val spdF = fields.firstOrNull { it.key == "spd" }
    val rest = fields.filter { it.key != "rpm" && it.key != "spd" }
    val firstEngine = rest.indexOfFirst { it.key in ENGINE_KEYS }.let { if (it < 0) rest.size else it }
    val top = rest.take(firstEngine).map { fieldSpec(state, it) }
    val engine = rest.drop(firstEngine).filter { it.key in ENGINE_KEYS }.map { fieldSpec(state, it) }
    val drive = rest.drop(firstEngine).filter { it.key !in ENGINE_KEYS }
    val dials: @Composable RowScope.() -> Unit = {
        if (rpmF != null) DialCard(state.d("rpm"), rpmF.unit, 7500f, 1500f, 2, Modifier.weight(1f).height(DIAL_H))
        if (spdF != null) DialCard(state.d("spd"), spdF.unit, 180f, 20f, 1, Modifier.weight(1f).height(DIAL_H))
    }
    if (cols >= 4) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1.15f), horizontalArrangement = Arrangement.spacedBy(12.dp), content = dials)
            Box(Modifier.weight(0.85f)) { TileGrid(top.take(6), 2) }
        }
        if (top.size > 6) TileGrid(top.drop(6), cols)
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), content = dials)
        TileGrid(top, cols)
    }
    SectionTitle(stringResource(R.string.grp_engine), Modifier.padding(top = 6.dp))
    TileGrid(engine, cols)
    SectionTitle(stringResource(R.string.grp_drive_brake), Modifier.padding(top = 6.dp))
    DriveSections(state, drive, cols)
}

private val DIAL_H = 288.dp     // = three tile rows

/** Round dial: 270 deg arc, labelled major ticks every [step], [minor] minor ticks between them,
 *  accent arc + needle up to the value (clamped to 0..[max]), the number + unit in the lower centre
 *  (no caption: the scale and the unit say what it is).
 *  The needle and the arc glide between the ~4 Hz samples (250 ms linear transition).
 *  The animated fraction is read ONLY inside the draw lambda, so a frame of the animation is a
 *  redraw of this one canvas -- no recomposition, no layout; the scale text is measured once up
 *  front, only the counting number is re-measured per frame.
 *  At rest nothing is drawn at all. */
@Composable
private fun DialCard(value: Double?, unit: String, max: Float, step: Float, minor: Int, modifier: Modifier) {
    val c = pc
    val tm = rememberTextMeasurer()
    // the VALUE is animated (not the clamped fraction), so the number counts along with the needle
    val anim = animateFloatAsState((value ?: 0.0).toFloat(), tween(DIAL_MS, easing = LinearEasing), label = "dial")
    val nMajor = (max / step).toInt()
    val tickLabels = remember(tm, max, step) {
        val st = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
        (0..nMajor).map { tm.measure("%.0f".format(it * step), st) }
    }
    val numStyle = remember { TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum") }
    val u = remember(tm, unit) { tm.measure(unit, TextStyle(fontSize = 15.sp)) }
    val hasValue = value != null
    PCard(modifier, padding = 10.dp) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 9.dp.toPx()
            // the 270 deg arc reaches 0.707 r below the centre -> the biggest circle that fits the card
            val r = minOf(size.width / 2 - sw, (size.height - 1.5f * sw) / 1.707f)
            val ctr = Offset(size.width / 2, r + sw)
            val arcTl = Offset(ctr.x - r, ctr.y - r)
            val shown = anim.value
            val f = (shown / max).coerceIn(0f, 1f)
            drawArc(c.track, 135f, 270f, false, arcTl, Size(2 * r, 2 * r), style = Stroke(sw, cap = StrokeCap.Round))
            if (f > 0f) drawArc(c.accent, 135f, 270f * f, false, arcTl, Size(2 * r, 2 * r), style = Stroke(sw, cap = StrokeCap.Round))
            fun dir(frac: Float): Offset { val a = Math.toRadians((135.0 + 270.0 * frac)); return Offset(kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat()) }
            for (i in 0..nMajor) {
                val d = dir(i / nMajor.toFloat())
                drawLine(c.text, ctr + d * (r - sw * 0.9f), ctr + d * (r - sw * 0.9f - 11.dp.toPx()), strokeWidth = 2.dp.toPx())
                val l = tickLabels[i]
                val lp = ctr + d * (r - sw * 0.9f - 28.dp.toPx())
                drawText(l, c.text, Offset(lp.x - l.size.width / 2f, lp.y - l.size.height / 2f))
                if (i < nMajor) for (k in 1..minor) {
                    val dm = dir((i + k / (minor + 1f)) / nMajor)
                    drawLine(c.dim, ctr + dm * (r - sw * 0.9f), ctr + dm * (r - sw * 0.9f - 6.dp.toPx()), strokeWidth = 1.dp.toPx())
                }
            }
            if (hasValue) {
                val d = dir(f)
                drawLine(c.text, ctr - d * (10.dp.toPx()), ctr + d * (r - sw * 0.9f - 14.dp.toPx()), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            drawCircle(c.card, 9.dp.toPx(), ctr); drawCircle(c.dim, 7.dp.toPx(), ctr)
            // the number shows where the needle IS right now (measured per frame: one short string)
            val num = tm.measure(if (hasValue) Math.round(shown).toString() else "–", numStyle)
            val ny = ctr.y + r * 0.22f
            drawText(num, c.text, Offset(ctr.x - num.size.width / 2f, ny))
            drawText(u, c.dim, Offset(ctr.x - u.size.width / 2f, ny + num.size.height - 4.dp.toPx()))
        }
    }
}

// Needle transition between two samples: 250 ms = the firmware's sample period, linear, so
// consecutive 4 Hz samples join into one even sweep.
private const val DIAL_MS = 250

/** The driving part of the engine + driving tab: the drive/brake fields + slope, then drive
 *  mode + cruise and exterior lights. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.DriveSections(state: CanState, fields: List<Field>, cols: Int) {
    TileGrid(fields.map { fieldSpec(state, it) }, cols)

    // drive mode (0x49B b4): NORMAL/ECO/PWR selector + independent EV toggle
    SectionTitle(stringResource(R.string.grp_drivemode), Modifier.padding(top = 6.dp))
    val dm = state.i("dmode")
    val ev = state.i("ev") != 0
    val cr = state.i("cruise") != 0
    TileGrid(listOf(
        TileSpec(stringResource(R.string.f_drivemode),
            when (dm) { 1 -> "ECO"; 2 -> "PWR"; else -> stringResource(R.string.dm_normal) }, highlight = dm != 0, icon = IcTune),
        TileSpec(stringResource(R.string.f_evmode), if (ev) "● EV" else "○", highlight = ev, icon = IcElectricCar),
        TileSpec(stringResource(R.string.f_cruise), if (cr) "●" else "○", highlight = cr, icon = IcSpeed),
    ), cols)

    SectionTitle(stringResource(R.string.grp_lights), Modifier.padding(top = 6.dp))
    val lt = state.i("lights")
    @Composable fun light(res: Int, on: Boolean) = TileSpec(stringResource(res), if (on) "●" else "○", highlight = on, icon = IcLightbulb)
    TileGrid(listOf(
        light(R.string.l_position, lt and 0x10 != 0),
        light(R.string.l_lowbeam, lt and 0x20 != 0),
        light(R.string.l_highbeam, lt and 0x40 != 0),
        light(R.string.l_fogfront, lt and 0x08 != 0),
        light(R.string.l_fogrear, lt and 0x04 != 0),
        // raw ambient-light level (0x620): ~31 bright .. ~600 dark; cluster flips at ~100
        TileSpec(stringResource(R.string.f_ambL), fmtNum(state.d("ambL"), 0), icon = IcLightMode),
    ), cols)
}

/** Keys of the battery group shown by the SOC arc / block chart / fan row instead of a tile. */
private val BATT_SPECIAL = setOf("soc", "bmin", "bmax", "blkD", "battFan", "tb1", "tb2", "tb3", "hvAir", "hvA")

/** Hybrid-battery tab: SOC arc + block-voltage chart, the remaining battery fields + SoH as
 *  tiles, the cooling fan as a bar, and the per-block internal resistance. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HybridBatteryTab(state: CanState, fields: List<Field>, refAh: Float) = TabPage { cols ->
    val c = pc
    if (cols >= 4) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SocCard(state, Modifier.width(290.dp).height(336.dp))
            BlockChartCard(state, wide = true, modifier = Modifier.weight(1f).height(336.dp))
        }
    } else {
        SocCard(state, Modifier.fillMaxWidth().height(250.dp))
        BlockChartCard(state, wide = false, modifier = Modifier.fillMaxWidth().height(366.dp))
    }

    // State of Health = learned capacity / reference capacity (degradation)
    val cap = state.d("capAh")
    val weak = state.i("weakB")
    val tiles = fields.filter { it.key !in BATT_SPECIAL }.map { f ->
        val sp = fieldSpec(state, f)
        if (f.key == "weakB" && weak in 1..14) sp.copy(highlight = true) else sp
    } + TileSpec(
        stringResource(R.string.f_capSoh),
        if (cap != null && refAh > 0) "%.0f".format(cap / refAh * 100) else "–",
        if (cap != null && refAh > 0) "%" else "",
        icon = IcBatteryChargingFull,
    )
    TileGrid(tiles, cols)

    // per-block internal resistance for the chart (on-demand "B"; refreshed every 5 s while this tab is open)
    LaunchedEffect(Unit) { while (true) { CanService.fetchBlockR(); kotlinx.coroutines.delay(5000) } }
}

/** SOC (0..100 %) on a 270° arc. */
@Composable
private fun SocCard(state: CanState, modifier: Modifier) {
    val c = pc
    val soc = state.d("soc")
    PCard(modifier) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 16.dp.toPx()
                val d = minOf(size.width, size.height) - sw
                val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
                val st = Stroke(sw, cap = StrokeCap.Round)
                drawArc(c.track, 135f, 270f, false, tl, Size(d, d), style = st)
                val f = ((soc ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
                if (f > 0f) drawArc(c.green, 135f, 270f * f, false, tl, Size(d, d), style = st)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row {
                    Text(soc?.let { "${it.toInt()}" } ?: "–", Modifier.alignByBaseline(), color = c.text,
                        fontSize = 54.sp, fontWeight = FontWeight.Bold, style = NumStyle, maxLines = 1)
                    if (soc != null) Text(" %", Modifier.alignByBaseline(), color = c.text, fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold)
                }
                Text(stringResource(R.string.f_soc), color = c.dim, fontSize = 16.sp, maxLines = 1)
                // pack current + the power it means right now (hvA x VL): ▲ = charging, ▼ = discharging
                val hvA = state.d("hvA")
                if (hvA != null) {
                    val kw = state.d("vl")?.let { kotlin.math.abs(hvA) * it / 1000.0 }
                    val dir = if (hvA < -0.5) "▲ " else if (hvA > 0.5) "▼ " else ""
                    Spacer(Modifier.height(8.dp))
                    Text(dir + (kw?.let { "%.1f kW".format(it) } ?: "– kW"), color = if (hvA < -0.5) c.greenText else c.text,
                        fontSize = 24.sp, fontWeight = FontWeight.SemiBold, style = NumStyle, maxLines = 1, softWrap = false)
                    Text("%.1f A".format(hvA), color = c.dim, fontSize = 17.sp, style = NumStyle, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** One chart for the 14 HV blocks: internal resistance as BARS (left axis, mOhm), block voltage
 *  as a LINE (right axis, V); the weakest block (weakB) in amber.
 *  Scales do not jump with the data:
 *   - resistance: 0..30 mOhm (blocks sit around 15), widened in whole 10 mOhm steps only if a block exceeds it;
 *   - voltage: a FIXED +-0.3 V window around the mean of the 14 blocks. The pack voltage swings by
 *     volts with load, but the blocks move together, so the line keeps its shape and only the
 *     axis numbers change. (Logs: block spread median 0.10 V, 99 % under 0.32 V.) A block outside
 *     the window is clamped to its edge. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockChartCard(state: CanState, wide: Boolean, modifier: Modifier) {
    val c = pc
    val volts = (1..14).map { state.d("b%02d".format(it)) }
    val res by CanService.blockR.collectAsState()
    val weak = state.i("weakB")
    val tm = rememberTextMeasurer()
    // temperature bands under the blocks: the three pack thermistors each over a third of the pack,
    // the intake air across the whole width. (Which blocks each thermistor really sits at is NOT
    // confirmed for this pack -- the thirds are a layout assumption.)
    val tbands = listOf(Triple(stringResource(R.string.f_tb1), state.d("tb1"), 0 to 5),
        Triple(stringResource(R.string.f_tb2), state.d("tb2"), 5 to 10),
        Triple(stringResource(R.string.f_tb3), state.d("tb3"), 10 to 14))
    val coolLabel = stringResource(R.string.batt_cooling); val air = state.d("hvAir")
    val fan = ((state.d("battFan") ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    fun tempColor(t: Double?): Color = when {
        t == null -> c.track
        t < 25 -> c.accent
        t < 40 -> c.green
        t < 48 -> c.amber
        else -> c.red
    }
    val presentV = volts.filterNotNull()
    val mean = if (presentV.isEmpty()) null else presentV.average()
    val rVals = (0 until 14).map { i -> res.getOrNull(i)?.takeIf { !it.isNaN() } }
    val rTop = maxOf(R_AXIS_MIN, kotlin.math.ceil(((rVals.filterNotNull().maxOrNull() ?: 0f) / 10f).toDouble()).toFloat() * 10f)
    PCard(modifier) {
        val legend: @Composable () -> Unit = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((col, line, res2) in listOf(Triple(c.green, false, R.string.hv_block_res), Triple(c.accent, true, R.string.hv_block_voltages)))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(if (line) 18.dp else 10.dp).height(if (line) 3.dp else 12.dp).background(col, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(res2) + if (line) " (V)" else " (mΩ)", color = c.dim, fontSize = 13.sp, maxLines = 1)
                    }
            }
        }
        val chips: @Composable () -> Unit = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((r, key) in listOf(R.string.f_bmin to "bmin", R.string.f_bmax to "bmax", R.string.f_blkD to "blkD"))
                    InfoChip(stringResource(r), state.d(key)?.let { "%.2f V".format(it) } ?: "–")
            }
        }
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(end = 12.dp)) { legend() }
                Box(Modifier.weight(1.4f, fill = false)) { chips() }
            }
        } else { legend(); Spacer(Modifier.height(6.dp)); chips() }
        Spacer(Modifier.height(8.dp))
        val small = TextStyle(fontSize = 12.sp, fontFeatureSettings = "tnum")
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val bandH = 24.dp.toPx(); val bandGap = 6.dp.toPx()
            val left = 30.dp.toPx(); val right = 46.dp.toPx(); val top = 18.dp.toPx()
            val bottom = 22.dp.toPx() + 2 * (bandH + bandGap)
            val pw = size.width - left - right; val ph = size.height - top - bottom
            if (pw <= 0f || ph <= 0f) return@Canvas
            fun label(t: String, x: Float, y: Float, col: Color, ax: Float = 0.5f, ay: Float = 0.5f, bold: Boolean = false) {
                val l = tm.measure(t, if (bold) small.copy(fontWeight = FontWeight.Bold) else small)
                drawText(l, col, Offset(x - l.size.width * ax, y - l.size.height * ay))
            }
            // left axis: resistance grid (0 .. rTop in 10 mOhm steps)
            var g = 0f
            while (g <= rTop + 0.01f) {
                val y = top + ph * (1f - g / rTop)
                drawLine(c.divider, Offset(left, y), Offset(left + pw, y), strokeWidth = 1f)
                label("%.0f".format(g), left - 6.dp.toPx(), y, c.dim, ax = 1f)
                g += 10f
            }
            // right axis: voltage window around the mean
            if (mean != null) for (d in listOf(-V_WINDOW, 0.0, V_WINDOW)) {
                val y = top + ph * (0.5f - (d / (2 * V_WINDOW)).toFloat() * V_BAND)
                label("%.2f".format(mean + d), left + pw + 6.dp.toPx(), y, c.accent, ax = 0f)
            }
            val cw = pw / 14f
            val pts = ArrayList<Offset?>(14)
            for (i in 0 until 14) {
                val isWeak = weak == i + 1
                val cx = left + cw * (i + 0.5f)
                rVals[i]?.let { r ->
                    val h = ph * (r / rTop).coerceIn(0f, 1f)
                    drawRoundRect(if (isWeak) c.amber else c.green, Offset(cx - cw * 0.32f, top + ph - h), Size(cw * 0.64f, h),
                        CornerRadius(4.dp.toPx()))
                    if (h > 20.dp.toPx()) label("%.1f".format(r), cx, top + ph - 9.dp.toPx(), Color(0xFF06121C), bold = true)
                    else label("%.1f".format(r), cx, top + ph - h - 8.dp.toPx(), c.dim)
                }
                label("b%02d".format(i + 1), cx, top + ph + 11.dp.toPx(), if (isWeak) c.amberText else c.dim, bold = isWeak)
                val v = volts[i]
                pts += if (v == null || mean == null) null else {
                    val f = ((v - mean) / (2 * V_WINDOW)).toFloat().coerceIn(-0.5f, 0.5f) * V_BAND
                    Offset(cx, top + ph * (0.5f - f))
                }
            }
            // temperature bands
            val bandText = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
            fun band(x0: Float, x1: Float, y: Float, col: Color, t: String) {
                drawRoundRect(col, Offset(x0, y), Size(x1 - x0, bandH), CornerRadius(bandH / 2))
                val l = tm.measure(t, bandText)
                if (l.size.width < x1 - x0) drawText(l, Color(0xFF06121C), Offset((x0 + x1) / 2 - l.size.width / 2f, y + bandH / 2 - l.size.height / 2f))
            }
            val by = top + ph + 22.dp.toPx()
            for ((name, t, span) in tbands)
                band(left + cw * span.first + 2.dp.toPx(), left + cw * span.second - 2.dp.toPx(), by, tempColor(t),
                    name + "  " + (t?.let { "%.0f °C".format(it) } ?: "–"))
            // cooling: a progress bar of the battery fan (battFan %), the intake-air temperature as its caption
            val fx0 = left + 2.dp.toPx(); val fx1 = left + pw - 2.dp.toPx(); val fy = by + bandH + bandGap
            drawRoundRect(c.track, Offset(fx0, fy), Size(fx1 - fx0, bandH), CornerRadius(bandH / 2))
            val fw = (fx1 - fx0) * fan
            if (fw > 0f) drawRoundRect(c.accent, Offset(fx0, fy), Size(maxOf(fw, bandH), bandH), CornerRadius(bandH / 2))
            val cl = tm.measure(coolLabel + ": " + (air?.let { "%.0f °C".format(it) } ?: "–"), bandText)
            val cpos = Offset((fx0 + fx1) / 2 - cl.size.width / 2f, fy + bandH / 2 - cl.size.height / 2f)
            drawText(cl, c.text, cpos)                                   // on the empty track
            if (fw > 0f) clipRect(fx0, fy, fx0 + maxOf(fw, bandH), fy + bandH) { drawText(cl, c.onAccent, cpos) }   // on the fill
            for (i in 0 until 13) { val a = pts[i]; val b = pts[i + 1]
                if (a != null && b != null) drawLine(c.accent, a, b, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round) }
            pts.forEachIndexed { i, p -> if (p != null) {
                drawCircle(c.card, 6.dp.toPx(), p); drawCircle(if (weak == i + 1) c.amber else c.accent, 4.dp.toPx(), p)
                label("%.2f".format(volts[i]), p.x, p.y - 13.dp.toPx(), c.text)
            } }
        }
    }
}

private const val R_AXIS_MIN = 30f     // mOhm: top of the resistance axis unless a block exceeds it
private const val V_WINDOW = 0.3       // V either side of the block mean on the voltage axis
private const val V_BAND = 0.8f        // share of the plot height the voltage window spans (room for labels)

/** Tyre pressure: top-view car with a card per wheel + the spare. */
@Composable
internal fun TpmsTab(tpms: Map<Wheel, TireReading>) = TabPage {
    SectionTitle(stringResource(R.string.tpms_title))
    TpmsCarView(tpms)
}

@Composable
internal fun GpsTab(loc: Location?) = TabPage { cols ->
    TileGrid(listOf(
        TileSpec(stringResource(R.string.f_lat), loc?.let { "%.6f".format(it.latitude) } ?: stringResource(R.string.gps_no_fix), icon = IcMyLocation),
        TileSpec(stringResource(R.string.f_lon), loc?.let { "%.6f".format(it.longitude) } ?: "–", icon = IcMyLocation),
        TileSpec(stringResource(R.string.f_gspd), loc?.let { "%.0f".format(it.speed * 3.6f) } ?: "–", if (loc != null) "km/h" else "", icon = IcSpeed),
        TileSpec(stringResource(R.string.f_galt), loc?.let { "%.0f".format(it.altitude) } ?: "–", if (loc != null) "m" else "", icon = IcHeight),
        TileSpec(stringResource(R.string.f_gbrg), loc?.let { "%.0f".format(it.bearing) } ?: "–", if (loc != null) "°" else "", icon = IcNavigation),
        TileSpec(stringResource(R.string.f_gacc), loc?.let { "%.0f".format(it.accuracy) } ?: "–", if (loc != null) "m" else "", icon = IcGpsFixed),
    ), cols)
}
