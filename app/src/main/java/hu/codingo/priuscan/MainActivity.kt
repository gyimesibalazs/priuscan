package hu.codingo.priuscan

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val connected by CanService.connected.collectAsState()
    val device by CanService.deviceInfo.collectAsState()
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
        ScrollableTabRow(
            selectedTabIndex = tab.coerceIn(0, titles.size - 1),
            edgePadding = 8.dp,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            titles.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i },
                    text = { Text(t, fontSize = 12.sp, maxLines = 1) })
            }
        }
        when (tab) {
            0 -> DashboardTab(state)
            in 1..groups.size -> {
                val (titleRes, fields) = groups[tab - 1]
                if (titleRes == R.string.grp_hybrid_batt) HybridBatteryTab(state, fields, prefs.batteryRefAh)
                else if (titleRes == R.string.grp_drive_brake) DriveTab(state, fields)
                else GroupTab(state, fields)
            }
            groups.size + 1 -> TpmsTab(tpmsReadings)
            groups.size + 2 -> GpsTab(gpsLoc)
            else -> SettingsScreen(prefs) { tab = 0 }
        }
    }
}

@Composable
private fun tabColumn(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    // cap content width and center it, so on a wide landscape head-unit screen the
    // rows (and the tyre values next to the car drawing) stay readable, not stretched.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 820.dp).fillMaxHeight().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            content()
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Tab 1: glance dashboard - header + odometer + fuel level + the trip switcher. */
@Composable
private fun DashboardTab(state: CanState) = tabColumn {
    item { Header(state) }   // odo + fuel level now live in the header (tank gauge + ODO row)
    item { TripSection() }
}

/** Tyre pressure on its own big tab. */
@Composable
private fun TpmsTab(tpms: Map<Wheel, TireReading>) = tabColumn {
    item { GroupTitle(stringResource(R.string.tpms_title)) }
    item { TpmsCarView(tpms) }
}

@Composable
private fun GroupTab(state: CanState, fields: List<Field>) = tabColumn {
    itemsIndexed(fields) { _, f ->
        SensorRow(stringResource(f.labelRes), format(state.d(f.key), f.decimals, f.unit))
    }
}

/** Driving tab = the drive/brake fields + the exterior-light states (0x622 bitmask). */
@Composable
private fun DriveTab(state: CanState, fields: List<Field>) = tabColumn {
    itemsIndexed(fields) { _, f ->
        SensorRow(stringResource(f.labelRes), format(state.d(f.key), f.decimals, f.unit))
    }
    // drive mode (0x49B b4): NORMAL/ECO/PWR selector + independent EV toggle
    item { GroupTitle(stringResource(R.string.grp_drivemode)) }
    item {
        val dm = state.i("dmode") ?: 0
        SensorRow(stringResource(R.string.f_drivemode),
            when (dm) { 1 -> "ECO"; 2 -> "PWR"; else -> stringResource(R.string.dm_normal) },
            highlight = dm != 0)
    }
    item {
        val ev = (state.i("ev") ?: 0) != 0
        SensorRow(stringResource(R.string.f_evmode), if (ev) "● EV" else "○", highlight = ev)
    }

    // cruise control + turn signal (firmware decodes them; not elsewhere in the UI)
    item { GroupTitle(stringResource(R.string.grp_cruise)) }
    item {
        val cr = (state.i("cruise") ?: 0) != 0
        SensorRow(stringResource(R.string.f_cruise), if (cr) "●" else "○", highlight = cr)
    }
    item {
        val ss = state.d("setSpd")
        SensorRow(stringResource(R.string.f_setSpd), if (ss != null && ss > 0) "%.0f km/h".format(ss) else "–")
    }
    item {
        val t = state.i("turn") ?: 0
        SensorRow(stringResource(R.string.f_turn), when (t) { 1 -> "◄"; 2 -> "►"; else -> "–" }, highlight = t == 1 || t == 2)
    }

    item { GroupTitle(stringResource(R.string.grp_lights)) }
    val lt = state.i("lights") ?: 0
    item { LightRow(R.string.l_position, lt and 0x10 != 0) }
    item { LightRow(R.string.l_lowbeam,  lt and 0x20 != 0) }
    item { LightRow(R.string.l_highbeam, lt and 0x40 != 0) }
    item { LightRow(R.string.l_fogfront, lt and 0x08 != 0) }
    item { LightRow(R.string.l_fogrear,  lt and 0x04 != 0) }
    // raw ambient-light level (0x620): ~31 bright .. ~600 dark; cluster flips at ~100
    item { SensorRow(stringResource(R.string.f_ambL), format(state.d("ambL"), 0, "")) }
}

@Composable
private fun LightRow(labelRes: Int, on: Boolean) {
    SensorRow(stringResource(labelRes), if (on) "●" else "○", highlight = on)
}

/** Hybrid-battery tab: the battery fields + SoH + the b01..b14 block voltages merged in. */
@Composable
private fun HybridBatteryTab(state: CanState, fields: List<Field>, refAh: Float) = tabColumn {
    itemsIndexed(fields) { _, f ->
        SensorRow(stringResource(f.labelRes), format(state.d(f.key), f.decimals, f.unit))
    }
    // State of Health = learned capacity / reference capacity (degradation)
    val cap = state.d("capAh")
    item {
        SensorRow(
            stringResource(R.string.f_capSoh),
            if (cap != null && refAh > 0) "%.0f %%".format(cap / refAh * 100) else "–",
        )
    }
    item { GroupTitle(stringResource(R.string.hv_block_voltages)) }
    itemsIndexed((1..14).toList()) { _, i ->
        val v = state.d("b%02d".format(i))
        SensorRow(
            stringResource(R.string.block_n, i),
            if (v == null) "–" else "%.2f V".format(v),
            highlight = state.i("weakB") == i,
        )
    }
    // per-block internal resistance (on-demand "B"; refreshed every 5 s while this tab is open)
    item { GroupTitle(stringResource(R.string.hv_block_res)) }
    item {
        LaunchedEffect(Unit) { while (true) { CanService.fetchBlockR(); kotlinx.coroutines.delay(5000) } }
        val r by CanService.blockR.collectAsState()
        Column {
            (1..14).forEach { i ->
                val v = r.getOrNull(i - 1)
                SensorRow(stringResource(R.string.block_n, i),
                    if (v == null || v.isNaN()) "–" else "%.1f mΩ".format(v))
            }
        }
    }
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
    TripDef(R.string.trip_oil, null, 3, 2, 2),
    TripDef(R.string.trip_life, null, 1, 0, 0),
)

/** The dashboard trip switcher: scrollable chips + the selected trip's stats, a long-press
 *  reset (with circular progress) for resettable trips, and history for Tank/Oil. */
@Composable
private fun TripSection() {
    val slots by CanService.tripSlots.collectAsState()
    val refuelHist by CanService.refuelHist.collectAsState()
    val oilHist by CanService.oilHist.collectAsState()
    var sel by remember { mutableStateOf(0) }
    val def = TRIPS[sel]

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TRIPS.forEachIndexed { i, t ->
                val label = if (t.arg != null) stringResource(t.labelRes, t.arg) else stringResource(t.labelRes)
                FilterChip(selected = i == sel, onClick = { sel = i }, label = { Text(label, fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(6.dp))
        if (slots.isEmpty()) {
            Text(stringResource(R.string.trip_need_fw), color = Color(0xFFFFB74D), fontSize = 14.sp)
            return@Column
        }
        val s = slots.getOrNull(def.idx) ?: TripSlot.EMPTY
        val evPct = if (s.dist > 0.1) (s.ev / s.dist * 100).toInt() else 0
        SensorRow(stringResource(R.string.r_dist), "%.1f km / %.1f EV km (%d%%)".format(s.dist, s.ev, evPct))
        SensorRow(stringResource(R.string.r_fuelused), "%.2f L".format(s.fuel))
        SensorRow(stringResource(R.string.r_avgcons), "%.1f l/100km".format(s.avgCons))
        SensorRow(stringResource(R.string.r_avgspeed), "%.0f km/h".format(s.avgKmh))
        SensorRow(stringResource(R.string.r_movetime), fmtDur(s.moveS.toLong()))
        SensorRow(stringResource(R.string.r_regen), "%.0f %%".format(s.regen))
        if (s.epoch > 0) {
            Text(
                fmtDate(s.epoch) + (if (s.odo > 0) "  ·  " + stringResource(R.string.trip_at, "%,d".format(s.odo)) else ""),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
            )
        }
        if (def.resetCmd > 0) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ResetHoldButton {
                    CanService.resetSlot(def.resetCmd)
                    if (def.hist == 2) CanService.fetchOilHistory()
                }
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.trip_hold), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        if (def.copySrc != null) {   // snapshot this live trip into A/B/C (hold)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.trip_copy), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                ResetHoldButton(label = "A") { CanService.copySlot(3, def.copySrc) }
                Spacer(Modifier.width(6.dp))
                ResetHoldButton(label = "B") { CanService.copySlot(4, def.copySrc) }
                Spacer(Modifier.width(6.dp))
                ResetHoldButton(label = "C") { CanService.copySlot(5, def.copySrc) }
            }
        }
        if (def.hist != 0) {
            LaunchedEffect(sel) { if (def.hist == 1) CanService.fetchRefuelHistory() else CanService.fetchOilHistory() }
            val h = if (def.hist == 1) refuelHist else oilHist
            Spacer(Modifier.height(8.dp))
            GroupTitle(stringResource(R.string.r_history))
            if (def.hist == 1) {   // undo a false refuel: merge the last entry back into the tank (hold)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(stringResource(R.string.undo_refuel), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    ResetHoldButton(label = "⤺") { CanService.mergeLastRefuel(); CanService.fetchRefuelHistory() }
                }
            }
            if (h.isEmpty()) {
                Text(stringResource(R.string.r_none), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            } else {
                h.asReversed().forEach { r ->   // newest first
                    SensorRow(
                        fmtDate(r.epoch),
                        "%.1f km · %.1f EV · %.1f l/100km · %.0f%% regen".format(r.dist, r.ev, r.avgCons, r.regen),
                    )
                }
            }
        }
    }
}

/** Press-and-hold (~1.3 s) to reset, with a circular progress around the button. */
@Composable
private fun ResetHoldButton(label: String = "⟲", onReset: () -> Unit) {
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
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(52.dp).pointerInput(Unit) {
            detectTapGestures(onPress = { holding = true; tryAwaitRelease(); holding = false })
        },
    ) {
        if (progress > 0f)
            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(52.dp), strokeWidth = 3.dp)
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun GpsTab(loc: Location?) = tabColumn {
    item { SensorRow(stringResource(R.string.f_lat), loc?.let { "%.6f".format(it.latitude) } ?: stringResource(R.string.gps_no_fix)) }
    item { SensorRow(stringResource(R.string.f_lon), loc?.let { "%.6f".format(it.longitude) } ?: "–") }
    item { SensorRow(stringResource(R.string.f_gspd), loc?.let { "%.0f km/h".format(it.speed * 3.6f) } ?: "–") }
    item { SensorRow(stringResource(R.string.f_galt), loc?.let { "%.0f m".format(it.altitude) } ?: "–") }
    item { SensorRow(stringResource(R.string.f_gbrg), loc?.let { "%.0f°".format(it.bearing) } ?: "–") }
    item { SensorRow(stringResource(R.string.f_gacc), loc?.let { "%.0f m".format(it.accuracy) } ?: "–") }
}

// ---- dashboard top palette ----
private val CGreen = Color(0xFF66BB6A); private val CYellow = Color(0xFFFFCA28)
private val CBlue = Color(0xFF50AAFF); private val CAmber = Color(0xFFFFA028)
private val COrange = Color(0xFFFF8C1E); private val CRed = Color(0xFFE53935)
private val CGaugeFrame = Color(0xFF5F5F5F); private val CIconDim = Color(0xFFAFAFAF)

private val PctStyle = TextStyle(
    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
    shadow = Shadow(Color.Black, blurRadius = 10f),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(s: CanState) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        // TOP-aligned so the temp, icons and gauges share a common top line; the block is tall
        // enough for TWO icon rows (icons at the top, ODO pinned to the bottom).
        Row(Modifier.fillMaxWidth().height(140.dp), verticalAlignment = Alignment.Top) {
            // coolant temperature, narrowed horizontally; no font padding so it hugs the top line
            Text(
                s.coolant?.let { "${it.toInt()}" } ?: "–",
                fontSize = 100.sp, fontWeight = FontWeight.Bold, color = coolantColor(s.coolant),
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                modifier = Modifier.graphicsLayer(scaleX = 0.78f),
            )
            Text("°", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = coolantColor(s.coolant),
                modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.width(20.dp))
            // middle: status icons (up to 2 rows) at the top, justified ODO row at the bottom
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                StatusIcons(s)
                OdoRow(s)
            }
            Spacer(Modifier.width(24.dp))
            FuelGauge(s)
            Spacer(Modifier.width(8.dp))
            BatteryGauge(s)
        }
        val cwl = s.i("cwL") ?: 0          // learned weak-block (z-score)
        val rasym = s.i("rasym") ?: 0      // per-block resistance asymmetry (degradation)
        if (s.wpWarn > 0 || s.cellWarn > 0 || cwl > 0 || rasym > 0) {
            val parts = mutableListOf<String>()
            if (s.wpWarn > 0)   parts += stringResource(if (s.wpWarn >= 2) R.string.warn_overheat else R.string.warn_cooling)
            if (s.cellWarn > 0) parts += stringResource(if (s.cellWarn >= 2) R.string.warn_hv_fault else R.string.warn_hv_cell)
            if (cwl > 0)        parts += stringResource(R.string.warn_weak_block, s.i("wblk") ?: 0)
            if (rasym > 0)      parts += stringResource(R.string.warn_high_res, s.i("rwblk") ?: 0)
            Text(
                parts.joinToString("  •  "),
                color = Color(0xFFFF5252), fontWeight = FontWeight.Bold, fontSize = 15.sp,
            )
        }
        HsiStrip(s)   // power/charge flow bar at the bottom of the dash-top
        BrakeBar(s)   // brake pedal position (for visual verification)
    }
}

