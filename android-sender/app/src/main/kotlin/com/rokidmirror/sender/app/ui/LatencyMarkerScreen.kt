package com.rokidmirror.sender.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Optical end-to-end latency marker (docs/latency-testing.md): a frame counter, a monotonic
 * millisecond clock and a square that flips black/white every frame. Film phone and glasses
 * together with a high-frame-rate camera and subtract the readings.
 */
@Composable
fun LatencyMarkerScreen(modifier: Modifier = Modifier) {
    var frame by remember { mutableLongStateOf(0L) }
    var nowNs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { withFrameNanos { t -> frame++; nowNs = t } } }
    Column(modifier.fillMaxSize().background(Color.Black).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(160.dp).background(if (frame % 2 == 0L) Color.White else Color.Black))
        Text("%06d".format(frame), color = Color.White, fontSize = 96.sp, fontFamily = FontFamily.Monospace)
        Text("%d ms".format(nowNs / 1_000_000), color = Color.White, fontSize = 40.sp, fontFamily = FontFamily.Monospace)
        Text("Mirror this screen; film phone + glasses at 240 fps; latency = frame difference / display fps.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    }
}
