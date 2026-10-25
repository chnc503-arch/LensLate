package com.lenslate.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/** Юнит-тесты точечного ремонта строк после OCR. */
class OcrTextRepairTest {

    @Test
    fun insertsSpaceAfterColonBeforeWord() {
        assertEquals(
            "ВАЖНО: Перед первым включением",
            OcrTextRepair.repair("ВАЖНО:Перед первым включением"),
        )
    }

    @Test
    fun keepsClockValuesUntouched() {
        assertEquals("12:30", OcrTextRepair.repair("12:30"))
        assertEquals("Питание: 220 В, 50 Гц.", OcrTextRepair.repair("Питание: 220 В, 50 Гц."))
    }

    @Test
    fun convertsLookalikesInsideModelCode() {
        assertEquals(
            "Модель LX-4200 · Серия А",
            OcrTextRepair.repair("Модель ЪХ—4200 · Серия А"),
        )
    }

    @Test
    fun leavesOrdinaryCyrillicTextUntouched() {
        assertEquals("5-й квартал 2024 г.", OcrTextRepair.repair("5-й квартал 2024 г."))
    }

    @Test
    fun restoresDecorativeSeparatorFromSpacedApostrophe() {
        assertEquals(
            "акустика · свет · тишина",
            OcrTextRepair.repair("акустика ' свет ' тишина"),
        )
        assertEquals(
            "12 ОКТЯБРЯ · 20:00",
            OcrTextRepair.repair("12 ОКТЯБРЯ ‘ 20:00"),
        )
    }

    @Test
    fun keepsQuotedWordsIntact() {
        assertEquals("слово 'цитата' внутри", OcrTextRepair.repair("слово 'цитата' внутри"))
    }

    @Test
    fun trimsOcrNoiseAroundDisplayLines() {
        assertEquals("ЧЕМНОТЩ", OcrTextRepair.repair("___./ЧЕМНОТЩ"))
        assertEquals("ОДИН ВЕЧЕР ОДНА СЦЕН", OcrTextRepair.repair("ОДИН ВЕЧЕР ОДНА СЦЕН ^"))
    }

    @Test
    fun restoresMiddleDotFromSpacedHyphenInDisplayCaps() {
        assertEquals("12 ОКТЯБРЯ · 20:00", OcrTextRepair.repair("12 ОКТЯБРЯ - 20:00"))
        assertEquals("ТИХИЙ БАР · СТОЙКА 3", OcrTextRepair.repair("ТИХИЙ БАР - СТОЙКА 3"))
    }

    @Test
    fun keepsDashInLowercaseProse() {
        assertEquals(
            "после концерта — открытый микрофон",
            OcrTextRepair.repair("после концерта — открытый микрофон"),
        )
    }

    @Test
    fun joinsSlashGluedToSentenceEnd() {
        assertEquals(
            "нескольких УРОВНЯХ ЯРКОСТИ. Проверка идёт по всей строке:",
            OcrTextRepair.repair("нескольких  УРОВНЯХ  ЯРКОСТИ ./Проверка идёт по всей строке:"),
        )
    }
}