/** Hybrid System Indicator: center-anchored bar from the total drive power (0x247). hsi>0 = PWR
 *  (driving, incl. engine-direct) -> fills right (amber); hsi<0 = CHG/regen -> fills left (green). */
@Composable
private fun HsiStrip(s: CanState) {
    val pow = s.d("hsi")?.toFloat() ?: return
    // ASYMMETRIC: drive power (~60+ kW) far exceeds regen (~-25 kW), so the PWR side gets both more
    // width AND a larger kW scale than the CHG side. Neutral (0 kW) sits left of centre.
    val regenMax = 20f; val powerMax = 55f               // kW full-scale, CHG / PWR (tunable)
    val centerFrac = 0.30f
    val regen = ((-pow) / regenMax).coerceIn(0f, 1f)     // CHG -> green left
    val power = (pow / powerMax).coerceIn(0f, 1f)         // PWR -> amber right
    Canvas(Modifier.fillMaxWidth().height(12.dp).padding(top = 4.dp)) {
        val w = size.width; val h = size.height; val cx = w * centerFrac
        val r = CornerRadius(h / 2f, h / 2f)
        drawRoundRect(Color(0xFFCDD2D8), size = Size(w, h), cornerRadius = r)   // track
        if (regen > 0f) drawRect(Color(0xFF2EA047), Offset(cx - regen * cx, 0f), Size(regen * cx, h))      // regen left
        if (power > 0f) drawRect(Color(0xFFF5A028), Offset(cx, 0f), Size(power * (w - cx), h))             // power right
        drawLine(Color(0xFF282C32), Offset(cx, -2f), Offset(cx, h + 2f), strokeWidth = 3f)                // neutral tick
    }
}

