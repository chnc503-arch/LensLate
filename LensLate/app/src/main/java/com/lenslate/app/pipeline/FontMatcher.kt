package com.lenslate.app.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max

internal data class FontMatch(
    val variant: FontVariant,
    val score: Float,
    val horizontalScale: Float,
)

/**
 * Compares the source ink mask with the same text rendered in every catalogue
 * font. Both shapes are normalised to a fixed grid, which keeps the comparison
 * independent of size while preserving serif/sans, weight and italic shapes.
 */
internal object FontMatcher {

    fun match(
        text: String,
        mask: BooleanArray,
        maskWidth: Int,
        maskHeight: Int,
        regionRect: Rect,
        lineRect: Rect,
        capHeight: Float,
        letterSpacingPx: Float,
        weightHint: Int,
        underlined: Boolean,
        struckThrough: Boolean,
        italicHint: Boolean = false,
    ): FontMatch? {
        val normalized = text.replace('\n', ' ').trim()
        if (normalized.count { it.isLetter() } < 2 || normalized.length > MAX_TEXT_LENGTH) return null
        if (capHeight < MIN_CAP_HEIGHT) return null

        val localLine = Rect(
            (lineRect.left - regionRect.left).coerceIn(0, maskWidth - 1),
            (lineRect.top - regionRect.top).coerceIn(0, maskHeight - 1),
            (lineRect.right - regionRect.left).coerceIn(1, maskWidth),
            (lineRect.bottom - regionRect.top).coerceIn(1, maskHeight),
        )
        if (localLine.width() < 3 || localLine.height() < 3) return null
        val sourceBox = inkBounds(mask, maskWidth, maskHeight, localLine) ?: return null
        val sourceGrid = rasterize(mask, maskWidth, sourceBox)

        var best: FontMatch? = null
        FontCatalog.matchable.forEach { variant ->
            // Ink-mask IoU rewards extra coverage, so a bold face can win on a
            // regular source purely because anti-aliasing thickened the mask.
            // The independently measured weight has the final say on that axis.
            if (weightHint <= MAX_REGULAR_HINT && variant.weight >= BOLD_VARIANT_WEIGHT) {
                return@forEach
            }
            // An upright face cannot imitate a real lean and vice versa, so the
            // shape comparison decides; the hint only nudges close calls. The
            // old hard filter missed italics whose slant estimate came out low
            // and forced an upright face onto slanted source text.
            val slantPenalty = when {
                variant.italic && !italicHint -> ITALIC_WITHOUT_HINT_PENALTY
                !variant.italic && italicHint -> UPRIGHT_WITH_ITALIC_HINT_PENALTY
                else -> 0f
            }
            val typeface = FontCatalog.typeface(variant.key) ?: return@forEach
            val candidate = renderCandidate(
                text = normalized,
                typeface = typeface,
                capHeight = capHeight,
                letterSpacingPx = letterSpacingPx,
                underlined = underlined,
                struckThrough = struckThrough,
            ) ?: return@forEach
            val candidateGrid = rasterize(candidate.mask, candidate.width, candidate.bounds)
            val shapeScore = similarity(sourceGrid, candidateGrid)
            val weightBonus = WEIGHT_BONUS *
                (1f - (abs(variant.weight - weightHint) / 500f)).coerceIn(0f, 1f)
            // Rasterization stretches both shapes to one grid, so the width
            // evidence survives only here. Monospace advances render wider
            // than proportional ones; without this term a heavy sans can be
            // replaced by mono-bold purely because both silhouettes are dense.
            val widthRatio = sourceBox.width().toFloat() / max(1, candidate.bounds.width())
            val widthDeviation = abs(ln(widthRatio.coerceAtLeast(0.2f))) / LN_MAX_WIDTH_DEVIATION
            val widthBonus = WIDTH_BONUS * (1f - widthDeviation.coerceIn(0f, 1f))
            val score = (shapeScore + weightBonus + widthBonus - slantPenalty).coerceIn(0f, 1f)
            if (best == null || score > best!!.score) {
                best = FontMatch(
                    variant = variant,
                    score = score,
                    horizontalScale = (sourceBox.width().toFloat() / max(1, candidate.bounds.width()))
                        .coerceIn(MIN_HORIZONTAL_SCALE, MAX_HORIZONTAL_SCALE),
                )
            }
        }
        return best?.takeIf { it.score >= MIN_MATCH_SCORE }
    }

