package com.lenslate.app.pipeline

import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.Point2D
import com.lenslate.app.model.TextLineRecord
import com.lenslate.app.model.TextRegionRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/** Юнит-тесты объединения строчных OCR-регионов в абзацы. */
class OcrParagraphMergerTest {

    private fun polygon(left: Float, top: Float, right: Float, bottom: Float) = listOf(
        Point2D(left, top),
        Point2D(right, top),
        Point2D(right, bottom),
        Point2D(left, bottom),
    )

    private fun region(
        id: Int,
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        confidence: Float = 0.8f,
    ): TextRegionRecord {
        val bounds = Bounds2D(left, top, right, bottom)
        val line = TextLineRecord(
            text = text,
            bounds = bounds,
            polygon = polygon(left, top, right, bottom),
            angleDegrees = 0f,
            confidence = confidence,
            elements = emptyList(),
        )
        return TextRegionRecord(
            id = id,
            text = text,
            bounds = bounds,
            polygon = line.polygon,
            script = "Cyrillic",
            readingOrder = id - 1,
            confidence = confidence,
            overallConfidence = confidence,
            lines = listOf(line),
        )
    }

    private fun block(
        id: Int,
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        lineTexts: List<String>,
    ): TextRegionRecord {
        val lineHeight = (bottom - top) / lineTexts.size
        val lines = lineTexts.mapIndexed { index, lineText ->
            val lineTop = top + index * lineHeight
            TextLineRecord(
                text = lineText,
                bounds = Bounds2D(left, lineTop, right, lineTop + lineHeight),
                polygon = polygon(left, lineTop, right, lineTop + lineHeight),
                angleDegrees = 0f,
                confidence = 0.8f,
                elements = emptyList(),
            )
        }
        val bounds = Bounds2D(left, top, right, bottom)
        return TextRegionRecord(
            id = id,
            text = text,
            bounds = bounds,
            polygon = polygon(left, top, right, bottom),
            script = "Cyrillic",
            readingOrder = id - 1,
            confidence = 0.8f,
            overallConfidence = 0.8f,
            lines = lines,
        )
    }

    @Test
    fun complementaryBlocksWithSharedBoundaryLineAreJoined() {
        val upper = block(
            id = 1,
            text = "Первая строка\nнескольких уровнях яркости.",
            left = 40f, top = 100f, right = 900f, bottom = 160f,
            lineTexts = listOf("Первая строка", "нескольких уровнях яркости."),
        )
        val lower = block(
            id = 2,
            text = "нескольких уровнях яркости.\nПроверка идёт по всей строке.",
            left = 40f, top = 168f, right = 900f, bottom = 228f,
            lineTexts = listOf("нескольких уровнях яркости.", "Проверка идёт по всей строке."),
        )

        val result = OcrParagraphMerger.merge(listOf(upper, lower))

        assertEquals(1, result.size)
        assertEquals(
            "Первая строка\nнескольких уровнях яркости.\nПроверка идёт по всей строке.",
            result[0].text,
        )
    }

    @Test
    fun wrappedLinesAreJoinedIntoParagraph() {
        val first = region(1, "Устройство предназначено для обработки", 40f, 100f, 800f, 130f)
        val second = region(2, "изображений и документов. Все вычисления", 40f, 135f, 800f, 165f)
        val third = region(3, "локально, без передачи данных.", 40f, 170f, 800f, 200f)

        val result = OcrParagraphMerger.merge(listOf(first, second, third))

        assertEquals(1, result.size)
        assertEquals(3, result[0].lines.size)
        assertEquals("${first.text}\n${second.text}\n${third.text}", result[0].text)
        assertEquals(100f, result[0].bounds.top, 0.01f)
        assertEquals(200f, result[0].bounds.bottom, 0.01f)
    }

    @Test
    fun paragraphGapKeepsRegionsSeparate() {
        val first = region(1, "Первый абзац без точки", 40f, 100f, 800f, 130f)
        val second = region(2, "Второй абзац после отступа", 40f, 160f, 800f, 190f)

        val result = OcrParagraphMerger.merge(listOf(first, second))

        assertEquals(2, result.size)
    }

    @Test
    fun sentenceEndingListItemsStaySeparate() {
        val first = region(1, "Влажность: не более 80 % без конденсации.", 40f, 500f, 700f, 530f)
        val second = region(2, "Питание: 220 В, 50 Гц. Мощность: 45 Вт.", 40f, 537f, 900f, 567f)

        val result = OcrParagraphMerger.merge(listOf(first, second))

        assertEquals(2, result.size)
    }

    @Test
    fun differentSizesAreNotJoined() {
        val heading = region(1, "ТЕХНИЧЕСКИЙ ПАСПОРТ", 40f, 80f, 800f, 140f)
        val subtitle = region(2, "Модель LX-4200 · Серия A", 40f, 155f, 500f, 185f)

        val result = OcrParagraphMerger.merge(listOf(heading, subtitle))

        assertEquals(2, result.size)
    }

    @Test
    fun sideBySideColumnsAreNotJoined() {
        val left = region(1, "Левая колонка", 40f, 300f, 400f, 330f)
        val right = region(2, "Правая колонка", 450f, 300f, 800f, 330f)

        val result = OcrParagraphMerger.merge(listOf(left, right))

        assertEquals(2, result.size)
    }

