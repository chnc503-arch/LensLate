package com.lenslate.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lenslate.app.model.Language
import com.lenslate.app.model.LanguagePackInfo
import com.lenslate.app.model.PipelineProgress
import com.lenslate.app.model.ScanDocument
import com.lenslate.app.model.ScanImageInfo
import com.lenslate.app.model.ScanMode
import com.lenslate.app.model.StepState
import com.lenslate.app.model.TranslationPair
import com.lenslate.app.pipeline.BackgroundRestorer
import com.lenslate.app.pipeline.LanguageDetector
import com.lenslate.app.pipeline.OcrEngineChoice
import com.lenslate.app.pipeline.OcrExperimentResult
import com.lenslate.app.pipeline.OcrProcessor
import com.lenslate.app.pipeline.StyleStressLab
import com.lenslate.app.pipeline.TextRenderer
import com.lenslate.app.pipeline.TranslationEngine
import com.lenslate.app.util.BitmapUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.roundToInt

enum class ScanPhase { IDLE, LOADING, PROCESSING, DONE, ERROR }

enum class PreviewMode { ORIGINAL, TRANSLATED, COMPARE }

data class ToastMessage(val id: Long, val text: String)

/** OCR hypothesis chosen in the experimental tab and applied to the regular scan. */
data class AppliedExperiment(
    val engine: OcrEngineChoice,
    val variantName: String,
)

