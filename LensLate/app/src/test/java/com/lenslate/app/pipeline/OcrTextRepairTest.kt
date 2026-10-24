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
}
