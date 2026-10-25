package com.lenslate.app.pipeline

import com.lenslate.app.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTextGuardTest {

    @Test
    fun displayLetteringIsRecognisedAsAllCaps() {
        assertTrue(TranslationTextGuard.isAllCaps("ВХОД ПО РЕГИСТРАЦИИ"))
        assertTrue(TranslationTextGuard.isAllCaps("«ТИПОГРАФИЯ»"))
        assertFalse(TranslationTextGuard.isAllCaps("Вход в зал"))
        assertFalse(TranslationTextGuard.isAllCaps("A1"))
    }

    @Test
    fun sentenceCaseKeepsOnlyTheFirstLetterUppercase() {
        assertEquals(
            "Вход по регистрации",
            TranslationTextGuard.sentenceCase("ВХОД ПО РЕГИСТРАЦИИ"),
        )
        assertEquals(
            "Текст. Другой текст",
            TranslationTextGuard.sentenceCase("ТЕКСТ. ДРУГОЙ ТЕКСТ"),
        )
    }

    @Test
    fun acronymsAndCodesSurviveSentenceCasing() {
        assertEquals(
            "ГОСТ 12345-2020 вход",
            TranslationTextGuard.sentenceCase("ГОСТ 12345-2020 ВХОД"),
        )
        assertEquals(
            "Модель ИК-4200",
            TranslationTextGuard.sentenceCase("МОДЕЛЬ ИК-4200"),
        )
        assertEquals(
            "USB порт",
            TranslationTextGuard.sentenceCase("USB ПОРТ"),
        )
    }

    @Test
    fun displayCaseIsRestoredAfterTranslation() {
        assertEquals("THE DARK HALL", TranslationTextGuard.restoreDisplayCase("The dark hall", true))
        assertEquals("The dark hall", TranslationTextGuard.restoreDisplayCase("The dark hall", false))
    }

    @Test
    fun mixedAlphabetsCountAsAFailedAnswer() {
        assertTrue(TranslationTextGuard.isMixedAnswer("THE DARKЗАЛ", Language.ENGLISH))
        assertFalse(TranslationTextGuard.isMixedAnswer("THE DARK HALL", Language.ENGLISH))
        assertTrue(TranslationTextGuard.isMixedAnswer("КРУТЯЩИЙ MOMENT", Language.RUSSIAN))
        assertFalse(TranslationTextGuard.isMixedAnswer("КРУТЯЩИЙ МОМЕНТ 20 Nm", Language.RUSSIAN))
        assertFalse(TranslationTextGuard.isMixedAnswer("トルク", Language.JAPANESE))
    }

    @Test
    fun foreignShareIsMeasuredOnLettersOnly() {
        assertEquals(
            0.3f,
            TranslationTextGuard.foreignLetterShare("THE DARKЗАЛ", Language.ENGLISH),
            0.001f,
        )
        assertEquals(0f, TranslationTextGuard.foreignLetterShare("1234", Language.ENGLISH), 0f)
    }
}