    @Test
    fun shortLastLineJoinsByLeftEdge() {
        val first = region(1, "The paragraph continues on the next visual line", 40f, 100f, 800f, 130f)
        val tail = region(2, "short tail", 40f, 135f, 200f, 165f)

        val result = OcrParagraphMerger.merge(listOf(first, tail))

        assertEquals(1, result.size)
        assertEquals(2, result[0].lines.size)
    }

    @Test
    fun idsAndReadingOrderAreReassigned() {
        val first = region(9, "Начало абзаца без точки", 40f, 100f, 800f, 130f)
        val second = region(4, "продолжение абзаца.", 40f, 135f, 800f, 165f)
        val separate = region(7, "Отдельный блок.", 40f, 300f, 400f, 330f)

        val result = OcrParagraphMerger.merge(listOf(first, second, separate))

        assertEquals(2, result.size)
        assertEquals(1, result[0].id)
        assertEquals(0, result[0].readingOrder)
        assertEquals(2, result[1].id)
        assertEquals(1, result[1].readingOrder)
    }

    @Test
    fun containedFragmentsDoNotSurviveAsSeparateBlocks() {
        val paragraph = region(
            1,
            "Проверка идёт по всей строке: оттенок медленно уходит из холодного серого в тёплый уголь.",
            104f, 948f, 1005f, 1103f,
            confidence = 0.85f,
        )
        val fragments = listOf(
            region(2, "оттенок медленно уходит из", 556f, 952f, 964f, 978f, confidence = 0.91f),
            region(3, "холодного серого в тёплый уголь", 104f, 983f, 713f, 1022f, confidence = 0.9f),
            region(4, "оуходитиз", 800f, 953f, 964f, 978f, confidence = 0.88f),
        )

        val result = OcrParagraphMerger.merge(listOf(paragraph) + fragments)

        assertEquals(1, result.size)
        assertEquals(paragraph.text, result[0].text)
    }

    @Test
    fun shortFragmentInsideLargerLineIsDropped() {
        val line = region(1, "ОДИН ВЕЧЕР · ОДНА СЦЕНА", 85f, 1305f, 627f, 1336f, confidence = 0.9f)
        val fragment = region(2, "Од", 378f, 1305f, 431f, 1336f, confidence = 0.95f)

        val result = OcrParagraphMerger.merge(listOf(line, fragment))

        assertEquals(1, result.size)
        assertEquals(line.text, result[0].text)
    }

    @Test
    fun partiallyOverlappingDifferentColumnsStaySeparate() {
        val left = region(1, "Левая колонка с текстом", 40f, 300f, 400f, 400f)
        val right = region(2, "Правая колонка с другим текстом", 370f, 300f, 800f, 400f)

        val result = OcrParagraphMerger.merge(listOf(left, right))

        assertEquals(2, result.size)
    }

    @Test
    fun duplicateOverlappingLineRecordsAreDropped() {
        val base = block(
            id = 1,
            text = "ПОЛНАЯ\nТЕМНОТА",
            left = 70f, top = 700f, right = 640f, bottom = 800f,
            lineTexts = listOf("ПОЛНАЯ", "ТЕМНОТА"),
        )
        // A second pass emitted the same first line with a few pixels of
        // vertical drift; both copies used to become separate render frames.
        val duplicate = base.lines.first().copy(
            bounds = Bounds2D(72f, 706f, 640f, 752f),
            polygon = polygon(72f, 706f, 640f, 752f),
        )
        val region = base.copy(lines = base.lines + duplicate)

        val result = OcrParagraphMerger.merge(listOf(region))

        assertEquals(1, result.size)
        assertEquals(listOf("ПОЛНАЯ", "ТЕМНОТА"), result[0].lines.map { it.text })
    }

    @Test
    fun adjacentRepeatedLineIsAbsorbedIntoParagraphBounds() {
        val paragraph = region(
            1,
            "нескольких уровнях яркости. Проверка идёт по всей строке: оттенок уходит в тёплый угол.",
            70f, 947f, 1020f, 1200f,
            confidence = 0.87f,
        )
        val repeatedLine = region(
            2,
            "нескольких уровнях яркости.",
            104f, 913f, 520f, 936f,
            confidence = 0.8f,
        )

        val result = OcrParagraphMerger.merge(listOf(paragraph, repeatedLine))

        assertEquals(1, result.size)
        assertEquals(paragraph.text, result[0].text)
        assertEquals(913f, result[0].bounds.top, 0.01f)
        assertEquals(2, result[0].lines.size)
    }

    @Test
    fun scannerSliverCannotFormALineOfItsOwn() {
        val heading = region(1, "ТЕМНОТА", 105f, 700f, 640f, 750f, confidence = 0.6f)
        val sliver = region(2, "Т", 64f, 700f, 120f, 702f, confidence = 0.3f)

        val result = OcrParagraphMerger.merge(listOf(heading, sliver))

        assertEquals(1, result.size)
        assertEquals(listOf("ТЕМНОТА"), result[0].lines.map { it.text })
        assertEquals(105f, result[0].bounds.left, 0.01f)
    }
}
