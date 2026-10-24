package com.lenslate.app.pipeline

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Per-glyph ink sampling that adapts to the backdrop instead of trusting one
 * colour measured around the whole paragraph.
 *
 * A poster often has a gradient or textured background: the paragraph median
 * can sit far from the backdrop behind a particular glyph, so every pixel of
 * that glyph looks like ink. The sampler reads narrow strips above and below
 * the glyph, builds a local top-to-bottom backdrop ramp and filters out strips
 * that are contaminated by neighbouring text.
 *
 * Pure Kotlin on purpose: the same maths runs in production and in the desktop
 * lab tests, without an Android Bitmap.
 */
internal object InkColorSampler {

    data class Backdrop(val top: Int, val bottom: Int) {
        fun colorAt(fraction: Float): Int {
            if (top == bottom) return top
            val t = fraction.coerceIn(0f, 1f)
            val a = lerp(alpha(top), alpha(bottom), t)
            val r = lerp(red(top), red(bottom), t)
            val g = lerp(green(top), green(bottom), t)
            val b = lerp(blue(top), blue(bottom), t)
            return argb(a, r, g, b)
        }
    }

    /**
     * How the ink colour of a glyph is chosen from its pixels.
     *
     * [LOCAL] is the production path: median of strong pixels against the local
     * backdrop. [PEAK] averages the most-covered quartile instead of the median,
     * which keeps more saturation when blur has no fully covered core, and
     * accepts very low contrast so a washed-out glyph is not replaced by the
     * paragraph colour. It is experimental and only runs in the lab until it is
     * approved for scanning.
     */
    enum class Mode { LOCAL, PEAK }

    fun estimateBackdrop(
        topStrip: IntArray,
        bottomStrip: IntArray,
        globalBackground: Int,
        inkColor: Int,
    ): Backdrop {
        val top = usableStripColor(topStrip, globalBackground, inkColor)
        val bottom = usableStripColor(bottomStrip, globalBackground, inkColor)
        return when {
            top != null && bottom != null -> {
                if (distanceSq(top, bottom) > MAX_BACKDROP_RAMP_DISTANCE) {
                    // The two strips disagree too much to be one smooth
                    // backdrop; one of them is probably a neighbouring graphic.
                    if (distanceSq(top, globalBackground) <= distanceSq(bottom, globalBackground)) {
                        Backdrop(top, top)
                    } else {
                        Backdrop(bottom, bottom)
                    }
                } else {
                    Backdrop(top, bottom)
                }
            }
            top != null -> Backdrop(top, top)
            bottom != null -> Backdrop(bottom, bottom)
            else -> Backdrop(globalBackground, globalBackground)
        }
    }

    /**
     * Samples the strongest ink core of one glyph against the local backdrop.
     * Anti-aliased edges are a blend with the background and are discarded by
     * keeping only colours at least [CHARACTER_STRONG_RATIO] of the maximum
     * distance.
     */
    fun sampleGlyphColor(
        pixels: IntArray,
        width: Int,
        glyphTop: Int,
        glyphBottom: Int,
        backdrop: Backdrop,
        fallbackInk: Int,
        mode: Mode = Mode.LOCAL,
    ): Int {
        val rows = glyphBottom - glyphTop
        if (width <= 0 || rows <= 0) return fallbackInk
        if (pixels.size < width * (glyphTop + rows)) return fallbackInk

        val distances = FloatArray(width * rows)
        var maximumDistance = 0f
        for (row in 0 until rows) {
            val fraction = if (rows <= 1) 0.5f else row.toFloat() / (rows - 1)
            val reference = backdrop.colorAt(fraction)
            val rowOffset = (glyphTop + row) * width
            for (x in 0 until width) {
                val pixel = pixels[rowOffset + x]
                if (alpha(pixel) < MIN_CHARACTER_ALPHA) continue
                val distance = distanceSq(pixel, reference)
                val index = row * width + x
                distances[index] = distance
                if (distance > maximumDistance) maximumDistance = distance
            }
        }
        val floor = if (mode == Mode.PEAK) MIN_PEAK_CHARACTER_DISTANCE_SQ else MIN_CHARACTER_DISTANCE_SQ
        if (maximumDistance < floor) return fallbackInk

        val threshold = max(floor, maximumDistance * CHARACTER_STRONG_RATIO)
        val candidates = ArrayList<Int>()
        val candidateDistances = ArrayList<Float>()
        for (index in distances.indices) {
            if (distances[index] >= threshold) {
                candidates += pixels[glyphTop * width + index]
                candidateDistances += distances[index]
            }
        }
        if (candidates.isEmpty()) return fallbackInk
        return when (mode) {
            Mode.LOCAL -> medianColor(candidates)
            Mode.PEAK -> peakColor(candidates, candidateDistances)
        }
    }

