package com.lenslate.app.pipeline

import com.lenslate.app.model.TextRegionRecord

/**
 * Aggregate quality of one OCR hypothesis, used by the automated lab stage to
 * choose between a fast pass and a deeper multi-recipe pass without a human.
 */
internal object SmartScanQuality {

    fun score(
        regions: List<TextRegionRecord>,
        imageArea: Float,
        lexical: Boolean = false,
    ): Float {
        if (imageArea <= 0f) return 0f
        val relevant = regions.filter { it.text.isNotBlank() && !it.isProtected }
        if (relevant.isEmpty()) return 0f
        val usable = relevant.filter { CyrillicReadingPlausibility.isUsableReading(it.text) }
        val usableShare = usable.size.toFloat() / relevant.size
        val plausibility = usable
            .map { region ->
                if (lexical) {
                    CyrillicReadingPlausibility.lexicalQuality(region.text)
                } else {
                    CyrillicReadingPlausibility.score(region.text)
                }
            }
            .averageOrZero()
        val confidence = relevant
            .mapNotNull { it.overallConfidence ?: it.confidence }
            .averageOrZero()
        val coverageShare = (relevant.sumOf { area(it) } / imageArea)
            .toFloat()
            .coerceIn(0f, COVERAGE_CAP) / COVERAGE_CAP
        return PLAUSIBILITY_WEIGHT * plausibility +
            CONFIDENCE_WEIGHT * confidence +
            USABLE_WEIGHT * usableShare +
            COVERAGE_WEIGHT * coverageShare
    }

    fun needsDeeperPass(
        score: Float,
        regions: List<TextRegionRecord>,
        lexical: Boolean = false,
    ): Boolean {
        if (regions.isEmpty()) return true
        if (score < DEEP_PASS_THRESHOLD) return true
        val relevant = regions.filter { it.text.isNotBlank() && !it.isProtected }
        if (relevant.isEmpty()) return false
        val junkShare = relevant.count { !CyrillicReadingPlausibility.isUsableReading(it.text) } /
            relevant.size.toFloat()
        if (junkShare > MAX_JUNK_SHARE) return true
        // A reading can be structurally perfect and still contain invented
        // words, which is exactly what happens on low-contrast posters. Only
        // the lab asks for this evidence; the regular scan never pays for it.
        return lexical && relevant.any { CyrillicReadingPlausibility.isSuspectReading(it.text) }
    }

    private fun area(region: TextRegionRecord): Double =
        (region.bounds.width.toDouble() * region.bounds.height.toDouble()).coerceAtLeast(0.0)

    private fun List<Float>.averageOrZero(): Float =
        if (isEmpty()) 0f else average().toFloat().coerceIn(0f, 1f)

    private const val PLAUSIBILITY_WEIGHT = 0.42f
    private const val CONFIDENCE_WEIGHT = 0.24f
    private const val USABLE_WEIGHT = 0.22f
    private const val COVERAGE_WEIGHT = 0.12f
    private const val COVERAGE_CAP = 0.4f
    private const val DEEP_PASS_THRESHOLD = 0.62f
    private const val MAX_JUNK_SHARE = 0.25f
}
