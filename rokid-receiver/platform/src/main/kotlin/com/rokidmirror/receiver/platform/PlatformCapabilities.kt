package com.rokidmirror.receiver.platform

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.rokidmirror.protocol.control.Payloads

/** Result of probing the device's H.264 decoder. */
data class DecoderProbe(
    val name: String,
    val hardwareAccelerated: Boolean,
    val lowLatency: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFpsAt720p: Int,
    val supports1080p30: Boolean,
    val profiles: List<String>,
)

/** Builds the CAPABILITIES payload from measured decoder/display facts, never from assumptions. */
object PlatformCapabilities {
    fun probeDecoder(mime: String = MediaFormat.MIMETYPE_VIDEO_AVC): DecoderProbe? {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
        val info = infos.firstOrNull { it.isHardwareAccelerated } ?: infos.firstOrNull() ?: return null
        val caps = info.getCapabilitiesForType(mime)
        val v = caps.videoCapabilities
        val lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        val fps720 = runCatching { v.getSupportedFrameRatesFor(1280, 720).upper.toInt() }.getOrDefault(0)
        val profiles = caps.profileLevels.map { it.profile }.distinct().mapNotNull { avcProfileName(it) }
        return DecoderProbe(info.name, info.isHardwareAccelerated, lowLatency, v.supportedWidths.upper, v.supportedHeights.upper, fps720, runCatching { v.areSizeAndRateSupported(1920, 1080, 30.0) }.getOrDefault(false), profiles)
    }

    fun capabilities(display: DisplayInfo, platform: PlatformDescription, input: InputCapabilities, sensors: SensorCapabilities, probe: DecoderProbe?): Payloads.Capabilities {
        val codecs = if (probe != null) listOf(Payloads.CodecCapability("h264", probe.profiles, probe.hardwareAccelerated, probe.lowLatency)) else emptyList()
        // Conservative default until M0 measures sustained throughput: 720p30 (spec: test 480p30 first, 720p30 after).
        val maxDecode = when {
            probe == null -> Payloads.DecodeLimit(0, 0, 0)
            probe.hardwareAccelerated && probe.maxFpsAt720p >= 60 -> Payloads.DecodeLimit(1280, 720, 60)
            probe.hardwareAccelerated -> Payloads.DecodeLimit(1280, 720, 30)
            else -> Payloads.DecodeLimit(854, 480, 30) // software decode: 480p30 only
        }
        return Payloads.Capabilities(
            display = Payloads.DisplaySize(display.width, display.height, display.densityDpi, display.refreshHz),
            codecs = codecs,
            maxDecode = maxDecode,
            input = Payloads.InputCapability(touchBar = input.touchBar != Support.UNAVAILABLE, imu = sensors.rotationVector != Support.UNAVAILABLE, keys = input.hardwareKeys != Support.UNAVAILABLE),
            receiverModel = platform.model,
            receiverOs = "${platform.os} / API ${platform.apiLevel} / ${platform.abi}",
            headViewportSupported = sensors.rotationVector != Support.UNAVAILABLE,
        )
    }

    private fun avcProfileName(p: Int): String? = when (p) {
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "baseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline -> "constrained-baseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "main"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "high"
        MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh -> "constrained-high"
        else -> null
    }
}
