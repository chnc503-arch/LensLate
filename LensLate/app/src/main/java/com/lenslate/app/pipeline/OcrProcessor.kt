package com.lenslate.app.pipeline

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import com.lenslate.app.model.AffineTransform2D
import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.ImageQualityEstimate
import com.lenslate.app.model.Point2D
import com.lenslate.app.model.PreprocessingCandidate
import com.lenslate.app.model.PreprocessingSummary
import com.lenslate.app.model.ScanDocument
import com.lenslate.app.model.ScanImageInfo
import com.lenslate.app.model.ScanMode
import com.lenslate.app.model.TextAppearanceEstimate
import com.lenslate.app.model.TextBlock
import com.lenslate.app.model.TextElementRecord
import com.lenslate.app.model.TextLineRecord
import com.lenslate.app.model.TextRegionRecord
import com.lenslate.app.model.TextSymbolRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

enum class OcrEngineChoice { AUTO, LATIN, JAPANESE, CYRILLIC }

/** One preprocessing hypothesis measured in the lab. */
data class OcrExperimentResult(
    val engine: OcrEngineChoice,
    val variantName: String,
    val regionCount: Int,
    val averageConfidence: Float?,
    val text: String,
    val durationMs: Long,
    val error: String? = null,
    val variantSummary: String = "",
    val breakdown: OcrScoreBreakdown? = null,
    val droppedRegions: Int = 0,
    val dropReasons: Map<String, Int> = emptyMap(),
) {
    val score: Float
        get() = breakdown?.total ?: (averageConfidence ?: if (regionCount > 0) 0.35f else 0f) *
            (0.7f + 0.3f * (regionCount.coerceAtMost(8) / 8f))
}

/**
 * Распознаёт текст на изображении.
 *
 * Латинские и японские документы обрабатываются ML Kit Text Recognition v2
 * (несколько вариантов предобработки + слияние результатов), кириллица —
 * локальным Tesseract. Вся геометрия регионов хранится в координатах
 * working-bitmap, чтобы анализ стиля, инпейнтинг и рендеринг работали без
 * пересчётов и расхождений.
 */
