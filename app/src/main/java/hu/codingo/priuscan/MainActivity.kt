package hu.codingo.priuscan

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
        add(stringResource(R.string.gps_title))
        add(stringResource(R.string.tab_refuel))
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
            0 -> DashboardTab(state, tpmsReadings)
            in 1..groups.size -> {
                val (titleRes, fields) = groups[tab - 1]
                if (titleRes == R.string.grp_hybrid_batt) HybridBatteryTab(state, fields, prefs.batteryRefAh)
                else GroupTab(state, fields)
            }
            groups.size + 1 -> GpsTab(gpsLoc)
            groups.size + 2 -> RefuelTab()
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
            Modifier.widthIn(max = 600.dp).fillMaxHeight().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            content()
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Tab 1: glance dashboard - coolant/gear/rpm/consumption + trip + car & tyres. */
@Composable
private fun DashboardTab(state: CanState, tpms: Map<Wheel, TireReading>) = tabColumn {
    item { Header(state) }
    // trip summary on the first screen (since ESP boot): odo / fuel / dist / EV / avg
    item { GroupTitle(stringResource(R.string.grp_trip)) }
    itemsIndexed(Fields.trip) { _, f ->
        SensorRow(stringResource(f.labelRes), format(state.d(f.key), f.decimals, f.unit))
    }
    // active moving time since ESP boot (computed in firmware)
    item { SensorRow(stringResource(R.string.r_movetime), fmtDur(state.d("tMove")?.toLong() ?: 0L)) }
    item { GroupTitle(stringResource(R.string.tpms_title)) }
    item { TpmsCarView(tpms) }
}

@Composable
private fun GroupTab(state: CanState, fields: List<Field>) = tabColumn {
    itemsIndexed(fields) { _, f ->
        SensorRow(stringResource(f.labelRes), format(state.d(f.key), f.decimals, f.unit))
    }
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

@Composable
private fun RefuelTab() {
    val live by CanService.tripLive.collectAsState()
    val hist by CanService.tripHistory.collectAsState()
    tabColumn {
        item { GroupTitle(stringResource(R.string.r_since_refuel)) }
        item { SensorRow(stringResource(R.string.r_time), fmtDur(live.elapsedS)) }
        item { SensorRow(stringResource(R.string.r_dist), "%.1f km".format(live.distKm)) }
        item { SensorRow(stringResource(R.string.f_tEv), "%.1f km".format(live.evKm)) }
        item { SensorRow(stringResource(R.string.r_avgspeed), "%.0f km/h".format(live.avgKmh)) }
        item { SensorRow(stringResource(R.string.r_fuelused), "%.2f L".format(live.fuelL)) }
        item { SensorRow(stringResource(R.string.r_avgcons), "%.1f l/100km".format(live.avgCons)) }
        item { GroupTitle(stringResource(R.string.r_history)) }
        if (hist.isEmpty()) {
            item { Text(stringResource(R.string.r_none), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
        }
        itemsIndexed(hist) { _, r ->
            SensorRow(
                fmtDate(r.epoch),
                "%.0f km (%.0f EV) · %.0f km/h · %.1f L · %.1f l/100km".format(r.distKm, r.evKm, r.avgKmh, r.fuelL, r.avgCons),
            )
        }
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
                    s.d("fuel")?.let { append("  •  %.1f l/h".format(it)) }   // consumption
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
