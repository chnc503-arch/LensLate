package com.lenslate.app.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.TextLineRecord
import com.lenslate.app.model.TextRegionRecord
import com.lenslate.app.model.TextStyle
import com.lenslate.app.model.TextStyleSample
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class RegionStyleAnalysis(
    val style: TextStyle,
    val characterColors: List<Int>,
    val characterStyles: List<TextStyleSample>,
    val confidenceByAttribute: Map<String, Float>,
    val fontClassConfidence: Float,
)

/**
 * Извлекает из исходного изображения максимум параметров оформления текста:
 * цвет чернил и фона, кегль, насыщенность, наклон, трекинг, межстрочный
 * интервал, выравнивание, подчёркивание/зачёркивание и цвета символов.
 *
 * Все координаты региона — в пикселях working-bitmap.
 */
@Singleton
class StyleAnalyzer @Inject constructor() {

    fun analyze(bitmap: Bitmap, region: TextRegionRecord): RegionStyleAnalysis {
        val rect = clampRect(region.bounds.toRect(), bitmap)
        if (rect.width() <= 0 || rect.height() <= 0) return fallback(region, bitmap)

        val width = rect.width()
        val height = rect.height()
        val pixels = IntArray(width * height)
        try {
            bitmap.getPixels(pixels, 0, width, rect.left, rect.top, width, height)
        } catch (_: IllegalArgumentException) {
            return fallback(region, bitmap)
        }

        val background = estimateBackgroundColor(bitmap, rect)
        // Which side of the paper the strokes occupy. On white-on-white
        // posters the drop shadow is the strongest colour in the region, so
        // "farthest from background" alone paints the shadow as ink.
        val inkEstimate = InkColorSampler.estimateInk(pixels, width, background)
        val maxDistance = inkEstimate?.distanceSq ?: maxInkDistance(pixels, background)
        if (maxDistance < MIN_USEFUL_INK_DISTANCE) {
            return fallback(region, bitmap)
        }
        val inkThreshold = max(MIN_INK_DISTANCE, maxDistance * INK_THRESHOLD_RATIO)
        // The nominal floors are distances and maxDistance is already squared,
        // so on washed-out ink the squared floor can exceed every real pixel
        // and the whole glyph reads as background. Capping both thresholds
        // below maxDistance keeps the faint core while leaving normal contrast
        // on the old values.
        val inkThresholdSq = min(inkThreshold * inkThreshold, maxDistance * FAINT_MASK_RATIO)
        val strongThreshold = max(inkThreshold * 1.45f, maxDistance * STRONG_INK_RATIO)
        val strongThresholdSq = min(strongThreshold * strongThreshold, maxDistance * STRONG_SQ_CEILING)
        val expectedLight = inkEstimate?.light
        val backgroundLuminance = luminance(background)
        val mask = BooleanArray(pixels.size)
        var inkPixels = 0
        for (index in pixels.indices) {
            val pixel = pixels[index]
            if (expectedLight != null && (luminance(pixel) >= backgroundLuminance) != expectedLight) {
                continue
            }
            val distance = colorDistanceSq(pixel, background)
            if (distance >= inkThresholdSq) {
                mask[index] = true
                inkPixels++
            }
        }
        if (inkPixels < minInkPixels(pixels.size)) {
            return fallback(region, bitmap)
        }

        val inkColor = inkEstimate?.color
            ?: estimateInkColor(pixels, background, strongThresholdSq)
            ?: fallbackInkColor(background)

        val lineBoxes = region.lines.mapNotNull { line ->
            line.bounds?.toRect()?.let { clampRect(it, bitmap) }?.takeIf { !it.isEmpty }
        }
        val capHeight = estimateCapHeight(region, lineBoxes, height)
        // A halo on the opposite side of the paper is the source drop shadow
        // or glow. Its blur scales with the type size, and the centroid shift
        // between halo and strokes approximates the shadow offset.
        val shadow = inkEstimate?.takeIf { it.haloColor != null }
        val shadowRadius = if (shadow != null) {
            (capHeight * HALO_RADIUS_RATIO).coerceIn(MIN_HALO_RADIUS_PX, MAX_HALO_RADIUS_PX)
        } else {
            0f
        }
        val shadowColor = shadow?.haloColor ?: Color.TRANSPARENT
        val shadowOffsetX = shadow?.haloOffsetX
            ?.coerceIn(-shadowRadius * HALO_OFFSET_LIMIT, shadowRadius * HALO_OFFSET_LIMIT)
            ?: 0f
        val shadowOffsetY = shadow?.haloOffsetY
            ?.coerceIn(-shadowRadius * HALO_OFFSET_LIMIT, shadowRadius * HALO_OFFSET_LIMIT)
            ?: 0f
        val sizePx = (capHeight / CAP_HEIGHT_RATIO).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        val strokeWidth = estimateStrokeWidth(mask, width, height, capHeight)
        val density = estimateInkDensity(mask, width, height, lineBoxes)
        val weight = estimateWeight(strokeWidth, capHeight, density)
        val mono = FontShapeEstimator.isMonospaced(
            region.lines.map { line ->
                line.elements
                    .flatMap { element -> element.symbols }
                    .mapNotNull { symbol ->
                        symbol.bounds?.let { bounds ->
                            GlyphBox(symbol.text, bounds.left, bounds.right)
                        }
                    }
            },
        )
        val alignment = estimateAlignment(region)
        val slant = FontShapeEstimator.slantDegrees(
            mask = mask,
            width = width,
            height = height,
            lines = lineBoxes.map { TextLineSpan(it.left, it.top, it.right, it.bottom) },
        )
        val letterSpacing = estimateLetterSpacing(region, sizePx, mono)
        val lineSpacing = estimateLineSpacing(region, sizePx)
        val underlined = detectDecoration(mask, width, height, lineBoxes, region, underline = true)
        val struckThrough = detectDecoration(mask, width, height, lineBoxes, region, underline = false)
        val characterStyles = analyzeCharacterStyles(
            bitmap,
            region,
            background,
            inkColor,
            expectedLight,
        )
        val characterColors = characterStyles.mapNotNull { it.color }

        val fontMatch = matchFont(
            region = region,
            mask = mask,
            maskWidth = width,
            maskHeight = height,
            regionRect = rect,
            capHeight = capHeight,
            letterSpacingPx = letterSpacing,
            weightHint = weight,
            underlined = underlined,
            struckThrough = struckThrough,
            italicHint = slant >= MIN_ITALIC_SLANT,
        )
        val variant = fontMatch?.variant
        val fontFamily = variant?.fontClass ?: if (mono) "monospace" else "sans"
        val fontWeight = variant?.weight ?: weight
        val isMono = variant?.fontClass == "monospace" || (variant == null && mono)
        val italic = variant?.italic == true
        val fontKey = variant?.key ?: FontCatalog.fallbackKey(fontFamily, fontWeight, weight >= 600)
        android.util.Log.d(
            "LensLateStyle",
            "«${region.text.replace('\n', ' ').take(24)}» cap=${"%.1f".format(capHeight)} " +
                "stroke=${"%.2f".format(strokeWidth)} w=$weight sl=${"%.1f".format(slant)} " +
                "font=$fontKey score=${"%.2f".format(fontMatch?.score ?: 0f)} " +
                "mix=${"%.2f".format(letterSpacing)} line=${"%.2f".format(lineSpacing)} " +
                "dens=${"%.2f".format(density)} mono=$mono " +
                "shadow=${shadowRadius}@${shadow?.haloColor}",
        )

        val style = TextStyle(
            color = inkColor,
            backgroundColor = background,
            sizePx = sizePx,
            isBold = fontWeight >= 600,
            isMono = isMono,
            angle = region.lines.mapNotNull { it.angleDegrees }.averageOrNull() ?: 0f,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            alignment = alignment,
            letterSpacingPx = letterSpacing,
            lineSpacingMultiplier = lineSpacing,
            capHeightPx = capHeight,
            // An italic typeface already carries its own slant; applying the
            // estimated skew on top would double the lean.
            slantDegrees = if (italic) 0f else slant,
            horizontalScale = fontMatch?.horizontalScale ?: 1f,
            strokeWidthPx = strokeWidth,
            isUnderlined = underlined,
            isStruckThrough = struckThrough,
            fontKey = fontKey,
            fontMatchScore = fontMatch?.score ?: 0f,
            shadowColor = shadowColor,
            shadowRadiusPx = shadowRadius,
            shadowOffsetX = shadowOffsetX,
            shadowOffsetY = shadowOffsetY,
        )
        return RegionStyleAnalysis(
            style = style,
            characterColors = characterColors,
            characterStyles = characterStyles,
            confidenceByAttribute = mapOf(
                "foregroundColor" to if (inkPixels > capHeight * 2f) 0.68f else 0.35f,
                "localBackgroundColor" to 0.72f,
                "textHeightPx" to if (region.lines.any { it.elements.any { e -> e.symbols.isNotEmpty() } }) 0.62f else 0.35f,
                "weight" to if (fontMatch != null) 0.62f else 0.45f,
                "weightClass" to 0.4f,
                "slantDegrees" to 0.35f,
                "alignment" to if (region.lines.size >= 2) 0.62f else 0.32f,
                "spacingPx" to 0.4f,
                "lineSpacingMultiplier" to if (region.lines.size >= 2) 0.55f else 0.3f,
                "likelyFontClass" to (fontMatch?.score?.coerceAtLeast(0.3f) ?: if (mono) 0.62f else 0.48f),
                "fontFamily" to (fontMatch?.score ?: 0.2f),
                "capHeightPx" to 0.55f,
                "strokeWidthPx" to 0.42f,
                "isUnderlined" to if (underlined) 0.55f else 0.32f,
                "isStruckThrough" to if (struckThrough) 0.5f else 0.32f,
                "shadow" to if (shadow != null) 0.6f else 0.3f,
            ),
            fontClassConfidence = fontMatch?.score?.coerceAtLeast(0.3f) ?: if (mono) 0.62f else 0.5f,
        )
    }

