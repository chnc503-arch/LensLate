package com.lenslate.app.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.lenslate.app.model.AffineTransform2D
import com.lenslate.app.model.ImageQualityEstimate
import com.lenslate.app.model.Point2D
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class PreprocessOperation {
    ORIGINAL,
    CONTRAST_STRETCH,
    GRAYSCALE_STRETCH,
    GAMMA,
    ADAPTIVE_THRESHOLD,
    OTSU_THRESHOLD,
    UNSHARP_MASK,
    DENOISE,
    CLAHE,
    INVERT,
    SHARPEN_CONTRAST,
    TOPOHAT,
    DENOISE_SHARPEN,
    FLAT_FIELD,
    UPSCALE,
}

/** One step of a preprocessing recipe. Stages run in order on the previous result. */
internal data class PreprocessStage(
    val operation: PreprocessOperation,
    val gamma: Float = 1f,
    val threshold: Float = 0.5f,
    val sharpness: Float = 0f,
    val denoiseStrength: Float = 0f,
    val scale: Float = 1f,
)

internal data class OcrVariantSpec(
    val name: String,
    val stages: List<PreprocessStage>,
    val rotationDegrees: Float = 0f,
    val description: String = "",
) {
    constructor(
        name: String,
        operation: PreprocessOperation,
        gamma: Float = 1f,
        brightness: Float = 0f,
        threshold: Float = 0.5f,
        sharpness: Float = 0f,
        denoiseStrength: Float = 0f,
        rotationDegrees: Float = 0f,
        description: String = "",
    ) : this(
        name = name,
        stages = listOf(
            PreprocessStage(
                operation = operation,
                gamma = gamma,
                threshold = threshold,
                sharpness = sharpness,
                denoiseStrength = denoiseStrength,
            ),
        ),
        rotationDegrees = rotationDegrees,
        description = description,
    )

    /** The last substantive operation, used for grouping and compatibility. */
    val operation: PreprocessOperation
        get() = stages.lastOrNull { it.operation != PreprocessOperation.ORIGINAL }?.operation
            ?: PreprocessOperation.ORIGINAL

    val upscale: Float
        get() = stages.fold(1f) { result, stage ->
            if (stage.operation == PreprocessOperation.UPSCALE) result * stage.scale else result
        }
}

internal data class PreparedOcrInput(
    val bitmap: Bitmap,
    /** Maps coordinates in this input bitmap back into the working-image coordinates. */
    val inputToWorking: AffineTransform2D,
)

internal data class PreprocessingPlan(
    val quality: ImageQualityEstimate,
    val variants: List<OcrVariantSpec>,
    val lowPercentile: Int,
    val highPercentile: Int,
)

/** Creates a small, quality-driven set of temporary OCR inputs without changing the source bitmap. */
internal class OcrPreprocessor {

    fun plan(bitmap: Bitmap): PreprocessingPlan {
        val stats = analyzeImage(bitmap)
        val variants = mutableListOf<OcrVariantSpec>(
            OcrVariantSpec("original", PreprocessOperation.ORIGINAL, description = "без обработки"),
        )
        autoRecipes(stats).take(MAX_AUTO_RECIPES).forEach { variants += it }

        estimateRotations(
            bitmap,
            stats.quality.meanLuminance,
            stats.lowPercentile,
            stats.highPercentile,
        ).forEach { angle ->
            if (variants.size < MAX_VARIANTS) {
                variants += OcrVariantSpec(
                    name = "rotate-${angle.roundToInt()}-degrees",
                    operation = PreprocessOperation.ORIGINAL,
                    rotationDegrees = angle,
                    description = "поворот ${angle.roundToInt()}°",
                )
            }
        }

        return PreprocessingPlan(
            stats.quality,
            variants.take(MAX_VARIANTS),
            stats.lowPercentile,
            stats.highPercentile,
        )
    }

    /** Single hypothesis selected in the lab; falls back to the automatic plan. */
    fun planForced(bitmap: Bitmap, variantName: String): PreprocessingPlan {
        val base = plan(bitmap)
        val forced = base.variants.firstOrNull { it.name == variantName }
            ?: planExperiments(bitmap).variants.firstOrNull { it.name == variantName }
            ?: base.variants.first()
        return PreprocessingPlan(base.quality, listOf(forced), base.lowPercentile, base.highPercentile)
    }