@Singleton
class OcrProcessor @Inject constructor(
    private val tesseractEngine: TesseractOcrEngine,
    private val styleAnalyzer: StyleAnalyzer,
    private val documentIntelligence: DocumentIntelligence,
) {

    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val japaneseRecognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build(),
    )

    private val preprocessor = OcrPreprocessor()
    private val regionMerger = OcrRegionMerger()
    private val regionSanitizer = OcrRegionSanitizer()

    /** Compatibility adapter for existing pipeline callers. */
    suspend fun process(
        bitmap: Bitmap,
        useJapanese: Boolean = false,
        useCyrillic: Boolean = false,
        experimentMode: Boolean = false,
        scanMode: ScanMode = ScanMode.DETAILED,
    ): List<TextBlock> =
        processDetailed(
            bitmap = bitmap,
            imageInfo = ScanImageInfo.identity(bitmap.width, bitmap.height),
            useJapanese = useJapanese,
            useCyrillic = useCyrillic,
            experimentMode = experimentMode,
            scanMode = scanMode,
        ).regions.map { it.toLegacyBlock() }

    suspend fun processDetailed(
        bitmap: Bitmap,
        imageInfo: ScanImageInfo = ScanImageInfo.identity(bitmap.width, bitmap.height),
        useJapanese: Boolean = false,
        useCyrillic: Boolean = false,
        experimentMode: Boolean = false,
        scanMode: ScanMode = ScanMode.DETAILED,
        autoDetect: Boolean = false,
        forcedEngine: OcrEngineChoice? = null,
        forcedVariantName: String? = null,
        aggressiveSanitizer: Boolean = false,
        protectEmbeddedImages: Boolean = true,
    ): ScanDocument {
        require(imageInfo.workingWidth == bitmap.width && imageInfo.workingHeight == bitmap.height) {
            "Image metadata does not match the OCR bitmap dimensions."
        }
        val plan = when {
            forcedVariantName != null -> preprocessor.planForced(bitmap, forcedVariantName)
            experimentMode -> preprocessor.planExperiments(bitmap)
            else -> preprocessor.plan(bitmap)
        }
        val engine = forcedEngine ?: when {
            autoDetect -> OcrEngineChoice.AUTO
            useCyrillic -> OcrEngineChoice.CYRILLIC
            useJapanese -> OcrEngineChoice.JAPANESE
            else -> OcrEngineChoice.LATIN
        }
        val strictVariant = forcedVariantName != null
        val document = when (engine) {
            OcrEngineChoice.AUTO -> processAuto(
                bitmap = bitmap,
                imageInfo = imageInfo,
                plan = plan,
                experimentMode = experimentMode,
                scanMode = scanMode,
                strictCyrillic = strictVariant,
                aggressiveSanitizer = aggressiveSanitizer,
            )
            OcrEngineChoice.CYRILLIC -> processCyrillic(
                bitmap = bitmap,
                imageInfo = imageInfo,
                plan = plan,
                experimentMode = experimentMode,
                scanMode = scanMode,
                strictVariant = strictVariant,
                aggressiveSanitizer = aggressiveSanitizer,
            )
            OcrEngineChoice.JAPANESE -> processMlKit(
                bitmap = bitmap,
                imageInfo = imageInfo,
                plan = plan,
                useJapanese = true,
                experimentMode = experimentMode,
                scanMode = scanMode,
                aggressiveSanitizer = aggressiveSanitizer,
            )
            OcrEngineChoice.LATIN -> processMlKit(
                bitmap = bitmap,
                imageInfo = imageInfo,
                plan = plan,
                useJapanese = false,
                experimentMode = experimentMode,
                scanMode = scanMode,
                aggressiveSanitizer = aggressiveSanitizer,
            )
        }
        return documentIntelligence.enrich(document, bitmap, protectEmbeddedImages)
    }

    /**
     * Runs every preprocessing hypothesis (or the requested subset) through a single
     * recognizer and reports the raw reading of each, without merging. Used only by the
     * experimental tab so a variant can be measured and then applied deliberately.
     */
    suspend fun experimentVariants(
        bitmap: Bitmap,
        engine: OcrEngineChoice,
        variantNames: List<String>? = null,
        onResult: (OcrExperimentResult) -> Unit = {},
    ): List<OcrExperimentResult> {
        val plan = preprocessor.planExperiments(bitmap)
        val specs = plan.variants.filter { variantNames == null || it.name in variantNames }
        val resolved = resolveExperimentEngine(bitmap, engine)
        val results = mutableListOf<OcrExperimentResult>()
        val candidates = mutableListOf<OcrScoreCandidate?>()
        specs.forEach { spec ->
            val started = SystemClock.elapsedRealtime()
            var candidate: OcrScoreCandidate? = null
            val result = try {
                val rawRegions = when (resolved) {
                    OcrEngineChoice.CYRILLIC -> recognizeTesseractVariant(bitmap, plan, spec)
                    OcrEngineChoice.JAPANESE -> recognizeMlKitVariant(
                        bitmap,
                        plan,
                        spec,
                        japaneseRecognizer,
                    )
                    else -> recognizeMlKitVariant(bitmap, plan, spec, latinRecognizer)
                }
                val sanitized = sanitizeRegions(rawRegions, bitmap, profileFor(resolved))
                val regions = sanitized.regions
                candidate = scoreCandidate(regions)
                experimentResult(
                    engine = resolved,
                    spec = spec,
                    regions = regions,
                    durationMs = SystemClock.elapsedRealtime() - started,
                    breakdown = OcrQualityScorer.evaluate(
                        candidate,
                        candidates.filterNotNull(),
                    ),
                ).copy(
                    droppedRegions = sanitized.dropped.size,
                    dropReasons = sanitized.reasons,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                OcrExperimentResult(
                    engine = resolved,
                    variantName = spec.name,
                    regionCount = 0,
                    averageConfidence = null,
                    text = "",
                    durationMs = SystemClock.elapsedRealtime() - started,
                    error = exception.localizedMessage ?: "Ошибка распознавания",
                    variantSummary = spec.description,
                )
            }
            candidates += candidate
            results += result
            onResult(result)
        }
        // Cross-variant consensus is only complete after every recipe has run,
        // so the streamed previews are re-scored once here.
        val available = candidates.filterNotNull()
        return results.mapIndexed { index, result ->
            val candidate = candidates.getOrNull(index) ?: return@mapIndexed result
            val others = available.filter { it !== candidate }
            result.copy(breakdown = OcrQualityScorer.evaluate(candidate, others))
        }
    }

    fun experimentVariantNames(bitmap: Bitmap): List<String> =
        preprocessor.planExperiments(bitmap).variants.map { it.name }

    fun preprocessingPreview(bitmap: Bitmap, variantName: String): Bitmap? {
        val plan = preprocessor.plan(bitmap)
        val spec = preprocessor.planExperiments(bitmap)
            .variants
            .firstOrNull { it.name == variantName }
            ?: return null
        val enhanced = preprocessor.createVariant(bitmap, plan, spec)
        return if (enhanced === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else enhanced
    }

    private suspend fun resolveExperimentEngine(
        bitmap: Bitmap,
        requested: OcrEngineChoice,
    ): OcrEngineChoice {
        if (requested != OcrEngineChoice.AUTO) return requested
        val fingerprint = runCatching {
            tesseractEngine.recognize(bitmap, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
        }.getOrNull()
        if (!fingerprint.isNullOrEmpty() && containsEnoughCyrillic(fingerprint)) {
            return OcrEngineChoice.CYRILLIC
        }
        val base = preprocessor.plan(bitmap)
        val latin = runCatching {
            recognizeMlKitVariant(bitmap, base, base.variants.first(), latinRecognizer)
        }.getOrNull()
        return if (latin != null && latin.any { containsKana(it.text) }) {
            OcrEngineChoice.JAPANESE
        } else {
            OcrEngineChoice.LATIN
        }
    }

    private suspend fun recognizeTesseractVariant(
        bitmap: Bitmap,
        plan: PreprocessingPlan,
        spec: OcrVariantSpec,
    ): List<TextRegionRecord> {
        var input: Bitmap? = null
        return try {
            val prepared = preprocessor.createInput(bitmap, plan, spec)
            input = prepared.bitmap
            OcrGeometryMapper.mapRegions(
                regions = tesseractEngine.recognize(input, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT),
                transform = prepared.inputToWorking,
            )
        } finally {
            if (input != null && input !== bitmap && !input.isRecycled) input.recycle()
        }
    }

    private suspend fun recognizeMlKitVariant(
        bitmap: Bitmap,
        plan: PreprocessingPlan,
        spec: OcrVariantSpec,
        recognizer: TextRecognizer,
    ): List<TextRegionRecord> {
        var input: Bitmap? = null
        return try {
            val prepared = preprocessor.createInput(bitmap, plan, spec)
            input = prepared.bitmap
            val visionText = recognizer.process(InputImage.fromBitmap(input, 0)).await()
            extractRegions(
                text = visionText,
                inputToWorking = prepared.inputToWorking,
                angleOffsetDegrees = -spec.rotationDegrees,
            )
        } finally {
            if (input != null && input !== bitmap && !input.isRecycled) input.recycle()
        }
    }

    private fun experimentResult(
        engine: OcrEngineChoice,
        spec: OcrVariantSpec,
        regions: List<TextRegionRecord>,
        durationMs: Long,
        breakdown: OcrScoreBreakdown? = null,
    ): OcrExperimentResult {
        val confidences = regions.mapNotNull { it.confidence }
        return OcrExperimentResult(
            engine = engine,
            variantName = spec.name,
            regionCount = regions.size,
            averageConfidence = confidences.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            text = regions.joinToString("\n") { it.text },
            durationMs = durationMs,
            variantSummary = spec.description,
            breakdown = breakdown,
        )
    }

    private fun scoreCandidate(regions: List<TextRegionRecord>): OcrScoreCandidate {
        val confidences = regions.mapNotNull { it.confidence }
        return OcrScoreCandidate(
            text = regions.joinToString("\n") { it.text },
            averageConfidence = confidences.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            regions = regions.map { OcrRegionMetric(it.text, it.bounds) },
        )
    }

    /** Text plausibility first, then ink support; used by both the scanner and the lab. */
    private fun sanitizeRegions(
        regions: List<TextRegionRecord>,
        bitmap: Bitmap?,
        profile: OcrSanitizerProfile,
        aggressive: Boolean = false,
    ): OcrSanitizerResult {
        val textResult = regionSanitizer.sanitizeText(regions, profile, aggressive)
        if (bitmap == null) return textResult
        val inkResult = regionSanitizer.sanitizeInk(textResult.regions, bitmap, profile, aggressive)
        return OcrSanitizerResult(
            regions = inkResult.regions,
            dropped = textResult.dropped + inkResult.dropped,
        )
    }

    private fun profileFor(engine: OcrEngineChoice): OcrSanitizerProfile = when (engine) {
        OcrEngineChoice.CYRILLIC -> OcrSanitizerProfile.TESSERACT
        else -> OcrSanitizerProfile.NEURAL
    }

    private suspend fun processAuto(
        bitmap: Bitmap,
        imageInfo: ScanImageInfo,
        plan: PreprocessingPlan,
        experimentMode: Boolean,
        scanMode: ScanMode,
        strictCyrillic: Boolean = false,
        aggressiveSanitizer: Boolean = false,
    ): ScanDocument {
        val fingerprint = try {
            tesseractEngine.recognize(bitmap, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
        if (!fingerprint.isNullOrEmpty() && containsEnoughCyrillic(fingerprint)) {
            val passes = try {
                recognizeCyrillicPasses(
                    bitmap,
                    plan,
                    experimentMode,
                    seedRegions = fingerprint,
                    strictVariant = strictCyrillic,
                    aggressive = aggressiveSanitizer,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                listOf(OcrPass(CYRILLIC_ORIGINAL_PASS, fingerprint))
            }
            return documentFromCyrillicPasses(
                passes,
                bitmap,
                imageInfo,
                plan,
                experimentMode,
                scanMode,
                aggressiveSanitizer,
            )
        }
        // A cheap probe decides the script before paying for every enhancement
        // recipe: rotations and the untouched image need no pixel passes.
        val probeVariants = plan.variants
            .filter { it.operation == PreprocessOperation.ORIGINAL }
            .ifEmpty { listOf(plan.variants.first()) }
        val japaneseProbe = processMlKit(
            bitmap = bitmap,
            imageInfo = imageInfo,
            plan = plan.copy(variants = probeVariants),
            useJapanese = true,
            experimentMode = false,
            scanMode = scanMode,
        )
        if (japaneseProbe.regions.any { containsKana(it.text) }) {
            return processMlKit(
                bitmap = bitmap,
                imageInfo = imageInfo,
                plan = plan,
                useJapanese = true,
                experimentMode = experimentMode,
                scanMode = scanMode,
                aggressiveSanitizer = aggressiveSanitizer,
            )
        }
        val latin = processMlKit(
            bitmap = bitmap,
            imageInfo = imageInfo,
            plan = plan,
            useJapanese = false,
            experimentMode = experimentMode,
            scanMode = scanMode,
            aggressiveSanitizer = aggressiveSanitizer,
        )
        return if (latin.regions.isNotEmpty()) latin else japaneseProbe
    }

    private suspend fun processCyrillic(
        bitmap: Bitmap,
        imageInfo: ScanImageInfo,
        plan: PreprocessingPlan,
        experimentMode: Boolean,
        scanMode: ScanMode,
        strictVariant: Boolean = false,
        aggressiveSanitizer: Boolean = false,
    ): ScanDocument {
        val passes = try {
            recognizeCyrillicPasses(
                bitmap,
                plan,
                experimentMode,
                strictVariant = strictVariant,
                aggressive = aggressiveSanitizer,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // A Latin fallback would silently return transliterated garbage for Russian
            // documents, so a missing local model is reported as an actionable error.
            throw IllegalStateException(
                "Не удалось запустить OCR-модель русского языка (Tesseract). " +
                    "Переустановите приложение.",
                exception,
            )
        }
        return documentFromCyrillicPasses(
            passes,
            bitmap,
            imageInfo,
            plan,
            experimentMode,
            scanMode,
            aggressiveSanitizer,
        )
    }

    /** Runs the Russian model over several layout and enhancement hypotheses and merges them. */
    private suspend fun recognizeCyrillicPasses(
        bitmap: Bitmap,
        plan: PreprocessingPlan,
        experimentMode: Boolean,
        seedRegions: List<TextRegionRecord>? = null,
        strictVariant: Boolean = false,
        aggressive: Boolean = false,
    ): List<OcrPass> {
        if (strictVariant) {
            val spec = plan.variants.first()
            var input: Bitmap? = null
            return try {
                val prepared = preprocessor.createInput(bitmap, plan, spec)
                input = prepared.bitmap
                val regions = OcrGeometryMapper.mapRegions(
                    regions = tesseractEngine.recognize(input, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT),
                    transform = prepared.inputToWorking,
                )
                val cleaned = regionSanitizer
                    .sanitizeText(regions, OcrSanitizerProfile.TESSERACT, aggressive)
                    .regions
                listOf(OcrPass("tesseract-${spec.name}", cleaned))
                    .filter { it.regions.isNotEmpty() }
            } finally {
                if (input != null && input !== bitmap && !input.isRecycled) input.recycle()
            }
        }
        val passes = mutableListOf<OcrPass>()
        if (seedRegions != null) {
            passes += OcrPass(CYRILLIC_ORIGINAL_PASS, seedRegions)
        } else {
            passes += OcrPass(
                CYRILLIC_ORIGINAL_PASS,
                tesseractEngine.recognize(bitmap, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT),
            )
        }
        // Sparse text keeps scattered labels apart; automatic layout reconstructs dense
        // multi-column pages where sparse segmentation breaks paragraphs into fragments.
        try {
            val autoRegions = tesseractEngine.recognize(bitmap, TessBaseAPI.PageSegMode.PSM_AUTO)
            if (autoRegions.isNotEmpty()) {
                passes += OcrPass(CYRILLIC_AUTO_PASS, autoRegions)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // The sparse reading remains usable when the layout hypothesis fails.
        }

        // Rotated inputs are essential for Cyrillic too: Tesseract tolerates only
        // a few degrees, so a skewed page would otherwise lose whole lines.
        // The lab intentionally runs every hypothesis; a normal scan keeps a cap.
        val variantLimit = if (experimentMode) Int.MAX_VALUE else MAX_CYRILLIC_PREPROCESS_PASSES
        val variants = plan.variants
            .filter { spec ->
                spec.operation != PreprocessOperation.ORIGINAL || spec.rotationDegrees != 0f
            }
            .sortedByDescending { spec -> spec.rotationDegrees != 0f }
            .take(variantLimit)
        variants.forEach { spec ->
            var input: Bitmap? = null
            try {
                val prepared = preprocessor.createInput(bitmap, plan, spec)
                input = prepared.bitmap
                val regions = OcrGeometryMapper.mapRegions(
                    regions = tesseractEngine.recognize(input, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT),
                    transform = prepared.inputToWorking,
                )
                if (regions.isNotEmpty()) {
                    passes += OcrPass("tesseract-${spec.name}", regions)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // An optional enhancement variant must not discard the original reading.
            } finally {
                if (input != null && input !== bitmap && !input.isRecycled) input.recycle()
            }
        }
        // String-level filtering happens before scoring so invented words cannot
        // win consensus and pull honest readings into a merge with them.
        return passes
            .map { pass ->
                pass.copy(
                    regions = regionSanitizer
                        .sanitizeText(pass.regions, OcrSanitizerProfile.TESSERACT, aggressive)
                        .regions,
                )
            }
            .filter { it.regions.isNotEmpty() }
    }

    private fun documentFromCyrillicPasses(
        passes: List<OcrPass>,
        bitmap: Bitmap,
        imageInfo: ScanImageInfo,
        plan: PreprocessingPlan,
        experimentMode: Boolean,
        scanMode: ScanMode,
        aggressiveSanitizer: Boolean = false,
    ): ScanDocument {
        if (passes.isEmpty()) {
            return ScanDocument(
                image = imageInfo,
                regions = emptyList(),
                preprocessing = PreprocessingSummary(
                    quality = plan.quality.toEstimate(),
                    candidates = emptyList(),
                    selectedCandidate = "",
                ),
                scanMode = scanMode,
            )
        }
        val scores = scorePasses(passes)
        val selected = selectPass(passes, scores)
        val selectedScore = scores[selected.name]?.total ?: 0f
        val mergeCandidates = passes.flatMap { pass ->
            val passScore = scores[pass.name]?.total ?: 0f
            pass.regions
                .filter { region ->
                    pass === selected ||
                        (region.confidence?.let { it >= MIN_CYRILLIC_MERGE_CONFIDENCE } == true) ||
                        (region.confidence == null && passScore >= selectedScore + MIN_VARIANT_ADVANTAGE)
                }
                .map { region -> OcrRegionCandidate(pass.name, passScore, region) }
        }
        val merged = regionMerger.merge(mergeCandidates)
        val marked = merged.regions.map { region ->
            markGeometry(region).copy(preprocessingVariant = TESSERACT_PASS)
        }
        val sanitized = sanitizeRegions(
            marked,
            bitmap,
            OcrSanitizerProfile.TESSERACT,
            aggressiveSanitizer,
        )
        val regions = attachAppearance(sanitized.regions, bitmap)
        val contributingPasses = merged.contributingPassNames.ifEmpty { setOf(selected.name) }
        val summaries = passes.map { pass ->
            PreprocessingCandidate(
                name = pass.name,
                averageOcrConfidence = pass.averageConfidence,
                agreementWithOtherPasses = scores[pass.name]?.consensus ?: 0f,
                recognizedText = pass.text,
                score = scores[pass.name]?.total ?: 0f,
                selected = pass.name in contributingPasses,
            )
        }
        return ScanDocument(
            image = imageInfo,
            regions = regions,
            preprocessing = PreprocessingSummary(
                quality = plan.quality.toEstimate(),
                candidates = summaries,
                selectedCandidate = if (contributingPasses.size == 1) {
                    contributingPasses.first()
                } else {
                    CYRILLIC_COMBINED_PASS
                },
            ),
            experimentResults = if (experimentMode) summaries else emptyList(),
            scanMode = scanMode,
        )
    }

    private suspend fun processMlKit(
        bitmap: Bitmap,
        imageInfo: ScanImageInfo,
        plan: PreprocessingPlan,
        useJapanese: Boolean,
        experimentMode: Boolean,
        scanMode: ScanMode,
        aggressiveSanitizer: Boolean = false,
    ): ScanDocument {
        val recognizer = if (useJapanese) japaneseRecognizer else latinRecognizer
        val passes = mutableListOf<OcrPass>()

        plan.variants.forEachIndexed { index, variant ->
            var input: Bitmap? = null
            try {
                val prepared = preprocessor.createInput(bitmap, plan, variant)
                input = prepared.bitmap
                val visionText = recognizer.process(InputImage.fromBitmap(input, 0)).await()
                passes += OcrPass(
                    name = variant.name,
                    regions = extractRegions(
                        text = visionText,
                        inputToWorking = prepared.inputToWorking,
                        angleOffsetDegrees = -variant.rotationDegrees,
                    ),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // The baseline failure is actionable; a failed optional variant is not.
                if (index == 0) throw exception
            } finally {
                if (input != null && input !== bitmap && !input.isRecycled) input.recycle()
            }
        }

        if (passes.all { it.regions.isEmpty() }) {
            var fallbackInput: Bitmap? = null
            try {
                val fallback = preprocessor.createInput(
                    bitmap,
                    plan,
                    OcrVariantSpec("rotate-180-degrees", PreprocessOperation.ORIGINAL, rotationDegrees = 180f),
                )
                fallbackInput = fallback.bitmap
                val visionText = recognizer.process(InputImage.fromBitmap(fallbackInput, 0)).await()
                passes += OcrPass(
                    name = "rotate-180-degrees",
                    regions = extractRegions(visionText, fallback.inputToWorking, 180f),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // Keep the baseline empty result when the upside-down fallback is unavailable.
            } finally {
                if (fallbackInput != null && fallbackInput !== bitmap && !fallbackInput.isRecycled) {
                    fallbackInput.recycle()
                }
            }
        }

        if (useJapanese) {
            // The Japanese recognizer sometimes drops Latin digits; a Latin pass on
            // the unmodified variant recovers them so the merger can keep numeric text.
            val originalVariant = plan.variants.first()
            var assistInput: Bitmap? = null
            try {
                val prepared = preprocessor.createInput(bitmap, plan, originalVariant)
                assistInput = prepared.bitmap
                val visionText = latinRecognizer.process(InputImage.fromBitmap(assistInput, 0)).await()
                passes += OcrPass(
                    name = LATIN_DIGIT_ASSIST_PASS,
                    regions = extractRegions(visionText, prepared.inputToWorking),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // Digits remain recoverable through the primary recognizer when the assist fails.
            } finally {
                if (assistInput != null && assistInput !== bitmap && !assistInput.isRecycled) {
                    assistInput.recycle()
                }
            }
        }

        if (passes.isEmpty()) {
            passes += OcrPass("original", emptyList())
        }
        val sanitizedPasses = passes.map { pass ->
            pass.copy(
                regions = regionSanitizer
                    .sanitizeText(pass.regions, OcrSanitizerProfile.NEURAL, aggressiveSanitizer)
                    .regions,
            )
        }
        val scores = scorePasses(sanitizedPasses)
        val selected = selectPass(sanitizedPasses, scores)
        val baselineScore = scores["original"]?.total ?: 0f
        val mergeCandidates = sanitizedPasses.flatMap { pass ->
            val passScore = scores[pass.name]?.total ?: 0f
            pass.regions
                .filter { region ->
                    val hasDigits = region.text.any(Char::isDigit)
                    if (pass.name == LATIN_DIGIT_ASSIST_PASS && pass !== selected) {
                        // The assist pass exists only to recover digits the primary recognizer
                        // missed; its non-digit readings are noise on scripts the Latin model
                        // does not cover.
                        hasDigits && (
                            region.confidence?.let { it >= MIN_DIGIT_REGION_CONFIDENCE }
                                ?: (passScore >= baselineScore + MIN_VARIANT_ADVANTAGE)
                            )
                    } else {
                        pass === selected ||
                            pass.name == "original" ||
                            (region.confidence?.let {
                                it >= if (hasDigits) MIN_DIGIT_REGION_CONFIDENCE else MIN_MERGE_REGION_CONFIDENCE
                            }
                                ?: (passScore >= baselineScore + MIN_VARIANT_ADVANTAGE))
                    }
                }
                .map { region -> OcrRegionCandidate(pass.name, passScore, region) }
        }
        val merged = regionMerger.merge(mergeCandidates)
        var refined = refineFormulaRegions(merged.regions, bitmap, useJapanese)
        refined = enhanceLowConfidenceRegions(refined, bitmap, useJapanese)
        refined = refined.map(::markGeometry)
        val sanitized = sanitizeRegions(
            refined,
            bitmap,
            OcrSanitizerProfile.NEURAL,
            aggressiveSanitizer,
        )
        val contributingPasses = merged.contributingPassNames.ifEmpty { setOf(selected.name) }
        val summaries = sanitizedPasses.map { pass ->
            PreprocessingCandidate(
                name = pass.name,
                averageOcrConfidence = pass.averageConfidence,
                agreementWithOtherPasses = scores[pass.name]?.consensus ?: 0f,
                recognizedText = pass.text,
                score = scores[pass.name]?.total ?: 0f,
                selected = pass.name in contributingPasses,
            )
        }
        val selectedCandidate = contributingPasses.singleOrNull() ?: "combined"
        val experimentResults = if (experimentMode) summaries else emptyList()
        return ScanDocument(
            image = imageInfo,
            regions = attachAppearance(sanitized.regions, bitmap),
            preprocessing = PreprocessingSummary(
                quality = plan.quality.toEstimate(),
                candidates = summaries,
                selectedCandidate = selectedCandidate,
            ),
            experimentResults = experimentResults,
            scanMode = scanMode,
        )
    }

    private fun scorePasses(passes: List<OcrPass>): Map<String, OcrScoreBreakdown> {
        val candidates = passes.map { it.candidate }
        return passes.mapIndexed { index, pass ->
            pass.name to OcrQualityScorer.evaluate(
                candidate = candidates[index],
                others = candidates.filterIndexed { otherIndex, _ -> otherIndex != index },
            )
        }.toMap()
    }

    private fun selectPass(
        passes: List<OcrPass>,
        scores: Map<String, OcrScoreBreakdown>,
    ): OcrPass = passes.maxByOrNull { scores[it.name]?.total ?: 0f } ?: passes.first()

    private fun extractRegions(
        text: Text,
        inputToWorking: AffineTransform2D,
        angleOffsetDegrees: Float = 0f,
    ): List<TextRegionRecord> =
        text.textBlocks.asSequence()
            .filter { it.text.isNotBlank() && it.boundingBox != null }
            .mapIndexed { index, block ->
                val blockGeometry = geometry(block.boundingBox, block.cornerPoints, inputToWorking)
                val lines = block.lines.map { line ->
                    val lineGeometry = geometry(line.boundingBox, line.cornerPoints, inputToWorking)
                    val elements = line.elements.map { element ->
                        val elementGeometry = geometry(element.boundingBox, element.cornerPoints, inputToWorking)
                        val symbols = element.symbols.map { symbol ->
                            val symbolGeometry = geometry(symbol.boundingBox, symbol.cornerPoints, inputToWorking)
                            TextSymbolRecord(
                                text = symbol.text,
                                bounds = symbolGeometry.first,
                                polygon = symbolGeometry.second,
                                confidence = symbol.confidence,
                            )
                        }
                        TextElementRecord(
                            text = element.text,
                            bounds = elementGeometry.first,
                            polygon = elementGeometry.second,
                            confidence = element.confidence,
                            symbols = symbols,
                        )
                    }
                    TextLineRecord(
                        text = line.text,
                        bounds = lineGeometry.first,
                        polygon = lineGeometry.second,
                        angleDegrees = OcrGeometryMapper.normalizeAngle(line.angle + angleOffsetDegrees),
                        confidence = line.confidence,
                        elements = elements,
                    )
                }
                val elementConfidences = lines.flatMap { it.elements }.mapNotNull { it.confidence }
                TextRegionRecord(
                    id = index + 1,
                    text = block.text.trim(),
                    bounds = blockGeometry.first ?: error("OCR block has no bounds."),
                    polygon = blockGeometry.second,
                    script = detectScript(block.text),
                    readingOrder = index,
                    confidence = elementConfidences.averageOrNull(),
                    overallConfidence = elementConfidences.averageOrNull(),
                    lines = lines,
                )
            }
            .toList()

    /** Анализирует цвет, кегль, насыщенность, наклон и межстрочные параметры в working-координатах. */
    private fun attachAppearance(
        regions: List<TextRegionRecord>,
        bitmap: Bitmap,
    ): List<TextRegionRecord> = regions.map { region ->
        val analysis = styleAnalyzer.analyze(bitmap, region)
        val style = analysis.style
        val alternativeColors = analysis.characterColors.distinct().takeIf { it.size > 1 }
        region.copy(
            characterColors = analysis.characterColors,
            characterStyles = analysis.characterStyles,
            appearance = TextAppearanceEstimate(
                foregroundArgb = style.color,
                localBackgroundArgb = style.backgroundColor,
                textHeightPx = style.sizePx,
                weight = if (style.isBold) "bold" else "regular",
                slantDegrees = style.slantDegrees,
                alignment = style.alignment,
                spacingPx = style.letterSpacingPx,
                lineSpacingPx = style.sizePx * style.lineSpacingMultiplier,
                likelyFontClass = style.fontFamily,
                fontClassConfidence = analysis.fontClassConfidence,
                alternativeColors = alternativeColors.orEmpty(),
                confidenceByAttribute = analysis.confidenceByAttribute,
                fontWeight = style.fontWeight,
                capHeightPx = style.capHeightPx,
                strokeWidthPx = style.strokeWidthPx,
                lineSpacingMultiplier = style.lineSpacingMultiplier,
                horizontalScale = style.horizontalScale,
                isUnderlined = style.isUnderlined,
                isStruckThrough = style.isStruckThrough,
                alpha = 1f,
                fontKey = style.fontKey.ifBlank { null },
                fontMatchScore = style.fontMatchScore,
            ),
        )
    }

    /** Re-recognizes formula-like regions from an upscaled crop; keeps the original reading unless refinement clearly wins. */
    private suspend fun refineFormulaRegions(
        regions: List<TextRegionRecord>,
        bitmap: Bitmap,
        useJapanese: Boolean,
    ): List<TextRegionRecord> = regions.map { region ->
        val confidence = region.confidence
        if (!FormulaDetection.looksLikeFormula(region.text) ||
            (confidence != null && confidence >= FORMULA_REFINEMENT_MAX_CONFIDENCE)
        ) {
            region
        } else {
            refineRegionText(
                region = region,
                bitmap = bitmap,
                useJapanese = useJapanese,
                targetHeightPx = FORMULA_TARGET_HEIGHT_PX,
                keepOnlyFormula = true,
            )
        }
    }

    /** Низкоуверенные регионы перераспознаём на апскейленном кадре, оставляя лучшее чтение. */
    private suspend fun enhanceLowConfidenceRegions(
        regions: List<TextRegionRecord>,
        bitmap: Bitmap,
        useJapanese: Boolean,
    ): List<TextRegionRecord> = regions.map { region ->
        val confidence = region.confidence ?: 1f
        if (confidence >= LOW_CONFIDENCE_THRESHOLD) {
            region
        } else {
            refineRegionText(
                region = region,
                bitmap = bitmap,
                useJapanese = useJapanese,
                targetHeightPx = ENHANCE_TARGET_HEIGHT_PX,
                keepOnlyFormula = false,
            )
        }
    }

    private suspend fun refineRegionText(
        region: TextRegionRecord,
        bitmap: Bitmap,
        useJapanese: Boolean,
        targetHeightPx: Float,
        keepOnlyFormula: Boolean,
    ): TextRegionRecord {
        val workingBounds = Bounds2D.fromPoints(region.polygon) ?: return region
        val cropRect = workingBounds.toRect()
        val paddingFraction = if (keepOnlyFormula) FORMULA_CROP_PADDING_FRACTION else LOW_CONFIDENCE_CROP_PADDING_FRACTION
        val minPadding = if (keepOnlyFormula) FORMULA_MIN_CROP_PADDING else LOW_CONFIDENCE_MIN_CROP_PADDING
        val padding = (maxOf(cropRect.width(), cropRect.height()) * paddingFraction)
            .toInt()
            .coerceAtLeast(minPadding)
        val left = (cropRect.left - padding).coerceIn(0, bitmap.width - 1)
        val top = (cropRect.top - padding).coerceIn(0, bitmap.height - 1)
        val right = (cropRect.right + padding).coerceIn(left + 1, bitmap.width)
        val bottom = (cropRect.bottom + padding).coerceIn(top + 1, bitmap.height)
        val crop = try {
            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        } catch (_: IllegalArgumentException) {
            return region
        }
        val scale = (targetHeightPx / crop.height.coerceAtLeast(1)).coerceIn(1f, REFINEMENT_MAX_UPSCALE)
        val scaled = if (scale > 1.05f) {
            Bitmap.createScaledBitmap(
                crop,
                (crop.width * scale).toInt().coerceAtLeast(1),
                (crop.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            crop
        }
        val recognizers = if (useJapanese) listOf(japaneseRecognizer, latinRecognizer) else listOf(latinRecognizer)
        var replacement: TextRegionRecord? = null
        try {
            recognizers.forEach { recognizer ->
                try {
                    val visionText = recognizer.process(InputImage.fromBitmap(scaled, 0)).await()
                    val refinedRegions = extractRegions(visionText, AffineTransform2D())
                    val text = refinedRegions.joinToString("\n") { it.text }.trim()
                    if (text.isBlank()) return@forEach
                    if (keepOnlyFormula && !FormulaDetection.looksLikeFormula(text)) return@forEach
                    val refinedConfidence = refinedRegions
                        .flatMap { it.lines }
                        .flatMap { it.elements }
                        .mapNotNull { it.confidence }
                        .takeIf { it.isNotEmpty() }
                        ?.average()
                        ?.toFloat()
                        ?: return@forEach
                    val current = replacement ?: region
                    val currentConfidence = current.confidence
                    if ((currentConfidence == null || refinedConfidence > currentConfidence) &&
                        refinedPreservesDigits(current.text, text)
                    ) {
                        replacement = current.copy(text = text, confidence = refinedConfidence)
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    // A failed refinement keeps the original reading.
                }
            }
        } finally {
            scaled.recycle()
            if (crop !== scaled) crop.recycle()
        }
        return replacement ?: region
    }

    /** Rejects refinements that would lose digits the original reading already found. */
    private fun refinedPreservesDigits(original: String, refined: String): Boolean {
        val expected = original.filter(Char::isDigit).groupingBy { it }.eachCount()
        if (expected.isEmpty()) return true
        val available = refined.filter(Char::isDigit).groupingBy { it }.eachCount()
        return expected.all { (digit, count) -> (available[digit] ?: 0) >= count }
    }

    private fun markGeometry(region: TextRegionRecord): TextRegionRecord {
        val curve = CurvedTextAnalyzer.analyze(region)
        return region.copy(
            isVertical = detectVertical(region),
            isCurved = curve != null,
            curve = curve,
            isFormula = FormulaDetection.isVerbatimFormula(region.text),
            overallConfidence = region.confidence,
        )
    }

    /** Вертикальный текст: высокий узкий блок, символы идут колонкой. */
    private fun detectVertical(region: TextRegionRecord): Boolean {
        val width = region.bounds.width.coerceAtLeast(1f)
        val height = region.bounds.height
        if (height <= 40f || height / width <= 2.2f) return false
        val lines = region.lines.filter { it.bounds != null }
        if (lines.size < 2) return true
        // Многоколоночная подпись тоже высокая, но её строки идут горизонтально.
        val averageAngle = lines.mapNotNull { it.angleDegrees }.averageOrNull() ?: 0f
        return abs(averageAngle) > 45f || lines.all { (it.bounds?.height ?: 0f) > (it.bounds?.width ?: 0f) * 1.4f }
    }

    /** Изогнутый текст: центры символов заметно отклоняются от прямой — параметры дуги считает CurvedTextAnalyzer. */
    private fun geometry(
        rect: Rect?,
        cornerPoints: Array<Point>?,
        transform: AffineTransform2D,
    ): Pair<Bounds2D?, List<Point2D>> {
        val sourcePoints = cornerPoints?.takeIf { it.size >= 3 }?.map {
            Point2D(it.x.toFloat(), it.y.toFloat())
        } ?: rect?.let(::rectCorners).orEmpty()
        val mapped = sourcePoints.map(transform::map)
        return Bounds2D.fromPoints(mapped) to mapped
    }

    private fun rectCorners(rect: Rect): List<Point2D> = listOf(
        Point2D(rect.left.toFloat(), rect.top.toFloat()),
        Point2D(rect.right.toFloat(), rect.top.toFloat()),
        Point2D(rect.right.toFloat(), rect.bottom.toFloat()),
        Point2D(rect.left.toFloat(), rect.bottom.toFloat()),
    )

    private fun detectScript(text: String): String? {
        var kana = 0
        var han = 0
        var cyrillic = 0
        var latin = 0
        text.codePoints().forEach { codePoint ->
            when {
                codePoint in 0x3040..0x30FF || codePoint in 0x31F0..0x31FF -> kana++
                codePoint in 0x3400..0x4DBF || codePoint in 0x4E00..0x9FFF -> han++
                codePoint in 0x0400..0x052F -> cyrillic++
                codePoint in 0x0041..0x005A ||
                    codePoint in 0x0061..0x007A ||
                    codePoint in 0x00C0..0x024F -> latin++
            }
        }
        return when {
            kana > 0 -> "Japanese"
            han > 0 && han >= latin && han >= cyrillic -> "Japanese"
            cyrillic >= 2 && cyrillic.toFloat() / (cyrillic + latin + han).coerceAtLeast(1) >= 0.12f ->
                "Cyrillic"
            latin > 0 -> "Latin"
            else -> null
        }
    }

    private fun containsKana(text: String): Boolean =
        text.codePoints().anyMatch { it in 0x3040..0x30FF || it in 0x31F0..0x31FF }

    private fun containsEnoughCyrillic(regions: List<TextRegionRecord>): Boolean {
        val text = regions.joinToString(" ") { it.text }
        var cyrillic = 0
        var letters = 0
        text.codePoints().forEach { codePoint ->
            if (Character.isLetter(codePoint)) {
                letters++
                if (codePoint in 0x0400..0x052F) cyrillic++
            }
        }
        if (cyrillic < MIN_CYRILLIC_LETTERS) return false
        return cyrillic.toFloat() / letters.coerceAtLeast(1) >= MIN_CYRILLIC_RATIO
    }

    private fun ImageQualityEstimate.toEstimate(): ImageQualityEstimate = this

    private fun List<Float>.averageOrNull(): Float? = takeIf { isNotEmpty() }?.average()?.toFloat()

    private data class OcrPass(
        val name: String,
        val regions: List<TextRegionRecord>,
    ) {
        val text: String get() = regions.joinToString("\n") { it.text }
        val averageConfidence: Float?
            get() = regions.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val candidate: OcrScoreCandidate
            get() = OcrScoreCandidate(
                text = text,
                averageConfidence = averageConfidence,
                regions = regions.map { OcrRegionMetric(it.text, it.bounds) },
            )
    }

    private companion object {
        const val MIN_VARIANT_ADVANTAGE = 0.045f
        const val MIN_MERGE_REGION_CONFIDENCE = 0.55f
        const val MIN_DIGIT_REGION_CONFIDENCE = 0.35f
        const val LOW_CONFIDENCE_THRESHOLD = 0.55f
        const val ENHANCE_TARGET_HEIGHT_PX = 128f
        const val REFINEMENT_MAX_UPSCALE = 4f
        const val LATIN_DIGIT_ASSIST_PASS = "latin-digit-assist"
        const val FORMULA_CROP_PADDING_FRACTION = 0.2f
        const val FORMULA_MIN_CROP_PADDING = 8
        const val FORMULA_TARGET_HEIGHT_PX = 96f
        const val FORMULA_REFINEMENT_MAX_CONFIDENCE = 0.9f
        const val LOW_CONFIDENCE_CROP_PADDING_FRACTION = 0.3f
        const val LOW_CONFIDENCE_MIN_CROP_PADDING = 6
        const val CURVATURE_DEVIATION_RATIO = 0.18f
        const val TESSERACT_PASS = "tesseract-rus"
        const val CYRILLIC_ORIGINAL_PASS = "tesseract-original-sparse"
        const val CYRILLIC_AUTO_PASS = "tesseract-original-auto"
        const val CYRILLIC_COMBINED_PASS = "tesseract-combined"
        // Deskew hypotheses are explicitly sorted to the front before this cap,
        // so a skewed page never loses its rotations to cheaper recipes.
        const val MAX_CYRILLIC_PREPROCESS_PASSES = 10
        const val MIN_CYRILLIC_MERGE_CONFIDENCE = 0.55f
        // A genuine Russian page read by the rus model is overwhelmingly Cyrillic.
        // A few lookalike letters on a Latin page must not steal the auto-detection.
        const val MIN_CYRILLIC_LETTERS = 6
        const val MIN_CYRILLIC_RATIO = 0.45f
    }

    fun close() {
        latinRecognizer.close()
        japaneseRecognizer.close()
        tesseractEngine.close()
    }
}