/** Brake pedal POSITION bar (0x4A2): left-anchored red fill, 0..~100% of pedal travel. For
 *  visual verification of the brake-pedal signal (distinct from the friction pressure). */
@Composable
private fun BrakeBar(s: CanState) {
    val pos = s.d("brkPos")?.toFloat() ?: return
    val frac = (pos / 128f).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxWidth().height(8.dp).padding(top = 3.dp)) {
        val w = size.width; val h = size.height; val r = CornerRadius(h / 2f, h / 2f)
        drawRoundRect(Color(0xFFCDD2D8), size = Size(w, h), cornerRadius = r)
        if (frac > 0f) drawRoundRect(Color(0xFFE53935), size = Size(frac * w, h), cornerRadius = r)
    }
}

@Composable private fun Ic(res: Int, tint: Color, w: Int = 30) =
    Image(painterResource(res), null, Modifier.size(w.dp), colorFilter = ColorFilter.tint(tint))

@Composable private fun ModeBadge(text: String, color: Color) {
    Box(Modifier.border(2.dp, color, RoundedCornerShape(5.dp)).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun EvIcon(tint: Color) {
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_car_ev), null, Modifier.size(32.dp), colorFilter = ColorFilter.tint(tint))
        Text("EV", fontSize = 8.sp, fontWeight = FontWeight.Bold,
            color = if (tint == CGreen) Color.White else Color.Black)
    }
}

