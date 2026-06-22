package com.neurometa.test

import com.neurometa.sdk.model.EEGDataPacket
import java.util.Locale

object DebugLogFormatter {

    private const val RAW_PREVIEW_BYTES = 32
    private const val EEG_PREVIEW_SAMPLES = 8

    fun formatRawPacket(raw: ByteArray, previewBytes: Int = RAW_PREVIEW_BYTES): String {
        val preview =
            raw.take(previewBytes).joinToString(" ") { byte ->
                "%02X".format(byte.toInt() and 0xFF)
            }
        val suffix = if (raw.size > previewBytes) " ..." else ""
        return "[BLE] len=${raw.size} hex=$preview$suffix"
    }

    fun formatParsedEeg(
        packet: EEGDataPacket,
        channel: Int = 0,
        previewSamples: Int = EEG_PREVIEW_SAMPLES
    ): String {
        val samples = packet.channelData[channel] ?: packet.channelData.values.firstOrNull()
        if (samples == null) {
            return "[EEG] seq=${packet.sequenceNumber} sr=${packet.samplingRate}Hz empty"
        }

        val preview =
            samples.take(previewSamples).joinToString(", ") { sample ->
                String.format(Locale.US, "%.1f", sample)
            }
        val suffix = if (samples.size > previewSamples) ", ..." else ""
        return "[EEG] seq=${packet.sequenceNumber} sr=${packet.samplingRate}Hz ch=$channel count=${samples.size} eeg=$preview$suffix"
    }
}
