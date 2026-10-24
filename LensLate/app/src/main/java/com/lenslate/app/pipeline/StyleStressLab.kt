package com.lenslate.app.pipeline

import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Synthetic hard cases for overlay colour quality: a blurry photo, an
 * overexposed highlight, low contrast and a textured colour gradient.
 *
 * Each case is generated pixel by pixel with a known ink colour, then sampled
 * with the same [InkColorSampler] the app uses. The report compares three
 * strategies on identical pixels:
 *
 * - `global` — one background colour for the whole page (the old behaviour);
 * - `local` — per-glyph backdrop ramp, median of strong pixels (production);
 * - `peak` — local backdrop, mean of the most-covered quartile (experimental).
 *
 * Pure Kotlin on purpose: the lab screen on a phone and the JVM tests run the
 * exact same generator and measurements.
 */
object StyleStressLab {

    data class CaseResult(
        val id: String,
        val title: String,
        val detail: String,
        val glyphCount: Int,
        val globalError: Float,
        val localError: Float,
        val peakError: Float,
    ) {
        internal val best: InkColorSampler.Mode
            get() = if (peakError < localError) InkColorSampler.Mode.PEAK else InkColorSampler.Mode.LOCAL

        val passed: Boolean
            get() = peakError <= PASS_ERROR && peakError <= localError + PASS_MARGIN
    }

    data class Report(val cases: List<CaseResult>) {
        val passedCount: Int get() = cases.count { it.passed }
        val peakWins: Int get() = cases.count { it.peakError < it.localError }

        val verdict: Verdict
            get() = when {
                cases.isEmpty() -> Verdict.FAIL
                passedCount == cases.size -> Verdict.PASS
                passedCount * 2 >= cases.size -> Verdict.MIXED
                else -> Verdict.FAIL
            }
    }

    enum class Verdict { PASS, MIXED, FAIL }

    fun run(): Report = Report(
        listOf(
            measure(
                id = "gradient",
                title = "Градиент на текстуре",
                detail = "Цвет плавно меняется вдоль строки, фон шумный и с бликами.",
                blurRadius = 1,
                overexposure = false,
                lowContrast = false,
            ),
            measure(
                id = "blur",
                title = "Замыленный текст",
                detail = "Размытие 2 px: полностью покрытых пикселей у штриха почти нет.",
                blurRadius = 2,
                overexposure = false,
                lowContrast = false,
            ),
            measure(
                id = "overexposure",
                title = "Пересвет",
                detail = "Яркое пятно выжигает и фон, и чернила под ним.",
                blurRadius = 0,
                overexposure = true,
                lowContrast = false,
            ),
            measure(
                id = "low-contrast",
                title = "Низкий контраст",
                detail = "Чернила почти сливаются с меняющимся фоном.",
                blurRadius = 1,
                overexposure = false,
                lowContrast = true,
            ),
        ),
    )

    private fun measure(
        id: String,
        title: String,
        detail: String,
        blurRadius: Int,
        overexposure: Boolean,
        lowContrast: Boolean,
    ): CaseResult {
        val page = buildPage(blurRadius, overexposure, lowContrast)
        var globalSum = 0f
        var localSum = 0f
        var peakSum = 0f
        page.glyphs.forEachIndexed { index, glyph ->
            val expected = page.expected[index]
            val global = sample(page, glyph, page.nominalInk, globalBackdrop = true)
            val local = sample(page, glyph, page.nominalInk, globalBackdrop = false)
            val peak = sample(page, glyph, page.nominalInk, globalBackdrop = false, mode = InkColorSampler.Mode.PEAK)
            globalSum += InkColorSampler.colorDistance(global, expected)
            localSum += InkColorSampler.colorDistance(local, expected)
            peakSum += InkColorSampler.colorDistance(peak, expected)
        }
        val count = page.glyphs.size.coerceAtLeast(1)
        return CaseResult(
            id = id,
            title = title,
            detail = detail,
            glyphCount = page.glyphs.size,
            globalError = globalSum / count,
            localError = localSum / count,
            peakError = peakSum / count,
        )
    }

