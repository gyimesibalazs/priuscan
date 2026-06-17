package hu.codingo.priuscan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CanService.start(this)   // USB attach / launcher inditasnal is megy a service
        val prefs = Prefs(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0E1216)) {
                    var showSettings by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(false)
                    }
                    if (showSettings) {
                        SettingsScreen(prefs) { showSettings = false }
                    } else {
                        MainScreen(
                            onOverlayPerm = {
                                startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:$packageName")
                                    )
                                )
                            },
                            onBatteryPerm = {
                                val pm = getSystemService(PowerManager::class.java)
                                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                                    startActivity(
                                        Intent(
                                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:$packageName")
                                        )
                                    )
                                }
                            },
                            overlayGranted = { Settings.canDrawOverlays(this) },
                            onSettings = { showSettings = true },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    onOverlayPerm: () -> Unit,
    onBatteryPerm: () -> Unit,
    overlayGranted: () -> Boolean,
    onSettings: () -> Unit,
) {
    val state by CanService.state.collectAsState()
    val connected by CanService.connected.collectAsState()
    val device by CanService.deviceInfo.collectAsState()
    // a Beallitasok/rendszer-dialogbol visszaterve ujraertekeljuk az engedelyt,
    // hogy a gomb eltunjon, ha kozben megadtak
    var granted by remember { mutableStateOf(overlayGranted()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = overlayGranted() }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.TextButton(onClick = onSettings) {
                    Text("⚙ Beállítások")
                }
            }
        }
        item { Header(state, connected, device) }

        if (!granted) {
            item {
                Button(onClick = onOverlayPerm, Modifier.fillMaxWidth()) {
                    Text("Overlay engedély megadása (riasztásokhoz kötelező)")
                }
            }
        }
        item {
            Button(onClick = onBatteryPerm, Modifier.fillMaxWidth()) {
                Text("Akkumulátor-optimalizálás kikapcsolása")
            }
        }

        Fields.groups.forEach { (title, fields) ->
            item { GroupTitle(title) }
            itemsIndexed(fields) { _, f ->
                SensorRow(f.label, format(state.d(f.key), f.decimals, f.unit))
            }
        }

        item { GroupTitle("HV blokkfeszültségek") }
        itemsIndexed((1..14).toList()) { _, i ->
            val v = state.d("b%02d".format(i))
            SensorRow(
                "Blokk %02d".format(i),
                if (v == null) "–" else "%.2f V".format(v),
                highlight = state.i("weakB") == i,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Header(s: CanState, connected: Boolean, device: String?) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(12.dp).clip(CircleShape)
                    .background(if (connected) Color(0xFF66BB6A) else Color(0xFFEF5350))
            )
            Spacer(Modifier.size(8.dp))
            Text(
                if (connected) "PriusCAN kapcsolódva" else "Nincs kapcsolat…",
                color = Color(0xFF9EB6C3), fontSize = 14.sp,
            )
        }
        // pontosan melyik USB-eszkoz adja az ervenyes PriusCAN-adatot
        if (connected && device != null) {
            Text(
                device,
                color = Color(0xFF5E7A8A), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                s.coolant?.let { "${it.toInt()}°" } ?: "–",
                fontSize = 72.sp, fontWeight = FontWeight.Bold,
                color = coolantColor(s.coolant),
            )
            Spacer(Modifier.size(16.dp))
            Column(Modifier.padding(bottom = 12.dp)) {
                Text("hűtővíz", color = Color(0xFF9EB6C3), fontSize = 13.sp)
                val gear = if (s.gear in 0..4) CanState.GEARS[s.gear] else "–"
                Text(
                    "$gear  •  ${s.d("rpm")?.toInt() ?: "–"} rpm  •  SoC ${s.d("soc")?.toInt() ?: "–"}%",
                    color = Color.White, fontSize = 16.sp,
                )
            }
        }
        if (s.wpWarn > 0 || s.cellWarn > 0) {
            Text(
                buildString {
                    if (s.wpWarn > 0) append(if (s.wpWarn >= 2) "⚠ TÚLMELEGEDÉS  " else "⚠ Hűtés figyelmeztetés  ")
                    if (s.cellWarn > 0) append(if (s.cellWarn >= 2) "⚠ HV AKKU HIBA" else "⚠ HV cella figyelmeztetés")
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
        color = Color(0xFF5E7A8A), fontSize = 12.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
    )
}

@Composable
private fun SensorRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = if (highlight) Color(0xFFFFB74D) else Color(0xFFB9C6CE), fontSize = 16.sp)
        Text(
            value,
            color = if (highlight) Color(0xFFFFB74D) else Color.White,
            fontSize = 16.sp, fontFamily = FontFamily.Monospace,
        )
    }
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