    /**
     * Лабораторный набор: одиночные операции и комбинированные рецепты,
     * которые меняют сразу несколько параметров изображения.
     */
    fun planExperiments(bitmap: Bitmap): PreprocessingPlan {
        val stats = analyzeImage(bitmap)
        val variants = mutableListOf<OcrVariantSpec>()
        variants += OcrVariantSpec("original", PreprocessOperation.ORIGINAL, description = "без обработки")
        variants += OcrVariantSpec("contrast-stretch", PreprocessOperation.CONTRAST_STRETCH)
        variants += OcrVariantSpec("gamma-brighten", PreprocessOperation.GAMMA, gamma = 0.72f)
        variants += OcrVariantSpec("gamma-darken", PreprocessOperation.GAMMA, gamma = 1.28f)
        variants += OcrVariantSpec("denoise", PreprocessOperation.DENOISE, denoiseStrength = 0.5f)
        variants += OcrVariantSpec("clahe", PreprocessOperation.CLAHE, threshold = 2.2f)
        variants += OcrVariantSpec("unsharp-mask", PreprocessOperation.UNSHARP_MASK, sharpness = 0.6f)
        variants += OcrVariantSpec("flat-field", PreprocessOperation.FLAT_FIELD)
        variants += OcrVariantSpec("tophat", PreprocessOperation.TOPOHAT)
        variants += OcrVariantSpec("adaptive-threshold", PreprocessOperation.ADAPTIVE_THRESHOLD, threshold = SAUVOLA_K)
        variants += OcrVariantSpec("otsu-threshold", PreprocessOperation.OTSU_THRESHOLD)
        variants += OcrVariantSpec("invert", PreprocessOperation.INVERT)
        variants += recipe(
            name = "denoise-contrast",
            description = "шумоподавление + растяжение контраста + резкость",
            PreprocessStage(PreprocessOperation.DENOISE, denoiseStrength = 0.5f),
            PreprocessStage(PreprocessOperation.CONTRAST_STRETCH),
            PreprocessStage(PreprocessOperation.UNSHARP_MASK, sharpness = 0.6f),
        )
        variants += recipe(
            name = "flatfield-clahe",
            description = "выравнивание фона + локальный контраст + резкость",
            PreprocessStage(PreprocessOperation.FLAT_FIELD),
            PreprocessStage(PreprocessOperation.CLAHE, threshold = 2.4f),
            PreprocessStage(PreprocessOperation.UNSHARP_MASK, sharpness = 0.55f),
        )
        variants += recipe(
            name = "gamma-clahe",
            description = "гамма + CLAHE + резкость (тёмные фото)",
            PreprocessStage(PreprocessOperation.GAMMA, gamma = 0.7f),
            PreprocessStage(PreprocessOperation.CLAHE, threshold = 2.8f),
            PreprocessStage(PreprocessOperation.UNSHARP_MASK, sharpness = 0.5f),
        )
        variants += recipe(
            name = "gray-clahe",
            description = "оттенки серого + локальный контраст + резкость",
            PreprocessStage(PreprocessOperation.GRAYSCALE_STRETCH),
            PreprocessStage(PreprocessOperation.CLAHE, threshold = 2.2f),
            PreprocessStage(PreprocessOperation.UNSHARP_MASK, sharpness = 0.6f),
        )
        variants += recipe(
            name = "gray-sauvola",
            description = "серый + шумоподавление + адаптивный порог",
            PreprocessStage(PreprocessOperation.GRAYSCALE_STRETCH),
            PreprocessStage(PreprocessOperation.DENOISE, denoiseStrength = 0.4f),
            PreprocessStage(PreprocessOperation.ADAPTIVE_THRESHOLD, threshold = SAUVOLA_K),
        )
        variants += recipe(
            name = "tophat-contrast",
            description = "top-hat + растяжение контраста + резкость",
            PreprocessStage(PreprocessOperation.TOPOHAT),
            PreprocessStage(PreprocessOperation.CONTRAST_STRETCH),
            PreprocessStage(PreprocessOperation.UNSHARP_MASK, sharpness = 0.5f),
        )
        variants += recipe(
            name = "denoise-sauvola",
            description = "шумоподавление + адаптивный порог",
            PreprocessStage(PreprocessOperation.DENOISE, denoiseStrength = 0.55f),
            PreprocessStage(PreprocessOperation.ADAPTIVE_THRESHOLD, threshold = 0.28f),
        )
        val upscale = upscaleFactor(stats, aggressive = true)
        if (upscale > 1.05f) {
            variants += recipe(
                name = "upscale-detail",
                description = "апскейл ×${formatScale(upscale)} + шумоподавление + резкость",
                PreprocessStage(PreprocessOperation.UPSCALE, scale = upscale),
                PreprocessStage(
                    PreprocessOperation.DENOISE_SHARPEN,
                    denoiseStrength = 0.3f,
                    sharpness = 0.8f,
                ),
            )
            variants += recipe(
                name = "upscale-clahe",
                description = "апскейл ×${formatScale(upscale)} + CLAHE + резкость",
                PreprocessStage(PreprocessOperation.UPSCALE, scale = upscale),
                PreprocessStage(PreprocessOperation.CLAHE, threshold = 2.2f),
                PreprocessStage(PreprocessOperation.UNSHARP_MASK, sharpness = 0.6f),
            )
        }
        variants += OcrVariantSpec("sharpen-contrast", PreprocessOperation.SHARPEN_CONTRAST, sharpness = 0.75f)
        variants += OcrVariantSpec("denoise-sharpen", PreprocessOperation.DENOISE_SHARPEN, denoiseStrength = 0.5f, sharpness = 0.6f)
        estimateRotations(
            bitmap,
            stats.quality.meanLuminance,
            stats.lowPercentile,
            stats.highPercentile,
        ).forEach { angle ->
            variants += OcrVariantSpec(
                name = "deskew-${angle.roundToInt()}-degrees",
                operation = PreprocessOperation.ORIGINAL,
                rotationDegrees = angle,
                description = "выравнивание наклона ${angle.roundToInt()}°",
            )
        }
        return PreprocessingPlan(stats.quality, variants, stats.lowPercentile, stats.highPercentile)
    }

    fun createInput(bitmap: Bitmap, plan: PreprocessingPlan, spec: OcrVariantSpec): PreparedOcrInput {
        var current = bitmap
        var ownsCurrent = false
        var workingToInput = AffineTransform2D()
        try {
            spec.stages.forEach { stage ->
                val applied = applyStage(current, plan, stage)
                if (applied.bitmap !== current) {
                    if (ownsCurrent && !current.isRecycled) current.recycle()
                    current = applied.bitmap
                    ownsCurrent = true
                }
                if (applied.scaleX != 1f || applied.scaleY != 1f) {
                    workingToInput = workingToInput.then(
                        AffineTransform2D.scale(applied.scaleX, applied.scaleY),
                    )
                }
            }
            var input = current
            var inputToWorking = workingToInput.inverse() ?: AffineTransform2D()
            if (spec.rotationDegrees != 0f) {
                val (rotated, rotatedToInput) = rotate(input, spec.rotationDegrees)
                if (ownsCurrent && input !== bitmap && !input.isRecycled) input.recycle()
                input = rotated
                inputToWorking = rotatedToInput.then(inputToWorking)
            }
            return PreparedOcrInput(input, inputToWorking)
        } catch (throwable: Throwable) {
            if (ownsCurrent && current !== bitmap && !current.isRecycled) current.recycle()
            throw throwable
        }
    }

    fun createVariant(bitmap: Bitmap, plan: PreprocessingPlan, spec: OcrVariantSpec): Bitmap {
        if (spec.stages.isEmpty()) return bitmap
        var current = bitmap
        var ownsCurrent = false
        try {
            spec.stages.forEach { stage ->
                val applied = applyStage(current, plan, stage)
                if (applied.bitmap !== current) {
                    if (ownsCurrent && !current.isRecycled) current.recycle()
                    current = applied.bitmap
                    ownsCurrent = true
                }
            }
            return current
        } catch (throwable: Throwable) {
            if (ownsCurrent && current !== bitmap && !current.isRecycled) current.recycle()
            throw throwable
        }
    }

    private data class StageOutput(
        val bitmap: Bitmap,
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
    )

    private fun applyStage(source: Bitmap, plan: PreprocessingPlan, stage: PreprocessStage): StageOutput {
        if (stage.operation == PreprocessOperation.ORIGINAL) return StageOutput(source)
        if (stage.operation == PreprocessOperation.UPSCALE) {
            return upscaleStage(source, stage.scale)
        }
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return StageOutput(source)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val output = when (stage.operation) {
            PreprocessOperation.ORIGINAL -> return StageOutput(source)
            PreprocessOperation.UPSCALE -> return StageOutput(source)
            PreprocessOperation.CONTRAST_STRETCH ->
                contrastStretch(pixels, plan.lowPercentile, plan.highPercentile)
            PreprocessOperation.GRAYSCALE_STRETCH ->
                grayscaleStretch(pixels, plan.lowPercentile, plan.highPercentile)
            PreprocessOperation.GAMMA -> gammaPixels(pixels, stage.gamma)
            PreprocessOperation.INVERT -> invertPixels(pixels)
            PreprocessOperation.ADAPTIVE_THRESHOLD ->
                adaptiveThreshold(pixels, width, height, stage.threshold)
            PreprocessOperation.OTSU_THRESHOLD -> otsuThreshold(pixels)
            PreprocessOperation.UNSHARP_MASK ->
                unsharpPixels(pixels, width, height, stage.sharpness)
            PreprocessOperation.DENOISE ->
                denoisePixels(pixels, width, height, stage.denoiseStrength)
            PreprocessOperation.CLAHE ->
                clahePixels(pixels, width, height, stage.threshold)
            PreprocessOperation.SHARPEN_CONTRAST ->
                sharpenContrastPixels(pixels, width, height, stage.sharpness)
            PreprocessOperation.TOPOHAT -> topHatPixels(pixels, width, height)
            PreprocessOperation.DENOISE_SHARPEN ->
                denoiseSharpenPixels(pixels, width, height, stage.denoiseStrength, stage.sharpness)
            PreprocessOperation.FLAT_FIELD -> flatFieldPixels(pixels, width, height)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(output, 0, width, 0, 0, width, height)
        }
        return StageOutput(bitmap)
    }

