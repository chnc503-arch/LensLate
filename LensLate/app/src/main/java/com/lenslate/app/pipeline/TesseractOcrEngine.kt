package com.lenslate.app.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.Point2D
import com.lenslate.app.model.TextElementRecord
import com.lenslate.app.model.TextLineRecord
import com.lenslate.app.model.TextRegionRecord
import com.lenslate.app.model.TextSymbolRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OCR кириллицы через Tesseract (tess-two). ML Kit не поддерживает кириллические
 * скрипты, поэтому для русского языка используется локальная модель rus.traineddata.
 *
 * Геометрия возвращается в координатах working-bitmap, символы и слова сохраняются
 * для последующего анализа стиля.
 */
@Singleton
class TesseractOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val lock = Any()

    @Volatile
    private var api: TessBaseAPI? = null

    @Volatile
    private var trainedDataReady = false

    suspend fun recognize(
        bitmap: Bitmap,
        pageSegMode: Int = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
    ): List<TextRegionRecord> {
        if (bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            synchronized(lock) {
                recognizeLocked(bitmap, pageSegMode)
            }
        }
    }

    private fun recognizeLocked(bitmap: Bitmap, pageSegMode: Int): List<TextRegionRecord> {
        val scale = (TARGET_OCR_DIMENSION / max(bitmap.width, bitmap.height).toFloat())
            .coerceIn(1f, MAX_UPSCALE)
        val input = if (scale > 1.04f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val engine = engine()
        try {
            engine.setPageSegMode(pageSegMode)
            engine.setImage(input)
            engine.getUTF8Text()
            val rawLines = mutableListOf<RawBox>()
            val rawWords = mutableListOf<RawBox>()
            val rawSymbols = mutableListOf<RawBox>()
            walk(engine, TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE) { rawLines += it }
            walk(engine, TessBaseAPI.PageIteratorLevel.RIL_WORD) { rawWords += it }
            walk(engine, TessBaseAPI.PageIteratorLevel.RIL_SYMBOL) { rawSymbols += it }
            val lines = buildLines(rawLines, rawWords, rawSymbols, scale, bitmap.width, bitmap.height)
            engine.clear()
            return buildRegions(lines)
        } finally {
            runCatching { engine.clear() }
            if (input !== bitmap && !input.isRecycled) input.recycle()
        }
    }

    private fun engine(): TessBaseAPI {
        api?.let { return it }
        ensureTrainedData()
        val created = TessBaseAPI()
        val initialized = runCatching {
            created.init(context.filesDir.absolutePath, LANGUAGE)
        }.getOrDefault(false)
        if (!initialized) {
            runCatching { created.end() }
            throw IllegalStateException(
                "Не удалось загрузить OCR-модель русского языка. Переустановите приложение.",
            )
        }
        created.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
        created.setVariable("preserve_interword_spaces", "1")
        created.setVariable("user_defined_dpi", "300")
        api = created
        return created
    }

    private fun ensureTrainedData() {
        if (trainedDataReady) return
        val directory = File(context.filesDir, "tessdata")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Не удалось подготовить каталог OCR-модели.")
        }
        val target = File(directory, "$LANGUAGE.traineddata")
        if (!target.exists() || target.length() < MIN_TRAINED_DATA_BYTES) {
            context.assets.open("tessdata/$LANGUAGE.traineddata").use { input ->
                target.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
            }
        }
        trainedDataReady = true
    }

    private fun walk(
        engine: TessBaseAPI,
        level: Int,
        onBox: (RawBox) -> Unit,
    ) {
        val iterator = runCatching { engine.resultIterator }.getOrNull() ?: return
        try {
            iterator.begin()
            do {
                val text = runCatching { iterator.getUTF8Text(level) }.getOrNull()?.trim().orEmpty()
                if (text.isEmpty()) continue
                val rect = runCatching { iterator.getBoundingRect(level) }.getOrNull() ?: continue
                if (rect.width() <= 0 || rect.height() <= 0) continue
                val confidence = runCatching { iterator.confidence(level) }
                    .getOrDefault(-1f)
                    .takeIf { it.isFinite() && it >= 0f }
                    ?.div(100f)
                    ?.coerceIn(0f, 1f)
                onBox(RawBox(text, rect, confidence))
            } while (runCatching { iterator.next(level) }.getOrDefault(false))
        } finally {
            runCatching { iterator.delete() }
        }
    }

    private fun buildLines(
        rawLines: List<RawBox>,
        rawWords: List<RawBox>,
        rawSymbols: List<RawBox>,
        scale: Float,
        workingWidth: Int,
        workingHeight: Int,
    ): List<TextLineRecord> {
        val lineAccs = rawLines.mapTo(mutableListOf()) { raw ->
            LineAcc(
                rect = raw.rect,
                text = raw.text,
                confidence = raw.confidence,
            )
        }
        val unassignedWords = mutableListOf<RawBox>()
        rawWords.forEach { word ->
            val target = lineAccs
                .map { line -> line to line.rect.distanceToWord(word.rect) }
                .filter { (line, distance) ->
                    distance <= max(line.rect.height() * WORD_LINE_MAX_DISTANCE, MIN_WORD_LINE_DISTANCE)
                }
                .minByOrNull { it.second }
                ?.first
            if (target != null) {
                target.words += WordAcc(word.text, word.rect, word.confidence)
                target.rect = union(target.rect, word.rect)
            } else {
                unassignedWords += word
            }
        }
        if (lineAccs.isEmpty()) {
            clusterWordsIntoLines(unassignedWords, lineAccs)
        } else {
            unassignedWords.forEach { word ->
                val nearest = lineAccs.minByOrNull { it.rect.distanceToWord(word.rect) }
                if (nearest != null && nearest.rect.distanceToWord(word.rect) <=
                    max(nearest.rect.height() * WORD_LINE_MAX_DISTANCE_FALLBACK, MIN_WORD_LINE_DISTANCE)
                ) {
                    nearest.words += WordAcc(word.text, word.rect, word.confidence)
                    nearest.rect = union(nearest.rect, word.rect)
                } else {
                    lineAccs += LineAcc(word.rect, word.text, word.confidence).apply {
                        words += WordAcc(word.text, word.rect, word.confidence)
                    }
                }
            }
        }

        rawSymbols.forEach { symbol ->
            if (symbol.text.isBlank()) return@forEach
            val line = lineAccs
                .filter { it.rect.inflateBy(it.rect.height() * 0.35f).containsCenterOf(symbol.rect) }
                .minByOrNull { it.rect.centerDistanceTo(symbol.rect) }
                ?: lineAccs.minByOrNull { it.rect.centerDistanceTo(symbol.rect) }
                ?: return@forEach
            val word = line.words
                .filter { it.rect.inflateBy(max(2f, it.rect.height() * 0.45f)).containsCenterOf(symbol.rect) }
                .minByOrNull { it.rect.centerDistanceTo(symbol.rect) }
                ?: line.words.minByOrNull { it.rect.centerDistanceTo(symbol.rect) }
            if (word != null) {
                word.symbols += symbol
                word.rect = union(word.rect, symbol.rect)
            } else {
                val pseudo = WordAcc(symbol.text, symbol.rect, symbol.confidence)
                pseudo.symbols += symbol
                line.words += pseudo
            }
        }

        return lineAccs.mapNotNull { line ->
            val words = line.words
                .filter { it.text.isNotBlank() }
                .sortedBy { it.rect.left }
                .map { it.normalized(scale, workingWidth, workingHeight) }
            val text = line.text.trim().ifEmpty { assembleText(words.map { it.text }) }
            if (text.isEmpty() || words.isEmpty()) return@mapNotNull null
            val bounds = Bounds2D.fromPoints(
                listOf(
                    Point2D(line.rect.left / scale, line.rect.top / scale),
                    Point2D(line.rect.right / scale, line.rect.bottom / scale),
                ),
            ) ?: return@mapNotNull null
            val clampedBounds = bounds.clamp(workingWidth, workingHeight)
            val angle = estimateAngle(words)
            val confidence = words.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
                ?: line.confidence
            TextLineRecord(
                text = text,
                bounds = clampedBounds,
                polygon = clampedBounds.toPolygon(),
                angleDegrees = angle,
                confidence = confidence,
                elements = words.map { word ->
                    TextElementRecord(
                        text = word.text,
                        bounds = word.bounds,
                        polygon = word.polygon,
                        confidence = word.confidence,
                        symbols = word.symbols.map { symbol ->
                            TextSymbolRecord(
                                text = symbol.text,
                                bounds = symbol.bounds,
                                polygon = symbol.polygon,
                                confidence = symbol.confidence,
                            )
                        },
                    )
                },
            )
        }.sortedWith(compareBy({ it.bounds?.top ?: 0f }, { it.bounds?.left ?: 0f }))
    }

    private fun clusterWordsIntoLines(words: List<RawBox>, target: MutableList<LineAcc>) {
        val sorted = words.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        sorted.forEach { word ->
            val line = target
                .filter { it.rect.inflateBy(it.rect.height() * 0.6f).containsCenterOf(word.rect) }
                .minByOrNull { it.rect.centerDistanceTo(word.rect) }
            if (line != null) {
                line.words += WordAcc(word.text, word.rect, word.confidence)
                line.rect = union(line.rect, word.rect)
                line.text = assembleText(line.words.sortedBy { it.rect.left }.map { it.text })
            } else {
                target += LineAcc(word.rect, word.text, word.confidence).apply {
                    this.words += WordAcc(word.text, word.rect, word.confidence)
                }
            }
        }
    }

    private fun buildRegions(lines: List<TextLineRecord>): List<TextRegionRecord> {
        if (lines.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<TextLineRecord>>()
        lines.forEach { line ->
            val current = groups.lastOrNull()
            if (current != null && canJoin(current.last(), line)) {
                current += line
            } else {
                groups += mutableListOf(line)
            }
        }
        return groups.mapIndexed { index, group ->
            val bounds = Bounds2D(
                left = group.minOf { it.bounds?.left ?: 0f },
                top = group.minOf { it.bounds?.top ?: 0f },
                right = group.maxOf { it.bounds?.right ?: 0f },
                bottom = group.maxOf { it.bounds?.bottom ?: 0f },
            )
            val confidence = group.mapNotNull { it.confidence }
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?.toFloat()
            TextRegionRecord(
                id = index + 1,
                text = group.joinToString("\n") { it.text },
                bounds = bounds,
                polygon = bounds.toPolygon(),
                script = "Cyrillic",
                readingOrder = index,
                confidence = confidence,
                overallConfidence = confidence,
                lines = group,
            )
        }
    }

    private fun canJoin(previous: TextLineRecord, next: TextLineRecord): Boolean {
        val a = previous.bounds ?: return false
        val b = next.bounds ?: return false
        if (a.width <= 0f || b.width <= 0f) return false
        val verticalGap = b.top - a.bottom
        val referenceHeight = max(a.height, b.height).coerceAtLeast(1f)
        if (verticalGap < -referenceHeight * 0.25f) return false
        if (verticalGap > referenceHeight * 0.55f) return false
        val overlap = min(a.right, b.right) - max(a.left, b.left)
        val overlapRatio = overlap / min(a.width, b.width).coerceAtLeast(1f)
        val leftAligned = abs(a.left - b.left) <= referenceHeight * 0.8f
        if (overlapRatio < 0.35f && !leftAligned) return false
        val previousWords = previous.elements.size
        val nextWords = next.elements.size
        val paragraphLike = previousWords >= 3 || nextWords >= 3 ||
            previous.text.length >= 28 || next.text.length >= 28
        return paragraphLike || verticalGap <= referenceHeight * 0.12f
    }

    private fun estimateAngle(words: List<NormalizedWord>): Float {
        val points = mutableListOf<Point2D>()
        if (words.size >= 2) {
            words.forEach { word ->
                word.bounds?.let { points += Point2D((it.left + it.right) / 2f, (it.top + it.bottom) / 2f) }
            }
        } else {
            words.firstOrNull()?.symbols?.forEach { symbol ->
                symbol.bounds?.let { points += Point2D((it.left + it.right) / 2f, (it.top + it.bottom) / 2f) }
            }
        }
        if (points.size < 2) return 0f
        val meanX = points.map { it.x }.average().toFloat()
        val meanY = points.map { it.y }.average().toFloat()
        var covariance = 0f
        var variance = 0f
        points.forEach { point ->
            val dx = point.x - meanX
            covariance += dx * (point.y - meanY)
            variance += dx * dx
        }
        if (variance <= 1e-4f) return 0f
        val degrees = Math.toDegrees(atan((covariance / variance).toDouble())).toFloat()
        return if (abs(degrees) < MIN_ANGLE_DEGREES) 0f else degrees.coerceIn(-MAX_ANGLE, MAX_ANGLE)
    }

    private fun assembleText(words: List<String>): String {
        val builder = StringBuilder()
        words.forEach { raw ->
            val word = raw.trim()
            if (word.isEmpty()) return@forEach
            if (builder.isNotEmpty()) {
                val previous = builder.last()
                val noSpaceBefore = word.first() in NO_SPACE_BEFORE
                val noSpaceAfter = previous in NO_SPACE_AFTER
                if (!noSpaceBefore && !noSpaceAfter) builder.append(' ')
            }
            builder.append(word)
        }
        return builder.toString()
    }

    fun close() {
        synchronized(lock) {
            api?.let { runCatching { it.end() } }
            api = null
        }
    }

    private fun WordAcc.normalized(scale: Float, width: Int, height: Int): NormalizedWord {
        val bounds = Bounds2D(
            left = (rect.left / scale).coerceIn(0f, width.toFloat()),
            top = (rect.top / scale).coerceIn(0f, height.toFloat()),
            right = (rect.right / scale).coerceIn(0f, width.toFloat()),
            bottom = (rect.bottom / scale).coerceIn(0f, height.toFloat()),
        )
        val normalizedSymbols = symbolsForText().map { symbol ->
            val symbolBounds = Bounds2D(
                left = (symbol.rect.left / scale).coerceIn(0f, width.toFloat()),
                top = (symbol.rect.top / scale).coerceIn(0f, height.toFloat()),
                right = (symbol.rect.right / scale).coerceIn(0f, width.toFloat()),
                bottom = (symbol.rect.bottom / scale).coerceIn(0f, height.toFloat()),
            )
            NormalizedSymbol(symbol.text, symbolBounds, symbolBounds.toPolygon(), symbol.confidence)
        }
        return NormalizedWord(
            text = text,
            bounds = bounds,
            polygon = bounds.toPolygon(),
            confidence = confidence,
            symbols = normalizedSymbols,
        )
    }

    /** Если Tesseract не отдал символы, делим слово на равные ячейки для анализа цвета. */
    private fun WordAcc.symbolsForText(): List<RawBox> {
        if (symbols.isNotEmpty()) return symbols.sortedBy { it.rect.left }
        val characters = text.filterNot(Char::isWhitespace)
        if (characters.isEmpty()) return emptyList()
        val cellWidth = rect.width().toFloat() / characters.length
        return characters.mapIndexed { index, character ->
            val left = rect.left + (index * cellWidth).roundToInt()
            val right = rect.left + ((index + 1) * cellWidth).roundToInt()
            RawBox(
                text = character.toString(),
                rect = Rect(
                    left,
                    rect.top + (rect.height() * 0.08f).roundToInt(),
                    max(left + 1, right),
                    rect.bottom - (rect.height() * 0.08f).roundToInt(),
                ),
                confidence = confidence,
            )
        }
    }

    private fun Bounds2D.clamp(width: Int, height: Int): Bounds2D = Bounds2D(
        left = left.coerceIn(0f, (width - 1).toFloat().coerceAtLeast(0f)),
        top = top.coerceIn(0f, (height - 1).toFloat().coerceAtLeast(0f)),
        right = right.coerceIn(1f, width.toFloat().coerceAtLeast(1f)),
        bottom = bottom.coerceIn(1f, height.toFloat().coerceAtLeast(1f)),
    )

    private fun Bounds2D.toPolygon(): List<Point2D> = listOf(
        Point2D(left, top),
        Point2D(right, top),
        Point2D(right, bottom),
        Point2D(left, bottom),
    )

    private data class RawBox(
        val text: String,
        val rect: Rect,
        val confidence: Float?,
    )

    private class WordAcc(
        val text: String,
        var rect: Rect,
        val confidence: Float?,
    ) {
        val symbols = mutableListOf<RawBox>()
    }

    private class LineAcc(
        var rect: Rect,
        var text: String,
        var confidence: Float?,
    ) {
        val words = mutableListOf<WordAcc>()
    }

    private data class NormalizedSymbol(
        val text: String,
        val bounds: Bounds2D?,
        val polygon: List<Point2D>,
        val confidence: Float?,
    )

    private data class NormalizedWord(
        val text: String,
        val bounds: Bounds2D?,
        val polygon: List<Point2D>,
        val confidence: Float?,
        val symbols: List<NormalizedSymbol>,
    )

    private fun Rect.inflateBy(amount: Float): Rect = Rect(
        (left - amount).roundToInt(),
        (top - amount).roundToInt(),
        (right + amount).roundToInt(),
        (bottom + amount).roundToInt(),
    )

    private fun Rect.containsCenterOf(other: Rect): Boolean {
        val centerX = (other.left + other.right) / 2
        val centerY = (other.top + other.bottom) / 2
        return centerX in left..right && centerY in top..bottom
    }

    private fun Rect.centerDistanceTo(other: Rect): Float {
        val dx = (left + right) / 2f - (other.left + other.right) / 2f
        val dy = (top + bottom) / 2f - (other.top + other.bottom) / 2f
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun Rect.distanceToWord(other: Rect): Float {
        val centerY = (other.top + other.bottom) / 2f
        val vertical = when {
            centerY < top -> top - centerY
            centerY > bottom -> centerY - bottom
            else -> 0f
        }
        val horizontalGap = max(
            max(left - other.right, other.left - right).toFloat(),
            0f,
        )
        return vertical + horizontalGap * 0.35f
    }

    private fun union(first: Rect, second: Rect): Rect = Rect(
        min(first.left, second.left),
        min(first.top, second.top),
        max(first.right, second.right),
        max(first.bottom, second.bottom),
    )

    private companion object {
        const val LANGUAGE = "rus"
        const val MIN_TRAINED_DATA_BYTES = 1_000_000L
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val TARGET_OCR_DIMENSION = 2600f
        const val MAX_UPSCALE = 3f
        const val WORD_LINE_MAX_DISTANCE = 0.55f
        const val WORD_LINE_MAX_DISTANCE_FALLBACK = 0.9f
        const val MIN_WORD_LINE_DISTANCE = 10f
        const val MIN_ANGLE_DEGREES = 1.2f
        const val MAX_ANGLE = 25f
        val NO_SPACE_BEFORE = charArrayOf(',', '.', ';', ':', '!', '?', ')', ']', '}', '%', '°', '′', '″')
        val NO_SPACE_AFTER = charArrayOf('(', '[', '{', '/', '№', '#')
    }
}
