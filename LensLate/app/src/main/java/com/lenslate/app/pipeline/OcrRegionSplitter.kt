package com.lenslate.app.pipeline

import android.graphics.Bitmap
import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.Point2D
import com.lenslate.app.model.TextLineRecord
import com.lenslate.app.model.TextRegionRecord
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One OCR block can cover lines painted in different inks, for example a pink
 * promo line glued to the grey paragraph above it. Appearance analysis samples
 * a single ink per region and the translator receives the whole block as one
 * sentence, so the promo line would be recoloured and translated with the body.
 * Lines are sampled independently and the block is cut where the ink changes.
 */
internal object OcrRegionSplitter {

    fun split(regions: List<TextRegionRecord>, bitmap: Bitmap): List<TextRegionRecord> {
        if (regions.isEmpty()) return regions
        var changed = false
        val parts = mutableListOf<TextRegionRecord>()
        regions.forEach { region ->
            val split = splitRegion(region, bitmap)
            if (split.size > 1) changed = true
            parts += split
        }
        if (!changed) return regions
        return parts.mapIndexed { index, region ->
            region.copy(id = index + 1, readingOrder = index)
        }
    }

    private fun splitRegion(region: TextRegionRecord, bitmap: Bitmap): List<TextRegionRecord> {
        if (region.isVertical || region.isCurved || region.isFormula || region.isProtected) {
            android.util.Log.d(
                "LensLateSplit",
                "skip id=${region.id} v=${region.isVertical} c=${region.isCurved} " +
                    "f=${region.isFormula} p=${region.isProtected} «${region.text.take(24)}»",
            )
            return listOf(region)
        }
        val lines = region.lines
            .filter { line -> line.bounds != null && line.text.any(Char::isLetterOrDigit) }
            .sortedBy { line -> line.bounds?.top ?: 0f }
        if (lines.size < 2) return listOf(region)

        val inks = lines.map { line -> sampleLineInk(bitmap, line.bounds!!) }
        android.util.Log.d(
            "LensLateSplit",
            "id=${region.id} lines=${lines.size} inks=" +
                lines.mapIndexed { index, line ->
                    "%02X@%.0f-%.0f".format(inks[index] ?: 0, line.bounds!!.top, line.bounds.bottom) +
                        "«${line.text.take(12)}»"
                }.joinToString(" | "),
        )
        val groups = groupLines(
            heights = lines.map { line -> line.bounds!!.bottom - line.bounds.top },
            inks = inks,
        ).map { indices -> indices.map { lines[it] } }
        android.util.Log.d(
            "LensLateSplit",
            "id=${region.id} groups=${groups.size} " +
                groups.map { group -> group.size.toString() }.joinToString("+"),
        )
        if (groups.size < 2) return listOf(region)
        return groups.map { group -> rebuild(region, group) }
    }

    /**
     * Line indices that share one typographic setting. Ink alone is not enough:
     * a poster headline and the caption under it are often printed in the same
     * colour, and then the whole block gets the caption's size and one merged
     * sentence. A jump in glyph height is a hand-set boundary, so it cuts.
     */
    internal fun groupLines(heights: List<Float>, inks: List<Int?>): List<List<Int>> {
        val groups = mutableListOf<MutableList<Int>>()
        val groupInk = mutableListOf<Int?>()
        val groupHeight = mutableListOf<Float>()
        heights.forEachIndexed { index, height ->
            val ink = inks.getOrNull(index)
            val previousInk = groupInk.lastOrNull()
            val previousHeight = groupHeight.lastOrNull()
            val inkChanged = previousInk != null && ink != null && differentInk(previousInk, ink)
            val sizeChanged = previousHeight != null &&
                height >= SIZE_SPLIT_FLOOR && previousHeight >= SIZE_SPLIT_FLOOR &&
                max(height, previousHeight) / min(height, previousHeight) >= SIZE_SPLIT_RATIO
            if (groups.isEmpty() || inkChanged || sizeChanged) {
                groups += mutableListOf(index)
                groupInk += ink
                groupHeight += height
            } else {
                groups.last() += index
                // An unreadable line between two blocks must not hide the
                // boundary behind it: keep the last known ink as the group's.
                if (groupInk.last() == null) groupInk[groupInk.lastIndex] = ink
            }
        }
        return groups
    }

    private fun rebuild(region: TextRegionRecord, lines: List<TextLineRecord>): TextRegionRecord {
        val boxes = lines.mapNotNull { it.bounds }
        val bounds = Bounds2D(
            left = boxes.minOf { it.left },
            top = boxes.minOf { it.top },
            right = boxes.maxOf { it.right },
            bottom = boxes.maxOf { it.bottom },
        )
        var weightedConfidence = 0f
        var weight = 0
        lines.forEach { line ->
            val confidence = line.confidence ?: return@forEach
            val characters = line.text.count(Char::isLetterOrDigit).coerceAtLeast(1)
            weightedConfidence += confidence * characters
            weight += characters
        }
        val confidence = if (weight > 0) weightedConfidence / weight else region.confidence
        return region.copy(
            text = lines.joinToString("\n") { it.text.trim() }.trim(),
            bounds = bounds,
            polygon = bounds.toPolygon(),
            confidence = confidence,
            overallConfidence = confidence,
            lines = lines,
            characterColors = emptyList(),
            characterStyles = emptyList(),
        )
    }

