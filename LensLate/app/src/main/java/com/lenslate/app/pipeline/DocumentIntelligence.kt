package com.lenslate.app.pipeline

import android.graphics.Bitmap
import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.DocumentAnalysis
import com.lenslate.app.model.DocumentImageStats
import com.lenslate.app.model.DocumentKind
import com.lenslate.app.model.DocumentTextStats
import com.lenslate.app.model.ScanDocument
import com.lenslate.app.model.TextRegionRecord
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Understands what kind of document was photographed and which OCR readings
 * belong to a picture rather than to editable text.
 *
 * The image is analysed on a coarse grid. For every cell the dominant paper
 * tone and the remaining background texture are measured separately, so glyph
 * strokes do not make ordinary text look photographic. A region is protected
 * only when both its own box and its immediate ring are photographic; a caption
 * printed on clean paper under a photo therefore remains translatable.
 */
@Singleton
class DocumentIntelligence @Inject constructor() {

    fun analyze(bitmap: Bitmap, regions: List<TextRegionRecord>): DocumentAnalysis {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 4 || height < 4) return DocumentAnalysis()

        val scale = min(1f, MAX_SAMPLE_SIDE / max(width, height).toFloat())
        val sampleWidth = max(2, (width * scale).roundToInt())
        val sampleHeight = max(2, (height * scale).roundToInt())
        var sample: Bitmap? = null
        val pixels = try {
            val source = if (sampleWidth == width && sampleHeight == height) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
                    .also { sample = it }
            }
            IntArray(sampleWidth * sampleHeight).also {
                source.getPixels(it, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
            }
        } catch (_: Exception) {
            return DocumentAnalysis()
        } finally {
            if (sample != null && sample !== bitmap && !sample.isRecycled) sample.recycle()
        }

        val scaleX = sampleWidth / width.toFloat()
        val scaleY = sampleHeight / height.toFloat()
        val inputs = regions.map { region ->
            AnalysisRegion(
                id = region.id,
                left = region.bounds.left * scaleX,
                top = region.bounds.top * scaleY,
                right = region.bounds.right * scaleX,
                bottom = region.bounds.bottom * scaleY,
                confidence = region.overallConfidence ?: region.confidence,
                isFormula = region.isFormula,
                text = region.text,
            )
        }
        return DocumentAnalyzer.analyzePixels(pixels, sampleWidth, sampleHeight, inputs)
    }

    /**
     * Adds the analysis to a scan and marks protected regions. The analysis keeps
     * every candidate regardless of the switch so the lab can show what would be
     * protected when the feature is enabled later.
     */
    fun enrich(
        document: ScanDocument,
        bitmap: Bitmap,
        protectEmbeddedImages: Boolean,
    ): ScanDocument {
        val analysis = analyze(bitmap, document.regions)
        val activeReasons = if (protectEmbeddedImages) analysis.protectionReasons else emptyMap()
        val regions = document.regions.map { region ->
            val reason = activeReasons[region.id]
            region.copy(
                isProtected = reason != null,
                protectionReason = reason,
            )
        }
        return document.copy(regions = regions, analysis = analysis)
    }

    private companion object {
        const val MAX_SAMPLE_SIDE = 224f
    }
}

internal data class AnalysisRegion(
    val id: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float?,
    val isFormula: Boolean,
    val text: String,
)

/**
 * Pure pixel-level part of [DocumentIntelligence]; kept free of Android classes
 * so thresholds and protection rules can be unit tested.
 */
internal object DocumentAnalyzer {

