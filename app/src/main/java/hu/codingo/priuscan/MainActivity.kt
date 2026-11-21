package hu.codingo.priuscan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CanService.start(this)   // the service also runs on USB attach / launcher start
        val prefs = Prefs(this)
        CanService.carDark.value = prefs.darkLast   // start in the last-known mode (no flash)
        setContent {
            val carDark by CanService.carDark.collectAsState()
            val dark = if (prefs.autoDarkCar) carDark else androidx.compose.foundation.isSystemInDarkTheme()
            PriusTheme(dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(prefs = prefs)
                }
            }
        }
    }
}

@Composable
fun MainScreen(prefs: Prefs) {
    val state by CanService.state.collectAsState()
    val tpmsReadings by CanService.tpms.collectAsState()
    val gpsLoc by CanService.gps.collectAsState()

    val groups = Fields.groups
    var tab by remember { mutableStateOf(0) }
    // tab order: Dashboard | <each sensor group> | GPS | Settings
    // (HV block voltages are merged into the hybrid-battery tab; no Trip/Other/Maintenance/DEBUG tabs)
    val titles = buildList {
        add(stringResource(R.string.tab_dashboard))
        groups.forEach { add(stringResource(it.first)) }
        add(stringResource(R.string.tab_tpms))
        add(stringResource(R.string.gps_title))
        add(stringResource(R.string.settings_title))
    }

    Column(Modifier.fillMaxSize()) {
        CapsuleTabBar(
            titles, tab.coerceIn(0, titles.size - 1), { tab = it },
            Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
        )
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> DashboardTab(state, prefs)
                in 1..groups.size -> {
                    val (titleRes, fields) = groups[tab - 1]
                    when (titleRes) {
                        R.string.grp_hybrid_batt -> HybridBatteryTab(state, fields, prefs.batteryRefAh)
                        R.string.grp_engine_drive -> EngineDriveTab(state, fields, prefs)
                        else -> GroupTab(state, fields)
                    }
                }
                groups.size + 1 -> TpmsTab(tpmsReadings)
                groups.size + 2 -> GpsTab(gpsLoc)
                else -> SettingsScreen(prefs) { tab = 0 }
            }
        }
    }
}

// ================================ Dashboard ================================

private val HEADER_H = 164.dp      // common min height of the header card and the gauges card

/** Tab 1: glance dashboard. Wide screen: [level card] | [header card | gauges card] above the
 *  trip chips + the selected trip's statistics card. The level card exists only with tilt data. */
@Composable
private fun DashboardTab(state: CanState, prefs: Prefs) {
    val tilt by CanService.tilt.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // narrow screens: no level card (the angles are still listed on the drive tab)
        val t = tilt
        val showLevel = t != null && maxWidth >= 900.dp
        Row(
            Modifier.widthIn(max = 1400.dp).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showLevel && t != null)
                PCard(horizontalAlignment = Alignment.CenterHorizontally) { LevelPanel(t, prefs) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeaderCard(state, Modifier.weight(1f))
                    GaugesCard(state)
                }
                TripSection()
            }
        }
    }
}

private val LEVEL_SIZE = 170.dp
private const val LEVEL_RANGE = 8.0   // deg at the end of a tube

/** Bullseye level, the car seen from above: the bubble drifts towards the HIGHER end like in a
 *  real circular level -- up = nose up, left = right side lower. Rings at 4 and 8 deg; the bubble
 *  stops on the rim and turns amber beyond 8 deg. Hold the panel ~1.3 s at standstill to store
 *  the current attitude as level. */
