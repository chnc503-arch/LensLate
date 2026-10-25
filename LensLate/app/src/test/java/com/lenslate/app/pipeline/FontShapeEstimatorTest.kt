package com.lenslate.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.tan

class FontShapeEstimatorTest {

    private fun line(widths: List<Float>, advance: (index: Int) -> Float): List<GlyphBox> {
        var left = 0f
        return widths.mapIndexed { index, width ->
            val box = GlyphBox("x", left, left + width)
            left += advance(index)
            box
        }
    }

    @Test
    fun monospacedLineKeepsTheFullAdvanceForNarrowGlyphs() {
        val widths = listOf(12f, 9f, 3f, 10f, 4f, 11f, 8f, 12f)
        assertTrue(FontShapeEstimator.isMonospaced(listOf(line(widths) { 14f })))
    }

    @Test
    fun proportionalTextMovesNarrowGlyphsCloser() {
        val widths = listOf(3f, 10f, 12f, 8f, 11f, 4f, 12f, 9f, 10f, 11f)
        assertFalse(FontShapeEstimator.isMonospaced(listOf(line(widths) { index -> widths[index] + 2f })))
    }

    @Test
    fun synthesisedEqualCellsAreNotMistakenForMonospace() {
        val widths = List(8) { 10f }
        assertFalse(FontShapeEstimator.isMonospaced(listOf(line(widths) { 10f })))
    }

    @Test
    fun uniformWideCapitalsCannotProveMonospace() {
        val widths = listOf(10f, 11f, 12f, 11f, 10f, 11f, 12f, 11f)
        assertFalse(FontShapeEstimator.isMonospaced(listOf(line(widths) { 14f })))
    }

    @Test
    fun uprightStemsProduceNoSlant() {
        val mask = stemMask(width = 160, height = 40, slantDegrees = 0f)
        assertEquals(
            0f,
            FontShapeEstimator.slantDegrees(mask, 160, 40, listOf(TextLineSpan(0, 0, 160, 40))),
            0.01f,
        )
    }

    @Test
    fun rightLeaningStemsProducePositiveSlant() {
        val mask = stemMask(width = 160, height = 40, slantDegrees = 12f)
        val slant = FontShapeEstimator.slantDegrees(
            mask,
            160,
            40,
            listOf(TextLineSpan(0, 0, 160, 40)),
        )
        assertEquals(12f, slant, 1.5f)
    }

    @Test
    fun leftLeaningStemsProduceNegativeSlant() {
        val mask = stemMask(width = 160, height = 40, slantDegrees = -11f)
        val slant = FontShapeEstimator.slantDegrees(
            mask,
            160,
            40,
            listOf(TextLineSpan(0, 0, 160, 40)),
        )
        assertEquals(-11f, slant, 1.5f)
    }

    @Test
    fun aShearThatNoStemConfirmsIsRejected() {
        val mask = stemMask(width = 160, height = 40, slantDegrees = 14f, stems = 2)
        assertEquals(
            0f,
            FontShapeEstimator.slantDegrees(mask, 160, 40, listOf(TextLineSpan(0, 0, 160, 40))),
            0.01f,
        )
    }

    @Test
    fun flatToppedLettersAreNotReadAsLeaning() {
        val mask = teeMask(width = 200, height = 40, slantDegrees = 0f)
        assertEquals(
            0f,
            FontShapeEstimator.slantDegrees(mask, 200, 40, listOf(TextLineSpan(0, 0, 200, 40))),
            0.01f,
        )
    }

    @Test
    fun flatToppedLettersKeepTheirRealLean() {
        val mask = teeMask(width = 200, height = 40, slantDegrees = 11f)
        val slant = FontShapeEstimator.slantDegrees(
            mask,
            200,
            40,
            listOf(TextLineSpan(0, 0, 200, 40)),
        )
        assertTrue("measured $slant", slant in 6f..12f)
    }

