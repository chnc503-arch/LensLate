package com.lenslate.app.pipeline

import android.graphics.Bitmap
import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.Point2D
import com.lenslate.app.model.TextLineRecord
import com.lenslate.app.model.TextRegionRecord
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OCR boxes can be narrower than the ink they describe: a missed letter at the
 * end of a line leaves original ink outside the region. Every later stage
 * inherits that error — the restorer leaves a ghost over the unerased glyphs,
 * and the font matcher reads the cropped ink as the whole line, so the
 * translation comes out squeezed.
 *
 * This pass measures each line against the source pixels instead of trusting
 * the box: a row projection isolates the line itself, a column projection finds
 * where its ink starts and ends. A box is only pulled in over a margin the mask
 * shows to be empty, so a recognised glyph is never cut away, and only lines
 * the projection can really describe are allowed to widen.
 */
internal object OcrLineBoxRefiner {

    fun refine(regions: List<TextRegionRecord>, bitmap: Bitmap): List<TextRegionRecord> {
        if (regions.isEmpty() || bitmap.width < MIN_BITMAP_SIDE || bitmap.height < MIN_BITMAP_SIDE) {
            return regions
        }
        return regions.map { refineRegion(it, bitmap) }
    }

    private fun refineRegion(region: TextRegionRecord, bitmap: Bitmap): TextRegionRecord {
        val skip = skipReason(region)
        if (skip != null) {
            if (DEBUG_ALIGN) {
                android.util.Log.d("LensLateAlign", "skip «${region.text.take(18)}» $skip")
            }
            return region
        }

        var changed = false
        val lines = region.lines.map { line ->
            val refined = refineLine(line, bitmap)
            if (refined !== line) changed = true
            refined
        }
        if (!changed) return region
        val boxes = lines.mapNotNull { it.bounds }
        if (boxes.isEmpty() || boxes.size != lines.size) return region
        // The region is the ink its lines stand on, so the union of the measured
        // boxes replaces the old extent: lines the pass could not measure keep
        // their own box and still hold the block open.
        val bounds = Bounds2D(
            left = boxes.minOf { it.left },
            top = region.bounds.top,
            right = boxes.maxOf { it.right },
            bottom = region.bounds.bottom,
        )
        return region.copy(bounds = bounds, polygon = bounds.toPolygon(), lines = lines)
    }

    /**
     * Projections here assume a horizontal line of ink on a locally flat
     * backdrop, so anything they cannot describe is left untouched rather than
     * guessed at.
     */
    private fun skipReason(region: TextRegionRecord): String? = when {
        region.isVertical -> "vertical"
        region.isCurved -> "curved"
        region.isFormula -> "formula"
        region.isProtected -> "protected"
        region.bounds.width < 1f || region.bounds.height < 1f -> "empty"
        region.bounds.height > region.bounds.width * VERTICAL_ASPECT -> "tall"
        region.lines.any { abs(it.angleDegrees ?: 0f) > MAX_TILTED_ANGLE_DEGREES } -> "steep"
        else -> null
    }

