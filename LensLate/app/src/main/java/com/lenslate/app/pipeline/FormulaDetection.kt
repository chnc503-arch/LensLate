package com.lenslate.app.pipeline

/**
 * Heuristics for text that behaves like a formula or numeric expression.
 * Pure JVM logic so it stays unit-testable without Android dependencies.
 */
internal object FormulaDetection {

    const val OPERATORS = "+-*/×÷=<>≤≥≈^%√()[]{}"

    /** A formula-like string mixes digits with operators and carries little alphabetic content. */
    fun looksLikeFormula(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return false
        val digits = normalized.count(Char::isDigit)
        val operators = normalized.count { it in OPERATORS }
        if (digits == 0 || operators == 0) return false
        val letters = normalized.count { it.isLetter() }
        return letters <= digits + operators
    }

    /**
     * Formula or numeric notation safe to leave verbatim: unlike [looksLikeFormula],
     * this rejects regions with natural-language words ("Цена 100 ₽") so real labels
     * still get translated.
     */
    fun isVerbatimFormula(text: String): Boolean {
        if (!looksLikeFormula(text)) return false
        return text.trim()
            .split(WHITESPACE)
            .none { word -> word.count { it.isLetter() } >= MIN_WORD_LETTERS }
    }

    private val WHITESPACE = Regex("\\s+")
    private const val MIN_WORD_LETTERS = 3
}