    private data class InkUnit(
        val text: String,
        val bounds: Bounds2D,
    )

    /**
     * Каждый глиф получает свой цвет и относительную высоту. Размер
     * высчитывается внутри класса глифа, чтобы точки над «i», выносные
     * элементы и хвосты не выглядели как случайный дрейф кегля.
     */
    private fun analyzeCharacterStyles(
        bitmap: Bitmap,
        region: TextRegionRecord,
        background: Int,
        fallbackColor: Int,
        inkLight: Boolean?,
    ): List<TextStyleSample> {
        if (region.lines.isEmpty()) return emptyList()
        return region.lines.flatMap { line ->
            val units = collectInkUnits(line)
            if (units.isEmpty()) return@flatMap emptyList()
            // A trend measured across a whole paragraph would read line breaks
            // as a size drift; every line is normalised on its own.
            val scales = sizeTrend(units)
            units.mapIndexed { index, unit ->
                TextStyleSample(
                    color = sampleUnitColor(
                        bitmap,
                        unit.bounds,
                        background,
                        fallbackColor,
                        inkLight,
                    ),
                    scale = scales[index],
                )
            }
        }
    }

    private fun collectInkUnits(line: TextLineRecord): List<InkUnit> {
        val symbols = line.elements
            .flatMap { it.symbols }
            .mapNotNull { symbol ->
                val bounds = symbol.bounds ?: return@mapNotNull null
                if (symbol.text.isBlank()) null else InkUnit(symbol.text, bounds)
            }
        if (symbols.size >= 2) return symbols
        val elements = line.elements
            .mapNotNull { element ->
                val bounds = element.bounds ?: return@mapNotNull null
                if (element.text.isBlank()) null else InkUnit(element.text, bounds)
            }
        if (elements.size >= 2) return elements
        return symbols.ifEmpty { elements }
    }