    /**
     * Ink is the colour of the pixels farthest from the local background. A
     * single dominant colour per line is enough: the splitter only cares
     * whether two lines belong to one ink family, not about gradients inside
     * a line.
     */
    private fun sampleLineInk(bitmap: Bitmap, bounds: Bounds2D): Int? {
        val left = bounds.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = bounds.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = bounds.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width < 2 || height < 2) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)

        val histogram = IntArray(HISTOGRAM_BUCKETS)
        val stride = max(1, sqrt(pixels.size.toFloat() / MAX_LINE_SAMPLES).toInt())
        var sampled = 0
        var index = 0
        while (index < pixels.size) {
            val pixel = pixels[index]
            histogram[bucket(pixel)]++
            sampled++
            index += stride
        }
        if (sampled < MIN_LINE_SAMPLES) return null

        val backgroundBucket = histogram.indices.maxByOrNull { histogram[it] } ?: return null
        var red = 0L
        var green = 0L
        var blue = 0L
        var backgroundPixels = 0
        index = 0
        while (index < pixels.size) {
            val pixel = pixels[index]
            if (bucket(pixel) == backgroundBucket) {
                red += pixel shr 16 and 0xFF
                green += pixel shr 8 and 0xFF
                blue += pixel and 0xFF
                backgroundPixels++
            }
            index += stride
        }
        if (backgroundPixels == 0) return null
        val background = floatArrayOf(
            red.toFloat() / backgroundPixels,
            green.toFloat() / backgroundPixels,
            blue.toFloat() / backgroundPixels,
        )

        val distances = FloatArray(sampled)
        var cursor = 0
        var maxDistance = 0f
        index = 0
        while (index < pixels.size) {
            val pixel = pixels[index]
            val distance = squaredDistance(pixel, background)
            distances[cursor++] = distance
            if (distance > maxDistance) maxDistance = distance
            index += stride
        }
        if (maxDistance < MIN_LINE_CONTRAST * MIN_LINE_CONTRAST) return null

        distances.sort()
        // A wide line box holds mostly background: the top fifth of its pixels
        // still includes a halo of anti-aliased edges, which drains the colour
        // of dark blurred ink. Core pixels (half the peak distance and above)
        // keep the hue saturated enough to tell a muted pink promo line from
        // the grey paragraph above it.
        val percentileFloor = distances[(distances.size * INK_PERCENTILE).toInt().coerceIn(0, distances.lastIndex)]
        val floor = max(percentileFloor, maxDistance * INK_CORE_SHARE)
        red = 0L
        green = 0L
        blue = 0L
        var inkPixels = 0
        index = 0
        while (index < pixels.size) {
            val pixel = pixels[index]
            if (squaredDistance(pixel, background) >= floor) {
                red += pixel shr 16 and 0xFF
                green += pixel shr 8 and 0xFF
                blue += pixel and 0xFF
                inkPixels++
            }
            index += stride
        }
        if (inkPixels < MIN_INK_SAMPLES) return null
        return 0xFF000000.toInt() or
            ((red / inkPixels).toInt() shl 16) or
            ((green / inkPixels).toInt() shl 8) or
            (blue / inkPixels).toInt()
    }

    /**
     * Ink families differ by hue as well as by lightness: a pink line against a
     * grey paragraph is far away in RGB and highly saturated. Requiring both
     * keeps light and dark shades of one colour in a single block.
     */
    private fun differentInk(first: Int, second: Int): Boolean {
        if (colorDistance(first, second) < SPLIT_COLOR_DISTANCE) return false
        return abs(chroma(first) - chroma(second)) >= SPLIT_CHROMA_DELTA
    }

    private fun bucket(pixel: Int): Int {
        val red = (pixel shr 16 and 0xFF) shr 4
        val green = (pixel shr 8 and 0xFF) shr 4
        val blue = (pixel and 0xFF) shr 4
        return (red shl 8) or (green shl 4) or blue
    }

    private fun squaredDistance(pixel: Int, background: FloatArray): Float {
        val red = (pixel shr 16 and 0xFF) - background[0]
        val green = (pixel shr 8 and 0xFF) - background[1]
        val blue = (pixel and 0xFF) - background[2]
        return red * red + green * green + blue * blue
    }

    private fun colorDistance(first: Int, second: Int): Float {
        val red = (first shr 16 and 0xFF) - (second shr 16 and 0xFF)
        val green = (first shr 8 and 0xFF) - (second shr 8 and 0xFF)
        val blue = (first and 0xFF) - (second and 0xFF)
        return sqrt((red * red + green * green + blue * blue).toFloat())
    }

    private fun chroma(color: Int): Int {
        val red = color shr 16 and 0xFF
        val green = color shr 8 and 0xFF
        val blue = color and 0xFF
        return maxOf(red, green, blue) - minOf(red, green, blue)
    }

    private fun Bounds2D.toPolygon(): List<Point2D> = listOf(
        Point2D(left, top),
        Point2D(right, top),
        Point2D(right, bottom),
        Point2D(left, bottom),
    )

    private const val MAX_LINE_SAMPLES = 4000
    private const val MIN_LINE_SAMPLES = 40
    private const val MIN_INK_SAMPLES = 8
    private const val INK_PERCENTILE = 0.8f
    private const val INK_CORE_SHARE = 0.5f
    private const val MIN_LINE_CONTRAST = 45f
    // A typeset line of body copy keeps its height from line to line, so a
    // quarter of growth means a different style was started, not a wrap.
    private const val SIZE_SPLIT_RATIO = 1.25f
    // Below a glyph there is no typographic signal left, only OCR noise.
    private const val SIZE_SPLIT_FLOOR = 16f
    // Ink under a blur overlay loses most of its brightness, so a grey/pink
    // pair can sit closer in RGB than two crisp colours would. Saturation
    // carries the distinguishing evidence; the distance floor only has to
    // reject two shades of the same grey.
    private const val SPLIT_COLOR_DISTANCE = 40f
    private const val SPLIT_CHROMA_DELTA = 30
    private const val HISTOGRAM_BUCKETS = 4096
}