    private fun sample(
        page: Page,
        glyph: GlyphBox,
        nominalInk: Int,
        globalBackdrop: Boolean,
        mode: InkColorSampler.Mode = InkColorSampler.Mode.LOCAL,
    ): Int {
        val pad = ((glyph.bottom - glyph.top) * STRIP_RATIO).roundToInt().coerceIn(MIN_STRIP_PAD, MAX_STRIP_PAD)
        val left = glyph.left.coerceIn(0, page.width - 1)
        val right = glyph.right.coerceIn(left + 1, page.width)
        val top = (glyph.top - pad).coerceIn(0, page.height - 1)
        val bottom = (glyph.bottom + pad).coerceIn(top + 1, page.height)
        val glyphTop = glyph.top.coerceIn(top, bottom - 1) - top
        val glyphBottom = glyph.bottom.coerceIn(top + 1, bottom) - top
        val sampleWidth = right - left
        val sampleHeight = bottom - top
        val crop = IntArray(sampleWidth * sampleHeight)
        for (row in 0 until sampleHeight) {
            System.arraycopy(page.pixels, (top + row) * page.width + left, crop, row * sampleWidth, sampleWidth)
        }
        val backdrop = if (globalBackdrop) {
            InkColorSampler.Backdrop(page.globalBackground, page.globalBackground)
        } else {
            InkColorSampler.estimateBackdrop(
                topStrip = crop.copyOfRange(0, glyphTop * sampleWidth),
                bottomStrip = crop.copyOfRange(glyphBottom * sampleWidth, crop.size),
                globalBackground = page.globalBackground,
                inkColor = nominalInk,
            )
        }
        return InkColorSampler.sampleGlyphColor(
            pixels = crop,
            width = sampleWidth,
            glyphTop = glyphTop,
            glyphBottom = glyphBottom,
            backdrop = backdrop,
            fallbackInk = nominalInk,
            mode = mode,
        )
    }

    private fun buildPage(blurRadius: Int, overexposure: Boolean, lowContrast: Boolean): Page {
        val pixels = IntArray(PAGE_WIDTH * PAGE_HEIGHT)
        for (y in 0 until PAGE_HEIGHT) {
            for (x in 0 until PAGE_WIDTH) {
                val t = x.toFloat() / (PAGE_WIDTH - 1)
                val stripe = (sin(x * 0.06) * 7).roundToInt() + (sin(y * 0.11) * 5).roundToInt()
                val grain = grain(x, y)
                pixels[y * PAGE_WIDTH + x] = argb(
                    255,
                    (26 + 128 * t + stripe + grain).toInt().coerceIn(0, 255),
                    (34 + 44 * t + stripe / 2 + grain).toInt().coerceIn(0, 255),
                    (92 - 54 * t + grain).toInt().coerceIn(0, 255),
                )
            }
        }

        val glyphs = (0 until GLYPH_COUNT).map { index ->
            val left = GLYPH_LEFT + index * GLYPH_STEP
            GlyphBox(left, GLYPH_TOP, left + GLYPH_WIDTH, GLYPH_TOP + GLYPH_HEIGHT)
        }
        val firstCenter = (glyphs.first().left + glyphs.first().right) / 2
        val lastCenter = (glyphs.last().left + glyphs.last().right) / 2
        val inkColors = glyphs.map { glyph ->
            val center = (glyph.left + glyph.right) / 2
            val fraction = (center - firstCenter).toFloat() / (lastCenter - firstCenter)
            StyleGradient.lerpColor(WARM_INK, COLD_INK, fraction)
        }
        val expected = IntArray(glyphs.size)
        glyphs.forEachIndexed { index, glyph ->
            val ink = inkColors[index]
            val centerX = (glyph.left + glyph.right) / 2
            val centerY = (glyph.top + glyph.bottom) / 2
            val localBackground = pixels[centerY * PAGE_WIDTH + centerX]
            val drawn = if (lowContrast) {
                StyleGradient.lerpColor(ink, localBackground, LOW_CONTRAST_MIX)
            } else {
                ink
            }
            expected[index] = if (overexposure) {
                StyleGradient.lerpColor(drawn, WHITE, glareWeight(centerX, centerY))
            } else {
                drawn
            }
            for (y in glyph.top until glyph.bottom) {
                for (x in glyph.left until glyph.right) {
                    val coverage = coverage(glyph, x, y)
                    if (coverage <= 0f) continue
                    val base = pixels[y * PAGE_WIDTH + x]
                    pixels[y * PAGE_WIDTH + x] = StyleGradient.lerpColor(base, drawn, coverage)
                }
            }
        }

        if (overexposure) {
            for (y in 0 until PAGE_HEIGHT) {
                for (x in 0 until PAGE_WIDTH) {
                    val weight = glareWeight(x, y)
                    if (weight <= 0f) continue
                    val index = y * PAGE_WIDTH + x
                    pixels[index] = StyleGradient.lerpColor(pixels[index], WHITE, weight)
                }
            }
        }
        val blurred = boxBlur(pixels, PAGE_WIDTH, PAGE_HEIGHT, blurRadius)

        return Page(
            width = PAGE_WIDTH,
            height = PAGE_HEIGHT,
            pixels = blurred,
            glyphs = glyphs,
            expected = expected,
            globalBackground = blurred[GLOBAL_SAMPLE_ROW * PAGE_WIDTH + GLOBAL_SAMPLE_COLUMN],
            nominalInk = StyleGradient.lerpColor(WARM_INK, COLD_INK, 0.5f),
        )
    }

