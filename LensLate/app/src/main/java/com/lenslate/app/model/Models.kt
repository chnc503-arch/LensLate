package com.lenslate.app.model

import android.graphics.Rect

/**
 * Блок текста, найденный OCR-движком.
 * Содержит оригинальный текст, координаты на изображении и метаданные.
 */
data class TextBlock(
    val id: Int,
    val text: String,
    val boundingBox: Rect,
    val lines: List<String>,
    /** Угол наклона в градусах, 0 = горизонтальный */
    val angle: Float = 0f,
    /** Уверенность OCR от 0.0 до 1.0 */
    val confidence: Float = 1f,
)

/**
 * Стиль текста, извлечённый из оригинального изображения.
 */
data class TextStyle(
    /** Цвет текста в формате ARGB */
    val color: Int,
    /** Цвет фона под текстом */
    val backgroundColor: Int,
    /** Размер шрифта в пикселях */
    val sizePx: Float,
    val isBold: Boolean = false,
    val isMono: Boolean = false,
    /** Угол наклона в градусах */
    val angle: Float = 0f,
    /** sans | serif | monospace */
    val fontFamily: String = "sans",
    val fontWeight: Int = 400,
    /** left | center | right */
    val alignment: String = "left",
    val letterSpacingPx: Float = 0f,
    val lineSpacingMultiplier: Float = 1.05f,
    val capHeightPx: Float? = null,
    val slantDegrees: Float = 0f,
    val horizontalScale: Float = 1f,
    val strokeWidthPx: Float = 0f,
    val isUnderlined: Boolean = false,
    val isStruckThrough: Boolean = false,
    /** Key of the matched font in FontCatalog (system family + weight). */
    val fontKey: String = "",
    val fontMatchScore: Float = 0f,
)

/**
 * Переведённый блок — содержит оригинал, перевод и стиль для рендеринга.
 */
data class TranslatedBlock(
    val id: Int,
    val original: String,
    val translated: String,
    val boundingBox: Rect,
    val style: TextStyle,
    /** Уверенность перевода от 0.0 до 1.0 */
    val translationConfidence: Float = 1f,
    /** Цвета по символам — для рендеринга разноцветного текста. */
    val characterColors: List<Int> = emptyList(),
    /** Вертикальный текст (выстраивается сверху вниз). */
    val isVertical: Boolean = false,
    /** Изогнутый текст (посимвольный поворот вдоль дуги). */
    val isCurved: Boolean = false,
)

/**
 * Языковая пара для перевода.
 */
enum class Language(val code: String, val displayName: String, val flag: String) {
    ENGLISH("en", "Английский", "🇬🇧"),
    RUSSIAN("ru", "Русский",    "🇷🇺"),
    JAPANESE("ja", "Японский",  "🇯🇵");

    companion object {
        fun fromCode(code: String): Language? = entries.find { it.code == code }
    }
}

/**
 * Режим отображения результата сканирования.
 */
enum class ScanMode {
    /** Только общая точность и краткий текст. */
    COMPACT,
    /** Подробная карточка по каждому региону. */
    DETAILED,
    /** Все параметры: цвета, шрифт, угол, вариант предобработки, confidence по символам. */
    EXTENDED;
}

data class TranslationPair(
    val from: Language,
    val to: Language,
) {
    val isIdentity: Boolean get() = from == to
}

/** Состояние загружаемого языкового пакета on-device перевода. */
data class LanguagePackInfo(
    val language: Language,
    val isInstalled: Boolean,
    val isDownloading: Boolean = false,
    val approximateSizeMb: Int = 30,
)

/** What kind of document the scan looks like, based on text and image evidence. */
enum class DocumentKind(val displayName: String) {
    LITERARY_TEXT("Литературный текст"),
    MANGA("Манга / комикс"),
    TECHNICAL("Технический / научный"),
    DOCUMENT("Документ с фото"),
}

data class DocumentImageStats(
    val photoLikeShare: Float = 0f,
    val historicalPhotoShare: Float = 0f,
    val lineArtShare: Float = 0f,
    val colorfulShare: Float = 0f,
    val graphicShare: Float = 0f,
)

data class DocumentTextStats(
    val characterCount: Int = 0,
    val kanaShare: Float = 0f,
    val digitShare: Float = 0f,
    val symbolShare: Float = 0f,
    val formulaShare: Float = 0f,
    val textCoverage: Float = 0f,
    val averageConfidence: Float? = null,
    val shortRegionShare: Float = 0f,
    val regionCount: Int = 0,
)

/**
 * Result of document understanding. Regions whose text is embedded in a photo
 * or illustration can be protected from inpainting so the picture stays intact.
 */
