package com.neurometa.test

import com.neurometa.sdk.model.EEGDataPacket
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugLogFormatterTest {

    @Test
    fun `formats raw packet as hex preview`() {
        val raw = byteArrayOf(
            0xED.toByte(),
            0x01,
            0x1B,
            0x00,
            0x00
        )

        val formatted = DebugLogFormatter.formatRawPacket(raw)

        assertEquals("[BLE] len=5 hex=ED 01 1B 00 00", formatted)
    }

    @Test
    fun `formats parsed eeg packet as readable preview`() {
        val packet =
            EEGDataPacket(
                timestamp = 1L,
                sequenceNumber = 12,
                channelData = mapOf(0 to doubleArrayOf(191.1, 201.2, 211.3)),
                samplingRate = 250
            )

        val formatted = DebugLogFormatter.formatParsedEeg(packet)

        assertEquals(
            "[EEG] seq=12 sr=250Hz ch=0 count=3 eeg=191.1, 201.2, 211.3",
            formatted
        )
    }
}
