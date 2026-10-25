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
     * Which side of the paper the glyphs live on. White-on-white posters put
     * the ink *above* the background while their drop shadow sits below it;
     * a region-wide "farthest colour" therefore reports the shadow as ink.
     * Comparing how many pixels support each side picks the strokes, because
     * they cover more of the glyph than their offset shadow does.
     */
    data class InkEstimate(
        val color: Int,
        val distanceSq: Float,
        val light: Boolean,
        val support: Int,
        val haloColor: Int?,
        val haloDistanceSq: Float,
        val haloSupport: Int,
        val haloOffsetX: Float,
        val haloOffsetY: Float,
    )

    fun estimateInk(
        pixels: IntArray,
        width: Int,
        background: Int,
    ): InkEstimate? {
        if (pixels.isEmpty() || width <= 0) return null
        val stride = max(1, sqrt(pixels.size.toFloat() / MAX_INK_SAMPLES).toInt())
        val backgroundLuminance = luminance(background)
        var lightMax = 0f
        var darkMax = 0f
        var index = 0
        var samples = 0
        while (index < pixels.size) {
            val pixel = pixels[index]
            if (alpha(pixel) >= MIN_INK_SAMPLE_ALPHA) {
                val distance = distanceSq(pixel, background)
                if (luminance(pixel) >= backgroundLuminance) {
                    if (distance > lightMax) lightMax = distance
                } else if (distance > darkMax) {
                    darkMax = distance
                }
                samples++
            }
            index += stride
        }
        if (samples == 0) return null
        val lightBand = lightMax * SIDE_BAND_RATIO
        val darkBand = darkMax * SIDE_BAND_RATIO
        var lightCount = 0
        var darkCount = 0
        var lightX = 0.0
        var lightY = 0.0
        var darkX = 0.0
        var darkY = 0.0
        index = 0
        while (index < pixels.size) {
            val pixel = pixels[index]
            if (alpha(pixel) >= MIN_INK_SAMPLE_ALPHA) {
                val distance = distanceSq(pixel, background)
                val x = index % width
                val y = index / width
                if (luminance(pixel) >= backgroundLuminance) {
                    if (distance >= lightBand) {
                        lightCount++
                        lightX += x
                        lightY += y
                    }
                } else if (distance >= darkBand) {
                    darkCount++
                    darkX += x
                    darkY += y
                }
            }
            index += stride
        }
        val lightWins = lightCount >= darkCount
        val inkMax = if (lightWins) lightMax else darkMax
        val inkSupport = if (lightWins) lightCount else darkCount
        val haloMax = if (lightWins) darkMax else lightMax
        val haloSupport = if (lightWins) darkCount else lightCount
        val minimumSupport = max(MIN_INK_SAMPLE_SUPPORT, samples / MIN_INK_SUPPORT_DIVISOR)
        if (inkMax < MIN_INK_SIGNAL_SQ || inkSupport < minimumSupport) return null

        val inkBand = inkMax * SIDE_BAND_RATIO
        val haloBand = haloMax * SIDE_BAND_RATIO
        val inkColors = ArrayList<Int>()
        val haloColors = ArrayList<Int>()
        var inkX = 0.0
        var inkY = 0.0
        var countedInk = 0
        var haloX = 0.0
        var haloY = 0.0
        var countedHalo = 0
        index = 0
        while (index < pixels.size) {
            val pixel = pixels[index]
            if (alpha(pixel) >= MIN_INK_SAMPLE_ALPHA) {
                val distance = distanceSq(pixel, background)
                val light = luminance(pixel) >= backgroundLuminance
                if (light == lightWins) {
                    if (distance >= inkBand) {
                        inkColors += pixel
                        inkX += index % width
                        inkY += index / width
                        countedInk++
                    }
                } else if (distance >= haloBand) {
                    haloColors += pixel
                    haloX += index % width
                    haloY += index / width
                    countedHalo++
                }
            }
            index += stride
        }
        if (inkColors.isEmpty()) return null
        val haloPresent = haloColors.isNotEmpty() &&
            haloMax >= MIN_HALO_SIGNAL_SQ &&
            countedHalo.toFloat() / max(1, countedInk) >= MIN_HALO_SUPPORT_RATIO
        val offsetX = if (haloPresent && countedHalo > 0 && countedInk > 0) {
            (haloX / countedHalo - inkX / countedInk).toFloat()
        } else {
            0f
        }
        val offsetY = if (haloPresent && countedHalo > 0 && countedInk > 0) {
            (haloY / countedHalo - inkY / countedInk).toFloat()
        } else {
            0f
        }
        return InkEstimate(
            color = medianColor(inkColors),
            distanceSq = inkMax,
            light = lightWins,
            support = inkSupport,
            haloColor = if (haloPresent) medianColor(haloColors) else null,
            haloDistanceSq = haloMax,
            haloSupport = haloSupport,
            haloOffsetX = offsetX,
            haloOffsetY = offsetY,
        )
    }

    /**
     * How the ink colour of a glyph is chosen from its pixels.
     *
     * [LOCAL] is the previous scan path: median of strong pixels against the
     * local backdrop. [PEAK] averages the most-covered quartile instead of the
     * median, which keeps more saturation when blur has no fully covered core,
     * and accepts very low contrast so a washed-out glyph is not replaced by
     * the paragraph colour. [ADAPTIVE] now drives the scan: it keeps the exact
     * LOCAL result whenever a glyph has a solid ink core and switches to PEAK
     * only for faint ink, where LOCAL would discard the sampled colour. Only
     * PEAK stays test-only.
     */
    enum class Mode { LOCAL, PEAK, ADAPTIVE }

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
        inkLight: Boolean? = null,
    ): Int {
        sampleGlyphCore(pixels, width, glyphTop, glyphBottom, backdrop, mode, inkLight)
            ?.let { return it }
        // A local gradient can flip the expected polarity; the unconstrained
        // pass keeps the old behaviour instead of dropping the glyph colour.
        if (inkLight != null) {
            sampleGlyphCore(pixels, width, glyphTop, glyphBottom, backdrop, mode, null)
                ?.let { return it }
        }
        return fallbackInk
    }

    /**
     * Replace the letters that were sampled as nothing with the line's own ink.
     * A glyph box which lost its strokes still answers with the anti-aliased
     * fringe around it, and painted, that colour is an invisible letter inside a
     * readable word. The neighbours say what this line's ink is worth against
     * their local paper, so a sample that keeps only a share of the distance the
     * rest of the line keeps was never ink.
     *
     * The bar is relative on purpose: a white-on-white poster is faint for every
     * letter at once, and there is no outlier to correct.
     */
    fun reconcileWeakSamples(
        samples: List<Int>,
        backdrops: List<Int>,
        fallback: Int,
    ): List<Int> {
        if (samples.size < MIN_RECONCILED_SAMPLES || samples.size != backdrops.size) return samples
        val distances = samples.mapIndexed { index, color -> distanceSq(color, backdrops[index]) }
        val floor = medianValue(distances) * MIN_LINE_INK_SHARE
        val strong = samples.filterIndexed { index, _ -> distances[index] >= floor }
        if (strong.isEmpty() || strong.size == samples.size) return samples
        // A line that is mostly failed samples has nothing to learn from itself;
        // the ink measured over the whole block is the better answer.
        val shared = if (strong.size * 2 >= samples.size) medianColor(strong) else fallback
        return samples.mapIndexed { index, color ->
            if (distances[index] >= floor) color else shared
        }
    }

    private fun medianValue(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        return values.sorted()[values.size / 2]
    }

    /**
     * [inkLight] restricts sampling to pixels on the same side of the local
     * backdrop as the region ink. Without it a white glyph's dark drop shadow
     * is the strongest signal inside the glyph box and is sampled instead.
     */
    private fun sampleGlyphCore(
        pixels: IntArray,
        width: Int,
        glyphTop: Int,
        glyphBottom: Int,
        backdrop: Backdrop,
        mode: Mode,
        inkLight: Boolean?,
    ): Int? {
        val rows = glyphBottom - glyphTop
        if (width <= 0 || rows <= 0) return null
        if (pixels.size < width * (glyphTop + rows)) return null

        val distances = FloatArray(width * rows)
        var maximumDistance = 0f
        for (row in 0 until rows) {
            val fraction = if (rows <= 1) 0.5f else row.toFloat() / (rows - 1)
            val reference = backdrop.colorAt(fraction)
            val referenceLuminance = luminance(reference)
            val rowOffset = (glyphTop + row) * width
            for (x in 0 until width) {
                val pixel = pixels[rowOffset + x]
                if (alpha(pixel) < MIN_CHARACTER_ALPHA) continue
                if (inkLight != null &&
                    (luminance(pixel) >= referenceLuminance) != inkLight
                ) {
                    continue
                }
                val distance = distanceSq(pixel, reference)
                val index = row * width + x
                distances[index] = distance
                if (distance > maximumDistance) maximumDistance = distance
            }
        }
        val acceptsFaintInk = mode != Mode.LOCAL
        val floor = if (acceptsFaintInk) MIN_PEAK_CHARACTER_DISTANCE_SQ else MIN_CHARACTER_DISTANCE_SQ
        if (maximumDistance < floor) return null

        val threshold = max(floor, maximumDistance * CHARACTER_STRONG_RATIO)
        val candidates = ArrayList<Int>()
        val candidateDistances = ArrayList<Float>()
        for (index in distances.indices) {
            if (distances[index] >= threshold) {
                candidates += pixels[glyphTop * width + index]
                candidateDistances += distances[index]
            }
        }
        if (candidates.isEmpty()) return null
        val faint = maximumDistance < MIN_CHARACTER_DISTANCE_SQ
        return when (mode) {
            Mode.LOCAL -> medianColor(candidates)
            Mode.PEAK -> peakColor(candidates, candidateDistances)
            Mode.ADAPTIVE -> if (faint) {
                peakColor(candidates, candidateDistances)
            } else {
                medianColor(candidates)
            }
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
    private fun luminance(color: Int): Float =
        (0.2126f * red(color) + 0.7152f * green(color) + 0.0722f * blue(color)) / 255f

    private const val MIN_CHARACTER_ALPHA = 40
    private const val MAX_INK_SAMPLES = 90_000
    private const val MIN_INK_SAMPLE_ALPHA = 40
    private const val SIDE_BAND_RATIO = 0.5f
    private const val MIN_INK_SIGNAL_SQ = 0.0008f
    private const val MIN_HALO_SIGNAL_SQ = 0.004f
    private const val MIN_HALO_SUPPORT_RATIO = 0.05f
    private const val MIN_INK_SAMPLE_SUPPORT = 4
    private const val MIN_INK_SUPPORT_DIVISOR = 300
    private const val MIN_STRIP_ALPHA = 40
    private const val MIN_CHARACTER_DISTANCE_SQ = 0.045f
    // Extreme overexposure leaves barely ten grey levels between ink and
    // paper; the old 0.0025 floor discarded exactly those glyphs.
    private const val MIN_PEAK_CHARACTER_DISTANCE_SQ = 0.0005f

    /** A line sample must keep this share of what the rest of the line keeps. */
    private const val MIN_LINE_INK_SHARE = 0.35f
    private const val MIN_RECONCILED_SAMPLES = 3
    private const val CHARACTER_STRONG_RATIO = 0.62f
    private const val PEAK_SHARE = 0.25f
    private const val MIN_STRIP_PIXELS = 8
    private const val MAX_STRIP_TO_INK = 0.12f
    private const val MAX_BACKDROP_RAMP_DISTANCE = 0.85f
}
