package com.neurometa.test

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ActivityMainLayoutRegressionTest {

    @Test
    fun `activity main layout does not expose y zoom controls`() {
        val layoutFile = File("src/main/res/layout/activity_main.xml")
        val layoutText = layoutFile.readText()

        assertFalse(layoutText.contains("seekYAxisZoom"))
        assertFalse(layoutText.contains("tvYAxisZoomValue"))
        assertFalse(layoutText.contains("Y ZOOM"))
    }
}
