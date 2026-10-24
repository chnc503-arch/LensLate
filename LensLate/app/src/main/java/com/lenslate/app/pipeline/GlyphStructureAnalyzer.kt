package com.lenslate.app.pipeline

import kotlin.math.max
import kotlin.math.min

internal data class GlyphStructureStats(
    val inkRows: Int,
    val medianRunsPerRow: Float,
    val multiRunRowShare: Float,
)

/**
 * Separates a line of glyphs from a solid logo/object silhouette.
 *
 * A row through real text crosses several narrow strokes; a filled logo or a
 * single blob produces one long run. Counting runs per row is cheap and does not
 * depend on the OCR engine, so it works for Cyrillic, Latin and Japanese alike.
 */
internal object GlyphStructureAnalyzer {

    fun analyze(ink: BooleanArray, width: Int, height: Int): GlyphStructureStats {
        if (width < 3 || height < 3 || ink.size < width * height) {
            return GlyphStructureStats(0, 0f, 0f)
        }
        val histogram = IntArray(MAX_RUNS + 1)
        var inkRows = 0
        var multiRunRows = 0
        for (y in 0 until height) {
            val row = y * width
            var runs = 0
            var insideRun = false
            for (x in 0 until width) {
                if (ink[row + x]) {
                    if (!insideRun) {
                        runs++
                        insideRun = true
                    }
                } else {
                    insideRun = false
                }
            }
            if (runs == 0) continue
            inkRows++
            if (runs >= MULTI_RUN_MIN) multiRunRows++
            histogram[min(runs, MAX_RUNS)]++
        }
        if (inkRows == 0) return GlyphStructureStats(0, 0f, 0f)
        var accumulated = 0
        var medianRuns = 0
        val halfway = inkRows / 2
        for (runs in 0..MAX_RUNS) {
            accumulated += histogram[runs]
            if (accumulated > halfway) {
                medianRuns = runs
                break
            }
        }
        return GlyphStructureStats(
            inkRows = inkRows,
            medianRunsPerRow = medianRuns.toFloat(),
            multiRunRowShare = multiRunRows.toFloat() / inkRows,
        )
    }

    fun looksLikeSolidGraphic(stats: GlyphStructureStats, glyphCount: Int): Boolean {
        if (glyphCount < MIN_GLYPHS_FOR_STRUCTURE) return false
        if (stats.inkRows < MIN_INK_ROWS) return false
        val requiredMedian = max(
            MIN_REQUIRED_MEDIAN_RUNS,
            min(MAX_REQUIRED_MEDIAN_RUNS, glyphCount * RUNS_PER_GLYPH),
        )
        return stats.medianRunsPerRow <= requiredMedian &&
            stats.multiRunRowShare < MIN_MULTI_RUN_ROW_SHARE
    }

    private const val MAX_RUNS = 64
    private const val MULTI_RUN_MIN = 3
    private const val MIN_GLYPHS_FOR_STRUCTURE = 3
    private const val MIN_INK_ROWS = 4
    private const val MIN_REQUIRED_MEDIAN_RUNS = 2.0f
    private const val MAX_REQUIRED_MEDIAN_RUNS = 2.6f
    private const val RUNS_PER_GLYPH = 0.34f
    private const val MIN_MULTI_RUN_ROW_SHARE = 0.10f
}