    private fun upscaleStage(source: Bitmap, requestedScale: Float): StageOutput {
        val safeScale = requestedScale
            .takeIf { it.isFinite() }
            ?.coerceIn(1f, MAX_UPSCALE_STAGE)
            ?: 1f
        if (safeScale < MIN_UPSCALE_EFFECT) return StageOutput(source)
        val sourcePixels = source.width.toLong() * source.height.toLong()
        val budgetScale = sqrt(
            MAX_UPSCALED_PIXELS.toDouble() / sourcePixels.coerceAtLeast(1L),
        ).toFloat()
        val scale = min(safeScale, budgetScale)
        if (scale < MIN_UPSCALE_EFFECT) return StageOutput(source)

        var current = source
        var ownsCurrent = false
        var remaining = scale
        try {
            while (remaining > MIN_UPSCALE_EFFECT) {
                val step = min(remaining, 2f)
                val targetWidth = (current.width * step).roundToInt().coerceAtLeast(1)
                val targetHeight = (current.height * step).roundToInt().coerceAtLeast(1)
                val next = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(next)
                canvas.drawBitmap(
                    current,
                    null,
                    android.graphics.Rect(0, 0, targetWidth, targetHeight),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
                )
                if (ownsCurrent && !current.isRecycled) current.recycle()
                current = next
                ownsCurrent = true
                remaining /= step
            }
        } catch (throwable: Throwable) {
            if (ownsCurrent && current !== source && !current.isRecycled) current.recycle()
            throw throwable
        }
        return StageOutput(
            bitmap = current,
            scaleX = current.width.toFloat() / source.width.coerceAtLeast(1),
            scaleY = current.height.toFloat() / source.height.coerceAtLeast(1),
        )
    }

    /** Рецепты, собранные из нескольких операций по результатам анализа фото. */
    private fun autoRecipes(stats: ImageStats): List<OcrVariantSpec> {
        val quality = stats.quality
        val lowContrast = quality.percentileRange < LOW_CONTRAST_RANGE
        val veryLowContrast = quality.percentileRange < VERY_LOW_CONTRAST_RANGE
        val dark = quality.meanLuminance < DARK_MEAN_THRESHOLD
        val bright = quality.meanLuminance > BRIGHT_MEAN_THRESHOLD
        val soft = stats.detail < SOFT_TEXT_DETAIL
        val busy = stats.detail > NOISY_DETAIL
        val uneven = stats.unevenLighting > UNEVEN_LIGHTING_THRESHOLD
        val clipped = max(quality.clippedDarkFraction, quality.clippedBrightFraction)
        val glare = quality.clippedBrightFraction > GLARE_CLIP_FRACTION
        val recipes = mutableListOf<OcrVariantSpec>()

        if ((uneven || lowContrast || soft) && clipped < MAX_CLIPPED_FOR_ENHANCEMENT) {
            recipes += recipe(
                name = "auto-document",
                description = "выравнивание фона + локальный контраст + резкость",
                PreprocessStage(PreprocessOperation.FLAT_FIELD),
                PreprocessStage(
                    PreprocessOperation.CLAHE,
                    threshold = if (veryLowContrast) 3.2f else 2.3f,
                ),
                PreprocessStage(PreprocessOperation.UNSHARP_MASK, sharpness = 0.55f),
            )
        }
        if (dark) {
            val gamma = when {
                quality.meanLuminance < 0.18f -> 0.45f
                quality.meanLuminance < 0.26f -> 0.58f
                else -> 0.72f
            }
            recipes += recipe(
                name = "auto-lowlight",
                description = "осветление + локальный контраст + резкость",
                PreprocessStage(PreprocessOperation.GAMMA, gamma = gamma),
                PreprocessStage(PreprocessOperation.CLAHE, threshold = 2.8f),
                PreprocessStage(PreprocessOperation.UNSHARP_MASK, sharpness = 0.55f),
            )
        }
        if (lowContrast && clipped < MAX_CLIPPED_FOR_ENHANCEMENT) {
            recipes += recipe(
                name = "auto-faded",
                description = "растяжение яркости + усиление резкости",
                PreprocessStage(PreprocessOperation.GRAYSCALE_STRETCH),
                PreprocessStage(PreprocessOperation.UNSHARP_MASK, sharpness = 0.8f),
            )
        }
        if ((lowContrast && soft) || uneven) {
            val k = if (veryLowContrast) 0.26f else SAUVOLA_K
            recipes += recipe(
                name = "auto-binarize",
                description = "шумоподавление + адаптивный порог Sauvola",
                PreprocessStage(PreprocessOperation.DENOISE, denoiseStrength = 0.4f),
                PreprocessStage(PreprocessOperation.ADAPTIVE_THRESHOLD, threshold = k),
            )
        }
        if (busy) {
            recipes += recipe(
                name = "auto-denoise",
                description = "шумоподавление + локальный контраст + резкость",
                PreprocessStage(PreprocessOperation.DENOISE, denoiseStrength = 0.65f),
                PreprocessStage(PreprocessOperation.CLAHE, threshold = 2.0f),
                PreprocessStage(PreprocessOperation.UNSHARP_MASK, sharpness = 0.65f),
            )
        }
        if (bright || glare) {
            recipes += recipe(
                name = "auto-glare",
                description = "локальный контраст + затемнение + резкость",
                PreprocessStage(PreprocessOperation.CLAHE, threshold = 3.2f),
                PreprocessStage(PreprocessOperation.GAMMA, gamma = 1.18f),
                PreprocessStage(PreprocessOperation.UNSHARP_MASK, sharpness = 0.45f),
            )
        }
        if (stats.colored && (lowContrast || quality.clippedDarkFraction > 0.02f)) {
            recipes += recipe(
                name = "auto-color",
                description = "чёрно-белое ядро + top-hat + резкость",
                PreprocessStage(PreprocessOperation.GRAYSCALE_STRETCH),
                PreprocessStage(PreprocessOperation.TOPOHAT),
                PreprocessStage(PreprocessOperation.UNSHARP_MASK, sharpness = 0.5f),
            )
        }
        val upscale = upscaleFactor(stats, aggressive = false)
        if (upscale > 1.05f) {
            recipes += recipe(
                name = "auto-upscale",
                description = "апскейл ×${formatScale(upscale)} + шумоподавление + резкость",
                PreprocessStage(PreprocessOperation.UPSCALE, scale = upscale),
                PreprocessStage(
                    PreprocessOperation.DENOISE_SHARPEN,
                    denoiseStrength = 0.3f,
                    sharpness = 0.75f,
                ),
            )
        }
        return recipes
    }

