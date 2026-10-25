package com.lenslate.app.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.Point2D
import com.lenslate.app.model.TextRegionRecord
import java.util.Random
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Removes the original text while keeping the surrounding background intact.
 *
 * The erase mask is not a word rectangle: OCR boxes are only used as an allowed
 * area, then every pixel inside is classified as ink or background by comparing
 * it with the region's detected ink and background colours. Thin glyph-shaped
 * holes preserve counters, letter gaps and background structure.
 *
 * The low-frequency fill is a directional ray interpolation. On top of it a
 * PatchMatch pass copies real high-frequency texture from nearby untexted
 * background, so noisy/gradient backgrounds are not turned into a soapy patch.
 */
@Singleton
class BackgroundRestorer @Inject constructor() {

    fun restore(
        source: Bitmap,
        regions: List<TextRegionRecord>,
        paddingPx: Int = DEFAULT_PADDING_PX,
    ): Bitmap {
        if (regions.isEmpty() || source.width <= 2 || source.height <= 2) {
            return source.copy(Bitmap.Config.ARGB_8888, true)
        }
        val width = source.width
        val height = source.height
        val mask = buildMask(source, regions, paddingPx.coerceIn(0, 12))
        val maskCount = mask.count { it }
        if (maskCount == 0) return source.copy(Bitmap.Config.ARGB_8888, true)

        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)
        val original = pixels.copyOf()
        val fallback = medianBackground(original, mask)
        fillMaskedPixels(original, pixels, mask, width, height, fallback)
        smoothBaseFill(pixels, mask, width, height)
        addSourceTexture(original, pixels, mask, width, height)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    // ── mask ──────────────────────────────────────────────────────

