package com.lenslate.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/** Behavior of the lab-only lexicon repair on readings from the dark-ink page. */
class CyrillicLexicalRepairTest {

    @Test
    fun inventedWordsAreReplacedWithKnownForms() {
        assertEquals("ТЕМНОТА", CyrillicLexicalRepair.repair("ЧЕМНОТЩ"))
        assertEquals("ОТЛИЧАЮТСЯ", CyrillicLexicalRepair.repair("ОТПИЧЭЮТСЯ"))
        assertEquals("ПОДЛОЖКИ", CyrillicLexicalRepair.repair("ПОДЛОЖКИЁ"))
        assertEquals("ДЕРЖИТСЯ", CyrillicLexicalRepair.repair("ДЗРЖИТСЯ"))
        assertEquals("СЕРОГО", CyrillicLexicalRepair.repair("СЭРОГО"))
        assertEquals("ОРИГИНАЛА", CyrillicLexicalRepair.repair("ОРИГИНЭПЭ"))
    }

    @Test
    fun contextChoosesTheGrammaticalCompletion() {
        assertEquals("ПОЛНАЯ ТЕМНОТА", CyrillicLexicalRepair.repair("ПОЛНАЯ ТЕМНОТ"))
        assertEquals("после КОНЦЕРТА", CyrillicLexicalRepair.repair("после КОНЦЭРТЭ"))
    }

    @Test
    fun caseOfTheSourceIsPreserved() {
        assertEquals("Темнота", CyrillicLexicalRepair.repair("Чемнотщ"))
    }

    @Test
    fun rareButRealVocabularyIsUntouched() {
        val text = "типографика и акциденция"
        assertEquals(text, CyrillicLexicalRepair.repair(text))
    }

    @Test
    fun knownTextLatinAndNumbersAreUntouched() {
        val text = "Стойка 3 · 12 ОКТЯБРЯ · 20:00"
        assertEquals(text, CyrillicLexicalRepair.repair(text))
        val paragraph = "Проверка идёт по всей строке: оттенок медленно уходит из холодного серого в тёплый уголь."
        assertEquals(paragraph, CyrillicLexicalRepair.repair(paragraph))
    }

    @Test
    fun slashDebrisIsRemovedBeforeRepair() {
        val cleaned = CyrillicLexicalRepair.repair("’ ПОЛНАЯ/Ё/;./1/і ТЕМНОТ!")
        assertEquals("ПОЛНАЯ ТЕМНОТА!", cleaned)
    }

    @Test
    fun numericSlashesSurvive() {
        assertEquals("12/10/2026", CyrillicLexicalRepair.repair("12/10/2026"))
    }

    @Test
    fun repairBudgetIsBoundedPerRegion() {
        val source = List(10) { "ЧЕМНОТЩ" }.joinToString(" ")
        val repaired = CyrillicLexicalRepair.repair(source)
        assertEquals(6, repaired.split(" ").count { it == "ТЕМНОТА" })
    }
}
