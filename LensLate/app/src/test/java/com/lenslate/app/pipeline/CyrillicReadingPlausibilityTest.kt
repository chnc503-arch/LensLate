package com.lenslate.app.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CyrillicReadingPlausibilityTest {

    @Test
    fun scoresRealRussianHigh() {
        assertTrue(CyrillicReadingPlausibility.score("ПОЛНАЯ ТЕМНОТА") > 0.9f)
        assertTrue(CyrillicReadingPlausibility.score("акустика, свет и тишина") > 0.9f)
    }

    @Test
    fun penalizesInventedConsonantClusters() {
        val real = CyrillicReadingPlausibility.score("вход строго по спискам")
        val invented = CyrillicReadingPlausibility.score("вход строго ПО СПЦСКЭМ")
        assertTrue("invented acronym must rank below the real word", invented < real - 0.2f)
    }

    @Test
    fun penalizesLongDigitStringsAgainstWords() {
        val word = CyrillicReadingPlausibility.score("акустика ' свет ' тишина")
        val digits = CyrillicReadingPlausibility.score("акустика ' 088771 ' тишина")
        assertTrue(word > digits)
    }

    @Test
    fun penalizesMixedAlphabetWords() {
        val mixed = CyrillicReadingPlausibility.tokenScore("cлoвo")
        assertTrue(mixed < CyrillicReadingPlausibility.tokenScore("слово"))
    }

    @Test
    fun keepsShortPrepositionsAndNumbersReadable() {
        assertTrue(CyrillicReadingPlausibility.tokenScore("в") > 0.8f)
        assertTrue(CyrillicReadingPlausibility.tokenScore("12") > 0.6f)
        assertTrue(CyrillicReadingPlausibility.score("12 ОКТЯБРЯ - 20:00") > 0.8f)
    }

    @Test
    fun doesNotRewardPureGarbage() {
        assertTrue(CyrillicReadingPlausibility.score("№: :/:") < 0.2f)
        assertTrue(CyrillicReadingPlausibility.score("") == 0f)
    }

    @Test
    fun keepsIsolatedLatinBrandWordsUsable() {
        assertTrue(CyrillicReadingPlausibility.score("LensLate перевод") > 0.5f)
    }

    @Test
    fun acceptsRealLinesAndShortLabels() {
        assertTrue(CyrillicReadingPlausibility.isUsableReading("ПОЛНАЯ ТЕМНОТА"))
        assertTrue(CyrillicReadingPlausibility.isUsableReading("12 ОКТЯБРЯ - 20:00"))
        assertTrue(CyrillicReadingPlausibility.isUsableReading("код A-17"))
        assertTrue(CyrillicReadingPlausibility.isUsableReading("ТИХИЙ БАР · СТОЙКА 3"))
    }

    @Test
    fun rejectsFragmentsAndPunctuationNoise() {
        assertFalse(CyrillicReadingPlausibility.isUsableReading("ЕП"))
        assertFalse(CyrillicReadingPlausibility.isUsableReading("и"))
        assertFalse(CyrillicReadingPlausibility.isUsableReading("Од"))
        assertFalse(CyrillicReadingPlausibility.isUsableReading("Чп“"))
        assertFalse(CyrillicReadingPlausibility.isUsableReading("5$“"))
        assertFalse(CyrillicReadingPlausibility.isUsableReading("___./ЧЕМНОТЩ"))
        assertFalse(
            CyrillicReadingPlausibility.isUsableReading("Б“,\"/А/'   |/к?/}./_а;/5-/дні—&‘./\"‘"),
        )
    }
}