    private fun coverage(glyph: GlyphBox, x: Int, y: Int): Float {
        val column = x - glyph.left
        val row = y - glyph.top
        val stem = column in 2..4 || column in 9..11
        val bar = row in 10..12 && column in 2..11
        if (stem || bar) return 1f
        val stemRow = row in 2..21
        val stemEdge = stemRow && (column == 1 || column == 5 || column == 8 || column == 12)
        val barEdge = column in 2..11 && (row == 9 || row == 13)
        return if (stemEdge || barEdge) 0.45f else 0f
    }

    private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0) return pixels
        val horizontal = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumRed = 0
                var sumGreen = 0
                var sumBlue = 0
                var count = 0
                for (dx in -radius..radius) {
                    val sx = (x + dx).coerceIn(0, width - 1)
                    val color = pixels[y * width + sx]
                    sumRed += red(color)
                    sumGreen += green(color)
                    sumBlue += blue(color)
                    count++
                }
                horizontal[y * width + x] = argb(255, sumRed / count, sumGreen / count, sumBlue / count)
            }
        }
        val vertical = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumRed = 0
                var sumGreen = 0
                var sumBlue = 0
                var count = 0
                for (dy in -radius..radius) {
                    val sy = (y + dy).coerceIn(0, height - 1)
                    val color = horizontal[sy * width + x]
                    sumRed += red(color)
                    sumGreen += green(color)
                    sumBlue += blue(color)
                    count++
                }
                vertical[y * width + x] = argb(255, sumRed / count, sumGreen / count, sumBlue / count)
            }
        }
        return vertical
    }

    private fun glareWeight(x: Int, y: Int): Float {
        val dx = (x - GLARE_X) / GLARE_RADIUS_X
        val dy = (y - GLARE_Y) / GLARE_RADIUS_Y
        val distance = dx * dx + dy * dy
        if (distance >= 1f) return 0f
        val falloff = 1f - distance
        return falloff * falloff * GLARE_STRENGTH
    }

    private fun grain(x: Int, y: Int): Int {
        var hash = x * 374761393 + y * 668265263
        hash = (hash xor (hash shr 13)) * 1274126177
        return ((hash xor (hash shr 16)) and 0xF) - 7
    }

    private data class GlyphBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private class Page(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
        val glyphs: List<GlyphBox>,
        val expected: IntArray,
        val globalBackground: Int,
        val nominalInk: Int,
    )

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private fun red(color: Int): Int = (color shr 16) and 0xFF
    private fun green(color: Int): Int = (color shr 8) and 0xFF
    private fun blue(color: Int): Int = color and 0xFF

    private const val PAGE_WIDTH = 360
    private const val PAGE_HEIGHT = 120
    private const val GLYPH_COUNT = 7
    private const val GLYPH_LEFT = 22
    private const val GLYPH_STEP = 46
    private const val GLYPH_WIDTH = 14
    private const val GLYPH_TOP = 48
    private const val GLYPH_HEIGHT = 24
    private const val STRIP_RATIO = 0.30f
    private const val MIN_STRIP_PAD = 3
    private const val MAX_STRIP_PAD = 10
    private const val LOW_CONTRAST_MIX = 0.55f
    private const val GLARE_X = 180f
    private const val GLARE_Y = 54f
    private const val GLARE_RADIUS_X = 150f
    private const val GLARE_RADIUS_Y = 74f
    private const val GLARE_STRENGTH = 0.62f
    private const val GLOBAL_SAMPLE_ROW = 3
    private const val GLOBAL_SAMPLE_COLUMN = 3
    private const val PASS_ERROR = 0.08f
    private const val PASS_MARGIN = 0.02f
    private const val WHITE = 0xFFFFFFFF.toInt()
    private val WARM_INK = 0xFFEB283C.toInt()
    private val COLD_INK = 0xFF2855FF.toInt()
}
