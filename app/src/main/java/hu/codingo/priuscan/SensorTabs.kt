package hu.codingo.priuscan

import android.location.Location
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import hu.codingo.priuscan.icons.*
import androidx.compose.ui.text.font.FontWeight
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
    "acAmb", "solar" -> IcWbSunny
    "wFL", "wFR", "wRL", "wRR" -> IcTireRepair
    "wDif" -> IcCompareArrows
    "gLat" -> IcSwapHoriz
    "gFwd" -> IcSwapVert
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

/** Driving tab = the drive/brake fields + slope + drive mode, cruise/turn and exterior lights. */
@Composable
internal fun DriveTab(state: CanState, fields: List<Field>, prefs: Prefs) = TabPage { cols ->
    val t by CanService.tilt.collectAsState()
    // slope from the G sensor (same values as the dashboard level, zero offset applied)
    val pitch = t?.let { it.pitch - prefs.tiltZeroPitch }
    val roll = t?.let { it.roll - prefs.tiltZeroRoll }
    TileGrid(
        fields.map { fieldSpec(state, it) } + listOf(
            TileSpec(stringResource(R.string.f_pitch), fmtNum(pitch, 1), if (pitch == null) "" else "°", icon = IcHeight),
            TileSpec(stringResource(R.string.f_roll), fmtNum(roll, 1), if (roll == null) "" else "°", icon = IcSwapHoriz),
        ),
        cols,
    )

    // drive mode (0x49B b4): NORMAL/ECO/PWR selector + independent EV toggle
    SectionTitle(stringResource(R.string.grp_drivemode), Modifier.padding(top = 6.dp))
    val dm = state.i("dmode")
    val ev = state.i("ev") != 0
    TileGrid(listOf(
        TileSpec(stringResource(R.string.f_drivemode),
            when (dm) { 1 -> "ECO"; 2 -> "PWR"; else -> stringResource(R.string.dm_normal) }, highlight = dm != 0, icon = IcTune),
        TileSpec(stringResource(R.string.f_evmode), if (ev) "● EV" else "○", highlight = ev, icon = IcElectricCar),
    ), cols)

    // cruise control + turn signal (firmware decodes them; not elsewhere in the UI)
    SectionTitle(stringResource(R.string.grp_cruise), Modifier.padding(top = 6.dp))
    val cr = state.i("cruise") != 0
    val ss = state.d("setSpd")
    val turn = state.i("turn")
    TileGrid(listOf(
        TileSpec(stringResource(R.string.f_cruise), if (cr) "●" else "○", highlight = cr, icon = IcSpeed),
        TileSpec(stringResource(R.string.f_setSpd), if (ss != null && ss > 0) "%.0f".format(ss) else "–",
            if (ss != null && ss > 0) "km/h" else "", icon = IcSpeed),
        TileSpec(stringResource(R.string.f_turn), when (turn) { 1 -> "◄"; 2 -> "►"; else -> "–" },
            highlight = turn == 1 || turn == 2, icon = IcTurnRight),
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
private val BATT_SPECIAL = setOf("soc", "bmin", "bmax", "blkD", "battFan")

/** Hybrid-battery tab: SOC arc + block-voltage chart, the remaining battery fields + SoH as
 *  tiles, the cooling fan as a bar, and the per-block internal resistance. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HybridBatteryTab(state: CanState, fields: List<Field>, refAh: Float) = TabPage { cols ->
    val c = pc
    if (cols >= 4) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SocCard(state, Modifier.width(290.dp).height(270.dp))
            BlockChartCard(state, wide = true, modifier = Modifier.weight(1f).height(270.dp))
        }
    } else {
        SocCard(state, Modifier.fillMaxWidth().height(250.dp))
        BlockChartCard(state, wide = false, modifier = Modifier.fillMaxWidth().height(300.dp))
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

    // battery cooling fan as a progress row
    val fan = state.d("battFan")
    PCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.f_battFan), Modifier.weight(0.35f), color = c.text, fontSize = 17.sp,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(12.dp))
            ThinBar(((fan ?: 0.0) / 100.0).toFloat(), c.green, Modifier.weight(0.65f), height = 12.dp)
            Text(
                if (fan == null) "–" else "%.0f %%".format(fan), Modifier.width(96.dp),
                color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, style = NumStyle,
                textAlign = TextAlign.End, maxLines = 1, softWrap = false,
            )
        }
    }

    // per-block internal resistance (on-demand "B"; refreshed every 5 s while this tab is open)
    SectionTitle(stringResource(R.string.hv_block_res), Modifier.padding(top = 6.dp))
    LaunchedEffect(Unit) { while (true) { CanService.fetchBlockR(); kotlinx.coroutines.delay(5000) } }
    val r by CanService.blockR.collectAsState()
    TileGrid(
        (1..14).map { i ->
            val v = r.getOrNull(i - 1)
            val ok = v != null && !v.isNaN()
            TileSpec(stringResource(R.string.block_n, i), if (ok) "%.1f".format(v) else "–", if (ok) "mΩ" else "")
        },
        cols = if (cols >= 4) 7 else cols + 1, compact = true,
    )
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
            }
        }
    }
}

/** b01..b14 block voltages as bars (auto-scaled around the current min..max so ~10 mV
 *  differences are visible); the weakest block (weakB) in amber. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockChartCard(state: CanState, wide: Boolean, modifier: Modifier) {
    val c = pc
    val vals = (1..14).map { state.d("b%02d".format(it)) }
    val weak = state.i("weakB")
    val present = vals.filterNotNull()
    val mn = present.minOrNull() ?: 0.0
    val mx = present.maxOrNull() ?: 1.0
    val span = maxOf(mx - mn, 0.05)
    val lo = mn - span * 0.6; val hi = mx + span * 0.1        // bar floor below the minimum
    PCard(modifier) {
        val chips: @Composable () -> Unit = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((res, key) in listOf(R.string.f_bmin to "bmin", R.string.f_bmax to "bmax", R.string.f_blkD to "blkD"))
                    InfoChip(stringResource(res), state.d(key)?.let { "%.2f V".format(it) } ?: "–")
            }
        }
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardTitle(stringResource(R.string.hv_block_voltages), Modifier.weight(1f).padding(end = 12.dp))
                Box(Modifier.weight(1.4f, fill = false)) { chips() }
            }
        } else {
            CardTitle(stringResource(R.string.hv_block_voltages))
            Spacer(Modifier.height(6.dp))
            chips()
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            vals.forEachIndexed { i, v ->
                val isWeak = weak == i + 1
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    // bar area (value above the bar); bars scale to the space left under the value text
                    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom) {
                        Text(v?.let { "%.2f".format(it) } ?: "–", color = if (isWeak) c.amberText else c.text,
                            fontSize = 12.sp, style = NumStyle, maxLines = 1, softWrap = false)
                        Spacer(Modifier.height(2.dp))
                        val f = v?.let { ((it - lo) / (hi - lo)).toFloat().coerceIn(0.03f, 1f) } ?: 0f
                        if (f > 0f) Box(
                            Modifier.fillMaxWidth(0.72f).fillMaxHeight(f)
                                .background(if (isWeak) c.amber else c.green, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                    Text("b%02d".format(i + 1), Modifier.padding(top = 3.dp),
                        color = if (isWeak) c.amberText else c.dim, fontSize = 12.sp,
                        fontWeight = if (isWeak) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

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
