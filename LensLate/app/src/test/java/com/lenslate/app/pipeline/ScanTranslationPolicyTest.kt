package com.lenslate.app.pipeline

import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.TextRegionRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanTranslationPolicyTest {

    @Test
    fun keepsLowConfidenceTextSoItStillGetsTranslated() {
        val regions = listOf(
            region(id = 0, text = "ВХОД", confidence = 0.42f),
            region(id = 1, text = "ПО РЕГИСТРАЦИИ", confidence = null),
            region(id = 2, text = "Круглосуточно", confidence = 0.31f),
        )

        assertEquals(
            listOf(0, 1, 2),
            ScanTranslationPolicy.selectableIndices(regions),
        )
    }

    @Test
    fun skipsBlankFormulaAndProtectedBlocks() {
        val regions = listOf(
            region(id = 0, text = "   "),
            region(id = 1, text = "E = mc^2", formula = true),
            region(id = 2, text = "LENS LATE", protected = true),
            region(id = 3, text = "Открыто 24 часа"),
        )

        assertEquals(
            listOf(3),
            ScanTranslationPolicy.selectableIndices(regions),
        )
    }

    private fun region(
        id: Int,
        text: String,
        confidence: Float? = 0.9f,
        formula: Boolean = false,
        protected: Boolean = false,
    ) = TextRegionRecord(
        id = id,
        text = text,
        bounds = Bounds2D(0f, 0f, 100f, 20f),
        polygon = emptyList(),
        script = null,
        readingOrder = id,
        confidence = confidence,
        overallConfidence = confidence,
        lines = emptyList(),
        isFormula = formula,
        isProtected = protected,
    )
}
