package hu.codingo.priuscan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    val statusOptions = listOf(
        "ct" to "Hűtővíz hőfok",
        "soc" to "HV akku töltöttség",
        "rpm" to "Fordulatszám",
        "cons" to "Aktuális fogyasztás",
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Beállítások", fontSize = 28.sp, color = Color.White)

        Text("Státuszsor elemei", fontSize = 16.sp, color = Color.White)
        Text(
            "Minden bekapcsolt érték saját ikont kap a státuszsorban " +
            "(számként rajzolva), így egyszerre több is látszik.",
            fontSize = 13.sp, color = Color(0xFF9EB6C3),
        )
        statusOptions.forEach { (key, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(
                    checked = key in statusSel,
                    onCheckedChange = {
                        statusSel = if (it) statusSel + key else statusSel - key
                    },
                )
                Text(label, color = Color.White)
            }
        }

        Text(
            "Home Assistant kapcsolat (MQTT). A szenzorok MQTT Discovery-vel " +
            "maguktól megjelennek HA-ban. Net nélkül a batch-ek ts-sel pufferbe " +
            "kerülnek, és kapcsolatnál a $prefix/backlog topicra mennek ki.",
            fontSize = 13.sp, color = Color(0xFF9EB6C3),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = enabled, onCheckedChange = { enabled = it })
            Spacer(Modifier.padding(6.dp))
            Text("HA integráció engedélyezve", color = Color.White)
        }

        OutlinedTextField(host, { host = it }, label = { Text("MQTT broker (host/IP)") },
            Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") },
            Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(user, { user = it }, label = { Text("Felhasználó (opcionális)") },
            Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(pass, { pass = it }, label = { Text("Jelszó") },
            Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(prefix, { prefix = it }, label = { Text("Topic prefix") },
            Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(interval, { interval = it.filter(Char::isDigit) },
            label = { Text("Push időköz (másodperc)") },
            Modifier.fillMaxWidth(), singleLine = true)

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
                onClose()
            },
            Modifier.fillMaxWidth(),
        ) { Text("Mentés") }

        TextButton(onClick = onClose, Modifier.fillMaxWidth()) { Text("Mégse") }
    }
}