    private fun refineLine(line: TextLineRecord, bitmap: Bitmap): TextLineRecord {
        val bounds = line.bounds ?: return line
        if (!bounds.left.isFinite() || !bounds.top.isFinite() ||
            !bounds.right.isFinite() || !bounds.bottom.isFinite()
        ) {
            return line
        }
        val left = floor(bounds.left).toInt()
        val top = floor(bounds.top).toInt()
        val right = ceil(bounds.right).toInt()
        val bottom = ceil(bounds.bottom).toInt()
        if (right - left < MIN_LINE_WIDTH || bottom - top < MIN_LINE_HEIGHT) return line

        // The window must reach past the bad box on both sides; one line width
        // is enough on this page class and keeps unrelated graphics out.
        val padX = max(MIN_WINDOW_PAD, right - left)
        val padY = max(MIN_WINDOW_PAD, ((bottom - top) * WINDOW_PAD_Y).roundToInt())
        val windowLeft = (left - padX).coerceAtLeast(0)
        val windowTop = (top - padY).coerceAtLeast(0)
        val windowRight = (right + padX).coerceAtMost(bitmap.width)
        val windowBottom = (bottom + padY).coerceAtMost(bitmap.height)
        val width = windowRight - windowLeft
        val height = windowBottom - windowTop
        if (width < MIN_WINDOW_SIDE || height < MIN_WINDOW_SIDE) return line

        val pixels = IntArray(width * height)
        try {
            bitmap.getPixels(pixels, 0, width, windowLeft, windowTop, width, height)
        } catch (_: IllegalArgumentException) {
            return line
        }
        val backdrop = backdrop(pixels, width, height) ?: return line
        val backdropLuminance = luminance(backdrop)

        var mask = inkMask(pixels, width, height, 0 until height, backdrop, backdropLuminance)
            ?: return line

        val oldTopLocal = (top - windowTop).coerceIn(0, height - 1)
        val oldBottomLocal = (bottom - windowTop).coerceIn(oldTopLocal + 1, height)
        val band = mainRowRun(mask, width, height, oldTopLocal, oldBottomLocal) ?: return line

        // The brightest pixel of the window is usually a neighbouring graphic,
        // not this line's ink, and a threshold derived from it hides a
        // dark-on-dark heading completely. Once the line's own rows are known,
        // measuring again inside them sees the letters that are really there.
        val lineMask = inkMask(pixels, width, height, band, backdrop, backdropLuminance)
        if (lineMask != null) mask = lineMask

        val bandHeight = band.last - band.first + 1
        val columnGap = max(MIN_COLUMN_GAP, (bandHeight * COLUMN_GAP_RATIO).roundToInt())
        val columnCounts = inkColumns(mask, width, height, band)
        val oldLeftLocal = (left - windowLeft).coerceIn(0, width - 1)
        val oldRightLocal = (right - windowLeft).coerceIn(oldLeftLocal + 1, width)
        val cluster = activeRuns(columnCounts, 1, columnGap)
            .maxByOrNull { overlap(it, oldLeftLocal, oldRightLocal) }
            ?: return line
        if (overlap(cluster, oldLeftLocal, oldRightLocal) <= 0) return line
        // A band whose "ink" touches both window edges is a gradient or texture
        // boundary, not a text line; leave such boxes alone.
        if (cluster.first <= 0 && cluster.last >= width - 1) return line

        val inkLeft = cluster.first + windowLeft
        val inkRight = cluster.last + windowLeft + 1
        // A box can be wider than its line: another pass may have measured a
        // card edge or a graphic beside the text. The margin is discarded only
        // when it holds no ink of this band, so a recognised glyph is never cut.
        val shrinkLeft = left < inkLeft - MIN_SHRINK &&
            !anyInk(mask, width, band, oldLeftLocal, cluster.first)
        val shrinkRight = right > inkRight + MIN_SHRINK &&
            !anyInk(mask, width, band, cluster.last + 1, oldRightLocal)
        // A tilted line's axis-aligned box only approximates the ink, so its
        // measured edges are never trustworthy enough to widen the box; pulling
        // them in towards real ink still reduces the error.
        val grow = abs(line.angleDegrees ?: 0f) <= MAX_ANGLE_DEGREES
        // Ink that reaches the window boundary continues past what was measured,
        // so it is page structure rather than the end of this line: that side of
        // the box keeps its old edge.
        val newLeft = when {
            shrinkLeft -> (inkLeft - EDGE_MARGIN).coerceAtLeast(0)
            grow && cluster.first > 0 -> min(left, inkLeft - EDGE_MARGIN).coerceAtLeast(0)
            else -> left
        }
        val newRight = when {
            shrinkRight -> (inkRight + EDGE_MARGIN).coerceAtMost(bitmap.width)
            grow && cluster.last < width - 1 -> max(right, inkRight + EDGE_MARGIN).coerceAtMost(bitmap.width)
            else -> right
        }
        if (newRight - newLeft == right - left) return line
        if (newRight - newLeft > (right - left) * MAX_GROWTH) return line
        if (DEBUG_ALIGN) {
            android.util.Log.d(
                "LensLateAlign",
                "«${line.text.take(18)}» $left..$right -> $newLeft..$newRight " +
                    "ink=$inkLeft..$inkRight band=${band.first}..${band.last}",
            )
        }

        val refined = Bounds2D(
            left = newLeft.toFloat(),
            top = bounds.top,
            right = newRight.toFloat(),
            bottom = bounds.bottom,
        )
        return line.copy(bounds = refined, polygon = refined.toPolygon())
    }

