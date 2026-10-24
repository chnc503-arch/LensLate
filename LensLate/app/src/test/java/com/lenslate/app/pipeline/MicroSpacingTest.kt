package com.lenslate.app.pipeline

import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.Point2D
import com.lenslate.app.model.TextElementRecord
import com.lenslate.app.model.TextLineRecord
import com.lenslate.app.model.TextRegionRecord
import com.lenslate.app.model.TextSymbolRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class MicroSpacingTest {

    private fun symbol(text: String, left: Float, right: Float): TextSymbolRecord {
        val bounds = Bounds2D(left, 0f, right, 20f)
        return TextSymbolRecord(
            text = text,
            bounds = bounds,
            polygon = listOf(
                Point2D(left, 0f),
                Point2D(right, 0f),
                Point2D(right, 20f),
                Point2D(left, 20f),
            ),
            confidence = 0.9f,
        )
    }

    private fun region(vararg symbols: TextSymbolRecord): TextRegionRecord {
        val element = TextElementRecord(
            text = symbols.joinToString("") { it.text },
            bounds = Bounds2D(0f, 0f, 60f, 20f),
            polygon = emptyList(),
            confidence = 0.9f,
            symbols = symbols.toList(),
        )
        val line = TextLineRecord(
            text = element.text,
            bounds = element.bounds,
            polygon = emptyList(),
            angleDegrees = 0f,
            confidence = 0.9f,
            elements = listOf(element),
        )
        return TextRegionRecord(
            id = 1,
            text = element.text,
            bounds = Bounds2D(0f, 0f, 60f, 20f),
            polygon = emptyList(),
            script = "Cyrillic",
            readingOrder = 0,
            confidence = 0.9f,
            overallConfidence = 0.9f,
            lines = listOf(line),
        )
    }

    @Test
    fun looseGapAfterOpeningBracketIsTransferred() {
        val source = region(
            symbol("(", 0f, 8f),
            symbol("b", 10.4f, 22f),
            symbol(")", 23f, 31f),
        )
        assertEquals("(\u200Ab)", MicroSpacing.transfer(source, "(b)"))
    }

    @Test
    fun tightBracketStaysTight() {
        val source = region(
            symbol("(", 0f, 8f),
            symbol("b", 8f, 20f),
            symbol(")", 20f, 28f),
        )
        assertEquals("(b)", MicroSpacing.transfer(source, "(b)"))
    }

    @Test
    fun looseGapBeforeClosingBracketIsTransferred() {
        val source = region(
            symbol("(", 0f, 8f),
            symbol("b", 8f, 20f),
            symbol(")", 22.4f, 30f),
        )
        assertEquals("(b\u200A)", MicroSpacing.transfer(source, "(b)"))
    }

    @Test
    fun realSpaceIsNeverRemovedOrDuplicated() {
        val source = region(
            symbol("(", 0f, 8f),
            symbol("b", 8f, 20f),
        )
        assertEquals("( b)", MicroSpacing.transfer(source, "( b)"))
    }

    @Test
    fun ordinaryLettersAreNotSpaced() {
        val source = region(
            symbol("П", 0f, 12f),
            symbol("р", 13f, 24f),
            symbol("и", 25f, 35f),
        )
        assertEquals("Привет", MicroSpacing.transfer(source, "Привет"))
    }

    @Test
    fun patternsAreDetectedOnlyInsideWords() {
        val first = TextElementRecord(
            text = "(b)",
            bounds = Bounds2D(0f, 0f, 30f, 20f),
            polygon = emptyList(),
            confidence = 0.9f,
            symbols = listOf(
                symbol("(", 0f, 8f),
                symbol("b", 10f, 22f),
                symbol(")", 23f, 31f),
            ),
        )
        val second = TextElementRecord(
            text = "текст",
            bounds = Bounds2D(40f, 0f, 80f, 20f),
            polygon = emptyList(),
            confidence = 0.9f,
            symbols = listOf(symbol("т", 40f, 50f)),
        )
        val line = TextLineRecord(
            text = "(b) текст",
            bounds = Bounds2D(0f, 0f, 80f, 20f),
            polygon = emptyList(),
            angleDegrees = 0f,
            confidence = 0.9f,
            elements = listOf(first, second),
        )
        val source = region().copy(lines = listOf(line), text = "(b) текст")
        // Only the bracket pair inside the first word contributes; the gap
        // between words is a real space and cannot create a pattern.
        assertEquals("(\u200Ab) текст", MicroSpacing.transfer(source, "(b) текст"))
    }
}
