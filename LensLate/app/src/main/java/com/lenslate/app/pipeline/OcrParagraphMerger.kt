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
        if (regions.isEmpty()) return regions
        val normalized = regions.map(::normalizeLines)
        if (normalized.size < 2) return normalized
        val groups = mutableListOf<MutableList<TextRegionRecord>>()
        normalized
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
        if (merged.size == normalized.size && deduped.size == normalized.size) return normalized
        return deduped
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .mapIndexed { index, region -> region.copy(id = index + 1, readingOrder = index) }
    }

    /**
     * Independent passes attach their own line boxes to the same region. Two
     * boxes of one physical line then look like two source lines to the renderer,
     * which wraps a title and draws the halves over each other. Keep one box per
     * line before any geometry decisions are made.
     */
    private fun normalizeLines(region: TextRegionRecord): TextRegionRecord {
        if (region.lines.size < 2) return region
        val kept = mutableListOf<TextLineRecord>()
        var dropped = false
        region.lines
            .sortedWith(compareBy({ it.bounds?.top ?: Float.MAX_VALUE }, { it.bounds?.left ?: 0f }))
            .forEach { line ->
                val hostIndex = kept.indexOfFirst { existing -> isDuplicateLine(existing, line) }
                if (hostIndex < 0) {
                    kept += line
                } else {
                    dropped = true
                    if (boxHeight(line) > boxHeight(kept[hostIndex])) kept[hostIndex] = line
                }
            }
        if (!dropped) return region
        // The dropped box may have defined the region edges (a stray sliver
        // above a heading widened the block and shifted its frame left), so the
        // frame must follow the lines that survived.
        val bounds = unionBounds(kept)
        return if (bounds == null) {
            region.copy(lines = kept)
        } else {
            region.copy(lines = kept, bounds = bounds, polygon = bounds.toPolygon())
        }
    }

    private fun isDuplicateLine(host: TextLineRecord, candidate: TextLineRecord): Boolean {
        val first = host.bounds ?: return false
        val second = candidate.bounds ?: return false
        val vertical = min(first.bottom, second.bottom) - max(first.top, second.top)
        val horizontal = min(first.right, second.right) - max(first.left, second.left)
        val sameRow = vertical > min(first.height, second.height) * DUPLICATE_LINE_OVERLAP &&
            horizontal > min(first.width, second.width) * DUPLICATE_LINE_OVERLAP
        if (sameRow) {
            if (textSimilarity(host.text, candidate.text) >= DUPLICATE_LINE_SIMILARITY) return true
            // Two passes read one physical line in different words when the ink
            // is faint. Boxes that start and end within a fifth of a line height
            // of each other are that one line, whatever each pass heard.
            val slack = min(first.height, second.height) * DUPLICATE_LINE_COORD_SLACK
            if (abs(first.top - second.top) <= slack && abs(first.bottom - second.bottom) <= slack) {
                return true
            }
        }
        // Complementary passes can split one physical line into a full box and
        // a thin sliver just above or below it. The sliver overlaps the host by
        // nothing vertically, so containment alone leaves two frames for one
        // line and the renderer squeezes the heading into them.
        val small = min(first.height, second.height)
        val large = max(first.height, second.height)
        if (large <= 0f || small > large * SLIVER_MAX_HEIGHT_RATIO) return false
        if (horizontal <= min(first.width, second.width) * DUPLICATE_LINE_OVERLAP) return false
        val gap = max(first.top, second.top) - min(first.bottom, second.bottom)
        if (gap > large * SLIVER_MAX_GAP_RATIO) return false
        // A box a few pixels tall that sits on another line's rows is scanner
        // edge noise: it cannot be a physical line even when its few characters
        // read differently. Keeping it widened the block far to the left.
        if (small <= large * SLIVER_IGNORE_HEIGHT_RATIO && small <= SLIVER_IGNORE_MAX_HEIGHT) {
            return true
        }
        return textSimilarity(host.text, candidate.text) >= DUPLICATE_LINE_SIMILARITY
    }

    /** Adds a line unless it duplicates one already kept; keeps the taller box. */
    private fun appendLine(lines: MutableList<TextLineRecord>, line: TextLineRecord) {
        val hostIndex = lines.indexOfFirst { existing -> isDuplicateLine(existing, line) }
        if (hostIndex < 0) {
            lines += line
        } else if (boxHeight(line) > boxHeight(lines[hostIndex])) {
            lines[hostIndex] = line
        }
    }

    private fun boxHeight(line: TextLineRecord): Float = line.bounds?.height ?: 0f

    private fun unionBounds(lines: List<TextLineRecord>): Bounds2D? {
        val boxes = lines.mapNotNull { it.bounds }
        if (boxes.isEmpty()) return null
        return Bounds2D(
            left = boxes.minOf { it.left },
            top = boxes.minOf { it.top },
            right = boxes.maxOf { it.right },
            bottom = boxes.maxOf { it.bottom },
        )
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
            .filter { it.bounds != null }
            .sortedWith(compareBy({ it.bounds?.top ?: Float.MAX_VALUE }, { it.bounds?.left ?: 0f }))
            .forEach { line -> appendLine(lines, line) }
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
        val lines = mutableListOf<TextLineRecord>()
        ordered.forEach { region ->
            region.lines.ifEmpty { listOf(fallbackLine(region)) }.forEach { line ->
                appendLine(lines, line)
            }
        }
        // Cross-pass duplicates are dropped above; the frame follows the boxes
        // that survived instead of the union of the original detections, whose
        // stray edges shifted the block away from its ink.
        val bounds = unionBounds(lines) ?: Bounds2D(
            left = ordered.minOf { it.bounds.left },
            top = ordered.minOf { it.bounds.top },
            right = ordered.maxOf { it.bounds.right },
            bottom = ordered.maxOf { it.bounds.bottom },
        )
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
    // Posters with airy leading put 0.6-0.75 line heights between body lines;
    // the old 0.6 cap split such a paragraph into three separately translated
    // blocks. Paragraph breaks are separated by much more than one line.
    private const val MAX_GAP_RATIO = 0.75f
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
    private const val DUPLICATE_LINE_OVERLAP = 0.5f
    private const val DUPLICATE_LINE_SIMILARITY = 0.6f
    private const val DUPLICATE_LINE_COORD_SLACK = 0.2f
    private const val SLIVER_MAX_HEIGHT_RATIO = 0.5f
    private const val SLIVER_MAX_GAP_RATIO = 0.35f
    private const val SLIVER_IGNORE_HEIGHT_RATIO = 0.18f

    /** Beyond this many rows a box is a text line, however short against a paragraph. */
    private const val SLIVER_IGNORE_MAX_HEIGHT = 8f
    private const val MIN_BOUNDARY_LETTERS = 8
    private const val MIN_BOUNDARY_LENGTH_RATIO = 0.6f
    private const val BOUNDARY_LINE_SIMILARITY = 0.85f
    private const val MIN_BOUNDARY_OVERLAP = 0.45f
    private const val MAX_BOUNDARY_DUPLICATES = 3
    private val SENTENCE_ENDINGS = charArrayOf('.', '!', '?', ';', ':', '…')
}