@Composable
private fun LevelPanel(t: Tilt, prefs: Prefs, modifier: Modifier = Modifier) {
    var zeroP by remember { mutableStateOf(prefs.tiltZeroPitch) }
    var zeroR by remember { mutableStateOf(prefs.tiltZeroRoll) }
    val cur by androidx.compose.runtime.rememberUpdatedState(t)
    var holding by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    LaunchedEffect(holding) {
        if (!holding) { progress = 0f; return@LaunchedEffect }
        val start = withFrameNanos { it }
        while (holding) {
            val now = withFrameNanos { it }
            progress = ((now - start) / 1_300_000_000f).coerceAtMost(1f)
            if (progress >= 1f) {
                if (cur.speedKmh < 1.0) {              // zero only at standstill
                    zeroP = cur.pitch.toFloat(); zeroR = cur.roll.toFloat()
                    prefs.tiltZeroPitch = zeroP; prefs.tiltZeroRoll = zeroR
                }
                holding = false; break
            }
        }
    }
    val pitch = t.pitch - zeroP; val roll = t.roll - zeroR
    val c = pc
    Column(modifier.width(LEVEL_SIZE).pointerInput(Unit) {
        detectTapGestures(onPress = { holding = true; tryAwaitRelease(); holding = false })
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(LEVEL_SIZE), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 4.dp.toPx(); val rad = 11.dp.toPx()
                val ctr = Offset(size.width / 2, size.height / 2)
                val rOut = size.width / 2 - stroke / 2
                val travel = rOut - stroke / 2 - rad                       // bubble centre at 8 deg
                drawCircle(c.gaugeFrame, rOut, ctr, style = Stroke(stroke))
                drawCircle(c.gaugeFrame, travel / 2, ctr, style = Stroke(2f))  // 4 deg ring
                drawLine(c.gaugeFrame, Offset(ctr.x - rOut, ctr.y), Offset(ctr.x + rOut, ctr.y), strokeWidth = 2f)
                drawLine(c.gaugeFrame, Offset(ctr.x, ctr.y - rOut), Offset(ctr.x, ctr.y + rOut), strokeWidth = 2f)
                var fx = (-roll / LEVEL_RANGE).toFloat(); var fy = (-pitch / LEVEL_RANGE).toFloat()
                val mag = kotlin.math.hypot(fx, fy)
                if (mag > 1f) { fx /= mag; fy /= mag }
                drawCircle(if (mag > 1f) c.amber else c.green, rad, Offset(ctr.x + fx * travel, ctr.y + fy * travel))
            }
            if (progress > 0f)
                CircularProgressIndicator(progress = { progress }, strokeWidth = 3.dp, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for ((arrow, a) in listOf("↕" to pitch, "↔" to roll))
                Text("$arrow%+.1f°".format(Math.round(a * 10) / 10.0 + 0.0), color = c.text, fontSize = 18.sp,   // +0.0: no "-0.0"
                    fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
        }
    }
}

/** Header card: coolant temperature | status icons + status line, warnings, HSI bar. */
@Composable
private fun HeaderCard(s: CanState, modifier: Modifier = Modifier) {
    val c = pc
    PCard(modifier.heightIn(min = HEADER_H), padding = 14.dp) {
        Row(Modifier.fillMaxWidth().height(92.dp), verticalAlignment = Alignment.CenterVertically) {
            // coolant temperature; no font padding so it hugs the card's top line
            val cc = coolantColor(s.coolant)
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    s.coolant?.let { "${it.toInt()}" } ?: "–",
                    fontSize = 80.sp, fontWeight = FontWeight.Bold, color = cc, style = NumStyle.merge(
                        TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))),
                    maxLines = 1, softWrap = false,
                )
                Text("°", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = cc,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(16.dp))
            Box(Modifier.width(1.dp).fillMaxHeight(0.8f).background(c.divider))
            Spacer(Modifier.width(18.dp))
            // status icons (up to 2 rows) at the top, justified status line at the bottom
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                StatusIcons(s)
                OdoRow(s)
            }
        }
        val cwl = s.i("cwL")          // learned weak-block (z-score)
        val rasym = s.i("rasym")      // per-block resistance asymmetry (degradation)
        if (s.wpWarn > 0 || s.cellWarn > 0 || cwl > 0 || rasym > 0) {
            val parts = mutableListOf<String>()
            if (s.wpWarn > 0)   parts += stringResource(if (s.wpWarn >= 2) R.string.warn_overheat else R.string.warn_cooling)
            if (s.cellWarn > 0) parts += stringResource(if (s.cellWarn >= 2) R.string.warn_hv_fault else R.string.warn_hv_cell)
            if (cwl > 0)        parts += stringResource(R.string.warn_weak_block, s.i("wblk"))
            if (rasym > 0)      parts += stringResource(R.string.warn_high_res, s.i("rwblk"))
            Text(
                parts.joinToString("  •  "), Modifier.padding(top = 6.dp),
                color = c.redText, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            )
        }
        Spacer(Modifier.height(10.dp))   // margin so the HSI bar doesn't touch the row above
        HsiStrip(s)
    }
}

