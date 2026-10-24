package com.lenslate.app.pipeline

import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.Point2D
import com.lenslate.app.model.TextElementRecord
import com.lenslate.app.model.TextLineRecord
import com.lenslate.app.model.TextRegionRecord
import com.lenslate.app.model.TextSymbolRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrRegionSanitizerTest {

    private fun symbol(index: Int, width: Float, height: Float): TextSymbolRecord {
        val left = index * width
        return TextSymbolRecord(
            text = "x",
            bounds = Bounds2D(left, 0f, left + width, height),
            polygon = emptyList(),
            confidence = null,
        )
    }

    private fun glyphRegion(
        text: String,
        widths: List<Float>,
        heights: List<Float> = widths.map { 22f },
        confidence: Float? = 0.5f,
    ): TextRegionRecord {
        val symbols = widths.mapIndexed { index, width ->
            symbol(index, width, heights[index % heights.size])
        }
        val line = TextLineRecord(
            text = text,
            bounds = Bounds2D(0f, 0f, widths.sum(), heights.max()),
            polygon = emptyList(),
            angleDegrees = 0f,
            confidence = confidence,
            elements = listOf(
                TextElementRecord(
                    text = text,
                    bounds = Bounds2D(0f, 0f, widths.sum(), heights.max()),
                    polygon = emptyList(),
                    confidence = confidence,
                    symbols = symbols,
                ),
            ),
        )
        return region(text, confidence).copy(lines = listOf(line))
    }

    private fun region(
        text: String,
        confidence: Float? = 0.6f,
        isFormula: Boolean = false,
    ): TextRegionRecord = TextRegionRecord(
        id = 1,
        text = text,
        bounds = Bounds2D(0f, 0f, 100f, 40f),
        polygon = listOf(
            Point2D(0f, 0f),
            Point2D(100f, 0f),
            Point2D(100f, 40f),
            Point2D(0f, 40f),
        ),
        script = "Cyrillic",
        readingOrder = 0,
        confidence = confidence,
        overallConfidence = confidence,
        lines = emptyList(),
        isFormula = isFormula,
    )

    private fun kept(text: String, confidence: Float? = 0.6f, isFormula: Boolean = false): Boolean =
        OcrRegionSanitizer()
            .sanitizeText(listOf(region(text, confidence, isFormula)))
            .regions
            .isNotEmpty()

    @Test
    fun dropsReadingsWithoutAlphanumerics() {
        val result = OcrRegionSanitizer().sanitizeText(listOf(region("— « » |")))
        assertEquals(0, result.regions.size)
        assertEquals("no-text", result.dropped.single().reason)
    }

    @Test
    fun dropsSymbolHeavyNoise() {
        val result = OcrRegionSanitizer().sanitizeText(listOf(region("¤|¤ 1")))
        assertEquals(0, result.regions.size)
        assertEquals("symbol-noise", result.dropped.single().reason)
    }

    @Test
    fun dropsSingleRepeatedGlyph() {
        val result = OcrRegionSanitizer().sanitizeText(listOf(region("лллл")))
        assertEquals(0, result.regions.size)
        assertEquals("repetition", result.dropped.single().reason)
    }

    @Test
    fun keepsShortLegitimateLabels() {
        assertTrue(kept("(b)"))
        assertTrue(kept("США"))
        assertTrue(kept("2024"))
        assertTrue(kept("№ 4"))
    }

    @Test
    fun keepsFormulaRegardlessOfSymbols() {
        assertTrue(kept("x^2 + y^2 = 4", isFormula = true))
    }

    @Test
    fun dropsAlternatingCaseGibberish() {
        val result = OcrRegionSanitizer()
            .sanitizeText(listOf(region("вЫсОкИй ТеКсТ", confidence = 0.4f)))
        assertEquals(0, result.regions.size)
        assertEquals("case-noise", result.dropped.single().reason)
    }

    @Test
    fun keepsMixedCaseWhenConfidenceIsHigh() {
        assertTrue(kept("вЫсОкИй ТеКсТ", confidence = 0.95f))
    }

    @Test
    fun dropsLookalikeMixedWord() {
        val result = OcrRegionSanitizer()
            .sanitizeText(listOf(region("cлoвo", confidence = 0.4f)))
        assertEquals(0, result.regions.size)
        assertEquals("mixed-script", result.dropped.single().reason)
    }

    @Test
    fun keepsSeparateLatinAndCyrillicWords() {
        assertTrue(kept("Wi-Fi роутер", confidence = 0.55f))
        assertTrue(kept("Python и Java", confidence = 0.55f))
    }

    @Test
    fun neuralProfileKeepsCaseVariation() {
        val result = OcrRegionSanitizer().sanitizeText(
            regions = listOf(region("вЫсОкИй", confidence = 0.4f)),
            profile = OcrSanitizerProfile.NEURAL,
        )
        assertEquals(1, result.regions.size)
    }

    @Test
    fun aggressiveProfileDropsMoreMixedTokens() {
        val text = "cлoвo cлoвo нормальный текст документа"
        val regular = OcrRegionSanitizer().sanitizeText(listOf(region(text, confidence = 0.6f)))
        val aggressive = OcrRegionSanitizer()
            .sanitizeText(listOf(region(text, confidence = 0.6f)), aggressive = true)
        assertEquals(1, regular.regions.size)
        assertEquals(0, aggressive.regions.size)
    }

    @Test
    fun dropsUniformGlyphLogo() {
        val logo = glyphRegion("LensLate", widths = listOf(12f, 12f, 12f, 12f, 12f, 12f, 12f, 12f))
        val result = OcrRegionSanitizer().sanitizeText(listOf(logo))
        assertEquals(0, result.regions.size)
        assertEquals("uniform-glyphs", result.dropped.single().reason)
    }

    @Test
    fun keepsProportionalWordWithRealLetterWidths() {
        val word = glyphRegion(
            "Willow",
            widths = listOf(16f, 7f, 6f, 14f, 13f, 12f),
            heights = listOf(22f, 22f, 22f, 22f, 22f, 22f),
        )
        assertTrue(OcrRegionSanitizer().sanitizeText(listOf(word)).regions.isNotEmpty())
    }

    @Test
    fun keepsUniformDigitTable() {
        val digits = glyphRegion("10245", widths = List(5) { 11f })
        assertTrue(OcrRegionSanitizer().sanitizeText(listOf(digits)).regions.isNotEmpty())
    }

    @Test
    fun keepsConfidentUniformLogoReading() {
        val logo = glyphRegion(
            "LensLate",
            widths = List(8) { 12f },
            confidence = 0.9f,
        )
        assertTrue(OcrRegionSanitizer().sanitizeText(listOf(logo)).regions.isNotEmpty())
    }

    @Test
    fun aggressiveNeuralProfileAlsoDropsUniformLogo() {
        val logo = glyphRegion(
            "LensLate",
            widths = List(8) { 12f },
            confidence = 0.5f,
        )
        val result = OcrRegionSanitizer().sanitizeText(
            regions = listOf(logo),
            profile = OcrSanitizerProfile.NEURAL,
            aggressive = true,
        )
        assertEquals(0, result.regions.size)
        assertEquals("uniform-glyphs", result.dropped.single().reason)
    }
}