/** Status-icon strip: only active states show; wraps to a 2nd row when too many. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusIcons(s: CanState) {
    val lt = s.i("lights") ?: 0
    val dmode = s.i("dmode") ?: 0
    val evOn = (s.i("ev") ?: 0) != 0
    val spd = s.d("spd") ?: 0.0
    val rpm = s.d("rpm") ?: 0.0
    val brk = s.d("brkP") ?: 0.0
    val hvA = s.d("hvA") ?: 0.0
    val cruise = (s.i("cruise") ?: 0) != 0
    val belt = (s.i("belt") ?: 0) != 0
    val phys = brk > 0.55                          // physical brake (pedal pressed)
    val regen = !phys && hvA < -5.0 && spd > 2     // regen only (charging, pedal at rest, moving)
    val evGhost = !evOn && spd > 2 && rpm < 100     // electric-only drive without EV mode
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (lt and 0x10 != 0) Ic(R.drawable.ic_light_position, CGreen)
        if (lt and 0x20 != 0) Ic(R.drawable.ic_light_low, CGreen)
        if (lt and 0x40 != 0) Ic(R.drawable.ic_light_high, CBlue)
        if (lt and 0x08 != 0) Ic(R.drawable.ic_light_fog, CGreen)
        if (lt and 0x04 != 0) Ic(R.drawable.ic_light_fog, CAmber)
        if (cruise) Ic(R.drawable.ic_cruise, CGreen)
        if (phys) Ic(R.drawable.ic_brake_pedal, CYellow, 32) else if (regen) Ic(R.drawable.ic_brake_pedal, CGreen, 32)
        if (evOn) EvIcon(CGreen) else if (evGhost) EvIcon(CYellow)
        if (dmode == 1) ModeBadge("ECO", CGreen)
        if (dmode == 2) ModeBadge("PWR", COrange)
        if (belt) Ic(R.drawable.ic_seatbelt, CRed)
    }
}

/** Justified (space-between) status line: gear · speed · rpm · odometer · consumption. */
@Composable
private fun OdoRow(s: CanState) {
    val gear = if (s.gear in 0..4) CanState.GEARS[s.gear] else "–"
    val spd = s.d("spd")?.let { "${it.toInt()} km/h" } ?: "– km/h"
    val rpm = "${s.d("rpm")?.toInt() ?: "–"} rpm"
    val odo = s.d("odo")?.let { "%,d km".format(it.toLong()) } ?: "– km"
    val cons = s.d("fuel")?.let { lh ->
        val sp = s.d("spd") ?: 0.0
        if (sp > 3.0) "%.1f l/100km".format(lh / sp * 100.0) else "%.1f l/h".format(lh)
    } ?: "– l/100km"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(gear, spd, rpm, odo, cons).forEach {
            Text(it, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp)
        }
    }
}

