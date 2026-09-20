package com.rokidmirror.sender.control

import com.rokidmirror.protocol.StreamPreset
import com.rokidmirror.protocol.control.Payloads
import com.rokidmirror.protocol.viewport.FitMode
import com.rokidmirror.protocol.viewport.ViewportState
import kotlinx.serialization.Serializable

/** Persisted display profile (spec "Profiles"). offsetX/offsetY are relative to center (-0.5..0.5). */
@Serializable
data class ViewProfile(
    val id: String,
    val name: String,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val fitMode: FitMode,
    val preferredStreamPreset: StreamPreset,
) {
    fun toViewport(): ViewportState = ViewportState(scale, 0.5f + offsetX, 0.5f + offsetY, fitMode)

    fun withViewport(v: ViewportState): ViewProfile = copy(scale = v.scale, offsetX = v.centerX - 0.5f, offsetY = v.centerY - 0.5f, fitMode = v.fitMode)

    fun toPayload(): Payloads.ProfileSet = Payloads.ProfileSet(id, name, scale, offsetX, offsetY, fitMode.name, preferredStreamPreset.wireName)

    companion object {
        const val FULL_SCREEN_ID = "full-screen"

        /** Default profiles from the specification; the Walking profile is deliberately unoptimized. */
        val defaults: List<ViewProfile> = listOf(
            ViewProfile(FULL_SCREEN_ID, "Full Screen", 1f, 0f, 0f, FitMode.FIT, StreamPreset.BALANCED),
            ViewProfile("reading", "Reading", 2f, 0f, -0.15f, FitMode.CUSTOM, StreamPreset.BALANCED),
            ViewProfile("work", "Work", 1.6f, 0f, 0f, FitMode.CUSTOM, StreamPreset.INTERACTIVE),
            ViewProfile("walking", "Walking / Corner View", 0.6f, 0f, 0f, FitMode.CUSTOM, StreamPreset.LOW),
        )
    }
}
