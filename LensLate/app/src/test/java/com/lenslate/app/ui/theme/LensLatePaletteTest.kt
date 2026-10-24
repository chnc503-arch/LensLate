package com.lenslate.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class LensLatePaletteTest {

    @Test
    fun knownPreferenceIdRestoresPalette() {
        assertEquals(
            LensLatePalette.CHOCOLATE_SKY,
            LensLatePalette.fromPreferenceId("chocolate-sky"),
        )
    }

    @Test
    fun unknownPreferenceIdUsesOliveDefault() {
        assertEquals(LensLatePalette.OLIVE_BURGUNDY, LensLatePalette.fromPreferenceId("missing"))
        assertEquals(LensLatePalette.OLIVE_BURGUNDY, LensLatePalette.fromPreferenceId(null))
    }
}
