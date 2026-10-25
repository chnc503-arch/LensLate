package com.lenslate.app.pipeline

import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.TextElementRecord
import com.lenslate.app.model.TextLineRecord
import com.lenslate.app.model.TextRegionRecord
import com.lenslate.app.model.TextSymbolRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaintInkSupportTest {

    private fun region(symbolLefts: List<Int>, symbolWidth: Int): TextRegionRecord {
        val bounds = Bounds2D(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
        val symbols = symbolLefts.map { left ->
            TextSymbolRecord(
                text = "ф",
                bounds = Bounds2D(left.toFloat(), 1f, (left + symbolWidth).toFloat(), 13f),
                polygon = emptyList(),
                confidence = 0.5f,
            )
        }
        val text = "ф".repeat(symbols.size)
        val line = TextLineRecord(
            text = text,
            bounds = bounds,
            polygon = emptyList(),
            angleDegrees = 0f,
            confidence = 0.5f,
            elements = listOf(
                TextElementRecord(
                    text = text,
                    bounds = bounds,
                    polygon = emptyList(),
                    confidence = 0.5f,
                    symbols = symbols,
                ),
            ),
        )
        return TextRegionRecord(
            id = 1,
            text = text,
            bounds = bounds,
            polygon = emptyList(),
            script = "Cyrillic",
            readingOrder = 0,
            confidence = 0.5f,
            overallConfidence = 0.5f,
            lines = listOf(line),
        )
    }

    private fun patch(ink: (Int, Int) -> Boolean): IntArray {
        val pixels = IntArray(WIDTH * HEIGHT) { BACKGROUND }
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                if (ink(x, y)) pixels[y * WIDTH + x] = FAINT_INK
            }
        }
        return pixels
    }

    private fun hasSupport(
        pixels: IntArray,
        region: TextRegionRecord,
        maximum: Float = pixels.maxOf { FaintInkSupport.distanceSq(it, BACKGROUND) },
    ): Boolean = FaintInkSupport.hasSupport(
        region = region,
        maximum = maximum,
        pixels = pixels,
        width = WIDTH,
        height = HEIGHT,
        offsetX = 0,
        offsetY = 0,
        background = BACKGROUND,
        glyphCount = region.text.length,
    )

    @Test
    fun acceptsFaintGlyphRows() {
        val pixels = patch { x, y -> y in 3..10 && GLYPH_BARS.any { x - it in 0..1 } }
        assertTrue(hasSupport(pixels, region(GLYPH_BARS, symbolWidth = 4)))
    }

    @Test
    fun rejectsFlatFaintField() {
        val pixels = IntArray(WIDTH * HEIGHT) { FAINT_INK }
        assertFalse(hasSupport(pixels, region(GLYPH_BARS, symbolWidth = 4)))
    }

    @Test
    fun rejectsSolidFaintBar() {
        // One long run per row is a logo or a filled panel, not text.
        val pixels = patch { x, y -> y in 3..10 && x in 9..38 }
        assertFalse(hasSupport(pixels, region(listOf(4, 14, 24, 34), symbolWidth = 10)))
    }

    @Test
    fun rejectsThinShadowLine() {
        // A single-row edge from a shadow cannot belong to a text line.
        val pixels = patch { _, y -> y == 7 }
        assertFalse(hasSupport(pixels, region(GLYPH_BARS, symbolWidth = 4)))
    }

    private companion object {
        const val WIDTH = 48
        const val HEIGHT = 14
        const val BACKGROUND = 0xFFF7F2E6.toInt()
        const val FAINT_INK = 0xFFE5E2D8.toInt()
        val GLYPH_BARS = listOf(4, 12, 20, 28, 36)
    }
}
