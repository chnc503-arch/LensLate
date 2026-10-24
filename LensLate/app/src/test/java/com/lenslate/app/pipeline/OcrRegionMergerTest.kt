package com.lenslate.app.pipeline

import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.Point2D
import com.lenslate.app.model.TextRegionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Юнит-тесты слияния регионов OCR (подавление дублей, упорядочение, приоритет цифр). */
class OcrRegionMergerTest {

    private fun region(
        id: Int,
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): TextRegionRecord {
        val polygon = listOf(
            Point2D(left, top),
            Point2D(right, top),
            Point2D(right, bottom),
            Point2D(left, bottom),
        )
        return TextRegionRecord(
            id = id,
            text = text,
            bounds = Bounds2D(left, top, right, bottom),
            polygon = polygon,
            script = "Latin",
            readingOrder = id - 1,
            confidence = null,
            overallConfidence = null,
            lines = emptyList(),
        )
    }

    private fun candidate(passName: String, passScore: Float, region: TextRegionRecord) =
        OcrRegionCandidate(passName, passScore, region)

    @Test
    fun emptyCandidatesYieldEmptyResult() {
        val result = OcrRegionMerger().merge(emptyList())
        assertEquals(0, result.regions.size)
        assertTrue(result.contributingPassNames.isEmpty())
    }

    @Test
    fun overlappingSimilarTextMergesToOne() {
        val a = candidate("original", 0.5f, region(1, "Hello world", 0f, 0f, 100f, 50f))
        val b = candidate("variant", 0.5f, region(2, "Hello world", 5f, 5f, 105f, 55f))
        val result = OcrRegionMerger().merge(listOf(a, b))
        assertEquals(1, result.regions.size)
    }

    @Test
    fun separateRegionsAreKeptAndOrderedByTop() {
        val lower = candidate("original", 0.5f, region(1, "Second", 0f, 100f, 50f, 150f))
        val upper = candidate("original", 0.5f, region(2, "First", 0f, 0f, 50f, 50f))
        val result = OcrRegionMerger().merge(listOf(lower, upper))
        assertEquals(2, result.regions.size)
        assertEquals("First", result.regions[0].text)
        assertEquals("Second", result.regions[1].text)
        assertEquals(1, result.regions[0].id)
        assertEquals(2, result.regions[1].id)
    }

    @Test
    fun longerDigitReadingWinsWhenQualityClose() {
        val a = candidate("original", 0.5f, region(1, "2024", 0f, 0f, 100f, 50f))
        val b = candidate("variant", 0.5f, region(2, "2024 год", 5f, 5f, 105f, 55f))
        val result = OcrRegionMerger().merge(listOf(a, b))
        assertEquals(1, result.regions.size)
        assertEquals("2024 год", result.regions[0].text)
    }
}