data class DocumentAnalysis(
    val kind: DocumentKind = DocumentKind.LITERARY_TEXT,
    val confidence: Float = 0f,
    val scores: Map<DocumentKind, Float> = emptyMap(),
    val text: DocumentTextStats = DocumentTextStats(),
    val image: DocumentImageStats = DocumentImageStats(),
    val protectionReasons: Map<Int, String> = emptyMap(),
    val protectedAreaShare: Float = 0f,
) {
    val protectedCount: Int get() = protectionReasons.size
    val isProtectingImages: Boolean get() = protectionReasons.isNotEmpty()
}

/**
 * Итоговый результат пайплайна обработки изображения.
 */
data class PipelineResult(
    val blocks: List<TranslatedBlock>,
    val totalTimeMs: Long,
    val ocrTimeMs: Long,
    val translateTimeMs: Long,
    val inpaintTimeMs: Long,
    val renderTimeMs: Long,
)

/**
 * Состояние шага пайплайна — для отображения прогресса в UI.
 */
sealed class StepState {
    data object Idle    : StepState()
    data class  Running(val detail: String = "") : StepState()
    data class  Done(val timeMs: Long, val detail: String = "") : StepState()
    data class  Error(val message: String) : StepState()
}

/**
 * Общее состояние пайплайна для ViewModel.
 */
data class PipelineProgress(
    val ocr:       StepState = StepState.Idle,
    val langDetect: StepState = StepState.Idle,
    val translate: StepState = StepState.Idle,
    val inpaint:   StepState = StepState.Idle,
    val render:    StepState = StepState.Idle,
) {
    val progressFraction: Float get() {
        var done = 0
        listOf(ocr, langDetect, translate, inpaint, render).forEach {
            if (it is StepState.Done) done++
        }
        return done / 5f
    }

    val isRunning: Boolean get() =
        listOf(ocr, langDetect, translate, inpaint, render).any { it is StepState.Running }

    val isComplete: Boolean get() = render is StepState.Done
}

data class Point2D(val x: Float, val y: Float)

data class Bounds2D(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    fun toRect(): Rect = Rect(
        kotlin.math.floor(left).toInt(),
        kotlin.math.floor(top).toInt(),
        kotlin.math.ceil(right).toInt(),
        kotlin.math.ceil(bottom).toInt(),
    )

    companion object {
        fun fromPoints(points: List<Point2D>): Bounds2D? {
            if (points.isEmpty()) return null
            return Bounds2D(
                left = points.minOf { it.x },
                top = points.minOf { it.y },
                right = points.maxOf { it.x },
                bottom = points.maxOf { it.y },
            )
        }
    }
}

/** Maps coordinates with x'=m00*x+m01*y+m02 and y'=m10*x+m11*y+m12. */
data class AffineTransform2D(
    val m00: Float = 1f,
    val m01: Float = 0f,
    val m02: Float = 0f,
    val m10: Float = 0f,
    val m11: Float = 1f,
    val m12: Float = 0f,
) {
    fun map(point: Point2D): Point2D = Point2D(
        x = m00 * point.x + m01 * point.y + m02,
        y = m10 * point.x + m11 * point.y + m12,
    )

    /** Returns this transform followed by [next]. */
    fun then(next: AffineTransform2D): AffineTransform2D = AffineTransform2D(
        m00 = next.m00 * m00 + next.m01 * m10,
        m01 = next.m00 * m01 + next.m01 * m11,
        m02 = next.m00 * m02 + next.m01 * m12 + next.m02,
        m10 = next.m10 * m00 + next.m11 * m10,
        m11 = next.m10 * m01 + next.m11 * m11,
        m12 = next.m10 * m02 + next.m11 * m12 + next.m12,
    )

    fun inverse(): AffineTransform2D? {
        val determinant = m00 * m11 - m01 * m10
        if (!determinant.isFinite() || kotlin.math.abs(determinant) < 1e-8f) return null
        val inverseDeterminant = 1f / determinant
        val a = m11 * inverseDeterminant
        val b = -m01 * inverseDeterminant
        val d = -m10 * inverseDeterminant
        val e = m00 * inverseDeterminant
        return AffineTransform2D(
            m00 = a,
            m01 = b,
            m02 = -(a * m02 + b * m12),
            m10 = d,
            m11 = e,
            m12 = -(d * m02 + e * m12),
        )
    }

    companion object {
        fun scale(x: Float, y: Float): AffineTransform2D = AffineTransform2D(m00 = x, m11 = y)
        fun translation(x: Float, y: Float): AffineTransform2D = AffineTransform2D(m02 = x, m12 = y)
    }
}

data class ScanImageInfo(
    val sourceUri: String?,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val exifOrientation: Int,
    val orientedWidth: Int,
    val orientedHeight: Int,
    val workingWidth: Int,
    val workingHeight: Int,
    /** Maps oriented original-image coordinates into the current working bitmap. */
    val orientedSourceToWorking: AffineTransform2D,
) {
    val workingToOrientedSource: AffineTransform2D
        get() = orientedSourceToWorking.inverse() ?: AffineTransform2D()

    companion object {
        fun identity(width: Int, height: Int, sourceUri: String? = null) = ScanImageInfo(
            sourceUri = sourceUri,
            encodedWidth = width,
            encodedHeight = height,
            exifOrientation = 1,
            orientedWidth = width,
            orientedHeight = height,
            workingWidth = width,
            workingHeight = height,
            orientedSourceToWorking = AffineTransform2D(),
        )
    }
}

