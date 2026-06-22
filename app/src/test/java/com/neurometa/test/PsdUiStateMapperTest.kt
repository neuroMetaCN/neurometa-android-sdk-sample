package com.neurometa.test

import com.neurometa.sdk.data.PSDAnalyzer
import com.neurometa.sdk.data.PSDConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PsdUiStateMapperTest {

    @Test
    fun `maps awake frontal results to relative display values`() {
        val result = PSDAnalyzer.PSDResult(
            timestamp = 123L,
            deltaPower = 7.0,
            thetaPower = 5.0,
            alphaPower = 4.0,
            betaPower = 3.0,
            gammaPower = 2.0,
            deltaRawPower = 12.0,
            deltaDisplayPower = 6.5,
            deltaConfidence = 0.42,
            deltaPercent = 18.0,
            thetaPercent = 22.0,
            alphaPercent = 34.0,
            betaPercent = 20.0,
            gammaPercent = 6.0,
            deltaRawPercent = 40.0,
            thetaRawPercent = 20.0,
            alphaRawPercent = 15.0,
            betaRawPercent = 15.0,
            gammaRawPercent = 10.0,
            deltaDisplayPercent = 18.0,
            thetaDisplayPercent = 22.0,
            alphaDisplayPercent = 34.0,
            betaDisplayPercent = 20.0,
            gammaDisplayPercent = 6.0,
            thetaFocusPercent = 50.0,
            alphaFocusPercent = 30.0,
            betaFocusPercent = 20.0,
            deltaWaveform = doubleArrayOf(1.0, 2.0),
            thetaWaveform = doubleArrayOf(1.0),
            alphaWaveform = doubleArrayOf(1.0),
            betaWaveform = doubleArrayOf(1.0),
            gammaWaveform = doubleArrayOf(1.0),
            psd = null,
            config = PSDConfig.awakeFrontalPreview(),
            signalQuality = PSDAnalyzer.SignalQuality.FAIR,
            qualityScore = 61,
            isDataValid = true,
            isFrozen = false,
            statusMessage = "READY · FAIR",
            artifactReason = "low-frequency-dominant"
        )

        val uiState = result.toPsdUiState()

        assertEquals(18.0, uiState.deltaPower, 0.0001)
        assertEquals(22.0, uiState.thetaPower, 0.0001)
        assertEquals(34.0, uiState.alphaPower, 0.0001)
        assertEquals(20.0, uiState.betaPower, 0.0001)
        assertEquals(6.0, uiState.gammaPower, 0.0001)
        assertEquals(DisplayMode.REL_PERCENT, uiState.displayMode)
        assertEquals(PSDAnalyzer.SignalQuality.FAIR, uiState.signalQuality)
        assertEquals("AWAKE FRONTAL · READY · FAIR", uiState.statusText)
        assertFalse(uiState.isDataValid.not())
    }

    @Test
    fun `maps sleep raw results to absolute uv display values`() {
        val result = PSDAnalyzer.PSDResult(
            timestamp = 456L,
            deltaPower = 14.0,
            thetaPower = 6.0,
            alphaPower = 3.0,
            betaPower = 2.0,
            gammaPower = 1.0,
            deltaRawPower = 15.0,
            deltaDisplayPower = 14.0,
            deltaConfidence = 0.9,
            deltaPercent = 50.0,
            thetaPercent = 20.0,
            alphaPercent = 12.0,
            betaPercent = 10.0,
            gammaPercent = 8.0,
            deltaRawPercent = 50.0,
            thetaRawPercent = 20.0,
            alphaRawPercent = 12.0,
            betaRawPercent = 10.0,
            gammaRawPercent = 8.0,
            deltaDisplayPercent = 50.0,
            thetaDisplayPercent = 20.0,
            alphaDisplayPercent = 12.0,
            betaDisplayPercent = 10.0,
            gammaDisplayPercent = 8.0,
            thetaFocusPercent = 40.0,
            alphaFocusPercent = 35.0,
            betaFocusPercent = 25.0,
            deltaWaveform = doubleArrayOf(1.0),
            thetaWaveform = doubleArrayOf(1.0),
            alphaWaveform = doubleArrayOf(1.0),
            betaWaveform = doubleArrayOf(1.0),
            gammaWaveform = doubleArrayOf(1.0),
            psd = null,
            config = PSDConfig.sleepRaw(),
            signalQuality = PSDAnalyzer.SignalQuality.GOOD,
            qualityScore = 90,
            isDataValid = true,
            isFrozen = false,
            statusMessage = "READY",
            artifactReason = null
        )

        val uiState = result.toPsdUiState()

        assertEquals(14.0, uiState.deltaPower, 0.0001)
        assertEquals(6.0, uiState.thetaPower, 0.0001)
        assertEquals(3.0, uiState.alphaPower, 0.0001)
        assertEquals(2.0, uiState.betaPower, 0.0001)
        assertEquals(1.0, uiState.gammaPower, 0.0001)
        assertEquals(DisplayMode.ABS_UV, uiState.displayMode)
        assertEquals("SLEEP RAW · READY", uiState.statusText)
    }
}
