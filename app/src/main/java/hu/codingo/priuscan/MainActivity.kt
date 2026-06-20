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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CanService.start(this)   // the service also runs on USB attach / launcher start
        val prefs = Prefs(this)
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
    item { Header(state) }
    item { SensorRow(stringResource(R.string.f_odo), format(state.d("odo"), 0, "km")) }
    item { SensorRow(stringResource(R.string.f_fuelIn), format(state.d("fuelIn"), 0, "%")) }
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
}

// Trip switcher definitions. idx = position in the firmware "slots" array
// [boot, lifetime, tank, oil, A, B, C, home]; resetCmd = "R<n>" (0 = not resettable);
// hist = 0 none / 1 refuel / 2 oil.
private data class TripDef(val labelRes: Int, val arg: String?, val idx: Int, val resetCmd: Int, val hist: Int)
private val TRIPS = listOf(
    TripDef(R.string.trip_boot, null, 0, 0, 0),
    TripDef(R.string.trip_home, null, 7, 0, 0),   // home: app auto-resets (geofence), no UI reset
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
        SensorRow(stringResource(R.string.r_dist), "%.1f km".format(s.dist))
        SensorRow(stringResource(R.string.f_tEv), "%.1f km".format(s.ev))
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
        if (def.hist != 0) {
            LaunchedEffect(sel) { if (def.hist == 1) CanService.fetchRefuelHistory() else CanService.fetchOilHistory() }
            val h = if (def.hist == 1) refuelHist else oilHist
            Spacer(Modifier.height(8.dp))
            GroupTitle(stringResource(R.string.r_history))
            if (h.isEmpty()) {
                Text(stringResource(R.string.r_none), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            } else {
                h.asReversed().forEach { r ->   // newest first
                    SensorRow(
                        fmtDate(r.epoch),
                        "%.0f km · %.0f EV · %.1f l/100km · %.0f%% regen".format(r.dist, r.ev, r.avgCons, r.regen),
                    )
                }
            }
        }
    }
}

/** Press-and-hold (~1.3 s) to reset, with a circular progress around the button. */
@Composable
private fun ResetHoldButton(onReset: () -> Unit) {
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
        Text("⟲", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)
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

@Composable
private fun Header(s: CanState) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                s.coolant?.let { "${it.toInt()}°" } ?: "–",
                fontSize = 72.sp, fontWeight = FontWeight.Bold,
                color = coolantColor(s.coolant),
            )
            Spacer(Modifier.size(16.dp))
            Column(Modifier.padding(bottom = 12.dp)) {
                Text(stringResource(R.string.coolant_caption), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                val gear = if (s.gear in 0..4) CanState.GEARS[s.gear] else "–"
                val line = buildString {
                    append("$gear  •  ${s.d("rpm")?.toInt() ?: "–"} rpm  •  SoC ${s.d("soc")?.toInt() ?: "–"}%")
                    // consumption: l/100km while moving, l/h when stationary
                    s.d("fuel")?.let { lh ->
                        val sp = s.d("spd") ?: 0.0
                        if (sp > 3.0) append("  •  %.1f l/100km".format(lh / sp * 100.0))
                        else append("  •  %.1f l/h".format(lh))
                    }
                }
                Text(line, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp)
            }
        }
        if (s.wpWarn > 0 || s.cellWarn > 0) {
            // stringResource must be resolved in composable scope, before buildString
            val wOver = stringResource(R.string.warn_overheat)
            val wCool = stringResource(R.string.warn_cooling)
            val wFault = stringResource(R.string.warn_hv_fault)
            val wCell = stringResource(R.string.warn_hv_cell)
            Text(
                buildString {
                    if (s.wpWarn > 0) append(if (s.wpWarn >= 2) wOver else wCool)
                    if (s.cellWarn > 0) append(if (s.cellWarn >= 2) wFault else wCell)
                },
                color = Color(0xFFFF5252), fontWeight = FontWeight.Bold, fontSize = 15.sp,
            )
        }
    }
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
