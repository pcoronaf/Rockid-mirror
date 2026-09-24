package com.rokidmirror.sender.app.session

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import com.rokidmirror.protocol.Protocol
import com.rokidmirror.protocol.StreamNegotiator
import com.rokidmirror.protocol.StreamParams
import com.rokidmirror.protocol.StreamPreset
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MessageType
import com.rokidmirror.protocol.control.MirrorException
import com.rokidmirror.protocol.control.Payloads
import com.rokidmirror.protocol.viewport.ViewportState
import com.rokidmirror.sender.app.AppContainer
import com.rokidmirror.sender.app.GlassesWorkspace
import com.rokidmirror.sender.app.PointerService
import com.rokidmirror.sender.capture.CaptureEvent
import com.rokidmirror.sender.capture.CaptureGeometry
import com.rokidmirror.sender.capture.CaptureGeometryPlanner
import com.rokidmirror.sender.capture.CaptureMode
import com.rokidmirror.sender.capture.ExtendedDisplayController
import com.rokidmirror.sender.capture.LaunchableApp
import com.rokidmirror.sender.capture.MediaProjectionCaptureController
import com.rokidmirror.sender.control.SenderEvent
import com.rokidmirror.sender.control.SenderState
import com.rokidmirror.sender.control.SenderStateMachine
import com.rokidmirror.sender.control.ViewProfile
import com.rokidmirror.sender.control.PointerController
import com.rokidmirror.sender.control.ViewportController
import com.rokidmirror.sender.discovery.DiscoveredReceiver
import com.rokidmirror.sender.encoder.EncoderCapabilities
import com.rokidmirror.sender.encoder.EncoderConfig
import com.rokidmirror.sender.encoder.MediaCodecVideoEncoder
import com.rokidmirror.sender.telemetry.AdaptiveAction
import com.rokidmirror.sender.telemetry.AdaptiveController
import com.rokidmirror.sender.telemetry.AdaptiveInputs
import com.rokidmirror.sender.telemetry.DiagnosticsReport
import com.rokidmirror.sender.telemetry.LatencyTracker
import com.rokidmirror.sender.telemetry.MirrorLog
import com.rokidmirror.sender.transport.LanStreamTransport
import com.rokidmirror.sender.transport.ReceiverEndpoint
import com.rokidmirror.sender.transport.TransportEvent
import com.rokidmirror.sender.transport.TransportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64

/**
 * Session coordinator: the only place where capture, encoder, transport, adaptation and the UI
 * state machine meet. Shared by the foreground service (streaming) and the activity (controls).
 */
class MirrorSession(private val context: Context, private val container: AppContainer) {
    private companion object {
        const val TAG = "Session"
        const val RECONNECT_WINDOW_MS = 60_000L
        const val VIEWPORT_SEND_INTERVAL_MS = 40L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateMutex = Mutex()
    /** One connection attempt at a time, whoever asked for it. */
    private val connectMutex = Mutex()

    private val _state = MutableStateFlow<SenderState>(SenderState.Idle)
    val state: StateFlow<SenderState> = _state.asStateFlow()
    private val _info = MutableStateFlow(SessionInfo())
    val info: StateFlow<SessionInfo> = _info.asStateFlow()
    private val _pairingPrompt = MutableStateFlow<PairingPrompt?>(null)
    val pairingPrompt: StateFlow<PairingPrompt?> = _pairingPrompt.asStateFlow()
    private val _receivers = MutableStateFlow<List<DiscoveredReceiver>>(emptyList())
    val receivers: StateFlow<List<DiscoveredReceiver>> = _receivers.asStateFlow()
    private val _activeProfile = MutableStateFlow(ViewProfile.defaults.first())
    val activeProfile: StateFlow<ViewProfile> = _activeProfile.asStateFlow()

    val viewport = ViewportController { scheduleViewportSend(it) }

    /** Mouse mode: the control pad drives a cursor over the mirrored frame instead of the viewport. */
    private val _mouseMode = MutableStateFlow(false)
    val mouseMode: StateFlow<Boolean> = _mouseMode.asStateFlow()
    val pointer = PointerController()
    private val _pointerPosition = MutableStateFlow(0.5f to 0.5f)
    val pointerPosition: StateFlow<Pair<Float, Float>> = _pointerPosition.asStateFlow()

    private val transport = LanStreamTransport(container.identity)
    private val capture = MediaProjectionCaptureController(context)
    private val latency = LatencyTracker()
    private val encoder = MediaCodecVideoEncoder(onAccessUnit = { unit ->
        val now = System.nanoTime()
        transport.sendVideo(unit)
        localPreview?.submit(unit)
        latency.onDispatched(unit.captureTimestampNs, unit.encodeTimestampNs, now, unit.size, unit.isKeyframe)
    })
    private var adaptive: AdaptiveController? = null
    private var synthetic: SyntheticSource? = null
    private val extended = ExtendedDisplayController(context)

    private var endpoint: ReceiverEndpoint? = null
    private var pendingCode: CompletableDeferred<String>? = null
    private var discoveryJob: Job? = null
    private var streamingJob: Job? = null
    private var reconnectJob: Job? = null
    private var viewportJob: Job? = null
    private var pendingViewport: ViewportState? = null
    private var localPreview: LocalPreviewDecoder? = null
    private var pointerJob: Job? = null
    private var pendingPointerSend = false
    /** Screen geometry captured at stream start; injection needs source == whole display. */
    private var captureDisplayGeometry: CaptureGeometry? = null
    private var userDisconnect = false

    private var encoderConfig: EncoderConfig? = null
    private var streamParams: StreamParams? = null
    private var sourceGeometry: CaptureGeometry? = null
    private var negotiatedLongEdge = 0
    private var resolutionStep = 0
    private var lastFormatSent: Payloads.StreamFormat? = null
    /** Kept after a stream ends so the diagnostics export still describes the last session. */
    private var lastStreamParams: StreamParams? = null
    private var isSynthetic = false
    private var syntheticPattern = SyntheticPattern.TEST_PATTERN
    private var isExtended = false

    init {
        scope.launch { transport.events.collect { onTransportEvent(it) } }
        scope.launch { capture.events.collect { onCaptureEvent(it) } }
        scope.launch { encoder.format.filterNotNull().collect { sendStreamFormatIfChanged() } }
        scope.launch { encoder.stats.collect { s -> _info.update { it.copy(encoderStats = s) } } }
        scope.launch { transport.statistics.collect { s -> _info.update { it.copy(transport = s) } } }
        scope.launch {
            container.profileRepository.activeProfileId.collectLatest { id ->
                container.profileRepository.profiles.first().firstOrNull { it.id == id }?.let { _activeProfile.value = it }
            }
        }
        scope.launch {
            container.profileRepository.preset.first()?.let { StreamPreset.fromWire(it) }?.let { p -> _info.update { it.copy(preset = p) } }
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                _info.update { it.copy(latency = latency.snapshot(System.nanoTime()), networkDescription = describeNetwork()) }
                if (_state.value is SenderState.Streaming) adapt()
            }
        }
    }

