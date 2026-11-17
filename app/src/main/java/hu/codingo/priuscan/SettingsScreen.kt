package hu.codingo.priuscan

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(prefs: Prefs, onClose: () -> Unit) {
    var enabled by remember { mutableStateOf(prefs.haEnabled) }
    var host by remember { mutableStateOf(prefs.mqttHost) }
    var port by remember { mutableStateOf(prefs.mqttPort.toString()) }
    var user by remember { mutableStateOf(prefs.mqttUser) }
    var pass by remember { mutableStateOf(prefs.mqttPass) }
    var prefix by remember { mutableStateOf(prefs.topicPrefix) }
    var interval by remember { mutableStateOf(prefs.pushIntervalSec.toString()) }
    var wgTunnel by remember { mutableStateOf(prefs.wgTunnel) }
    var statusSel by remember { mutableStateOf(prefs.statusItems) }
    var fontSize by remember { mutableStateOf(prefs.statusFontSize.toInt().toString()) }
    var refAh by remember { mutableStateOf(prefs.batteryRefAh.toString()) }
    var oilKm by remember { mutableStateOf(prefs.oilKm.toString()) }
    var oilMonths by remember { mutableStateOf(prefs.oilMonths.toString()) }
    var fuelCorr by remember { mutableStateOf("") }
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
    val canState by CanService.state.collectAsState()
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

    val c = pc
    // hoisted so a narrow/wide relayout doesn't reset them
    var homeLat by remember { mutableStateOf(prefs.homeLat) }
    var homeLon by remember { mutableStateOf(prefs.homeLon) }
    val gpsLoc by CanService.gps.collectAsState()
    val appSt by AppUpdater.state.collectAsState()
    val appPct by AppUpdater.progress.collectAsState()
    val appLatest by AppUpdater.latestName.collectAsState()
    val appMsg by AppUpdater.msg.collectAsState()
    val gfMsg by AppUpdater.geofenceMsg.collectAsState()
    LaunchedEffect(Unit) { AppUpdater.check(ctx) }
    val nightPermRuntime by CanService.nightPermMissing.collectAsState()

    // ---------- left column: connection, permissions, display, status strip, logging, dump ----------
    val leftCol: @Composable ColumnScope.() -> Unit = {
        // ---- Connection status (ESP + TPMS) ----
        SettingsCard(stringResource(R.string.connection_title)) {
            StatusRow(espConnected, stringResource(if (espConnected) R.string.connected else R.string.disconnected), espDevice.takeIf { espConnected })
            StatusRow(tpmsConn, stringResource(if (tpmsConn) R.string.tpms_connected else R.string.tpms_no_signal), tpmsDev.takeIf { tpmsConn })
        }

        // ---- Permissions ----
        SettingsCard(stringResource(R.string.permissions_title)) {
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
        }

        // ---- Display: dark mode from the car's light sensor ----
        SettingsCard(stringResource(R.string.display_title)) {
            SwitchRow(stringResource(R.string.theme_auto_car), autoDark) { autoDark = it; prefs.autoDarkCar = it }
            // A reinstall / in-app OTA revokes the adb-granted WRITE_SECURE_SETTINGS, and the system-
            // wide flip then fails SILENTLY. Probe the perm directly (live, every time Settings opens)
            // + the service's runtime detection, and show the exact fix instead of a mystery.
            val nightPermOk = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (autoDark && (!nightPermOk || nightPermRuntime)) {
                Text(stringResource(R.string.night_perm_missing), fontSize = 14.sp, color = c.redText)
                Text("adb shell pm grant hu.codingo.priuscan android.permission.WRITE_SECURE_SETTINGS",
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.dim)
            }
        }

        // ---- Status strip ----
        SettingsCard(stringResource(R.string.status_items_title)) {
            Text(stringResource(R.string.status_items_desc), fontSize = 14.sp, color = c.dim)
            statusOptions.forEach { (key, labelRes) ->
                SwitchRow(stringResource(labelRes), key in statusSel) {
                    statusSel = if (it) statusSel + key else statusSel - key
                }
            }
            OutlinedTextField(fontSize, { fontSize = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.font_size_label)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            TextButton(onClick = { prefs.statusX = -1; prefs.statusY = -1 }) {
                Text(stringResource(R.string.reset_status_pos))
            }
        }

        // ---- Local logging (without HA) ----
        SettingsCard(stringResource(R.string.local_logging_title)) {
            Text(stringResource(R.string.local_logging_desc, logInfo), fontSize = 14.sp, color = c.dim)
            SwitchRow(stringResource(R.string.logging_enabled), logEnabled) { logEnabled = it; prefs.logEnabled = it }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    DataLogger.shareIntent(ctx)?.let {
                        ctx.startActivity(Intent.createChooser(it, ctx.getString(R.string.download_logs)))
                    }
                }) { Text(stringResource(R.string.download_logs)) }
                TextButton(onClick = {
                    DataLogger.deleteAll(ctx)
                    logInfo = logInfoText(ctx, logTemplate)
                }) { Text(stringResource(R.string.delete_logs)) }
            }
        }

        // ---- CAN dump (diagnostic capture) ----
        SettingsCard(stringResource(R.string.dump_title)) {
            Text(stringResource(R.string.dump_desc), fontSize = 14.sp, color = c.dim)
            SwitchRow(
                when {
                    dumpActive -> "● " + DataLogger.humanSize(dumpBytes)
                    dumpFile != null -> DataLogger.humanSize(java.io.File(dumpFile!!).length())
                    else -> stringResource(R.string.dump_enabled)
                },
                dumpActive,
            ) { CanService.setDump(it, dumpUnknown) }
            if (!dumpActive) SwitchRow(stringResource(R.string.dump_unknown), dumpUnknown, dim = true) { dumpUnknown = it }
            dumpFile?.let { path ->
                if (!dumpActive) TextButton(onClick = {
                    DumpLogger.shareIntent(ctx, path)?.let {
                        ctx.startActivity(Intent.createChooser(it, ctx.getString(R.string.dump_share)))
                    }
                }) { Text(stringResource(R.string.dump_share)) }
            }
        }
    }

    // ---------- right column: firmware, app update, home, vehicle, TPMS, HA ----------
    val rightCol: @Composable ColumnScope.() -> Unit = {
        // ---- Firmware update (over USB) ----
        SettingsCard(stringResource(R.string.flash_title)) {
            Text(
                "ESP: " + CanService.fmtFw(fwRun ?: 0) + "  /  v" + CanService.fmtFw(CanService.BUNDLED_FW),
                fontSize = 14.sp, color = c.dim,
            )
            // NVS persistence test: restored shutdown epoch (offTs) + save counter (offN)
            run {
                val offN = canState.i("offN")
                val offTs = canState.d("offTs")?.toLong() ?: 0L
                val tsTxt = if (offTs > 0)
                    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(offTs * 1000))
                else "—"
                Text(stringResource(R.string.nvs_status, offN, tsTxt),
                    fontSize = 14.sp,
                    color = if (offN > 0) c.greenText else c.dim)
            }
            when (flSt) {
                FlashState.FLASHING -> {
                    Text(flMsg, color = c.text)
                    LinearProgressIndicator(progress = { flPct / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("$flPct %", color = c.text)
                }
                FlashState.DONE -> Text(flMsg, color = c.greenText)
                FlashState.ERROR -> Text(flMsg, color = c.redText)
                else -> {
                    if (fwRun != null && fwRun!! < CanService.BUNDLED_FW) {
                        Text(stringResource(R.string.flash_warn), fontSize = 13.sp, color = c.amberText)
                        Button(onClick = { CanService.requestFlash() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.flash_update_btn, CanService.fmtFw(CanService.BUNDLED_FW)))
                        }
                    } else {
                        Text(stringResource(R.string.flash_uptodate), fontSize = 14.sp, color = c.dim)
                    }
                }
            }
        }

        // ---- App update (over GitHub Releases) ----
        SettingsCard(stringResource(R.string.app_update_title)) {
            Text(
                "App: v" + BuildConfig.VERSION_NAME + (if (gfMsg.isNotEmpty()) "   ·   geo $gfMsg" else ""),
                fontSize = 14.sp, color = c.dim,
            )
            when (appSt) {
                AppUpdater.State.CHECKING -> Text(stringResource(R.string.app_checking), fontSize = 14.sp, color = c.dim)
                AppUpdater.State.DOWNLOADING -> {
                    LinearProgressIndicator(progress = { appPct / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("$appPct %", color = c.text)
                }
                AppUpdater.State.AVAILABLE -> {
                    Button(onClick = { AppUpdater.update(ctx) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.app_update_btn, appLatest ?: ""))
                    }
                }
                AppUpdater.State.ERROR -> Text(appMsg, fontSize = 14.sp, color = c.redText)
                else -> Text(stringResource(R.string.app_uptodate), fontSize = 14.sp, color = c.dim)
            }
        }

        // ---- Home location: auto-resets the "from-home" trip (slot 6) when the car departs here ----
        SettingsCard(null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    CardTitle(stringResource(R.string.home_title))
                    Text(
                        if (!homeLat.isNaN() && !homeLon.isNaN()) "%.5f, %.5f".format(homeLat, homeLon)
                        else stringResource(R.string.home_unset),
                        fontSize = 14.sp, color = c.dim)
                }
                TextButton(enabled = gpsLoc != null, onClick = {
                    gpsLoc?.let {
                        prefs.homeLat = it.latitude.toFloat(); prefs.homeLon = it.longitude.toFloat()
                        homeLat = prefs.homeLat; homeLon = prefs.homeLon
                    }
                }) { Text(stringResource(R.string.home_set_btn)) }
            }
        }

        // ---- Vehicle: HV battery reference, oil interval, fuel correction, manual refuel ----
        SettingsCard(null) {
            OutlinedTextField(refAh, { refAh = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text(stringResource(R.string.battery_ref_label)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)

            // ---- Oil-change interval (km + months; drives the due indicator on the Oil trip tab) ----
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(oilKm, { oilKm = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.oil_interval_km)) },
                    modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(oilMonths, { oilMonths = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.oil_interval_mo)) },
                    modifier = Modifier.weight(1f), singleLine = true)
            }

            // ---- Fuel correction (sent to the ESP, persisted there; affects instant + trip fuel) ----
            val curFCorr = canState.d("fCorr")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(fuelCorr, { fuelCorr = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text(stringResource(R.string.fuel_corr_label) + (curFCorr?.let { " — %.3f".format(it) } ?: "")) },
                    modifier = Modifier.weight(1f), singleLine = true)
                TextButton(onClick = { fuelCorr.toFloatOrNull()?.let { if (it in 0.5f..2.0f) CanService.sendCommand("F$it") } }) {
                    Text(stringResource(R.string.btn_save))
                }
            }
            // Manual refuel: use when the firmware missed the auto-detection (e.g. tanked across a
            // power-off). Closes the current tank into the history and starts a fresh one.
            TextButton(onClick = { CanService.sendCommand("K") }) {
                Text(stringResource(R.string.refuel_btn))
            }
        }

        // ---- TPMS pairing ----
        SettingsCard(stringResource(R.string.tpms_section)) {
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
                        Text(stringResource(R.string.tpms_id_fmt, it), color = c.dim, fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
            TextButton(onClick = { CanService.stopTpmsPairing() }) {
                Text(stringResource(R.string.tpms_pair_stop))
            }
        }

        // ---- Home Assistant (MQTT) ----
        SettingsCard(null) {
            Text(stringResource(R.string.ha_desc, prefix), fontSize = 14.sp, color = c.dim)
            SwitchRow(stringResource(R.string.ha_enabled), enabled) { enabled = it }
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
            OutlinedTextField(wgTunnel, { wgTunnel = it },
                label = { Text(stringResource(R.string.wg_tunnel)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    val twoCols = maxWidth >= 900.dp
    Column(
        Modifier.fillMaxHeight().widthIn(max = 1400.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = c.text)

        // the main settings file read back EMPTY at startup (torn write at a power cut) ->
        // tell the user what happened + what to re-check instead of silently showing defaults
        if (prefs.mainSettingsLost) {
            Text(stringResource(R.string.settings_lost_warn), fontSize = 14.sp, color = c.redText)
        }

        if (twoCols) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = leftCol)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = rightCol)
            }
        } else {
            leftCol()
            rightCol()
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    prefs.haEnabled = enabled
                    prefs.mqttHost = host
                    prefs.mqttPort = port.toIntOrNull() ?: 1883
                    prefs.mqttUser = user
                    prefs.mqttPass = pass
                    prefs.topicPrefix = prefix.ifBlank { "priuscan" }
                    prefs.pushIntervalSec = interval.toIntOrNull() ?: 30
                    prefs.wgTunnel = wgTunnel
                    prefs.statusItems = statusSel
                    prefs.statusFontSize = fontSize.toFloatOrNull() ?: 14f
                    prefs.batteryRefAh = refAh.toFloatOrNull() ?: 6.5f
                    prefs.oilKm = oilKm.toIntOrNull() ?: 10000
                    prefs.oilMonths = oilMonths.toIntOrNull() ?: 12
                    onClose()
                },
                Modifier.weight(1f).height(52.dp),
            ) { Text(stringResource(R.string.save), fontSize = 17.sp) }

            TextButton(onClick = onClose, Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.cancel), fontSize = 17.sp) }
        }
        Spacer(Modifier.height(12.dp))
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
}