    private fun sampleUnitColor(
        bitmap: Bitmap,
        unitBounds: Bounds2D,
        background: Int,
        fallbackColor: Int,
        inkLight: Boolean?,
    ): Int {
        val bounds = clampRect(unitBounds.toRect(), bitmap)
        if (bounds.width() < 2 || bounds.height() < 2) return fallbackColor
        // A narrow strip above and below the glyph measures the backdrop where
        // that glyph actually sits. On a gradient or textured poster the
        // paragraph-wide median is wrong for most of the line, and the glyph's
        // own background then counts as ink.
        val pad = (bounds.height() * LOCAL_BACKDROP_STRIP_RATIO)
            .roundToInt()
            .coerceIn(MIN_LOCAL_STRIP_PAD, MAX_LOCAL_STRIP_PAD)
        val topPad = min(pad, bounds.top)
        val bottomPad = min(pad, bitmap.height - bounds.bottom)
        val sampleWidth = bounds.width()
        val sampleHeight = bounds.height() + topPad + bottomPad
        if (sampleWidth < 2 || sampleHeight <= 0) return fallbackColor
        val pixels = IntArray(sampleWidth * sampleHeight)
        try {
            bitmap.getPixels(
                pixels,
                0,
                sampleWidth,
                bounds.left,
                bounds.top - topPad,
                sampleWidth,
                sampleHeight,
            )
        } catch (_: IllegalArgumentException) {
            return fallbackColor
        }
        val backdrop = InkColorSampler.estimateBackdrop(
            topStrip = rows(pixels, sampleWidth, 0, topPad),
            bottomStrip = rows(pixels, sampleWidth, sampleHeight - bottomPad, bottomPad),
            globalBackground = background,
            inkColor = fallbackColor,
        )
        return InkColorSampler.sampleGlyphColor(
            pixels = pixels,
            width = sampleWidth,
            glyphTop = topPad,
            glyphBottom = topPad + bounds.height(),
            backdrop = backdrop,
            fallbackInk = fallbackColor,
            mode = InkColorSampler.Mode.ADAPTIVE,
            inkLight = inkLight,
        )
    }

    private fun rows(pixels: IntArray, width: Int, startRow: Int, rowCount: Int): IntArray {
        if (rowCount <= 0 || width <= 0) return IntArray(0)
        val start = startRow * width
        val end = (startRow + rowCount) * width
        if (start < 0 || end > pixels.size) return IntArray(0)
        return pixels.copyOfRange(start, end)
    }