    private fun renderCandidate(
        text: String,
        typeface: android.graphics.Typeface,
        capHeight: Float,
        letterSpacingPx: Float,
        underlined: Boolean,
        struckThrough: Boolean,
    ): Candidate? {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        paint.typeface = typeface
        paint.color = Color.BLACK
        paint.textSize = PROBE_TEXT_SIZE
        val probe = Rect()
        paint.getTextBounds("H", 0, 1, probe)
        val probeCap = -probe.top
        if (probeCap <= 0f) return null
        val size = (capHeight * PROBE_TEXT_SIZE / probeCap).coerceIn(MIN_RENDER_SIZE, MAX_RENDER_SIZE)
        paint.textSize = size
        paint.letterSpacing = (letterSpacingPx / size.coerceAtLeast(1f)).coerceIn(-0.08f, 0.6f)
        paint.isUnderlineText = underlined
        paint.isStrikeThruText = struckThrough

        val measuredWidth = paint.measureText(text).coerceAtLeast(1f)
        val metrics = paint.fontMetrics
        val contentHeight = (metrics.descent - metrics.ascent).coerceAtLeast(1f)
        val width = ceil(measuredWidth + RENDER_PADDING * 2).toInt().coerceAtMost(MAX_RENDER_WIDTH)
        val height = ceil(contentHeight + RENDER_PADDING * 2).toInt().coerceAtMost(MAX_RENDER_HEIGHT)
        if (width < 3 || height < 3) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        val baseline = RENDER_PADDING - metrics.ascent
        canvas.drawText(text, RENDER_PADDING, baseline, paint)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val mask = BooleanArray(pixels.size) { index -> (pixels[index] ushr 24) > ALPHA_THRESHOLD }
        val bounds = inkBounds(mask, width, height, Rect(0, 0, width, height))
        bitmap.recycle()
        if (bounds == null) return null
        return Candidate(mask, width, bounds)
    }

    private fun inkBounds(
        mask: BooleanArray,
        width: Int,
        height: Int,
        area: Rect,
    ): Rect? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        val startX = area.left.coerceIn(0, width - 1)
        val endX = area.right.coerceIn(1, width)
        val startY = area.top.coerceIn(0, height - 1)
        val endY = area.bottom.coerceIn(1, height)
        for (y in startY until endY) {
            val row = y * width
            for (x in startX until endX) {
                if (!mask[row + x]) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return null
        return Rect(left, top, right + 1, bottom + 1)
    }

    private fun rasterize(mask: BooleanArray, width: Int, box: Rect): FloatArray {
        val grid = FloatArray(GRID_WIDTH * GRID_HEIGHT)
        val boxWidth = box.width().toFloat().coerceAtLeast(1f)
        val boxHeight = box.height().toFloat().coerceAtLeast(1f)
        for (cellY in 0 until GRID_HEIGHT) {
            for (cellX in 0 until GRID_WIDTH) {
                var covered = 0
                for (sampleY in 0 until SAMPLES) {
                    val y = (box.top + (cellY + (sampleY + 0.5f) / SAMPLES) / GRID_HEIGHT * boxHeight)
                        .toInt()
                    if (y !in box.top until box.bottom) continue
                    for (sampleX in 0 until SAMPLES) {
                        val x = (box.left + (cellX + (sampleX + 0.5f) / SAMPLES) / GRID_WIDTH * boxWidth)
                            .toInt()
                        if (x !in box.left until box.right) continue
                        if (mask[y * width + x]) covered++
                    }
                }
                grid[cellY * GRID_WIDTH + cellX] = covered.toFloat() / (SAMPLES * SAMPLES)
            }
        }
        return grid
    }

    private fun similarity(source: FloatArray, candidate: FloatArray): Float {
        var intersection = 0f
        var union = 0f
        for (index in source.indices) {
            val first = source[index]
            val second = candidate[index]
            intersection += minOf(first, second)
            union += maxOf(first, second)
        }
        if (union <= 1e-4f) return 0f
        return (intersection / union).coerceIn(0f, 1f)
    }

    private data class Candidate(
        val mask: BooleanArray,
        val width: Int,
        val bounds: Rect,
    )

    private val ALPHA_THRESHOLD = 96
    private const val PROBE_TEXT_SIZE = 100f
    private const val MIN_RENDER_SIZE = 3f
    private const val MAX_RENDER_SIZE = 500f
    private const val MIN_CAP_HEIGHT = 3.5f
    private const val RENDER_PADDING = 3f
    private const val MAX_RENDER_WIDTH = 4096
    private const val MAX_RENDER_HEIGHT = 1024
    private const val MAX_TEXT_LENGTH = 180
    private const val GRID_WIDTH = 64
    private const val GRID_HEIGHT = 32
    private const val SAMPLES = 3
    private const val MIN_MATCH_SCORE = 0.16f
    private const val MIN_HORIZONTAL_SCALE = 0.45f
    private const val MAX_HORIZONTAL_SCALE = 1.6f
    private const val WEIGHT_BONUS = 0.05f
    private const val WIDTH_BONUS = 0.02f
    private const val MAX_WIDTH_DEVIATION_RATIO = 1.35f
    private val LN_MAX_WIDTH_DEVIATION = ln(MAX_WIDTH_DEVIATION_RATIO)
    private const val ITALIC_WITHOUT_HINT_PENALTY = 0.025f
    private const val UPRIGHT_WITH_ITALIC_HINT_PENALTY = 0.02f
    private const val MAX_REGULAR_HINT = 500
    private const val BOLD_VARIANT_WEIGHT = 700
}
