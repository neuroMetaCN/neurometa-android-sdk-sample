package com.neurometa.test

import com.neurometa.sdk.data.PSDAnalyzer
import com.neurometa.sdk.data.PSDConfig

data class PsdUiState(
    val deltaPower: Double,
    val thetaPower: Double,
    val alphaPower: Double,
    val betaPower: Double,
    val gammaPower: Double,
    val displayMode: DisplayMode,
    val signalQuality: PSDAnalyzer.SignalQuality,
    val isDataValid: Boolean,
    val statusText: String
)

enum class DisplayMode {
    ABS_UV,
    REL_PERCENT
}

fun PSDAnalyzer.PSDResult.toPsdUiState(): PsdUiState {
    val isAwakeFrontal = config.mode == PSDConfig.Mode.AWAKE_FRONTAL_PREVIEW
    return PsdUiState(
        deltaPower = if (isAwakeFrontal) deltaPercent else deltaDisplayPower,
        thetaPower = if (isAwakeFrontal) thetaPercent else thetaPower,
        alphaPower = if (isAwakeFrontal) alphaPercent else alphaPower,
        betaPower = if (isAwakeFrontal) betaPercent else betaPower,
        gammaPower = if (isAwakeFrontal) gammaPercent else gammaPower,
        displayMode = if (isAwakeFrontal) DisplayMode.REL_PERCENT else DisplayMode.ABS_UV,
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