    /**
     * Mean of the most-covered quartile. Blur leaves no pixel at full coverage,
     * so the median sits halfway between ink and backdrop; the strongest pixels
     * stay closest to the designed colour.
     */
    private fun peakColor(colors: List<Int>, distances: List<Float>): Int {
        val order = colors.indices.sortedByDescending { distances[it] }
        val take = (order.size * PEAK_SHARE).roundToInt().coerceIn(1, order.size)
        var alphaSum = 0
        var redSum = 0
        var greenSum = 0
        var blueSum = 0
        for (index in 0 until take) {
            val color = colors[order[index]]
            alphaSum += alpha(color)
            redSum += red(color)
            greenSum += green(color)
            blueSum += blue(color)
        }
        return argb(
            alphaSum / take,
            redSum / take,
            greenSum / take,
            blueSum / take,
        )
    }

    private fun usableStripColor(strip: IntArray, globalBackground: Int, inkColor: Int): Int? {
        if (strip.size < MIN_STRIP_PIXELS) return null
        val opaque = strip.filter { alpha(it) >= MIN_STRIP_ALPHA }
        if (opaque.size < MIN_STRIP_PIXELS) return null
        val color = medianColor(opaque)
        val toInk = distanceSq(color, inkColor)
        val toGlobal = distanceSq(color, globalBackground)
        // A strip mostly covered by glyphs (an adjacent tight line, a logo
        // edge) is not a backdrop. Real local backgrounds may legitimately
        // differ from the paragraph median, so only clearly ink-like strips
        // are rejected.
        if (toInk < toGlobal && toInk < MAX_STRIP_TO_INK) return null
        return color
    }

    private fun medianColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return 0
        val reds = IntArray(colors.size) { red(colors[it]) }.apply { sort() }
        val greens = IntArray(colors.size) { green(colors[it]) }.apply { sort() }
        val blues = IntArray(colors.size) { blue(colors[it]) }.apply { sort() }
        val alphas = IntArray(colors.size) { alpha(colors[it]) }.apply { sort() }
        val middle = colors.size / 2
        return argb(alphas[middle], reds[middle], greens[middle], blues[middle])
    }

    /** Mean squared channel difference in 0..1, the same metric as StyleAnalyzer. */
    internal fun distanceSq(first: Int, second: Int): Float {
        val dr = (red(first) - red(second)) / 255f
        val dg = (green(first) - green(second)) / 255f
        val db = (blue(first) - blue(second)) / 255f
        return (dr * dr + dg * dg + db * db) / 3f
    }

    internal fun colorDistance(first: Int, second: Int): Float = sqrt(distanceSq(first, second))

    private fun lerp(first: Int, second: Int, fraction: Float): Int =
        (first + (second - first) * fraction).toInt().coerceIn(0, 255)

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private fun alpha(color: Int): Int = (color ushr 24) and 0xFF
    private fun red(color: Int): Int = (color shr 16) and 0xFF
    private fun green(color: Int): Int = (color shr 8) and 0xFF
    private fun blue(color: Int): Int = color and 0xFF

    private const val MIN_CHARACTER_ALPHA = 40
    private const val MIN_STRIP_ALPHA = 40
    private const val MIN_CHARACTER_DISTANCE_SQ = 0.045f
    private const val MIN_PEAK_CHARACTER_DISTANCE_SQ = 0.0025f
    private const val CHARACTER_STRONG_RATIO = 0.62f
    private const val PEAK_SHARE = 0.25f
    private const val MIN_STRIP_PIXELS = 8
    private const val MAX_STRIP_TO_INK = 0.12f
    private const val MAX_BACKDROP_RAMP_DISTANCE = 0.85f
}
