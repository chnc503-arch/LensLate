package com.lenslate.app.pipeline

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/** A recognised glyph box; float coordinates keep OCR precision. */
internal data class GlyphBox(
    val text: String,
    val left: Float,
    val right: Float,
)

/** Line bounds already clamped to the region pixel grid. */
internal data class TextLineSpan(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * Shape hints that are cheap enough to compute for every region and stable
 * enough to be unit-tested without a bitmap.
 */
internal object FontShapeEstimator {

    /**
     * Monospace means equal *advances*, not equal ink boxes. A narrow glyph
     * ("i", "l", "1") in a monospaced face keeps the full advance, while the
     * same glyph in a proportional face moves the next glyph much closer.
     * Measuring that contrast also rejects the equal-width cells that
     * Tesseract synthesises when it returns no symbol boxes, which used to mark
     * every serif paragraph as monospaced.
     */
    fun isMonospaced(lines: List<List<GlyphBox>>): Boolean {
        val advances = ArrayList<Float>()
        val widths = ArrayList<Float>()
        lines.forEach { line ->
            val boxes = line
                .filter { it.text.any(Char::isLetterOrDigit) && it.right - it.left > 1f }
                .sortedBy { it.left }
            if (boxes.size < 2) return@forEach
            boxes.forEach { box -> widths += box.right - box.left }
            for (index in 0 until boxes.lastIndex) {
                val advance = boxes[index + 1].left - boxes[index].left
                if (advance > 1f) advances += advance
            }
        }
        if (advances.size < MIN_MONO_ADVANCES || widths.size < MIN_MONO_ADVANCES) return false
        val medianAdvance = median(advances)
        val medianWidth = median(widths)
        if (medianAdvance <= 1f || medianWidth <= 1f) return false

        val uniform = advances.filter { advance ->
            advance >= medianAdvance * MIN_TYPICAL_ADVANCE_RATIO &&
                advance <= medianAdvance * MAX_TYPICAL_ADVANCE_RATIO
        }
        if (uniform.size < MIN_MONO_ADVANCES) return false
        if (coefficientOfVariation(uniform) > MAX_PITCH_CV) return false

        // Advances are paired with the glyph box they start from; word gaps are
        // skipped because a space inflates the advance without telling us
        // anything about the face.
        val narrowAdvances = ArrayList<Float>()
        lines.forEach { line ->
            val boxes = line
                .filter { it.text.any(Char::isLetterOrDigit) && it.right - it.left > 1f }
                .sortedBy { it.left }
            for (index in 0 until boxes.lastIndex) {
                val width = boxes[index].right - boxes[index].left
                val advance = boxes[index + 1].left - boxes[index].left
                if (width <= medianWidth * NARROW_WIDTH_RATIO &&
                    advance in (medianAdvance * MIN_WORD_ADVANCE_RATIO)..(medianAdvance * MAX_NARROW_ADVANCE_RATIO)
                ) {
                    narrowAdvances += advance
                }
            }
        }
        if (narrowAdvances.size < MIN_NARROW_SAMPLES) return false
        // A monospaced face keeps every narrow glyph on the full advance, so the
        // smallest sampled advance is still close to the pitch. In a
        // proportional face at least one narrow glyph is followed by a narrow
        // neighbour and moves the next box much closer.
        return narrowAdvances.min() >= medianAdvance * MIN_NARROW_PITCH_RATIO
    }

    /**
     * Estimates synthetic slant by shearing the ink mask and looking for the
     * angle that makes vertical stems sharpest in the column projection.
     * Row centroids were too eager: a wide top bar or a single descender moved
     * the centroid by several degrees and upright subtitles came out italic.
     *
     * Returns degrees with the renderer's convention: a right-leaning italic is
     * negative. Zero means "no measurable slant".
     */
    fun slantDegrees(
        mask: BooleanArray,
        width: Int,
        height: Int,
        lines: List<TextLineSpan>,
    ): Float {
        if (width < MIN_SLANT_WIDTH || height < MIN_SLANT_HEIGHT || mask.size < width * height) {
            return 0f
        }
        val samples = ArrayList<SlantLine>(lines.size)
        var pointCount = 0
        lines.forEach { span ->
            val left = span.left.coerceIn(0, width - 1)
            val right = span.right.coerceIn(left + 1, width)
            val top = span.top.coerceIn(0, height - 1)
            val bottom = span.bottom.coerceIn(top + 1, height)
            if (right - left < MIN_SLANT_WIDTH || bottom - top < MIN_SLANT_HEIGHT) return@forEach
            val xs = ArrayList<Int>()
            val ys = ArrayList<Int>()
            for (y in top until bottom) {
                val row = y * width
                for (x in left until right) {
                    if (mask[row + x]) {
                        xs += x
                        ys += y
                    }
                }
            }
            if (xs.size < MIN_SLANT_POINTS_PER_LINE) return@forEach
            samples += SlantLine(left, right, top, bottom, xs.toIntArray(), ys.toIntArray())
            pointCount += xs.size
        }
        if (samples.isEmpty() || pointCount < MIN_SLANT_POINTS) return 0f

        val stride = if (pointCount > MAX_SLANT_POINTS) {
            ceil(pointCount.toDouble() / MAX_SLANT_POINTS).toInt().coerceAtLeast(1)
        } else {
            1
        }
        val halfHeight = samples.maxOf { (it.bottom - it.top) * 0.5f }
        val margin = ceil(tan(Math.toRadians(MAX_SLANT_DEGREES.toDouble())).toFloat() * halfHeight)
            .toInt()
            .coerceAtLeast(1)

        var zeroScore = 0f
        var bestScore = -1f
        var bestAngle = 0f
        var angle = -MAX_SLANT_DEGREES
        while (angle <= MAX_SLANT_DEGREES) {
            val score = projectionScore(samples, angle.toFloat(), margin, stride)
            if (angle == 0) zeroScore = score
            if (score > bestScore) {
                bestScore = score
                bestAngle = angle.toFloat()
            }
            angle += COARSE_SLANT_STEP
        }
        if (zeroScore <= 0f) return 0f
        var refined = bestAngle
        var offset = -COARSE_SLANT_STEP.toFloat()
        while (offset <= COARSE_SLANT_STEP) {
            val candidate = (bestAngle + offset)
                .coerceIn(-MAX_SLANT_DEGREES.toFloat(), MAX_SLANT_DEGREES.toFloat())
            val score = projectionScore(samples, candidate, margin, stride)
            if (score > bestScore) {
                bestScore = score
                refined = candidate
            }
            offset += FINE_SLANT_STEP
        }
        if (abs(refined) < MIN_SLANT_DEGREES) return 0f
        if (bestScore < zeroScore * (1f + MIN_SLANT_GAIN)) return 0f
        // Best shear is positive for a right-leaning face; the renderer uses
        // negative textSkewX for that same lean.
        return -refined.coerceIn(-MAX_SLANT_DEGREES.toFloat(), MAX_SLANT_DEGREES.toFloat())
    }

    private data class SlantLine(
        val left: Int,
        val right: Int,
        val top: Int,
        val bottom: Int,
        val xs: IntArray,
        val ys: IntArray,
    )

    private fun projectionScore(
        lines: List<SlantLine>,
        angleDegrees: Float,
        margin: Int,
        stride: Int,
    ): Float {
        val shear = tan(Math.toRadians(angleDegrees.toDouble())).toFloat()
        var total = 0f
        lines.forEach { line ->
            val bins = IntArray(line.right - line.left + margin * 2)
            val centerY = (line.top + line.bottom - 1) * 0.5f
            var index = 0
            while (index < line.xs.size) {
                val shifted = line.xs[index] + margin +
                    (shear * (line.ys[index] - centerY)).roundToInt()
                val bin = shifted - line.left
                if (bin in bins.indices) bins[bin]++
                index += stride
            }
            bins.forEach { count -> total += count.toFloat() * count }
        }
        return total
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun coefficientOfVariation(values: List<Float>): Float {
        if (values.isEmpty()) return Float.MAX_VALUE
        val mean = values.average().toFloat()
        if (mean <= 0f) return Float.MAX_VALUE
        val variance = values.sumOf { value ->
            val delta = (value - mean).toDouble()
            delta * delta
        } / values.size
        return sqrt(variance).toFloat() / mean
    }

    private const val MIN_MONO_ADVANCES = 6
    private const val MIN_TYPICAL_ADVANCE_RATIO = 0.6f
    private const val MAX_TYPICAL_ADVANCE_RATIO = 1.6f
    private const val MAX_PITCH_CV = 0.10f
    private const val NARROW_WIDTH_RATIO = 0.62f
    private const val MIN_WORD_ADVANCE_RATIO = 0.35f
    private const val MAX_NARROW_ADVANCE_RATIO = 1.4f
    private const val MIN_NARROW_SAMPLES = 2
    private const val MIN_NARROW_PITCH_RATIO = 0.80f

    private const val MIN_SLANT_WIDTH = 12
    private const val MIN_SLANT_HEIGHT = 8
    private const val MIN_SLANT_POINTS_PER_LINE = 24
    private const val MIN_SLANT_POINTS = 48
    private const val MAX_SLANT_POINTS = 40_000
    private const val MAX_SLANT_DEGREES = 24
    private const val COARSE_SLANT_STEP = 2
    private const val FINE_SLANT_STEP = 0.5f
    private const val MIN_SLANT_DEGREES = 4f
    private const val MIN_SLANT_GAIN = 0.06f
}