    /** Линейный тренд высоты внутри классов глифов; шум формы в перевод не попадает. */
    private fun sizeTrend(units: List<InkUnit>): FloatArray {
        val scales = FloatArray(units.size) { 1f }
        if (units.size < MIN_SIZE_TREND_UNITS) return scales
        val classes = IntArray(units.size) { index -> glyphClass(units[index].text) }
        val heights = FloatArray(units.size) { index -> units[index].bounds.height.toFloat() }
        val byClass = Array(GLYPH_CLASS_COUNT) { mutableListOf<Float>() }
        units.indices.forEach { index ->
            if (classes[index] >= 0 && heights[index] >= MIN_GLYPH_HEIGHT) {
                byClass[classes[index]] += heights[index]
            }
        }
        val medians = FloatArray(GLYPH_CLASS_COUNT) { classIndex ->
            val values = byClass[classIndex].sorted()
            // A class needs a couple of samples before its median means anything.
            if (values.size < 2) -1f else values[values.size / 2]
        }
        val valid = units.indices.filter { index ->
            classes[index] >= 0 &&
                heights[index] >= MIN_GLYPH_HEIGHT &&
                medians[classes[index]] > 0f
        }
        if (valid.size < MIN_SIZE_TREND_UNITS) return scales

        val ratios = valid.map { index ->
            (heights[index] / medians[classes[index]]).coerceIn(0.7f, 1.35f)
        }
        // 3-tap median removes single-glyph shape outliers (dots, quotes, tails).
        val smoothed = FloatArray(ratios.size) { index ->
            val window = listOfNotNull(
                ratios.getOrNull(index - 1),
                ratios[index],
                ratios.getOrNull(index + 1),
            ).sorted()
            window[window.size / 2]
        }
        val mean = smoothed.average().toFloat()
        if (mean <= 0.001f) return scales
        var covariance = 0f
        var variance = 0f
        smoothed.forEachIndexed { index, ratio ->
            val t = if (smoothed.size > 1) index.toFloat() / (smoothed.size - 1) - 0.5f else 0f
            covariance += t * (ratio / mean - 1f)
            variance += t * t
        }
        if (variance <= 1e-4f) return scales
        val slope = covariance / variance
        if (abs(slope) < MIN_TRANSFERABLE_SIZE_TREND) return scales
        valid.forEachIndexed { index, unitIndex ->
            val t = if (valid.size > 1) index.toFloat() / (valid.size - 1) - 0.5f else 0f
            scales[unitIndex] = (1f + slope * t)
                .coerceIn(MIN_TRANSFER_SCALE, MAX_TRANSFER_SCALE)
        }
        return scales
    }

    private fun glyphClass(text: String): Int {
        val character = text.firstOrNull { it.isLetterOrDigit() } ?: return -1
        if (character.isUpperCase() || character.isDigit()) return CLASS_TALL
        val lower = character.lowercaseChar()
        if (lower in DESCENDER_CHARS) return CLASS_DESCENDER
        if (lower in ASCENDER_CHARS) return CLASS_TALL
        return CLASS_MID
    }

    /**
     * Chooses the catalogue font whose rendered glyph silhouette best matches
     * the source ink of the longest line. Curved and vertical text is skipped
     * because a horizontal probe cannot be compared with rotated glyphs.
     */
    private fun matchFont(
        region: TextRegionRecord,
        mask: BooleanArray,
        maskWidth: Int,
        maskHeight: Int,
        regionRect: Rect,
        capHeight: Float,
        letterSpacingPx: Float,
        weightHint: Int,
        underlined: Boolean,
        struckThrough: Boolean,
        italicHint: Boolean,
    ): FontMatch? {
        if (region.isVertical || region.isCurved) return null
        if (capHeight < MIN_FONT_MATCH_CAP_HEIGHT || maskWidth < 8 || maskHeight < 6) return null
        val candidates = region.lines.mapNotNull { line ->
            val box = line.bounds?.toRect() ?: return@mapNotNull null
            if (box.width() < 6 || box.height() < 4) return@mapNotNull null
            val letters = line.text.count { it.isLetterOrDigit() }
            if (letters < MIN_FONT_MATCH_LETTERS) return@mapNotNull null
            line to letters
        }
        // Very long lines accumulate OCR differences, and every misplaced glyph
        // lowers the overlap for all fonts equally. A mid-length line keeps the
        // serif/weight differences dominant, and a second attempt hedges
        // against one badly recognised line.
        val ranked = candidates.sortedWith(
            compareBy<Pair<TextLineRecord, Int>> { abs(it.second - REPRESENTATIVE_LETTERS) }
                .thenByDescending { it.first.bounds?.width ?: 0f },
        )
        var best: FontMatch? = null
        ranked.take(REPRESENTATIVE_ATTEMPTS).forEach { (line, _) ->
            val lineRect = line.bounds?.toRect() ?: return@forEach
            val match = runCatching {
                FontMatcher.match(
                    text = line.text,
                    mask = mask,
                    maskWidth = maskWidth,
                    maskHeight = maskHeight,
                    regionRect = regionRect,
                    lineRect = lineRect,
                    capHeight = capHeight,
                    letterSpacingPx = letterSpacingPx,
                    weightHint = weightHint,
                    underlined = underlined,
                    struckThrough = struckThrough,
                    italicHint = italicHint,
                )
            }.getOrNull() ?: return@forEach
            if (best == null || match.score > best!!.score) best = match
        }
        return best
    }

