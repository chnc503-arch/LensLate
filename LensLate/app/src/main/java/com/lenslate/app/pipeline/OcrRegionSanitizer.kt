package com.lenslate.app.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import com.lenslate.app.model.TextRegionRecord
import kotlin.math.min
import kotlin.math.sqrt

internal enum class OcrSanitizerProfile {
    /** ML Kit detects text with a neural model, so only unsupported boxes are rejected. */
    NEURAL,

    /** Tesseract invents words on texture; every reading needs ink support. */
    TESSERACT,
}

internal data class OcrSanitizerDrop(
    val text: String,
    val reason: String,
    val confidence: Float?,
)

internal data class OcrSanitizerResult(
    val regions: List<TextRegionRecord>,
    val dropped: List<OcrSanitizerDrop> = emptyList(),
) {
    val reasons: Map<String, Int> get() = dropped.groupingBy { it.reason }.eachCount()
}

/**
 * Removes readings that are not supported by the text itself or by real ink.
 * Tesseract regularly produces plausible-looking words on paper texture, wood
 * grain and photos; those boxes then outvote honest passes and get inpainted as
 * if they were real, which is exactly the "text that does not exist" artefact.
 *
 * Checks are deliberately conservative: a questionable reading is kept unless
 * both the string and the pixels clearly disagree with it.
 */
internal class OcrRegionSanitizer {

    fun sanitizeText(
        regions: List<TextRegionRecord>,
        profile: OcrSanitizerProfile = OcrSanitizerProfile.TESSERACT,
        aggressive: Boolean = false,
    ): OcrSanitizerResult {
        val dropped = mutableListOf<OcrSanitizerDrop>()
        val kept = regions.filter { region ->
            val reason = textRejection(region, profile, aggressive)
            if (reason != null) dropped += drop(region, reason)
            reason == null
        }
        return OcrSanitizerResult(kept, dropped)
    }

    fun sanitizeInk(
        regions: List<TextRegionRecord>,
        bitmap: Bitmap,
        profile: OcrSanitizerProfile = OcrSanitizerProfile.TESSERACT,
        aggressive: Boolean = false,
    ): OcrSanitizerResult {
        val dropped = mutableListOf<OcrSanitizerDrop>()
        val kept = regions.mapNotNull { region ->
            val reason = inkRejection(region, bitmap, profile, aggressive)
            if (reason != null) {
                dropped += drop(region, reason)
                null
            } else {
                region
            }
        }
        return OcrSanitizerResult(kept, dropped)
    }

    private fun textRejection(
        region: TextRegionRecord,
        profile: OcrSanitizerProfile,
        aggressive: Boolean,
    ): String? {
        val text = region.text.trim()
        if (text.isEmpty()) return "no-text"
        val visible = text.filterNot(Char::isWhitespace)
        if (visible.isEmpty()) return "no-text"
        val meaningful = visible.count { it.isLetterOrDigit() }
        if (meaningful == 0) return "no-text"
        if (region.isFormula || FormulaDetection.looksLikeFormula(text)) return null

        var junk = 0
        visible.forEach { character -> if (character.isJunkSymbol()) junk++ }
        if (junk >= 2 && junk >= meaningful) return "symbol-noise"
        if (junk >= 3 && junk.toFloat() / visible.length >= JUNK_SHARE_MAX) return "symbol-noise"

        val compact = visible.filter(Char::isLetterOrDigit)
        val confidence = region.confidence
        if (compact.length >= 4 && compact.toSet().size == 1 && !compact[0].isDigit() &&
            (confidence == null || confidence < 0.86f)
        ) {
            return "repetition"
        }

        if (profile == OcrSanitizerProfile.NEURAL && !aggressive) return null

        if (looksLikeUniformGlyphs(region, confidence, profile, aggressive)) return "uniform-glyphs"
        if (looksLikeCaseNoise(compact, confidence, aggressive)) return "case-noise"
        if (looksLikeMixedScript(text, confidence, aggressive)) return "mixed-script"
        return null
    }

