package com.lenslate.app.pipeline

import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.TextRegionRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aggregate quality used by the automated lab stage to compare OCR hypotheses. */
class SmartScanQualityTest {

    private val imageArea = 1000f * 1000f

    private fun region(text: String, confidence: Float = 0.8f, protected: Boolean = false) =
        TextRegionRecord(
            id = 0,
            text = text,
            bounds = Bounds2D(0f, 0f, 200f, 40f),
            polygon = emptyList(),
            script = "Cyrillic",
            readingOrder = 0,
            confidence = confidence,
            overallConfidence = confidence,
            lines = emptyList(),
            isProtected = protected,
        )

    @Test
    fun emptyDocumentScoresZeroAndNeedsDeeperPass() {
        assertTrue(SmartScanQuality.score(emptyList(), imageArea) == 0f)
        assertTrue(SmartScanQuality.needsDeeperPass(0f, emptyList()))
    }

    @Test
    fun cleanReadingBeatsJunkHeavyReading() {
        val clean = listOf(
            region("ПОЛНАЯ ТЕМНОТА"),
            region("12 ОКТЯБРЯ · 20:00"),
            region("акустика · свет · тишина"),
        )
        val junk = clean + listOf(
            region("ЕП", confidence = 0.9f),
            region("___./ЧЕМНОТЩ", confidence = 0.9f),
            region("5$\"", confidence = 0.85f),
        )
        val cleanScore = SmartScanQuality.score(clean, imageArea)
        val junkScore = SmartScanQuality.score(junk, imageArea)
        assertTrue(cleanScore > junkScore)
        assertTrue(cleanScore > 0.5f)
    }

    @Test
    fun junkShareTriggersDeeperPassEvenWhenScoreLooksFine() {
        val mostlyJunk = listOf(
            region("ОДИН ВЕЧЕР · ОДНА СЦЕНА", confidence = 0.95f),
            region("ЕП", confidence = 0.95f),
            region("и", confidence = 0.95f),
            region("Од", confidence = 0.95f),
        )
        val score = SmartScanQuality.score(mostlyJunk, imageArea)
        assertTrue(SmartScanQuality.needsDeeperPass(score, mostlyJunk))
    }

    @Test
    fun cleanDocumentDoesNotAskForDeeperPass() {
        val clean = listOf(
            region("СЕЗОН ОТКРЫТЫХ ДВЕРЕЙ"),
            region("Программа на сентябрь"),
            region("ЦЕНА 2 400 ₽, код А-17"),
        )
        val score = SmartScanQuality.score(clean, imageArea)
        assertFalse(SmartScanQuality.needsDeeperPass(score, clean))
    }

    @Test
    fun protectedRegionsDoNotDiluteQuality() {
        val clean = listOf(
            region("ПОЛНАЯ ТЕМНОТА"),
            region("ОДИН ВЕЧЕР · ОДНА СЦЕНА"),
        )
        val withProtected = clean + region("random photo caption", protected = true)
        val score = SmartScanQuality.score(clean, imageArea)
        val protectedScore = SmartScanQuality.score(withProtected, imageArea)
        assertTrue(kotlin.math.abs(score - protectedScore) < 0.0001f)
    }

    @Test
    fun lexicalScoringFlagsInventedWordsThatStructureAccepts() {
        val clean = listOf(
            region("ПОЛНАЯ ТЕМНОТА"),
            region("ЗВУК И СВЕТ"),
        )
        val garbled = listOf(
            region("ПОЛНАЯ ТЕМНОТА"),
            region("ЧЕМНОТЩ"),
        )
        val lexicalClean = SmartScanQuality.score(clean, imageArea, lexical = true)
        val lexicalGarbled = SmartScanQuality.score(garbled, imageArea, lexical = true)
        println("lexical clean=$lexicalClean garbled=$lexicalGarbled")
        assertTrue(lexicalClean > lexicalGarbled)
        assertTrue(SmartScanQuality.needsDeeperPass(lexicalGarbled, garbled, lexical = true))
        assertFalse(SmartScanQuality.needsDeeperPass(lexicalClean, clean, lexical = true))
    }
}