    private fun buildMask(
        source: Bitmap,
        regions: List<TextRegionRecord>,
        padding: Int,
    ): BooleanArray {
        val width = source.width
        val height = source.height
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(maskBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL_AND_STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        regions.forEach { region ->
            val stroke = region.appearance?.strokeWidthPx
                ?.takeIf { it.isFinite() && it > 0f }
                ?.coerceIn(0f, 12f)
                ?: 0f
            paint.strokeWidth = (padding * 2f + stroke).coerceAtLeast(1f)

            val symbols = region.lines.flatMap { it.elements }.flatMap { it.symbols }
            val elements = region.lines.flatMap { it.elements }
            val lines = region.lines
            when {
                symbols.isNotEmpty() -> symbols.forEach { drawUnit(canvas, paint, it.polygon, it.bounds) }
                elements.isNotEmpty() -> elements.forEach { drawUnit(canvas, paint, it.polygon, it.bounds) }
                lines.isNotEmpty() -> lines.forEach { drawUnit(canvas, paint, it.polygon, it.bounds) }
                else -> drawUnit(canvas, paint, region.polygon, region.bounds)
            }
            // Tesseract boxes can stop short of the printed line, while the
            // line box now covers the full ink. Upright line boxes are the
            // printed strip itself, so allowing them lets the classifier reach
            // glyphs the symbol boxes never covered.
            if (lines.isNotEmpty()) {
                lines.forEach { line ->
                    val angle = line.angleDegrees ?: 0f
                    if (angle.isFinite() && abs(angle) < MAX_UPRIGHT_LINE_DEGREES) {
                        drawUnit(canvas, paint, line.polygon, line.bounds)
                    }
                }
            }
        }

        val pixels = IntArray(width * height)
        maskBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        maskBitmap.recycle()
        val allowed = BooleanArray(pixels.size) { index -> (pixels[index] ushr 24) > 8 }
        if (allowed.none { it }) return allowed

        val result = BooleanArray(pixels.size)
        var producedInk = false
        regions.forEach { region ->
            if (buildRegionMask(source, region, allowed, result, width, height)) {
                producedInk = true
            }
        }
        if (!producedInk) {
            return dilate(allowed, width, height, MASK_DILATION)
        }
        return result
    }

    /**
     * Classifies one region's ink and dilates the mask by the width of the
     * region's own ink halo, so blurred glyphs are erased together with their
     * soft edge instead of leaving a coloured silhouette behind.
     */
    private fun buildRegionMask(
        source: Bitmap,
        region: TextRegionRecord,
        allowed: BooleanArray,
        result: BooleanArray,
        width: Int,
        height: Int,
    ): Boolean {
        val rect = Rect(
            region.bounds.left.toInt().coerceIn(0, width - 1),
            region.bounds.top.toInt().coerceIn(0, height - 1),
            region.bounds.right.toInt().coerceIn(1, width),
            region.bounds.bottom.toInt().coerceIn(1, height),
        )
        if (rect.width() < 2 || rect.height() < 2) return false

        val cropLeft = (rect.left - TextEdgeProfile.MAX_DILATION).coerceAtLeast(0)
        val cropTop = (rect.top - TextEdgeProfile.MAX_DILATION).coerceAtLeast(0)
        val cropRight = (rect.right + TextEdgeProfile.MAX_DILATION).coerceAtMost(width)
        val cropBottom = (rect.bottom + TextEdgeProfile.MAX_DILATION).coerceAtMost(height)
        val cropWidth = cropRight - cropLeft
        val cropHeight = cropBottom - cropTop
        if (cropWidth < 2 || cropHeight < 2) return false

        val crop = IntArray(cropWidth * cropHeight)
        try {
            source.getPixels(crop, 0, cropWidth, cropLeft, cropTop, cropWidth, cropHeight)
        } catch (_: IllegalArgumentException) {
            return false
        }

        val background = region.appearance?.localBackgroundArgb
            ?.takeIf { Color.alpha(it) >= MIN_REFERENCE_ALPHA }
            ?: estimateRegionBackground(source, rect)
        val references = inkReferences(region, background)
        val maxContrast = references.maxOfOrNull { colorDistanceSq(it, background) } ?: 0f

        val localAllowed = BooleanArray(crop.size)
        val distances = FloatArray(crop.size)
        var allowedCount = 0
        for (y in 0 until cropHeight) {
            val cropRow = y * cropWidth
            val globalRow = (cropTop + y) * width
            for (x in 0 until cropWidth) {
                val cropIndex = cropRow + x
                val globalIndex = globalRow + cropLeft + x
                val isAllowed = allowed[globalIndex]
                localAllowed[cropIndex] = isAllowed
                val distance = colorDistanceSq(crop[cropIndex], background)
                distances[cropIndex] = distance
                if (isAllowed) allowedCount++
            }
        }

        val edgeWidth = TextEdgeProfile.medianEdgeWidth(
            distances, localAllowed, cropWidth, cropHeight, maxContrast,
        )
        val dilationRadius = TextEdgeProfile.dilationRadius(edgeWidth)

        val mask = BooleanArray(crop.size)
        var inkCount = 0
        if (references.isNotEmpty() && maxContrast < FULL_BLOCK_ERASE_CONTRAST_SQ) {
            // The ink is only a few levels away from the texture, so colour
            // separation latches onto background noise and leaves the glyph
            // edges behind. Erase the whole block instead and let texture
            // synthesis rebuild it, so no original letters stay under the
            // translation.
            val left = (rect.left - cropLeft - dilationRadius).coerceAtLeast(0)
            val top = (rect.top - cropTop - dilationRadius).coerceAtLeast(0)
            val right = (rect.right - cropLeft + dilationRadius).coerceAtMost(cropWidth)
            val bottom = (rect.bottom - cropTop + dilationRadius).coerceAtMost(cropHeight)
            if (right <= left || bottom <= top) return false
            for (y in top until bottom) {
                val row = y * cropWidth
                val globalRow = (cropTop + y) * width
                for (x in left until right) {
                    mask[row + x] = true
                    result[globalRow + cropLeft + x] = true
                    inkCount++
                }
            }
            logErase(region, references, maxContrast, background, inkCount, allowedCount, "whole")
            return inkCount > 0
        }
        if (references.isEmpty() || maxContrast < MIN_CLASSIFIABLE_CONTRAST_SQ) {
            copyAllowed(localAllowed, mask)
            inkCount = allowedCount
        } else {
            val thresholdSq = (maxContrast * EDGE_INK_SHARE)
                .coerceIn(MIN_EDGE_THRESHOLD_SQ, MAX_EDGE_THRESHOLD_SQ)
            val strongSq = (maxContrast * STRONG_INK_SHARE)
                .coerceIn(MIN_STRONG_THRESHOLD_SQ, MAX_STRONG_THRESHOLD_SQ)
            for (index in crop.indices) {
                if (!localAllowed[index]) continue
                val backgroundDistance = distances[index]
                if (backgroundDistance < thresholdSq) continue
                var inkDistance = Float.MAX_VALUE
                references.forEach { reference ->
                    val distance = colorDistanceSq(crop[index], reference)
                    if (distance < inkDistance) inkDistance = distance
                }
                if (inkDistance <= backgroundDistance * INK_VS_BACKGROUND_RATIO ||
                    backgroundDistance >= strongSq
                ) {
                    mask[index] = true
                    inkCount++
                }
            }
            if (allowedCount > MIN_FALLBACK_AREA && inkCount < allowedCount * MIN_REGION_INK_SHARE) {
                // Colour separation failed (very low contrast, rainbow text, an
                // unusual background): erase the whole allowed area rather than
                // leave original glyphs visible. Texture synthesis fills it later.
                copyAllowed(localAllowed, mask)
                inkCount = allowedCount
            }
        }
        if (inkCount == 0) return false

        logErase(region, references, maxContrast, background, inkCount, allowedCount, "classify")

        val dilated = dilate(mask, cropWidth, cropHeight, dilationRadius)
        for (y in 0 until cropHeight) {
            val cropRow = y * cropWidth
            val globalRow = (cropTop + y) * width
            for (x in 0 until cropWidth) {
                if (dilated[cropRow + x]) result[globalRow + cropLeft + x] = true
            }
        }
        return true
    }

    private fun logErase(
        region: TextRegionRecord,
        references: List<Int>,
        maxContrast: Float,
        background: Int,
        inkCount: Int,
        allowedCount: Int,
        mode: String,
    ) {
        if (!DEBUG_ERASE) return
        android.util.Log.d(
            "LensLateErase",
            "«${region.text.take(18)}» mode=$mode refs=${references.size} contrast=$maxContrast " +
                "bg=${Integer.toHexString(background)} ink=$inkCount/$allowedCount",
        )
    }

    private fun copyAllowed(allowed: BooleanArray, output: BooleanArray) {
        for (index in allowed.indices) {
            if (allowed[index]) output[index] = true
        }
    }

    private fun inkReferences(region: TextRegionRecord, background: Int): List<Int> {
        val unique = LinkedHashSet<Int>()
        val appearance = region.appearance
        appearance?.foregroundArgb?.let(unique::add)
        appearance?.alternativeColors?.forEach(unique::add)
        region.characterColors.forEach(unique::add)
        return unique
            .asSequence()
            .filter { Color.alpha(it) >= MIN_REFERENCE_ALPHA }
            .filter { colorDistanceSq(it, background) >= MIN_INK_REFERENCE_DISTANCE_SQ }
            .take(MAX_INK_REFERENCES)
            .toList()
    }

    private fun estimateRegionBackground(source: Bitmap, rect: Rect): Int {
        val padding = max(2, min(rect.width(), rect.height()) / 8)
        val sample = Rect(
            (rect.left - padding).coerceIn(0, source.width - 1),
            (rect.top - padding).coerceIn(0, source.height - 1),
            (rect.right + padding).coerceIn(1, source.width),
            (rect.bottom + padding).coerceIn(1, source.height),
        )
        val width = sample.width()
        val height = sample.height()
        if (width < 3 || height < 3) return Color.WHITE
        val colors = ArrayList<Int>(width * 2 + height * 2)
        val row = IntArray(width)
        val column = IntArray(height)
        try {
            source.getPixels(row, 0, width, sample.left, sample.top, width, 1)
            colors.addAll(row.toList())
            source.getPixels(row, 0, width, sample.left, sample.bottom - 1, width, 1)
            colors.addAll(row.toList())
            source.getPixels(column, 0, 1, sample.left, sample.top, 1, height)
            colors.addAll(column.toList())
            source.getPixels(column, 0, 1, sample.right - 1, sample.top, 1, height)
            colors.addAll(column.toList())
        } catch (_: IllegalArgumentException) {
            return Color.WHITE
        }
        if (colors.isEmpty()) return Color.WHITE
        val reds = IntArray(colors.size) { Color.red(colors[it]) }.apply { sort() }
        val greens = IntArray(colors.size) { Color.green(colors[it]) }.apply { sort() }
        val blues = IntArray(colors.size) { Color.blue(colors[it]) }.apply { sort() }
        val middle = colors.size / 2
        return Color.rgb(reds[middle], greens[middle], blues[middle])
    }

    private fun drawUnit(
        canvas: Canvas,
        paint: Paint,
        polygon: List<Point2D>,
        bounds: Bounds2D?,
    ) {
        if (polygon.size >= 3 && polygon.all { it.x.isFinite() && it.y.isFinite() }) {
            val path = Path().apply {
                moveTo(polygon.first().x, polygon.first().y)
                for (index in 1 until polygon.size) lineTo(polygon[index].x, polygon[index].y)
                close()
            }
            canvas.drawPath(path, paint)
            return
        }
        if (bounds != null && bounds.width > 0f && bounds.height > 0f) {
            canvas.drawRect(
                RectF(bounds.left, bounds.top, bounds.right, bounds.bottom),
                paint,
            )
        }
    }

    private fun dilate(mask: BooleanArray, width: Int, height: Int, extraRadius: Int): BooleanArray {
        if (extraRadius <= 0) return mask
        var current = mask
        repeat(extraRadius) {
            val next = current.copyOf()
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    val index = row + x
                    if (current[index]) continue
                    if (
                        (x > 0 && current[index - 1]) ||
                        (x < width - 1 && current[index + 1]) ||
                        (y > 0 && current[index - width]) ||
                        (y < height - 1 && current[index + width])
                    ) {
                        next[index] = true
                    }
                }
            }
            current = next
        }
        return current
    }

    // ── low-frequency fill ───────────────────────────────────────

    private fun fillMaskedPixels(
        original: IntArray,
        output: IntArray,
        mask: BooleanArray,
        width: Int,
        height: Int,
        fallback: Int,
    ) {
        val maxRay = max(32, min(200, max(width, height) / 6))
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                if (!mask[index]) continue

                var red = 0.0
                var green = 0.0
                var blue = 0.0
                var alpha = 0.0
                var totalWeight = 0.0

                for (axis in AXES) {
                    val forward = trace(original, mask, width, height, x, y, axis[0], axis[1], maxRay)
                    val backward = trace(original, mask, width, height, x, y, -axis[0], -axis[1], maxRay)
                    val axisWeight = if (axis[0] != 0 && axis[1] != 0) DIAGONAL_WEIGHT else ORTHOGONAL_WEIGHT
                    val paired = forward != null && backward != null
                    if (forward != null) {
                        val weight = axisWeight * (if (paired) PAIRED_BOOST else 1.0) /
                            (1.0 + forward.distance * forward.distance * DISTANCE_FALLOFF)
                        red += Color.red(forward.color) * weight
                        green += Color.green(forward.color) * weight
                        blue += Color.blue(forward.color) * weight
                        alpha += Color.alpha(forward.color) * weight
                        totalWeight += weight
                    }
                    if (backward != null) {
                        val weight = axisWeight * (if (paired) PAIRED_BOOST else 1.0) /
                            (1.0 + backward.distance * backward.distance * DISTANCE_FALLOFF)
                        red += Color.red(backward.color) * weight
                        green += Color.green(backward.color) * weight
                        blue += Color.blue(backward.color) * weight
                        alpha += Color.alpha(backward.color) * weight
                        totalWeight += weight
                    }
                }

                output[index] = if (totalWeight > 0.0) {
                    val inverse = 1.0 / totalWeight
                    Color.argb(
                        (alpha * inverse).toInt().coerceIn(0, 255),
                        (red * inverse).toInt().coerceIn(0, 255),
                        (green * inverse).toInt().coerceIn(0, 255),
                        (blue * inverse).toInt().coerceIn(0, 255),
                    )
                } else {
                    fallback
                }
            }
        }
    }

    private fun trace(
        pixels: IntArray,
        mask: BooleanArray,
        width: Int,
        height: Int,
        startX: Int,
        startY: Int,
        dx: Int,
        dy: Int,
        maxRay: Int,
    ): RaySample? {
        var x = startX + dx
        var y = startY + dy
        var distance = if (dx != 0 && dy != 0) DIAGONAL_STEP else 1f
        while (x in 0 until width && y in 0 until height && distance <= maxRay) {
            val index = y * width + x
            if (!mask[index]) return RaySample(pixels[index], distance)
            x += dx
            y += dy
            distance += if (dx != 0 && dy != 0) DIAGONAL_STEP else 1f
        }
        return null
    }

    /** Smooths only the low-frequency base; texture is added back afterwards. */
    private fun smoothBaseFill(pixels: IntArray, mask: BooleanArray, width: Int, height: Int) {
        val copy = pixels.copyOf()
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                if (!mask[index]) continue
                var red = 0.0
                var green = 0.0
                var blue = 0.0
                var alpha = 0.0
                var weightSum = 0.0
                for (offsetY in -1..1) {
                    val sampleY = y + offsetY
                    if (sampleY !in 0 until height) continue
                    for (offsetX in -1..1) {
                        val sampleX = x + offsetX
                        if (sampleX !in 0 until width) continue
                        val weight = when {
                            offsetX == 0 && offsetY == 0 -> 4.0
                            offsetX == 0 || offsetY == 0 -> 2.0
                            else -> 1.0
                        }
                        val color = copy[sampleY * width + sampleX]
                        red += Color.red(color) * weight
                        green += Color.green(color) * weight
                        blue += Color.blue(color) * weight
                        alpha += Color.alpha(color) * weight
                        weightSum += weight
                    }
                }
                if (weightSum > 0.0) {
                    val inverse = 1.0 / weightSum
                    pixels[index] = Color.argb(
                        (alpha * inverse).toInt().coerceIn(0, 255),
                        (red * inverse).toInt().coerceIn(0, 255),
                        (green * inverse).toInt().coerceIn(0, 255),
                        (blue * inverse).toInt().coerceIn(0, 255),
                    )
                }
            }
        }
    }

    // ── texture synthesis ────────────────────────────────────────

    private fun addSourceTexture(
        source: IntArray,
        output: IntArray,
        mask: BooleanArray,
        width: Int,
        height: Int,
    ) {
        val maskCount = mask.count { it }
        if (maskCount < MIN_TEXTURE_PIXELS) return
        if (maskCount > MAX_PATCHMATCH_PIXELS) {
            applyRandomTexture(source, output, mask, width, height)
            return
        }

        val indexAt = IntArray(width * height) { -1 }
        var nextIndex = 0
        for (index in mask.indices) {
            if (mask[index]) indexAt[index] = nextIndex++
        }
        val offsets = IntArray(maskCount) { NO_OFFSET }
        val costs = FloatArray(maskCount) { Float.MAX_VALUE }
        val maskIntegral = buildMaskIntegral(mask, width, height)
        val stride = width + 1
        val searchRadius = (min(width, height) / 16).coerceIn(MIN_SEARCH_RADIUS, MAX_SEARCH_RADIUS)
        val random = Random(PATCHMATCH_SEED)

        // Initial random guesses; every candidate is checked against the real
        // (unmasked) pixels of this pixel's patch.
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val pixelIndex = row + x
                if (!mask[pixelIndex]) continue
                val slot = indexAt[pixelIndex]
                var bestOffset = NO_OFFSET
                var bestCost = Float.MAX_VALUE
                var attempt = 0
                while (attempt < INITIAL_ATTEMPTS) {
                    val dx = random.nextInt(searchRadius * 2 + 1) - searchRadius
                    val dy = random.nextInt(searchRadius * 2 + 1) - searchRadius
                    val qx = x + dx
                    val qy = y + dy
                    if (isPatchValid(maskIntegral, stride, width, height, qx, qy, PATCH_RADIUS)) {
                        val distance = patchDistance(
                            source, mask, width, height,
                            x, y, qx, qy, PATCH_RADIUS, bestCost,
                        )
                        if (distance < bestCost) {
                            bestCost = distance
                            bestOffset = encodeOffset(dx, dy)
                        }
                    }
                    attempt++
                }
                offsets[slot] = bestOffset
                costs[slot] = bestCost
            }
        }

        // Propagation + shrinking random search, alternating scan directions so
        // texture flows from both sides of every glyph hole.
        repeat(PATCH_ITERATIONS) { iteration ->
            val reverse = iteration % 2 == 1
            if (reverse) {
                for (y in height - 1 downTo 0) {
                    for (x in width - 1 downTo 0) {
                        propagateAndSearch(
                            x, y, true, source, mask, indexAt, offsets, costs,
                            maskIntegral, stride, width, height, searchRadius, random,
                        )
                    }
                }
            } else {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        propagateAndSearch(
                            x, y, false, source, mask, indexAt, offsets, costs,
                            maskIntegral, stride, width, height, searchRadius, random,
                        )
                    }
                }
            }
        }

        applyPatchTexture(source, output, mask, indexAt, offsets, width, height)
    }

    private fun propagateAndSearch(
        x: Int,
        y: Int,
        reverse: Boolean,
        source: IntArray,
        mask: BooleanArray,
        indexAt: IntArray,
        offsets: IntArray,
        costs: FloatArray,
        maskIntegral: IntArray,
        stride: Int,
        width: Int,
        height: Int,
        searchRadius: Int,
        random: Random,
    ) {
        val pixelIndex = y * width + x
        if (!mask[pixelIndex]) return
        val slot = indexAt[pixelIndex]
        var bestOffset = offsets[slot]
        var bestCost = costs[slot]

        val primaryX = if (reverse) 1 else -1
        val primaryY = if (reverse) 0 else -1
        val secondaryX = if (reverse) 0 else 1
        val secondaryY = if (reverse) 1 else 0
        for (candidate in 0..1) {
            val nx = x + if (candidate == 0) primaryX else secondaryX
            val ny = y + if (candidate == 0) primaryY else secondaryY
            if (nx !in 0 until width || ny !in 0 until height) continue
            val neighborSlot = indexAt[ny * width + nx]
            if (neighborSlot < 0) continue
            val neighborOffset = offsets[neighborSlot]
            if (neighborOffset == NO_OFFSET) continue
            val dx = decodeX(neighborOffset)
            val dy = decodeY(neighborOffset)
            val qx = x + dx
            val qy = y + dy
            if (!isPatchValid(maskIntegral, stride, width, height, qx, qy, PATCH_RADIUS)) continue
            val distance = patchDistance(
                source, mask, width, height,
                x, y, qx, qy, PATCH_RADIUS, bestCost,
            )
            if (distance < bestCost) {
                bestCost = distance
                bestOffset = neighborOffset
            }
        }

        var radius = searchRadius
        while (radius >= 1) {
            var attempt = 0
            while (attempt < RANDOM_ATTEMPTS) {
                val dx = random.nextInt(radius * 2 + 1) - radius
                val dy = random.nextInt(radius * 2 + 1) - radius
                val qx = x + dx
                val qy = y + dy
                if (isPatchValid(maskIntegral, stride, width, height, qx, qy, PATCH_RADIUS)) {
                    val distance = patchDistance(
                        source, mask, width, height,
                        x, y, qx, qy, PATCH_RADIUS, bestCost,
                    )
                    if (distance < bestCost) {
                        bestCost = distance
                        bestOffset = encodeOffset(dx, dy)
                    }
                }
                attempt++
            }
            radius /= 2
        }
        offsets[slot] = bestOffset
        costs[slot] = bestCost
    }

    private fun applyPatchTexture(
        source: IntArray,
        output: IntArray,
        mask: BooleanArray,
        indexAt: IntArray,
        offsets: IntArray,
        width: Int,
        height: Int,
    ) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val pixelIndex = row + x
                if (!mask[pixelIndex]) continue
                val slot = indexAt[pixelIndex]
                val offset = offsets[slot]
                if (offset == NO_OFFSET) continue
                val qx = x + decodeX(offset)
                val qy = y + decodeY(offset)
                if (qx !in 0 until width || qy !in 0 until height) continue
                val sourceIndex = qy * width + qx
                val mean = patchMean(source, width, height, qx, qy, MEAN_RADIUS)
                val base = output[pixelIndex]
                val sampled = source[sourceIndex]
                output[pixelIndex] = Color.argb(
                    Color.alpha(base),
                    (Color.red(base) + (Color.red(sampled) - Color.red(mean)).coerceIn(-MAX_TEXTURE_DELTA, MAX_TEXTURE_DELTA))
                        .coerceIn(0, 255),
                    (Color.green(base) + (Color.green(sampled) - Color.green(mean)).coerceIn(-MAX_TEXTURE_DELTA, MAX_TEXTURE_DELTA))
                        .coerceIn(0, 255),
                    (Color.blue(base) + (Color.blue(sampled) - Color.blue(mean)).coerceIn(-MAX_TEXTURE_DELTA, MAX_TEXTURE_DELTA))
                        .coerceIn(0, 255),
                )
            }
        }
    }

    /**
     * Fast but still texture-preserving path for extremely large masks where a
     * full PatchMatch would be wasteful.
     */
    private fun applyRandomTexture(
        source: IntArray,
        output: IntArray,
        mask: BooleanArray,
        width: Int,
        height: Int,
    ) {
        val random = Random(PATCHMATCH_SEED)
        val search = (min(width, height) / 32).coerceIn(MIN_SEARCH_RADIUS, MAX_SEARCH_RADIUS)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val pixelIndex = row + x
                if (!mask[pixelIndex]) continue
                var sampled = -1
                var qx = x
                var qy = y
                var attempt = 0
                while (attempt < FALLBACK_ATTEMPTS) {
                    val candidateX = x + random.nextInt(search * 2 + 1) - search
                    val candidateY = y + random.nextInt(search * 2 + 1) - search
                    if (candidateX !in 0 until width || candidateY !in 0 until height) {
                        attempt++
                        continue
                    }
                    val candidateIndex = candidateY * width + candidateX
                    if (!mask[candidateIndex]) {
                        sampled = candidateIndex
                        qx = candidateX
                        qy = candidateY
                        break
                    }
                    attempt++
                }
                if (sampled < 0) continue
                val mean = patchMean(source, width, height, qx, qy, MEAN_RADIUS)
                val base = output[pixelIndex]
                output[pixelIndex] = Color.argb(
                    Color.alpha(base),
                    (Color.red(base) + (Color.red(source[sampled]) - Color.red(mean)).coerceIn(-MAX_TEXTURE_DELTA, MAX_TEXTURE_DELTA))
                        .coerceIn(0, 255),
                    (Color.green(base) + (Color.green(source[sampled]) - Color.green(mean)).coerceIn(-MAX_TEXTURE_DELTA, MAX_TEXTURE_DELTA))
                        .coerceIn(0, 255),
                    (Color.blue(base) + (Color.blue(source[sampled]) - Color.blue(mean)).coerceIn(-MAX_TEXTURE_DELTA, MAX_TEXTURE_DELTA))
                        .coerceIn(0, 255),
                )
            }
        }
    }

    private fun buildMaskIntegral(mask: BooleanArray, width: Int, height: Int): IntArray {
        val stride = width + 1
        val integral = IntArray(stride * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0
            val row = y * width
            val currentBase = (y + 1) * stride
            val previousBase = y * stride
            for (x in 0 until width) {
                if (mask[row + x]) rowSum++
                integral[currentBase + x + 1] = integral[previousBase + x + 1] + rowSum
            }
        }
        return integral
    }

    private fun isPatchValid(
        integral: IntArray,
        stride: Int,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        radius: Int,
    ): Boolean {
        val left = centerX - radius
        val top = centerY - radius
        val right = centerX + radius + 1
        val bottom = centerY + radius + 1
        if (left < 0 || top < 0 || right > width || bottom > height) return false
        val sum = integral[bottom * stride + right] -
            integral[top * stride + right] -
            integral[bottom * stride + left] +
            integral[top * stride + left]
        return sum == 0
    }

    private fun patchDistance(
        source: IntArray,
        mask: BooleanArray,
        width: Int,
        height: Int,
        targetX: Int,
        targetY: Int,
        sourceX: Int,
        sourceY: Int,
        radius: Int,
        limit: Float,
    ): Float {
        var sum = 0f
        for (offsetY in -radius..radius) {
            val targetRow = targetY + offsetY
            if (targetRow !in 0 until height) continue
            val sourceRow = sourceY + offsetY
            val targetBase = targetRow * width
            val sourceBase = sourceRow * width
            for (offsetX in -radius..radius) {
                val targetColumn = targetX + offsetX
                if (targetColumn < 0 || targetColumn >= width) continue
                if (mask[targetBase + targetColumn]) continue
                val targetColor = source[targetBase + targetColumn]
                val sourceColor = source[sourceBase + sourceX + offsetX]
                val red = (Color.red(targetColor) - Color.red(sourceColor)) / 255f
                val green = (Color.green(targetColor) - Color.green(sourceColor)) / 255f
                val blue = (Color.blue(targetColor) - Color.blue(sourceColor)) / 255f
                sum += red * red + green * green + blue * blue
                if (sum >= limit) return sum
            }
        }
        return sum
    }

    private fun patchMean(
        source: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        radius: Int,
    ): Int {
        var red = 0
        var green = 0
        var blue = 0
        var count = 0
        for (y in (centerY - radius).coerceAtLeast(0)..(centerY + radius).coerceAtMost(height - 1)) {
            val row = y * width
            for (x in (centerX - radius).coerceAtLeast(0)..(centerX + radius).coerceAtMost(width - 1)) {
                val color = source[row + x]
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
                count++
            }
        }
        if (count == 0) return Color.BLACK
        return Color.rgb(red / count, green / count, blue / count)
    }

    private fun encodeOffset(dx: Int, dy: Int): Int =
        ((dx + OFFSET_BIAS) shl 16) or ((dy + OFFSET_BIAS) and 0xFFFF)

    private fun decodeX(offset: Int): Int = (offset shr 16) - OFFSET_BIAS

    private fun decodeY(offset: Int): Int = (offset and 0xFFFF) - OFFSET_BIAS

    // ── helpers ──────────────────────────────────────────────────

    private fun medianBackground(pixels: IntArray, mask: BooleanArray): Int {
        val stride = max(1, sqrt(pixels.size.toFloat() / MAX_BACKGROUND_SAMPLES).toInt())
        val samples = ArrayList<Int>(MAX_BACKGROUND_SAMPLES)
        var index = 0
        while (index < pixels.size) {
            if (!mask[index]) samples += pixels[index]
            index += stride
        }
        if (samples.isEmpty()) return Color.WHITE
        val reds = IntArray(samples.size) { Color.red(samples[it]) }.apply { sort() }
        val greens = IntArray(samples.size) { Color.green(samples[it]) }.apply { sort() }
        val blues = IntArray(samples.size) { Color.blue(samples[it]) }.apply { sort() }
        val middle = samples.size / 2
        return Color.rgb(reds[middle], greens[middle], blues[middle])
    }

    private fun colorDistanceSq(first: Int, second: Int): Float {
        val red = (Color.red(first) - Color.red(second)) / 255f
        val green = (Color.green(first) - Color.green(second)) / 255f
        val blue = (Color.blue(first) - Color.blue(second)) / 255f
        return (red * red + green * green + blue * blue) / 3f
    }

    private data class RaySample(val color: Int, val distance: Float)

    private companion object {
        const val DEBUG_ERASE = false
        const val DEFAULT_PADDING_PX = 2
        const val MASK_DILATION = 2
        const val MAX_BACKGROUND_SAMPLES = 40_000

        const val MIN_REFERENCE_ALPHA = 16
        const val MIN_INK_REFERENCE_DISTANCE_SQ = 0.0018f
        const val MAX_INK_REFERENCES = 16
        const val MIN_CLASSIFIABLE_CONTRAST_SQ = 0.0025f
        const val FULL_BLOCK_ERASE_CONTRAST_SQ = 0.05f
        const val EDGE_INK_SHARE = 0.14f
        const val STRONG_INK_SHARE = 0.55f
        const val MIN_EDGE_THRESHOLD_SQ = 0.0015f
        const val MAX_EDGE_THRESHOLD_SQ = 0.10f
        const val MIN_STRONG_THRESHOLD_SQ = 0.012f
        const val MAX_STRONG_THRESHOLD_SQ = 0.75f
        const val INK_VS_BACKGROUND_RATIO = 0.94f
        const val MIN_FALLBACK_AREA = 40
        const val MIN_REGION_INK_SHARE = 0.03f
        const val MAX_UPRIGHT_LINE_DEGREES = 2f

        const val DISTANCE_FALLOFF = 0.06
        const val PAIRED_BOOST = 1.7
        const val ORTHOGONAL_WEIGHT = 1.0
        const val DIAGONAL_WEIGHT = 0.35
        const val DIAGONAL_STEP = 1.4142f

        const val MIN_TEXTURE_PIXELS = 8
        const val MAX_PATCHMATCH_PIXELS = 350_000
        const val PATCH_RADIUS = 3
        const val MEAN_RADIUS = 2
        const val PATCH_ITERATIONS = 4
        const val INITIAL_ATTEMPTS = 10
        const val RANDOM_ATTEMPTS = 4
        const val FALLBACK_ATTEMPTS = 6
        const val MIN_SEARCH_RADIUS = 16
        const val MAX_SEARCH_RADIUS = 48
        const val MAX_TEXTURE_DELTA = 96
        const val OFFSET_BIAS = 32768
        const val NO_OFFSET = Int.MIN_VALUE
        const val PATCHMATCH_SEED = 0x5EED1234L

        val AXES = arrayOf(
            intArrayOf(1, 0),
            intArrayOf(0, 1),
            intArrayOf(1, 1),
            intArrayOf(1, -1),
        )
    }
}
