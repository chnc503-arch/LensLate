package com.lenslate.app.pipeline

import com.lenslate.app.model.DocumentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class DocumentIntelligenceTest {

    private val width = 140
    private val height = 140

    private fun blankPage(): IntArray = IntArray(width * height) { 0xFFFFFFFF.toInt() }

    private fun drawTextRow(
        pixels: IntArray,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        color: Int = 0xFF202020.toInt(),
    ) {
        for (y in top until bottom) {
            if ((y - top) % 7 !in 0..1) continue
            for (x in left until right) {
                if (x >= 0 && x < width && y >= 0 && y < height) {
                    pixels[y * width + x] = color
                }
            }
        }
    }

    private fun historicalPhoto(): IntArray {
        val random = Random(7)
        return IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            // Grainy sepia print: one luminance noise channel keeps the photo
            // monochrome while a fixed warm cast marks it as an old photograph.
            val base = 112 +
                (30 * kotlin.math.sin(x / 7.5) * kotlin.math.cos(y / 10.5)).toInt() +
                random.nextInt(100) - 50
            val red = (base + 12).coerceIn(0, 255)
            val green = (base + 4).coerceIn(0, 255)
            val blue = (base - 10).coerceIn(0, 255)
            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }

    private fun mangaLines(pixels: IntArray) {
        val random = Random(11)
        repeat(60) {
            val left = random.nextInt(width - 20)
            val top = random.nextInt(height - 20)
            val length = 10 + random.nextInt(26)
            val horizontal = random.nextBoolean()
            for (step in 0 until length) {
                if ((step / 3) % 2 != 0) continue
                val x = left + if (horizontal) step else step / 3
                val y = top + if (horizontal) step / 3 else step
                if (x in 0 until width && y in 0 until height) {
                    pixels[y * width + x] = 0xFF101010.toInt()
                }
            }
        }
        repeat(12) {
            val cx = 10 + random.nextInt(width - 20)
            val cy = 10 + random.nextInt(height - 20)
            for (y in cy..(cy + 6).coerceAtMost(height - 1)) {
                for (x in cx..(cx + 6).coerceAtMost(width - 1)) {
                    if ((x + y) % 3 == 0) pixels[y * width + x] = 0xFF101010.toInt()
                }
            }
        }
    }

    private fun coloredLogo(
        pixels: IntArray,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        color: Int = 0xFFD02A2A.toInt(),
    ) {
        for (y in top until bottom) {
            for (x in left until right) {
                if (x in 0 until width && y in 0 until height) {
                    pixels[y * width + x] = color
                }
            }
        }
    }

    private fun region(
        id: Int,
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        confidence: Float = 0.9f,
        formula: Boolean = false,
    ) = AnalysisRegion(
        id = id,
        left = left.toFloat(),
        top = top.toFloat(),
        right = right.toFloat(),
        bottom = bottom.toFloat(),
        confidence = confidence,
        isFormula = formula,
        text = text,
    )

    @Test
    fun cleanLiteraryPageIsNotProtected() {
        val pixels = blankPage()
        val regions = listOf(
            region(1, "Обычный текст страницы без изображений", 10, 12, 130, 28),
            region(2, "И ещё несколько строк обычного текста", 10, 34, 130, 50),
            region(3, "Повесть в трёх главах и эпилоге", 10, 56, 130, 72),
        )
        regions.forEach { drawTextRow(pixels, it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }

        val analysis = DocumentAnalyzer.analyzePixels(pixels, width, height, regions)

        assertEquals(DocumentKind.LITERARY_TEXT, analysis.kind)
        assertFalse(analysis.isProtectingImages)
        assertTrue(analysis.image.photoLikeShare < 0.2f)
    }

    @Test
    fun kanaAndLineArtAreRecognizedAsManga() {
        val pixels = blankPage()
        mangaLines(pixels)
        val regions = listOf(
            region(1, "こんにちは、元気ですか", 12, 14, 70, 30),
            region(2, "はい、ありがとう", 80, 38, 130, 54),
            region(3, "それではまた明日", 20, 66, 96, 82),
        )
        regions.forEach { drawTextRow(pixels, it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }

        val analysis = DocumentAnalyzer.analyzePixels(pixels, width, height, regions)

        assertEquals(DocumentKind.MANGA, analysis.kind)
        assertTrue(analysis.text.kanaShare > 0.2f)
        assertFalse(analysis.isProtectingImages)
    }

    @Test
    fun formulasAndDigitsAreRecognizedAsTechnical() {
        val pixels = blankPage()
        val regions = listOf(
            region(1, "E = mc^2", 10, 12, 80, 26, formula = true),
            region(2, "∫ x^2 dx = 42", 10, 34, 90, 48, formula = true),
            region(3, "Результат опыта 3.14", 10, 60, 120, 74),
            region(4, "Погрешность 0.0025", 10, 82, 120, 96),
        )
        regions.forEach { drawTextRow(pixels, it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }

        val analysis = DocumentAnalyzer.analyzePixels(pixels, width, height, regions)

        assertEquals(DocumentKind.TECHNICAL, analysis.kind)
        assertTrue(analysis.text.formulaShare > 0.4f)
        assertFalse(analysis.isProtectingImages)
    }

    @Test
    fun historicalPhotoDocumentProtectsTextInsidePhoto() {
        val pixels = historicalPhoto()
        val regions = listOf(
            region(1, "Семейный архив 1928", 14, 22, 96, 40, confidence = 0.55f),
            region(2, "Фотография", 92, 96, 130, 112, confidence = 0.4f),
        )
        regions.forEach { drawTextRow(pixels, it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt(), 0xFF2A2118.toInt()) }

        val analysis = DocumentAnalyzer.analyzePixels(pixels, width, height, regions)

        assertEquals(DocumentKind.DOCUMENT, analysis.kind)
        assertTrue(analysis.image.photoLikeShare > 0.5f)
        assertTrue(analysis.image.historicalPhotoShare > 0.3f)
        assertTrue(analysis.protectionReasons.containsKey(1))
        assertTrue(analysis.protectionReasons.containsKey(2))
    }

    @Test
    fun captionBelowPhotoStaysTranslatable() {
        val pixels = blankPage()
        val photo = historicalPhoto()
        val photoHeight = 68
        for (y in 0 until photoHeight) {
            for (x in 0 until width) pixels[y * width + x] = photo[y * width + x]
        }
        val regions = listOf(
            region(1, "Рис. 1. Семейный архив", 10, 92, 128, 108, confidence = 0.93f),
        )
        drawTextRow(pixels, 10, 92, 128, 108)

        val analysis = DocumentAnalyzer.analyzePixels(pixels, width, height, regions)

        assertFalse(analysis.protectionReasons.containsKey(1))
    }

    @Test
    fun protectionOnlyMarksImageBackedRegions() {
        val pixels = historicalPhoto()
        val regions = listOf(
            region(1, "1914", 16, 20, 60, 36, confidence = 0.6f),
            region(2, "Обычная подпись на бумаге", 10, 108, 130, 124, confidence = 0.95f),
        )
        drawTextRow(pixels, 16, 20, 60, 36, 0xFF2A2118.toInt())
        for (y in 104 until height) {
            for (x in 0 until width) pixels[y * width + x] = 0xFFFFFFFF.toInt()
        }
        drawTextRow(pixels, 10, 108, 130, 124)

        val analysis = DocumentAnalyzer.analyzePixels(pixels, width, height, regions)

        assertTrue(analysis.protectionReasons.containsKey(1))
        assertFalse(analysis.protectionReasons.containsKey(2))
    }

    @Test
    fun coloredLogoProtectsUncertainText() {
        val pixels = blankPage()
        coloredLogo(pixels, 12, 40, 74, 102)
        val regions = listOf(
            region(1, "BRAND", 22, 60, 62, 78, confidence = 0.48f),
        )
        drawTextRow(pixels, 22, 60, 62, 78, 0xFFFFFFFF.toInt())

        val analysis = DocumentAnalyzer.analyzePixels(pixels, width, height, regions)

        assertTrue(analysis.image.graphicShare > 0.03f)
        assertTrue(analysis.protectionReasons.containsKey(1))
        assertTrue(analysis.protectionReasons.getValue(1).contains("график"))
    }

    @Test
    fun confidentTextOnColoredBannerStaysTranslatable() {
        val pixels = blankPage()
        coloredLogo(pixels, 12, 40, 74, 102)
        val regions = listOf(
            region(1, "Заголовок баннера", 22, 60, 62, 78, confidence = 0.94f),
        )
        drawTextRow(pixels, 22, 60, 62, 78, 0xFFFFFFFF.toInt())

        val analysis = DocumentAnalyzer.analyzePixels(pixels, width, height, regions)

        assertFalse(analysis.protectionReasons.containsKey(1))
    }
}
