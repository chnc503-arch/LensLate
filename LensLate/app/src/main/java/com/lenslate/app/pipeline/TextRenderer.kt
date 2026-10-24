package com.lenslate.app.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.TextCurve
import com.lenslate.app.model.TextLineRecord
import com.lenslate.app.model.TextRegionRecord
import com.lenslate.app.model.TextStyle
import com.lenslate.app.model.TextStyleSample
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Накладывает перевод на восстановленный фон, повторяя оформление оригинала:
 * кегль, насыщенность, наклон, трекинг, межстрочный интервал, выравнивание,
 * цвет (в том числе по символам), подчёркивание/зачёркивание, вертикальную
 * раскладку и изогнутые надписи. Если перевод длиннее оригинала, кегль
 * уменьшается минимально необходим — блок остаётся в исходных границах.
 */
@Singleton
class TextRenderer @Inject constructor() {

    fun render(
        source: Bitmap,
        regions: List<TextRegionRecord>,
        obstacles: List<TextRegionRecord> = regions,
    ): Bitmap {
        val translated = regions.filter { !it.translation.isNullOrBlank() }
        val result = source.copy(Bitmap.Config.ARGB_8888, true) ?: return source
        if (translated.isEmpty()) return result
        val canvas = Canvas(result)
        translated.forEach { region ->
            renderRegion(canvas, region, obstacles, source.height)
        }
        return result
    }

