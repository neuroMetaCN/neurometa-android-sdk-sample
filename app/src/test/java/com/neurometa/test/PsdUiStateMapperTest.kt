package com.neurometa.test

import com.neurometa.sdk.data.PSDAnalyzer
import com.neurometa.sdk.data.PSDConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PsdUiStateMapperTest {

    @Test
    fun `maps final powers and simplified status for ui`() {
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
            deltaPercent = 40.0,
            thetaPercent = 20.0,
            alphaPercent = 15.0,
            betaPercent = 15.0,
            gammaPercent = 10.0,
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

        assertEquals(6.5, uiState.deltaPower, 0.0001)
        assertEquals(5.0, uiState.thetaPower, 0.0001)
        assertEquals(4.0, uiState.alphaPower, 0.0001)
        assertEquals(3.0, uiState.betaPower, 0.0001)
        assertEquals(2.0, uiState.gammaPower, 0.0001)
        assertEquals(PSDAnalyzer.SignalQuality.FAIR, uiState.signalQuality)
        assertEquals("AWAKE FRONTAL · READY · FAIR", uiState.statusText)
        assertFalse(uiState.isDataValid.not())
    }
}
