package com.neurometa.test

import com.neurometa.sdk.data.PSDAnalyzer
import com.neurometa.sdk.data.PSDConfig

data class PsdUiState(
    val deltaPower: Double,
    val thetaPower: Double,
    val alphaPower: Double,
    val betaPower: Double,
    val gammaPower: Double,
    val signalQuality: PSDAnalyzer.SignalQuality,
    val isDataValid: Boolean,
    val statusText: String
)

fun PSDAnalyzer.PSDResult.toPsdUiState(): PsdUiState {
    return PsdUiState(
        deltaPower = deltaDisplayPower,
        thetaPower = thetaPower,
        alphaPower = alphaPower,
        betaPower = betaPower,
        gammaPower = gammaPower,
        signalQuality = signalQuality,
        isDataValid = isDataValid,
        statusText = "${config.toPsdModeLabel()} · $statusMessage"
    )
}

private fun PSDConfig.toPsdModeLabel(): String {
    return when (mode) {
        PSDConfig.Mode.AWAKE_FRONTAL_PREVIEW -> "AWAKE FRONTAL"
        PSDConfig.Mode.SLEEP_RAW -> "SLEEP RAW"
    }
}