@Composable
private fun FuelGauge(s: CanState) {
    // % shows the CALCULATED real fullness (calibrated liters / tank), and the gauge fill matches.
    // Falls back to the raw gauge reading when fuelL is not available (warm-up / old firmware).
    val full = 47f                              // TANK_FULL in prius_parse.h
    val liters = s.d("fuelL")?.toFloat()
    val frac = (liters?.let { it / full } ?: ((s.d("fuelIn") ?: 0.0).toFloat() / 100f)).coerceIn(0f, 1f)
    val pct = liters?.let { (it / full * 100f).toInt() } ?: s.d("fuelIn")?.toInt()
    Box(Modifier.width(60.dp).height(140.dp)) {
        Canvas(Modifier.fillMaxSize()) { gaugeShape(frac, CYellow, battery = false) }
        Text(pct?.let { "$it%" } ?: "–",
            style = PctStyle, modifier = Modifier.align(BiasAlignment(0f, -0.15f)))   // % position unchanged
        // calibrated remaining liters, on a new line UNDER the % (smaller, doesn't move the %)
        s.d("fuelL")?.let { l ->
            // calculated remaining liters; turns amber if it disagrees with the measured gauge (drift)
            val drift = s.d("fuelGm")?.let { kotlin.math.abs(l - it) > 2.5 } ?: false
            Text("%.1f L".format(l), color = if (drift) Color(0xFFFFA000) else Color.White, fontSize = 12.sp,
                style = TextStyle(shadow = Shadow(Color.Black, blurRadius = 8f)),
                modifier = Modifier.align(BiasAlignment(0f, 0.28f)))
        }
        Row(Modifier.align(Alignment.TopCenter).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = CIconDim, fontSize = 16.sp)
            Image(painterResource(R.drawable.ic_fuel), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(CIconDim))
        }
    }
}

