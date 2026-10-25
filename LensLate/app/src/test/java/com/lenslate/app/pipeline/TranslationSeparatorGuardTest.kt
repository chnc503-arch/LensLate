package com.lenslate.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/** Точки-разделители и время должны пережить машинный перевод без искажений. */
class TranslationSeparatorGuardTest {

    @Test
    fun restoresMiddleDotFromHyphen() {
        assertEquals(
            "October 12 · 20:00",
            TranslationSeparatorGuard.restore(
                "12 ОКТЯБРЯ · 20:00",
                "October 12 - 20: 00",
            ),
        )
    }

    @Test
    fun restoresEveryMiddleDot() {
        assertEquals(
            "acoustics · light · silence",
            TranslationSeparatorGuard.restore(
                "акустика · свет · тишина",
                "acoustics - light - silence",
            ),
        )
    }

    @Test
    fun keepsEmDashWhenSourceHasNoDecorator() {
        assertEquals(
            "After the concert - an open microphone",
            TranslationSeparatorGuard.restore(
                "после концерта — открытый микрофон",
                "After the concert - an open microphone",
            ),
        )
    }

    @Test
    fun keepsBulletInsteadOfReplacingItWithDot() {
        assertEquals(
            "One evening • one price",
            TranslationSeparatorGuard.restore(
                "ОДИН ВЕЧЕР • ОДНА ЦЕНА",
                "One evening - one price",
            ),
        )
    }

    @Test
    fun tightensClockGapWithoutDots() {
        assertEquals(
            "Evening program 18:00",
            TranslationSeparatorGuard.restore(
                "ВЕЧЕРНЯЯ ПРОГРАММА 18:00",
                "Evening program 18: 00",
            ),
        )
    }
}
