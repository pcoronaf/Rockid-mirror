package com.rokidmirror.sender.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rokidmirror.protocol.Protocol
import com.rokidmirror.sender.app.session.MirrorSession

/** Discovered receivers (DNS-SD) plus the manual-IP diagnostic fallback and "Forget receiver". */
@Composable
fun ReceiversScreen(session: MirrorSession, modifier: Modifier = Modifier) {
    val receivers by session.receivers.collectAsState()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(Protocol.DEFAULT_CONTROL_PORT.toString()) }
    LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Receivers", style = MaterialTheme.typography.headlineMedium) }
        if (receivers.isEmpty()) item { Text("Searching for Rokid Glasses on this network… Make sure the glasses run the receiver app and share the phone's Wi-Fi or hotspot.") }
        items(receivers, key = { it.id }) { r ->
            val paired = session.isPaired(r.id)
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium)
                        Text("${r.host}:${r.port}  ${if (r.displayWidth > 0) "${r.displayWidth}x${r.displayHeight}" else ""}  ${if (paired) "· paired" else "· not paired"}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (paired) TextButton(onClick = { session.forgetReceiver(r.id) }) { Text("Forget") }
                    Button(onClick = { session.connect(r) }) { Text("Connect") }
                }
            }
        }
        item {
            Text("Manual connection (diagnostics)", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(host, { host = it }, label = { Text("Receiver IP") }, singleLine = true, modifier = Modifier.weight(0.6f))
                OutlinedTextField(port, { port = it.filter { c -> c.isDigit() } }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(0.4f))
            }
            Button(onClick = { session.connectManual(host.trim(), port.toIntOrNull() ?: Protocol.DEFAULT_CONTROL_PORT) }, enabled = host.isNotBlank()) { Text("Connect to IP") }
        }
    }
}
