package com.pebbllink.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private lateinit var link: WatchLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        link = WatchLink(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { PebblLinkScreen(link) }
            }
        }
    }

    override fun onDestroy() {
        link.stopScan()
        link.disconnect()
        super.onDestroy()
    }
}

private val permissions: Array<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
private fun PebblLinkScreen(link: WatchLink) {
    val ui by link.ui.collectAsState()
    var selected by remember { mutableStateOf<CharInfo?>(null) }
    var hex by remember { mutableStateOf("") }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) link.startScan()
    }

    Column(Modifier.statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pebbl Link", style = MaterialTheme.typography.headlineMedium)
        Text(
            when (ui.state) {
                LinkState.Idle -> "Not connected"
                LinkState.Scanning -> "Scanning for watches…"
                LinkState.Connecting -> "Connecting…"
                LinkState.Connected -> "Connected"
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.state == LinkState.Connected || ui.state == LinkState.Connecting) {
                OutlinedButton(onClick = { link.disconnect() }) { Text("Disconnect") }
            } else if (ui.state == LinkState.Scanning) {
                OutlinedButton(onClick = { link.stopScan() }) { Text("Stop scan") }
            } else {
                Button(onClick = { askPermission.launch(permissions) }) { Text("Scan") }
            }
        }

        if (ui.state == LinkState.Connected) {
            Text("Characteristics (tap one to select)", style = MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.weight(1f)) {
                items(ui.characteristics) { c ->
                    val flags = listOfNotNull("R".takeIf { c.canRead }, "W".takeIf { c.canWrite }, "N".takeIf { c.canNotify }).joinToString("")
                    Text(
                        "${c.service.short()} / ${c.uuid.short()}  [$flags]" + if (c == selected) "  ◀" else "",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clickable { selected = c }.padding(vertical = 6.dp),
                    )
                }
            }
            selected?.let { c ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (c.canNotify) TextButton(onClick = { link.subscribe(c) }) { Text("Subscribe") }
                    if (c.canRead) TextButton(onClick = { link.read(c) }) { Text("Read") }
                }
                if (c.canWrite) {
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { hex = it },
                        label = { Text("Hex bytes, e.g. 01 02 ff") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(enabled = parseHex(hex) != null, onClick = { parseHex(hex)?.let { link.write(c, it) } }) { Text("Send") }
                }
            }
        } else {
            Text("Watches nearby", style = MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.weight(1f)) {
                items(ui.devices) { d ->
                    Text(
                        "${d.name ?: "(unnamed)"}   ${d.address}   ${d.rssi} dBm",
                        modifier = Modifier.fillMaxWidth().clickable { link.connect(d.address) }.padding(vertical = 10.dp),
                    )
                }
            }
        }

        Text("Log", style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.weight(1f), reverseLayout = true) {
            items(ui.log.asReversed()) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}
