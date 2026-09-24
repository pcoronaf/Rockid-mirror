package com.rokidmirror.sender.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import com.rokidmirror.sender.app.session.MirrorSession
import com.rokidmirror.sender.control.SenderState
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the wearer is seeing, full screen on the phone and zoomable.
 *
 * Two sources, because neither covers every mode. The glasses send back snapshots of their own
 * display at the panel's native width, which carry the camera view and the overlay exactly. A
 * decoded video frame cannot be read back from the decoder's surface, so while mirroring the
 * phone draws the region of its own screen that is on the glasses instead, computed from the
 * viewport it already owns.
 */
@Composable
fun GlassesViewDialog(session: MirrorSession, onDismiss: () -> Unit) {
    val preview by session.previewFrame.collectAsState()
    val state by session.state.collectAsState()
    val info by session.info.collectAsState()
    val viewport by session.viewport.state.collectAsState()
    val mirroring = state is SenderState.Streaming || state is SenderState.Paused

    // Mirroring cannot be photographed by the glasses, so the phone decodes its own stream and
    // frames it exactly as the glasses do. The map stays available for seeing where you are.
    var showMap by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var frameSize by remember { mutableStateOf(IntSize.Zero) }

    fun clampOffset(candidate: Offset, currentScale: Float): Offset {
        // At 1x there is nowhere to go; beyond it, panning stops at the picture's edges.
        val maxX = (frameSize.width * (currentScale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (frameSize.height * (currentScale - 1f) / 2f).coerceAtLeast(0f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    fun setScale(next: Float) {
        val clamped = next.coerceIn(MIN_ZOOM, MAX_ZOOM)
        scale = clamped
        offset = clampOffset(offset, clamped)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val displayWidth = info.capabilities?.display?.width ?: 480
                val displayHeight = info.capabilities?.display?.height ?: 640
                val snapshot = preview
                val showingCamera = snapshot != null && snapshot.isCamera

                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black)
                        .onSizeChanged { frameSize = it }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (abs(zoom - 1f) > 0.001f) setScale(scale * zoom)
                                if (scale > 1f) offset = clampOffset(offset + pan, scale)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else setScale(2.5f)
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val zoomed = Modifier.graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
                    when {
                        showingCamera || (snapshot != null && !mirroring) -> Image(
                            bitmap = snapshot!!.bitmap.asImageBitmap(),
                            contentDescription = "What the glasses are showing",
                            contentScale = ContentScale.Fit,
                            modifier = zoomed
                                .fillMaxWidth()
                                .aspectRatio(displayWidth.toFloat() / displayHeight),
                        )
                        mirroring && !showMap -> Box(
                            zoomed.fillMaxWidth().aspectRatio(displayWidth.toFloat() / displayHeight),
                        ) { MirroredPicture(session, displayWidth, displayHeight) }
                        mirroring -> Box(
                            zoomed.fillMaxWidth().aspectRatio(displayWidth.toFloat() / displayHeight),
                        ) { ViewportMap(session) }
                        else -> Text(
                            "Waiting for the glasses…",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                if (mirroring) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !showMap, onClick = { showMap = false }, label = { Text("Picture") })
                        FilterChip(selected = showMap, onClick = { showMap = true }, label = { Text("Map") })
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("%.1fx".format(scale), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { setScale(scale / 1.5f) }, enabled = scale > MIN_ZOOM) { Text("−") }
                    OutlinedButton(onClick = { setScale(scale * 1.5f) }, enabled = scale < MAX_ZOOM) { Text("+") }
                    OutlinedButton(onClick = { scale = 1f; offset = Offset.Zero }, enabled = scale != 1f) { Text("Fit") }
                    Box(Modifier.weight(1f))
                    Button(onClick = onDismiss) { Text("Close") }
                }

                Text(
                    when {
                        showingCamera -> "Live camera view from the glasses. Pinch or use + to zoom, drag to move, double tap to reset."
                        mirroring && !showMap -> "The mirrored picture, framed exactly as the glasses frame it. " +
                            "Decoded here on the phone, because a decoded frame cannot be read back out of the glasses."
                        mirroring -> "The bright area is the part of your screen that is on the glasses."
                        snapshot != null -> "The glasses' overlay. Nothing is being mirrored."
                        else -> "Connect and the glasses will start sending snapshots."
                    },
                    color = Color(0xFFCBD5E1),
                    style = MaterialTheme.typography.bodySmall,
                )

                val stream = info.stream
                if (stream != null) {
                    Text(
                        "%dx%d · %.0f fps · viewport %.1fx %s".format(
                            stream.width, stream.height, info.encoderStats.fps, viewport.scale, viewport.fitMode.name.lowercase(),
                        ),
                        color = Color(0xFF94A3B8),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * The mirrored picture, placed exactly as the glasses place it.
 *
 * The same [ViewportMath] the receiver uses decides where the frame sits, so zoom and pan on the
 * glasses are reproduced here rather than approximated.
 */
@Composable
private fun MirroredPicture(session: MirrorSession, displayWidth: Int, displayHeight: Int) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var failed by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val placement = session.glassesPlacement()

    DisposableEffect(Unit) { onDispose { session.stopLocalPreview() } }

    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { boxSize = it }, contentAlignment = Alignment.Center) {
        if (boxSize.width > 0) {
            val k = boxSize.width.toFloat() / displayWidth
            val frameWidth = with(density) { (placement.width * k).toDp() }
            val frameHeight = with(density) { (placement.height * k).toDp() }
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                failed = !session.startLocalPreview(Surface(texture))
                            }
                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                session.stopLocalPreview()
                                return true
                            }
                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier
                    .offset { IntOffset((placement.left * k).roundToInt(), (placement.top * k).roundToInt()) }
                    .size(frameWidth, frameHeight),
            )
        }
        if (failed) {
            Text(
                "Could not start the local decoder. Switch to Map.",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 8f

/** Draws the phone screen with the region the glasses are showing picked out. */
@Composable
private fun ViewportMap(session: MirrorSession) {
    val region = session.visibleRegionOnGlasses()
    val sourceWidth = session.viewport.sourceWidth.coerceAtLeast(1)
    val sourceHeight = session.viewport.sourceHeight.coerceAtLeast(1)
    val outline = Color(0xFF94A3B8)
    val highlight = Color(0xFF22D3EE)

    Canvas(Modifier.fillMaxSize().padding(8.dp)) {
        val phoneAspect = sourceWidth.toFloat() / sourceHeight
        val boxHeight = minOf(size.height, size.width / phoneAspect)
        val boxWidth = boxHeight * phoneAspect
        val left = (size.width - boxWidth) / 2f
        val top = (size.height - boxHeight) / 2f

        drawRoundRect(
            color = outline,
            topLeft = Offset(left, top),
            size = Size(boxWidth, boxHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
            style = Stroke(width = 2f),
        )
        drawRect(
            color = highlight.copy(alpha = 0.2f),
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
