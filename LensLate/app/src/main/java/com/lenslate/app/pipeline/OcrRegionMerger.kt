package com.lenslate.app.pipeline

import com.lenslate.app.model.Point2D
import com.lenslate.app.model.TextRegionRecord
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class OcrRegionCandidate(
    val passName: String,
    val passScore: Float,
    val region: TextRegionRecord,
)

internal data class OcrRegionMergeResult(
    val regions: List<TextRegionRecord>,
    val contributingPassNames: Set<String>,
)

/** Combines independent OCR passes while suppressing duplicate detections in source coordinates. */
internal class OcrRegionMerger {

    fun merge(candidates: List<OcrRegionCandidate>): OcrRegionMergeResult {
        if (candidates.isEmpty()) return OcrRegionMergeResult(emptyList(), emptySet())

        val winners = mutableListOf<RankedRegion>()
        candidates
            .filter { it.region.text.isNotBlank() }
            .map { RankedRegion(it, quality(it)) }
            .sortedByDescending { it.quality }
            .forEach { candidate ->
                val duplicateIndex = winners.indexOfFirst { isDuplicate(candidate.candidate.region, it.candidate.region) }
                if (duplicateIndex < 0) {
                    winners += candidate
                } else if (shouldReplace(winners[duplicateIndex], candidate)) {
                    winners[duplicateIndex] = candidate
                }
            }

        val ordered = winners.sortedWith(
            compareBy<RankedRegion> { it.candidate.region.bounds.top }
                .thenBy { it.candidate.region.bounds.left }
                .thenBy { it.candidate.region.readingOrder },
        )
        val regions = ordered.mapIndexed { index, ranked ->
            ranked.candidate.region.copy(id = index + 1, readingOrder = index)
        }
        return OcrRegionMergeResult(
            regions = regions,
            contributingPassNames = ordered.mapTo(linkedSetOf()) { it.candidate.passName },
        )
    }

    private fun quality(candidate: OcrRegionCandidate): Float {
        val passScore = candidate.passScore.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val digitBonus = if (candidate.region.text.any(Char::isDigit)) DIGIT_QUALITY_BONUS else 0f
        val confidence = candidate.region.confidence
            ?.takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: return passScore + digitBonus
        return 0.75f * confidence + 0.25f * passScore + digitBonus
    }

    /** Digit-containing readings must survive near-tie duplicates so numeric text is not dropped. */
    private fun shouldReplace(existing: RankedRegion, incoming: RankedRegion): Boolean {
        val qualityGap = incoming.quality - existing.quality
        if (
            existing.candidate.region.text.any(Char::isDigit) &&
            incoming.candidate.region.text.any(Char::isDigit)
        ) {
            // Prefer the more complete reading when the two recognizers roughly agree: the longer
            // text usually contains the digits plus the characters around them ("2024年" over "2024").
            val lengthDifference = normalizedLength(incoming.candidate.region.text) -
                normalizedLength(existing.candidate.region.text)
            if (lengthDifference != 0 && abs(qualityGap) <= DIGIT_PREFERENCE_QUALITY_MARGIN) {
                return lengthDifference > 0
            }
        }
        return qualityGap > 0f
    }

    private fun normalizedLength(text: String): Int = text.filterNot(Char::isWhitespace).length

    private fun isDuplicate(first: TextRegionRecord, second: TextRegionRecord): Boolean {
        val firstPolygon = polygon(first)
        val secondPolygon = polygon(second)
        val smallerArea = min(polygonArea(firstPolygon), polygonArea(secondPolygon))
        if (smallerArea <= 0f) return false

        val overlapOfSmaller = polygonIntersectionArea(firstPolygon, secondPolygon) / smallerArea
        return overlapOfSmaller >= HIGH_GEOMETRY_OVERLAP ||
            (overlapOfSmaller >= MIN_GEOMETRY_OVERLAP && textSimilarity(first.text, second.text) >= MIN_TEXT_SIMILARITY)
    }