    // ---- discovery -------------------------------------------------------------------------

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        dispatch(SenderEvent.StartSearch)
        discoveryJob = scope.launch {
            container.discovery.discover().collect { list ->
                _receivers.value = list
                dispatch(SenderEvent.ReceiversChanged(list.size))
            }
        }
    }

    fun stopDiscovery() { discoveryJob?.cancel(); discoveryJob = null }

    fun isPaired(receiverId: String): Boolean = container.credentialStore.load(receiverId) != null

    fun forgetReceiver(receiverId: String) { container.credentialStore.forget(receiverId); event("Forgot receiver $receiverId") }

    // ---- connection ------------------------------------------------------------------------

    fun connect(receiver: DiscoveredReceiver) {
        val ep = ReceiverEndpoint(receiver.id, receiver.name, receiver.host, receiver.port)
        // An automatic retry must not race a deliberate connect.
        reconnectJob?.cancel()
        endpoint = ep
        userDisconnect = false
        _info.update { it.copy(receiverName = ep.name, receiverId = ep.id) }
        dispatch(SenderEvent.ConnectRequested(ep.name))
        scope.launch {
            try {
                connectOnce(ep)
                dispatch(SenderEvent.Connected)
            } catch (e: MirrorException) {
                MirrorLog.w(TAG, "connect_failed", "code" to e.code, "details" to e.details)
                dispatch(SenderEvent.Failed(e.code, e.details))
            }
        }
    }

    fun connectManual(host: String, port: Int = Protocol.DEFAULT_CONTROL_PORT) =
        connect(DiscoveredReceiver(id = "manual:$host", name = host, host = host, port = port, manual = true))

    private suspend fun connectOnce(ep: ReceiverEndpoint) = connectMutex.withLock {
        val credential = container.credentialStore.load(ep.id)
        transport.connect(ep, credential) {
            val deferred = CompletableDeferred<String>().also { pendingCode = it }
            _pairingPrompt.value = PairingPrompt(ep.name)
            dispatch(SenderEvent.PairingCodeRequired)
            try { deferred.await() } finally { _pairingPrompt.value = null; pendingCode = null }
        }
        // Capabilities arrive right after the handshake; wait briefly so Ready has geometry.
        withTimeoutOrNull(3000) { transport.state.first { it is TransportState.Connected && it.capabilities != null } }
        event("Connected to ${ep.name}")
    }

    fun submitPairingCode(code: String) {
        dispatch(SenderEvent.PairingCodeSubmitted)
        pendingCode?.complete(code)
    }

    fun cancelPairing() { pendingCode?.completeExceptionally(MirrorException(ErrorCode.PAIRING_FAILED, "cancelled by user")) }

    fun disconnect() {
        userDisconnect = true
        scope.launch {
            stopStreamingInternal("disconnect")
            reconnectJob?.cancel()
            transport.close("user disconnected")
            dispatch(SenderEvent.Disconnected)
            _info.update { SessionInfo(preset = it.preset) }
        }
    }

    // ---- capture / streaming ---------------------------------------------------------------

    suspend fun prepareCapture(mode: CaptureMode): Intent {
        dispatch(SenderEvent.CapturePermissionRequested)
        return capture.prepareCapture(mode).intent
    }

    fun onCapturePermissionDenied() = dispatch(SenderEvent.CapturePermissionDenied)

    /** Called by [com.rokidmirror.sender.app.MirrorService] once it runs in the foreground. */
    suspend fun startProjectionStreaming(resultCode: Int, data: Intent, sourceLabel: String) {
        stateMutex.withLock {
            try {
                // Mirroring replaces any extended display or generated source.
                if (isExtended) { runCatching { extended.stop() }; isExtended = false }
                PointerService.instance?.targetDisplayId = android.view.Display.DEFAULT_DISPLAY
                isSynthetic = false
                val source = displayGeometry()
                capture.setSourceGeometry(source)
                val surface = startEncoder(source)
                capture.start(resultCode, data, surface, CaptureGeometry(encoderConfig!!.width, encoderConfig!!.height, source.densityDpi))
                beginStream(sourceLabel, source)
            } catch (e: MirrorException) {
                MirrorLog.e(TAG, "start_failed", e)
                runCatching { encoder.stop() }
                dispatch(SenderEvent.Failed(e.code, e.details))
                throw e
            }
        }
    }

    /**
     * Extended-screen mode: the glasses become a second display with their own apps instead of
     * a copy of the phone. Needs no capture consent, because the display only ever shows content
     * this app launches onto it.
     */
    suspend fun startExtendedStreaming() {
        stateMutex.withLock {
            try {
                isSynthetic = false
                isExtended = true
                val caps = (transport.state.value as? TransportState.Connected)?.capabilities
                    ?: throw MirrorException(ErrorCode.RECEIVER_NOT_FOUND, "not connected to a receiver")
                // Render at the glasses' own resolution: 1:1 pixels, sharp text, least bitrate.
                val width = StreamNegotiator.align(caps.display.width.takeIf { it > 0 } ?: 480)
                val height = StreamNegotiator.align(caps.display.height.takeIf { it > 0 } ?: 640)
                val dpi = caps.display.densityDpi.takeIf { it > 0 } ?: 240
                val source = CaptureGeometry(width, height, dpi)
                val surface = startEncoder(source)
                extended.start(width, height, dpi, surface)
                PointerService.instance?.targetDisplayId = extended.displayId
                _extendedDisplayId.value = extended.displayId
                beginStream("Extended screen", source)
                event("Extended display ${width}x$height @${dpi}dpi (id ${extended.displayId})")
            } catch (e: MirrorException) {
                isExtended = false
                runCatching { extended.stop() }
                runCatching { encoder.stop() }
                dispatch(SenderEvent.Failed(e.code, e.details))
                throw e
            }
        }
    }

    /** Apps with a launcher entry, for the extended-screen picker. */
    fun launchableApps(): List<LaunchableApp> = runCatching { extended.launchableApps() }.getOrDefault(emptyList())

    private val _extendedDisplayId = MutableStateFlow(android.view.Display.INVALID_DISPLAY)
    /** Display the glasses workspace must be shown on, or INVALID_DISPLAY when idle. */
    val extendedDisplayId: StateFlow<Int> = _extendedDisplayId.asStateFlow()
    val isExtendedActive: Boolean get() = extended.isActive

    /**
     * The workspace is a window, not an activity: Android refuses activity launches onto a
     * virtual display an ordinary app created, including the app's own. The UI owns the window
     * and hands it here so control commands and the pointer can reach it.
     */
    fun attachWorkspace(workspace: GlassesWorkspace) {
        this.workspace = workspace
        event("Glasses workspace ready (${workspace.host})")
    }

    fun onWorkspaceUnavailable(reason: String) {
        workspace = null
        event("The glasses display cannot show a window: $reason")
    }

    fun detachWorkspace() {
        workspace?.let { runCatching { it.dismiss() } }
        workspace = null
    }

    private var workspace: GlassesWorkspace? = null

    fun launchOnExtendedDisplay(app: LaunchableApp) {
        val result = extended.launch(app.packageName)
        result.onSuccess { event("Launched ${app.label} on the glasses") }
            .onFailure { event("Could not launch ${app.label}: ${(it as? MirrorException)?.details ?: it.message}") }
    }

    /** Opens an address in the glasses workspace. */
    fun openOnGlasses(query: String) {
        val workspace = workspace
        if (workspace == null) { event("The glasses workspace is not running"); return }
        workspace.open(query)
        event("Opened on the glasses")
    }

    fun workspaceBack() = workspace?.back()
    fun workspaceHome() = workspace?.home()
    fun workspaceReload() = workspace?.reload()
    val workspaceRunning: Boolean get() = workspace != null

    /** Development path: streams a generated picture (no MediaProjection consent needed). */
    suspend fun startSyntheticStreaming(pattern: SyntheticPattern = SyntheticPattern.TEST_PATTERN) {
        stateMutex.withLock {
            try {
                isSynthetic = true
                syntheticPattern = pattern
                val source = CaptureGeometry(720, 1280, 320)
                val surface = startEncoder(source)
                synthetic = SyntheticSource(surface, encoderConfig!!.width, encoderConfig!!.height, encoderConfig!!.fps, pattern).also { it.start() }
                beginStream(pattern.sourceName, source)
            } catch (e: MirrorException) {
                runCatching { encoder.stop() }
                dispatch(SenderEvent.Failed(e.code, e.details))
                throw e
            }
        }
    }

    private suspend fun startEncoder(source: CaptureGeometry): Surface {
        val caps = (transport.state.value as? TransportState.Connected)?.capabilities
            ?: throw MirrorException(ErrorCode.RECEIVER_NOT_FOUND, "not connected to a receiver")
        val probe = EncoderCapabilities.probe() ?: throw MirrorException(ErrorCode.ENCODER_UNAVAILABLE, "no H.264 encoder")
        val params = StreamNegotiator.negotiate(_info.value.preset, source.width, source.height, caps, probe.limits)
        streamParams = params
        lastStreamParams = params
        sourceGeometry = source
        captureDisplayGeometry = displayGeometry()
        pointer.sourceWidth = source.width
        pointer.sourceHeight = source.height
        negotiatedLongEdge = maxOf(params.width, params.height)
        resolutionStep = 0
        val cfg = EncoderConfig(width = params.width, height = params.height, fps = params.fps, bitrate = params.bitrate)
        encoderConfig = cfg
        adaptive = AdaptiveController(params.preset.minBitrate, params.preset.maxBitrate, params.fps).apply { reset(params.bitrate, params.fps) }
        latency.reset()
        lastFormatSent = null
        val surface = encoder.start(cfg)
        _info.update { it.copy(stream = params, currentBitrate = params.bitrate, encoderName = probe.encoderName, encoderLowLatency = probe.supportsLowLatency, sourceWidth = source.width, sourceHeight = source.height) }
        return surface
    }

    private suspend fun beginStream(sourceLabel: String, source: CaptureGeometry) {
        val params = streamParams!!
        val caps = _info.value.capabilities
        viewport.setGeometry(source.width, source.height, caps?.display?.width ?: 480, caps?.display?.height ?: 640, recenter = true)
        transport.send(MessageType.STREAM_START, Payloads.StreamStart.serializer(), Payloads.StreamStart(params.codec.wireName, params.width, params.height, params.fps, params.bitrate, params.preset.wireName, sourceLabel, transport.sessionShortId))
        sendStreamFormatIfChanged(force = true)
        transport.send(MessageType.PROFILE_SET, Payloads.ProfileSet.serializer(), _activeProfile.value.toPayload())
        sendViewportNow(viewport.state.value)
        encoder.requestKeyFrame()
        _info.update { it.copy(sourceName = sourceLabel, sessionStartedAtNs = System.nanoTime()) }
        dispatch(SenderEvent.StreamStarted(sourceLabel))
        event("Streaming $sourceLabel ${params.width}x${params.height}@${params.fps} ${params.bitrate / 1000} kbps")
    }

    fun stopStreaming(reason: String = "user") { scope.launch { stopStreamingInternal(reason); dispatch(SenderEvent.StopRequested) } }

    private suspend fun stopStreamingInternal(reason: String) {
        stateMutex.withLock {
            if (encoderConfig == null && synthetic == null) return
            synthetic?.stop(); synthetic = null
            if (isExtended) {
                detachWorkspace()
                _extendedDisplayId.value = android.view.Display.INVALID_DISPLAY
                runCatching { extended.stop() }
                PointerService.instance?.targetDisplayId = android.view.Display.DEFAULT_DISPLAY
                isExtended = false
            }
            runCatching { capture.stop() }
            runCatching { encoder.stop() }
            if (transport.state.value is TransportState.Connected) runCatching { transport.send(MessageType.STREAM_STOP, Payloads.StreamStop.serializer(), Payloads.StreamStop(reason)) }
            encoderConfig = null; streamParams = null; adaptive = null
            if (_mouseMode.value) { _mouseMode.value = false; runCatching { sendPointerNow() } }
            _info.update { it.copy(stream = null, sourceName = "") }
            event("Stream stopped ($reason)")
        }
    }

    // ---- events ---------------------------------------------------------------------------

    private fun onCaptureEvent(e: CaptureEvent) {
        when (e) {
            is CaptureEvent.Stopped -> scope.launch {
                // Android terminated the projection: stop immediately (spec security requirement).
                stopStreamingInternal(e.reason)
                dispatch(SenderEvent.CaptureStopped(e.reason))
            }
            is CaptureEvent.ContentResized -> scope.launch { onContentResized(e.width, e.height) }
            is CaptureEvent.VisibilityChanged -> dispatch(SenderEvent.ContentHidden(!e.visible))
        }
    }

    private suspend fun onContentResized(width: Int, height: Int) = stateMutex.withLock {
        val cfg = encoderConfig ?: return@withLock
        val source = sourceGeometry ?: return@withLock
        if (width <= 0 || height <= 0) return@withLock
        // Resizing the VirtualDisplay makes Android report the content size again. Without this
        // guard every encoder restart triggered another resize round and reset the viewport.
        if (width == source.width && height == source.height) {
            MirrorLog.d(TAG, "content_resize_noop", "w" to width, "h" to height)
            return@withLock
        }
        val newSource = source.copy(width = width, height = height)
        sourceGeometry = newSource
        capture.setSourceGeometry(newSource)
        val (w, h) = CaptureGeometryPlanner.encodedSize(width, height, CaptureGeometryPlanner.applyResolutionStep(negotiatedLongEdge, resolutionStep))
        if (w != cfg.width || h != cfg.height) {
            restartEncoder(cfg.copy(width = w, height = h))
        }
        val caps = _info.value.capabilities
        viewport.setGeometry(width, height, caps?.display?.width ?: 480, caps?.display?.height ?: 640, recenter = true)
        sendStreamFormatIfChanged(force = true)
        sendViewportNow(viewport.state.value)
        _info.update { it.copy(sourceWidth = width, sourceHeight = height) }
        pointer.sourceWidth = width
        pointer.sourceHeight = height
        event("Content resized to ${width}x$height")
    }

    /** Restart the encoder with a new geometry and hand the new surface to the existing VirtualDisplay. */
    private suspend fun restartEncoder(newConfig: EncoderConfig) {
        val surface = encoder.reconfigure(newConfig)
        encoderConfig = newConfig
        if (isExtended) {
            extended.resize(newConfig.width, newConfig.height, sourceGeometry?.densityDpi ?: 240)
            extended.setSurface(surface)
        } else if (!isSynthetic) {
            capture.resize(newConfig.width, newConfig.height, sourceGeometry?.densityDpi ?: 320)
            capture.setSurface(surface)
        } else {
            synthetic?.stop()
            synthetic = SyntheticSource(surface, newConfig.width, newConfig.height, newConfig.fps, syntheticPattern).also { it.start() }
        }
        streamParams = streamParams?.copy(width = newConfig.width, height = newConfig.height, fps = newConfig.fps, bitrate = newConfig.bitrate)
        _info.update { it.copy(stream = streamParams, currentBitrate = newConfig.bitrate) }
    }

    private fun onTransportEvent(e: TransportEvent) {
        when (e) {
            is TransportEvent.CapabilitiesReceived -> {
                _info.update { it.copy(capabilities = e.capabilities) }
                viewport.setGeometry(viewport.sourceWidth, viewport.sourceHeight, e.capabilities.display.width, e.capabilities.display.height, recenter = false)
            }
            is TransportEvent.KeyframeRequested -> encoder.requestKeyFrame()
            is TransportEvent.StatsReceived -> {}
            is TransportEvent.PreviewReceived -> onPreviewFrame(e.frame)
            is TransportEvent.ReceiverError -> { event("Receiver error ${e.code}: ${e.message}"); if (!e.code.recoverable) dispatch(SenderEvent.Failed(e.code, e.message)) }
            is TransportEvent.CredentialIssued -> { container.credentialStore.save(e.credential); event("Paired with ${e.credential.receiverId}") }
            is TransportEvent.Disconnected -> onLinkLost(e.code, e.details)
        }
    }

    private fun onPreviewFrame(frame: Payloads.PreviewFrame) {
        if (!_previewEnabled.value) return
        val decoded = runCatching {
            val bytes = android.util.Base64.decode(frame.data, android.util.Base64.NO_WRAP)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: return
        _previewFrame.value = GlassesPreview(decoded, frame.kind, System.nanoTime())
    }

    private fun onLinkLost(code: ErrorCode, details: String?) {
        if (userDisconnect) return
        _previewFrame.value = null
        if (reconnectJob?.isActive == true) return // attempts inside the reconnect loop report through exceptions
        val ep = endpoint ?: return
        val recoverable = code == ErrorCode.NETWORK_LOST || code == ErrorCode.RECEIVER_NOT_FOUND
        event("Link lost: ${details ?: code.name}")
        dispatch(SenderEvent.ConnectionLost(recoverable))
        if (!recoverable) { scope.launch { stopStreamingInternal("link failure") }; return }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val start = System.nanoTime()
            var attempt = 0
            var delayMs = 500L
            while (isActive && System.nanoTime() - start < RECONNECT_WINDOW_MS * 1_000_000) {
                attempt++
                dispatch(SenderEvent.ReconnectAttempt(attempt))
                try {
                    connectOnce(ep)
                    resumeAfterReconnect()
                    dispatch(SenderEvent.Reconnected)
                    event("Reconnected after $attempt attempt(s)")
                    return@launch
                } catch (e: MirrorException) {
                    if (e.code == ErrorCode.AUTHENTICATION_FAILED || e.code == ErrorCode.PAIRING_FAILED) {
                        stopStreamingInternal("re-authentication failed")
                        dispatch(SenderEvent.Failed(e.code, e.details)); return@launch
                    }
                }
                delay(delayMs); delayMs = (delayMs * 2).coerceAtMost(2000)
            }
            stopStreamingInternal("reconnect window exceeded")
            dispatch(SenderEvent.Failed(ErrorCode.NETWORK_LOST, "could not reconnect within ${RECONNECT_WINDOW_MS / 1000}s; reopen the app to retry"))
        }
    }

    private suspend fun resumeAfterReconnect() = stateMutex.withLock {
        val params = streamParams ?: return@withLock
        // New session id/keys: re-announce the stream and force a keyframe so the decoder restarts.
        transport.send(MessageType.STREAM_START, Payloads.StreamStart.serializer(), Payloads.StreamStart(params.codec.wireName, params.width, params.height, params.fps, params.bitrate, params.preset.wireName, _info.value.sourceName, transport.sessionShortId))
        sendStreamFormatIfChanged(force = true)
        sendViewportNow(viewport.state.value)
        sendPointerNow()
        encoder.requestKeyFrame()
    }

    private suspend fun sendStreamFormatIfChanged(force: Boolean = false) {
        val f = encoder.format.value ?: return
        if (transport.state.value !is TransportState.Connected) return
        val src = sourceGeometry ?: return
        val payload = Payloads.StreamFormat(
            codec = f.codec.wireName, width = f.width, height = f.height, fps = f.fps,
            csd0 = f.csd0?.let { Base64.getEncoder().encodeToString(it) }, csd1 = f.csd1?.let { Base64.getEncoder().encodeToString(it) },
            sourceWidth = src.width, sourceHeight = src.height, rotationDegrees = src.rotationDegrees,
        )
        if (!force && payload == lastFormatSent) return
        lastFormatSent = payload
        runCatching { transport.send(MessageType.STREAM_FORMAT, Payloads.StreamFormat.serializer(), payload) }
    }

    // ---- adaptation -----------------------------------------------------------------------

    private suspend fun adapt() {
        val ctl = adaptive ?: return
        val cfg = encoderConfig ?: return
        val t = _info.value.transport
        val rs = t.receiverStats
        val lat = _info.value.latency
        val inputs = AdaptiveInputs(
            lossFraction = t.lossFraction,
            rttMs = t.rttMs,
            minRttMs = t.minRttMs,
            encodedFps = lat?.fps ?: -1f,
            encodeLatencyMs = lat?.encodeMsAvg ?: 0f,
            receiverDecodeLagFrames = rs?.decodeLagFrames ?: 0,
            droppedFramesPerSecond = 0f,
            sendQueueDrops = (t.unitsDroppedAtSender - lastSenderDrops).toInt().also { lastSenderDrops = t.unitsDroppedAtSender },
        )
        when (val action = ctl.evaluate(inputs)) {
            AdaptiveAction.None -> {}
            is AdaptiveAction.RequestKeyframe -> { encoder.requestKeyFrame(); event("Keyframe requested (${action.reason})") }
            is AdaptiveAction.SetBitrate -> stateMutex.withLock {
                encoderConfig?.let { c -> encoder.reconfigure(c.copy(bitrate = action.bitrate)); encoderConfig = c.copy(bitrate = action.bitrate) }
                _info.update { it.copy(currentBitrate = action.bitrate) }
                event("Bitrate -> ${action.bitrate / 1000} kbps")
            }
            is AdaptiveAction.SetFps -> stateMutex.withLock { encoderConfig?.let { restartEncoder(it.copy(fps = action.fps)); sendStreamFormatIfChanged(force = true) }; event("FPS -> ${action.fps}") }
            is AdaptiveAction.SetResolutionStep -> stateMutex.withLock {
                resolutionStep = action.step
                val src = sourceGeometry ?: return@withLock
                val (w, h) = CaptureGeometryPlanner.encodedSize(src.width, src.height, CaptureGeometryPlanner.applyResolutionStep(negotiatedLongEdge, action.step))
                encoderConfig?.let { if (it.width != w || it.height != h) { restartEncoder(it.copy(width = w, height = h)); sendStreamFormatIfChanged(force = true) } }
                event("Resolution step -> ${action.step} (${w}x$h)")
            }
        }
    }
    private var lastSenderDrops = 0L

    // ---- viewport / profiles / presets ----------------------------------------------------

    private fun scheduleViewportSend(v: ViewportState) {
        pendingViewport = v
        if (viewportJob?.isActive == true) return
        viewportJob = scope.launch {
            while (pendingViewport != null) {
                val next = pendingViewport; pendingViewport = null
                next?.let { sendViewportNow(it) }
                delay(VIEWPORT_SEND_INTERVAL_MS)
            }
        }
    }

    private suspend fun sendViewportNow(v: ViewportState) {
        if (transport.state.value !is TransportState.Connected) return
        runCatching { transport.send(MessageType.VIEWPORT_SET, Payloads.ViewportSet.serializer(), Payloads.ViewportSet(v.scale, v.centerX, v.centerY, v.fitMode.name)) }
    }

    fun applyProfile(profile: ViewProfile) {
        _activeProfile.value = profile
        viewport.apply(profile.toViewport())
        scope.launch {
            container.profileRepository.setActive(profile.id)
            if (transport.state.value is TransportState.Connected) runCatching { transport.send(MessageType.PROFILE_SET, Payloads.ProfileSet.serializer(), profile.toPayload()) }
        }
        if (profile.preferredStreamPreset != _info.value.preset) setPreset(profile.preferredStreamPreset)
    }

    fun saveCurrentViewportToProfile(profile: ViewProfile) {
        val updated = profile.withViewport(viewport.state.value).copy(preferredStreamPreset = _info.value.preset)
        _activeProfile.value = updated
        scope.launch { container.profileRepository.save(updated); container.profileRepository.setActive(updated.id) }
        event("Saved profile ${updated.name}")
    }

    fun setPreset(preset: StreamPreset) {
        _info.update { it.copy(preset = preset) }
        scope.launch {
            container.profileRepository.setPreset(preset.wireName)
            if (_state.value is SenderState.Streaming) stateMutex.withLock {
                val src = sourceGeometry ?: return@withLock
                val caps = _info.value.capabilities ?: return@withLock
                val probe = EncoderCapabilities.probe() ?: return@withLock
                val params = StreamNegotiator.negotiate(preset, src.width, src.height, caps, probe.limits)
                negotiatedLongEdge = maxOf(params.width, params.height); resolutionStep = 0
                adaptive = AdaptiveController(preset.minBitrate, preset.maxBitrate, params.fps).apply { reset(params.bitrate, params.fps) }
                restartEncoder(EncoderConfig(width = params.width, height = params.height, fps = params.fps, bitrate = params.bitrate))
                streamParams = params
                sendStreamFormatIfChanged(force = true)
                event("Preset -> ${preset.wireName}")
            }
        }
    }

    fun requestKeyframe() = encoder.requestKeyFrame()

    // ---- seeing what the glasses see ---------------------------------------------------

    private val _previewEnabled = MutableStateFlow(false)
    val previewEnabled: StateFlow<Boolean> = _previewEnabled.asStateFlow()
    private val _previewFrame = MutableStateFlow<GlassesPreview?>(null)
    /** Latest snapshot from the glasses, or null when none has arrived. */
    val previewFrame: StateFlow<GlassesPreview?> = _previewFrame.asStateFlow()

    /**
     * Asks the glasses for snapshots of their display. Off by default: it costs a little
     * bandwidth on the same link that carries the video.
     */
    fun setPreviewEnabled(enabled: Boolean) {
        _previewEnabled.value = enabled
        if (!enabled) { _previewFrame.value = null; stopLocalPreview() }
        scope.launch {
            if (transport.state.value !is TransportState.Connected) return@launch
            runCatching {
                transport.send(MessageType.PREVIEW_SET, Payloads.PreviewSet.serializer(), Payloads.PreviewSet(enabled))
            }
        }
    }

    /**
     * Starts decoding our own outgoing stream into [surface], so the glasses view can show the
     * real mirrored picture rather than a diagram of it.
     */
    fun startLocalPreview(surface: Surface): Boolean {
        val format = encoder.format.value ?: return false
        val decoder = localPreview ?: LocalPreviewDecoder { message -> event("Local preview: $message") }.also { localPreview = it }
        val ok = decoder.start(surface, format.width, format.height, format.csd0, format.csd1)
        if (ok) encoder.requestKeyFrame()
        return ok
    }

    fun stopLocalPreview() {
        localPreview?.release()
        localPreview = null
    }

    val localPreviewRunning: Boolean get() = localPreview?.isRunning == true

    /** Where the whole encoded frame sits on the glasses, in glasses display pixels. */
    fun glassesPlacement(): com.rokidmirror.protocol.viewport.Placement {
        val info = _info.value
        val display = info.capabilities?.display
        return com.rokidmirror.protocol.viewport.ViewportMath.placement(
            viewport.state.value,
            info.sourceWidth.takeIf { it > 0 } ?: viewport.sourceWidth,
            info.sourceHeight.takeIf { it > 0 } ?: viewport.sourceHeight,
            display?.width ?: 480,
            display?.height ?: 640,
        )
    }

    /**
     * What part of the phone screen the glasses are actually showing. Exact, computed from the
     * viewport the phone already owns, so it needs nothing sent back.
     */
    fun visibleRegionOnGlasses(): com.rokidmirror.protocol.viewport.SourceRegion {
        val info = _info.value
        val display = info.capabilities?.display
        return com.rokidmirror.protocol.viewport.ViewportMath.visibleSourceRegion(
            viewport.state.value,
            info.sourceWidth.takeIf { it > 0 } ?: viewport.sourceWidth,
            info.sourceHeight.takeIf { it > 0 } ?: viewport.sourceHeight,
            display?.width ?: 480,
            display?.height ?: 640,
        )
    }

    // ---- glasses camera view -------------------------------------------------------------

    private val _visionEnabled = MutableStateFlow(false)
    /** Camera view on the glasses. Nothing is captured here; the phone only asks for the mode. */
    val visionEnabled: StateFlow<Boolean> = _visionEnabled.asStateFlow()
    private val _visionContrast = MutableStateFlow(1.4f)
    val visionContrast: StateFlow<Float> = _visionContrast.asStateFlow()

    fun setVisionMode(enabled: Boolean) {
        _visionEnabled.value = enabled
        sendVision()
        event(if (enabled) "Camera view on the glasses" else "Camera view off")
    }

    fun setVisionContrast(contrast: Float) {
        _visionContrast.value = contrast
        sendVision()
    }

    private fun sendVision() {
        scope.launch {
            if (transport.state.value !is TransportState.Connected) { event("Not connected to the glasses"); return@launch }
            runCatching {
                transport.send(MessageType.VISION_SET, Payloads.Vision.serializer(), Payloads.Vision(_visionEnabled.value, _visionContrast.value))
            }.onFailure { event("Could not reach the glasses: ${it.message}") }
        }
    }

    // ---- mouse mode ---------------------------------------------------------------------

    /**
     * Touch injection needs the cursor's frame coordinates to be phone screen coordinates, which
     * holds only when the whole display is being captured. With a single app captured, Android
     * gives no way to map the window back to screen coordinates, so taps stay disabled.
     */
    val pointerInjectionAvailable: Boolean
        get() {
            if (isExtended) return extended.isActive
            if (isSynthetic) return false
            val src = sourceGeometry ?: return false
            val disp = captureDisplayGeometry ?: return false
            return src.width == disp.width && src.height == disp.height
        }

    val pointerServiceEnabled: Boolean get() = PointerService.isRunning()

    fun setMouseMode(enabled: Boolean) {
        if (_mouseMode.value == enabled) return
        _mouseMode.value = enabled
        if (enabled) {
            sourceGeometry?.let { pointer.sourceWidth = it.width; pointer.sourceHeight = it.height }
            pointer.reset()
            _pointerPosition.value = pointer.position.x to pointer.position.y
            event("Mouse mode on" + if (!pointerInjectionAvailable) " (pointer only: not a whole-screen capture)" else "")
        } else {
            event("Mouse mode off")
        }
        schedulePointerSend()
    }

    fun movePointer(dxPadFraction: Float, dyPadFraction: Float) {
        if (!_mouseMode.value) return
        val p = pointer.moveBy(dxPadFraction, dyPadFraction)
        _pointerPosition.value = p.x to p.y
        schedulePointerSend()
    }

    fun pointerTap() = withInjector("tap") { svc, x, y -> svc.tap(x, y) }
    fun pointerLongPress() = withInjector("long press") { svc, x, y -> svc.longPress(x, y) }
    fun pointerScroll(dxPixels: Float, dyPixels: Float) {
        lastScroll = dxPixels to dyPixels
        withInjector("scroll") { svc, x, y -> svc.scroll(x, y, dxPixels, dyPixels) }
    }
    private var lastScroll: Pair<Float, Float>? = null
    fun pointerBack() = withInjector("back") { svc, _, _ -> svc.back() }
    fun pointerHome() = withInjector("home") { svc, _, _ -> svc.home() }
    fun pointerRecents() = withInjector("recents") { svc, _, _ -> svc.recents() }

    private fun withInjector(what: String, block: (PointerService, Float, Float) -> Unit) {
        // In extended mode the content is our own view hierarchy, so events go straight in.
        // That needs no accessibility service and cannot be refused by the platform.
        val workspace = if (isExtended) this.workspace else null
        if (workspace != null) {
            val (px, py) = pointer.toSourcePixels()
            when (what) {
                "tap" -> workspace.tap(px.toFloat(), py.toFloat())
                "long press" -> workspace.longPress(px.toFloat(), py.toFloat())
                "back" -> workspace.back()
                "home" -> workspace.home()
                "recents" -> workspace.reload()
                else -> lastScroll?.let { (dx, dy) -> workspace.scroll(px.toFloat(), py.toFloat(), dx, dy) }
            }
            encoder.requestKeyFrame()
            return
        }
        val svc = PointerService.instance
        if (svc == null) { event("Pointer service is off; enable it in Accessibility settings"); return }
        if (!pointerInjectionAvailable && what != "back" && what != "home" && what != "recents") {
            event(if (isSynthetic) "Cannot $what: the test pattern is not a real screen" else "Cannot $what: mouse mode needs whole-screen mirroring or extended screen")
            return
        }
        val (px, py) = pointer.toSourcePixels()
        block(svc, px.toFloat(), py.toFloat())
        // A click usually changes what is on screen; show it immediately rather than at the next IDR.
        if (what == "tap" || what == "long press" || what == "back" || what == "home" || what == "recents") encoder.requestKeyFrame()
    }

    private fun schedulePointerSend() {
        pendingPointerSend = true
        if (pointerJob?.isActive == true) return
        pointerJob = scope.launch {
            while (pendingPointerSend) {
                pendingPointerSend = false
                sendPointerNow()
                delay(VIEWPORT_SEND_INTERVAL_MS)
            }
        }
    }

    private suspend fun sendPointerNow() {
        if (transport.state.value !is TransportState.Connected) return
        val p = pointer.position
        runCatching {
            transport.send(MessageType.POINTER, Payloads.Pointer.serializer(), Payloads.Pointer(p.x, p.y, visible = _mouseMode.value))
        }
    }

    /** True while the control channel to a receiver is established. */
    val isConnected: Boolean get() = transport.state.value is TransportState.Connected

    /**
     * Re-establishes a dropped link on demand, e.g. when the app returns to the foreground after
     * the screen was locked and the platform tore the socket down.
     */
    fun retryConnectionIfDropped() {
        if (userDisconnect || isConnected) return
        if (reconnectJob?.isActive == true) return
        val state = _state.value
        val shouldRetry = state is SenderState.Error && (state.code == ErrorCode.NETWORK_LOST || state.code == ErrorCode.RECEIVER_NOT_FOUND)
        if (!shouldRetry) return
        onLinkLost(ErrorCode.NETWORK_LOST, "retry after returning to foreground")
    }

    // ---- diagnostics -----------------------------------------------------------------------

    fun diagnosticsReport(): DiagnosticsReport {
        val i = _info.value
        val rs = i.transport.receiverStats
        val last = i.stream ?: lastStreamParams
        return DiagnosticsReport(
            generatedAtEpochMs = System.currentTimeMillis(),
            senderVersion = AppContainer.VERSION,
            protocolVersion = Protocol.PROTOCOL_VERSION,
            phoneModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            receiverName = i.receiverName, receiverModel = i.capabilities?.receiverModel, receiverOs = i.capabilities?.receiverOs,
            receiverDecoder = i.capabilities?.decoderName?.ifBlank { null },
            receiverDecoderHardware = i.capabilities?.codecs?.firstOrNull()?.hardware ?: false,
            receiverMaxDecode = i.capabilities?.maxDecode?.let { "${it.width}x${it.height}@${it.fps}" },
            negotiatedCodec = last?.codec?.wireName, encodedWidth = last?.width ?: 0, encodedHeight = last?.height ?: 0,
            fps = i.encoderStats.fps, targetFps = last?.fps ?: 0, bitrateBps = i.encoderStats.bitrateBps,
            rttMs = i.transport.rttMs, lossFraction = i.transport.lossFraction,
            encodeMsAvg = i.latency?.encodeMsAvg ?: 0f, encodeMsMax = i.latency?.encodeMsMax ?: 0f,
            receiverReassemblyMs = rs?.reassemblyMs ?: -1f, receiverDecodeMs = rs?.decodeMs ?: -1f, receiverRenderMs = rs?.renderMs ?: -1f,
            networkMs = if (i.transport.rttMs >= 0) i.transport.rttMs / 2 else -1f,
            droppedFramesReceiver = rs?.framesDropped ?: 0, sendQueueDrops = i.transport.unitsDroppedAtSender,
            sessionDurationSec = if (i.sessionStartedAtNs > 0) (System.nanoTime() - i.sessionStartedAtNs) / 1_000_000_000 else 0,
            encoderName = i.encoderName, encoderLowLatency = i.encoderLowLatency, recentEvents = i.recentEvents,
        )
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun dispatch(e: SenderEvent) {
        _state.update { SenderStateMachine.reduce(it, e) }
    }

    private fun event(text: String) {
        MirrorLog.i(TAG, "event", "text" to text)
        _info.update { it.copy(recentEvents = (it.recentEvents + "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $text").takeLast(30)) }
    }

    private fun displayGeometry(): CaptureGeometry {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = context.resources.displayMetrics
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            CaptureGeometry(b.width(), b.height(), metrics.densityDpi)
        } else {
            @Suppress("DEPRECATION")
            val p = android.graphics.Point().also { wm.defaultDisplay.getRealSize(it) }
            CaptureGeometry(p.x, p.y, metrics.densityDpi)
        }
    }

    private fun describeNetwork(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "No network (hotspot mode?)"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val info = caps.transportInfo as? WifiInfo
                val freq = info?.frequency ?: -1
                when {
                    freq >= 5925 -> "6 GHz Wi-Fi"; freq >= 4900 -> "5 GHz Wi-Fi"; freq > 0 -> "2.4 GHz Wi-Fi"; else -> "Wi-Fi"
                }
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular (glasses must be on the phone hotspot)"
            else -> "Local network"
        }
    }
}