/** Builds the "<size> (<n> files)" string from the localized template. */
private fun logInfoText(ctx: Context, template: String): String =
    template.format(DataLogger.humanSize(DataLogger.totalBytes(ctx)), DataLogger.files(ctx).size)

/** Connection status row: green/red dot + label, optional device sub-line. */
@Composable
private fun StatusRow(ok: Boolean, label: String, sub: String?) {
    val c = pc
    Row(
        Modifier.fillMaxWidth().border(1.dp, c.cardBorder, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(if (ok) c.green else c.red))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(label, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, color = c.dim, fontSize = 13.sp)
        }
    }
}

/** A settings group: card with an optional title, 10 dp between the rows. */
@Composable
private fun SettingsCard(title: String?, content: @Composable ColumnScope.() -> Unit) {
    PCard(Modifier.fillMaxWidth(), padding = 18.dp) {
        if (title != null) {
            CardTitle(title)
            Spacer(Modifier.height(10.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Label on the left, Switch on the right (whole-row text wraps instead of clipping). */
@Composable
private fun SwitchRow(label: String, checked: Boolean, dim: Boolean = false, onChange: (Boolean) -> Unit) {
    val c = pc
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp),
            color = if (dim) c.dim else c.text, fontSize = if (dim) 15.sp else 16.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