    @Test
    fun tinyMasksAreNotMeasured() {
        val mask = BooleanArray(100) { true }
        assertEquals(0f, FontShapeEstimator.slantDegrees(mask, 10, 10, listOf(TextLineSpan(0, 0, 10, 10))))
    }

    @Test
    fun wideDisplayCapsShowTheirTrackingOnTheMask() {
        val bars = List(10) { index -> 2 + index * 12 }
        val mask = barMask(width = 140, height = 24, bars = bars, barWidth = 4)
        val gap = FontShapeEstimator.stemGapPx(
            mask,
            140,
            24,
            listOf(TextLineSpan(0, 0, 132, 24)),
            sizePx = 20f,
        )
        assertEquals(8f, gap!!, 0.01f)
    }

    @Test
    fun tightlySetTextGivesNoTrackingEvidence() {
        val bars = List(10) { index -> 2 + index * 6 }
        val mask = barMask(width = 140, height = 24, bars = bars, barWidth = 5)
        assertNull(
            FontShapeEstimator.stemGapPx(
                mask,
                140,
                24,
                listOf(TextLineSpan(0, 0, 132, 24)),
                sizePx = 20f,
            ),
        )
    }

    @Test
    fun wordGapsAreNotCountedAsTracking() {
        val bars = listOf(0, 13, 26, 39, 72, 85, 98, 111, 124)
        val mask = barMask(width = 140, height = 24, bars = bars, barWidth = 6)
        val gap = FontShapeEstimator.stemGapPx(
            mask,
            140,
            24,
            listOf(TextLineSpan(0, 0, 132, 24)),
            sizePx = 20f,
        )
        assertEquals(7f, gap!!, 0.01f)
    }

    @Test
    fun smallTypeIsTooCoarseToMeasure() {
        val bars = List(10) { index -> 2 + index * 5 }
        val mask = barMask(width = 140, height = 10, bars = bars, barWidth = 3)
        assertNull(
            FontShapeEstimator.stemGapPx(
                mask,
                140,
                10,
                listOf(TextLineSpan(0, 0, 52, 10)),
                sizePx = 8f,
            ),
        )
    }

    private fun barMask(width: Int, height: Int, bars: List<Int>, barWidth: Int): BooleanArray {
        val mask = BooleanArray(width * height)
        bars.forEach { start ->
            for (y in 0 until height) {
                val row = y * width
                for (offset in 0 until barWidth) {
                    val x = start + offset
                    if (x in 0 until width) mask[row + x] = true
                }
            }
        }
        return mask
    }

    /** Rows of a «Т»: a wide bar over a narrow stem, sheared as a whole. */
    private fun teeMask(width: Int, height: Int, slantDegrees: Float): BooleanArray {
        val mask = BooleanArray(width * height)
        val center = (height - 1) * 0.5f
        val shear = tan(Math.toRadians(slantDegrees.toDouble()))
        var tee = 6
        while (tee + 16 < width) {
            for (y in 6 until height - 6) {
                val shift = (-shear * (y - center)).roundToInt()
                val bar = y < 12
                val from = if (bar) tee else tee + 6
                val to = if (bar) tee + 15 else tee + 8
                for (x in from..to) {
                    val column = x + shift
                    if (column in 0 until width) mask[y * width + column] = true
                }
            }
            tee += 22
        }
        return mask
    }

    private fun stemMask(
        width: Int,
        height: Int,
        slantDegrees: Float,
        stems: Int = Int.MAX_VALUE,
    ): BooleanArray {
        val mask = BooleanArray(width * height)
        val center = (height - 1) * 0.5f
        val shear = tan(Math.toRadians(slantDegrees.toDouble()))
        var stem = 8
        var drawn = 0
        while (stem < width - 4 && drawn < stems) {
            for (y in 6 until height - 6) {
                val shift = (-shear * (y - center)).roundToInt()
                for (offset in 0..2) {
                    val x = stem + shift + offset
                    if (x in 0 until width) mask[y * width + x] = true
                }
            }
            stem += 14
            drawn++
        }
        return mask
    }
}
