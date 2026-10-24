package com.lenslate.app.pipeline

import com.lenslate.app.model.Point2D
import com.lenslate.app.model.TextCurve
import com.lenslate.app.model.TextRegionRecord
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Fits a circle through the glyph centres of a single-line region and keeps the
 * fit only when the bend is strong and coherent enough to be real. Straight
 * lines (including barely curved ones) intentionally return null so the
 * renderer never bends ordinary text.
 */
internal object CurvedTextAnalyzer {

    fun analyze(region: TextRegionRecord): TextCurve? {
        if (region.lines.size != 1) return null
        val line = region.lines.first()
        val symbols = line.elements
            .flatMap { it.symbols }
            .filter { it.text.isNotBlank() }
        if (symbols.size < MIN_SYMBOLS) return null
        val points = symbols.mapNotNull { symbol ->
            val bounds = symbol.bounds ?: return@mapNotNull null
            if (bounds.width <= 0f || bounds.height <= 2f || !bounds.height.isFinite()) {
                return@mapNotNull null
            }
            Point2D((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
        }
        if (points.size < MIN_SYMBOLS) return null

        val heights = symbols.mapNotNull { it.bounds?.height }.filter { it > 1f }.sorted()
        if (heights.isEmpty()) return null
        val glyphHeight = heights[heights.size / 2]
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val width = maxX - minX
        if (width < glyphHeight * MIN_WIDTH_HEIGHT_RATIO) return null

        val first = points.first()
        val last = points.last()
        if (last.x - first.x < width * MIN_LEFT_TO_RIGHT_RATIO) return null

        // A real arc departs noticeably from the chord between its endpoints.
        val chordLength = distance(first, last).coerceAtLeast(1f)
        val normalX = -(last.y - first.y) / chordLength
        val normalY = (last.x - first.x) / chordLength
        var maxDeviation = 0f
        points.forEach { point ->
            val deviation = abs((point.x - first.x) * normalX + (point.y - first.y) * normalY)
            if (deviation > maxDeviation) maxDeviation = deviation
        }
        if (maxDeviation < max(glyphHeight * MIN_DEVIATION_RATIO, MIN_DEVIATION_PX)) return null

        val circle = fitCircle(points) ?: return null
        if (!circle.radius.isFinite() || circle.radius <= 1f) return null
        if (circle.radius > width * MAX_RADIUS_TO_WIDTH) return null
        if (circle.radius < width * MIN_RADIUS_TO_WIDTH) {
            // Very tight logos can wrap around a small circle; keep only when the
            // angular span is large enough to justify it.
            if (circle.radius < width * TIGHT_RADIUS_TO_WIDTH) return null
        }
        var squaredError = 0.0
        points.forEach { point ->
            val distance = distance(point, circle.centerX, circle.centerY) - circle.radius
            squaredError += distance * distance
        }
        val rms = sqrt(squaredError / points.size).toFloat()
        if (rms > max(glyphHeight * MAX_RMS_RATIO, MAX_RMS_PX)) return null

        val angles = points.map { point ->
            Math.toDegrees(
                atan2(
                    (point.y - circle.centerY).toDouble(),
                    (point.x - circle.centerX).toDouble(),
                ),
            ).toFloat()
        }
        val unwrapped = unwrap(angles) ?: return null
        val span = unwrapped.last() - unwrapped.first()
        if (abs(span) < MIN_SPAN_DEGREES || abs(span) > MAX_SPAN_DEGREES) return null
        val deltas = unwrapped.zipWithNext { a, b -> b - a }
        val meaningful = deltas.filter { abs(it) >= MIN_DELTA_DEGREES }
        if (meaningful.size < 3) return null
        val direction = if (span > 0f) 1f else -1f
        val coherent = meaningful.count { it * direction > 0f }.toFloat() / meaningful.size
        if (coherent < MIN_DIRECTION_COHERENCE) return null

        val confidence = (
            0.5f +
                0.25f * (1f - (rms / max(glyphHeight * MAX_RMS_RATIO, 0.1f))).coerceIn(0f, 1f) +
                0.2f * (maxDeviation / (glyphHeight * 1.5f)).coerceIn(0f, 1f)
            ).coerceIn(0.25f, 0.97f)
        return TextCurve(
            centerX = circle.centerX,
            centerY = circle.centerY,
            radius = circle.radius,
            startAngleDegrees = unwrapped.first(),
            endAngleDegrees = unwrapped.last(),
            clockwise = span > 0f,
            confidence = confidence,
        )
    }

    private fun fitCircle(points: List<Point2D>): CircleFit? {
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        var sxz = 0.0
        var syz = 0.0
        var sz = 0.0
        points.forEach { point ->
            val x = point.x.toDouble()
            val y = point.y.toDouble()
            val z = x * x + y * y
            sx += x
            sy += y
            sxx += x * x
            syy += y * y
            sxy += x * y
            sxz += x * z
            syz += y * z
            sz += z
        }
        val n = points.size.toDouble()
        val matrix = arrayOf(
            doubleArrayOf(sxx, sxy, sx, -sxz),
            doubleArrayOf(sxy, syy, sy, -syz),
            doubleArrayOf(sx, sy, n, -sz),
        )
        if (!solve3x3(matrix)) return null
        val d = matrix[0][3]
        val e = matrix[1][3]
        val f = matrix[2][3]
        val centerX = (-d / 2.0).toFloat()
        val centerY = (-e / 2.0).toFloat()
        val radiusSquared = centerX * centerX + centerY * centerY - f
        if (!radiusSquared.isFinite() || radiusSquared <= 0.0) return null
        return CircleFit(centerX, centerY, sqrt(radiusSquared).toFloat())
    }

    /** Gaussian elimination with partial pivoting; modifies [matrix] in place. */
    private fun solve3x3(matrix: Array<DoubleArray>): Boolean {
        for (column in 0 until 3) {
            var pivot = column
            for (row in column + 1 until 3) {
                if (abs(matrix[row][column]) > abs(matrix[pivot][column])) pivot = row
            }
            if (abs(matrix[pivot][column]) < 1e-9) return false
            if (pivot != column) {
                val swap = matrix[pivot]
                matrix[pivot] = matrix[column]
                matrix[column] = swap
            }
            val divisor = matrix[column][column]
            for (row in column + 1 until 3) {
                val factor = matrix[row][column] / divisor
                for (index in column until 4) {
                    matrix[row][index] -= factor * matrix[column][index]
                }
            }
        }
        for (row in 2 downTo 0) {
            var value = matrix[row][3]
            for (column in row + 1 until 3) {
                value -= matrix[row][column] * matrix[column][3]
            }
            matrix[row][3] = value / matrix[row][row]
        }
        return true
    }

    private fun unwrap(angles: List<Float>): List<Float>? {
        if (angles.size < 2) return null
        val result = ArrayList<Float>(angles.size)
        result += angles.first()
        var previous = angles.first()
        for (index in 1 until angles.size) {
            var angle = angles[index]
            while (angle - previous > 180f) angle -= 360f
            while (angle - previous < -180f) angle += 360f
            result += angle
            previous = angle
        }
        return result
    }

    private fun distance(first: Point2D, second: Point2D): Float {
        val dx = second.x - first.x
        val dy = second.y - first.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun distance(point: Point2D, x: Float, y: Float): Float {
        val dx = point.x - x
        val dy = point.y - y
        return sqrt(dx * dx + dy * dy)
    }

    private data class CircleFit(val centerX: Float, val centerY: Float, val radius: Float)

    private const val MIN_SYMBOLS = 5
    private const val MIN_WIDTH_HEIGHT_RATIO = 3.0f
    private const val MIN_LEFT_TO_RIGHT_RATIO = 0.55f
    private const val MIN_DEVIATION_RATIO = 0.3f
    private const val MIN_DEVIATION_PX = 2.5f
    private const val MAX_RADIUS_TO_WIDTH = 40f
    private const val MIN_RADIUS_TO_WIDTH = 0.36f
    private const val TIGHT_RADIUS_TO_WIDTH = 0.24f
    private const val MAX_RMS_RATIO = 0.2f
    private const val MAX_RMS_PX = 2.5f
    private const val MIN_SPAN_DEGREES = 18f
    private const val MAX_SPAN_DEGREES = 400f
    private const val MIN_DELTA_DEGREES = 1f
    private const val MIN_DIRECTION_COHERENCE = 0.7f
}
