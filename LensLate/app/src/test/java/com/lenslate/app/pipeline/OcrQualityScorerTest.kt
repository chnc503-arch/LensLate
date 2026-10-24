package com.lenslate.app.pipeline

import com.lenslate.app.model.Bounds2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Оценка качества OCR без эталона: уверенность + консенсус + покрытие + язык + вёрстка. */
class OcrQualityScorerTest {

    private val sentence = "Внимание, поезд отправляется через пятнадцать минут."
    private val unrelated = "Срочно требуется ремонт кровли до конца недели."

    private fun candidate(
        text: String,
        confidence: Float? = 0.85f,
        regions: List<OcrRegionMetric> = listOf(
            OcrRegionMetric(text, Bounds2D(0f, 0f, 600f, 40f)),
        ),
    ) = OcrScoreCandidate(text, confidence, regions)

    @Test
    fun emptyTextScoresZero() {
        val breakdown = OcrQualityScorer.evaluate(candidate("", 0.99f), emptyList())
        assertEquals(0f, breakdown.total, 0.0001f)
    }

    @Test
    fun plausibleSentenceBeatsGarbageWithHigherConfidence() {
        val garbage = candidate("ЩЩЩЩ ЬЬЬЬ ЪЪЪЪ", confidence = 0.97f)
        val good = candidate(sentence)
        val goodScore = OcrQualityScorer.evaluate(good, emptyList())
        val garbageScore = OcrQualityScorer.evaluate(garbage, emptyList())
        assertTrue(
            "good=${goodScore.total} garbage=${garbageScore.total}",
            goodScore.total > garbageScore.total,
        )
    }

    @Test
    fun completeReadingCoversMoreThanAFragment() {
        val full = OcrQualityScorer.evaluate(candidate(sentence), emptyList())
        val fragment = OcrQualityScorer.evaluate(candidate("Внимание"), emptyList())
        assertTrue(
            "full=${full.coverage} fragment=${fragment.coverage}",
            full.coverage > fragment.coverage,
        )
    }

    @Test
    fun readingThatMatchesOtherVariantsWinsByConsensus() {
        val peers = listOf(
            candidate("Внимание поезд отправляется через пятнадцать минут", 0.8f),
            candidate("Внимание, поезд отправляется через пятнадцать минут!", 0.82f),
        )
        val matching = OcrQualityScorer.evaluate(candidate(sentence), peers)
        val stray = OcrQualityScorer.evaluate(candidate(unrelated), peers)
        assertTrue(
            "matching=${matching.consensus} stray=${stray.consensus}",
            matching.consensus > stray.consensus,
        )
        assertTrue(matching.total > stray.total)
    }

    @Test
    fun duplicatedOverlappingRegionsLowerTheLayoutScore() {
        val cleanRegions = listOf(
            OcrRegionMetric("первая строка текста", Bounds2D(0f, 0f, 300f, 30f)),
            OcrRegionMetric("вторая строка текста", Bounds2D(0f, 60f, 300f, 90f)),
        )
        val duplicatedRegions = listOf(
            OcrRegionMetric("первая строка текста", Bounds2D(0f, 0f, 300f, 30f)),
            OcrRegionMetric("первая строка текста", Bounds2D(0f, 0f, 300f, 30f)),
        )
        val clean = OcrQualityScorer.evaluate(candidate(sentence, regions = cleanRegions), emptyList())
        val duplicated = OcrQualityScorer.evaluate(candidate(sentence, regions = duplicatedRegions), emptyList())
        assertTrue(
            "clean=${clean.layout} duplicated=${duplicated.layout}",
            clean.layout > duplicated.layout,
        )
    }

    @Test
    fun suspiciousSymbolsRaiseNoise() {
        val clean = OcrQualityScorer.evaluate(candidate("обычный текст без мусора"), emptyList())
        val noisy = OcrQualityScorer.evaluate(candidate("обычный |~^ текст }{ без мусора"), emptyList())
        assertTrue("clean=${clean.noise} noisy=${noisy.noise}", noisy.noise > clean.noise)
    }

    @Test
    fun rankIsAlignedWithTheInputAndPrefersThePlausibleReading() {
        val ranked = OcrQualityScorer.rank(
            listOf(
                candidate("ЩЩЩЩ ЬЬЬЬ ЪЪЪЪ", confidence = 0.97f),
                candidate(sentence),
            ),
        )
        assertEquals(2, ranked.size)
        assertTrue(ranked[1].total > ranked[0].total)
        assertTrue(ranked.all { it.total in 0f..1f })
    }
}
