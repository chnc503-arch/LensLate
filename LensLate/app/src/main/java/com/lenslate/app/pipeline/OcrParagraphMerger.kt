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

    fun merge(
        regions: List<TextRegionRecord>,
        lexical: Boolean = false,
    ): List<TextRegionRecord> {
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
        val merged = groups.map { group -> if (group.size == 1) group.single() else combine(group) }
        // Independent passes re-read the same line at different stretches, so the
        // same sentence survives as a full paragraph and as a handful of contained
        // fragments. Those fragments would otherwise be translated and drawn on
        // top of the paragraph.
        val deduped = deduplicate(merged, lexical)
        if (merged.size == regions.size && deduped.size == regions.size) return regions
        return deduped
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .mapIndexed { index, region -> region.copy(id = index + 1, readingOrder = index) }
    }

    private fun deduplicate(
        regions: List<TextRegionRecord>,
        lexical: Boolean,
    ): List<TextRegionRecord> {
        if (regions.size < 2) return regions
        val ranked = regions.sortedWith(
            compareByDescending<TextRegionRecord> { coverageRank(it, lexical) }
                .thenByDescending { paragraphRank(it, lexical) },
        )
        val kept = mutableListOf<TextRegionRecord>()
        var changed = false
        ranked.forEach { region ->
            val hostIndex = kept.indexOfFirst { survivor ->
                isFragmentOf(region, survivor) || absorbsInto(region, survivor)
            }
            if (hostIndex >= 0) {
                changed = true
                val host = kept[hostIndex]
                // Different passes cut the same paragraph at different lines, so a
                // fragment can sit just outside its host box while repeating a line
                // the host already contains. Absorbing it widens the box to the true
                // ink extent; otherwise the line is drawn twice and its original is
                // never erased.
                if (absorbsInto(region, host)) kept[hostIndex] = absorb(host, region)
            } else {
                kept += region
            }
        }
        return if (changed) kept else regions
    }

    private fun absorbsInto(fragment: TextRegionRecord, host: TextRegionRecord): Boolean {
        val fragmentText = normalizedText(fragment.text)
        if (fragmentText.isEmpty()) return false
        // Only a line cut from the same paragraph counts: its words already live
        // in the host text. Merely similar neighbours are different columns, not
        // fragments.
        if (!normalizedText(host.text).contains(fragmentText)) return false
        val gap = max(
            host.bounds.top - fragment.bounds.bottom,
            fragment.bounds.top - host.bounds.bottom,
        )
        // Side-by-side blocks overlap vertically; they must stay separate.
        if (gap < 0f) return false
        val reference = max(host.bounds.height, fragment.bounds.height)
        return gap <= reference * ABSORB_GAP_RATIO
    }

    private fun absorb(host: TextRegionRecord, fragment: TextRegionRecord): TextRegionRecord {
        val bounds = Bounds2D(
            left = min(host.bounds.left, fragment.bounds.left),
            top = min(host.bounds.top, fragment.bounds.top),
            right = max(host.bounds.right, fragment.bounds.right),
            bottom = max(host.bounds.bottom, fragment.bounds.bottom),
        )
        val lines = mutableListOf<TextLineRecord>()
        (host.lines + fragment.lines)
            .mapNotNull { line -> line.bounds?.let { bounds -> line to bounds } }
            .sortedWith(compareBy({ it.second.top }, { it.second.left }))
            .forEach { (line, lineBounds) ->
                val duplicate = lines.any { existing ->
                    val existingBounds = existing.bounds ?: return@any false
                    val overlap = min(existingBounds.bottom, lineBounds.bottom) -
                        max(existingBounds.top, lineBounds.top)
                    overlap > min(existingBounds.height, lineBounds.height) * ABSORB_LINE_OVERLAP
                }
                if (!duplicate) lines += line
            }
        return host.copy(bounds = bounds, polygon = bounds.toPolygon(), lines = lines)
    }

    private fun paragraphRank(region: TextRegionRecord, lexical: Boolean): Float {
        val confidence = region.confidence
            ?.takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: 0.5f
        val plausibility = if (lexical) {
            CyrillicReadingPlausibility.lexicalQuality(region.text)
        } else {
            CyrillicReadingPlausibility.score(region.text)
        }
        val letters = region.text.count(Char::isLetter).coerceAtMost(RANK_LETTER_CAP)
        return 0.62f * confidence + 0.3f * plausibility +
            0.08f * (letters.toFloat() / RANK_LETTER_CAP)
    }

    /**
     * The more text a box covers, the more complete its reading is. Confidence
     * decides between boxes of comparable size, so a very confident one-line
     * fragment never displaces the paragraph that already contains it.
     */
    private fun coverageRank(region: TextRegionRecord, lexical: Boolean): Float =
        region.bounds.width * region.bounds.height *
            (COVERAGE_QUALITY_FLOOR + (1f - COVERAGE_QUALITY_FLOOR) * paragraphRank(region, lexical))

    private fun isFragmentOf(candidate: TextRegionRecord, survivor: TextRegionRecord): Boolean {
        val overlap = overlapArea(candidate.bounds, survivor.bounds)
        if (overlap <= 0f) return false
        val candidateArea = candidate.bounds.width * candidate.bounds.height
        if (candidateArea <= 0f) return false
        val candidateShare = overlap / candidateArea
        if (candidateShare >= CONTAINED_FRAGMENT_OVERLAP) return true
        if (candidateShare >= PARTIAL_FRAGMENT_OVERLAP &&
            textSimilarity(candidate.text, survivor.text) >= FRAGMENT_TEXT_SIMILARITY
        ) {
            return true
        }
        val candidateText = normalizedText(candidate.text)
        return candidateShare >= SHALLOW_FRAGMENT_OVERLAP &&
            candidateText.isNotEmpty() &&
            normalizedText(survivor.text).contains(candidateText)
    }

    private fun overlapArea(a: Bounds2D, b: Bounds2D): Float {
        val width = min(a.right, b.right) - max(a.left, b.left)
        val height = min(a.bottom, b.bottom) - max(a.top, b.top)
        return if (width <= 0f || height <= 0f) 0f else width * height
    }

    private fun normalizedText(text: String): String =
        text.lowercase().filter(Char::isLetterOrDigit)

    private fun textSimilarity(first: String, second: String): Float {
        val a = normalizedText(first)
        val b = normalizedText(second)
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a.length < 2 || b.length < 2) return if (a == b) 1f else 0f
        val aBigrams = (0 until a.lastIndex).mapTo(HashSet()) { a.substring(it, it + 2) }
        val bBigrams = (0 until b.lastIndex).mapTo(HashSet()) { b.substring(it, it + 2) }
        val intersection = aBigrams.count { it in bBigrams }
        return intersection.toFloat() / (aBigrams.size + bBigrams.size - intersection)
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
        val boundaryDuplicate = sharesBoundaryLine(previous, next)
        if (heightRatio > MAX_HEIGHT_RATIO && !boundaryDuplicate) return null

        val gap = b.top - a.bottom
        if (gap < -reference * MAX_OVERLAP_RATIO && !boundaryDuplicate) return null
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
        // Complementary readings of the same paragraph share a boundary line,
        // which is not a new paragraph even when that line ends a sentence.
        if (!boundaryDuplicate &&
            endsSentence(previous.text) &&
            gap > reference * SENTENCE_BREAK_GAP_RATIO
        ) {
            return null
        }
        return gap
    }

    /**
     * Two passes can cut a paragraph at different lines, leaving an upper block
     * whose last line and a lower paragraph whose first line are the same
     * physical line. Those blocks are complementary readings, not neighbouring
     * paragraphs, so the usual sentence-break guard must not separate them.
     */
    private fun sharesBoundaryLine(previous: TextRegionRecord, next: TextRegionRecord): Boolean {
        if (previous.lines.size < 2 && next.lines.size < 2) return false
        val last = lastTextLine(previous.text) ?: return false
        val first = firstTextLine(next.text) ?: return false
        if (!isRepeatedBoundaryLine(last, first)) return false
        val overlap = min(previous.bounds.right, next.bounds.right) -
            max(previous.bounds.left, next.bounds.left)
        val narrower = min(previous.bounds.width, next.bounds.width).coerceAtLeast(1f)
        return overlap / narrower >= MIN_BOUNDARY_OVERLAP
    }

    private fun isRepeatedBoundaryLine(first: String, second: String): Boolean {
        val a = normalizedText(first)
        val b = normalizedText(second)
        if (a.length < MIN_BOUNDARY_LETTERS || b.length < MIN_BOUNDARY_LETTERS) return false
        if (a == b) return true
        val shorter = min(a.length, b.length).toFloat()
        val longer = max(a.length, b.length).toFloat()
        if (shorter / longer < MIN_BOUNDARY_LENGTH_RATIO) return false
        return textSimilarity(first, second) >= BOUNDARY_LINE_SIMILARITY
    }

    private fun lastTextLine(text: String): String? =
        text.lines().map(String::trim).lastOrNull { it.isNotEmpty() }

    private fun firstTextLine(text: String): String? =
        text.lines().map(String::trim).firstOrNull { it.isNotEmpty() }

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
        val lines = mutableListOf<TextLineRecord>()
        ordered.forEach { region ->
            region.lines.ifEmpty { listOf(fallbackLine(region)) }.forEach { line ->
                val lineBounds = line.bounds
                val duplicate = lineBounds != null && lines.any { existing ->
                    val existingBounds = existing.bounds ?: return@any false
                    val vertical = min(existingBounds.bottom, lineBounds.bottom) -
                        max(existingBounds.top, lineBounds.top)
                    val horizontal = min(existingBounds.right, lineBounds.right) -
                        max(existingBounds.left, lineBounds.left)
                    vertical > min(existingBounds.height, lineBounds.height) * ABSORB_LINE_OVERLAP &&
                        horizontal > min(existingBounds.width, lineBounds.width) * ABSORB_LINE_OVERLAP
                }
                if (!duplicate) lines += line
            }
        }
        val combinedText = combineText(ordered)
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
            text = combinedText,
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

    /**
     * Joins the text of complementary blocks while keeping one copy of the line
     * they both read. Without this, an upper block and the paragraph below it
     * repeat their shared boundary line and the translator sees it twice.
     */
    private fun combineText(ordered: List<TextRegionRecord>): String {
        val lines = mutableListOf<String>()
        ordered.forEach { region ->
            val regionLines = region.text.lines().map(String::trim).filter(String::isNotEmpty)
            var dropped = 0
            while (dropped < regionLines.size &&
                dropped < MAX_BOUNDARY_DUPLICATES &&
                lines.size - 1 - dropped >= 0 &&
                isRepeatedBoundaryLine(lines[lines.size - 1 - dropped], regionLines[dropped])
            ) {
                dropped++
            }
            regionLines.drop(dropped).forEach { lines += it }
        }
        return lines.joinToString("\n")
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
    private const val RANK_LETTER_CAP = 120
    private const val COVERAGE_QUALITY_FLOOR = 0.55f
    private const val CONTAINED_FRAGMENT_OVERLAP = 0.55f
    private const val PARTIAL_FRAGMENT_OVERLAP = 0.3f
    private const val SHALLOW_FRAGMENT_OVERLAP = 0.15f
    private const val FRAGMENT_TEXT_SIMILARITY = 0.5f
    private const val ABSORB_GAP_RATIO = 0.35f
    private const val ABSORB_LINE_OVERLAP = 0.5f
    private const val MIN_BOUNDARY_LETTERS = 8
    private const val MIN_BOUNDARY_LENGTH_RATIO = 0.6f
    private const val BOUNDARY_LINE_SIMILARITY = 0.85f
    private const val MIN_BOUNDARY_OVERLAP = 0.45f
    private const val MAX_BOUNDARY_DUPLICATES = 3
    private val SENTENCE_ENDINGS = charArrayOf('.', '!', '?', ';', ':', '…')
}