    fun analyzePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        regions: List<AnalysisRegion>,
    ): DocumentAnalysis {
        if (width < 2 || height < 2 || pixels.size < width * height) return DocumentAnalysis()
        val cellSize = max(MIN_CELL_SIZE, ceil(max(width, height) / TARGET_CELLS.toFloat()).toInt())
        val columns = ceil(width / cellSize.toFloat()).toInt()
        val rows = ceil(height / cellSize.toFloat()).toInt()
        val cellWidth = cellSize.toFloat()
        val cellHeight = cellSize.toFloat()

        val coverage = FloatArray(columns * rows)
        regions.forEach { region ->
            val left = region.left.coerceIn(0f, width.toFloat())
            val top = region.top.coerceIn(0f, height.toFloat())
            val right = region.right.coerceIn(0f, width.toFloat())
            val bottom = region.bottom.coerceIn(0f, height.toFloat())
            if (right - left < 1f || bottom - top < 1f) return@forEach
            val firstColumn = (left / cellWidth).toInt().coerceIn(0, columns - 1)
            val lastColumn = ((right - 0.01f) / cellWidth).toInt().coerceIn(0, columns - 1)
            val firstRow = (top / cellHeight).toInt().coerceIn(0, rows - 1)
            val lastRow = ((bottom - 0.01f) / cellHeight).toInt().coerceIn(0, rows - 1)
            for (row in firstRow..lastRow) {
                val cellTop = row * cellSize
                val overlapTop = max(top, cellTop.toFloat())
                val overlapBottom = min(bottom, (cellTop + cellSize).toFloat())
                if (overlapBottom <= overlapTop) continue
                for (column in firstColumn..lastColumn) {
                    val cellLeft = column * cellSize
                    val overlapLeft = max(left, cellLeft.toFloat())
                    val overlapRight = min(right, (cellLeft + cellSize).toFloat())
                    if (overlapRight <= overlapLeft) continue
                    val area = (overlapRight - overlapLeft) * (overlapBottom - overlapTop)
                    coverage[row * columns + column] += area / (cellWidth * cellHeight)
                }
            }
        }

        val cells = ArrayList<GridCell>(columns * rows)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val left = column * cellSize
                val top = row * cellSize
                val right = min(width, left + cellSize)
                val bottom = min(height, top + cellSize)
                if (right - left < 2 || bottom - top < 2) continue
                cells += profileCell(
                    pixels = pixels,
                    width = width,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    textShare = coverage[row * columns + column].coerceIn(0f, 1f),
                )
            }
        }
        if (cells.isEmpty()) return DocumentAnalysis()

        val imageStats = imageStats(cells)
        val textStats = textStats(regions, width.toFloat() * height)
        val probabilities = classify(textStats, imageStats)
        val kind = probabilities.maxByOrNull { it.value }?.key ?: DocumentKind.LITERARY_TEXT

        val evidence = regions
            .filter { !it.isFormula }
            .map { region -> regionEvidence(region, cells, columns, width, height) }
        val reasons = LinkedHashMap<Int, String>()
        var protectedArea = 0f
        evidence.forEach { item ->
            val reason = protectionReason(item, kind)
            if (reason != null) {
                reasons[item.id] = reason
                protectedArea += item.areaShare
            }
        }

        return DocumentAnalysis(
            kind = kind,
            confidence = probabilities[kind] ?: 0f,
            scores = probabilities,
            text = textStats,
            image = imageStats,
            protectionReasons = reasons,
            protectedAreaShare = protectedArea.coerceIn(0f, 1f),
        )
    }

    // ── image profiling ─────────────────────────────────────────

    private fun profileCell(
        pixels: IntArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        textShare: Float,
    ): GridCell {
        val cellWidth = right - left
        val cellHeight = bottom - top
        val count = cellWidth * cellHeight
        val luma = IntArray(count)
        val histogram = IntArray(256)
        var saturationSum = 0
        var warmthSum = 0
        var bright = 0
        var dark = 0
        var index = 0
        for (y in top until bottom) {
            val row = y * width
            for (x in left until right) {
                val color = pixels[row + x]
                val red = (color shr 16) and 0xFF
                val green = (color shr 8) and 0xFF
                val blue = color and 0xFF
                val value = (77 * red + 150 * green + 29 * blue) shr 8
                luma[index++] = value
                histogram[value]++
                saturationSum += max(red, max(green, blue)) - min(red, min(green, blue))
                warmthSum += red - blue
                if (value >= 205) bright++
                if (value <= 60) dark++
            }
        }

        // The median is used instead of the histogram mode: uniform glyph pixels
        // all fall into one bin and can outvote the much broader paper/photo
        // background, while the median stays on the background side.
        var center = 128
        var cumulative = 0
        for (value in 0 until 256) {
            cumulative += histogram[value]
            if (cumulative * 2 >= count) {
                center = value
                break
            }
        }
        var bandCount = 0
        var bandSum = 0.0
        var bandSquareSum = 0.0
        val bandStart = max(0, center - MODE_BAND)
        val bandEnd = min(255, center + MODE_BAND)
        for (value in bandStart..bandEnd) {
            val amount = histogram[value]
            if (amount == 0) continue
            bandCount += amount
            bandSum += value.toDouble() * amount
            bandSquareSum += value.toDouble() * value * amount
        }
        val backgroundMean = if (bandCount > 0) bandSum / bandCount else center.toDouble()
        val backgroundVariance = if (bandCount > 0) {
            (bandSquareSum / bandCount - backgroundMean * backgroundMean).coerceAtLeast(0.0)
        } else {
            0.0
        }
        val backgroundStd = sqrt(backgroundVariance).toFloat() / 255f

        var detailPixels = 0
        index = 0
        for (y in 0 until cellHeight) {
            val rowOffset = y * cellWidth
            for (x in 0 until cellWidth) {
                val value = luma[index]
                val rightDelta = if (x + 1 < cellWidth) abs(value - luma[rowOffset + x + 1]) else 0
                val bottomDelta = if (y + 1 < cellHeight) abs(value - luma[rowOffset + x + cellWidth]) else 0
                if (max(rightDelta, bottomDelta) >= DETAIL_STEP) detailPixels++
                index++
            }
        }

        val modeCoverage = bandCount.toFloat() / count
        val detailShare = detailPixels.toFloat() / count
        val saturation = saturationSum.toFloat() / (count * 255f)
        val warmth = warmthSum.toFloat() / (count * 255f)
        val brightShare = bright.toFloat() / count
        val darkShare = dark.toFloat() / count
        val textCovered = textShare > TEXT_COVERED_SHARE
        val textured = backgroundStd > BACKGROUND_TEXTURE_STD
        val colorful = saturation > COLORFUL_SATURATION

        val photo = if (textCovered) {
            (textured && detailShare > 0.10f) ||
                (colorful && detailShare > 0.07f) ||
                (modeCoverage < 0.48f && detailShare > 0.35f) ||
                (backgroundStd > 0.042f && detailShare > 0.22f)
        } else {
            (detailShare > 0.32f && modeCoverage < 0.70f) ||
                (detailShare > 0.17f && modeCoverage < 0.62f && (textured || colorful)) ||
                (textured && detailShare > 0.09f && modeCoverage < 0.74f) ||
                (colorful && detailShare > 0.055f && modeCoverage < 0.82f)
        }
        val historical = photo && saturation < HISTORICAL_MAX_SATURATION &&
            (warmth > HISTORICAL_WARMTH || saturation < GRAYSCALE_SATURATION)
        val lineArt = saturation < LINE_ART_MAX_SATURATION &&
            modeCoverage > LINE_ART_MIN_COVERAGE &&
            detailShare > LINE_ART_MIN_DETAIL &&
            brightShare > 0.32f &&
            darkShare > 0.012f
        // Flat, saturated areas with sharp edges are logos, packaging or
        // coloured illustrations rather than paper. Text-covered cells are
        // included: a caption printed on a coloured badge must be protected too.
        val graphic = if (textCovered) {
            colorful && modeCoverage > 0.42f && detailShare > 0.055f &&
                (backgroundStd > 0.020f || modeCoverage > 0.62f)
        } else {
            colorful && modeCoverage > 0.46f && detailShare > 0.06f &&
                (backgroundStd > 0.022f || modeCoverage > 0.64f)
        }

        return GridCell(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            weight = count.toFloat(),
            textShare = textShare,
            modeCoverage = modeCoverage,
            backgroundStd = backgroundStd,
            detailShare = detailShare,
            saturation = saturation,
            photo = photo,
            historical = historical,
            lineArt = lineArt,
            colorful = photo && colorful,
            graphic = graphic,
        )
    }

    private fun imageStats(cells: List<GridCell>): DocumentImageStats {
        var total = 0f
        var photo = 0f
        var historical = 0f
        var lineArt = 0f
        var colorful = 0f
        var graphic = 0f
        cells.forEach { cell ->
            total += cell.weight
            if (cell.photo) photo += cell.weight
            if (cell.historical) historical += cell.weight
            if (cell.lineArt) lineArt += cell.weight
            if (cell.colorful) colorful += cell.weight
            if (cell.graphic) graphic += cell.weight
        }
        if (total <= 0f) return DocumentImageStats()
        return DocumentImageStats(
            photoLikeShare = (photo / total).coerceIn(0f, 1f),
            historicalPhotoShare = (historical / total).coerceIn(0f, 1f),
            lineArtShare = (lineArt / total).coerceIn(0f, 1f),
            colorfulShare = (colorful / total).coerceIn(0f, 1f),
            graphicShare = (graphic / total).coerceIn(0f, 1f),
        )
    }

    // ── text profiling and classification ───────────────────────

    private fun textStats(regions: List<AnalysisRegion>, imageArea: Float): DocumentTextStats {
        if (regions.isEmpty()) return DocumentTextStats()
        var characters = 0
        var kana = 0
        var digits = 0
        var symbols = 0
        var formulas = 0
        var short = 0
        var area = 0f
        var confidenceSum = 0f
        var confidenceCount = 0
        regions.forEach { region ->
            val compact = region.text.filterNot(Char::isWhitespace)
            characters += compact.length
            compact.forEach { character ->
                if (character.isKana()) kana++
                if (character.isDigit()) digits++
                if (!character.isLetterOrDigit()) symbols++
            }
            if (region.isFormula) formulas++
            if (compact.length <= SHORT_REGION_CHARACTERS) short++
            area += max(0f, region.right - region.left) * max(0f, region.bottom - region.top)
            region.confidence?.takeIf { it.isFinite() }?.let {
                confidenceSum += it.coerceIn(0f, 1f)
                confidenceCount++
            }
        }
        val total = characters.coerceAtLeast(1)
        return DocumentTextStats(
            characterCount = characters,
            kanaShare = kana.toFloat() / total,
            digitShare = digits.toFloat() / total,
            symbolShare = symbols.toFloat() / total,
            formulaShare = formulas.toFloat() / regions.size,
            textCoverage = (area / imageArea.coerceAtLeast(1f)).coerceIn(0f, 1f),
            averageConfidence = if (confidenceCount > 0) {
                confidenceSum / confidenceCount
            } else {
                null
            },
            shortRegionShare = short.toFloat() / regions.size,
            regionCount = regions.size,
        )
    }

    private fun classify(
        text: DocumentTextStats,
        image: DocumentImageStats,
    ): Map<DocumentKind, Float> {
        val kana = normalize(text.kanaShare, 0.14f)
        val formula = normalize(text.formulaShare, 0.10f)
        val digits = normalize(text.digitShare, 0.16f)
        val photo = normalize(image.photoLikeShare, 0.25f)
        val historical = normalize(image.historicalPhotoShare, 0.22f)
        val lineArt = normalize(image.lineArtShare, 0.25f)
        val graphic = normalize(image.graphicShare, 0.18f)
        val coverage = normalize(text.textCoverage, 0.30f)
        val short = normalize(text.shortRegionShare, 0.65f)
        val lowConfidence = text.averageConfidence?.let {
            normalize(0.82f - it, 0.30f)
        } ?: 0f

        val literary = PRIOR_LITERARY *
            (1f - 0.55f * kana) *
            (1f - 0.65f * formula) *
            (1f - 0.25f * digits) *
            (1f - 0.70f * historical) *
            (1f - 0.50f * photo) *
            (1f - 0.15f * lineArt) *
            (1f - 0.20f * graphic) *
            (1f + 0.70f * coverage) *
            (1f - 0.35f * short)

        val manga = PRIOR_MANGA *
            (1f + 24f * kana) *
            (1f - 0.60f * formula) *
            (1f - 0.30f * digits) *
            (1f - 0.70f * historical) *
            (1f - 0.40f * photo) *
            (1f + 4f * lineArt) *
            (1f - 0.25f * graphic) *
            (1f - 0.20f * coverage) *
            (1f + 1.2f * short)

        val technical = PRIOR_TECHNICAL *
            (1f - 0.50f * kana) *
            (1f + 14f * formula) *
            (1f + 4f * digits) *
            (1f - 0.40f * historical) *
            (1f - 0.20f * photo) *
            (1f + 0.8f * lineArt) *
            (1f + 0.6f * graphic) *
            (1f + 0.30f * coverage) *
            (1f + 0.8f * short)

        val document = PRIOR_DOCUMENT *
            (1f - 0.30f * kana) *
            (1f - 0.40f * formula) *
            (1f + 0.8f * digits) *
            (1f + 24f * historical) *
            (1f + 8f * photo) *
            (1f + 1.6f * graphic) *
            (1f - 0.50f * lineArt) *
            (1f - 0.25f * coverage) *
            (1f + 0.4f * short) *
            (1f + 2f * lowConfidence)

        val raw = mapOf(
            DocumentKind.LITERARY_TEXT to literary,
            DocumentKind.MANGA to manga,
            DocumentKind.TECHNICAL to technical,
            DocumentKind.DOCUMENT to document,
        )
        val sum = raw.values.sum().takeIf { it > 0f } ?: 1f
        return raw.mapValues { (_, value) -> value / sum }
    }

    private fun normalize(value: Float, target: Float): Float =
        (value / target).coerceIn(0f, 1f)

    private fun Char.isKana(): Boolean = this in '\u3040'..'\u30FF'

    // ── region protection ───────────────────────────────────────

    private fun regionEvidence(
        region: AnalysisRegion,
        cells: List<GridCell>,
        columns: Int,
        width: Int,
        height: Int,
    ): RegionEvidence {
        val left = region.left.coerceIn(0f, width.toFloat())
        val top = region.top.coerceIn(0f, height.toFloat())
        val right = region.right.coerceIn(0f, width.toFloat())
        val bottom = region.bottom.coerceIn(0f, height.toFloat())
        val regionWidth = (right - left).coerceAtLeast(0f)
        val regionHeight = (bottom - top).coerceAtLeast(0f)
        if (regionWidth < 1f || regionHeight < 1f) {
            return RegionEvidence(region.id, 0f, 0f, 0f, 0f, 0f, 0f, 0f, region.confidence)
        }
        val pad = max(MIN_RING_PAD, min(regionWidth, regionHeight) * RING_SCALE)
        val ringLeft = (left - pad).coerceAtLeast(0f)
        val ringTop = (top - pad).coerceAtLeast(0f)
        val ringRight = (right + pad).coerceAtMost(width.toFloat())
        val ringBottom = (bottom + pad).coerceAtMost(height.toFloat())

        var selfWeight = 0f
        var selfPhoto = 0f
        var selfHistorical = 0f
        var selfGraphic = 0f
        var ringWeight = 0f
        var ringPhoto = 0f
        var ringHistorical = 0f
        var ringGraphic = 0f
        val cellWidth = cells.firstOrNull()?.let { (it.right - it.left).toFloat() } ?: 1f
        val cellHeight = cells.firstOrNull()?.let { (it.bottom - it.top).toFloat() } ?: 1f

        cells.forEach { cell ->
            val leftOverlap = max(left, cell.left.toFloat())
            val topOverlap = max(top, cell.top.toFloat())
            val rightOverlap = min(right, cell.right.toFloat())
            val bottomOverlap = min(bottom, cell.bottom.toFloat())
            if (rightOverlap > leftOverlap && bottomOverlap > topOverlap) {
                val area = (rightOverlap - leftOverlap) * (bottomOverlap - topOverlap)
                selfWeight += area
                if (cell.photo && !cell.graphic) selfPhoto += area
                if (cell.historical) selfHistorical += area
                if (cell.graphic) selfGraphic += area
            }

            val ringLeftOverlap = max(ringLeft, cell.left.toFloat())
            val ringTopOverlap = max(ringTop, cell.top.toFloat())
            val ringRightOverlap = min(ringRight, cell.right.toFloat())
            val ringBottomOverlap = min(ringBottom, cell.bottom.toFloat())
            if (ringRightOverlap <= ringLeftOverlap || ringBottomOverlap <= ringTopOverlap) return@forEach
            val centerX = (cell.left + cell.right) / 2f
            val centerY = (cell.top + cell.bottom) / 2f
            if (centerX in left..right && centerY in top..bottom) return@forEach
            val area = (ringRightOverlap - ringLeftOverlap) * (ringBottomOverlap - ringTopOverlap)
            ringWeight += area
            if (cell.photo && !cell.graphic) ringPhoto += area
            if (cell.historical) ringHistorical += area
            if (cell.graphic) ringGraphic += area
        }

        val selfPhotoShare = if (selfWeight > 0f) selfPhoto / selfWeight else 0f
        val selfHistoricalShare = if (selfWeight > 0f) selfHistorical / selfWeight else 0f
        val selfGraphicShare = if (selfWeight > 0f) selfGraphic / selfWeight else 0f
        val hasRing = ringWeight > 0.5f * cellWidth * cellHeight * 0.2f
        val ringPhotoShare = if (hasRing) ringPhoto / ringWeight else selfPhotoShare
        val ringHistoricalShare = if (hasRing) ringHistorical / ringWeight else selfHistoricalShare
        val ringGraphicShare = if (hasRing) ringGraphic / ringWeight else selfGraphicShare
        val areaShare = (regionWidth * regionHeight) / (width.toFloat() * height)
        return RegionEvidence(
            id = region.id,
            selfPhotoShare = selfPhotoShare,
            ringPhotoShare = ringPhotoShare,
            selfHistoricalShare = selfHistoricalShare,
            ringHistoricalShare = ringHistoricalShare,
            selfGraphicShare = selfGraphicShare,
            ringGraphicShare = ringGraphicShare,
            areaShare = areaShare,
            confidence = region.confidence,
        )
    }

    private fun protectionReason(evidence: RegionEvidence, kind: DocumentKind): String? {
        val photo = evidence.selfPhotoShare * 0.62f + evidence.ringPhotoShare * 0.38f
        val historical =
            evidence.selfHistoricalShare * 0.62f + evidence.ringHistoricalShare * 0.38f
        val graphic =
            evidence.selfGraphicShare * 0.62f + evidence.ringGraphicShare * 0.38f
        val confidence = evidence.confidence
        if (photo >= 0.60f && evidence.selfPhotoShare >= 0.30f) {
            return "Текст внутри изображения"
        }
        if (photo >= 0.45f && evidence.selfPhotoShare >= 0.20f && historical >= 0.55f) {
            return "Фрагмент исторического снимка"
        }
        if (graphic >= 0.42f && evidence.selfGraphicShare >= 0.22f &&
            (confidence == null || confidence < GRAPHIC_CONFIDENCE_LIMIT)
        ) {
            return "Текст на графике или логотипе"
        }
        if (kind == DocumentKind.DOCUMENT &&
            photo >= 0.45f &&
            evidence.selfPhotoShare >= 0.20f
        ) {
            return "Фото внутри документа"
        }
        if (kind != DocumentKind.LITERARY_TEXT &&
            evidence.selfPhotoShare >= 0.52f &&
            confidence != null &&
            confidence < LOW_CONFIDENCE_LIMIT
        ) {
            return "Неуверенное чтение на изображении"
        }
        return null
    }

    private data class GridCell(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val weight: Float,
        val textShare: Float,
        val modeCoverage: Float,
        val backgroundStd: Float,
        val detailShare: Float,
        val saturation: Float,
        val photo: Boolean,
        val historical: Boolean,
        val lineArt: Boolean,
        val colorful: Boolean,
        val graphic: Boolean,
    )

    private data class RegionEvidence(
        val id: Int,
        val selfPhotoShare: Float,
        val ringPhotoShare: Float,
        val selfHistoricalShare: Float,
        val ringHistoricalShare: Float,
        val selfGraphicShare: Float,
        val ringGraphicShare: Float,
        val areaShare: Float,
        val confidence: Float?,
    )

    private const val TARGET_CELLS = 14
    private const val MIN_CELL_SIZE = 8
    private const val MODE_BAND = 22
    private const val DETAIL_STEP = 26
    private const val TEXT_COVERED_SHARE = 0.20f
    private const val BACKGROUND_TEXTURE_STD = 0.052f
    private const val COLORFUL_SATURATION = 0.24f
    private const val HISTORICAL_MAX_SATURATION = 0.27f
    private const val HISTORICAL_WARMTH = 0.025f
    private const val GRAYSCALE_SATURATION = 0.10f
    private const val LINE_ART_MAX_SATURATION = 0.15f
    private const val LINE_ART_MIN_COVERAGE = 0.52f
    private const val LINE_ART_MIN_DETAIL = 0.09f
    private const val SHORT_REGION_CHARACTERS = 24
    private const val RING_SCALE = 0.55f
    private const val MIN_RING_PAD = 4f
    private const val LOW_CONFIDENCE_LIMIT = 0.50f
    private const val GRAPHIC_CONFIDENCE_LIMIT = 0.68f

    private const val PRIOR_LITERARY = 0.62f
    private const val PRIOR_MANGA = 0.12f
    private const val PRIOR_TECHNICAL = 0.18f
    private const val PRIOR_DOCUMENT = 0.08f
}