    private fun recipe(
        name: String,
        description: String,
        vararg stages: PreprocessStage,
    ): OcrVariantSpec = OcrVariantSpec(name, stages.toList(), description = description)

    private data class ImageStats(
        val quality: ImageQualityEstimate,
        val lowPercentile: Int,
        val highPercentile: Int,
        val detail: Float,
        val unevenLighting: Float,
        val colored: Boolean,
        val maxDimension: Int,
        val pixels: Long,
    )

    private fun analyzeImage(bitmap: Bitmap): ImageStats {
        val histogram = IntArray(256)
        var sampleCount = 0
        var luminanceSum = 0.0
        var luminanceSquaredSum = 0.0
        var clippedDark = 0
        var clippedBright = 0
        var detailSum = 0.0
        var detailSamples = 0
        var colored = false
        val cellSums = DoubleArray(UNEVEN_GRID * UNEVEN_GRID)
        val cellCounts = IntArray(UNEVEN_GRID * UNEVEN_GRID)
        val stride = (maxOf(bitmap.width, bitmap.height) / MAX_SAMPLE_DIMENSION).coerceAtLeast(1)
        val row = IntArray(bitmap.width)
        val safeWidth = bitmap.width.coerceAtLeast(1)
        val safeHeight = bitmap.height.coerceAtLeast(1)

        var y = 0
        while (y < bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            var x = 0
            while (x < bitmap.width) {
                val color = row[x]
                val luminance = luminance(color)
                val bucket = (luminance * 255f).roundToInt().coerceIn(0, 255)
                histogram[bucket]++
                sampleCount++
                luminanceSum += luminance
                luminanceSquaredSum += luminance * luminance
                if (bucket <= CLIPPED_DARK_THRESHOLD) clippedDark++
                if (bucket >= CLIPPED_BRIGHT_THRESHOLD) clippedBright++
                if (!colored) {
                    val maximum = max(Color.red(color), max(Color.green(color), Color.blue(color)))
                    val minimum = min(Color.red(color), min(Color.green(color), Color.blue(color)))
                    if (maximum - minimum > COLOR_DIFFERENCE_THRESHOLD) colored = true
                }
                val cellX = (x * UNEVEN_GRID / safeWidth).coerceIn(0, UNEVEN_GRID - 1)
                val cellY = (y * UNEVEN_GRID / safeHeight).coerceIn(0, UNEVEN_GRID - 1)
                val cell = cellY * UNEVEN_GRID + cellX
                cellSums[cell] += luminance
                cellCounts[cell]++
                if (x + stride < row.size) {
                    detailSum += abs(luminance - luminance(row[x + stride]))
                    detailSamples++
                }
                x += stride
            }
            y += stride
        }

        val detail = if (detailSamples == 0) 0f else (detailSum / detailSamples).toFloat()
        val mean = if (sampleCount == 0) 0f else (luminanceSum / sampleCount).toFloat()
        val variance = if (sampleCount == 0) 0f else
            (luminanceSquaredSum / sampleCount - mean * mean).coerceAtLeast(0.0).toFloat()
        val low = percentile(histogram, sampleCount, LOW_PERCENTILE)
        val high = percentile(histogram, sampleCount, HIGH_PERCENTILE)
        val cellMeans = cellSums.indices
            .filter { cellCounts[it] >= max(8, sampleCount / 60) }
            .map { cellSums[it] / cellCounts[it] }
        val unevenLighting = if (cellMeans.size < 4) 0f else
            (cellMeans.max() - cellMeans.min()).toFloat().coerceIn(0f, 1f)
        val quality = ImageQualityEstimate(
            meanLuminance = mean,
            luminanceStdDev = sqrt(variance),
            percentileRange = (high - low) / 255f,
            clippedDarkFraction = if (sampleCount == 0) 0f else clippedDark.toFloat() / sampleCount,
            clippedBrightFraction = if (sampleCount == 0) 0f else clippedBright.toFloat() / sampleCount,
        )
        return ImageStats(
            quality = quality,
            lowPercentile = low,
            highPercentile = high,
            detail = detail,
            unevenLighting = unevenLighting,
            colored = colored,
            maxDimension = max(bitmap.width, bitmap.height),
            pixels = bitmap.width.toLong() * bitmap.height.toLong(),
        )
    }

    private fun upscaleFactor(stats: ImageStats, aggressive: Boolean): Float {
        val desired = when {
            stats.maxDimension <= 700 -> 3f
            stats.maxDimension <= 1100 -> 2.5f
            stats.maxDimension <= 1600 -> 2f
            stats.maxDimension <= 2200 -> 1.7f
            else -> 1.4f
        }
        val budget = if (aggressive) EXPERIMENT_UPSCALE_PIXELS else AUTO_UPSCALE_PIXELS
        val budgetScale = sqrt(budget.toDouble() / stats.pixels.coerceAtLeast(1L)).toFloat()
        return min(desired, budgetScale).coerceIn(1f, MAX_UPSCALE_STAGE)
    }

    private fun formatScale(value: Float): String =
        String.format(java.util.Locale.US, "%.1f", value)

    private fun contrastStretch(pixels: IntArray, low: Int, high: Int): IntArray {
        val safeHigh = high.coerceAtLeast(low + 1)
        return IntArray(pixels.size) { index ->
            val color = pixels[index]
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            val luma = rgbLuminance(red, green, blue)
            preserveHue(
                red,
                green,
                blue,
                luma,
                stretch(luma, low, safeHigh),
                Color.alpha(color),
            )
        }
    }

    private fun grayscaleStretch(pixels: IntArray, low: Int, high: Int): IntArray {
        val safeHigh = high.coerceAtLeast(low + 1)
        return IntArray(pixels.size) { index ->
            val color = pixels[index]
            val adjusted = stretch(
                rgbLuminance(Color.red(color), Color.green(color), Color.blue(color)),
                low,
                safeHigh,
            )
            val channel = (adjusted * 255f).roundToInt().coerceIn(0, 255)
            Color.argb(Color.alpha(color), channel, channel, channel)
        }
    }

    private fun gammaPixels(pixels: IntArray, gammaValue: Float): IntArray {
        val safeGamma = gammaValue.takeIf { it.isFinite() }?.coerceIn(0.05f, 8f) ?: 1f
        return IntArray(pixels.size) { index ->
            val color = pixels[index]
            Color.argb(
                Color.alpha(color),
                gamma(Color.red(color), safeGamma),
                gamma(Color.green(color), safeGamma),
                gamma(Color.blue(color), safeGamma),
            )
        }
    }