data class ImageQualityEstimate(
    val meanLuminance: Float,
    val luminanceStdDev: Float,
    val percentileRange: Float,
    val clippedDarkFraction: Float,
    val clippedBrightFraction: Float,
)

data class PreprocessingCandidate(
    val name: String,
    val averageOcrConfidence: Float?,
    val agreementWithOtherPasses: Float,
    val recognizedText: String,
    val score: Float,
    val selected: Boolean,
)

data class PreprocessingSummary(
    val quality: ImageQualityEstimate,
    val candidates: List<PreprocessingCandidate>,
    val selectedCandidate: String,
)

data class TextSymbolRecord(
    val text: String,
    val bounds: Bounds2D?,
    val polygon: List<Point2D>,
    val confidence: Float?,
)

data class TextElementRecord(
    val text: String,
    val bounds: Bounds2D?,
    val polygon: List<Point2D>,
    val confidence: Float?,
    val symbols: List<TextSymbolRecord> = emptyList(),
)

data class TextLineRecord(
    val text: String,
    val bounds: Bounds2D?,
    val polygon: List<Point2D>,
    val angleDegrees: Float?,
    val confidence: Float?,
    val elements: List<TextElementRecord>,
)

/**
 * Параметры одного исходного глифа. Позволяет перенести на перевод
 * постепенное изменение цвета/размера вдоль строки, а не усреднять его.
 */
data class TextStyleSample(
    val color: Int? = null,
    /** Высота глифа относительно других глифов того же класса (строчные/заглавные/выносные). */
    val scale: Float = 1f,
)

data class TextAppearanceEstimate(
    val foregroundArgb: Int?,
    val localBackgroundArgb: Int?,
    val textHeightPx: Float?,
    val weight: String?,
    val slantDegrees: Float?,
    val alignment: String?,
    val spacingPx: Float?,
    val lineSpacingPx: Float?,
    val likelyFontClass: String?,
    val fontClassConfidence: Float?,
    val alternativeColors: List<Int> = emptyList(),
    val confidenceByAttribute: Map<String, Float>,
    val fontWeight: Int? = null,
    val capHeightPx: Float? = null,
    val strokeWidthPx: Float? = null,
    val lineSpacingMultiplier: Float? = null,
    val horizontalScale: Float? = null,
    val isUnderlined: Boolean? = null,
    val isStruckThrough: Boolean? = null,
    val alpha: Float? = null,
    val fontKey: String? = null,
    val fontMatchScore: Float? = null,
)

/** Circle fitted through the centers of the source glyphs. */
data class TextCurve(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val startAngleDegrees: Float,
    val endAngleDegrees: Float,
    /** True when the angle grows clockwise on screen (y axis points down). */
    val clockwise: Boolean,
    val confidence: Float,
) {
    val spanDegrees: Float get() = kotlin.math.abs(endAngleDegrees - startAngleDegrees)
    val centerAngleDegrees: Float get() = (startAngleDegrees + endAngleDegrees) / 2f
}

data class TextRegionRecord(
    val id: Int,
    val text: String,
    val bounds: Bounds2D,
    val polygon: List<Point2D>,
    val script: String?,
    val readingOrder: Int,
    val confidence: Float?,
    val overallConfidence: Float?,
    val lines: List<TextLineRecord>,
    val appearance: TextAppearanceEstimate? = null,
    val preprocessingVariant: String? = null,
    val characterColors: List<Int> = emptyList(),
    val characterStyles: List<TextStyleSample> = emptyList(),
    val isVertical: Boolean = false,
    val isCurved: Boolean = false,
    val curve: TextCurve? = null,
    val isFormula: Boolean = false,
    val isProtected: Boolean = false,
    val protectionReason: String? = null,
    val translation: String? = null,
    val translationConfidence: Float? = null,
) {
    fun toLegacyBlock(): TextBlock = TextBlock(
        id = id,
        text = text,
        boundingBox = bounds.toRect(),
        lines = lines.map { it.text },
        angle = lines.firstNotNullOfOrNull { it.angleDegrees } ?: 0f,
        confidence = confidence ?: 1f,
    )
}

data class ScanDocument(
    val schemaVersion: Int = 1,
    val image: ScanImageInfo,
    val regions: List<TextRegionRecord>,
    val preprocessing: PreprocessingSummary,
    val experimentResults: List<PreprocessingCandidate> = emptyList(),
    val scanMode: ScanMode = ScanMode.DETAILED,
    val analysis: DocumentAnalysis? = null,
)
