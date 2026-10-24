package com.lenslate.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Локальный фон глифа: градиентная подложка, текстура и защита от соседних строк. */
class InkColorSamplerTest {

    @Test
    fun uniformStripsProduceAUniformBackdrop() {
        val strip = solid(20, 4, light)
        val backdrop = InkColorSampler.estimateBackdrop(strip, strip, globalBackground = gray, inkColor = black)
        assertEquals(light, backdrop.top)
        assertEquals(light, backdrop.bottom)
        assertEquals(light, backdrop.colorAt(0.37f))
    }

    @Test
    fun verticalGradientBackdropIsInterpolated() {
        val top = solid(20, 4, warm)
        val bottom = solid(20, 4, cold)
        val backdrop = InkColorSampler.estimateBackdrop(top, bottom, globalBackground = gray, inkColor = red)
        assertColorNear(warm, backdrop.colorAt(0f), tolerance = 2)
        assertColorNear(cold, backdrop.colorAt(1f), tolerance = 2)
        assertColorNear(0xFF908070.toInt(), backdrop.colorAt(0.5f), tolerance = 3)
    }

    @Test
    fun stripCoveredByInkIsRejected() {
        val cleanTop = solid(20, 4, light)
        val inkStrip = solid(20, 4, black)
        val backdrop = InkColorSampler.estimateBackdrop(cleanTop, inkStrip, globalBackground = light, inkColor = black)
        assertEquals(light, backdrop.top)
        assertEquals(light, backdrop.bottom)
    }

    @Test
    fun minorityContaminationDoesNotMoveTheStripMedian() {
        val top = IntArray(20 * 4) { index -> if (index % 5 == 0) black else light }
        val bottom = IntArray(20 * 4) { index -> if (index % 5 == 0) black else light }
        val backdrop = InkColorSampler.estimateBackdrop(top, bottom, globalBackground = gray, inkColor = black)
        assertColorNear(light, backdrop.top, tolerance = 2)
    }

    @Test
    fun missingStripsFallBackToTheParagraphBackground() {
        val backdrop = InkColorSampler.estimateBackdrop(IntArray(0), IntArray(0), gray, black)
        assertEquals(gray, backdrop.top)
        assertEquals(gray, backdrop.bottom)
    }

    @Test
    fun inkIsRecoveredWhenTheParagraphMedianMatchesIt() {
        // The page is orange at the top and blue at the bottom; the paragraph
        // median is exactly the grey ink. Only a local backdrop can tell the
        // grey glyph pixels apart from their surroundings.
        val width = 16
        val topPad = 4
        val glyphRows = 8
        val bottomPad = 4
        val crop = IntArray(width * (topPad + glyphRows + bottomPad))
        val ink = 0xFF8088A0.toInt()
        val nominalInk = green
        for (row in 0 until topPad + glyphRows + bottomPad) {
            val fraction = row.toFloat() / (topPad + glyphRows + bottomPad - 1)
            val background = StyleGradient.lerpColor(warm, cold, fraction)
            for (x in 0 until width) {
                val inkColumn = x in 6..9 && row in topPad until topPad + glyphRows
                crop[row * width + x] = if (inkColumn) ink else background
            }
        }
        val topStrip = crop.copyOfRange(0, topPad * width)
        val bottomStrip = crop.copyOfRange(
            (topPad + glyphRows) * width,
            crop.size,
        )
        // The paragraph background is exactly the glyph colour, and the
        // fallback is a third colour. Using either of those would lose the
        // glyphs; only the local warm-to-cold ramp can recover them.
        val backdrop = InkColorSampler.estimateBackdrop(topStrip, bottomStrip, ink, nominalInk)
        val sampled = InkColorSampler.sampleGlyphColor(
            pixels = crop,
            width = width,
            glyphTop = topPad,
            glyphBottom = topPad + glyphRows,
            backdrop = backdrop,
            fallbackInk = nominalInk,
        )
        assertColorNear(ink, sampled, tolerance = 3)
    }

