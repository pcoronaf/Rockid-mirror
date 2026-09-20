package com.rokidmirror.sender.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rokidmirror.sender.app.session.MirrorSession

/** Diagnostics screen and sanitized export (spec "Logging and diagnostics"). */
@Composable
fun DiagnosticsScreen(session: MirrorSession, onShare: (String) -> Unit, modifier: Modifier = Modifier) {
    val info by session.info.collectAsState()
    val report = session.diagnosticsReport()
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Diagnostics", style = MaterialTheme.typography.headlineMedium)
        Text(report.toText(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        val rs = info.transport.receiverStats
        if (rs != null) {
            Text("Receiver pipeline", style = MaterialTheme.typography.titleMedium)
            Text("packets ${rs.packetsReceived} lost ${rs.packetsLost} · frames ${rs.framesDelivered} dropped ${rs.framesDropped} decoded ${rs.framesDecoded} · lag ${rs.decodeLagFrames} · %.1f fps".format(rs.fps), style = MaterialTheme.typography.bodySmall)
        }
        Text("Encoder", style = MaterialTheme.typography.titleMedium)
        Text("frames ${info.encoderStats.frames} key ${info.encoderStats.keyframes} restarts ${info.encoderStats.restarts} · last pts ${info.encoderStats.lastPtsUs} us · last size ${info.encoderStats.lastSizeBytes} B · encode %.1f ms".format(info.encoderStats.avgEncodeMs), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onShare(report.toJson()) }) { Text("Export JSON") }
            OutlinedButton(onClick = { onShare(report.toText()) }) { Text("Export text") }
            OutlinedButton(onClick = { session.requestKeyframe() }) { Text("Keyframe") }
        }
        Text("Events", style = MaterialTheme.typography.titleMedium)
        info.recentEvents.reversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth()) }
    }
}