data class ScanUiState(
    val phase: ScanPhase = ScanPhase.IDLE,
    val sourceName: String? = null,
    val imageInfo: ScanImageInfo? = null,
    val original: Bitmap? = null,
    val rendered: Bitmap? = null,
    val document: ScanDocument? = null,
    val progress: PipelineProgress = PipelineProgress(),
    /** null means auto detection. */
    val fromLanguage: Language? = Language.RUSSIAN,
    val toLanguage: Language = Language.ENGLISH,
    val detectedLanguage: Language? = null,
    val packs: List<LanguagePackInfo> = emptyList(),
    val refreshingModels: Boolean = true,
    val downloadingLanguages: Set<Language> = emptySet(),
    val message: ToastMessage? = null,
    val error: String? = null,
    val labRunning: Boolean = false,
    val labResults: List<OcrExperimentResult> = emptyList(),
    val labPreview: Bitmap? = null,
    val labPreviewName: String? = null,
    val appliedExperiment: AppliedExperiment? = null,
    /** Synthetic overlay-quality stress report; independent of the current photo. */
    val styleStress: StyleStressLab.Report? = null,
    val styleStressRunning: Boolean = false,
    /** Keeps OCR readings that sit inside photos/illustrations untouched. */
    val protectEmbeddedImages: Boolean = true,
    /** Experimental: stricter removal of OCR hallucination on texture. */
    val aggressiveSanitizer: Boolean = false,
) {
    val isBusy: Boolean
        get() = phase == ScanPhase.LOADING || phase == ScanPhase.PROCESSING || labRunning
    val hasImage: Boolean get() = original != null
    val hasTranslation: Boolean get() = rendered != null
    val regions get() = document?.regions.orEmpty()
    val averageConfidence: Float?
        get() = regions.mapNotNull { it.overallConfidence ?: it.confidence }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toFloat()
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ocrProcessor: OcrProcessor,
    private val languageDetector: LanguageDetector,
    private val translationEngine: TranslationEngine,
    private val backgroundRestorer: BackgroundRestorer,
    private val textRenderer: TextRenderer,
) : ViewModel() {

    private var _state by mutableStateOf(ScanUiState())
    val state: ScanUiState get() = _state

    private var pipelineJob: Job? = null
    private var loadJob: Job? = null
    private var modelsJob: Job? = null
    private var labJob: Job? = null
    private var styleStressJob: Job? = null
    private val stateLock = Any()
    private val messageIds = AtomicLong()

    init {
        refreshModelPacks(showLoading = true)
    }

    fun loadImage(uri: Uri) {
        pipelineJob?.cancel()
        loadJob?.cancel()
        labJob?.cancel()
        loadJob = viewModelScope.launch {
            mutate {
                it.copy(
                    phase = ScanPhase.LOADING,
                    sourceName = null,
                    imageInfo = null,
                    original = null,
                    rendered = null,
                    document = null,
                    detectedLanguage = null,
                    progress = PipelineProgress(),
                    error = null,
                    labRunning = false,
                    labResults = emptyList(),
                    labPreview = null,
                    labPreviewName = null,
                    appliedExperiment = null,
                )
            }
            val decoded = try {
                withContext(Dispatchers.IO) { BitmapUtils.decodeWithMetadata(context, uri) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutate {
                    it.copy(
                        phase = ScanPhase.ERROR,
                        error = "Не удалось открыть изображение. Поддерживаются PNG, JPG, WEBP и BMP.",
                    )
                }
                notify("Не удалось открыть изображение")
                return@launch
            }
            val name = withContext(Dispatchers.IO) { queryDisplayName(uri) }
            mutate {
                it.copy(
                    phase = ScanPhase.IDLE,
                    original = decoded.bitmap,
                    imageInfo = decoded.imageInfo,
                    sourceName = name,
                    error = null,
                )
            }
            notify("Изображение загружено")
        }
    }

    fun clear() {
        pipelineJob?.cancel()
        loadJob?.cancel()
        labJob?.cancel()
        mutate { current ->
            ScanUiState(
                fromLanguage = current.fromLanguage,
                toLanguage = current.toLanguage,
                packs = current.packs,
                downloadingLanguages = current.downloadingLanguages,
                refreshingModels = false,
                protectEmbeddedImages = current.protectEmbeddedImages,
                aggressiveSanitizer = current.aggressiveSanitizer,
            )
        }
        notify("Изображение удалено")
    }

    fun setProtectEmbeddedImages(enabled: Boolean) {
        if (state.isBusy || state.protectEmbeddedImages == enabled) return
        pipelineJob?.cancel()
        mutate { current ->
            val document = current.document
            val reasons = document?.analysis?.protectionReasons.orEmpty()
            val regions = document?.regions?.map { region ->
                val reason = if (enabled) reasons[region.id] else null
                region.copy(
                    translation = null,
                    translationConfidence = null,
                    isProtected = reason != null,
                    protectionReason = reason,
                )
            }
            current.copy(
                protectEmbeddedImages = enabled,
                document = document?.copy(regions = regions.orEmpty()),
                rendered = null,
                detectedLanguage = null,
                progress = PipelineProgress(),
                phase = if (current.original != null) ScanPhase.IDLE else current.phase,
                error = null,
            )
        }
        notify(
            if (enabled) {
                "Текст внутри изображений будет сохранён"
            } else {
                "Все найденные блоки будут переведены"
            },
        )
    }

    fun setAggressiveSanitizer(enabled: Boolean) {
        if (state.isBusy || state.aggressiveSanitizer == enabled) return
        invalidateTranslation()
        mutate { it.copy(aggressiveSanitizer = enabled) }
        notify(
            if (enabled) {
                "Агрессивная очистка включена для следующего скана"
            } else {
                "Агрессивная очистка выключена"
            },
        )
    }

    fun previewProtectionMap() {
        val snapshot = state
        val bitmap = snapshot.original ?: return
        val document = snapshot.document
        val analysis = document?.analysis
        if (document == null || analysis == null) {
            notify("Сначала выполните скан, чтобы увидеть защищённые зоны")
            return
        }
        viewModelScope.launch {
            val preview = withContext(Dispatchers.Default) {
                val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return@withContext null
                val canvas = Canvas(copy)
                val strokeWidth = (minOf(copy.width, copy.height) / 360f).coerceAtLeast(2f)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(52, 255, 96, 96)
                    style = Paint.Style.FILL
                }
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(225, 255, 96, 96)
                    style = Paint.Style.STROKE
                    this.strokeWidth = strokeWidth
                }
                document.regions
                    .filter { analysis.protectionReasons.containsKey(it.id) }
                    .forEach { region ->
                        val rect = RectF(
                            region.bounds.left,
                            region.bounds.top,
                            region.bounds.right,
                            region.bounds.bottom,
                        )
                        canvas.drawRect(rect, fill)
                        canvas.drawRect(rect, stroke)
                    }
                copy
            }
            if (preview == null) {
                notify("Не удалось построить карту защиты")
                return@launch
            }
            mutate {
                it.copy(
                    labPreview = preview,
                    labPreviewName = "защита · ${analysis.protectedCount} блоков",
                )
            }
        }
    }

    fun setFromLanguage(language: Language?) {
        if (state.isBusy || language == state.fromLanguage) return
        invalidateTranslation()
        mutate { it.copy(fromLanguage = language, detectedLanguage = null) }
    }

    fun setToLanguage(language: Language) {
        if (state.isBusy || language == state.toLanguage) return
        invalidateTranslation()
        mutate { it.copy(toLanguage = language, detectedLanguage = null) }
    }

    fun swapLanguages() {
        if (state.isBusy) return
        val from = state.fromLanguage
        if (from == null) {
            notify("Сначала выберите конкретный исходный язык")
            return
        }
        if (from == state.toLanguage) return
        invalidateTranslation()
        mutate { it.copy(fromLanguage = state.toLanguage, toLanguage = from, detectedLanguage = null) }
    }

    fun dismissError() {
        mutate { it.copy(error = null) }
    }

    fun showMessage(text: String) = notify(text)

    fun consumeMessage(id: Long) {
        mutate { if (it.message?.id == id) it.copy(message = null) else it }
    }

    fun refreshModelPacks(showLoading: Boolean = false) {
        if (showLoading) mutate { it.copy(refreshingModels = true) }
        modelsJob?.cancel()
        modelsJob = viewModelScope.launch {
            val codes = withContext(Dispatchers.IO) {
                runCatching { translationEngine.installedLanguageCodes() }.getOrDefault(emptySet())
            }
            mutate { current ->
                current.copy(
                    refreshingModels = false,
                    packs = buildPacks(current.downloadingLanguages, codes),
                )
            }
        }
    }

    fun downloadModel(language: Language) {
        if (state.isBusy) return
        if (state.downloadingLanguages.contains(language)) return
        if (state.packs.any { it.language == language && it.isInstalled }) return
        viewModelScope.launch {
            mutate { current ->
                current.copy(
                    downloadingLanguages = current.downloadingLanguages + language,
                    packs = buildPacks(current.downloadingLanguages + language, installedCodes(current)),
                )
            }
            notify("Загрузка модели: ${language.displayName}…")
            val result = runCatching {
                withContext(Dispatchers.IO) { translationEngine.download(language) }
            }
            mutate { current ->
                current.copy(downloadingLanguages = current.downloadingLanguages - language)
            }
            if (result.isSuccess) {
                notify("Модель «${language.displayName}» установлена")
            } else {
                notify("Не удалось скачать модель. Проверьте интернет и сервисы Google Play.")
            }
            refreshModelPacks()
        }
    }

    fun runPipeline() {
        val snapshot = state
        val bitmap = snapshot.original ?: return
        if (snapshot.isBusy) return
        val source = snapshot.fromLanguage
        val target = snapshot.toLanguage
        val applied = snapshot.appliedExperiment
        if (source == target) {
            notify("Исходный и целевой языки совпадают")
            return
        }
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            val pipelineStarted = SystemClock.elapsedRealtime()
            mutate {
                it.copy(
                    phase = ScanPhase.PROCESSING,
                    rendered = null,
                    document = null,
                    detectedLanguage = null,
                    error = null,
                    progress = PipelineProgress(
                        ocr = StepState.Running(
                            if (applied == null) {
                                "Сканирование текстовых блоков…"
                            } else {
                                "Сканирование · тест «${applied.variantName}»…"
                            },
                        ),
                    ),
                )
            }
            try {
                val imageInfo = snapshot.imageInfo
                    ?: ScanImageInfo.identity(bitmap.width, bitmap.height)

                val ocrStarted = SystemClock.elapsedRealtime()
                val document = withContext(Dispatchers.Default) {
                    ocrProcessor.processDetailed(
                        bitmap = bitmap,
                        imageInfo = imageInfo,
                        useJapanese = source == Language.JAPANESE,
                        useCyrillic = source == Language.RUSSIAN,
                        experimentMode = false,
                        scanMode = ScanMode.EXTENDED,
                        autoDetect = source == null,
                        forcedEngine = applied?.engine,
                        forcedVariantName = applied?.variantName,
                        aggressiveSanitizer = snapshot.aggressiveSanitizer,
                        protectEmbeddedImages = snapshot.protectEmbeddedImages,
                    )
                }
                ensureActive()
                val ocrMs = SystemClock.elapsedRealtime() - ocrStarted
                val documentKind = document.analysis?.kind?.displayName
                mutate {
                    it.copy(
                        document = document,
                        phase = ScanPhase.PROCESSING,
                        progress = it.progress.copy(
                            ocr = StepState.Done(
                                ocrMs,
                                buildString {
                                    append("${document.regions.size} блоков · ")
                                    append(ocrEngineLabel(document))
                                    if (documentKind != null) append(" · ").append(documentKind)
                                },
                            ),
                            langDetect = StepState.Running("Анализ скрипта и символов…"),
                        ),
                    )
                }
                if (document.regions.isEmpty()) {
                    mutate {
                        it.copy(
                            phase = ScanPhase.DONE,
                            progress = it.progress.copy(
                                langDetect = StepState.Done(0, "Текст не найден"),
                                translate = StepState.Done(0, "Пропущено"),
                                inpaint = StepState.Done(0, "Пропущено"),
                                render = StepState.Done(0, "Пропущено"),
                            ),
                        )
                    }
                    notify("Текст на изображении не найден")
                    return@launch
                }

                val languageStarted = SystemClock.elapsedRealtime()
                val effectiveSource = if (source != null) {
                    source
                } else {
                    withContext(Dispatchers.Default) {
                        languageDetector.detect(document.regions.joinToString("\n") { it.text })
                    } ?: inferLanguageFromScripts(document)
                }
                val languageMs = SystemClock.elapsedRealtime() - languageStarted
                ensureActive()
                mutate {
                    it.copy(
                        detectedLanguage = effectiveSource,
                        progress = it.progress.copy(
                            langDetect = StepState.Done(
                                languageMs,
                                "Определён: ${effectiveSource.displayName}",
                            ),
                        ),
                    )
                }
                if (effectiveSource == target) {
                    mutate {
                        it.copy(
                            phase = ScanPhase.DONE,
                            progress = it.progress.copy(
                                translate = StepState.Done(0, "Не требуется"),
                                inpaint = StepState.Done(0, "Пропущено"),
                                render = StepState.Done(0, "Пропущено"),
                            ),
                        )
                    }
                    notify("Язык изображения совпадает с целевым")
                    return@launch
                }

                mutate {
                    it.copy(
                        progress = it.progress.copy(
                            translate = StepState.Running("Подготовка языковой модели…"),
                        ),
                    )
                }
                refreshModelPacks()
                val pair = TranslationPair(from = effectiveSource, to = target)
                val protectedCount = document.regions.count { it.isProtected }
                val uncertainCount = document.regions.count { region ->
                    region.text.isNotBlank() && !region.isFormula && !region.isProtected &&
                        region.confidence != null && region.confidence < MIN_REPLACE_CONFIDENCE
                }
                val translatableIndices = document.regions.indices.filter { index ->
                    val region = document.regions[index]
                    region.text.isNotBlank() && !region.isFormula && !region.isProtected &&
                        region.confidence?.let { it >= MIN_REPLACE_CONFIDENCE } != false
                }
                if (translatableIndices.isEmpty()) {
                    mutate {
                        it.copy(
                            phase = ScanPhase.DONE,
                            document = document,
                            rendered = bitmap,
                            progress = it.progress.copy(
                                translate = StepState.Done(
                                    0,
                                    when {
                                        uncertainCount > 0 -> "Все блоки с низкой уверенностью"
                                        else -> "Формулы, числа и изображения"
                                    },
                                ),
                                inpaint = StepState.Done(0, "Пропущено"),
                                render = StepState.Done(0, "Оставлено без изменений"),
                            ),
                        )
                    }
                    notify(
                        when {
                            uncertainCount > 0 ->
                                "$uncertainCount блоков оставлено как в оригинале — низкая уверенность распознавания"
                            protectedCount > 0 ->
                                "Формулы и $protectedCount блоков внутри изображений сохранены как в оригинале"
                            else -> "Формулы и числа сохранены как в оригинале"
                        },
                    )
                    return@launch
                }
                val translateStarted = SystemClock.elapsedRealtime()
                val outcomes = withContext(Dispatchers.Default) {
                    translationEngine.translateBatch(
                        texts = translatableIndices.map { document.regions[it].text },
                        pair = pair,
                        onModelDownload = {
                            mutate { current ->
                                current.copy(
                                    progress = current.progress.copy(
                                        translate = StepState.Running("Скачиваем языковые модели…"),
                                    ),
                                    downloadingLanguages = current.downloadingLanguages +
                                        effectiveSource + target,
                                )
                            }
                            notify("Скачиваем языковые модели…")
                        },
                    )
                }
                ensureActive()
                val translateMs = SystemClock.elapsedRealtime() - translateStarted
                val translations = HashMap<Int, String>()
                outcomes.forEachIndexed { outcomeIndex, outcome ->
                    val text = outcome.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        translations[translatableIndices[outcomeIndex]] = text
                    }
                }
                mutate { current ->
                    current.copy(
                        downloadingLanguages = current.downloadingLanguages - effectiveSource - target,
                    )
                }
                refreshModelPacks()
                if (translations.isEmpty()) {
                    mutate {
                        it.copy(
                            phase = ScanPhase.ERROR,
                            document = document,
                            error = "Не удалось перевести текст. Модели скачиваются при первом запуске — проверьте интернет и сервисы Google Play.",
                            progress = it.progress.copy(
                                translate = StepState.Error("Нет перевода"),
                            ),
                        )
                    }
                    notify("Не удалось перевести текст")
                    return@launch
                }

                val translatedDocument = document.copy(
                    regions = document.regions.mapIndexed { index, region ->
                        val translation = translations[index]
                            ?: return@mapIndexed region.copy(translation = null)
                        region.copy(
                            translation = translation,
                            translationConfidence = region.confidence,
                        )
                    },
                )
                val translatedCount = translations.size
                mutate {
                    it.copy(
                        document = translatedDocument,
                        progress = it.progress.copy(
                            translate = StepState.Done(
                                translateMs,
                                "$translatedCount/${translatableIndices.size} блоков",
                            ),
                            inpaint = StepState.Running("Удаляем исходный текст…"),
                        ),
                    )
                }

                val replaceable = translatedDocument.regions.filter { region ->
                    !region.isProtected &&
                        !region.translation.isNullOrBlank() &&
                        region.translation != region.text
                }
                val inpaintStarted = SystemClock.elapsedRealtime()
                val restored = withContext(Dispatchers.Default) {
                    if (replaceable.isEmpty()) bitmap
                    else backgroundRestorer.restore(bitmap, replaceable)
                }
                ensureActive()
                val inpaintMs = SystemClock.elapsedRealtime() - inpaintStarted
                mutate {
                    it.copy(
                        progress = it.progress.copy(
                            inpaint = StepState.Done(
                                inpaintMs,
                                if (replaceable.isEmpty()) "Нечего удалять" else "Фон восстановлен",
                            ),
                            render = StepState.Running("Подбираем шрифт и масштаб…"),
                        ),
                    )
                }

                val renderStarted = SystemClock.elapsedRealtime()
                val rendered = withContext(Dispatchers.Default) {
                    if (replaceable.isEmpty()) bitmap
                    else textRenderer.render(
                        restored,
                        replaceable,
                        translatedDocument.regions,
                    )
                }
                ensureActive()
                val renderMs = SystemClock.elapsedRealtime() - renderStarted
                val totalMs = SystemClock.elapsedRealtime() - pipelineStarted
                mutate {
                    it.copy(
                        phase = ScanPhase.DONE,
                        rendered = rendered,
                        progress = it.progress.copy(
                            render = StepState.Done(renderMs, "Текст отрисован"),
                        ),
                    )
                }
                val failed = translatableIndices.size - translatedCount
                val preservedFormulas = document.regions.count { it.isFormula }
                val preservedSuffix = buildString {
                    if (preservedFormulas > 0) append(" · $preservedFormulas формул без изменений")
                    if (protectedCount > 0) append(" · $protectedCount блоков в изображениях сохранено")
                    if (uncertainCount > 0) {
                        append(" · $uncertainCount блоков оставлено (низкая уверенность)")
                    }
                }
                notify(
                    if (failed > 0) {
                        "Перевод завершён, $failed без перевода$preservedSuffix · ${formatSeconds(totalMs)}"
                    } else {
                        "Перевод завершён$preservedSuffix · ${formatSeconds(totalMs)}"
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                val message = friendlyError(exception)
                mutate { current ->
                    current.copy(
                        phase = ScanPhase.ERROR,
                        error = message,
                        progress = current.progress.withRunningStepError(),
                    )
                }
                notify(message)
            }
        }
    }

    fun runLabExperiments(engine: OcrEngineChoice) {
        val bitmap = state.original ?: return
        if (state.isBusy) return
        labJob?.cancel()
        labJob = viewModelScope.launch {
            mutate { it.copy(labRunning = true, labResults = emptyList(), error = null) }
            try {
                val results = withContext(Dispatchers.Default) {
                    ocrProcessor.experimentVariants(bitmap, engine) { result ->
                        mutate { current ->
                            current.copy(labResults = current.labResults + result)
                        }
                    }
                }
                mutate { it.copy(labRunning = false, labResults = results) }
                notify("Эксперимент завершён: ${results.size} вариантов")
            } catch (cancelled: CancellationException) {
                mutate { it.copy(labRunning = false) }
                throw cancelled
            } catch (exception: Exception) {
                mutate { it.copy(labRunning = false, error = friendlyError(exception)) }
                notify("Не удалось выполнить эксперимент")
            }
        }
    }

    fun previewLabVariant(variantName: String) {
        val bitmap = state.original ?: return
        viewModelScope.launch {
            val preview = withContext(Dispatchers.Default) {
                runCatching { ocrProcessor.preprocessingPreview(bitmap, variantName) }.getOrNull()
            }
            if (preview == null) {
                notify("Не удалось построить превью варианта")
                return@launch
            }
            mutate { it.copy(labPreview = preview, labPreviewName = variantName) }
        }
    }

    fun clearLabPreview() {
        mutate { it.copy(labPreview = null, labPreviewName = null) }
    }

    fun runStyleStressTest() {
        if (state.styleStressRunning) return
        styleStressJob?.cancel()
        styleStressJob = viewModelScope.launch {
            mutate { it.copy(styleStressRunning = true) }
            val report = withContext(Dispatchers.Default) { StyleStressLab.run() }
            mutate { it.copy(styleStressRunning = false, styleStress = report) }
            notify("Стресс-тест: ${report.passedCount} из ${report.cases.size} кейсов стабильны")
        }
    }

    fun applyLabExperiment(engine: OcrEngineChoice, variantName: String) {
        if (state.isBusy) return
        pipelineJob?.cancel()
        mutate {
            it.copy(
                appliedExperiment = AppliedExperiment(engine, variantName),
                rendered = null,
                document = null,
                detectedLanguage = null,
                progress = PipelineProgress(),
                phase = if (it.original != null) ScanPhase.IDLE else it.phase,
                error = null,
            )
        }
        notify("Настройка «$variantName» применена к основному скану")
    }

    fun clearAppliedExperiment() {
        if (state.isBusy) return
        pipelineJob?.cancel()
        mutate {
            it.copy(
                appliedExperiment = null,
                rendered = null,
                document = null,
                detectedLanguage = null,
                progress = PipelineProgress(),
                phase = if (it.original != null) ScanPhase.IDLE else it.phase,
                error = null,
            )
        }
        notify("Основной скан снова использует автонастройки")
    }

    private fun invalidateTranslation() {
        pipelineJob?.cancel()
        mutate {
            it.copy(
                rendered = null,
                document = null,
                progress = PipelineProgress(),
                phase = if (it.original != null) ScanPhase.IDLE else it.phase,
                error = null,
            )
        }
    }

    private fun buildPacks(
        downloading: Set<Language>,
        installed: Set<String>,
    ): List<LanguagePackInfo> = Language.entries.map { language ->
        LanguagePackInfo(
            language = language,
            isInstalled = language.code in installed,
            isDownloading = language in downloading,
            approximateSizeMb = APPROXIMATE_MODEL_MB,
        )
    }

    private fun installedCodes(current: ScanUiState): Set<String> =
        current.packs.filter { it.isInstalled }.mapTo(mutableSetOf()) { it.language.code }

    private fun queryDisplayName(uri: Uri): String? {
        val fromProvider = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return fromProvider
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "image"
    }

    private fun ocrEngineLabel(document: ScanDocument): String {
        val selected = document.preprocessing.selectedCandidate
        val engine = if (selected.contains("tesseract", ignoreCase = true)) {
            "Tesseract · Rus"
        } else {
            "ML Kit Text Recognition v2"
        }
        // Make the recipe the app picked for this photo visible instead of implicit.
        val mode = selected
            .removePrefix("tesseract-")
            .takeIf { it.isNotBlank() && it != "combined" && it != "original" }
        return if (mode != null) "$engine · $mode" else engine
    }

    private fun inferLanguageFromScripts(document: ScanDocument): Language =
        LanguageDetector.detectScript(document.regions.joinToString("\n") { it.text })
            ?: Language.ENGLISH

    private fun friendlyError(exception: Exception): String {
        val raw = exception.localizedMessage?.trim().orEmpty()
        return when {
            raw.contains("play services", ignoreCase = true) ||
                raw.contains("mlkit", ignoreCase = true) ->
                "Сервисы Google Play недоступны. Обновите их, чтобы загрузить модели перевода."

            raw.contains("network", ignoreCase = true) ||
                raw.contains("download", ignoreCase = true) ->
                "Нет соединения для загрузки языковой модели. Подключитесь к интернету и повторите."

            raw.isNotBlank() -> raw
            else -> "Не удалось обработать изображение. Попробуйте ещё раз."
        }
    }

    private fun PipelineProgress.withRunningStepError(): PipelineProgress = when {
        translate is StepState.Running -> copy(translate = StepState.Error("Ошибка"))
        langDetect is StepState.Running -> copy(langDetect = StepState.Error("Ошибка"))
        inpaint is StepState.Running -> copy(inpaint = StepState.Error("Ошибка"))
        render is StepState.Running -> copy(render = StepState.Error("Ошибка"))
        ocr is StepState.Running -> copy(ocr = StepState.Error("Ошибка"))
        else -> this
    }

    private fun notify(text: String) {
        mutate { it.copy(message = ToastMessage(messageIds.incrementAndGet(), text)) }
    }

    private inline fun mutate(transform: (ScanUiState) -> ScanUiState) {
        synchronized(stateLock) {
            _state = transform(_state)
        }
    }

    private fun formatSeconds(milliseconds: Long): String {
        val seconds = milliseconds / 1000f
        return if (seconds < 10f) {
            String.format(java.util.Locale.US, "%.1f с", seconds)
        } else {
            "${seconds.roundToInt()} с"
        }
    }

    private companion object {
        const val APPROXIMATE_MODEL_MB = 30

        /**
         * Blocks below this OCR confidence are left as the original: a wrong
         * reading painted over the photo looks worse than untranslated text.
         */
        const val MIN_REPLACE_CONFIDENCE = 0.70f
    }
}