/** Hybrid System Indicator bar (0x247). Zoned + stepped, filled FROM the HSI=0 point (15% width):
 *  hsi<0 fills left into CHG, hsi>0 fills right through ECO into PWR. Validated thresholds:
 *  HSI 50 = the engine joins MG2 (centre tick), HSI 100 = the EV/power limit = the ECO/PWR boundary.
 *  Widths CHG 15% (-100..0) / ECO 70% (0..100, linear: 50 at the centre) / PWR 15% (100..150).
 *  Heights: CHG 1u bottom, ECO 2u full, PWR 1u top. Rounded ends; zone labels underneath. */
@Composable
private fun HsiStrip(s: CanState) {
    val hsi = s.d("hsi")?.toFloat() ?: return
    val c = pc
    fun frac(h: Float): Float = (when {
        h <= 0f   -> (h + 100f) / 100f * 0.15f              // -100..0  -> 0..15%   (CHG/regen)
        h <= 100f -> 0.15f + h / 100f * 0.70f               // 0..100   -> 15..85%  (ECO; 50 at centre)
        else      -> 0.85f + (h - 100f) / 50f * 0.15f       // 100..150 -> 85..100% (PWR)
    }).coerceIn(0f, 1f)
    val xc = frac(hsi); val xa = 0.15f                      // anchor = HSI 0
    val lo = minOf(xa, xc); val hi = maxOf(xa, xc)
    val chg = c.accent; val eco = c.green; val pwr = c.amber
    Canvas(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(5.dp))) {
        val w = size.width; val u = size.height / 2f        // 1 unit = half the height
        fun zone(za: Float, zb: Float, yt: Float, yb: Float, col: Color) {
            drawRect(c.track, Offset(za * w, yt * u), Size((zb - za) * w, (yb - yt) * u))   // empty
            val ca = maxOf(za, lo); val cb = minOf(zb, hi)
            if (cb > ca) drawRect(col, Offset(ca * w, yt * u), Size((cb - ca) * w, (yb - yt) * u))  // fill
        }
        zone(0f, 0.15f, 1f, 2f, chg)       // CHG  (bottom 1u)
        zone(0.15f, 0.85f, 0f, 2f, eco)    // ECO  (full 2u)
        zone(0.85f, 1f, 0f, 1f, pwr)       // PWR  (top 1u)
        val m50 = frac(50f) * w            // engine-start tick (HSI 50 = ECO centre)
        drawLine(c.text, Offset(m50, 0f), Offset(m50, size.height), strokeWidth = 3f)
    }
    // labels centred under each zone (CHG 0..15 %, ECO 15..85 %, PWR 85..100 %); not localized
    Box(Modifier.fillMaxWidth().padding(top = 3.dp)) {
        val st = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("CHG", Modifier.align(BiasAlignment(-0.85f, 0f)), color = if (c.dark) chg else c.accent, style = st)
        Text("ECO", Modifier.align(BiasAlignment(0f, 0f)), color = c.greenText, style = st)
        Text("PWR", Modifier.align(BiasAlignment(0.85f, 0f)), color = if (c.dark) pwr else c.amberText, style = st)
    }
}

@Composable private fun Ic(res: Int, tint: Color, w: Int = 32) =
    Image(painterResource(res), null, Modifier.size(w.dp), colorFilter = ColorFilter.tint(tint))

@Composable private fun ModeBadge(text: String, color: Color) {
    Box(Modifier.border(2.dp, color, RoundedCornerShape(5.dp)).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun EvIcon(tint: Color, onTint: Color) {
    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_car_ev), null, Modifier.size(34.dp), colorFilter = ColorFilter.tint(tint))
        Text("EV", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = onTint)
    }
}

