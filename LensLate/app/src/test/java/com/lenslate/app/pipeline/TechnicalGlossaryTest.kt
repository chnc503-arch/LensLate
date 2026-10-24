package com.lenslate.app.pipeline

import com.lenslate.app.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicalGlossaryTest {

    @Test
    fun locksPreferredTermForShortLabel() {
        val prepared = TechnicalGlossary.prepare("Torque", Language.ENGLISH, Language.RUSSIAN)
        assertEquals("Крутящий момент", TechnicalGlossary.restore(prepared.text, prepared.tokens))
    }

    @Test
    fun protectsMeasurementWhileLockingLabelTerm() {
        val prepared = TechnicalGlossary.prepare(
            "Tightening torque: 25 N·m",
            Language.ENGLISH,
            Language.RUSSIAN,
        )
        assertTrue(prepared.tokens.isNotEmpty())
        assertFalse(prepared.text.contains("N·m"))
        val restored = TechnicalGlossary.restore(prepared.text, prepared.tokens)
        assertEquals("Момент затяжки: 25 N·m", restored)
    }

    @Test
    fun localizesUnitsAfterRestore() {
        val prepared = TechnicalGlossary.prepare("Давление 10 МПа", Language.RUSSIAN, Language.ENGLISH)
        val restored = TechnicalGlossary.restore(prepared.text, prepared.tokens)
        val refined = TechnicalGlossary.refine(restored, Language.ENGLISH)
        assertEquals("Pressure 10 MPa", refined)
    }

    @Test
    fun correctsTechnicalPhraseInProse() {
        val refined = TechnicalGlossary.refine("Момент силы затяжки", Language.RUSSIAN)
        assertEquals("Крутящий момент затяжки", refined)
    }

    @Test
    fun leavesSentenceUnlocked() {
        val sentence = "The pump must be checked before every start."
        val prepared = TechnicalGlossary.prepare(sentence, Language.ENGLISH, Language.RUSSIAN)
        assertEquals(sentence, prepared.text)
        assertTrue(prepared.tokens.isEmpty())
    }

    @Test
    fun restoresTokensEvenWhenModelChangesCase() {
        val prepared = TechnicalGlossary.prepare("M12×1.5", Language.ENGLISH, Language.RUSSIAN)
        assertEquals(1, prepared.tokens.size)
        val mangled = prepared.text.lowercase()
        assertEquals("M12×1.5", TechnicalGlossary.restore(mangled, prepared.tokens))
    }

    @Test
    fun normalizesSpacingAroundPunctuation() {
        val refined = TechnicalGlossary.refine("Давление ,10 МПа;температура 20 °C", Language.RUSSIAN)
        assertEquals("Давление, 10 МПа; температура 20 °C", refined)
    }
}
