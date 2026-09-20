package com.rokidmirror.sender.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.rokidmirror.protocol.StreamPreset
import com.rokidmirror.protocol.viewport.FitMode
import com.rokidmirror.sender.app.session.MirrorSession
import com.rokidmirror.sender.capture.CaptureMode
import com.rokidmirror.sender.control.ProfileRepository
import com.rokidmirror.sender.control.SenderState
import com.rokidmirror.sender.control.ViewProfile

/** Primary phone screen from the specification mock-up; keeps working as a remote control while another app is captured. */
@Composable
fun HomeScreen(
    session: MirrorSession,
    profiles: ProfileRepository,
    onRequestCapture: (CaptureMode) -> Unit,
    onStartSynthetic: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by session.state.collectAsState()
    val info by session.info.collectAsState()
    val viewport by session.viewport.state.collectAsState()
    val profileList by profiles.profiles.collectAsState(initial = ViewProfile.defaults)
    val active by session.activeProfile.collectAsState()

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Rokid Mirror", style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusRow("Receiver", info.receiverName ?: "none", statusLabel(state))
                StatusRow("Network", info.networkDescription, "")
                StatusRow("Latency", info.estimatedLatencyMs?.let { "%.0f ms".format(it) } ?: (if (info.transport.rttMs >= 0) "rtt %.0f ms".format(info.transport.rttMs) else "-"), "")
                val s = info.stream
                StatusRow("Stream", if (s != null) "${s.width}x${s.height} / %.0f fps / %.1f Mbps".format(info.encoderStats.fps, info.encoderStats.bitrateBps / 1e6) else "-", "")
                if (state is SenderState.Error) {
                    val e = state as SenderState.Error
                    Text("${e.code.userMessage} ${e.details?.let { "($it)" } ?: ""}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        val connected = state is SenderState.Ready || state is SenderState.Streaming || state is SenderState.Paused || state is SenderState.Recovering
        val streaming = state is SenderState.Streaming || state is SenderState.Paused || state is SenderState.Recovering && (state as SenderState.Recovering).wasStreaming
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onRequestCapture(CaptureMode.WHOLE_DISPLAY) }, enabled = state is SenderState.Ready, modifier = Modifier.weight(1f)) { Text("Mirror phone") }
            Button(onClick = { onRequestCapture(CaptureMode.USER_CHOICE) }, enabled = state is SenderState.Ready, modifier = Modifier.weight(1f)) { Text("Select app") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onStartSynthetic, enabled = state is SenderState.Ready, modifier = Modifier.weight(1f)) { Text("Test pattern") }
            if (connected) OutlinedButton(onClick = session::disconnect, modifier = Modifier.weight(1f)) { Text("Disconnect") }
        }

        Text("Quality", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StreamPreset.entries.forEach { p ->
                FilterChip(selected = info.preset == p, onClick = { session.setPreset(p) }, label = { Text(p.wireName.replaceFirstChar { it.uppercase() }) })
            }
        }

        Text("View", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = viewport.fitMode == FitMode.FIT, onClick = { session.viewport.setFitMode(FitMode.FIT) }, label = { Text("Fit") })
            FilterChip(selected = viewport.fitMode == FitMode.FILL, onClick = { session.viewport.setFitMode(FitMode.FILL) }, label = { Text("Fill") })
            FilterChip(selected = viewport.fitMode == FitMode.ACTUAL, onClick = { session.viewport.setFitMode(FitMode.ACTUAL) }, label = { Text("100%") })
            FilterChip(selected = viewport.fitMode == FitMode.CUSTOM, onClick = { session.viewport.setFitMode(FitMode.CUSTOM) }, label = { Text("Zoom") })
        }
        LabeledSlider("Zoom", session.viewport.zoomMultiplier(), 0.5f..8f) { session.viewport.setZoom(it) }
        LabeledSlider("Horizontal", viewport.centerX, 0f..1f) { session.viewport.setCenter(it, viewport.centerY) }
        LabeledSlider("Vertical", viewport.centerY, 0f..1f) { session.viewport.setCenter(viewport.centerX, it) }

        ControlPad(
            onZoom = { session.viewport.zoomBy(it) },
            onPan = { dx, dy -> session.viewport.panByViewportFraction(dx, dy) },
            onReset = { session.viewport.resetToFit() },
            onToggleReadable = { if (viewport.fitMode == FitMode.FIT) session.viewport.setZoom(2f) else session.viewport.resetToFit() },
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var open by remember { mutableStateOf(false) }
            Text("Profile:")
            Box {
                TextButton(onClick = { open = true }) { Text("${active.name} ▾") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    profileList.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { open = false; session.applyProfile(p) }) }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { session.saveCurrentViewportToProfile(active) }) { Text("Save") }
        }

        Button(onClick = onStop, enabled = streaming, modifier = Modifier.fillMaxWidth(), colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Stop") }
    }
}

@Composable
private fun StatusRow(label: String, value: String, trailing: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(0.3f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        Text(value, Modifier.weight(0.5f), style = MaterialTheme.typography.bodyMedium)
        Text(trailing, Modifier.weight(0.3f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.3f), style = MaterialTheme.typography.bodyMedium)
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(0.7f))
    }
}

/**
 * Gesture pad (spec "Phone gestures"): pinch = zoom, one-finger drag = pan, double tap = Fit,
 * two-finger tap = toggle Fit / readable zoom. No touch is injected into other apps.
 */
@Composable
fun ControlPad(onZoom: (Float) -> Unit, onPan: (Float, Float) -> Unit, onReset: () -> Unit, onToggleReadable: () -> Unit) {
    val density = LocalDensity.current
    val heightPx = with(density) { 180.dp.toPx() }
    Box(
        Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f) onZoom(zoom)
                    if (pan.x != 0f || pan.y != 0f) onPan(-pan.x / size.width, -pan.y / heightPx)
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }, onLongPress = { onToggleReadable() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text("Control surface\ndrag: pan · pinch: zoom · double tap: Fit · long press: toggle readable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}

private fun statusLabel(state: SenderState): String = when (state) {
    SenderState.Idle -> "Idle"
    SenderState.Searching -> "Searching…"
    is SenderState.ReceiverFound -> "${state.count} found"
    is SenderState.Pairing -> if (state.codeRequired) "Enter code" else "Connecting…"
    is SenderState.Ready -> "Connected"
    is SenderState.AwaitingCapturePermission -> "Waiting for consent"
    is SenderState.Streaming -> "Streaming"
    is SenderState.Paused -> "Paused"
    is SenderState.Recovering -> "Reconnecting (${state.attempt})"
    is SenderState.Error -> "Error"
}