/** Status-icon strip: only active states show; wraps to a 2nd row when too many. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusIcons(s: CanState) {
    val c = pc
    // icon tints: the night tones are the original ones; day uses darker tones for sunlight contrast
    val green = c.green
    val yellow = if (c.dark) Color(0xFFFFCA28) else Color(0xFFD08C00)
    val orange = if (c.dark) Color(0xFFFF8C1E) else Color(0xFFD86A00)
    val lt = s.i("lights")
    val dmode = s.i("dmode")
    val evOn = s.i("ev") != 0
    val spd = s.d("spd") ?: 0.0
    val rpm = s.d("rpm") ?: 0.0
    val brk = s.d("brkP") ?: 0.0
    val mg2 = s.d("mg2Pow") ?: 0.0
    val brkPos = s.d("brkPos") ?: 0.0
    val cruise = s.i("cruise") != 0
    val belt = s.i("belt") != 0
    val city by CanService.city.collectAsState()   // belterület geofence: true=city, false=open road
    val phys = brk > 0.55                          // physical (friction) brake = pressure rising
    // regen (green) = brake PEDAL pressed AND MG2 charging (generating), friction not yet engaged
    val regen = !phys && brkPos > 3.0 && mg2 < 0.0 && spd > 2
    val evGhost = !evOn && spd > 2 && rpm < 100     // electric-only drive without EV mode
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // belterület / országút (red road = open road with low-beam off -> headlight warning)
        when (city) {
            true -> Ic(R.drawable.ic_city, green)
            false -> Ic(R.drawable.ic_road, if (lt and 0x20 == 0) c.red else c.blue)
            else -> {}
        }
        if (lt and 0x10 != 0) Ic(R.drawable.ic_light_position, green)
        if (lt and 0x20 != 0) Ic(R.drawable.ic_light_low, green)
        if (lt and 0x40 != 0) Ic(R.drawable.ic_light_high, c.blue)
        if (lt and 0x08 != 0) Ic(R.drawable.ic_light_fog, green)
        if (lt and 0x04 != 0) Ic(R.drawable.ic_light_fog, c.amber)
        if (cruise) Ic(R.drawable.ic_cruise, green)
        if (phys) Ic(R.drawable.ic_brake_pedal, yellow, 34) else if (regen) Ic(R.drawable.ic_brake_pedal, green, 34)
        if (evOn) EvIcon(green, Color.White) else if (evGhost) EvIcon(yellow, Color.Black)
        if (dmode == 1) ModeBadge("ECO", c.greenText)
        if (dmode == 2) ModeBadge("PWR", orange)
        if (belt) Ic(R.drawable.ic_seatbelt, c.red)
    }
}

/** Justified status line: gear · speed · rpm · odometer · range · consumption. */
@Composable
private fun OdoRow(s: CanState) {
    val c = pc
    val gear = if (s.gear in 0..4) CanState.GEARS[s.gear] else "–"
    val spd = s.d("spd")?.let { "${it.toInt()} km/h" } ?: "– km/h"
    val rpm = "${s.d("rpm")?.toInt() ?: "–"} rpm"
    val odo = s.d("odo")?.let { "%,d km".format(it.toLong()) } ?: "– km"
    val cons = s.consumptionView().text()   // EV mode -> kWh/h resp. kWh/100km (pack draw)
    val range = s.d("range")?.let { "->| ${it.toInt()} km" }   // distance-to-empty
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        listOfNotNull(gear, spd, rpm, odo, range, cons).forEachIndexed { i, txt ->
            if (i > 0) Text("·", color = c.dim, fontSize = 17.sp)
            Text(txt, color = c.text, fontSize = 17.sp, style = NumStyle, maxLines = 1, softWrap = false)
        }
    }
}

/** Fuel + HV battery gauges in their own card. */
@Composable
private fun GaugesCard(s: CanState) {
    PCard(Modifier.heightIn(min = HEADER_H), verticalArrangement = Arrangement.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FuelGauge(s)
            BatteryGauge(s)
        }
    }
}

private val GAUGE_W = 70.dp
private val GAUGE_H = 120.dp

@Composable
private fun pctStyle(): TextStyle {
    val c = pc
    // soft halo in the BACKGROUND tone keeps the % readable over the fill in both themes
    return TextStyle(color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
        shadow = Shadow(if (c.dark) Color.Black else Color.White, blurRadius = 8f), fontFeatureSettings = "tnum")
}

