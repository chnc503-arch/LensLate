package com.lenslate.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A poster headline and the caption under it can share one OCR block. The
 * block then gets one style and one sentence, so the display line is drawn at
 * caption size. Grouping decides where a block has to be cut.
 */
class OcrRegionSplitterTest {

    private val grey = 0xFF101010.toInt()
    private val pink = 0xFFE04070.toInt()

    @Test
    fun keepsLinesOfOneSizeAndInkTogether() {
        val groups = OcrRegionSplitter.groupLines(
            listOf(30f, 31f, 29f),
            listOf(grey, grey, null),
        )
        assertEquals(listOf(listOf(0, 1, 2)), groups)
    }

    @Test
    fun cutsADisplayLineAwayFromTheBodyUnderIt() {
        val groups = OcrRegionSplitter.groupLines(
            listOf(110f, 34f, 30f),
            listOf(grey, grey, grey),
        )
        assertEquals(listOf(listOf(0), listOf(1, 2)), groups)
    }

    @Test
    fun cutsEveryStyleChangeInsideOneBlock() {
        val groups = OcrRegionSplitter.groupLines(
            listOf(110f, 34f, 26f),
            listOf(grey, grey, grey),
        )
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), groups)
    }

    @Test
    fun splitsWhereTheInkChangesEvenAtOneSize() {
        val groups = OcrRegionSplitter.groupLines(
            listOf(30f, 30f),
            listOf(grey, pink),
        )
        assertEquals(listOf(listOf(0), listOf(1)), groups)
    }

    @Test
    fun ignoresSizeJumpsOnSubGlyphNoise() {
        val groups = OcrRegionSplitter.groupLines(
            listOf(30f, 8f, 30f),
            listOf(grey, grey, grey),
        )
        assertEquals(listOf(listOf(0, 1, 2)), groups)
    }
}
