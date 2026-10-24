package com.lenslate.app.pipeline

import java.util.Random
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop lab for the hard case the user cares about: glyphs whose colour drifts
 * smoothly across a textured, gradient page. Runs entirely on the JVM, without
 * a phone or emulator, and exercises the same [InkColorSampler] as production.
 */
class ComplexBackgroundLabTest {

    @Test
    fun gradientInkSurvivesATexturedPageAndKeepsItsDrift() {
        val page = buildPage()
        val nominalInk = page.midInk
        val sampled = page.bars.map { box -> page.sample(box, nominalInk) }
        println(
            "LensLate complex-page lab: " +
                sampled.mapIndexed { index, color ->
                    "g$index=${color.toHex()}(expected ${page.expected[index].toHex()})"
                }.joinToString(" "),
        )

        assertTrue("expected ${page.bars.size} glyphs, got ${sampled.size}", sampled.size == page.bars.size)
        val first = sampled.first()
        val last = sampled.last()
        assertTrue("first glyph is not warm: ${first.toHex()}", red(first) - blue(first) > 25)
        assertTrue("last glyph is not cool: ${last.toHex()}", blue(last) - red(last) > 25)
        val fallingRed = sampled.zipWithNext().count { (left, right) -> red(right) <= red(left) + 5 }
        assertTrue(
            "red channel does not drift across the line: $fallingRed of ${sampled.size - 1}",
            fallingRed >= sampled.size - 2,
        )
        sampled.forEachIndexed { index, color ->
            assertColorNear(page.expected[index], color, tolerance = 22)
        }
        val distinct = sampled.map(::quantized).toSet().size
        assertTrue("gradient collapsed to $distinct colours", distinct >= 6)
    }

    @Test
    fun narrowAntiAliasedGlyphsKeepTheirGradient() {
        // Body text has 2 px stems: most of a glyph is anti-aliased edge, and a
        // single sampled colour per glyph must still follow the ink gradient.
        val boxes = (0 until THIN_COUNT).map { index ->
            val left = THIN_LEFT + index * THIN_STEP
            Box(left, THIN_TOP, left + THIN_WIDTH, THIN_TOP + THIN_HEIGHT)
        }
        val firstCenter = (boxes.first().left + boxes.first().right) / 2f
        val lastCenter = (boxes.last().left + boxes.last().right) / 2f
        val expected = boxes.map { box ->
            val center = (box.left + box.right) / 2f
            StyleGradient.lerpColor(WARM_INK, COLD_INK, (center - firstCenter) / (lastCenter - firstCenter))
        }
        val pixels = paintThinPage(boxes, expected)
        val sampled = boxes.map { box ->
            sampleGlyph(
                pixels = pixels,
                pageWidth = THIN_WIDTH_PAGE,
                pageHeight = THIN_HEIGHT_PAGE,
                left = box.left,
                top = box.top,
                right = box.right,
                bottom = box.bottom,
                nominalInk = MID_INK,
                globalBackground = pixels[THIN_WIDTH_PAGE * 4 + 4],
            )
        }

        assertTrue("first thin glyph is not warm: ${sampled.first().toHex()}", red(sampled.first()) - blue(sampled.first()) > 20)
        assertTrue("last thin glyph is not cool: ${sampled.last().toHex()}", blue(sampled.last()) - red(sampled.last()) > 20)
        sampled.zipWithNext().forEach { (left, right) ->
            assertTrue(
                "red channel rises inside the gradient: ${left.toHex()} -> ${right.toHex()}",
                red(right) <= red(left) + 6,
            )
        }
        sampled.forEachIndexed { index, color ->
            assertColorNear(expected[index], color, tolerance = 26)
        }
        val distinct = sampled.map(::quantized).toSet().size
        assertTrue("thin gradient collapsed to $distinct colours", distinct >= 5)
    }