    /** Собирает TextStyle из сохранённых оценок; используется и для карточек результата. */
    fun styleOf(region: TextRegionRecord): TextStyle {
        val appearance = region.appearance
        val fallbackSize = (region.bounds.height / region.lines.size.coerceAtLeast(1) * 0.82f)
            .coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        val weight = appearance?.fontWeight?.coerceIn(100, 900)
            ?: if (appearance?.weight == "bold") 700 else 400
        val fontClass = appearance?.likelyFontClass ?: "sans"
        return TextStyle(
            color = appearance?.foregroundArgb ?: Color.WHITE,
            backgroundColor = appearance?.localBackgroundArgb ?: Color.TRANSPARENT,
            sizePx = appearance?.textHeightPx?.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE) ?: fallbackSize,
            isBold = weight >= 600,
            isMono = fontClass == "monospace",
            angle = region.lines.mapNotNull { it.angleDegrees }.averageOrNull() ?: 0f,
            fontFamily = fontClass,
            fontWeight = weight,
            alignment = appearance?.alignment ?: "left",
            letterSpacingPx = appearance?.spacingPx ?: 0f,
            lineSpacingMultiplier = appearance?.lineSpacingMultiplier
                ?.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING) ?: 1.05f,
            capHeightPx = appearance?.capHeightPx,
            slantDegrees = appearance?.slantDegrees ?: 0f,
            horizontalScale = appearance?.horizontalScale?.coerceIn(MIN_HORIZONTAL_SCALE, MAX_HORIZONTAL_SCALE) ?: 1f,
            strokeWidthPx = appearance?.strokeWidthPx ?: 0f,
            isUnderlined = appearance?.isUnderlined ?: false,
            isStruckThrough = appearance?.isStruckThrough ?: false,
            fontKey = appearance?.fontKey
                ?: FontCatalog.fallbackKey(fontClass, weight, weight >= 600),
            fontMatchScore = appearance?.fontMatchScore ?: 0f,
            linePitchPx = sourceLinePitch(region),
        )
    }

    private fun renderRegion(
        canvas: Canvas,
        region: TextRegionRecord,
        obstacles: List<TextRegionRecord>,
        imageHeight: Int,
    ) {
        val rawText = region.translation?.takeIf { it.isNotBlank() } ?: return
        val text = MicroSpacing.transfer(region, matchSourceCase(region.text, rawText))
        val bounds = region.bounds
        if (bounds.width < 1f || bounds.height < 1f) return

        val style = styleOf(region)
        val paint = buildPaint(style)
        val samples = region.characterStyles.ifEmpty {
            region.characterColors.map { TextStyleSample(it) }
        }
        val verticalJapanese = region.isVertical && containsKana(text)
        val curve = region.curve?.takeIf {
            region.isCurved && !region.isVertical && !text.contains('\n') &&
                it.radius.isFinite() && it.radius > 1f
        }

        when {
            verticalJapanese -> renderVerticalJapanese(canvas, region, text, style, paint, samples)
            curve != null -> renderCurved(canvas, curve, text, style, paint, samples)
            region.isVertical -> renderRotatedColumn(canvas, region, text, style, paint, samples)
            else -> renderHorizontal(
                canvas,
                region,
                text,
                style,
                paint,
                samples,
                obstacles,
                imageHeight,
            )
        }
    }

    /** Повторяет регистр оригинала: ВЕРХНИЙ РЕГИСТР и заглавную первую букву. */
    private fun matchSourceCase(source: String, translation: String): String {
        if (translation.isEmpty()) return translation
        val letters = source.filter { it.isLetter() }
        if (letters.length >= 2) {
            val upperRatio = letters.count { it.isUpperCase() }.toFloat() / letters.length
            if (upperRatio >= 0.75f) return translation.uppercase()
        }
        val sourceStart = source.firstOrNull { it.isLetter() } ?: return translation
        if (!sourceStart.isUpperCase()) return translation
        val index = translation.indexOfFirst { it.isLetter() }
        if (index < 0 || !translation[index].isLowerCase()) return translation
        return buildString(translation.length) {
            append(translation, 0, index)
            append(translation[index].uppercaseChar())
            append(translation, index + 1, translation.length)
        }
    }

    private fun renderHorizontal(
        canvas: Canvas,
        region: TextRegionRecord,
        text: String,
        style: TextStyle,
        paint: TextPaint,
        samples: List<TextStyleSample>,
        obstacles: List<TextRegionRecord>,
        imageHeight: Int,
    ) {
        val frames = sourceLineFrames(region)
        val singleSourceLine = frames.size <= 1
        val content = if (singleSourceLine) text.replace('\n', ' ') else text
        val spannable = buildSpannable(
            text = content,
            samples = samples,
            fallback = style.color,
            transferScale = StyleGradient.hasScaleDrift(samples),
        )
        val alignment = layoutAlignment(style.alignment)
        val bounds = region.bounds
        val frame = frames.first()
        val baseSize = calibratedSize(style, paint)

        val layout: StaticLayout
        val drawLeft: Float
        val drawTop: Float

        if (singleSourceLine) {
            val targetWidth = frame.width.coerceAtLeast(2f)
            // style.sizePx is derived from the source glyph height. The extra
            // headroom covers ascender+descender metrics, not a growth request;
            // the old code fitted layout.height to the ink box and silently
            // shrank every translation.
            val sizeCeiling = (frame.height * MAX_SIZE_TO_LINE_HEIGHT)
                .coerceAtLeast(MIN_TEXT_SIZE)
            var size = baseSize.coerceIn(MIN_TEXT_SIZE, sizeCeiling)
            var naturalWidth = measureUnwrappedWidth(spannable, paint, size, style)
            if (naturalWidth <= 0f) return
            if (naturalWidth < targetWidth && content.length > 1) {
                // Short translations gain tracking first: widening the gaps
                // keeps glyph shapes intact longer than stretching them does.
                val extraEm = ((targetWidth - naturalWidth) / (content.length - 1)) / size
                if (extraEm > MIN_TRACKING_INCREMENT) {
                    paint.letterSpacing = (paint.letterSpacing +
                        extraEm.coerceAtMost(MAX_EXTRA_TRACKING_EM))
                        .coerceAtMost(MAX_TOTAL_TRACKING_EM)
                    naturalWidth = measureUnwrappedWidth(spannable, paint, size, style)
                }
            }
            if (naturalWidth < targetWidth) {
                val stretch = (targetWidth / naturalWidth.coerceAtLeast(0.01f))
                    .coerceIn(1f, MAX_WIDTH_STRETCH_SCALE)
                if (stretch > 1.001f) {
                    paint.textScaleX = (paint.textScaleX * stretch)
                        .coerceIn(MIN_HORIZONTAL_SCALE, MAX_HORIZONTAL_SCALE)
                    naturalWidth = measureUnwrappedWidth(spannable, paint, size, style)
                }
            } else if (naturalWidth > targetWidth) {
                // A long translation shrinks moderately before the glyphs are
                // squeezed: a smaller optical size reads as the same typeface,
                // a compressed one does not.
                val required = (size * targetWidth / naturalWidth)
                    .coerceAtLeast(size * MIN_FIT_SIZE_SCALE)
                if (required < size - 0.05f) {
                    size = required
                    naturalWidth = measureUnwrappedWidth(spannable, paint, size, style)
                }
                if (naturalWidth > targetWidth && content.length > 1) {
                    val deficitEm = ((naturalWidth - targetWidth) /
                        (content.length - 1)) / size
                    paint.letterSpacing = (paint.letterSpacing -
                        deficitEm.coerceAtMost(MAX_TRACKING_COMPRESSION_EM))
                        .coerceAtLeast(MIN_NEGATIVE_TRACKING_EM)
                    naturalWidth = measureUnwrappedWidth(spannable, paint, size, style)
                }
                if (naturalWidth > targetWidth) {
                    val condense = (targetWidth / naturalWidth)
                        .coerceIn(MIN_WIDTH_CONDENSE_SCALE, 1f)
                    paint.textScaleX = (paint.textScaleX * condense)
                        .coerceIn(MIN_HORIZONTAL_SCALE, MAX_HORIZONTAL_SCALE)
                    naturalWidth = measureUnwrappedWidth(spannable, paint, size, style)
                }
            }
            layout = buildFittedLine(spannable, paint, style, alignment, targetWidth, size)
            drawLeft = horizontalOffset(
                frame = frame,
                layoutWidth = maxLineWidth(layout).coerceAtMost(targetWidth),
                alignment = style.alignment,
            )
            // Anchor the baseline, not the ink centre: the translated glyphs
            // then sit exactly where the source line sat.
            drawTop = frame.baseline - layout.getLineBaseline(0)
        } else {
            val targetWidth = bounds.width.roundToInt().coerceAtLeast(2)
            val room = TextPlacement.room(
                bounds = bounds,
                obstacles = obstacles
                    .filter { it.id != region.id }
                    .map { it.bounds },
                canvasHeight = imageHeight.toFloat(),
            )
            val gap = (baseSize * NEIGHBOUR_GAP_EM).coerceAtLeast(2f)
            val placement = TextPlacement.window(
                bounds = bounds,
                room = room,
                gap = gap,
                maxGrowthRatio = MAX_NEIGHBOUR_GROWTH_RATIO,
            )
            layout = fitLayout(
                text = spannable,
                paint = paint,
                maxWidth = targetWidth,
                maxHeight = placement.maxHeight,
                style = style,
                alignment = alignment,
                startSize = baseSize,
                preferredLines = frames.size,
            )
            drawLeft = bounds.left
            drawTop = if (layout.lineCount >= 2) {
                TextPlacement.multilineTop(
                    anchoredTop = frames.first().baseline - layout.getLineBaseline(0),
                    layoutHeight = layout.height.toFloat(),
                    layoutLines = layout.lineCount,
                    sourceLines = frames.size,
                    sourceHeight = bounds.height,
                    window = placement,
                )
            } else {
                bounds.top + (bounds.height - layout.height) / 2f +
                    inkVerticalShift(layout, paint, spannable)
            }
        }

        canvas.save()
        if (abs(style.angle) >= MIN_ROTATION_DEGREES) {
            canvas.rotate(
                style.angle,
                (bounds.left + bounds.right) / 2f,
                (bounds.top + bounds.bottom) / 2f,
            )
        }
        canvas.translate(drawLeft, drawTop)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun horizontalOffset(
        frame: SourceLineFrame,
        layoutWidth: Float,
        alignment: String,
    ): Float = when (alignment) {
        "right" -> frame.right - layoutWidth
        "center" -> (frame.left + frame.right - layoutWidth) / 2f
        else -> frame.left
    }

    private fun buildFittedLine(
        text: CharSequence,
        paint: TextPaint,
        style: TextStyle,
        alignment: Layout.Alignment,
        targetWidth: Float,
        startSize: Float,
    ): StaticLayout {
        val layoutWidth = ceil(targetWidth * SINGLE_LINE_WIDTH_SLACK)
            .toInt()
            .coerceAtLeast(2)
        val first = buildLayout(text, paint, layoutWidth, alignment, startSize, style)
        if (fitsSingleLine(first, targetWidth)) return first
        var low = MIN_TEXT_SIZE
        var high = startSize
        var best: StaticLayout? = null
        repeat(FIT_ITERATIONS) {
            val middle = (low + high) / 2f
            val candidate = buildLayout(text, paint, layoutWidth, alignment, middle, style)
            if (fitsSingleLine(candidate, targetWidth)) {
                best = candidate
                low = middle
            } else {
                high = middle
            }
        }
        return best ?: buildLayout(text, paint, layoutWidth, alignment, MIN_TEXT_SIZE, style)
    }

    private fun fitsSingleLine(layout: StaticLayout, targetWidth: Float): Boolean =
        layout.lineCount <= 1 &&
            maxLineWidth(layout) <= targetWidth * SINGLE_LINE_WIDTH_SLACK + 2f

    private fun measureUnwrappedWidth(
        text: CharSequence,
        paint: TextPaint,
        size: Float,
        style: TextStyle,
    ): Float {
        val layout = buildLayout(
            text,
            paint,
            UNWRAPPED_PROBE_WIDTH,
            Layout.Alignment.ALIGN_NORMAL,
            size,
            style,
        )
        var total = 0f
        for (line in 0 until layout.lineCount) total += layout.getLineWidth(line)
        return total
    }

    private data class SourceLineFrame(
        val left: Float,
        val right: Float,
        val top: Float,
        val bottom: Float,
        val baseline: Float,
    ) {
        val width: Float get() = (right - left).coerceAtLeast(0f)
        val height: Float get() = (bottom - top).coerceAtLeast(0f)
    }

    private fun sourceLineFrames(region: TextRegionRecord): List<SourceLineFrame> {
        val frames = region.lines.mapNotNull { line ->
            val bounds = line.bounds ?: return@mapNotNull null
            if (bounds.width <= 0f || bounds.height <= 0f) return@mapNotNull null
            SourceLineFrame(
                left = bounds.left,
                right = bounds.right,
                top = bounds.top,
                bottom = bounds.bottom,
                baseline = estimateBaseline(
                    line = line,
                    bounds = bounds,
                    capHeight = region.appearance?.capHeightPx,
                ),
            )
        }
        return frames.ifEmpty { listOf(fallbackLineFrame(region.bounds)) }
    }

    /** Median baseline-to-baseline distance, used to keep the source line grid. */
    private fun sourceLinePitch(region: TextRegionRecord): Float? {
        val centers = region.lines
            .mapNotNull { it.bounds }
            .filter { it.height > 0f }
            .sortedBy { it.top }
            .map { (it.top + it.bottom) / 2f }
        if (centers.size < 2) return null
        val pitches = centers
            .zipWithNext { first, second -> second - first }
            .filter { it > 1f }
            .sorted()
        if (pitches.isEmpty()) return null
        return pitches[pitches.size / 2]
    }

    private fun fallbackLineFrame(bounds: Bounds2D): SourceLineFrame = SourceLineFrame(
        left = bounds.left,
        right = bounds.right,
        top = bounds.top,
        bottom = bounds.bottom,
        baseline = bounds.top + bounds.height * FALLBACK_BASELINE_RATIO,
    )

    /**
     * Baseline sits on the bottom of glyphs without descenders. Averaging
     * those bottoms is stable even when the line also has g/p/y tails, which
     * a naive box-centre would drag downward.
     */
    private fun estimateBaseline(
        line: TextLineRecord,
        bounds: Bounds2D,
        capHeight: Float?,
    ): Float {
        val units = line.elements.flatMap { element ->
            element.symbols.mapNotNull { symbol ->
                symbol.bounds?.let { symbol.text to it }
            }
        }
        val onBaseline = units
            .filter { (text, _) ->
                val character = text.firstOrNull { it.isLetterOrDigit() } ?: return@filter false
                !hasDescender(character)
            }
            .map { (_, unitBounds) -> unitBounds.bottom }
        val baseline = when {
            onBaseline.size >= 2 -> onBaseline.sorted()[onBaseline.size / 2]
            units.any { (text, _) ->
                text.firstOrNull { it.isLetterOrDigit() }?.let(::hasDescender) == true
            } -> bounds.bottom - bounds.height * DESCENDER_DEPTH_RATIO
            // Some engines return line boxes without symbol boxes. Such a box
            // may include the descender and leading, so its bottom is not the
            // baseline; the measured cap height locates the baseline instead.
            units.isEmpty() -> fallbackBaseline(bounds, capHeight)
            else -> bounds.bottom
        }
        return baseline.coerceIn(bounds.top, bounds.bottom)
    }

    private fun fallbackBaseline(bounds: Bounds2D, capHeight: Float?): Float {
        val cap = capHeight?.takeIf { it.isFinite() && it > 1f } ?: return bounds.bottom
        return (bounds.top + (bounds.height + cap) / 2f)
            .coerceIn(bounds.top, bounds.bottom)
    }

    private fun hasDescender(character: Char): Boolean =
        character.lowercaseChar() in DESCENDER_CHARS

    /** Кириллица/латиница в вертикальной колонке: строка поворачивается на 90°, как латиница в вертикальном наборе. */
    private fun renderRotatedColumn(
        canvas: Canvas,
        region: TextRegionRecord,
        text: String,
        style: TextStyle,
        paint: TextPaint,
        samples: List<TextStyleSample>,
    ) {
        val bounds = region.bounds
        val columnWidth = bounds.width.roundToInt().coerceAtLeast(2)
        val columnHeight = bounds.height.roundToInt().coerceAtLeast(2)
        // Layout is built in transposed space: its width runs down the column.
        val spannable = buildSpannable(text, samples, style.color, transferScale = false)
        val layout = fitLayout(
            text = spannable,
            paint = paint,
            maxWidth = columnHeight,
            maxHeight = columnWidth,
            style = style,
            alignment = Layout.Alignment.ALIGN_NORMAL,
            startSize = calibratedSize(style, paint),
        )
        val usedLength = maxLineWidth(layout).coerceAtMost(columnHeight.toFloat())

        canvas.save()
        canvas.translate(
            bounds.left + bounds.width / 2f + columnWidth / 2f,
            bounds.top + bounds.height / 2f - columnHeight / 2f,
        )
        canvas.rotate(90f)
        canvas.translate(
            (columnHeight - usedLength) / 2f,
            (columnWidth - layout.height) / 2f,
        )
        layout.draw(canvas)
        canvas.restore()
    }

    private fun renderVerticalJapanese(
        canvas: Canvas,
        region: TextRegionRecord,
        text: String,
        style: TextStyle,
        paint: TextPaint,
        samples: List<TextStyleSample>,
    ) {
        val chars = text.filterNot { it == '\n' || it == '\r' }
        if (chars.isEmpty()) return
        val bounds = region.bounds
        paint.textSize = style.sizePx
        val widest = chars.maxOf { paint.measureText(it.toString()) }.coerceAtLeast(1f)
        val spacingMultiplier = style.lineSpacingMultiplier.coerceIn(0.85f, 2.2f)
        val stepAtBase = paint.textSize * spacingMultiplier + style.letterSpacingPx * paint.textScaleX
        val scaleToWidth = bounds.width * 0.94f / widest
        val scaleToHeight = bounds.height / (chars.length * stepAtBase).coerceAtLeast(1f)
        paint.textSize = (paint.textSize * min(1f, min(scaleToWidth, scaleToHeight)))
            .coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)

        val step = paint.textSize * spacingMultiplier + style.letterSpacingPx * paint.textScaleX
        val totalHeight = step * chars.length
        var cellTop = bounds.top + (bounds.height - totalHeight) / 2f
        val metrics = paint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val centerX = (bounds.left + bounds.right) / 2f

        canvas.save()
        chars.forEachIndexed { index, char ->
            paint.color = StyleGradient.colorAt(
                samples,
                positionOf(index, chars.length),
                style.color,
            )
            val character = char.toString()
            val charWidth = paint.measureText(character)
            val baseline = cellTop + (step - lineHeight) / 2f - metrics.ascent
            canvas.drawText(character, centerX - charWidth / 2f, baseline, paint)
            cellTop += step
        }
        canvas.restore()
    }

    /**
     * Draws the translation on the circle fitted to the source glyphs, reusing
     * the original centre, radius, angular span and reading direction. The text
     * never guesses an upward arch: straight text never reaches this method.
     */
    private fun renderCurved(
        canvas: Canvas,
        curve: TextCurve,
        text: String,
        style: TextStyle,
        paint: TextPaint,
        samples: List<TextStyleSample>,
    ) {
        val chars = text.replace('\n', ' ').filterNot { it == '\r' }
        if (chars.isBlank()) return
        val spanDegrees = curve.endAngleDegrees - curve.startAngleDegrees
        if (abs(spanDegrees) < MIN_CURVE_SPAN_DEGREES) return
        val direction = if (spanDegrees >= 0f) 1f else -1f
        val arcLength = curve.radius *
            Math.toRadians(abs(spanDegrees).toDouble()).toFloat()
        if (arcLength <= 1f) return

        paint.textSize = calibratedSize(style, paint).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        var widths = FloatArray(chars.length) { paint.measureText(chars[it].toString()) }
        var totalWidth = widths.sum()
        val targetArc = arcLength * CURVE_FILL_RATIO
        if (totalWidth > targetArc) {
            paint.textSize = (paint.textSize * targetArc / totalWidth).coerceAtLeast(MIN_TEXT_SIZE)
            widths = FloatArray(chars.length) { paint.measureText(chars[it].toString()) }
            totalWidth = widths.sum()
            if (totalWidth > targetArc) {
                val squeeze = (targetArc / totalWidth).coerceIn(MIN_CURVE_SQUEEZE, 1f)
                paint.textScaleX = (paint.textScaleX * squeeze)
                    .coerceIn(MIN_CURVE_TEXT_SCALE, MAX_HORIZONTAL_SCALE)
                widths = FloatArray(chars.length) { paint.measureText(chars[it].toString()) }
                totalWidth = widths.sum()
            }
        }
        // Shorter translations stay centred inside the original arc instead of
        // stretching the letter spacing to fill it.
        var travelled = ((arcLength - totalWidth) / 2f).coerceAtLeast(0f)
        val bounds = Rect()

        canvas.save()
        chars.forEachIndexed { index, char ->
            val character = char.toString()
            val advance = widths[index].coerceAtLeast(0.01f)
            if (!char.isWhitespace()) {
                val angleDegrees = curve.startAngleDegrees + direction *
                    Math.toDegrees(((travelled + advance / 2f) / curve.radius).toDouble()).toFloat()
                val radians = Math.toRadians(angleDegrees.toDouble())
                val x = curve.centerX + curve.radius * cos(radians).toFloat()
                val y = curve.centerY + curve.radius * sin(radians).toFloat()
                paint.getTextBounds(character, 0, character.length, bounds)
                val baselineY = -(bounds.top + bounds.bottom) / 2f
                paint.color = StyleGradient.colorAt(
                    samples,
                    positionOf(index, chars.length),
                    style.color,
                )
                canvas.save()
                canvas.translate(x, y)
                canvas.rotate(angleDegrees + direction * 90f)
                canvas.drawText(character, -advance / 2f, baselineY, paint)
                canvas.restore()
            }
            travelled += advance
        }
        canvas.restore()
    }

    private fun fitLayout(
        text: CharSequence,
        paint: TextPaint,
        maxWidth: Int,
        maxHeight: Int,
        style: TextStyle,
        alignment: Layout.Alignment,
        startSize: Float = style.sizePx,
        preferredLines: Int? = null,
    ): StaticLayout {
        val maxSize = min(MAX_TEXT_SIZE, maxHeight * 1.7f + MIN_TEXT_SIZE)
        val start = startSize.coerceIn(MIN_TEXT_SIZE, maxSize)
        var size = start
        var layout = buildLayout(text, paint, maxWidth, alignment, size, style)
        if (fits(layout, maxWidth, maxHeight)) {
            return matchSourceLineCount(
                layout, size, text, paint, maxWidth, maxHeight, style, alignment, preferredLines,
            )
        }

        var low = MIN_TEXT_SIZE
        var high = start
        var bestSize = -1f
        repeat(FIT_ITERATIONS) {
            val middle = (low + high) / 2f
            val candidate = buildLayout(text, paint, maxWidth, alignment, middle, style)
            if (fits(candidate, maxWidth, maxHeight)) {
                bestSize = middle
                low = middle
            } else {
                high = middle
            }
        }

        if (bestSize < 0f) {
            size = MIN_TEXT_SIZE
            layout = buildLayout(text, paint, maxWidth, alignment, size, style)
            val widest = maxLineWidth(layout)
            if (widest > maxWidth && widest > 0f) {
                paint.textScaleX = (paint.textScaleX * maxWidth / widest)
                    .coerceIn(MIN_HORIZONTAL_SCALE, paint.textScaleX)
                layout = buildLayout(text, paint, maxWidth, alignment, size, style)
            }
            return layout
        }

        val fitted = buildLayout(text, paint, maxWidth, alignment, bestSize, style)
        return matchSourceLineCount(
            fitted, bestSize, text, paint, maxWidth, maxHeight, style, alignment, preferredLines,
        )
    }

    /**
     * When a translation wraps into more lines than the source, a slightly
     * smaller optical size is preferred if it restores the original line count:
     * the paragraph then occupies the same vertical rhythm as the original.
     * The reduction is capped, so genuinely longer text still wraps.
     */
    private fun matchSourceLineCount(
        layout: StaticLayout,
        fittedSize: Float,
        text: CharSequence,
        paint: TextPaint,
        maxWidth: Int,
        maxHeight: Int,
        style: TextStyle,
        alignment: Layout.Alignment,
        preferredLines: Int?,
    ): StaticLayout {
        if (preferredLines == null || preferredLines < 2 || layout.lineCount <= preferredLines) {
            return layout
        }
        var low = fittedSize * MIN_LINE_COUNT_SIZE_SCALE
        var high = fittedSize
        var candidate: StaticLayout? = null
        repeat(FIT_ITERATIONS) {
            val middle = (low + high) / 2f
            val trial = buildLayout(text, paint, maxWidth, alignment, middle, style)
            if (trial.lineCount <= preferredLines && fits(trial, maxWidth, maxHeight)) {
                candidate = trial
                low = middle
            } else {
                high = middle
            }
        }
        return candidate ?: layout
    }

    private fun buildLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        alignment: Layout.Alignment,
        size: Float,
        style: TextStyle,
    ): StaticLayout {
        paint.textSize = size
        val (extraSpacing, multiplier) = lineSpacing(style, paint, size)
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(alignment)
            .setLineSpacing(extraSpacing, multiplier)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    /**
     * Keeps the measured source baseline pitch instead of trusting the chosen
     * font's default leading. The pitch scales with the rendered optical size,
     * so a shrunken translation keeps the original paragraph rhythm.
     */
    private fun lineSpacing(
        style: TextStyle,
        paint: TextPaint,
        size: Float,
    ): Pair<Float, Float> {
        val multiplier = style.lineSpacingMultiplier.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING)
        val sourcePitch = style.linePitchPx
            ?.takeIf { it.isFinite() && it > 1f }
            ?: return 0f to multiplier
        val targetPitch = sourcePitch * (size / style.sizePx.coerceAtLeast(1f))
        val metrics = paint.fontMetrics
        val natural = (metrics.descent - metrics.ascent + metrics.leading) * multiplier
        val extra = (targetPitch - natural).coerceIn(
            -targetPitch * MAX_LINE_SPACING_REDUCTION,
            targetPitch * MAX_LINE_SPACING_ADDITION,
        )
        return extra to multiplier
    }

    private fun fits(layout: StaticLayout, maxWidth: Int, maxHeight: Int): Boolean =
        layout.height <= maxHeight + 1 && maxLineWidth(layout) <= maxWidth + 1f

    private fun inkVerticalShift(
        layout: StaticLayout,
        paint: TextPaint,
        text: CharSequence,
    ): Float {
        if (layout.lineCount == 0) return 0f
        var inkTop = Float.MAX_VALUE
        var inkBottom = -Float.MAX_VALUE
        val rect = Rect()
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line)
            if (start >= end) continue
            paint.getTextBounds(text, start, end, rect)
            val baseline = layout.getLineBaseline(line).toFloat()
            inkTop = min(inkTop, baseline + rect.top)
            inkBottom = max(inkBottom, baseline + rect.bottom)
        }
        if (inkTop > inkBottom) return 0f
        return layout.height / 2f - (inkTop + inkBottom) / 2f
    }

    private fun maxLineWidth(layout: StaticLayout): Float {
        var widest = 0f
        for (line in 0 until layout.lineCount) {
            val width = layout.getLineWidth(line)
            if (width > widest) widest = width
        }
        return widest
    }

    /**
     * Converts the measured cap height into an optical size for the chosen
     * typeface. Families differ in cap-height/em ratio, so reusing the
     * analyser's 0.72 constant would make the translation slightly too large
     * or too small depending on the matched font.
     */
    private fun calibratedSize(style: TextStyle, paint: TextPaint): Float {
        val capHeight = style.capHeightPx?.takeIf { it.isFinite() && it > 1f }
            ?: return style.sizePx
        paint.textSize = CAP_HEIGHT_PROBE_SIZE
        val probe = Rect()
        paint.getTextBounds(CAP_HEIGHT_PROBE, 0, CAP_HEIGHT_PROBE.length, probe)
        val ratio = probe.height() / CAP_HEIGHT_PROBE_SIZE
        if (ratio < MIN_CAP_HEIGHT_RATIO || ratio > MAX_CAP_HEIGHT_RATIO) {
            return style.sizePx
        }
        return (capHeight / ratio).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
    }

    private fun buildPaint(style: TextStyle): TextPaint {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        paint.color = style.color
        paint.typeface = typefaceFor(style)
        paint.textSize = style.sizePx
        val skew = tan(Math.toRadians(style.slantDegrees.toDouble())).toFloat()
        paint.textSkewX = skew.coerceIn(-MAX_SKEW, MAX_SKEW)
        paint.letterSpacing = (style.letterSpacingPx / style.sizePx.coerceAtLeast(1f))
            .coerceIn(-0.08f, 0.65f)
        paint.isUnderlineText = style.isUnderlined
        paint.isStrikeThruText = style.isStruckThrough
        paint.textScaleX = style.horizontalScale
            .takeIf { it.isFinite() }
            ?.coerceIn(MIN_HORIZONTAL_SCALE, MAX_HORIZONTAL_SCALE) ?: 1f
        val syntheticStroke = syntheticStroke(style)
        if (syntheticStroke > 0f) {
            paint.style = Paint.Style.FILL_AND_STROKE
            paint.strokeWidth = syntheticStroke
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
        }
        return paint
    }

    /**
     * Compares the physical stroke thickness measured on the source glyphs with
     * the thickness the chosen font weight already provides. The missing
     * difference is added as an outline, so a bold source stays bold even when
     * the best-shaped font on the device is a regular one.
     */
    private fun syntheticStroke(style: TextStyle): Float {
        val capHeight = style.capHeightPx
            ?.takeIf { it.isFinite() && it > 1f }
            ?: return 0f
        val sourceRatio = (style.strokeWidthPx / capHeight)
            .takeIf { it.isFinite() }
            ?: return 0f
        // A matched bold face already carries the weight. Only a clearly
        // thicker source justifies an outline there; otherwise the glyphs gain
        // a second boldness on top of the real one.
        val trueBoldFace = style.fontWeight >= 600 &&
            style.fontMatchScore >= MIN_FONT_MATCH_CONFIDENCE
        val delta = sourceRatio - expectedStrokeRatio(style.fontWeight)
        val tolerance = if (trueBoldFace) BOLD_FACE_STROKE_TOLERANCE else MIN_STROKE_DELTA
        if (delta <= tolerance) return 0f
        val maxRatio = if (trueBoldFace) MAX_BOLD_FACE_STROKE_RATIO else MAX_SYNTHETIC_STROKE_RATIO
        val maximum = (capHeight * maxRatio).coerceAtLeast(MIN_SYNTHETIC_STROKE)
        return (delta * capHeight).coerceIn(MIN_SYNTHETIC_STROKE, maximum)
    }

    /** Typical stem width as a share of cap height for Roboto-style families. */
    private fun expectedStrokeRatio(fontWeight: Int): Float = when {
        fontWeight >= 900 -> 0.175f
        fontWeight >= 800 -> 0.155f
        fontWeight >= 700 -> 0.132f
        fontWeight >= 600 -> 0.112f
        fontWeight >= 500 -> 0.092f
        fontWeight >= 400 -> 0.074f
        else -> 0.055f
    }

    private fun typefaceFor(style: TextStyle): Typeface {
        FontCatalog.typeface(style.fontKey)?.let { return it }
        val family = when (style.fontFamily.lowercase()) {
            "monospace" -> "monospace"
            "serif" -> "serif"
            else -> "sans-serif"
        }
        val base = Typeface.create(family, Typeface.NORMAL)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, style.fontWeight.coerceIn(100, 900), false)
        } else if (style.isBold || style.fontWeight >= 600) {
            Typeface.create(base, Typeface.BOLD)
        } else {
            base
        }
    }

    private fun layoutAlignment(alignment: String): Layout.Alignment = when (alignment) {
        "center" -> Layout.Alignment.ALIGN_CENTER
        "right" -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_NORMAL
    }

    /**
     * Colour and size are interpolated between source glyphs instead of being
     * quantised into a palette: a sign that darkens towards its right end keeps
     * that exact drift, and a rainbow word stays rainbow.
     */
    private fun buildSpannable(
        text: String,
        samples: List<TextStyleSample>,
        fallback: Int,
        transferScale: Boolean,
    ): CharSequence {
        if (text.isEmpty() || samples.isEmpty()) return text
        val builder = SpannableStringBuilder(text)
        val accent = accentPrefixColor(samples, fallback)
        if (accent != null) {
            val end = firstTokenEnd(text)
            if (end > 0) {
                builder.setSpan(
                    ForegroundColorSpan(accent),
                    0,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                return builder
            }
        }
        if (samples.size == 1) {
            val color = samples.first().color
            if (color != null && color != fallback) {
                builder.setSpan(
                    ForegroundColorSpan(color),
                    0,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        } else {
            var segmentStart = 0
            var segmentColor = StyleGradient.colorAt(samples, 0f, fallback)
            for (index in 1..text.length) {
                val color = if (index < text.length) {
                    StyleGradient.colorAt(samples, positionOf(index, text.length), fallback)
                } else {
                    Int.MIN_VALUE
                }
                if (color != segmentColor) {
                    builder.setSpan(
                        ForegroundColorSpan(segmentColor),
                        segmentStart,
                        index,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    segmentStart = index
                    segmentColor = color
                }
            }
        }
        if (transferScale) {
            for (index in text.indices) {
                val scale = StyleGradient.scaleAt(samples, positionOf(index, text.length))
                if (abs(scale - 1f) >= MIN_RENDERED_SCALE_STEP) {
                    builder.setSpan(
                        RelativeSizeSpan(scale),
                        index,
                        index + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }
        return builder
    }

    /**
     * A leading coloured word ("ВАЖНО:") marks the paragraph accent and the
     * translation starts with its equivalent. Mapping that colour by raw
     * character position would tint only a few letters of a longer paragraph,
     * so the whole first word inherits it instead.
     */
    private fun accentPrefixColor(samples: List<TextStyleSample>, fallback: Int): Int? {
        if (samples.size < MIN_ACCENT_SAMPLES) return null
        // A smoothly drifting line is a designed gradient, not an accent word;
        // collapsing its first word to one colour would destroy the effect.
        if (StyleGradient.looksLikeGradient(samples)) return null
        val first = samples.first().color ?: return null
        if (colorDisparity(first, fallback) < MIN_ACCENT_DISPARITY) return null
        val limit = (samples.size * MAX_ACCENT_PREFIX_SHARE).toInt().coerceAtLeast(1)
        var lastAccent = 0
        for (index in 1..limit.coerceAtMost(samples.lastIndex)) {
            val color = samples[index].color ?: break
            if (colorDisparity(color, first) > MAX_ACCENT_RUN_DISPARITY) break
            lastAccent = index
        }
        if (lastAccent < 1 || lastAccent >= limit) return null
        val rest = samples.drop(lastAccent + 1).mapNotNull { it.color }
        if (rest.isEmpty()) return null
        val bodyMatches = rest.count { colorDisparity(it, fallback) <= MIN_ACCENT_DISPARITY }
        if (bodyMatches.toFloat() / rest.size < MIN_ACCENT_BODY_SHARE) return null
        return first
    }

    private fun firstTokenEnd(text: String): Int {
        val end = text.indexOfFirst { it.isWhitespace() }
        return if (end < 0) text.length else end
    }

    private fun colorDisparity(first: Int, second: Int): Int {
        val alpha = abs(((first ushr 24) and 0xFF) - ((second ushr 24) and 0xFF))
        val red = abs(((first shr 16) and 0xFF) - ((second shr 16) and 0xFF))
        val green = abs(((first shr 8) and 0xFF) - ((second shr 8) and 0xFF))
        val blue = abs((first and 0xFF) - (second and 0xFF))
        return alpha + red + green + blue
    }

    private fun positionOf(index: Int, length: Int): Float =
        if (length <= 1) 0f else index.toFloat() / (length - 1)

    private fun containsKana(text: String): Boolean = text.codePoints().anyMatch { codePoint ->
        codePoint in 0x3040..0x30FF || codePoint in 0x31F0..0x31FF
    }

    private fun List<Float>.averageOrNull(): Float? =
        takeIf { it.isNotEmpty() }?.average()?.toFloat()

    private companion object {
        const val MIN_TEXT_SIZE = 5f
        const val MAX_TEXT_SIZE = 600f
        const val MIN_LINE_SPACING = 0.8f
        const val MAX_LINE_SPACING = 2.6f
        const val MIN_HORIZONTAL_SCALE = 0.5f
        const val MAX_HORIZONTAL_SCALE = 1.35f
        const val MAX_SKEW = 0.6f
        const val MIN_STROKE_DELTA = 0.012f
        const val MIN_SYNTHETIC_STROKE = 0.35f
        const val MAX_SYNTHETIC_STROKE_RATIO = 0.22f
        const val MIN_FONT_MATCH_CONFIDENCE = 0.35f
        const val BOLD_FACE_STROKE_TOLERANCE = 0.035f
        const val MAX_BOLD_FACE_STROKE_RATIO = 0.05f
        const val MIN_ACCENT_SAMPLES = 8
        const val MAX_ACCENT_PREFIX_SHARE = 0.2f
        const val MIN_ACCENT_DISPARITY = 140
        const val MAX_ACCENT_RUN_DISPARITY = 90
        const val MIN_ACCENT_BODY_SHARE = 0.6f
        const val MIN_ROTATION_DEGREES = 0.75f
        const val FIT_ITERATIONS = 10
        const val MIN_CURVE_SPAN_DEGREES = 8f
        const val CURVE_FILL_RATIO = 0.985f
        const val MIN_CURVE_SQUEEZE = 0.55f
        const val MIN_CURVE_TEXT_SCALE = 0.4f
        const val SINGLE_LINE_WIDTH_SLACK = 1.035f
        const val UNWRAPPED_PROBE_WIDTH = 200_000
        const val MAX_SIZE_TO_LINE_HEIGHT = 1.8f
        const val FALLBACK_BASELINE_RATIO = 0.82f
        const val DESCENDER_DEPTH_RATIO = 0.17f
        const val MAX_EXTRA_TRACKING_EM = 0.18f
        const val MAX_TOTAL_TRACKING_EM = 0.4f
        const val MAX_TRACKING_COMPRESSION_EM = 0.05f
        const val MIN_NEGATIVE_TRACKING_EM = -0.05f
        const val MIN_TRACKING_INCREMENT = 0.01f
        const val MAX_WIDTH_STRETCH_SCALE = 1.08f
        const val MIN_WIDTH_CONDENSE_SCALE = 0.86f
        const val MIN_FIT_SIZE_SCALE = 0.82f
        const val MIN_LINE_COUNT_SIZE_SCALE = 0.86f
        const val MAX_NEIGHBOUR_GROWTH_RATIO = 0.45f
        const val NEIGHBOUR_GAP_EM = 0.08f
        const val MAX_LINE_SPACING_REDUCTION = 0.35f
        const val MAX_LINE_SPACING_ADDITION = 0.8f
        const val CAP_HEIGHT_PROBE = "H"
        const val CAP_HEIGHT_PROBE_SIZE = 128f
        const val MIN_CAP_HEIGHT_RATIO = 0.45f
        const val MAX_CAP_HEIGHT_RATIO = 0.95f
        const val MIN_RENDERED_SCALE_STEP = 0.015f

        private val DESCENDER_CHARS = setOf(
            'g', 'j', 'p', 'q', 'y',
            'р', 'у', 'ф', 'ц', 'щ', 'д',
        )
    }
}
