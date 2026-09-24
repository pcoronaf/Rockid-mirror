package com.rokidmirror.sender.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rokidmirror.sender.app.session.MirrorSession
import com.rokidmirror.sender.control.SenderState

/**
 * What the wearer is seeing, on the phone.
 *
 * Two sources, because one alone cannot cover every mode. The glasses send back small snapshots
 * of their own display, which carry the camera view and the overlay exactly. A decoded video
 * frame cannot be read back from the decoder's surface, so while mirroring the phone instead
 * draws the region of its own screen that is on the glasses, computed from the viewport it
 * already owns. That map is exact and costs nothing.
 */
@Composable
fun GlassesViewDialog(session: MirrorSession, onDismiss: () -> Unit) {
    val preview by session.previewFrame.collectAsState()
    val state by session.state.collectAsState()
    val info by session.info.collectAsState()
    val viewport by session.viewport.state.collectAsState()
    val mirroring = state is SenderState.Streaming || state is SenderState.Paused

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Glasses view") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val displayWidth = info.capabilities?.display?.width ?: 480
                val displayHeight = info.capabilities?.display?.height ?: 640
                val snapshot = preview

                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(displayWidth.toFloat() / displayHeight)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        snapshot != null && snapshot.isCamera -> Image(
                            bitmap = snapshot.bitmap.asImageBitmap(),
                            contentDescription = "Camera view on the glasses",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        mirroring -> ViewportMap(session, viewport.scale)
                        snapshot != null -> Image(
                            bitmap = snapshot.bitmap.asImageBitmap(),
                            contentDescription = "Glasses overlay",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        else -> Text(
                            "Waiting for the glasses…",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Text(
                    when {
                        snapshot?.isCamera == true -> "Live camera view from the glasses."
                        mirroring -> "The bright area is the part of your screen that is on the glasses. " +
                            "The mirrored picture itself cannot be read back from the decoder, so this is a map of it, not a copy."
                        snapshot != null -> "The glasses' overlay. Nothing is being mirrored."
                        else -> "Connect and the glasses will start sending snapshots."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )

                val stream = info.stream
                if (stream != null) {
                    Text(
                        "%dx%d · %.0f fps · zoom %.1fx · %s".format(
                            stream.width, stream.height, info.encoderStats.fps, viewport.scale, viewport.fitMode.name.lowercase(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Draws the phone screen with the region the glasses are showing picked out. */
@Composable
private fun ViewportMap(session: MirrorSession, @Suppress("UNUSED_PARAMETER") scaleKey: Float) {
    val region = session.visibleRegionOnGlasses()
    val sourceWidth = session.viewport.sourceWidth.coerceAtLeast(1)
    val sourceHeight = session.viewport.sourceHeight.coerceAtLeast(1)
    val outline = Color(0xFF64748B)
    val highlight = Color(0xFF22D3EE)

    Canvas(Modifier.fillMaxWidth().padding(12.dp)) {
        val phoneAspect = sourceWidth.toFloat() / sourceHeight
        val boxHeight = minOf(size.height, size.width / phoneAspect)
        val boxWidth = boxHeight * phoneAspect
        val left = (size.width - boxWidth) / 2f
        val top = (size.height - boxHeight) / 2f

        drawRoundRect(
            color = outline,
            topLeft = Offset(left, top),
            size = Size(boxWidth, boxHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
            style = Stroke(width = 2f),
        )
        drawRect(
            color = highlight.copy(alpha = 0.18f),
            topLeft = Offset(left + region.left * boxWidth, top + region.top * boxHeight),
            size = Size(region.width * boxWidth, region.height * boxHeight),
        )
        drawRect(
            color = highlight,
            topLeft = Offset(left + region.left * boxWidth, top + region.top * boxHeight),
            size = Size(region.width * boxWidth, region.height * boxHeight),
            style = Stroke(width = 3f),
        )
    }
}