    private fun paintThinPage(boxes: List<Box>, inkColors: List<Int>): IntArray {
        val pixels = IntArray(THIN_WIDTH_PAGE * THIN_HEIGHT_PAGE)
        val random = Random(THIN_SEED)
        for (y in 0 until THIN_HEIGHT_PAGE) {
            val t = y.toFloat() / (THIN_HEIGHT_PAGE - 1)
            for (x in 0 until THIN_WIDTH_PAGE) {
                val grain = random.nextInt(9) - 4
                val stripe = (sin(x * 0.07 + y * 0.11) * 7).roundToInt()
                val red = (42 + 48 * t + grain + stripe).roundToInt().coerceIn(0, 255)
                val green = (30 + 44 * t + grain + stripe / 2).roundToInt().coerceIn(0, 255)
                val blue = (58 + 26 * t + grain).roundToInt().coerceIn(0, 255)
                pixels[y * THIN_WIDTH_PAGE + x] = argb(255, red, green, blue)
            }
        }
        boxes.forEachIndexed { index, box ->
            val ink = inkColors[index]
            for (y in box.top until box.bottom) {
                for (x in box.left until box.right) {
                    val coverage = thinCoverage(box, x)
                    if (coverage <= 0f) continue
                    pixels[y * THIN_WIDTH_PAGE + x] = StyleGradient.lerpColor(
                        pixels[y * THIN_WIDTH_PAGE + x],
                        ink,
                        coverage,
                    )
                }
            }
        }
        return pixels
    }

    private fun thinCoverage(box: Box, x: Int): Float = when (x - box.left) {
        1, 2, 6, 7 -> 1f
        0, 3, 5, 8 -> 0.45f
        else -> 0f
    }

    private fun buildPage(): Page {
        val bars = (0 until BAR_COUNT).map { index ->
            val left = BAR_LEFT + index * BAR_STEP
            Box(left, BAR_TOP, left + BAR_WIDTH, BAR_BOTTOM)
        }
        val firstCenter = (bars.first().left + bars.first().right) / 2f
        val lastCenter = (bars.last().left + bars.last().right) / 2f
        val expected = bars.map { box ->
            val center = (box.left + box.right) / 2f
            val fraction = (center - firstCenter) / (lastCenter - firstCenter)
            StyleGradient.lerpColor(WARM_INK, COLD_INK, fraction)
        }
        return Page(
            width = WIDTH,
            height = HEIGHT,
            pixels = paintPage(bars, expected),
            bars = bars,
            expected = expected,
            midInk = StyleGradient.lerpColor(WARM_INK, COLD_INK, 0.5f),
        )
    }