    private fun polygon(region: TextRegionRecord): List<Point2D> = region.polygon
        .takeIf { points -> points.size >= 3 && points.all { it.x.isFinite() && it.y.isFinite() } }
        ?: region.bounds.let { bounds ->
            listOf(
                Point2D(bounds.left, bounds.top),
                Point2D(bounds.right, bounds.top),
                Point2D(bounds.right, bounds.bottom),
                Point2D(bounds.left, bounds.bottom),
            )
        }

    private fun polygonIntersectionArea(subject: List<Point2D>, clip: List<Point2D>): Float {
        val orientation = if (signedPolygonArea(clip) >= 0f) 1f else -1f
        var output = subject
        for (edgeIndex in clip.indices) {
            if (output.isEmpty()) return 0f
            val edgeStart = clip[edgeIndex]
            val edgeEnd = clip[(edgeIndex + 1) % clip.size]
            val input = output
            val clipped = mutableListOf<Point2D>()
            var previous = input.last()
            for (current in input) {
                val previousSide = cross(edgeStart, edgeEnd, previous)
                val currentSide = cross(edgeStart, edgeEnd, current)
                val previousInside = orientation * previousSide >= -GEOMETRY_EPSILON
                val currentInside = orientation * currentSide >= -GEOMETRY_EPSILON
                if (currentInside) {
                    if (!previousInside) clipped += lineIntersection(previous, current, previousSide, currentSide)
                    clipped += current
                } else if (previousInside) {
                    clipped += lineIntersection(previous, current, previousSide, currentSide)
                }
                previous = current
            }
            output = clipped
        }
        return polygonArea(output)
    }

    private fun lineIntersection(
        start: Point2D,
        end: Point2D,
        startSide: Float,
        endSide: Float,
    ): Point2D {
        val denominator = startSide - endSide
        if (abs(denominator) <= GEOMETRY_EPSILON) return end
        val fraction = (startSide / denominator).coerceIn(0f, 1f)
        return Point2D(
            x = start.x + fraction * (end.x - start.x),
            y = start.y + fraction * (end.y - start.y),
        )
    }

    private fun cross(start: Point2D, end: Point2D, point: Point2D): Float =
        (end.x - start.x) * (point.y - start.y) - (end.y - start.y) * (point.x - start.x)

    private fun polygonArea(points: List<Point2D>): Float = abs(signedPolygonArea(points))

    private fun signedPolygonArea(points: List<Point2D>): Float {
        if (points.size < 3) return 0f
        var area = 0f
        points.forEachIndexed { index, point ->
            val next = points[(index + 1) % points.size]
            area += point.x * next.y - next.x * point.y
        }
        return area / 2f
    }

    private fun textSimilarity(first: String, second: String): Float {
        val firstBigrams = bigrams(first)
        val secondBigrams = bigrams(second)
        if (firstBigrams.isEmpty() && secondBigrams.isEmpty()) return 1f
        if (firstBigrams.isEmpty() || secondBigrams.isEmpty()) return 0f
        val intersection = firstBigrams.count { it in secondBigrams }
        return intersection.toFloat() / (firstBigrams.size + secondBigrams.size - intersection)
    }

    private fun bigrams(value: String): Set<String> {
        val normalized = value.lowercase().filterNot(Char::isWhitespace)
        if (normalized.isEmpty()) return emptySet()
        if (normalized.length == 1) return setOf(normalized)
        return (0 until normalized.lastIndex).mapTo(mutableSetOf()) { index ->
            normalized.substring(index, index + 2)
        }
    }

    private data class RankedRegion(
        val candidate: OcrRegionCandidate,
        val quality: Float,
    )

    private companion object {
        const val HIGH_GEOMETRY_OVERLAP = 0.78f
        const val MIN_GEOMETRY_OVERLAP = 0.38f
        const val MIN_TEXT_SIMILARITY = 0.42f
        const val GEOMETRY_EPSILON = 1e-4f
        const val DIGIT_QUALITY_BONUS = 0.06f
        const val DIGIT_PREFERENCE_QUALITY_MARGIN = 0.2f
    }
}
