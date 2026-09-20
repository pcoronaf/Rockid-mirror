package com.rokidmirror.sender.control

import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.viewport.FitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderStateMachineTest {
    private fun run(vararg events: SenderEvent, from: SenderState = SenderState.Idle): SenderState =
        events.fold(from) { s, e -> SenderStateMachine.reduce(s, e) }

    @Test
    fun happyPathToStreaming() {
        val s = run(
            SenderEvent.StartSearch, SenderEvent.ReceiversChanged(1), SenderEvent.ConnectRequested("Glasses"),
            SenderEvent.PairingCodeRequired, SenderEvent.PairingCodeSubmitted, SenderEvent.Connected,
            SenderEvent.CapturePermissionRequested, SenderEvent.StreamStarted("Phone"),
        )
        assertEquals(SenderState.Streaming("Glasses", "Phone"), s)
    }

    @Test
    fun permissionDeniedReturnsToReady() {
        val s = run(SenderEvent.ConnectRequested("G"), SenderEvent.Connected, SenderEvent.CapturePermissionRequested, SenderEvent.CapturePermissionDenied)
        assertEquals(SenderState.Ready("G"), s)
    }

    @Test
    fun systemStopDuringStreamingReturnsToReady() {
        val s = run(SenderEvent.ConnectRequested("G"), SenderEvent.Connected, SenderEvent.StreamStarted("App"), SenderEvent.CaptureStopped("projection stopped"))
        assertEquals(SenderState.Ready("G"), s)
    }

    @Test
    fun connectionLossWhileStreamingRecoversToStreaming() {
        var s = run(SenderEvent.ConnectRequested("G"), SenderEvent.Connected, SenderEvent.StreamStarted("App"), SenderEvent.ConnectionLost(recoverable = true))
        assertEquals(SenderState.Recovering("G", 0, wasStreaming = true), s)
        s = run(SenderEvent.ReconnectAttempt(2), SenderEvent.Reconnected, from = s)
        assertTrue(s is SenderState.Streaming)
    }

    @Test
    fun captureStopDuringRecoveryLandsInReady() {
        var s = run(SenderEvent.ConnectRequested("G"), SenderEvent.Connected, SenderEvent.StreamStarted("App"), SenderEvent.ConnectionLost(true))
        s = run(SenderEvent.CaptureStopped("lock"), SenderEvent.Reconnected, from = s)
        assertEquals(SenderState.Ready("G"), s)
    }

    @Test
    fun unrecoverableLossIsError() {
        val s = run(SenderEvent.ConnectRequested("G"), SenderEvent.Connected, SenderEvent.ConnectionLost(false))
        assertEquals(SenderState.Error(ErrorCode.NETWORK_LOST, null, "G"), s)
        assertEquals(SenderState.Idle, run(SenderEvent.Dismissed, from = s))
    }

    @Test
    fun hiddenContentPausesAndResumes() {
        var s = run(SenderEvent.ConnectRequested("G"), SenderEvent.Connected, SenderEvent.StreamStarted("App"), SenderEvent.ContentHidden(true))
        assertTrue(s is SenderState.Paused)
        s = run(SenderEvent.ContentHidden(false), from = s)
        assertTrue(s is SenderState.Streaming)
    }

    @Test
    fun profileRoundTripsViewport() {
        val p = ViewProfile.defaults.first { it.id == "reading" }
        val v = p.toViewport()
        assertEquals(0.35f, v.centerY, 1e-6f)
        assertEquals(FitMode.CUSTOM, v.fitMode)
        assertEquals(p, p.withViewport(v))
    }

    @Test
    fun viewportControllerClampsAndNotifies() {
        var sent = 0
        val c = ViewportController { sent++ }
        c.setGeometry(720, 1600, 480, 640, recenter = true)
        c.setFitMode(FitMode.ACTUAL)
        c.setCenter(0f, 0f)
        assertTrue(c.state.value.centerX > 0f && c.state.value.centerY > 0f)
        assertTrue(sent >= 2)
        c.resetToFit()
        assertEquals(FitMode.FIT, c.state.value.fitMode)
        assertEquals(1f, c.zoomMultiplier(), 1e-6f)
    }
}