@Composable
private fun FuelGauge(s: CanState) {
    val c = pc
    // % shows the CALCULATED real fullness (calibrated liters / tank), and the gauge fill matches.
    // Falls back to the raw gauge reading when fuelL is not available (warm-up / old firmware).
    val full = 47f                              // TANK_FULL in prius_parse.h
    val liters = s.d("fuelL")?.toFloat()
    val frac = (liters?.let { it / full } ?: ((s.d("fuelIn") ?: 0.0).toFloat() / 100f)).coerceIn(0f, 1f)
    val pct = liters?.let { (it / full * 100f).toInt() } ?: s.d("fuelIn")?.toInt()
    val halo = Shadow(if (c.dark) Color.Black else Color.White, blurRadius = 8f)
    Box(Modifier.width(GAUGE_W).height(GAUGE_H)) {
        Canvas(Modifier.fillMaxSize()) { gaugeShape(frac, c.amber, c.gaugeFrame, battery = false) }
        Text(pct?.let { "$it%" } ?: "–",
            style = pctStyle(), modifier = Modifier.align(BiasAlignment(0f, -0.15f)))
        // calibrated remaining liters, on a new line UNDER the % (smaller, doesn't move the %)
        s.d("fuelL")?.let { l ->
            // calculated remaining liters; turns amber if it disagrees with the measured gauge (drift)
            val drift = s.d("fuelGm")?.let { kotlin.math.abs(l - it) > 2.5 } ?: false
            Text("%.1f L".format(l), color = if (drift) c.amberText else c.text, fontSize = 13.sp,
                style = TextStyle(shadow = halo, fontFeatureSettings = "tnum"),
                modifier = Modifier.align(BiasAlignment(0f, 0.28f)))
        }
        Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = c.dim, fontSize = 16.sp)
            Image(painterResource(R.drawable.ic_fuel), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(c.dim))
        }
    }
}

@Composable
private fun BatteryGauge(s: CanState) {
    val c = pc
    val soc = (s.d("soc") ?: 0.0).toFloat()
    val frac = ((soc - 35f) / (70f - 35f)).coerceIn(0f, 1f)   // operating band ~35..70%
    val hvA = s.d("hvA") ?: 0.0
    Box(Modifier.width(GAUGE_W).height(GAUGE_H)) {
        Canvas(Modifier.fillMaxSize()) { gaugeShape(frac, c.green, c.gaugeFrame, battery = true) }
        Text(s.d("soc")?.let { "${it.toInt()}%" } ?: "–",
            style = pctStyle(), modifier = Modifier.align(BiasAlignment(0f, -0.15f)))
        val dir = if (hvA < -2) "▲" else if (hvA > 2) "▼" else ""   // charge direction
        if (dir.isNotEmpty()) {
            // pack power next to the arrow (same line as the fuel gauge's liters): |hvA x VL|,
            // one decimal under 10 kW so "▼ 23 kW" / "▲ 4.2 kW" both fit the gauge
            val kw = s.d("vl")?.let { kotlin.math.abs(hvA) * it / 1000.0 }
            val txt = dir + (kw?.let { if (it < 10) " %.1f kW".format(it) else " %.0f kW".format(it) } ?: "")
            Text(txt, color = c.text, fontSize = 12.sp,   // green-on-green fill was unreadable; the arrow shows the direction
                maxLines = 1, softWrap = false,
                style = TextStyle(shadow = Shadow(if (c.dark) Color.Black else Color.White, blurRadius = 8f)),
                modifier = Modifier.align(BiasAlignment(0f, 0.28f)))
        }
    }
}

