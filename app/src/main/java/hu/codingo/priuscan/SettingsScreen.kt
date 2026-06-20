package hu.codingo.priuscan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(prefs: Prefs, onClose: () -> Unit) {
    var enabled by remember { mutableStateOf(prefs.haEnabled) }
    var host by remember { mutableStateOf(prefs.mqttHost) }
    var port by remember { mutableStateOf(prefs.mqttPort.toString()) }
    var user by remember { mutableStateOf(prefs.mqttUser) }
    var pass by remember { mutableStateOf(prefs.mqttPass) }
    var prefix by remember { mutableStateOf(prefs.topicPrefix) }
    var interval by remember { mutableStateOf(prefs.pushIntervalSec.toString()) }
    var statusSel by remember { mutableStateOf(prefs.statusItems) }
    var fontSize by remember { mutableStateOf(prefs.statusFontSize.toInt().toString()) }
    var refAh by remember { mutableStateOf(prefs.batteryRefAh.toString()) }
    var logEnabled by remember { mutableStateOf(prefs.logEnabled) }
    var autoDark by remember { mutableStateOf(prefs.autoDarkCar) }
    var dumpUnknown by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    val logTemplate = stringResource(R.string.log_info)
    var logInfo by remember { mutableStateOf(logInfoText(ctx, logTemplate)) }

    // permissions (re-evaluated on resume after a system dialog)
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var locOk by remember { mutableStateOf(hasLocationPermission(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        overlayOk = Settings.canDrawOverlays(ctx); locOk = hasLocationPermission(ctx)
    }
    val locLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res -> if (res.values.any { it }) { locOk = true; CanService.start(ctx) } }

    val tpmsIds by CanService.tpmsIds.collectAsState()
    val pairing by CanService.tpmsPairing.collectAsState()
    val espConnected by CanService.connected.collectAsState()
    val espDevice by CanService.deviceInfo.collectAsState()
    val tpmsConn by CanService.tpmsConnected.collectAsState()
    val tpmsDev by CanService.tpmsDevice.collectAsState()
    val dumpActive by CanService.dumpActive.collectAsState()
    val dumpBytes by CanService.dumpBytes.collectAsState()
    val dumpFile by CanService.dumpFile.collectAsState()
    val fwRun by CanService.fwRunning.collectAsState()
    val flSt by CanService.flashState.collectAsState()
    val flPct by CanService.flashProgress.collectAsState()
    val flMsg by CanService.flashMsg.collectAsState()
    val tpmsWheels = listOf(
        Wheel.FL to R.string.tpms_fl, Wheel.FR to R.string.tpms_fr,
        Wheel.RL to R.string.tpms_rl, Wheel.RR to R.string.tpms_rr,
        Wheel.SPARE to R.string.tpms_spare,
    )

    val statusOptions = listOf(
        "ct" to R.string.opt_ct,
        "soc" to R.string.opt_soc,
        "rpm" to R.string.opt_rpm,
        "cons" to R.string.opt_cons,
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), fontSize = 28.sp, color = MaterialTheme.colorScheme.onBackground)

        // ---- Connection status (ESP + TPMS) ----
        Text(stringResource(R.string.connection_title), fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
        StatusRow(espConnected, stringResource(if (espConnected) R.string.connected else R.string.disconnected), espDevice.takeIf { espConnected })
        StatusRow(tpmsConn, stringResource(if (tpmsConn) R.string.tpms_connected else R.string.tpms_no_signal), tpmsDev.takeIf { tpmsConn })

        // ---- Permissions ----
        Text(stringResource(R.string.permissions_title), fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
        if (!overlayOk) {
            Button(
                onClick = { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) },
                Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.perm_overlay)) }
        }
        Button(
            onClick = {
                val pm = ctx.getSystemService(PowerManager::class.java)
                if (pm != null && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }
            },
            Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.perm_battery)) }
        if (!locOk) {
            Button(
                onClick = {
                    locLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                },
                Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.perm_location)) }
        }

        Text(stringResource(R.string.status_items_title), fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
        Text(
            stringResource(R.string.status_items_desc),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        statusOptions.forEach { (key, labelRes) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(
                    checked = key in statusSel,
                    onCheckedChange = {
                        statusSel = if (it) statusSel + key else statusSel - key
                    },
                )
                Text(stringResource(labelRes), color = MaterialTheme.colorScheme.onBackground)
            }
        }

        OutlinedTextField(fontSize, { fontSize = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.font_size_label)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true)

        TextButton(onClick = { prefs.statusX = -1; prefs.statusY = -1 }) {
            Text(stringResource(R.string.reset_status_pos))
        }

        // ---- Local logging (without HA) ----
        Text(stringResource(R.string.local_logging_title), fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
        Text(
            stringResource(R.string.local_logging_desc, logInfo),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = logEnabled, onCheckedChange = { logEnabled = it; prefs.logEnabled = it })
            Spacer(Modifier.padding(6.dp))
            Text(stringResource(R.string.logging_enabled), color = MaterialTheme.colorScheme.onBackground)
        }
        Row {
            TextButton(onClick = {
                DataLogger.shareIntent(ctx)?.let {
                    ctx.startActivity(Intent.createChooser(it, ctx.getString(R.string.download_logs)))
                }
            }) { Text(stringResource(R.string.download_logs)) }
            Spacer(Modifier.padding(8.dp))
            TextButton(onClick = {
                DataLogger.deleteAll(ctx)
                logInfo = logInfoText(ctx, logTemplate)
            }) { Text(stringResource(R.string.delete_logs)) }
        }

        // ---- CAN dump (diagnostic capture) ----
        Text(stringResource(R.string.dump_title), fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
        Text(stringResource(R.string.dump_desc), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = dumpActive, onCheckedChange = { CanService.setDump(it, dumpUnknown) })
            Spacer(Modifier.padding(6.dp))
            Text(
                when {
                    dumpActive -> "● " + DataLogger.humanSize(dumpBytes)
                    dumpFile != null -> DataLogger.humanSize(java.io.File(dumpFile!!).length())
                    else -> stringResource(R.string.dump_enabled)
                },
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (!dumpActive) Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = dumpUnknown, onCheckedChange = { dumpUnknown = it })
            Text(stringResource(R.string.dump_unknown), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        dumpFile?.let { path ->
            if (!dumpActive) TextButton(onClick = {
                DumpLogger.shareIntent(ctx, path)?.let {
                    ctx.startActivity(Intent.createChooser(it, ctx.getString(R.string.dump_share)))
                }
            }) { Text(stringResource(R.string.dump_share)) }
        }

        // ---- Firmware update (over USB) ----
        Text(stringResource(R.string.flash_title), fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "ESP: " + CanService.fmtFw(fwRun ?: 0) + "  /  v" + CanService.fmtFw(CanService.BUNDLED_FW),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (flSt) {
            FlashState.FLASHING -> {
                Text(flMsg, color = MaterialTheme.colorScheme.onBackground)
                LinearProgressIndicator(progress = { flPct / 100f }, modifier = Modifier.fillMaxWidth())
                Text("$flPct %", color = MaterialTheme.colorScheme.onBackground)
            }
            FlashState.DONE -> Text(flMsg, color = Color(0xFF7CFC00))
            FlashState.ERROR -> Text(flMsg, color = Color(0xFFFF6B6B))
            else -> {
                if (fwRun != null && fwRun!! < CanService.BUNDLED_FW) {
                    Text(stringResource(R.string.flash_warn), fontSize = 12.sp, color = Color(0xFFFFB74D))
                    Button(onClick = { CanService.requestFlash() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.flash_update_btn, CanService.fmtFw(CanService.BUNDLED_FW)))
                    }
                } else {
                    Text(stringResource(R.string.flash_uptodate), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ---- Display: dark mode from the car's light sensor ----
        Text(stringResource(R.string.display_title), fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = autoDark, onCheckedChange = { autoDark = it; prefs.autoDarkCar = it })
            Spacer(Modifier.padding(6.dp))
            Text(stringResource(R.string.theme_auto_car), color = MaterialTheme.colorScheme.onBackground)
        }

        // ---- HV battery ----
        OutlinedTextField(refAh, { refAh = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text(stringResource(R.string.battery_ref_label)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true)

        // ---- TPMS pairing ----
        Text(stringResource(R.string.tpms_section), fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
        TextButton(onClick = { CanService.sendTpmsCommand(Tpms.QUERY) }) {
            Text(stringResource(R.string.tpms_query))
        }
        tpmsWheels.forEach { (w, lblRes) ->
            val lbl = stringResource(lblRes)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { CanService.startTpmsPairing(w) }) {
                    Text(stringResource(R.string.tpms_pair_button, lbl))
                }
                tpmsIds[w]?.let {
                    Spacer(Modifier.padding(6.dp))
                    Text(stringResource(R.string.tpms_id_fmt, it), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
        TextButton(onClick = { CanService.stopTpmsPairing() }) {
            Text(stringResource(R.string.tpms_pair_stop))
        }

        Text(
            stringResource(R.string.ha_desc, prefix),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = enabled, onCheckedChange = { enabled = it })
            Spacer(Modifier.padding(6.dp))
            Text(stringResource(R.string.ha_enabled), color = MaterialTheme.colorScheme.onBackground)
        }

        OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.mqtt_broker)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.mqtt_port)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.mqtt_user)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(pass, { pass = it }, label = { Text(stringResource(R.string.mqtt_pass)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(prefix, { prefix = it }, label = { Text(stringResource(R.string.mqtt_topic_prefix)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(interval, { interval = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.push_interval)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true)

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                prefs.haEnabled = enabled
                prefs.mqttHost = host
                prefs.mqttPort = port.toIntOrNull() ?: 1883
                prefs.mqttUser = user
                prefs.mqttPass = pass
                prefs.topicPrefix = prefix.ifBlank { "priuscan" }
                prefs.pushIntervalSec = interval.toIntOrNull() ?: 30
                prefs.statusItems = statusSel
                prefs.statusFontSize = fontSize.toFloatOrNull() ?: 14f
                prefs.batteryRefAh = refAh.toFloatOrNull() ?: 6.5f
                onClose()
            },
            Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.save)) }

        TextButton(onClick = onClose, Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
    }

    // ---- TPMS pairing dialog (with ~30 s countdown; auto-stop on success/timeout) ----
    pairing?.let { w ->
        var remaining by remember(w) { mutableStateOf(30) }
        LaunchedEffect(w) {
            remaining = 30
            while (remaining > 0 && CanService.tpmsPairing.value == w) {
                kotlinx.coroutines.delay(1000); remaining--
            }
            if (CanService.tpmsPairing.value == w) CanService.stopTpmsPairing()
        }
        val lbl = stringResource(
            tpmsWheels.firstOrNull { it.first == w }?.second ?: R.string.tpms_spare
        )
        AlertDialog(
            onDismissRequest = { CanService.stopTpmsPairing() },
            title = { Text(stringResource(R.string.tpms_pair_title, lbl)) },
            text = {
                Text(
                    stringResource(R.string.tpms_pair_msg) + "  " +
                        stringResource(R.string.tpms_pair_countdown, remaining)
                )
            },
            confirmButton = {
                TextButton(onClick = { CanService.stopTpmsPairing() }) {
                    Text(stringResource(R.string.tpms_pair_stop))
                }
            },
        )
    }
}

/** Builds the "<size> (<n> files)" string from the localized template. */
private fun logInfoText(ctx: Context, template: String): String =
    template.format(DataLogger.humanSize(DataLogger.totalBytes(ctx)), DataLogger.files(ctx).size)

/** Connection status row: green/red dot + label, optional device sub-line. */
@Composable
private fun StatusRow(ok: Boolean, label: String, sub: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (ok) Color(0xFF66BB6A) else Color(0xFFEF5350)))
        Spacer(Modifier.padding(5.dp))
        Column {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            if (sub != null) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}