    private fun inkRejection(
        region: TextRegionRecord,
        bitmap: Bitmap,
        profile: OcrSanitizerProfile,
        aggressive: Boolean,
    ): String? {
        val confidence = region.confidence
        if (profile == OcrSanitizerProfile.NEURAL && confidence != null &&
            confidence >= NEURAL_TRUST_CONFIDENCE
        ) {
            return null
        }
        val rect = region.bounds.toRect()
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val right = rect.right.coerceIn(left + 1, bitmap.width)
        val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width < INK_MIN_REGION_SIDE || height < INK_MIN_REGION_SIDE) return null

        val glyphCount = region.text.count { it.isLetterOrDigit() }
        val checkStructure = glyphCount >= MIN_GLYPHS_FOR_STRUCTURE &&
            width * height <= MAX_STRUCTURE_PIXELS &&
            (aggressive || confidence == null || confidence < structureConfidenceLimit(profile))
        val pixels = IntArray(width * height)
        try {
            bitmap.getPixels(pixels, 0, width, left, top, width, height)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val background = estimateRingBackground(bitmap, left, top, right, bottom) ?: return null
        val flatDistance = when {
            aggressive -> AGGRESSIVE_FLAT_DISTANCE
            profile == OcrSanitizerProfile.TESSERACT -> TESSERACT_FLAT_DISTANCE
            else -> NEURAL_FLAT_DISTANCE
        }
        val minimumShare = when {
            aggressive -> AGGRESSIVE_MIN_INK_SHARE
            profile == OcrSanitizerProfile.TESSERACT -> TESSERACT_MIN_INK_SHARE
            else -> NEURAL_MIN_INK_SHARE
        }

        val thresholdSq = INK_DISTANCE * INK_DISTANCE
        val inkMask = if (checkStructure) BooleanArray(pixels.size) else null
        var maximum = 0f
        var inkPixels = 0
        pixels.forEachIndexed { index, pixel ->
            val distance = FaintInkSupport.distanceSq(pixel, background)
            if (distance > maximum) maximum = distance
            if (distance >= thresholdSq) {
                inkPixels++
                if (inkMask != null) inkMask[index] = true
            }
        }
        val share = inkPixels.toFloat() / pixels.size
        if (maximum < flatDistance * flatDistance || share < minimumShare) {
            // Faint ink (white on white, near-black on black) never reaches the
            // standard ink distance, so the region would be discarded even though
            // the OCR reading and the glyph layout are real. Retry with a
            // threshold derived from the measured contrast, but only for small
            // regions whose pixels still look like several glyphs.
            if (checkStructure && maximum in FAINT_MIN_DISTANCE_SQ..FAINT_MAX_DISTANCE_SQ &&
                FaintInkSupport.hasSupport(
                    region = region,
                    maximum = maximum,
                    pixels = pixels,
                    width = width,
                    height = height,
                    offsetX = left,
                    offsetY = top,
                    background = background,
                    glyphCount = glyphCount,
                )
            ) {
                return null
            }
            return if (maximum < flatDistance * flatDistance) "flat-area" else "no-ink"
        }
        val uniformLimit = if (aggressive) UNIFORM_FIELD_LIMIT_AGGRESSIVE else UNIFORM_FIELD_LIMIT
        if (share > uniformLimit) return "uniform-field"
        if ((profile == OcrSanitizerProfile.TESSERACT || aggressive) &&
            !FaintInkSupport.glyphsHaveInk(region, pixels, width, height, left, top, background, thresholdSq)
        ) {
            return "blank-glyphs"
        }
        if (inkMask != null) {
            val structure = GlyphStructureAnalyzer.analyze(inkMask, width, height)
            if (GlyphStructureAnalyzer.looksLikeSolidGraphic(structure, glyphCount)) {
                return "solid-graphic"
            }
        }
        return null
    }

    private fun structureConfidenceLimit(profile: OcrSanitizerProfile): Float =
        if (profile == OcrSanitizerProfile.TESSERACT) {
            TESSERACT_STRUCTURE_CONFIDENCE
        } else {
            NEURAL_STRUCTURE_CONFIDENCE
        }