    private fun paintPage(bars: List<Box>, inkColors: List<Int>): IntArray {
        val pixels = IntArray(WIDTH * HEIGHT)
        val random = Random(SEED)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val t = x.toFloat() / (WIDTH - 1)
                val stripe = (sin(x * 0.05) * 8).roundToInt() + (sin(y * 0.09) * 5).roundToInt()
                val grain = random.nextInt(11) - 5
                var red = (28 + 58 * t + stripe + grain).roundToInt().coerceIn(0, 255)
                var green = (62 + 16 * sin(y * 0.03) - 10 * t + stripe + grain)
                    .roundToInt()
                    .coerceIn(0, 255)
                var blue = (74 - 34 * t + stripe / 2 + grain).roundToInt().coerceIn(0, 255)
                val glow = glowAt(x, y)
                red += ((GLOW_RED - red) * glow).roundToInt()
                green += ((GLOW_GREEN - green) * glow).roundToInt()
                blue += ((GLOW_BLUE - blue) * glow).roundToInt()
                pixels[y * WIDTH + x] = argb(255, red, green, blue)
            }
        }
        bars.forEachIndexed { index, box ->
            val ink = inkColors[index]
            for (y in box.top until box.bottom) {
                for (x in box.left until box.right) {
                    if (x in box.left + STEM_WIDTH until box.left + STEM_WIDTH + GAP_WIDTH) continue
                    val coverage = edgeCoverage(box, x)
                    pixels[y * WIDTH + x] = StyleGradient.lerpColor(
                        pixels[y * WIDTH + x],
                        ink,
                        coverage,
                    )
                }
            }
        }
        return pixels
    }

    private fun glowAt(x: Int, y: Int): Float {
        val dx = (x - GLOW_X) / GLOW_RADIUS
        val dy = (y - GLOW_Y) / GLOW_RADIUS
        val distance = dx * dx + dy * dy
        if (distance >= 1f) return 0f
        val falloff = 1f - distance
        return falloff * falloff * GLOW_STRENGTH
    }

    private fun edgeCoverage(box: Box, x: Int): Float {
        val stemEnd = box.left + STEM_WIDTH
        val gapEnd = stemEnd + GAP_WIDTH
        return if (x == box.left || x == box.right - 1 || x == stemEnd - 1 || x == gapEnd) {
            EDGE_COVERAGE
        } else {
            1f
        }
    }

    private class Page(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
        val bars: List<Box>,
        val expected: List<Int>,
        val midInk: Int,
    ) {
        fun sample(box: Box, nominalInk: Int): Int = sampleGlyph(
            pixels = pixels,
            pageWidth = width,
            pageHeight = height,
            left = box.left,
            top = box.top,
            right = box.right,
            bottom = box.bottom,
            nominalInk = nominalInk,
            globalBackground = pixels[CORNER_SAMPLE_ROW * width + CORNER_SAMPLE_COLUMN],
        )
    }

    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun assertColorNear(expected: Int, actual: Int, tolerance: Int) {
        val channels = listOf(16, 8, 0).map { shift ->
            kotlin.math.abs(((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF))
        }
        assertTrue(
            "expected=${expected.toHex()} actual=${actual.toHex()} channels=$channels",
            channels.all { it <= tolerance },
        )
    }

    private fun quantized(color: Int): Int =
        ((red(color) shr 4) shl 8) or ((green(color) shr 4) shl 4) or (blue(color) shr 4)

    private fun Int.toHex(): String = "0x" + Integer.toHexString(this).uppercase()

    private fun red(color: Int): Int = (color shr 16) and 0xFF
    private fun green(color: Int): Int = (color shr 8) and 0xFF
    private fun blue(color: Int): Int = color and 0xFF
    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 160
        const val BAR_COUNT = 8
        const val BAR_LEFT = 24
        const val BAR_STEP = 54
        const val BAR_WIDTH = 22
        const val BAR_TOP = 62
        const val BAR_BOTTOM = 114
        const val STEM_WIDTH = 9
        const val GAP_WIDTH = 4
        const val EDGE_COVERAGE = 0.55f
        const val CORNER_SAMPLE_ROW = 4
        const val CORNER_SAMPLE_COLUMN = 4
        const val GLOW_X = 268
        const val GLOW_Y = 86
        const val GLOW_RADIUS = 74f
        const val GLOW_STRENGTH = 0.22f
        const val GLOW_RED = 190
        const val GLOW_GREEN = 172
        const val GLOW_BLUE = 120
        const val SEED = 11L
        const val THIN_WIDTH_PAGE = 320
        const val THIN_HEIGHT_PAGE = 72
        const val THIN_COUNT = 6
        const val THIN_LEFT = 20
        const val THIN_STEP = 48
        const val THIN_WIDTH = 9
        const val THIN_TOP = 26
        const val THIN_HEIGHT = 20
        const val THIN_SEED = 29L
        val WARM_INK = 0xFFEB283C.toInt()
        val COLD_INK = 0xFF2855FF.toInt()
        val MID_INK = 0xFFA03D8F.toInt()
    }
}

private fun sampleGlyph(
    pixels: IntArray,
    pageWidth: Int,
    pageHeight: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    nominalInk: Int,
    globalBackground: Int,
): Int {
    val pad = ((bottom - top) * 0.30f).roundToInt().coerceIn(3, 10)
    val sampleLeft = left.coerceIn(0, pageWidth - 1)
    val sampleRight = right.coerceIn(sampleLeft + 1, pageWidth)
    val sampleTop = (top - pad).coerceIn(0, pageHeight - 1)
    val sampleBottom = (bottom + pad).coerceIn(sampleTop + 1, pageHeight)
    val glyphTop = top.coerceIn(sampleTop, sampleBottom - 1) - sampleTop
    val glyphBottom = bottom.coerceIn(sampleTop + 1, sampleBottom) - sampleTop
    val sampleWidth = sampleRight - sampleLeft
    val sampleHeight = sampleBottom - sampleTop
    val crop = IntArray(sampleWidth * sampleHeight)
    for (row in 0 until sampleHeight) {
        val source = (sampleTop + row) * pageWidth + sampleLeft
        System.arraycopy(pixels, source, crop, row * sampleWidth, sampleWidth)
    }
    val backdrop = InkColorSampler.estimateBackdrop(
        topStrip = crop.copyOfRange(0, glyphTop * sampleWidth),
        bottomStrip = crop.copyOfRange(glyphBottom * sampleWidth, crop.size),
        globalBackground = globalBackground,
        inkColor = nominalInk,
    )
    return InkColorSampler.sampleGlyphColor(
        pixels = crop,
        width = sampleWidth,
        glyphTop = glyphTop,
        glyphBottom = glyphBottom,
        backdrop = backdrop,
        fallbackInk = nominalInk,
    )
}
