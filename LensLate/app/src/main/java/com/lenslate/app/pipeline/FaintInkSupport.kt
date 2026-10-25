package com.lenslate.app.pipeline

import com.lenslate.app.model.TextRegionRecord
import kotlin.math.sqrt

/**
 * Accepts a low-contrast reading only when its own contrast curve produces
 * glyph-shaped ink: several text rows with narrow runs, ink inside most
 * symbol boxes, and no filled silhouette. Flat paper and smooth shadows
 * fail that structure even when OCR invented a word there.
 */
internal object FaintInkSupport {

    fun hasSupport(
        region: TextRegionRecord,
        maximum: Float,
        pixels: IntArray,
        width: Int,
        height: Int,
        offsetX: Int,
        offsetY: Int,
        background: Int,
        glyphCount: Int,
    ): Boolean {
        val threshold = maxOf(FAINT_INK_DISTANCE, sqrt(maximum) * FAINT_INK_RATIO)
        val thresholdSq = threshold * threshold
        var ink = 0
        val mask = BooleanArray(pixels.size)
        pixels.forEachIndexed { index, pixel ->
            if (distanceSq(pixel, background) >= thresholdSq) {
                ink++
                mask[index] = true
            }
        }
        val share = ink.toFloat() / pixels.size
        if (share < FAINT_MIN_INK_SHARE || share > FAINT_MAX_INK_SHARE) return false
        if (!glyphsHaveInk(region, pixels, width, height, offsetX, offsetY, background, thresholdSq)) {
            return false
        }
        val structure = GlyphStructureAnalyzer.analyze(mask, width, height)
        if (GlyphStructureAnalyzer.looksLikeSolidGraphic(structure, glyphCount)) return false
        return structure.inkRows >= MIN_FAINT_INK_ROWS &&
            structure.multiRunRowShare >= MIN_FAINT_MULTI_RUN_SHARE
    }

    /**
     * Every non-space glyph should cover a few percent of its own box. Boxes on
     * blank paper score zero; boxes filled by a shadow gradient score almost one.
     * A text line keeps a clear mixture of ink and background.
     */
    fun glyphsHaveInk(
        region: TextRegionRecord,
        pixels: IntArray,
        width: Int,
        height: Int,
        offsetX: Int,
        offsetY: Int,
        background: Int,
        thresholdSq: Float,
    ): Boolean {
        val symbols = region.lines
            .flatMap { it.elements }
            .flatMap { it.symbols }
            .filter { it.text.any(Char::isLetterOrDigit) }
        if (symbols.size < MIN_SYMBOLS_FOR_SUPPORT) return true
        var sampled = 0
        var supported = 0
        symbols.take(MAX_SYMBOLS_TO_CHECK).forEach { symbol ->
            val bounds = symbol.bounds ?: return@forEach
            val left = (bounds.left.toInt() - offsetX).coerceIn(0, width - 1)
            val top = (bounds.top.toInt() - offsetY).coerceIn(0, height - 1)
            val right = (bounds.right.toInt() - offsetX).coerceIn(left + 1, width)
            val bottom = (bounds.bottom.toInt() - offsetY).coerceIn(top + 1, height)
            if (right - left < 2 || bottom - top < 2) return@forEach
            var ink = 0
            var maximum = 0f
            var total = 0
            for (y in top until bottom) {
                val row = y * width
                for (x in left until right) {
                    val distance = distanceSq(pixels[row + x], background)
                    if (distance > maximum) maximum = distance
                    if (distance >= thresholdSq) ink++
                    total++
                }
            }
            if (total == 0) return@forEach
            sampled++
            val share = ink.toFloat() / total
            if (maximum >= thresholdSq && share in SYMBOL_INK_SHARE_MIN..SYMBOL_INK_SHARE_MAX) {
                supported++
            }
        }
        if (sampled == 0) return true
        return supported.toFloat() / sampled >= MIN_SUPPORTED_SYMBOL_SHARE
    }

    fun distanceSq(first: Int, second: Int): Float {
        val red = ((first shr 16) and 0xFF) - ((second shr 16) and 0xFF)
        val green = ((first shr 8) and 0xFF) - ((second shr 8) and 0xFF)
        val blue = (first and 0xFF) - (second and 0xFF)
        return (red * red + green * green + blue * blue).toFloat()
    }

    private const val FAINT_INK_DISTANCE = 8f
    private const val FAINT_INK_RATIO = 0.45f
    private const val FAINT_MIN_INK_SHARE = 0.004f
    private const val FAINT_MAX_INK_SHARE = 0.55f
    private const val MIN_FAINT_INK_ROWS = 3
    private const val MIN_FAINT_MULTI_RUN_SHARE = 0.12f
    private const val SYMBOL_INK_SHARE_MIN = 0.015f
    private const val SYMBOL_INK_SHARE_MAX = 0.9f
    private const val MIN_SUPPORTED_SYMBOL_SHARE = 0.34f
    private const val MIN_SYMBOLS_FOR_SUPPORT = 3
    private const val MAX_SYMBOLS_TO_CHECK = 96
}
