package com.lenslate.app.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphStructureAnalyzerTest {

    private fun analyze(rows: List<String>): GlyphStructureStats {
        val height = rows.size
        val width = rows.maxOf { it.length }
        val ink = BooleanArray(width * height)
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, character ->
                if (character == '#') ink[y * width + x] = true
            }
        }
        return GlyphStructureAnalyzer.analyze(ink, width, height)
    }

    @Test
    fun lineOfSeparateStrokesIsText() {
        val stats = analyze(
            listOf(
                "##..##..##..##..##",
                "##..##..##..##..##",
                "##..##..##..##..##",
                "##..##..##..##..##",
                "##..##..##..##..##",
                "##..##..##..##..##",
            ),
        )

        assertFalse(GlyphStructureAnalyzer.looksLikeSolidGraphic(stats, glyphCount = 5))
    }

    @Test
    fun solidBlockIsGraphic() {
        val stats = analyze(List(8) { "#########" })

        assertTrue(GlyphStructureAnalyzer.looksLikeSolidGraphic(stats, glyphCount = 5))
    }

    @Test
    fun blobWithOneGapStillLooksGraphic() {
        val stats = analyze(
            listOf(
                "#########",
                "####.####",
                "####.####",
                "#########",
                "#########",
                "#########",
            ),
        )

        assertTrue(GlyphStructureAnalyzer.looksLikeSolidGraphic(stats, glyphCount = 4))
    }

    @Test
    fun shortLabelsAreNotJudgedByStructure() {
        val stats = analyze(List(8) { "####" })

        assertFalse(GlyphStructureAnalyzer.looksLikeSolidGraphic(stats, glyphCount = 2))
    }

    @Test
    fun emptyMaskHasNoStructure() {
        val stats = analyze(List(4) { "...." })

        assertFalse(GlyphStructureAnalyzer.looksLikeSolidGraphic(stats, glyphCount = 6))
    }
}