@Composable
private fun BatteryGauge(s: CanState) {
    val soc = (s.d("soc") ?: 0.0).toFloat()
    val frac = ((soc - 35f) / (70f - 35f)).coerceIn(0f, 1f)   // operating band ~35..70%
    val hvA = s.d("hvA") ?: 0.0
    Box(Modifier.width(60.dp).height(140.dp)) {
        Canvas(Modifier.fillMaxSize()) { gaugeShape(frac, CGreen, battery = true) }
        Text(s.d("soc")?.let { "${it.toInt()}%" } ?: "–",
            style = PctStyle, modifier = Modifier.align(BiasAlignment(0f, -0.15f)))
        val dir = if (hvA < -2) "▲" else if (hvA > 2) "▼" else ""   // charge direction
        if (dir.isNotEmpty())
            Text(dir, color = Color.White, fontSize = 16.sp,
                style = TextStyle(shadow = Shadow(Color.Black, blurRadius = 8f)),
                modifier = Modifier.align(BiasAlignment(0f, 0.45f)))
    }
}

/** Rounded gauge container + bottom-up fill; battery adds two terminal bumps on top. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.gaugeShape(frac: Float, fill: Color, battery: Boolean) {
    val w = size.width; val h = size.height; val r = 14.dp.toPx()
    val bump = if (battery) 7.dp.toPx() else 0f
    if (battery) {
        val bw = w * 0.22f
        drawRoundRect(CGaugeFrame, topLeft = Offset(w * 0.15f, 0f), size = Size(bw, bump + 2f), cornerRadius = CornerRadius(3f))
        drawRoundRect(CGaugeFrame, topLeft = Offset(w * 0.63f, 0f), size = Size(bw, bump + 2f), cornerRadius = CornerRadius(3f))
    }
    drawRoundRect(CGaugeFrame, topLeft = Offset(0f, bump), size = Size(w, h - bump),
        cornerRadius = CornerRadius(r), style = Stroke(4.dp.toPx()))
    val pad = 7.dp.toPx()
    val innerTop = bump + pad; val innerBot = h - pad
    val fillH = (innerBot - innerTop) * frac.coerceIn(0f, 1f)
    if (fillH > 0f)
        drawRoundRect(fill, topLeft = Offset(pad, innerBot - fillH), size = Size(w - 2 * pad, fillH),
            cornerRadius = CornerRadius(10f))
}

@Composable
private fun GroupTitle(t: String) {
    Text(
        t.uppercase(),
        Modifier.padding(top = 18.dp, bottom = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
    )
}

@Composable
private fun SensorRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = if (highlight) Color(0xFFFFB74D) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        Text(
            value,
            color = if (highlight) Color(0xFFFFB74D) else MaterialTheme.colorScheme.onBackground,
            fontSize = 16.sp, fontFamily = FontFamily.Monospace,
        )
    }
}

fun hasLocationPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun fmtDate(epoch: Long): String =
    if (epoch <= 0) "–"
    else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(epoch * 1000))

private fun fmtDur(s: Long): String {
    val h = s / 3600; val m = (s % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun format(v: Double?, decimals: Int, unit: String): String {
    if (v == null) return "–"
    val num = "%.${decimals}f".format(v)
    return if (unit.isEmpty()) num else "$num $unit"
}

private fun coolantColor(t: Double?): Color = when {
    t == null -> Color(0xFF9EB6C3)
    t < 70 -> Color(0xFF64B5F6)
    t < 98 -> Color(0xFF81C784)
    t < 105 -> Color(0xFFFFB74D)
    else -> Color(0xFFFF5252)
}
