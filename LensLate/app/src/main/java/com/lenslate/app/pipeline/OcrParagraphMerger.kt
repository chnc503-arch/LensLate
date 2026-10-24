package com.lenslate.app.pipeline

import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.Point2D
import com.lenslate.app.model.TextLineRecord
import com.lenslate.app.model.TextRegionRecord
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Rebuilds paragraph-sized regions from line-sized readings. Cross-pass
 * deduplication keeps the sharpest line boxes, which leaves a paragraph as
 * several separate regions; styling and translating those independently picks
 * a different weight for every line and translates sentence fragments.
 * Adjacent lines that share a column, leading and size are joined here, before
 * appearance analysis and translation.
 */
internal object OcrParagraphMerger {

    fun merge(regions: List<TextRegionRecord>): List<TextRegionRecord> {
        if (regions.size < 2) return regions
        val groups = mutableListOf<MutableList<TextRegionRecord>>()
        regions
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .forEach { region ->
                val target = groups
                    .mapNotNull { group -> joinGap(group, region)?.let { gap -> group to gap } }
                    .minByOrNull { it.second }
                    ?.first
                if (target != null) target += region else groups += mutableListOf(region)
            }
        if (groups.none { it.size > 1 }) return regions
        return groups
            .map { group -> if (group.size == 1) group.single() else combine(group) }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .mapIndexed { index, region -> region.copy(id = index + 1, readingOrder = index) }
    }

    /** Returns the inter-line gap when the group may continue, null otherwise. */
    private fun joinGap(group: List<TextRegionRecord>, next: TextRegionRecord): Float? {
        val previous = group.last()
        if (!isEligible(previous) || !isEligible(next)) return null
        val lineCount = group.sumOf { it.lines.size.coerceAtLeast(1) } +
            next.lines.size.coerceAtLeast(1)
        if (lineCount > MAX_PARAGRAPH_LINES) return null

        val lastLine = previous.lines.lastOrNull()
        val firstLine = next.lines.firstOrNull()
        val a = lastLine?.bounds ?: previous.bounds
        val b = firstLine?.bounds ?: next.bounds
        if (a.width <= 0f || b.width <= 0f) return null
        if (a.height < MIN_LINE_HEIGHT || b.height < MIN_LINE_HEIGHT) return null

        val reference = max(a.height, b.height)
        val heightRatio = reference / min(a.height, b.height)
        if (heightRatio > MAX_HEIGHT_RATIO) return null

        val gap = b.top - a.bottom
        if (gap < -reference * MAX_OVERLAP_RATIO) return null
        if (gap > reference * MAX_GAP_RATIO) return null

        val overlap = min(a.right, b.right) - max(a.left, b.left)
        val overlapRatio = overlap / min(a.width, b.width).coerceAtLeast(1f)
        val leftAligned = abs(a.left - b.left) <= reference * LEFT_ALIGNMENT_RATIO
        val centerDelta = abs((a.left + a.right) / 2f - (b.left + b.right) / 2f)
        val centerAligned = centerDelta <= min(a.width, b.width) * CENTER_ALIGNMENT_SPREAD
        if (!leftAligned && !centerAligned && overlapRatio < MIN_HORIZONTAL_OVERLAP) return null

        val previousAngle = lastLine?.angleDegrees
        val nextAngle = firstLine?.angleDegrees
        if (previousAngle != null && nextAngle != null &&
            abs(previousAngle - nextAngle) > MAX_ANGLE_DELTA
        ) {
            return null
        }

        // Separate list items and labels end with punctuation and still sit on
        // the same leading; a wrapped paragraph continues in the middle of a
        // phrase, so the punctuation plus the normal gap marks a new region.
        if (endsSentence(previous.text) && gap > reference * SENTENCE_BREAK_GAP_RATIO) return null
        return gap
    }

    private fun isEligible(region: TextRegionRecord): Boolean =
        region.text.isNotBlank() &&
            !region.isVertical &&
            !region.isCurved &&
            !region.isFormula &&
            !region.isProtected

    private fun combine(group: List<TextRegionRecord>): TextRegionRecord {
        val ordered = group.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val bounds = Bounds2D(
            left = ordered.minOf { it.bounds.left },
            top = ordered.minOf { it.bounds.top },
            right = ordered.maxOf { it.bounds.right },
            bottom = ordered.maxOf { it.bounds.bottom },
        )
        val lines = ordered.flatMap { region ->
            region.lines.ifEmpty { listOf(fallbackLine(region)) }
        }
        var weightedConfidence = 0f
        var weight = 0
        ordered.forEach { region ->
            val confidence = region.confidence ?: return@forEach
            val characters = region.text.count { it.isLetterOrDigit() }.coerceAtLeast(1)
            weightedConfidence += confidence * characters
            weight += characters
        }
        val confidence = if (weight > 0) {
            weightedConfidence / weight
        } else {
            ordered.mapNotNull { it.confidence }.averageOrNull()
        }
        return TextRegionRecord(
            id = ordered.first().id,
            text = ordered.joinToString("\n") { it.text.trim() }.trim(),
            bounds = bounds,
            polygon = bounds.toPolygon(),
            script = ordered.firstNotNullOfOrNull { it.script },
            readingOrder = ordered.first().readingOrder,
            confidence = confidence,
            overallConfidence = confidence,
            lines = lines,
            preprocessingVariant = ordered.firstNotNullOfOrNull { it.preprocessingVariant },
        )
    }

    private fun fallbackLine(region: TextRegionRecord): TextLineRecord = TextLineRecord(
        text = region.text,
        bounds = region.bounds,
        polygon = region.polygon,
        angleDegrees = null,
        confidence = region.confidence,
        elements = emptyList(),
    )

    private fun endsSentence(text: String): Boolean {
        val trimmed = text.trimEnd().trimEnd { it in TRAILING_MARKS }
        val last = trimmed.lastOrNull() ?: return false
        return last in SENTENCE_ENDINGS
    }

    private fun List<Float>.averageOrNull(): Float? =
        takeIf { it.isNotEmpty() }?.average()?.toFloat()

    private fun Bounds2D.toPolygon(): List<Point2D> = listOf(
        Point2D(left, top),
        Point2D(right, top),
        Point2D(right, bottom),
        Point2D(left, bottom),
    )

    private const val MIN_LINE_HEIGHT = 3f
    private const val MAX_HEIGHT_RATIO = 1.4f
    private const val MAX_OVERLAP_RATIO = 0.45f
    private const val MAX_GAP_RATIO = 0.6f
    private const val SENTENCE_BREAK_GAP_RATIO = 0.2f
    private const val LEFT_ALIGNMENT_RATIO = 0.85f
    private const val CENTER_ALIGNMENT_SPREAD = 0.25f
    private const val MIN_HORIZONTAL_OVERLAP = 0.5f
    private const val MAX_ANGLE_DELTA = 2.5f
    private const val MAX_PARAGRAPH_LINES = 10
    private const val TRAILING_MARKS = "\"'»”’)]}"
    private val SENTENCE_ENDINGS = charArrayOf('.', '!', '?', ';', ':', '…')
}
