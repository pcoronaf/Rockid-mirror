package com.rokidmirror.sender.encoder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.rokidmirror.protocol.EncoderLimits
import com.rokidmirror.protocol.VideoCodec

/** Result of probing the device's hardware AVC encoder (M4 "codec capability probing"). */
data class EncoderProbe(
    val encoderName: String,
    val hardwareAccelerated: Boolean,
    val supportsLowLatency: Boolean,
    val supportsCbr: Boolean,
    val limits: EncoderLimits,
    val supportsSize: (Int, Int) -> Boolean,
    val supportsFpsAt: (Int, Int, Double) -> Boolean,
)

object EncoderCapabilities {
    fun probe(codec: VideoCodec = VideoCodec.H264): EncoderProbe? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val candidates = list.codecInfos.filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(codec.mimeType, ignoreCase = true) } }
        val info = candidates.firstOrNull { isHardware(it) } ?: candidates.firstOrNull() ?: return null
        val caps = info.getCapabilitiesForType(codec.mimeType)
        val video = caps.videoCapabilities
        val enc = caps.encoderCapabilities
        val lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        return EncoderProbe(
            encoderName = info.name,
            hardwareAccelerated = isHardware(info),
            supportsLowLatency = lowLatency,
            supportsCbr = enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR),
            limits = EncoderLimits(
                maxWidth = video.supportedWidths.upper,
                maxHeight = video.supportedHeights.upper,
                maxFps = video.supportedFrameRates.upper.toInt().coerceAtMost(120),
                minBitrate = video.bitrateRange.lower,
                maxBitrate = video.bitrateRange.upper,
            ),
            supportsSize = { w, h -> video.isSizeSupported(w, h) },
            supportsFpsAt = { w, h, fps -> runCatching { video.areSizeAndRateSupported(w, h, fps) }.getOrDefault(false) },
        )
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated
        else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")

    fun describe(format: MediaFormat): String = format.toString()
}
