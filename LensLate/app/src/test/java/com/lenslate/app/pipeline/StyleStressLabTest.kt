package com.lenslate.app.pipeline

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hard-case colour quality: blurred text, two kinds of overexposure, low
 * contrast, near-black ink on a dark surface and a textured colour gradient.
 * The lab compares a single page-wide background with the per-glyph backdrop,
 * the pure peak sampler and the adaptive candidate.
 */
class StyleStressLabTest {

    @Test
    fun hardCasesStayRecoverableAndTheLabIsDeterministic() {
        val report = StyleStressLab.run()
        println(
            "LensLate stress lab: " + report.cases.joinToString(" | ") {
                "${it.id} global=${percent(it.globalError)} local=${percent(it.localError)} " +
                    "adaptive=${percent(it.adaptiveError)} peak=${percent(it.peakError)} " +
                    "best=${it.best} pass=${it.passed}"
            },
        )

        assertEquals("all six hard cases must run", 6, report.cases.size)

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

        val darkInk = report.cases.first { it.id == "dark-ink" }
        assertTrue(
            "adaptive sampling must not lose to the median on near-black ink: " +
                "adaptive=${percent(darkInk.adaptiveError)} local=${percent(darkInk.localError)}",
            darkInk.adaptiveError <= darkInk.localError + LAB_MARGIN,
        )

        val blownOut = report.cases.first { it.id == "blown-out" }
        assertTrue(
            "adaptive sampling must recover clipped ink the median replaces: " +
                "adaptive=${percent(blownOut.adaptiveError)} local=${percent(blownOut.localError)}",
            blownOut.adaptiveError < blownOut.localError,
        )

        assertEquals("every hard case must pass in the lab", 6, report.passedCount)
        assertTrue(
            "peak sampling should win or tie in most cases: ${report.peakWins}",
            report.peakWins >= 3,
        )
        assertTrue(
            "the adaptive candidate must win where the median discards faint ink: ${report.adaptiveWins}",
            report.adaptiveWins >= 2,
        )

        report.cases.forEach { case ->
            assertTrue(
                "${case.id} colour error is too high: ${percent(case.adaptiveError)}",
                case.adaptiveError < MAX_RECOVERABLE_ERROR,
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