    private fun invertPixels(pixels: IntArray): IntArray = IntArray(pixels.size) { index ->
        val color = pixels[index]
        Color.argb(
            Color.alpha(color),
            255 - Color.red(color),
            255 - Color.green(color),
            255 - Color.blue(color),
        )
    }

    /** Rec. 709 luma in 0..255; every local filter works on this plane to avoid colour fringes. */
    private fun lumaArray(pixels: IntArray): IntArray = IntArray(pixels.size) { index ->
        val color = pixels[index]
        val luma = (54 * Color.red(color) + 183 * Color.green(color) + 19 * Color.blue(color) + 128) shr 8
        luma.coerceIn(0, 255)
    }

    private fun grayOutput(luma: IntArray, source: IntArray): IntArray = IntArray(luma.size) { index ->
        val value = luma[index].coerceIn(0, 255)
        (source[index] and 0xFF000000.toInt()) or (value shl 16) or (value shl 8) or value
    }

    /** Normalizes dark-background photographs so local filters always see dark glyphs on light paper. */
    private fun lightBackgroundLuma(luma: IntArray): IntArray {
        val sum = luma.sumOf { it.toLong() }
        if (sum * 2 >= luma.size.toLong() * 255L) return luma
        return IntArray(luma.size) { 255 - luma[it] }
    }

    private fun adaptiveThreshold(
        pixels: IntArray,
        width: Int,
        height: Int,
        kHint: Float,
    ): IntArray {
        val luma = lightBackgroundLuma(lumaArray(pixels))
        val stride = width + 1
        val integral = LongArray(stride * (height + 1))
        val squared = LongArray(integral.size)
        for (y in 0 until height) {
            var rowSum = 0L
            var rowSquared = 0L
            val rowOffset = (y + 1) * stride
            val previousOffset = y * stride
            val pixelOffset = y * width
            for (x in 0 until width) {
                val value = luma[pixelOffset + x]
                rowSum += value.toLong()
                rowSquared += value.toLong() * value
                integral[rowOffset + x + 1] = integral[previousOffset + x + 1] + rowSum
                squared[rowOffset + x + 1] = squared[previousOffset + x + 1] + rowSquared
            }
        }
        val radius = (min(width, height) / SAUVOLA_WINDOW_DIVISOR).coerceIn(8, 48)
        val k = kHint.takeIf { it.isFinite() && it in 0.05f..0.9f } ?: SAUVOLA_K
        val output = IntArray(luma.size)
        for (y in 0 until height) {
            val top = max(0, y - radius)
            val bottom = min(height - 1, y + radius)
            for (x in 0 until width) {
                val left = max(0, x - radius)
                val right = min(width - 1, x + radius)
                val area = ((right - left + 1) * (bottom - top + 1)).toDouble()
                val sum = rectangleSum(integral, stride, left, top, right, bottom)
                val squareSum = rectangleSum(squared, stride, left, top, right, bottom)
                val mean = sum / area
                val deviation = sqrt((squareSum / area - mean * mean).coerceAtLeast(0.0))
                val threshold = mean * (1.0 + k * (deviation / 128.0 - 1.0))
                val value = if (luma[y * width + x] <= threshold) 0 else 255
                output[y * width + x] = Color.argb(255, value, value, value)
            }
        }
        return output
    }

    private fun rectangleSum(
        integral: LongArray,
        stride: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Double {
        val bottomRight = integral[(bottom + 1) * stride + right + 1]
        val topRight = integral[top * stride + right + 1]
        val bottomLeft = integral[(bottom + 1) * stride + left]
        val topLeft = integral[top * stride + left]
        return (bottomRight - topRight - bottomLeft + topLeft).toDouble()
    }

    private fun otsuThreshold(pixels: IntArray): IntArray {
        val luma = lightBackgroundLuma(lumaArray(pixels))
        val histogram = IntArray(256)
        luma.forEach { histogram[it]++ }
        val total = luma.size.toDouble()
        var totalSum = 0.0
        for (value in 0..255) totalSum += value.toDouble() * histogram[value]
        var backgroundWeight = 0.0
        var backgroundSum = 0.0
        var bestVariance = -1.0
        var threshold = 127
        for (value in 0..255) {
            backgroundWeight += histogram[value]
            if (backgroundWeight == 0.0) continue
            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight <= 0.0) break
            backgroundSum += value.toDouble() * histogram[value]
            val backgroundMean = backgroundSum / backgroundWeight
            val foregroundMean = (totalSum - backgroundSum) / foregroundWeight
            val variance = backgroundWeight * foregroundWeight *
                (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)
            if (variance > bestVariance) {
                bestVariance = variance
                threshold = value
            }
        }
        return IntArray(luma.size) { index ->
            val value = if (luma[index] <= threshold) 0 else 255
            Color.argb(255, value, value, value)
        }
    }