    /**
     * Ink of the rows in [rows]: the strongest departure from the backdrop sets
     * the scale, and everything that reaches a fraction of it counts as ink.
     * Null when even the strongest pixel is indistinguishable from the page.
     */
    private fun inkMask(
        pixels: IntArray,
        width: Int,
        height: Int,
        rows: IntRange,
        backdrop: FloatArray,
        backdropLuminance: Float,
    ): BooleanArray? {
        var maxDistance = 0f
        var inkLight = true
        for (y in rows) {
            val row = y * width
            for (x in 0 until width) {
                val pixel = pixels[row + x]
                val distance = distanceSq(pixel, backdrop)
                if (distance > maxDistance) {
                    maxDistance = distance
                    inkLight = luminance(pixel) >= backdropLuminance
                }
            }
        }
        if (maxDistance < MIN_MAX_DISTANCE_SQ) return null
        val threshold = max(MIN_INK_DISTANCE_SQ, maxDistance * INK_THRESHOLD_RATIO)
        val mask = BooleanArray(pixels.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val pixel = pixels[row + x]
                if ((luminance(pixel) >= backdropLuminance) != inkLight) continue
                if (distanceSq(pixel, backdrop) >= threshold) mask[row + x] = true
            }
        }
        return mask
    }

    /**
     * Ink height per column over the line's own rows. A column that carries at
     * least as much ink just outside those rows as inside them belongs to
     * something spanning the page — a vignette, a panel edge, a texture — and is
     * reported as empty, because it cannot be the side of a glyph.
     */
    private fun inkColumns(
        mask: BooleanArray,
        width: Int,
        height: Int,
        band: IntRange,
    ): IntArray {
        val bandHeight = band.last - band.first + 1
        val counts = IntArray(width)
        for (y in band) {
            val row = y * width
            for (x in 0 until width) if (mask[row + x]) counts[x]++
        }
        val probe = max(BAND_EDGE_PROBE_MIN, (bandHeight * BAND_EDGE_PROBE_RATIO).roundToInt())
        val outside = IntArray(width)
        for (y in (band.first - probe).coerceAtLeast(0) until band.first) {
            val row = y * width
            for (x in 0 until width) if (mask[row + x]) outside[x]++
        }
        for (y in (band.last + 1) until (band.last + 1 + probe).coerceAtMost(height)) {
            val row = y * width
            for (x in 0 until width) if (mask[row + x]) outside[x]++
        }
        for (x in 0 until width) if (outside[x] >= counts[x]) counts[x] = 0
        return counts
    }

    /**
     * Rows of the line itself. OCR line boxes may overlap a neighbour by a few
     * pixels on a dark poster, so the run with the most ink — not simply the
     * first and last rows with any ink — decides where the line lives.
     */
    private fun mainRowRun(
        mask: BooleanArray,
        width: Int,
        height: Int,
        oldTop: Int,
        oldBottom: Int,
    ): IntRange? {
        val rowCounts = IntArray(height)
        for (y in 0 until height) {
            val row = y * width
            var count = 0
            for (x in 0 until width) if (mask[row + x]) count++
            rowCounts[y] = count
        }
        val runs = activeRuns(rowCounts, 1, MAX_ROW_GAP)
        if (runs.isEmpty()) return null
        val overlapping = runs.filter { overlap(it, oldTop, oldBottom) > 0 }
        val chosen = (overlapping.ifEmpty { runs }).maxByOrNull { run ->
            var mass = 0
            for (y in run.first..run.last) mass += rowCounts[y]
            mass
        } ?: return null
        return chosen
    }

    private fun backdrop(pixels: IntArray, width: Int, height: Int): FloatArray? {
        val samples = ArrayList<Int>(BACKDROP_SAMPLE_LIMIT)
        val stepX = max(1, width / BACKDROP_EDGE_SAMPLES)
        val stepY = max(1, height / BACKDROP_EDGE_SAMPLES)
        for (y in 0 until min(2, height)) {
            var x = 0
            while (x < width) { samples += pixels[y * width + x]; x += stepX }
        }
        for (y in max(0, height - 2) until height) {
            var x = 0
            while (x < width) { samples += pixels[y * width + x]; x += stepX }
        }
        for (x in 0 until min(2, width)) {
            var y = 0
            while (y < height) { samples += pixels[y * width + x]; y += stepY }
        }
        for (x in max(0, width - 2) until width) {
            var y = 0
            while (y < height) { samples += pixels[y * width + x]; y += stepY }
        }
        if (samples.size < MIN_BACKDROP_SAMPLES) return null
        val reds = IntArray(samples.size)
        val greens = IntArray(samples.size)
        val blues = IntArray(samples.size)
        samples.forEachIndexed { index, color ->
            reds[index] = (color shr 16) and 0xFF
            greens[index] = (color shr 8) and 0xFF
            blues[index] = color and 0xFF
        }
        reds.sort()
        greens.sort()
        blues.sort()
        val middle = samples.size / 2
        return floatArrayOf(
            reds[middle].toFloat(),
            greens[middle].toFloat(),
            blues[middle].toFloat(),
        )
    }

    private fun activeRuns(counts: IntArray, floor: Int, maxGap: Int): List<IntRange> {
        val result = ArrayList<IntRange>()
        var start = -1
        var lastActive = -1
        for (index in counts.indices) {
            if (counts[index] < floor) continue
            if (start < 0) {
                start = index
            } else if (index - lastActive > maxGap + 1) {
                result += start..lastActive
                start = index
            }
            lastActive = index
        }
        if (start >= 0) result += start..lastActive
        return result
    }

    private fun overlap(run: IntRange, start: Int, end: Int): Int =
        max(0, min(run.last + 1, end) - max(run.first, start))

    private fun anyInk(
        mask: BooleanArray,
        width: Int,
        band: IntRange,
        startX: Int,
        endX: Int,
    ): Boolean {
        val from = startX.coerceIn(0, width)
        val to = endX.coerceIn(from, width)
        for (y in band) {
            val row = y * width
            for (x in from until to) if (mask[row + x]) return true
        }
        return false
    }

    private fun luminance(color: FloatArray): Float =
        0.2126f * color[0] + 0.7152f * color[1] + 0.0722f * color[2]

    private fun luminance(color: Int): Float =
        0.2126f * ((color shr 16) and 0xFF) +
            0.7152f * ((color shr 8) and 0xFF) +
            0.0722f * (color and 0xFF)

    private fun distanceSq(color: Int, backdrop: FloatArray): Float {
        val red = ((color shr 16) and 0xFF) - backdrop[0]
        val green = ((color shr 8) and 0xFF) - backdrop[1]
        val blue = (color and 0xFF) - backdrop[2]
        return red * red + green * green + blue * blue
    }

    private fun Bounds2D.toPolygon(): List<Point2D> = listOf(
        Point2D(left, top),
        Point2D(right, top),
        Point2D(right, bottom),
        Point2D(left, bottom),
    )

    private const val MIN_BITMAP_SIDE = 16
    private const val MIN_LINE_WIDTH = 6
    private const val MIN_LINE_HEIGHT = 4
    private const val MIN_WINDOW_PAD = 24
    private const val MIN_WINDOW_SIDE = 8
    private const val WINDOW_PAD_Y = 0.8f
    private const val VERTICAL_ASPECT = 2.2f
    private const val MAX_ANGLE_DEGREES = 2f
    // Past this tilt the horizontal projection no longer describes the line at
    // all, and the box is left as the engine reported it.
    private const val MAX_TILTED_ANGLE_DEGREES = 7f
    private const val MIN_MAX_DISTANCE_SQ = 60f
    private const val MIN_INK_DISTANCE_SQ = 10f
    private const val INK_THRESHOLD_RATIO = 0.16f
    private const val MAX_ROW_GAP = 1
    private const val MIN_COLUMN_GAP = 4
    // Word spaces scale with the type size; tracked display lines push them to
    // roughly one and a half line heights, while unrelated page graphics sit
    // further away.
    private const val COLUMN_GAP_RATIO = 1.8f
    // Rows sampled above and below the line to tell a glyph stem from page
    // structure that keeps going past it.
    private const val BAND_EDGE_PROBE_MIN = 4
    private const val BAND_EDGE_PROBE_RATIO = 0.35f
    private const val EDGE_MARGIN = 1
    private const val MAX_GROWTH = 3.2f
    private const val MIN_SHRINK = 4
    private const val DEBUG_ALIGN = false
    private const val BACKDROP_EDGE_SAMPLES = 600
    private const val BACKDROP_SAMPLE_LIMIT = 4096
    private const val MIN_BACKDROP_SAMPLES = 16
}