/** Rounded gauge container + bottom-up fill; battery adds two terminal bumps on top. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.gaugeShape(frac: Float, fill: Color, frame: Color, battery: Boolean) {
    val w = size.width; val h = size.height; val r = 14.dp.toPx()
    val bump = if (battery) 7.dp.toPx() else 0f
    if (battery) {
        val bw = w * 0.22f
        drawRoundRect(frame, topLeft = Offset(w * 0.15f, 0f), size = Size(bw, bump + 2f), cornerRadius = CornerRadius(3f))
        drawRoundRect(frame, topLeft = Offset(w * 0.63f, 0f), size = Size(bw, bump + 2f), cornerRadius = CornerRadius(3f))
    }
    drawRoundRect(frame, topLeft = Offset(0f, bump), size = Size(w, h - bump),
        cornerRadius = CornerRadius(r), style = Stroke(3.dp.toPx()))
    val pad = 7.dp.toPx()
    val innerTop = bump + pad; val innerBot = h - pad
    val fillH = (innerBot - innerTop) * frac.coerceIn(0f, 1f)
    if (fillH > 0f)
        drawRoundRect(fill, topLeft = Offset(pad, innerBot - fillH), size = Size(w - 2 * pad, fillH),
            cornerRadius = CornerRadius(10f))
}

// Trip switcher definitions. idx = position in the firmware "slots" array
// [boot, lifetime, tank, oil, A, B, C, home]; resetCmd = "R<n>" (0 = not resettable);
// hist = 0 none / 1 refuel / 2 oil.
// copySrc: if set, this trip can be snapshotted INTO A/B/C ('B'=since-boot, 'H'=from-home)
private data class TripDef(val labelRes: Int, val arg: String?, val idx: Int, val resetCmd: Int, val hist: Int, val copySrc: Char? = null)
private val TRIPS = listOf(
    TripDef(R.string.trip_boot, null, 0, 0, 0, copySrc = 'B'),
    TripDef(R.string.trip_home, null, 7, 0, 0, copySrc = 'H'),   // home: app auto-resets (geofence), no UI reset
    TripDef(R.string.sl_trip, "A", 4, 3, 0),
    TripDef(R.string.sl_trip, "B", 5, 4, 0),
    TripDef(R.string.sl_trip, "C", 6, 5, 0),
    TripDef(R.string.trip_tank, null, 2, 0, 1),
    TripDef(R.string.trip_oil, null, 3, 0, 2),   // reset via the "register oil change" dialog, not hold
    TripDef(R.string.trip_life, null, 1, 0, 0),
)

/** The dashboard trip switcher: chips + the selected trip's stats card, a long-press reset (with
 *  circular progress) for resettable trips, snapshot-into-A/B/C, and history for Tank/Oil. */