    @Test
    fun glyphWithoutInkFallsBackToTheParagraphInkColour() {
        val width = 12
        val glyphRows = 6
        val uniform = solid(width, glyphRows + 8, light)
        val backdrop = InkColorSampler.estimateBackdrop(
            uniform.copyOfRange(0, 4 * width),
            uniform.copyOfRange((glyphRows + 4) * width, uniform.size),
            light,
            black,
        )
        val sampled = InkColorSampler.sampleGlyphColor(
            pixels = uniform,
            width = width,
            glyphTop = 4,
            glyphBottom = 4 + glyphRows,
            backdrop = backdrop,
            fallbackInk = black,
        )
        assertEquals(black, sampled)
    }

    @Test
    fun peakModeStaysCloserToInkWhenNoPixelIsFullyCovered() {
        // A blurry glyph has a coverage gradient instead of a solid core. The
        // median of the strong pixels lands on the middle coverage, the peak
        // mode averages the most covered quarter and keeps more saturation.
        val width = 9
        val glyphRows = 8
        val height = glyphRows + 8
        val crop = solid(width, height, light)
        for (row in 4 until 4 + glyphRows) {
            for (x in 1 until width - 1) {
                val coverage = when (x) {
                    4 -> 0.90f
                    3, 5 -> 0.72f
                    2, 6 -> 0.55f
                    else -> 0.30f
                }
                crop[row * width + x] = StyleGradient.lerpColor(light, red, coverage)
            }
        }
        val backdrop = InkColorSampler.estimateBackdrop(
            crop.copyOfRange(0, 4 * width),
            crop.copyOfRange((4 + glyphRows) * width, crop.size),
            light,
            red,
        )
        val median = InkColorSampler.sampleGlyphColor(
            pixels = crop,
            width = width,
            glyphTop = 4,
            glyphBottom = 4 + glyphRows,
            backdrop = backdrop,
            fallbackInk = green,
        )
        val peak = InkColorSampler.sampleGlyphColor(
            pixels = crop,
            width = width,
            glyphTop = 4,
            glyphBottom = 4 + glyphRows,
            backdrop = backdrop,
            fallbackInk = green,
            mode = InkColorSampler.Mode.PEAK,
        )
        assertTrue(
            "peak=${peak.toHex()} must be closer to ink than median=${median.toHex()}",
            InkColorSampler.distanceSq(peak, red) < InkColorSampler.distanceSq(median, red),
        )
        assertColorNear(red, peak, tolerance = 26)
    }

    @Test
    fun peakModeSamplesTextBelowTheNormalContrastFloor() {
        val width = 10
        val glyphRows = 6
        val height = glyphRows + 8
        val faintInk = 0xFFDCDCDC.toInt()
        val crop = solid(width, height, light)
        for (row in 4 until 4 + glyphRows) {
            for (x in 2 until 8) {
                crop[row * width + x] = faintInk
            }
        }
        val backdrop = InkColorSampler.estimateBackdrop(
            crop.copyOfRange(0, 4 * width),
            crop.copyOfRange((4 + glyphRows) * width, crop.size),
            light,
            green,
        )
        val median = InkColorSampler.sampleGlyphColor(
            pixels = crop,
            width = width,
            glyphTop = 4,
            glyphBottom = 4 + glyphRows,
            backdrop = backdrop,
            fallbackInk = green,
        )
        val peak = InkColorSampler.sampleGlyphColor(
            pixels = crop,
            width = width,
            glyphTop = 4,
            glyphBottom = 4 + glyphRows,
            backdrop = backdrop,
            fallbackInk = green,
            mode = InkColorSampler.Mode.PEAK,
        )
        assertEquals("normal mode keeps the paragraph colour for faint text", green, median)
        assertColorNear(faintInk, peak, tolerance = 3)
    }

    private fun solid(width: Int, height: Int, color: Int): IntArray =
        IntArray(width * height) { color }

    private fun assertColorNear(expected: Int, actual: Int, tolerance: Int) {
        val channels = listOf(16, 8, 0).map { shift ->
            kotlin.math.abs(((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF))
        }
        assertTrue(
            "expected=${expected.toHex()} actual=${actual.toHex()} channels=$channels",
            channels.all { it <= tolerance },
        )
    }

    private fun Int.toHex(): String = "0x" + Integer.toHexString(this).uppercase()

    private companion object {
        val black = 0xFF101010.toInt()
        val gray = 0xFF808080.toInt()
        val light = 0xFFF0F0F0.toInt()
        val warm = 0xFFF0E0D0.toInt()
        val cold = 0xFF302010.toInt()
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
    }
}