    private fun estimateRingBackground(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Int? {
        val ring = 2
        val outerLeft = (left - ring).coerceAtLeast(0)
        val outerTop = (top - ring).coerceAtLeast(0)
        val outerRight = (right + ring).coerceAtMost(bitmap.width)
        val outerBottom = (bottom + ring).coerceAtMost(bitmap.height)
        val width = outerRight - outerLeft
        val height = outerBottom - outerTop
        if (width < 3 || height < 3) return null
        val pixels = IntArray(width * height)
        try {
            bitmap.getPixels(pixels, 0, width, outerLeft, outerTop, width, height)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val reds = ArrayList<Int>(2 * width + 2 * height)
        val greens = ArrayList<Int>(reds.size.coerceAtLeast(8))
        val blues = ArrayList<Int>(reds.size.coerceAtLeast(8))
        fun sample(x: Int, y: Int) {
            val pixel = pixels[y * width + x]
            reds += (pixel shr 16) and 0xFF
            greens += (pixel shr 8) and 0xFF
            blues += pixel and 0xFF
        }
        for (x in 0 until width) {
            sample(x, 0)
            sample(x, height - 1)
        }
        for (y in 1 until height - 1) {
            sample(0, y)
            sample(width - 1, y)
        }
        if (reds.isEmpty()) return null
        reds.sort()
        greens.sort()
        blues.sort()
        val middle = reds.size / 2
        return Color.rgb(reds[middle], greens[middle], blues[middle])
    }

    private fun looksLikeCaseNoise(
        compact: String,
        confidence: Float?,
        aggressive: Boolean,
    ): Boolean {
        if (confidence != null && confidence >= if (aggressive) 0.88f else 0.8f) return false
        val letters = compact.filter(Char::isLetter)
        if (letters.length < MIN_CASE_LETTERS) return false
        val cyrillic = letters.count { it in '\u0400'..'\u04FF' }
        if (cyrillic.toFloat() / letters.length < 0.7f) return false
        var transitions = 0
        var flips = 0
        var lowers = 0
        var previous: Boolean? = null
        letters.forEach { character ->
            if (!character.isUpperCase() && !character.isLowerCase()) return@forEach
            val lower = character.isLowerCase()
            if (lower) lowers++
            if (previous != null) {
                transitions++
                if (lower != previous) flips++
            }
            previous = lower
        }
        if (lowers == 0 || lowers == letters.length) return false
        val requiredFlips = if (aggressive) 3 else 4
        val requiredShare = if (aggressive) 0.26f else 0.34f
        return flips >= requiredFlips &&
            transitions >= 8 &&
            flips.toFloat() / transitions >= requiredShare
    }

    /**
     * Full words written in two alphabets at once are a Tesseract hallmark
     * ("cлoвo" with Latin lookalikes). Separate Latin and Cyrillic words in one
     * line are perfectly normal and must survive.
     */
    private fun looksLikeMixedScript(
        text: String,
        confidence: Float?,
        aggressive: Boolean,
    ): Boolean {
        if (confidence != null && confidence >= if (aggressive) 0.88f else 0.8f) return false
        val tokens = text.split(WHITESPACE).filter { token -> token.any(Char::isLetter) }
        if (tokens.isEmpty()) return false
        var mixedTokens = 0
        var letters = 0
        var minorityLetters = 0
        tokens.forEach { token ->
            val cyrillic = token.count { it in '\u0400'..'\u04FF' }
            val latin = token.count { it in 'a'..'z' || it in 'A'..'Z' }
            letters += cyrillic + latin
            minorityLetters += min(cyrillic, latin)
            if (cyrillic >= 2 && latin >= 2 &&
                min(cyrillic, latin).toFloat() / (cyrillic + latin) >= MIXED_TOKEN_MINORITY_SHARE
            ) {
                mixedTokens++
            }
        }
        if (mixedTokens == 0) return false
        if (tokens.size == 1) return true
        if (mixedTokens.toFloat() / tokens.size >=
            if (aggressive) MIXED_TOKEN_SHARE_AGGRESSIVE else MIXED_TOKEN_SHARE
        ) {
            return true
        }
        return minorityLetters.toFloat() / letters.coerceAtLeast(1) >= MIXED_LETTER_SHARE_MAX
    }

    private fun looksLikeUniformGlyphs(
        region: TextRegionRecord,
        confidence: Float?,
        profile: OcrSanitizerProfile,
        aggressive: Boolean,
    ): Boolean {
        val limit = when {
            aggressive -> 0.85f
            profile == OcrSanitizerProfile.TESSERACT -> 0.70f
            else -> 0.55f
        }
        if (confidence != null && confidence >= limit) return false
        val text = region.text
        // Digit tables legitimately use equal-width, equal-height boxes.
        if (text.any(Char::isDigit)) return false
        // Multi-word text has natural spacing; a uniform silhouette is suspicious
        // only for a single token such as a stylised logo.
        if (text.any(Char::isWhitespace)) return false
        if (text.count(Char::isLetter) < MIN_UNIFORM_GLYPHS) return false
        val symbols = region.lines
            .flatMap { it.elements }
            .flatMap { it.symbols }
            .filter { symbol -> symbol.text.any(Char::isLetter) }
        if (symbols.size < MIN_UNIFORM_GLYPHS) return false
        val widths = symbols.mapNotNull { symbol -> symbol.bounds?.width?.takeIf { it > 1f } }
        val heights = symbols.mapNotNull { symbol -> symbol.bounds?.height?.takeIf { it > 1f } }
        if (widths.size < MIN_UNIFORM_GLYPHS || heights.size < MIN_UNIFORM_GLYPHS) return false
        return coefficientOfVariation(widths) <= MAX_UNIFORM_WIDTH_CV &&
            coefficientOfVariation(heights) <= MAX_UNIFORM_HEIGHT_CV
    }

    private fun coefficientOfVariation(values: List<Float>): Float {
        if (values.isEmpty()) return Float.MAX_VALUE
        val mean = values.average().toFloat()
        if (mean <= 0f) return Float.MAX_VALUE
        val variance = values.sumOf { value ->
            val delta = (value - mean).toDouble()
            delta * delta
        } / values.size
        return (sqrt(variance).toFloat() / mean)
    }

    private fun Char.isJunkSymbol(): Boolean {
        if (isLetterOrDigit() || isWhitespace()) return false
        val type = Character.getType(code).toInt()
        return when (type) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            -> false
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt(),
            -> !ALLOWED_SYMBOLS.contains(code.toChar())
            else -> true
        }
    }

    private fun drop(region: TextRegionRecord, reason: String): OcrSanitizerDrop =
        OcrSanitizerDrop(region.text, reason, region.confidence)

    private companion object {
        const val INK_DISTANCE = 30f
        const val TESSERACT_FLAT_DISTANCE = 26f
        const val AGGRESSIVE_FLAT_DISTANCE = 38f
        const val NEURAL_FLAT_DISTANCE = 20f
        const val TESSERACT_MIN_INK_SHARE = 0.012f
        const val AGGRESSIVE_MIN_INK_SHARE = 0.018f
        const val NEURAL_MIN_INK_SHARE = 0.006f
        const val FAINT_MIN_DISTANCE_SQ = 64f
        const val FAINT_MAX_DISTANCE_SQ = 3136f
        const val UNIFORM_FIELD_LIMIT = 0.85f
        const val UNIFORM_FIELD_LIMIT_AGGRESSIVE = 0.78f
        const val INK_MIN_REGION_SIDE = 6
        const val NEURAL_TRUST_CONFIDENCE = 0.55f
        const val MIN_GLYPHS_FOR_STRUCTURE = 3
        const val MAX_STRUCTURE_PIXELS = 80_000
        const val TESSERACT_STRUCTURE_CONFIDENCE = 0.82f
        const val NEURAL_STRUCTURE_CONFIDENCE = 0.72f
        const val MIN_UNIFORM_GLYPHS = 5
        const val MAX_UNIFORM_WIDTH_CV = 0.075f
        const val MAX_UNIFORM_HEIGHT_CV = 0.045f
        const val JUNK_SHARE_MAX = 0.3f
        const val MIN_CASE_LETTERS = 6
        const val MIXED_TOKEN_MINORITY_SHARE = 0.25f
        const val MIXED_TOKEN_SHARE = 0.5f
        const val MIXED_TOKEN_SHARE_AGGRESSIVE = 0.34f
        const val MIXED_LETTER_SHARE_MAX = 0.38f
        val WHITESPACE = Regex("\\s+")
        const val ALLOWED_SYMBOLS =
            "+-=±×÷%‰§©®™€\$£¥₽&@#*\\/|~^_<>°′″…–—•·"
    }
}
