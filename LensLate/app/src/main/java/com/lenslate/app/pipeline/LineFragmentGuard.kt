package com.lenslate.app.pipeline

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tells whether a box cuts a written line in half.
 *
 * OCR can stop mid-line once glare washes the rest of it out, and the reading
 * then describes only the ink it actually saw. Replacing such a block paints
 * the translation next to the original words that stayed on the photo. A box is
 * only safe to replace when its own ink ends at its edges.
 */
internal object LineFragmentGuard {

    private const val STRENGTH_RATIO = 0.28f
    private const val MIN_STRENGTH = 6f
    private const val MIN_GAP_COLUMNS = 4
    private const val WORD_GAP_RATIO = 0.35f
    private const val MIN_FRAGMENT_SHARE = 0.6f
    private const val PEAK_PERCENTILE = 0.92f

    fun isFragment(
        pixels: IntArray,
        width: Int,
        height: Int,
        boxLeft: Int,
        boxTop: Int,
        boxRight: Int,
        boxBottom: Int,
        sizePx: Float,
    ): Boolean {
        if (width < 4 || height < 4 || sizePx <= 0f) return false
        val top = boxTop.coerceIn(1, height - 2)
        val bottom = boxBottom.coerceIn(top + 1, height)
        val left = boxLeft.coerceIn(0, width - 1)
        val right = boxRight.coerceIn(left + 1, width)
        val pad = bottom - top
        val aboveStart = (top - pad).coerceAtLeast(0)
        val belowEnd = (bottom + pad).coerceAtMost(height)
        if (top - aboveStart < 2 || belowEnd - bottom < 2) return false

        val strength = strengthPerColumn(pixels, width, aboveStart, top, bottom, belowEnd)
        val inside = strength.copyOfRange(left, right).sortedArray()
        if (inside.isEmpty()) return false
        val peak = inside[((inside.size - 1) * PEAK_PERCENTILE).toInt()]
        val threshold = maxOf(MIN_STRENGTH, peak * STRENGTH_RATIO)
        val gap = maxOf(MIN_GAP_COLUMNS, (sizePx * WORD_GAP_RATIO).roundToInt())
        val minRun = sizePx * MIN_FRAGMENT_SHARE
        val rightEdge = reach(strength, (right + 2).coerceAtMost(width - 1), width - 1, +1, threshold, gap)
        if (rightEdge - right >= minRun) return true
        val leftEdge = reach(strength, (left - 2).coerceAtLeast(0), 0, -1, threshold, gap)
        return left - leftEdge >= minRun
    }

    /**
     * Strongest departure from the local backdrop inside the line's own rows.
     * The backdrop is taken from the same columns just above and below them, so
     * a glare gradient crossing the window cancels out instead of turning into
     * ink.
     */
    private fun strengthPerColumn(
        pixels: IntArray,
        width: Int,
        aboveStart: Int,
        top: Int,
        bottom: Int,
        belowEnd: Int,
    ): FloatArray {
        val strength = FloatArray(width)
        for (x in 0 until width) {
            var sum = 0L
            var count = 0
            for (y in aboveStart until top) {
                sum += luminance(pixels[y * width + x])
                count++
            }
            for (y in bottom until belowEnd) {
                sum += luminance(pixels[y * width + x])
                count++
            }
            if (count == 0) continue
            val backdrop = sum.toFloat() / count
            var peak = 0f
            for (y in top until bottom) {
                val distance = abs(luminance(pixels[y * width + x]) - backdrop)
                if (distance > peak) peak = distance.toFloat()
            }
            strength[x] = peak
        }
        return strength
    }

    /**
     * Furthest column still carrying ink from [start] towards [limit], stopping
     * at the first gap wide enough to be a space between words.
     */
    private fun reach(
        strength: FloatArray,
        start: Int,
        limit: Int,
        direction: Int,
        threshold: Float,
        gap: Int,
    ): Int {
        var position = start
        var far = start
        var misses = 0
        while (position in strength.indices) {
            if (direction > 0 && position > limit) break
            if (direction < 0 && position < limit) break
            if (strength[position] >= threshold) {
                far = position
                misses = 0
            } else {
                misses++
                if (misses > gap) break
            }
            position += direction
        }
        return far
    }

    private fun luminance(color: Int): Int =
        (((color shr 16) and 0xFF) * 299 + ((color shr 8) and 0xFF) * 587 + (color and 0xFF) * 114) / 1000
}
