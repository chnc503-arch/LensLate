package com.lenslate.app.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calibration of the offline Russian model behind the lab's lexical deep pass. */
class RussianLanguageModelTest {

    @Test
    fun commonWordsAreKnown() {
        listOf("подложки", "яркости", "акустика", "октября", "темнота").forEach { word ->
            assertTrue("должно быть известно: $word", RussianLanguageModel.isKnownWord(word))
        }
    }

    @Test
    fun printConfusionNearMissesAreSuspect() {
        assertTrue(RussianLanguageModel.isSuspectReading("ПОДЛОЖКИЁ"))
        assertTrue(RussianLanguageModel.isSuspectReading("ЯРКАСТИ"))
        assertTrue(RussianLanguageModel.isSuspectReading("ЧЕМНОТЩ"))
    }

    @Test
    fun cleanSentenceIsNotSuspect() {
        assertFalse(RussianLanguageModel.isSuspectReading("ПОЛНАЯ ТЕМНОТА"))
        assertFalse(RussianLanguageModel.isSuspectReading("ОДИН ВЕЧЕР · ОДНА СЦЕНА"))
        assertTrue(RussianLanguageModel.lexicalScore("ПОЛНАЯ ТЕМНОТА") > 0.9f)
    }

    @Test
    fun garbleScoresBelowCleanText() {
        val clean = RussianLanguageModel.lexicalScore("ПОЛНАЯ ТЕМНОТА")
        val garble = RussianLanguageModel.lexicalScore("ЧЕМНОТЩ")
        println("lexical clean=$clean garble=$garble")
        assertTrue(garble < clean - 0.3f)
    }

    @Test
    fun validUnknownWordsKeepPositiveQuality() {
        listOf("типографика", "акциденция").forEach { word ->
            val quality = RussianLanguageModel.wordQuality(word)
            println(
                "unknown=$word quality=$quality known=${RussianLanguageModel.isKnownWord(word)} " +
                    "suspect=${RussianLanguageModel.isSuspectWord(word)}",
            )
            assertFalse("не должно быть в лексиконе: $word", RussianLanguageModel.isKnownWord(word))
            assertTrue("не должно обнуляться: $word", quality > 0.15f)
        }
    }
}
