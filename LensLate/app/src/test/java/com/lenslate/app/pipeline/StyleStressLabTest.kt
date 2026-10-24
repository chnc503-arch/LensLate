package com.lenslate.app.pipeline

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hard-case colour quality: blurred text, overexposure, low contrast and a
 * textured colour gradient. The lab compares a single page-wide background
 * with the per-glyph backdrop and the experimental peak sampler.
 */
class StyleStressLabTest {

    @Test
    fun hardCasesStayRecoverableAndTheLabIsDeterministic() {
        val report = StyleStressLab.run()
        println(
            "LensLate stress lab: " + report.cases.joinToString(" | ") {
                "${it.id} global=${percent(it.globalError)} local=${percent(it.localError)} " +
                    "peak=${percent(it.peakError)} best=${it.best} pass=${it.passed}"
            },
        )

        assertEquals("all four hard cases must run", 4, report.cases.size)

        val gradient = report.cases.first { it.id == "gradient" }
        assertTrue(
            "local backdrop must beat one page colour on a gradient: " +
                "local=${percent(gradient.localError)} global=${percent(gradient.globalError)}",
            gradient.localError < gradient.globalError,
        )

        val blur = report.cases.first { it.id == "blur" }
        assertTrue(
            "peak sampling must not lose to the median on blurred text: " +
                "peak=${percent(blur.peakError)} local=${percent(blur.localError)}",
            blur.peakError <= blur.localError + LAB_MARGIN,
        )

        val overexposure = report.cases.first { it.id == "overexposure" }
        assertTrue(
            "peak sampling must cope with a highlight: " +
                "peak=${percent(overexposure.peakError)} local=${percent(overexposure.localError)}",
            overexposure.peakError <= overexposure.localError + LAB_MARGIN,
        )

        val lowContrast = report.cases.first { it.id == "low-contrast" }
        assertTrue(
            "peak sampling must recover text the median discards as low contrast: " +
                "peak=${percent(lowContrast.peakError)} local=${percent(lowContrast.localError)}",
            lowContrast.peakError < lowContrast.localError,
        )

        assertEquals("every hard case must pass in the lab", 4, report.passedCount)
        assertTrue(
            "peak sampling should win or tie in each case: ${report.peakWins}",
            report.peakWins >= 3,
        )

        report.cases.forEach { case ->
            assertTrue(
                "${case.id} colour error is too high: ${percent(case.peakError)}",
                case.peakError < MAX_RECOVERABLE_ERROR,
            )
        }

        assertEquals("the synthetic lab must be deterministic", report, StyleStressLab.run())
    }

    private fun percent(value: Float): String =
        String.format(Locale.US, "%.1f%%", value * 100f)

    private companion object {
        const val LAB_MARGIN = 0.02f
        const val MAX_RECOVERABLE_ERROR = 0.20f
    }
}
