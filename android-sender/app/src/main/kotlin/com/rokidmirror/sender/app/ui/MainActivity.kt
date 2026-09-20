package com.rokidmirror.sender.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rokidmirror.sender.app.MirrorApplication
import com.rokidmirror.sender.app.MirrorService
import com.rokidmirror.sender.capture.CaptureMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = MirrorApplication.from(application)
        val session = container.session
        setContent {
            RokidMirrorTheme {
                var tab by remember { mutableStateOf(0) }
                val scope = rememberCoroutineScope()
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    session.startDiscovery()
                }
                var pendingLabel by remember { mutableStateOf("Phone") }
                val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    val data = result.data
                    if (result.resultCode == Activity.RESULT_OK && data != null) {
                        // Fresh consent per session: the result is forwarded once and never cached.
                        MirrorService.startProjection(this@MainActivity, result.resultCode, data, pendingLabel)
                    } else {
                        session.onCapturePermissionDenied()
                    }
                }
                val requestCapture: (CaptureMode) -> Unit = { mode ->
                    pendingLabel = if (mode == CaptureMode.WHOLE_DISPLAY) "Phone" else "App"
                    scope.launch { captureLauncher.launch(session.prepareCapture(mode)) }
                }
                val prompt by session.pairingPrompt.collectAsState()
                prompt?.let { p -> PairingCodeDialog(receiverName = p.receiverName, onSubmit = session::submitPairingCode, onCancel = session::cancelPairing) }

                Scaffold(bottomBar = {
                    NavigationBar {
                        NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(iconMirror, null) }, label = { Text("Mirror") })
                        NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(iconGlasses, null) }, label = { Text("Receivers") })
                        NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(iconStats, null) }, label = { Text("Diagnostics") })
                        NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Icon(iconMarker, null) }, label = { Text("Marker") })
                    }
                }) { padding ->
                    val m = Modifier.padding(padding)
                    when (tab) {
                        0 -> HomeScreen(session, container.profileRepository, requestCapture, onStartSynthetic = { MirrorService.startSynthetic(this@MainActivity) }, onStop = { MirrorService.stop(this@MainActivity); session.stopStreaming() }, modifier = m)
                        1 -> ReceiversScreen(session, modifier = m)
                        2 -> DiagnosticsScreen(session, onShare = { shareText(it) }, modifier = m)
                        else -> LatencyMarkerScreen(modifier = m)
                    }
                }
            }
        }
    }

    private fun shareText(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Export diagnostics"))
    }
}

private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
    .build()

val iconMirror = icon("mirror", "M4 6h16v10H4zM2 18h20v2H2z")
val iconGlasses = icon("glasses", "M2 10h8v5H2zM14 10h8v5h-8zM10 12h4v1h-4z")
val iconStats = icon("stats", "M4 20V10h3v10zM10 20V4h3v16zM16 20v-7h3v7z")
val iconMarker = icon("marker", "M12 3l3 6h-6zM5 13h14v8H5z")
