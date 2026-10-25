package com.lenslate.app.pipeline

import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.TextAppearanceEstimate
import com.lenslate.app.model.TextLineRecord
import com.lenslate.app.model.TextRegionRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanTranslationPolicyTest {

    @Test
    fun keepsLowConfidenceTextSoItStillGetsTranslated() {
        val regions = listOf(
            region(id = 0, text = "ВХОД", confidence = 0.42f),
            region(id = 1, text = "ПО РЕГИСТРАЦИИ", confidence = null),
            region(id = 2, text = "Круглосуточно", confidence = 0.31f),
        )

        assertEquals(
            listOf(0, 1, 2),
            ScanTranslationPolicy.selectableIndices(regions),
        )
    }

    @Test
    fun skipsBlankFormulaAndProtectedBlocks() {
        val regions = listOf(
            region(id = 0, text = "   "),
            region(id = 1, text = "E = mc^2", formula = true),
            region(id = 2, text = "LENS LATE", protected = true),
            region(id = 3, text = "Открыто 24 часа"),
        )

        assertEquals(
            listOf(3),
            ScanTranslationPolicy.selectableIndices(regions),
        )
    }

    @Test
    fun dropsReadingThatCannotPhysicallyFitItsOwnBox() {
        val regions = listOf(
            // Ten characters cannot stand in 26 px of a 30,8 pt line.
            region(
                id = 0,
                text = "Начало про",
                bounds = Bounds2D(224f, 739f, 250f, 767f),
                size = 30.8f,
            ),
            region(
                id = 1,
                text = "ЛЕ цій",
                bounds = Bounds2D(631f, 1573f, 691f, 1600f),
                size = 36.8f,
            ),
        )

        assertEquals(emptyList<Int>(), ScanTranslationPolicy.selectableIndices(regions))
    }

    @Test
    fun dropsMultiLineRegionWhoseLinesOverflowTheirBoxes() {
        val regions = listOf(
            TextRegionRecord(
                id = 0,
                text = "ШТ ЁЛ:1- Ш т.г'зШз‘ч/из\nЁЁШФШ/‹Эіщдк © нанят-пиши",
                bounds = Bounds2D(116f, 910f, 610f, 1136f),
                polygon = emptyList(),
                script = null,
                readingOrder = 0,
                confidence = 0.61f,
                overallConfidence = 0.61f,
                lines = listOf(
                    line("ШТ ЁЛ:1- Ш т.г'зШз‘ч/из", 116f, 610f),
                    line("ЁЁШФШ/‹Эіщдк © нанят-пиши", 116f, 610f),
                ),
                appearance = appearance(size = 92.3f),
            ),
        )

        assertEquals(emptyList<Int>(), ScanTranslationPolicy.selectableIndices(regions))
    }

    @Test
    fun keepsParagraphWithOneMismatchedRowBox() {
        // A refiner can leave a single row holding another row's narrow box.
        // The other four rows describe the block, so the reading stands.
        val rows = listOf(
            "Проверка идёт по всей строке: оттенок медленно уходит из" to 856f,
            "холодного серого в тёплый уголь." to 483f,
            "Если перевод потеряет этот сдвиг, он станет заметно светлее" to 180f,
            "или темнее оригинала." to 325f,
            "Бар на втором этаже, стойка три, вход со двора." to 696f,
        )
        val regions = listOf(
            TextRegionRecord(
                id = 0,
                text = rows.joinToString("\n") { it.first },
                bounds = Bounds2D(103f, 947f, 1005.5f, 1156.3f),
                polygon = emptyList(),
                script = null,
                readingOrder = 0,
                confidence = 0.78f,
                overallConfidence = 0.78f,
                lines = rows.map { (text, width) -> line(text, 105f, 105f + width) },
                appearance = appearance(size = 33.136f),
            ),
        )

        assertEquals(listOf(0), ScanTranslationPolicy.selectableIndices(regions))
    }

    @Test
    fun dropsParagraphWhoseRowsMostlyOverflow() {
        val rows = listOf(
            "Проверка идёт по всей строке: оттенок медленно уходит из" to 180f,
            "холодного серого в тёплый уголь." to 120f,
            "Если перевод потеряет этот сдвиг, он станет заметно светлее" to 180f,
            "или темнее оригинала." to 325f,
            "Бар на втором этаже, стойка три, вход со двора." to 696f,
        )
        val regions = listOf(
            TextRegionRecord(
                id = 0,
                text = rows.joinToString("\n") { it.first },
                bounds = Bounds2D(103f, 947f, 1005.5f, 1156.3f),
                polygon = emptyList(),
                script = null,
                readingOrder = 0,
                confidence = 0.78f,
                overallConfidence = 0.78f,
                lines = rows.map { (text, width) -> line(text, 105f, 105f + width) },
                appearance = appearance(size = 33.136f),
            ),
        )

        assertEquals(emptyList<Int>(), ScanTranslationPolicy.selectableIndices(regions))
    }

    @Test
    fun keepsParagraphWhoseLineCarriesEveryRow() {
        // Merging a paragraph can leave one line record holding all of its
        // rows. The box only ever has to hold the widest row.
        val paragraph = "Проверка идёт по всей строке: оттенок медленно уходит из\n" +
            "холодного серого в тёплый уголь.\n" +
            "Если перевод потеряет этот сдвиг, он станет заметно светлее\n" +
            "или темнее оригинала.\n" +
            "Бар на втором этаже, стойка три, вход со двора."
        val regions = listOf(
            TextRegionRecord(
                id = 0,
                text = paragraph,
                bounds = Bounds2D(103f, 947f, 1005.5f, 1156.3f),
                polygon = emptyList(),
                script = null,
                readingOrder = 0,
                confidence = 0.78f,
                overallConfidence = 0.78f,
                lines = listOf(line(paragraph, 103f, 1005.5f)),
                appearance = appearance(size = 33.136f),
            ),
        )

        assertEquals(listOf(0), ScanTranslationPolicy.selectableIndices(regions))
    }

    @Test
    fun keepsTightButPossibleReadings() {
        val regions = listOf(
            region(
                id = 0,
                text = "код А- 17",
                bounds = Bounds2D(820f, 1072f, 929f, 1092f),
                size = 26f,
            ),
            region(
                id = 1,
                text = "вход строго по спискам",
                bounds = Bounds2D(84f, 366f, 396f, 391f),
                size = 28.4f,
            ),
            region(
                id = 2,
                text = "приходите заранее: вход закроют за десять минут",
                bounds = Bounds2D(112f, 1290f, 775f, 1311f),
                size = 29.6f,
            ),
        )

        assertEquals(listOf(0, 1, 2), ScanTranslationPolicy.selectableIndices(regions))
    }

    private fun region(
        id: Int,
        text: String,
        confidence: Float? = 0.9f,
        formula: Boolean = false,
        protected: Boolean = false,
        bounds: Bounds2D = Bounds2D(0f, 0f, 100f, 20f),
        size: Float? = null,
    ) = TextRegionRecord(
        id = id,
        text = text,
        bounds = bounds,
        polygon = emptyList(),
        script = null,
        readingOrder = id,
        confidence = confidence,
        overallConfidence = confidence,
        lines = listOf(line(text, bounds.left, bounds.right)),
        appearance = appearance(size),
        isFormula = formula,
        isProtected = protected,
    )

    private fun line(text: String, left: Float, right: Float) = TextLineRecord(
        text = text,
        bounds = Bounds2D(left, 0f, right, 20f),
        polygon = emptyList(),
        angleDegrees = 0f,
        confidence = 0.9f,
        elements = emptyList(),
    )

    private fun appearance(size: Float? = null) = TextAppearanceEstimate(
        foregroundArgb = null,
        localBackgroundArgb = null,
        textHeightPx = size,
        weight = null,
        slantDegrees = null,
        alignment = null,
        spacingPx = null,
        lineSpacingPx = null,
        likelyFontClass = null,
        fontClassConfidence = null,
        confidenceByAttribute = emptyMap(),
    )
}
