package com.lenslate.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun rightLeaningStemsProduceNegativeSlant() {
        val mask = stemMask(width = 160, height = 40, slantDegrees = 12f)
        val slant = FontShapeEstimator.slantDegrees(
            mask,
            160,
            40,
            listOf(TextLineSpan(0, 0, 160, 40)),
        )
        assertEquals(-12f, slant, 1.5f)
    }

    @Test
    fun leftLeaningStemsProducePositiveSlant() {
        val mask = stemMask(width = 160, height = 40, slantDegrees = -11f)
        val slant = FontShapeEstimator.slantDegrees(
            mask,
            160,
            40,
            listOf(TextLineSpan(0, 0, 160, 40)),
        )
        assertEquals(11f, slant, 1.5f)
    }

    @Test
    fun tinyMasksAreNotMeasured() {
        val mask = BooleanArray(100) { true }
        assertEquals(0f, FontShapeEstimator.slantDegrees(mask, 10, 10, listOf(TextLineSpan(0, 0, 10, 10))))
    }

    private fun stemMask(width: Int, height: Int, slantDegrees: Float): BooleanArray {
        val mask = BooleanArray(width * height)
        val center = (height - 1) * 0.5f
        val shear = tan(Math.toRadians(slantDegrees.toDouble()))
        var stem = 8
        while (stem < width - 4) {
            for (y in 6 until height - 6) {
                val shift = (-shear * (y - center)).roundToInt()
                for (offset in 0..2) {
                    val x = stem + shift + offset
                    if (x in 0 until width) mask[y * width + x] = true
                }
            }
            stem += 14
        }
        return mask
    }
}