@Composable
private fun TripSection() {
    val c = pc
    val slots by CanService.tripSlots.collectAsState()
    val refuelHist by CanService.refuelHist.collectAsState()
    val oilHist by CanService.oilHist.collectAsState()
    var sel by remember { mutableStateOf(0) }
    val def = TRIPS[sel]

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TRIPS.forEachIndexed { i, t ->
                    val label = if (t.arg != null) stringResource(t.labelRes, t.arg) else stringResource(t.labelRes)
                    PillChip(label, selected = i == sel, onClick = { sel = i })
                    if (i < TRIPS.lastIndex) Spacer(Modifier.width(8.dp))
                }
            }
        }
        if (slots.isEmpty()) {
            PCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.trip_need_fw), color = c.amberText, fontSize = 15.sp)
            }
            return@Column
        }
        val s = slots.getOrNull(def.idx) ?: TripSlot.EMPTY
        val evPct = if (s.dist > 0.1) (s.ev / s.dist * 100).toInt() else 0
        PCard(Modifier.fillMaxWidth(), padding = 16.dp) {
            StatRow(stringResource(R.string.r_dist), "%.1f km / %.1f EV km (%d%%)".format(s.dist, s.ev, evPct))
            StatRow(stringResource(R.string.r_citydist), "%.1f km / %.1f EV km (%d%%)".format(s.cityDist, s.cityEv, s.cityEvPct))
            StatRow(stringResource(R.string.r_fuelused), "%.2f L".format(s.fuel))
            StatRow(stringResource(R.string.r_avgcons), "%.1f l/100km".format(s.avgCons))
            StatRow(stringResource(R.string.r_avgspeed), "%.0f km/h".format(s.avgKmh))
            StatRow(stringResource(R.string.r_movetime), fmtDur(s.moveS.toLong()))
            StatRow(stringResource(R.string.r_evcons), "%.2f kWh · %.1f kWh/100km".format(s.evKwh, s.evKwhPer100))
            StatRow(stringResource(R.string.r_regen), "%.2f kWh (~%.0f km)".format(s.regenKwh, s.regenKm))

            // footer: period start · snapshot into A/B/C · hold-to-reset
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                var first = true
                if (s.epoch > 0) {
                    Text(
                        fmtDate(s.epoch) + (if (s.odo > 0) "  ·  " + stringResource(R.string.trip_at, "%,d".format(s.odo)) else ""),
                        color = c.dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    first = false
                }
                if (def.copySrc != null) {   // snapshot this live trip into A/B/C (hold)
                    if (!first) VDivider()
                    Text(stringResource(R.string.trip_copy), color = c.dim, fontSize = 14.sp, maxLines = 1)
                    Spacer(Modifier.width(12.dp))
                    ResetHoldButton(label = "A") { CanService.copySlot(3, def.copySrc) }
                    Spacer(Modifier.width(10.dp))
                    ResetHoldButton(label = "B") { CanService.copySlot(4, def.copySrc) }
                    Spacer(Modifier.width(10.dp))
                    ResetHoldButton(label = "C") { CanService.copySlot(5, def.copySrc) }
                    first = false
                }
                if (def.resetCmd > 0) {
                    if (!first) VDivider()
                    ResetHoldButton {
                        CanService.resetSlot(def.resetCmd)
                        if (def.hist == 2) CanService.fetchOilHistory()
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.trip_hold), color = c.dim, fontSize = 14.sp, maxLines = 1)
                }
            }
            if (def.hist == 2) OilSection(s)   // due indicator + "register oil change" (sends R2)
            if (def.hist != 0) {
                LaunchedEffect(sel) { if (def.hist == 1) CanService.fetchRefuelHistory() else CanService.fetchOilHistory() }
                val h = if (def.hist == 1) refuelHist else oilHist
                SectionTitle(stringResource(R.string.r_history), Modifier.padding(top = 18.dp, bottom = 4.dp))
                if (def.hist == 1) {   // undo a false refuel: merge the last entry back into the tank (hold)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(stringResource(R.string.undo_refuel), color = c.dim, fontSize = 14.sp)
                        Spacer(Modifier.width(12.dp))
                        ResetHoldButton(label = "⤺") { CanService.mergeLastRefuel(); CanService.fetchRefuelHistory() }
                    }
                }
                if (h.isEmpty()) {
                    Text(stringResource(R.string.r_none), color = c.dim, fontSize = 15.sp)
                } else {
                    val oilMeta by OilLog.entries.collectAsState()   // join app-side metadata by period-start odo
                    h.asReversed().forEachIndexed { i, r ->   // newest first
                        val meta = if (def.hist == 2) oilMeta.lastOrNull { kotlin.math.abs(it.odo - r.odo) <= 3 } else null
                        StatRow(
                            fmtDate(r.epoch),
                            "%.1f km · %.1f EV · %.1f l/100km · %.1f kWh/100km · %.2f kWh (~%.0f km)".format(
                                r.dist, r.ev, r.avgCons, r.evKwhPer100, r.regenKwh, r.regenKm),
                            divider = meta == null && i < h.lastIndex,
                        )
                        meta?.let { m ->
                            Text(
                                listOfNotNull(m.oilType.ifBlank { null },
                                              if (m.filter) stringResource(R.string.oil_filter) else null,
                                              m.note.ifBlank { null }).joinToString(" · "),
                                color = c.dim, fontSize = 13.sp,
                                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VDivider() {
    Spacer(Modifier.width(16.dp))
    Box(Modifier.width(1.dp).height(32.dp).background(pc.divider))
    Spacer(Modifier.width(16.dp))
}

/** Oil tab extras: due indicator (km + months vs the Settings interval; VISUAL only, no alert)
 *  + the "register oil change" dialog. Confirming sends R2 (the firmware archives the finished
 *  period into ohist and restarts slot[2]) and stores the app-side metadata (OilLog). */
@Composable
private fun OilSection(s: TripSlot) {
    val c = pc
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    LaunchedEffect(Unit) { OilLog.load(ctx) }
    val oilMeta by OilLog.entries.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    // due fraction: km OR elapsed months, whichever is closer (avg month = 30.44 days)
    val months = if (s.epoch > 0) (System.currentTimeMillis() / 1000 - s.epoch) / 2_629_800.0 else 0.0
    val pct = maxOf(s.dist / prefs.oilKm, months / prefs.oilMonths).toFloat()
    val color = when {
        pct >= 1f -> c.red        // overdue
        pct >= 0.9f -> c.amber    // due soon
        else -> c.green
    }
    Spacer(Modifier.height(12.dp))
    ThinBar(pct, color, height = 8.dp)
    val curMeta = oilMeta.lastOrNull { kotlin.math.abs(it.odo - s.odo) <= 3 }   // the oil currently in
    Text(
        stringResource(R.string.oil_due, s.dist.toInt(), prefs.oilKm, months, prefs.oilMonths) +
            (curMeta?.oilType?.ifBlank { null }?.let { "  ·  $it" } ?: ""),
        color = c.dim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
    )
    TextButton(onClick = { showDialog = true }) { Text(stringResource(R.string.oil_register), fontSize = 15.sp) }

    if (showDialog) {
        val odo = CanService.state.collectAsState().value.d("odo")?.toLong() ?: 0L
        var oilType by remember { mutableStateOf("") }
        var filter by remember { mutableStateOf(true) }
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.oil_register)) },
            text = {
                Column {
                    Text(fmtDate(System.currentTimeMillis() / 1000) + "  ·  %,d km".format(odo),
                        color = c.dim, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(oilType, { oilType = it },
                        label = { Text(stringResource(R.string.oil_type)) }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = filter, onCheckedChange = { filter = it })
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.oil_filter))
                    }
                    OutlinedTextField(note, { note = it },
                        label = { Text(stringResource(R.string.oil_note)) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    CanService.resetSlot(2)                       // firmware: archive period + restart slot[2]
                    OilLog.add(ctx, OilLog.OilEntry(System.currentTimeMillis() / 1000, odo,
                                                    oilType.trim(), filter, note.trim()))
                    CanService.fetchOilHistory()
                }) { Text(stringResource(R.string.btn_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Press-and-hold (~1.3 s) bordered button, with a circular progress while held. */
@Composable
private fun ResetHoldButton(label: String = "⟲", onReset: () -> Unit) {
    val c = pc
    var holding by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    LaunchedEffect(holding) {
        if (!holding) { progress = 0f; return@LaunchedEffect }
        val start = withFrameNanos { it }
        while (holding) {
            val now = withFrameNanos { it }
            progress = ((now - start) / 1_300_000_000f).coerceAtMost(1f)
            if (progress >= 1f) { onReset(); holding = false; break }
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.width(56.dp).height(46.dp).clip(shape)
            .border(1.5.dp, if (holding) c.accent else c.borderStrong, shape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = { holding = true; tryAwaitRelease(); holding = false })
            },
    ) {
        if (progress > 0f)
            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.text)
    }
}

// ================================ shared helpers ================================

fun hasLocationPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

internal fun fmtDate(epoch: Long): String =
    if (epoch <= 0) "–"
    else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(epoch * 1000))

internal fun fmtDur(s: Long): String {
    val h = s / 3600; val m = (s % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** Number only (same decimals as before), "–" when missing. The unit is rendered separately. */
internal fun fmtNum(v: Double?, decimals: Int): String = if (v == null) "–" else "%.${decimals}f".format(v)

/** Coolant-temperature colour ramp (<70 blue, 70–98 green, 98–105 amber, >105 red). Night keeps
 *  the original tones; day uses darker ones so the big number stays readable in sunlight. */
@Composable
internal fun coolantColor(t: Double?): Color {
    val c = pc
    return if (c.dark) when {
        t == null -> c.dim
        t < 70 -> Color(0xFF64B5F6)
        t < 98 -> Color(0xFF81C784)
        t < 105 -> Color(0xFFFFB74D)
        else -> Color(0xFFFF5252)
    } else when {
        t == null -> c.dim
        t < 70 -> c.accent
        t < 98 -> c.greenText
        t < 105 -> c.amberText
        else -> c.redText
    }
}