    private fun estimateBackgroundColor(bitmap: Bitmap, rect: Rect): Int {
        val padding = max(2, (min(rect.width(), rect.height()) * BACKGROUND_SAMPLE_PADDING).toInt())
        val sampleRect = Rect(
            (rect.left - padding).coerceIn(0, bitmap.width - 1),
            (rect.top - padding).coerceIn(0, bitmap.height - 1),
            (rect.right + padding).coerceIn(1, bitmap.width),
            (rect.bottom + padding).coerceIn(1, bitmap.height),
        )
        val width = sampleRect.width()
        val height = sampleRect.height()
        if (width <= 0 || height <= 0) return Color.WHITE
        val ring = max(1, min(width, height) / 10)
        val colors = ArrayList<Int>(width * ring * 2 + height * ring * 2)
        try {
            val top = IntArray(width * ring)
            bitmap.getPixels(top, 0, width, sampleRect.left, sampleRect.top, width, ring)
            colors.addAll(top.toList())
            val bottom = IntArray(width * ring)
            bitmap.getPixels(bottom, 0, width, sampleRect.left, sampleRect.bottom - ring, width, ring)
            colors.addAll(bottom.toList())
            val left = IntArray(ring * height)
            bitmap.getPixels(left, 0, ring, sampleRect.left, sampleRect.top, ring, height)
            colors.addAll(left.toList())
            val right = IntArray(ring * height)
            bitmap.getPixels(right, 0, ring, sampleRect.right - ring, sampleRect.top, ring, height)
            colors.addAll(right.toList())
        } catch (_: IllegalArgumentException) {
            return Color.WHITE
        }
        if (colors.isEmpty()) return Color.WHITE
        val histogram = HashMap<Int, Int>()
        colors.forEach { color ->
            val key = quantizedKey(color)
            histogram[key] = (histogram[key] ?: 0) + 1
        }
        val mode = histogram.maxByOrNull { it.value }?.key
        val modePixels = if (mode != null) {
            colors.filter { color -> quantizedKey(color) == mode }
        } else {
            emptyList()
        }
        return if (modePixels.size >= colors.size * MIN_BACKGROUND_MODE_SHARE) {
            medianColor(modePixels)
        } else {
            medianColor(colors)
        }
    }

    private fun maxInkDistance(pixels: IntArray, background: Int): Float {
        val stride = max(1, sqrt(pixels.size.toFloat() / MAX_COLOR_SAMPLES).toInt())
        var maximum = 0f
        var index = 0
        while (index < pixels.size) {
            val distance = colorDistanceSq(pixels[index], background)
            if (distance > maximum) maximum = distance
            index += stride
        }
        return maximum
    }

    private fun estimateInkColor(
        pixels: IntArray,
        background: Int,
        thresholdSq: Float,
    ): Int? {
        val stride = max(1, sqrt(pixels.size.toFloat() / MAX_COLOR_SAMPLES).toInt())
        val histogram = HashMap<Int, Int>()
        var index = 0
        while (index < pixels.size) {
            val color = pixels[index]
            if (colorDistanceSq(color, background) >= thresholdSq) {
                val key = quantizedKey(color)
                histogram[key] = (histogram[key] ?: 0) + 1
            }
            index += stride
        }
        if (histogram.isEmpty()) return null
        val mode = histogram.maxByOrNull { it.value }?.key ?: return null
        val colors = ArrayList<Int>()
        index = 0
        while (index < pixels.size) {
            val color = pixels[index]
            if (colorDistanceSq(color, background) >= thresholdSq && quantizedKey(color) == mode) {
                colors += color
            }
            index += stride
        }
        if (colors.isEmpty()) return null
        return medianColor(colors)
    }

    private fun fallbackInkColor(background: Int): Int =
        if (luminance(background) > 0.5f) Color.BLACK else Color.WHITE

    private fun estimateCapHeight(
        region: TextRegionRecord,
        lineBoxes: List<Rect>,
        regionHeight: Int,
    ): Float {
        val symbolHeights = region.lines.mapNotNull { line ->
            val symbols = line.elements
                .flatMap { it.symbols }
                .filter { symbol -> symbol.text.any(Char::isLetterOrDigit) }
                .mapNotNull { symbol ->
                    val height = symbol.bounds?.height?.takeIf { it > 1f }
                        ?: return@mapNotNull null
                    symbol.text to height
                }
            estimateLineCapHeight(symbols)
                ?: line.bounds?.height?.takeIf { it > 1f }?.times(FALLBACK_CAP_RATIO)
        }
        // A degenerate region may be thinner than the nominal 3 px floor; an
        // inverted coerceIn range would otherwise crash the whole scan.
        val heightLimit = regionHeight.toFloat().coerceAtLeast(1f)
        if (symbolHeights.isNotEmpty()) {
            return symbolHeights.sorted()[symbolHeights.size / 2].coerceIn(1f, heightLimit)
        }
        val lineHeight = lineBoxes.map { it.height().toFloat() }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
            ?: regionHeight.toFloat()
        return (lineHeight * FALLBACK_CAP_RATIO).coerceIn(1f, heightLimit)
    }

