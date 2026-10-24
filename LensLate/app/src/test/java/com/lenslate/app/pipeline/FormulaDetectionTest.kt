package com.lenslate.app.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormulaDetectionTest {

    @Test
    fun detectsArithmeticExpressions() {
        assertTrue(FormulaDetection.looksLikeFormula("3+4=7"))
        assertTrue(FormulaDetection.looksLikeFormula("12 × 8"))
        assertTrue(FormulaDetection.looksLikeFormula("x=5"))
        assertTrue(FormulaDetection.looksLikeFormula("(a+b)/2"))
    }

    @Test
    fun doesNotFlagPlainProseOrNumbers() {
        assertFalse(FormulaDetection.looksLikeFormula("2024 year report"))
        assertFalse(FormulaDetection.looksLikeFormula("100"))
        assertFalse(FormulaDetection.looksLikeFormula("Price: 1 200 USD"))
        assertFalse(FormulaDetection.looksLikeFormula(""))
    }

    @Test
    fun flagsMixedNumberWithOperatorOnlyContent() {
        assertTrue(FormulaDetection.looksLikeFormula("50%"))
        assertFalse(FormulaDetection.looksLikeFormula("Discount 50% for members"))
    }

    @Test
    fun preservesOnlySymbolicFormulasVerbatim() {
        assertTrue(FormulaDetection.isVerbatimFormula("3+4=7"))
        assertTrue(FormulaDetection.isVerbatimFormula("(a+b)/2"))
        assertTrue(FormulaDetection.isVerbatimFormula("50%"))
        assertTrue(FormulaDetection.isVerbatimFormula("x = 5"))
    }

    @Test
    fun keepsProseWithNumbersTranslatable() {
        assertFalse(FormulaDetection.isVerbatimFormula("50% off"))
        assertFalse(FormulaDetection.isVerbatimFormula("Цена 100 = скидка"))
        assertFalse(FormulaDetection.isVerbatimFormula("100"))
        assertFalse(FormulaDetection.isVerbatimFormula("Price 1+1 USD"))
    }
}