    private fun gaussianBlur(source: IntArray, width: Int, height: Int): IntArray {
        val horizontal = IntArray(source.size)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                var sum = 0
                for (tap in -2..2) {
                    val sampleX = (x + tap).coerceIn(0, width - 1)
                    sum += source[offset + sampleX] * GAUSSIAN_KERNEL[tap + 2]
                }
                horizontal[offset + x] = sum / GAUSSIAN_KERNEL_SUM
            }
        }
        val output = IntArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                for (tap in -2..2) {
                    val sampleY = (y + tap).coerceIn(0, height - 1)
                    sum += horizontal[sampleY * width + x] * GAUSSIAN_KERNEL[tap + 2]
                }
                output[y * width + x] = sum / GAUSSIAN_KERNEL_SUM
            }
        }
        return output
    }

    private fun unsharpPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        sharpness: Float,
    ): IntArray {
        val luma = unsharpLuma(lumaArray(pixels), width, height, sharpness)
        return grayOutput(luma, pixels)
    }

    private fun unsharpLuma(
        luma: IntArray,
        width: Int,
        height: Int,
        sharpness: Float,
    ): IntArray {
        val blurred = gaussianBlur(luma, width, height)
        val amount = sharpness.takeIf { it.isFinite() }?.coerceIn(0.05f, 2f) ?: 0.5f
        return IntArray(luma.size) { index ->
            (luma[index] + amount * (luma[index] - blurred[index]))
                .roundToInt()
                .coerceIn(0, 255)
        }
    }

    private fun denoisePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        strength: Float,
    ): IntArray {
        var luma = median3(lumaArray(pixels), width, height)
        if (strength >= STRONG_DENOISE_THRESHOLD) luma = median3(luma, width, height)
        return grayOutput(luma, pixels)
    }

    private fun denoiseSharpenPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        denoiseStrength: Float,
        sharpness: Float,
    ): IntArray {
        var luma = median3(lumaArray(pixels), width, height)
        if (denoiseStrength >= STRONG_DENOISE_THRESHOLD) luma = median3(luma, width, height)
        return grayOutput(unsharpLuma(luma, width, height, sharpness), pixels)
    }

    /** 3x3 median; two passes approximate a 5x5 window at a fraction of the cost. */
    private fun median3(source: IntArray, width: Int, height: Int): IntArray {
        val output = IntArray(source.size)
        val window = IntArray(9)
        for (y in 0 until height) {
            val top = max(0, y - 1)
            val bottom = min(height - 1, y + 1)
            for (x in 0 until width) {
                val left = max(0, x - 1)
                val right = min(width - 1, x + 1)
                var count = 0
                for (sampleY in top..bottom) {
                    val offset = sampleY * width
                    for (sampleX in left..right) window[count++] = source[offset + sampleX]
                }
                for (index in 1 until count) {
                    val value = window[index]
                    var position = index - 1
                    while (position >= 0 && window[position] > value) {
                        window[position + 1] = window[position]
                        position--
                    }
                    window[position + 1] = value
                }
                output[y * width + x] = window[count / 2]
            }
        }
        return output
    }

    private fun clahePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        clipHint: Float,
    ): IntArray {
        val luma = lumaArray(pixels)
        val grid = CLAHE_TILES
        val tileWidth = ceil(width.toDouble() / grid).toInt().coerceAtLeast(1)
        val tileHeight = ceil(height.toDouble() / grid).toInt().coerceAtLeast(1)
        val clipLimit = clipHint.takeIf { it.isFinite() }?.coerceIn(0.5f, 10f) ?: 2f
        val luts = Array(grid * grid) { IntArray(256) }
        for (tileY in 0 until grid) {
            val top = tileY * tileHeight
            if (top >= height) continue
            val bottom = min(height, top + tileHeight)
            for (tileX in 0 until grid) {
                val left = tileX * tileWidth
                if (left >= width) continue
                val right = min(width, left + tileWidth)
                val histogram = IntArray(256)
                var count = 0
                for (y in top until bottom) {
                    val offset = y * width
                    for (x in left until right) {
                        histogram[luma[offset + x]]++
                        count++
                    }
                }
                if (count == 0) continue
                val clip = max(1, (clipLimit * count / 256f).roundToInt())
                var excess = 0
                for (value in 0..255) {
                    if (histogram[value] > clip) {
                        excess += histogram[value] - clip
                        histogram[value] = clip
                    }
                }
                val share = excess / 256
                val remainder = excess % 256
                if (share > 0) {
                    for (value in 0..255) histogram[value] += share
                }
                for (value in 0 until remainder) histogram[value]++
                val lut = luts[tileY * grid + tileX]
                var cumulative = 0
                for (value in 0..255) {
                    cumulative += histogram[value]
                    lut[value] = (cumulative * 255f / count).roundToInt().coerceIn(0, 255)
                }
            }
        }
        val output = IntArray(luma.size)
        for (y in 0 until height) {
            val positionY = y.toFloat() / tileHeight - 0.5f
            val baseY = floor(positionY).toInt().coerceIn(0, grid - 1)
            val nextY = min(grid - 1, baseY + 1)
            val fractionY = if (baseY == nextY) 0f else (positionY - floor(positionY)).coerceIn(0f, 1f)
            for (x in 0 until width) {
                val positionX = x.toFloat() / tileWidth - 0.5f
                val baseX = floor(positionX).toInt().coerceIn(0, grid - 1)
                val nextX = min(grid - 1, baseX + 1)
                val fractionX = if (baseX == nextX) 0f else (positionX - floor(positionX)).coerceIn(0f, 1f)
                val value = luma[y * width + x]
                val topLeft = luts[baseY * grid + baseX][value]
                val topRight = luts[baseY * grid + nextX][value]
                val bottomLeft = luts[nextY * grid + baseX][value]
                val bottomRight = luts[nextY * grid + nextX][value]
                val topValue = topLeft + (topRight - topLeft) * fractionX
                val bottomValue = bottomLeft + (bottomRight - bottomLeft) * fractionX
                val mapped = (topValue + (bottomValue - topValue) * fractionY)
                    .roundToInt()
                    .coerceIn(0, 255)
                output[y * width + x] = Color.argb(255, mapped, mapped, mapped)
            }
        }
        return output
    }

    private fun sharpenContrastPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        sharpness: Float,
    ): IntArray {
        val sharpened = unsharpLuma(lumaArray(pixels), width, height, sharpness)
        return grayOutput(stretchLuma(sharpened, 0.02f, 0.98f), pixels)
    }

    private fun stretchLuma(luma: IntArray, lowFraction: Float, highFraction: Float): IntArray {
        val histogram = IntArray(256)
        luma.forEach { histogram[it]++ }
        val low = percentile(histogram, luma.size, lowFraction)
        val high = percentile(histogram, luma.size, highFraction).coerceAtLeast(low + 1)
        val scale = 255f / (high - low)
        return IntArray(luma.size) { index ->
            ((luma[index] - low) * scale).roundToInt().coerceIn(0, 255)
        }
    }

    /** Black-hat filtering on normalized luma: local paper is replaced by white, strokes stay dark. */
    private fun topHatPixels(pixels: IntArray, width: Int, height: Int): IntArray {
        val luma = lightBackgroundLuma(lumaArray(pixels))
        val radius = (min(width, height) / TOPHAT_WINDOW_DIVISOR).coerceIn(3, 8)
        val closing = rankFilter(
            rankFilter(luma, width, height, radius, takeMax = true),
            width,
            height,
            radius,
            takeMax = false,
        )
        val enhanced = IntArray(luma.size) { index ->
            (255 - 2 * (closing[index] - luma[index]).coerceAtLeast(0)).coerceIn(0, 255)
        }
        return grayOutput(enhanced, pixels)
    }

    private fun rankFilter(
        source: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        takeMax: Boolean,
    ): IntArray {
        val horizontal = IntArray(source.size)
        val initial = if (takeMax) 0 else 255
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                var value = initial
                val left = max(0, x - radius)
                val right = min(width - 1, x + radius)
                for (sampleX in left..right) {
                    val candidate = source[offset + sampleX]
                    if (if (takeMax) candidate > value else candidate < value) value = candidate
                }
                horizontal[offset + x] = value
            }
        }
        val output = IntArray(source.size)
        for (y in 0 until height) {
            val top = max(0, y - radius)
            val bottom = min(height - 1, y + radius)
            for (x in 0 until width) {
                var value = initial
                for (sampleY in top..bottom) {
                    val candidate = horizontal[sampleY * width + x]
                    if (if (takeMax) candidate > value else candidate < value) value = candidate
                }
                output[y * width + x] = value
            }
        }
        return output
    }

    /** Removes uneven illumination by dividing the image by a heavily smoothed paper estimate. */
    private fun flatFieldPixels(pixels: IntArray, width: Int, height: Int): IntArray {
        val luma = lightBackgroundLuma(lumaArray(pixels))
        val scale = min(1f, FLAT_FIELD_PREVIEW / max(width, height).toFloat())
        val smallWidth = max(1, (width * scale).roundToInt())
        val smallHeight = max(1, (height * scale).roundToInt())
        val small = IntArray(smallWidth * smallHeight) { index ->
            val x = (index % smallWidth) * width / smallWidth
            val y = (index / smallWidth) * height / smallHeight
            luma[y * width + x]
        }
        val background = rankFilter(
            rankFilter(small, smallWidth, smallHeight, 2, takeMax = true),
            smallWidth,
            smallHeight,
            2,
            takeMax = false,
        )
        val output = IntArray(luma.size)
        for (y in 0 until height) {
            val sourceY = (y.toFloat() / height * (smallHeight - 1))
                .coerceIn(0f, (smallHeight - 1).toFloat())
            val baseY = sourceY.toInt()
            val nextY = min(smallHeight - 1, baseY + 1)
            val fractionY = sourceY - baseY
            for (x in 0 until width) {
                val sourceX = (x.toFloat() / width * (smallWidth - 1))
                    .coerceIn(0f, (smallWidth - 1).toFloat())
                val baseX = sourceX.toInt()
                val nextX = min(smallWidth - 1, baseX + 1)
                val fractionX = sourceX - baseX
                val topLeft = background[baseY * smallWidth + baseX].toFloat()
                val topRight = background[baseY * smallWidth + nextX].toFloat()
                val bottomLeft = background[nextY * smallWidth + baseX].toFloat()
                val bottomRight = background[nextY * smallWidth + nextX].toFloat()
                val topValue = topLeft + (topRight - topLeft) * fractionX
                val bottomValue = bottomLeft + (bottomRight - bottomLeft) * fractionX
                val estimated = (topValue + (bottomValue - topValue) * fractionY).coerceAtLeast(32f)
                val corrected = (luma[y * width + x] * 255f / estimated)
                    .roundToInt()
                    .coerceIn(0, 255)
                output[y * width + x] = Color.argb(255, corrected, corrected, corrected)
            }
        }
        return output
    }

    private fun estimateRotations(
        bitmap: Bitmap,
        meanLuminance: Float,
        lowPercentile: Int,
        highPercentile: Int,
    ): List<Float> {
        val scale = minOf(1f, ORIENTATION_PREVIEW_DIMENSION / maxOf(bitmap.width, bitmap.height).toFloat())
        val previewWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val previewHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val preview = if (previewWidth == bitmap.width && previewHeight == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, previewWidth, previewHeight, true)
        }
        try {
            val pixels = IntArray(preview.width * preview.height)
            preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
            val threshold = (lowPercentile + highPercentile) / 2f
            val selectDarkPixels = meanLuminance >= 0.5f
            val sampleStep = if (pixels.size > MAX_ORIENTATION_PIXELS) 2 else 1
            val foreground = ArrayList<SamplePoint>(minOf(pixels.size / 4, MAX_ORIENTATION_POINTS))
            var y = 0
            while (y < preview.height) {
                var x = 0
                while (x < preview.width) {
                    val lightness = luminance(pixels[y * preview.width + x]) * 255f
                    val isForeground = if (selectDarkPixels) lightness < threshold else lightness > threshold
                    if (isForeground) foreground += SamplePoint(x.toFloat(), y.toFloat())
                    x += sampleStep
                }
                y += sampleStep
            }
            if (foreground.size < MIN_ORIENTATION_POINTS || foreground.size > MAX_ORIENTATION_POINTS) {
                return emptyList()
            }

            val coarseAngles = (-90..90 step ORIENTATION_STEP_DEGREES).map { it.toFloat() }
            val scores = coarseAngles.map { angle ->
                RotationScore(angle, projectionScore(foreground, preview.width, preview.height, angle))
            }
            val baselineScore = scores.firstOrNull { it.degrees == 0f }?.score ?: 0f
            val bestScore = scores.maxOfOrNull { it.score } ?: return emptyList()
            val peaks = scores
                .filter { peak ->
                    kotlin.math.abs(peak.degrees) >= MIN_ROTATION_ANGLE &&
                        peak.score >= bestScore * SECOND_ANGLE_RATIO &&
                        peak.score >= baselineScore * rotationImprovement(peak.degrees)
                }
                .sortedByDescending { it.score }
            val selected = mutableListOf<RotationScore>()
            for (peak in peaks) {
                if (selected.none { kotlin.math.abs(it.degrees - peak.degrees) < PEAK_SEPARATION_DEGREES }) {
                    selected += peak
                }
                if (selected.size == MAX_ROTATION_VARIANTS) break
            }

            return selected.map { peak ->
                var bestAngle = peak.degrees
                var refinedScore = peak.score
                for (offset in -REFINEMENT_RANGE_DEGREES..REFINEMENT_RANGE_DEGREES) {
                    val angle = peak.degrees + offset
                    val score = projectionScore(foreground, preview.width, preview.height, angle)
                    if (score > refinedScore) {
                        refinedScore = score
                        bestAngle = angle
                    }
                }
                bestAngle
            }.filter { kotlin.math.abs(it) >= MIN_ROTATION_ANGLE }
                .distinctBy { it.roundToInt() }
        } finally {
            if (preview !== bitmap && !preview.isRecycled) preview.recycle()
        }
    }

    private fun rotationImprovement(degrees: Float): Float =
        if (abs(degrees) < SMALL_ROTATION_LIMIT_DEGREES) {
            SMALL_ROTATION_IMPROVEMENT
        } else {
            ROTATION_IMPROVEMENT
        }

    private fun projectionScore(points: List<SamplePoint>, width: Int, height: Int, degrees: Float): Float {
        val radians = Math.toRadians(degrees.toDouble())
        val sinAngle = sin(radians).toFloat()
        val cosAngle = cos(radians).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val cornerYs = listOf(
            PointLike(0f, 0f),
            PointLike(width.toFloat(), 0f),
            PointLike(width.toFloat(), height.toFloat()),
            PointLike(0f, height.toFloat()),
        ).map { sinAngle * (it.x - centerX) + cosAngle * (it.y - centerY) }
        val minY = cornerYs.minOrNull() ?: 0f
        val rows = IntArray(ceil((cornerYs.maxOrNull() ?: 0f) - minY).toInt().coerceAtLeast(1) + 1)
        points.forEach { point ->
            val row = (sinAngle * (point.x - centerX) + cosAngle * (point.y - centerY) - minY)
                .roundToInt()
                .coerceIn(0, rows.lastIndex)
            rows[row]++
        }
        val concentration = rows.sumOf { it.toLong() * it }.toDouble()
        // The projected extent depends on the angle, so raw concentration would
        // favour 90° on portrait pages simply because it has fewer rows. Dividing
        // by the uniform-distribution concentration makes angles directly comparable.
        val uniformConcentration = points.size.toDouble() / rows.size
        return ((concentration / points.size) / uniformConcentration).toFloat()
    }

    private fun rotate(bitmap: Bitmap, degrees: Float): Pair<Bitmap, AffineTransform2D> {
        val radians = Math.toRadians(degrees.toDouble())
        val cosAngle = cos(radians).toFloat()
        val sinAngle = sin(radians).toFloat()
        val centerX = bitmap.width / 2f
        val centerY = bitmap.height / 2f
        val rotation = AffineTransform2D(
            m00 = cosAngle,
            m01 = -sinAngle,
            m02 = -cosAngle * centerX + sinAngle * centerY,
            m10 = sinAngle,
            m11 = cosAngle,
            m12 = -sinAngle * centerX - cosAngle * centerY,
        )
        val bounds = listOf(
            Point2D(0f, 0f),
            Point2D(bitmap.width.toFloat(), 0f),
            Point2D(bitmap.width.toFloat(), bitmap.height.toFloat()),
            Point2D(0f, bitmap.height.toFloat()),
        ).map(rotation::map)
        val minX = bounds.minOf { it.x }
        val minY = bounds.minOf { it.y }
        val outputWidth = ceil(bounds.maxOf { it.x } - minX).toInt().coerceAtLeast(1)
        val outputHeight = ceil(bounds.maxOf { it.y } - minY).toInt().coerceAtLeast(1)
        val inputToOutput = rotation.copy(
            m02 = rotation.m02 - minX,
            m12 = rotation.m12 - minY,
        )
        val matrix = Matrix().apply {
            setValues(
                floatArrayOf(
                    inputToOutput.m00,
                    inputToOutput.m01,
                    inputToOutput.m02,
                    inputToOutput.m10,
                    inputToOutput.m11,
                    inputToOutput.m12,
                    0f,
                    0f,
                    1f,
                ),
            )
        }
        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        // OCR engines read transparent corners as black when alpha is ignored,
        // which creates fake dark areas around a rotated page.
        output.eraseColor(Color.WHITE)
        Canvas(output).drawBitmap(
            bitmap,
            matrix,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return output to (inputToOutput.inverse() ?: AffineTransform2D())
    }

    private data class SamplePoint(val x: Float, val y: Float)
    private data class PointLike(val x: Float, val y: Float)
    private data class RotationScore(val degrees: Float, val score: Float)

    private fun percentile(histogram: IntArray, count: Int, percentile: Float): Int {
        if (count <= 0) return 0
        val target = (count * percentile).roundToInt().coerceAtLeast(1)
        var accumulated = 0
        for (index in histogram.indices) {
            accumulated += histogram[index]
            if (accumulated >= target) return index
        }
        return histogram.lastIndex
    }

    private fun stretch(value: Float, low: Int, high: Int): Float =
        ((value * 255f - low) / (high - low).coerceAtLeast(1)).coerceIn(0f, 1f)

    private fun preserveHue(
        red: Int,
        green: Int,
        blue: Int,
        originalLuma: Float,
        adjustedLuma: Float,
        alpha: Int,
    ): Int {
        if (originalLuma <= 0.001f) {
            val channel = (adjustedLuma * 255f).roundToInt().coerceIn(0, 255)
            return Color.argb(alpha, channel, channel, channel)
        }
        val ratio = adjustedLuma / originalLuma
        return Color.argb(
            alpha,
            (red * ratio).roundToInt().coerceIn(0, 255),
            (green * ratio).roundToInt().coerceIn(0, 255),
            (blue * ratio).roundToInt().coerceIn(0, 255),
        )
    }

    private fun gamma(channel: Int, value: Float): Int =
        (255f * (channel / 255f).pow(value)).roundToInt().coerceIn(0, 255)

    private fun luminance(color: Int): Float = rgbLuminance(
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun rgbLuminance(red: Int, green: Int, blue: Int): Float =
        (0.2126f * red + 0.7152f * green + 0.0722f * blue) / 255f

    private companion object {
        const val MAX_SAMPLE_DIMENSION = 384
        const val MAX_AUTO_RECIPES = 8
        const val MAX_VARIANTS = 12
        const val MAX_ROTATION_VARIANTS = 2
        const val LOW_PERCENTILE = 0.05f
        const val HIGH_PERCENTILE = 0.95f
        const val LOW_CONTRAST_RANGE = 0.58f
        const val VERY_LOW_CONTRAST_RANGE = 0.36f
        const val SOFT_TEXT_DETAIL = 0.045f
        const val NOISY_DETAIL = 0.18f
        const val UNEVEN_LIGHTING_THRESHOLD = 0.2f
        const val UNEVEN_GRID = 3
        const val GLARE_CLIP_FRACTION = 0.05f
        const val MAX_CLIPPED_FOR_ENHANCEMENT = 0.12f
        const val CLIPPED_DARK_THRESHOLD = 3
        const val CLIPPED_BRIGHT_THRESHOLD = 252
        const val COLOR_DIFFERENCE_THRESHOLD = 24
        const val DARK_MEAN_THRESHOLD = 0.34f
        const val BRIGHT_MEAN_THRESHOLD = 0.76f
        const val ORIENTATION_PREVIEW_DIMENSION = 256f
        const val MAX_ORIENTATION_PIXELS = 40_000
        const val MAX_ORIENTATION_POINTS = 30_000
        const val MIN_ORIENTATION_POINTS = 24
        const val ORIENTATION_STEP_DEGREES = 3
        const val MIN_ROTATION_ANGLE = 3f
        const val ROTATION_IMPROVEMENT = 1.04f
        const val SMALL_ROTATION_LIMIT_DEGREES = 10f
        const val SMALL_ROTATION_IMPROVEMENT = 1.075f
        const val SECOND_ANGLE_RATIO = 0.74f
        const val PEAK_SEPARATION_DEGREES = 8f
        const val REFINEMENT_RANGE_DEGREES = 2
        const val SAUVOLA_K = 0.34f
        const val SAUVOLA_WINDOW_DIVISOR = 48
        const val STRONG_DENOISE_THRESHOLD = 0.65f
        const val CLAHE_TILES = 8
        const val TOPHAT_WINDOW_DIVISOR = 90
        const val FLAT_FIELD_PREVIEW = 256f
        const val GAUSSIAN_KERNEL_SUM = 16
        const val MIN_UPSCALE_EFFECT = 1.06f
        const val MAX_UPSCALE_STAGE = 3f
        const val MAX_UPSCALED_PIXELS = 12_000_000L
        const val AUTO_UPSCALE_PIXELS = 8_000_000L
        const val EXPERIMENT_UPSCALE_PIXELS = 10_000_000L
        val GAUSSIAN_KERNEL = intArrayOf(1, 4, 6, 4, 1)
    }
}