    /**
     * Uppercase letters and digits expose cap height directly; ascenders are a
     * close proxy. All-lowercase lines only expose x-height, which is roughly
     * three quarters of cap height — using it as cap height made every lowercase
     * page render noticeably smaller than its source.
     */
    private fun estimateLineCapHeight(symbols: List<Pair<String, Float>>): Float? {
        if (symbols.isEmpty()) return null
        val caps = mutableListOf<Float>()
        val ascenders = mutableListOf<Float>()
        val xHeight = mutableListOf<Float>()
        symbols.forEach { (text, height) ->
            val first = text.firstOrNull { it.isLetterOrDigit() } ?: return@forEach
            when {
                first.isUpperCase() || first.isDigit() -> caps += height
                first.lowercaseChar() in CAP_ASCENDER_CHARS -> ascenders += height
                else -> xHeight += height
            }
        }
        median(caps)?.let { return it }
        median(ascenders)?.let { return it * ASCENDER_HEIGHT_RATIO }
        return median(xHeight)?.times(X_HEIGHT_TO_CAP_RATIO)
    }

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        return values.sorted()[values.size / 2]
    }

    /**
     * Median vertical run through the ink mask. Scanning columns crosses
     * vertical stems at their true width, while the old horizontal scan also
     * measured the long horizontal bars of serifs and capitals, which
     * inflated regular serif print into a false bold.
     */
    private fun estimateStrokeWidth(
        mask: BooleanArray,
        width: Int,
        height: Int,
        capHeight: Float,
    ): Float {
        val maxRun = (capHeight * MAX_STROKE_RUN_RATIO).coerceAtLeast(5f).toInt()
        val runs = ArrayList<Int>()
        for (x in 0 until width) {
            var run = 0
            for (y in 0 until height) {
                if (mask[y * width + x]) {
                    run++
                } else {
                    if (run in 1..maxRun) runs += run
                    run = 0
                }
            }
            if (run in 1..maxRun) runs += run
        }
        if (runs.isEmpty()) return (capHeight * DEFAULT_STROKE_RATIO).coerceAtLeast(1f)
        runs.sort()
        val median = runs[runs.size / 2].toFloat()
        // The ink mask already trims faint edges, but the remaining anti-
        // aliasing still adds a fraction of a pixel to every run. At small
        // point sizes that fraction is large enough to turn regular print into
        // a false bold, so the bias is removed before the ratio is computed.
        val corrected = (median - STROKE_ANTIALIAS_SLACK).coerceAtLeast(MIN_STROKE_PX)
        // Cap height can be as small as 1 px on degenerate regions; the upper
        // bound must never fall below the lower one.
        val maximum = (capHeight * 0.32f).coerceAtLeast(MIN_STROKE_PX)
        return corrected.coerceIn(MIN_STROKE_PX, maximum)
    }

    private fun estimateInkDensity(
        mask: BooleanArray,
        width: Int,
        height: Int,
        lineBoxes: List<Rect>,
    ): Float {
        var ink = 0
        var area = 0
        lineBoxes.forEach { line ->
            val left = line.left.coerceIn(0, width)
            val top = line.top.coerceIn(0, height)
            val right = line.right.coerceIn(left, width)
            val bottom = line.bottom.coerceIn(top, height)
            for (y in top until bottom) {
                val row = y * width
                for (x in left until right) {
                    if (mask[row + x]) ink++
                    area++
                }
            }
        }
        return if (area > 0) ink.toFloat() / area else 0f
    }

    private fun estimateWeight(
        strokeWidth: Float,
        capHeight: Float,
        density: Float,
    ): Int {
        if (capHeight <= 0f) return 400
        val ratio = strokeWidth / capHeight
        return when {
            // Black display faces sit well above bold: without this tier the
            // catalogue could only answer with 700 and every poster headline
            // came out visibly lighter than the source.
            ratio >= 0.22f || density >= 0.47f -> 900
            ratio >= 0.155f || density >= 0.38f -> 700
            ratio >= 0.125f || density >= 0.31f -> 600
            ratio >= 0.10f -> 500
            ratio >= 0.075f -> 400
            else -> 300
        }
    }

    private fun estimateAlignment(region: TextRegionRecord): String {
        val lines = region.lines.mapNotNull { it.bounds }.filter { it.width > 0f }
        if (lines.size < 2) return "left"
        val lefts = lines.map { it.left }
        val rights = lines.map { it.right }
        val centers = lines.map { (it.left + it.right) / 2f }
        val widths = lines.map { it.width }
        val medianWidth = widths.sorted()[widths.size / 2].coerceAtLeast(1f)
        val leftSpread = standardDeviation(lefts)
        val rightSpread = standardDeviation(rights)
        val centerSpread = standardDeviation(centers)
        val minimum = minOf(leftSpread, rightSpread, centerSpread)
        if (minimum > medianWidth * MAX_ALIGNMENT_SPREAD) return "left"
        return when (minimum) {
            centerSpread -> "center"
            rightSpread -> "right"
            else -> "left"
        }
    }

    private fun estimateLetterSpacing(
        region: TextRegionRecord,
        sizePx: Float,
        mono: Boolean,
    ): Float {
        val gaps = ArrayList<Float>()
        region.lines.forEach { line ->
            val symbols = line.elements
                .flatMap { it.symbols }
                .filter { it.text.isNotBlank() }
                .mapNotNull { it.bounds }
                .filter { it.width > 0f }
                .sortedBy { it.left }
            for (index in 1 until symbols.size) {
                val gap = symbols[index].left - symbols[index - 1].right
                if (gap in (-sizePx * 0.4f)..(sizePx * 1.4f)) gaps += gap
            }
        }
        if (gaps.size < MIN_SPACING_SAMPLES) return 0f
        gaps.sort()
        val median = gaps[gaps.size / 2]
        val naturalGap = sizePx * if (mono) MONO_NATURAL_GAP_RATIO else SANS_NATURAL_GAP_RATIO
        return (median - naturalGap).coerceIn(0f, sizePx * MAX_EXTRA_TRACKING_RATIO)
    }

    private fun estimateLineSpacing(region: TextRegionRecord, sizePx: Float): Float {
        val centers = region.lines
            .mapNotNull { it.bounds }
            .sortedBy { it.top }
            .map { (it.top + it.bottom) / 2f }
        if (centers.size < 2) return DEFAULT_LINE_SPACING
        val pitches = centers.zipWithNext { first, second -> abs(second - first) }
            .filter { it > 1f }
            .sorted()
        if (pitches.isEmpty()) return DEFAULT_LINE_SPACING
        val pitch = pitches[pitches.size / 2]
        return (pitch / (sizePx * NOMINAL_LINE_HEIGHT_RATIO)).coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING)
    }

    private fun detectDecoration(
        mask: BooleanArray,
        width: Int,
        height: Int,
        lineBoxes: List<Rect>,
        region: TextRegionRecord,
        underline: Boolean,
    ): Boolean {
        if (underline) {
            // Подчёркивание имеет смысл только для горизонтального текста.
            val vertical = region.bounds.height > region.bounds.width * 2.2f
            if (vertical) return false
        }
        for (line in lineBoxes) {
            val lineHeight = line.height()
            if (lineHeight < 6) continue
            val start = if (underline) (lineHeight * 0.72f).toInt() else (lineHeight * 0.4f).toInt()
            val end = if (underline) (lineHeight * 0.98f).toInt() else (lineHeight * 0.62f).toInt()
            if (end <= start) continue
            val fractions = FloatArray(end - start)
            for (offset in start until end) {
                val y = line.top + offset
                if (y !in 0 until height) continue
                var count = 0
                val row = y * width
                for (x in line.left.coerceIn(0, width) until line.right.coerceIn(0, width)) {
                    if (mask[row + x]) count++
                }
                fractions[offset - start] = count.toFloat() / line.width().coerceAtLeast(1)
            }
            var bestIndex = -1
            var best = 0f
            fractions.forEachIndexed { index, value ->
                if (value > best) {
                    best = value
                    bestIndex = index
                }
            }
            if (best < if (underline) UNDERLINE_COVERAGE else STRIKE_COVERAGE) continue
            val before = fractions.getOrNull(bestIndex - 1) ?: 0f
            val after = fractions.getOrNull(bestIndex + 1) ?: 0f
            if (before < best * 0.72f && after < best * 0.72f) return true
        }
        return false
    }

    private fun fallback(region: TextRegionRecord, bitmap: Bitmap): RegionStyleAnalysis {
        val rect = clampRect(region.bounds.toRect(), bitmap)
        val background = if (rect.width() > 2 && rect.height() > 2) {
            estimateBackgroundColor(bitmap, rect)
        } else {
            Color.WHITE
        }
        val lineCount = region.lines.size.coerceAtLeast(1)
        val size = (region.bounds.height / lineCount * 0.8f).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        val style = TextStyle(
            color = fallbackInkColor(background),
            backgroundColor = background,
            sizePx = size,
            angle = region.lines.mapNotNull { it.angleDegrees }.averageOrNull() ?: 0f,
            fontWeight = 400,
            alignment = estimateAlignment(region),
            lineSpacingMultiplier = 1f,
            capHeightPx = size * CAP_HEIGHT_RATIO,
        )
        return RegionStyleAnalysis(
            style = style,
            characterColors = emptyList(),
            characterStyles = emptyList(),
            confidenceByAttribute = mapOf(
                "foregroundColor" to 0.2f,
                "localBackgroundColor" to 0.35f,
                "textHeightPx" to 0.25f,
                "weight" to 0.2f,
            ),
            fontClassConfidence = 0.25f,
        )
    }

    // ── helpers ────────────────────────────────────────────────

    /** 12-bit RGB bucket; the shifts must stay inside their own parentheses. */
    private fun quantizedKey(color: Int): Int =
        ((Color.red(color) shr 4) shl 16) or
            ((Color.green(color) shr 4) shl 8) or
            (Color.blue(color) shr 4)

    private fun colorDistanceSq(first: Int, second: Int): Float {
        val dr = (Color.red(first) - Color.red(second)) / 255f
        val dg = (Color.green(first) - Color.green(second)) / 255f
        val db = (Color.blue(first) - Color.blue(second)) / 255f
        return (dr * dr + dg * dg + db * db) / 3f
    }

    private fun luminance(color: Int): Float =
        (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f

    private fun medianColor(pixels: List<Int>): Int {
        if (pixels.isEmpty()) return Color.TRANSPARENT
        val reds = IntArray(pixels.size) { Color.red(pixels[it]) }.apply { sort() }
        val greens = IntArray(pixels.size) { Color.green(pixels[it]) }.apply { sort() }
        val blues = IntArray(pixels.size) { Color.blue(pixels[it]) }.apply { sort() }
        val alphas = IntArray(pixels.size) { Color.alpha(pixels[it]) }.apply { sort() }
        val middle = pixels.size / 2
        return Color.argb(alphas[middle], reds[middle], greens[middle], blues[middle])
    }

    private fun standardDeviation(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = values.average().toFloat()
        return sqrt(values.map { (it - mean) * (it - mean) }.average().toFloat())
    }

    private fun minInkPixels(area: Int): Int = max(6, area / 8000)

    private fun clampRect(rect: Rect, bitmap: Bitmap): Rect = Rect(
        rect.left.coerceIn(0, bitmap.width - 1),
        rect.top.coerceIn(0, bitmap.height - 1),
        rect.right.coerceIn(1, bitmap.width),
        rect.bottom.coerceIn(1, bitmap.height),
    ).let { safe ->
        if (safe.right <= safe.left) Rect(safe.left, safe.top, min(safe.left + 1, bitmap.width), safe.bottom)
        else safe
    }

    private fun Bounds2D.toRect(): Rect = Rect(
        kotlin.math.floor(left).toInt(),
        kotlin.math.floor(top).toInt(),
        kotlin.math.ceil(right).toInt(),
        kotlin.math.ceil(bottom).toInt(),
    )

    private fun List<Float>.averageOrNull(): Float? = takeIf { it.isNotEmpty() }?.average()?.toFloat()

    private companion object {
        const val CAP_HEIGHT_RATIO = 0.72f
        const val X_HEIGHT_RATIO = 0.52f
        const val X_HEIGHT_TO_CAP_RATIO = CAP_HEIGHT_RATIO / X_HEIGHT_RATIO
        const val ASCENDER_HEIGHT_RATIO = 0.97f
        const val FALLBACK_CAP_RATIO = 0.68f
        val CAP_ASCENDER_CHARS = setOf('b', 'd', 'f', 'h', 'k', 'l', 't', 'б')
        const val MIN_FONT_SIZE = 5f
        const val MAX_FONT_SIZE = 400f
        // Squared RGB distance, matching InkColorSampler: a glyph that differs
        // from its backdrop by ~0.03 per channel is still real ink when the
        // sampled core decides the colour. Extreme overexposure sits there.
        const val MIN_USEFUL_INK_DISTANCE = 0.0008f
        const val MIN_INK_DISTANCE = 0.03f
        const val INK_THRESHOLD_RATIO = 0.35f
        const val STRONG_INK_RATIO = 0.55f
        const val FAINT_MASK_RATIO = 0.60f
        const val STRONG_SQ_CEILING = 0.60f
        // CSS-like text shadows blur by roughly 5% of the cap height; the
        // offset is measured from the halo/stroke centroids and stays inside
        // a fraction of that blur.
        const val HALO_RADIUS_RATIO = 0.055f
        const val MIN_HALO_RADIUS_PX = 1.5f
        const val MAX_HALO_RADIUS_PX = 14f
        const val HALO_OFFSET_LIMIT = 0.8f
        const val LOCAL_BACKDROP_STRIP_RATIO = 0.30f
        const val MIN_LOCAL_STRIP_PAD = 2
        const val MAX_LOCAL_STRIP_PAD = 10
        const val MIN_FONT_MATCH_CAP_HEIGHT = 4f
        const val MIN_FONT_MATCH_LETTERS = 2
        const val REPRESENTATIVE_LETTERS = 26
        const val REPRESENTATIVE_ATTEMPTS = 2
        const val MAX_COLOR_SAMPLES = 90_000
        const val BACKGROUND_SAMPLE_PADDING = 0.18f
        const val MIN_BACKGROUND_MODE_SHARE = 0.28f
        const val MAX_STROKE_RUN_RATIO = 0.65f
        const val DEFAULT_STROKE_RATIO = 0.09f
        const val STROKE_ANTIALIAS_SLACK = 0.4f
        const val MIN_STROKE_PX = 0.6f
        const val MAX_ALIGNMENT_SPREAD = 0.35f
        // Real italics lean by eight degrees or more; below six the shear
        // estimate is indistinguishable from glyph-shape noise.
        const val MIN_ITALIC_SLANT = 6f
        const val MIN_SPACING_SAMPLES = 3
        const val SANS_NATURAL_GAP_RATIO = 0.06f
        const val MONO_NATURAL_GAP_RATIO = 0.16f
        const val MAX_EXTRA_TRACKING_RATIO = 0.45f
        const val DEFAULT_LINE_SPACING = 1.0f
        const val NOMINAL_LINE_HEIGHT_RATIO = 1.16f
        const val MIN_LINE_SPACING = 0.82f
        const val MAX_LINE_SPACING = 2.6f
        const val UNDERLINE_COVERAGE = 0.55f
        const val STRIKE_COVERAGE = 0.62f
        const val MIN_SIZE_TREND_UNITS = 4
        const val MIN_GLYPH_HEIGHT = 3f
        const val MIN_TRANSFERABLE_SIZE_TREND = 0.045f
        const val MIN_TRANSFER_SCALE = 0.88f
        const val MAX_TRANSFER_SCALE = 1.14f
        const val GLYPH_CLASS_COUNT = 3
        const val CLASS_TALL = 0
        const val CLASS_DESCENDER = 1
        const val CLASS_MID = 2

        private val DESCENDER_CHARS = setOf(
            'g', 'j', 'p', 'q', 'y',
            'р', 'у', 'ф', 'ц', 'щ', 'д',
        )

        private val ASCENDER_CHARS = setOf(
            'b', 'd', 'f', 'h', 'k', 'l', 't',
            'б', 'ё', 'й',
        )
    }
}
