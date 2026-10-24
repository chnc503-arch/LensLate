package com.lenslate.app.pipeline

import com.lenslate.app.model.Bounds2D
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Comparative quality score of one OCR pass. There is no ground truth on a
 * phone, so the score is built from independent signals: recogniser
 * confidence, agreement with other preprocessing recipes, found volume,
 * language plausibility and region layout. A pass that hallucinates a few
 * high-confidence characters is punished by coverage and language checks,
 * while a faint but complete reading is rewarded by cross-variant consensus.
 */
data class OcrScoreBreakdown(
    val confidence: Float = 0f,
    val consensus: Float = 0f,
    val coverage: Float = 0f,
    val linguistic: Float = 0f,
    val layout: Float = 0f,
    val noise: Float = 0f,
    val total: Float = 0f,
) {
    companion object {
        val EMPTY = OcrScoreBreakdown()
    }
}

data class OcrRegionMetric(
    val text: String,
    val bounds: Bounds2D?,
)

data class OcrScoreCandidate(
    val text: String,
    val averageConfidence: Float?,
    val regions: List<OcrRegionMetric> = emptyList(),
)

internal object OcrQualityScorer {

    fun evaluate(
        candidate: OcrScoreCandidate,
        others: List<OcrScoreCandidate>,
    ): OcrScoreBreakdown {
        val normalized = normalize(candidate.text)
        if (normalized.isEmpty()) return OcrScoreBreakdown()
        val stats = analyzeText(candidate.text)
        if (stats.visible == 0) return OcrScoreBreakdown()

        val confidence = confidenceScore(candidate)
        val consensus = consensusScore(candidate.text, others.map { it.text })
        val coverage = coverageScore(normalized.length, others)
        val linguistic = linguisticScore(stats)
        val layout = layoutScore(candidate.regions)
        val noise = noiseScore(stats)
        val weighted =
            WEIGHT_CONFIDENCE * confidence +
                WEIGHT_CONSENSUS * consensus +
                WEIGHT_COVERAGE * coverage +
                WEIGHT_LINGUISTIC * linguistic +
                WEIGHT_LAYOUT * layout
        val total = (weighted - NOISE_WEIGHT * noise).coerceIn(0f, 1f)
        return OcrScoreBreakdown(
            confidence = confidence,
            consensus = consensus,
            coverage = coverage,
            linguistic = linguistic,
            layout = layout,
            noise = noise,
            total = total,
        )
    }

    fun rank(candidates: List<OcrScoreCandidate>): List<OcrScoreBreakdown> =
        candidates.mapIndexed { index, candidate ->
            evaluate(candidate, candidates.filterIndexed { other, _ -> other != index })
        }

    private fun confidenceScore(candidate: OcrScoreCandidate): Float {
        if (normalize(candidate.text).isEmpty()) return 0f
        // ML Kit reports very high confidence even for a couple of letters;
        // stretching the useful 0.25..1.0 range separates good passes from noise.
        val combined = candidate.averageConfidence ?: 0.42f
        return ((combined - 0.25f) / 0.75f).coerceIn(0f, 1f)
    }

    private fun consensusScore(text: String, others: List<String>): Float {
        val comparable = others.filter { normalize(it).isNotEmpty() }
        if (comparable.isEmpty()) return NEUTRAL_CONSENSUS
        val similarities = comparable
            .map { similarity(text, it) }
            .sortedDescending()
            .take(MAX_CONSENSUS_PEERS)
        return similarities.average().toFloat().coerceIn(0f, 1f)
    }

    private fun coverageScore(length: Int, others: List<OcrScoreCandidate>): Float {
        val maxLength = others
            .map { normalize(it.text).length }
            .plus(length)
            .maxOrNull()
            ?.coerceAtLeast(1) ?: 1
        val relative = length.toFloat() / maxLength
        val absolute = (length.toFloat() / ABSOLUTE_COVERAGE_CHARS).coerceIn(0f, 1f)
        return (0.62f * relative + 0.38f * absolute).coerceIn(0f, 1f)
    }

    private fun linguisticScore(stats: TextStats): Float {
        if (stats.letterWords == 0) return 0.55f
        val mixed = stats.mixedScriptWords.toFloat() / stats.letterWords
        val vowelAnomaly = stats.vowelAnomalyWords.toFloat() / stats.letterWords
        val caseNoise = (stats.caseFlips.toFloat() / stats.letterTokens.coerceAtLeast(1))
        val repetition = (stats.longRepetitions.toFloat() / stats.words.coerceAtLeast(1))
        val bigram = bigramPlausibility(stats)
        val bigramAnomaly = if (bigram < 0f) 0f else (1f - bigram).coerceIn(0f, 1f)
        val penalty =
            mixed * 0.75f +
                vowelAnomaly * 0.22f +
                caseNoise.coerceAtMost(0.6f) * 0.25f +
                repetition.coerceAtMost(0.8f) * 0.28f +
                bigramAnomaly * 0.34f
        return (1f - penalty).coerceIn(0.15f, 1f)
    }

    private fun layoutScore(regions: List<OcrRegionMetric>): Float {
        if (regions.isEmpty()) return 0.55f
        var tiny = 0
        var informative = 0
        regions.forEach { region ->
            val length = normalize(region.text).length
            val bounds = region.bounds
            val area = if (bounds != null) bounds.width * bounds.height else 0f
            if (length > 0) informative++
            if (length <= 2 && (bounds == null || area < TINY_REGION_AREA)) tiny++
        }
        val tinyRatio = if (informative == 0) 0f else tiny.toFloat() / informative
        val overlapRatio = overlappingFraction(regions)
        val densityPenalty = if (regions.size > MANY_REGIONS) {
            ((regions.size - MANY_REGIONS) / 600f).coerceIn(0f, 0.25f)
        } else {
            0f
        }
        return (1f - 0.55f * tinyRatio - 0.4f * overlapRatio - densityPenalty)
            .coerceIn(0.15f, 1f)
    }

    private fun overlappingFraction(regions: List<OcrRegionMetric>): Float {
        val bounded = regions.mapNotNull { it.bounds }.take(MAX_OVERLAP_REGIONS)
        if (bounded.size < 2) return 0f
        var overlapping = 0
        for (first in 0 until bounded.size - 1) {
            for (second in first + 1 until bounded.size) {
                if (overlapsSignificantly(bounded[first], bounded[second])) {
                    overlapping++
                    break
                }
            }
        }
        return overlapping.toFloat() / bounded.size
    }

    private fun overlapsSignificantly(first: Bounds2D, second: Bounds2D): Boolean {
        val overlapWidth = min(first.right, second.right) - max(first.left, second.left)
        val overlapHeight = min(first.bottom, second.bottom) - max(first.top, second.top)
        if (overlapWidth <= 0f || overlapHeight <= 0f) return false
        val overlapArea = overlapWidth * overlapHeight
        val firstArea = first.width * first.height
        val secondArea = second.width * second.height
        val smallest = min(firstArea, secondArea)
        if (smallest <= 0f) return false
        return overlapArea / smallest >= DUPLICATE_OVERLAP_RATIO
    }

    private fun noiseScore(stats: TextStats): Float {
        val visible = stats.visible.coerceAtLeast(1)
        val junkRatio = stats.junk.toFloat() / visible
        val symbolNoise = stats.suspiciousSymbols.toFloat() / visible
        val singleExcess = ((stats.singleCharTokens - stats.commonSingleWords).coerceAtLeast(0)).toFloat() /
            stats.words.coerceAtLeast(1)
        val punctuationExcess = (
            stats.punctuation.toFloat() / visible - MAX_REASONABLE_PUNCTUATION
            ).coerceAtLeast(0f)
        val exactRepeat = stats.longRepetitions.toFloat() / stats.words.coerceAtLeast(1)
        return (
            junkRatio * 2.2f +
                symbolNoise * 1.4f +
                singleExcess.coerceAtMost(0.8f) * 0.45f +
                punctuationExcess * 1.6f +
                exactRepeat.coerceAtMost(0.8f) * 0.35f
            ).coerceIn(0f, 1f)
    }

    private fun bigramPlausibility(stats: TextStats): Float {
        if (stats.bigramTotal == 0) return -1f
        return (stats.bigramHits.toFloat() / stats.bigramTotal).coerceIn(0f, 1f)
    }

    private fun similarity(first: String, second: String): Float {
        val a = textBigrams(first)
        val b = textBigrams(second)
        if (a.isEmpty() && b.isEmpty()) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val intersection = a.count { it in b }
        return intersection.toFloat() / (a.size + b.size - intersection)
    }

    private fun textBigrams(value: String): Set<String> {
        val text = normalize(value)
        if (text.isEmpty()) return emptySet()
        if (text.length == 1) return setOf(text)
        return (0 until text.lastIndex).mapTo(mutableSetOf()) { index ->
            text.substring(index, index + 2)
        }
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun analyzeText(value: String): TextStats {
        var visible = 0
        var letters = 0
        var digits = 0
        var punctuation = 0
        var junk = 0
        var suspiciousSymbols = 0
        var words = 0
        var letterWords = 0
        var letterTokens = 0
        var mixedScriptWords = 0
        var vowelAnomalyWords = 0
        var caseFlips = 0
        var longRepetitions = 0
        var singleCharTokens = 0
        var commonSingleWords = 0
        var bigramHits = 0
        var bigramTotal = 0

        value.split(Regex("[\\s]+")).forEach tokenLoop@ { rawToken ->
            val token = rawToken.trim()
            if (token.isEmpty()) return@tokenLoop
            val tokenLetters = StringBuilder()
            val tokenCyrillic = StringBuilder()
            val tokenLatin = StringBuilder()
            var previousLetter: Char? = null
            var runChar = '\u0000'
            var runLength = 0
            token.forEach { character ->
                when {
                    character.isLetter() -> {
                        letters++
                        visible++
                        tokenLetters.append(character)
                        if (isCyrillic(character)) tokenCyrillic.append(character)
                        if (isLatin(character)) tokenLatin.append(character)
                        if (previousLetter != null && previousLetter!!.isLowerCase() && character.isUpperCase()) {
                            caseFlips++
                        }
                        previousLetter = character
                        if (character.lowercaseChar() == runChar) {
                            runLength++
                        } else {
                            runChar = character.lowercaseChar()
                            runLength = 1
                        }
                        if (runLength >= LONG_REPEAT_LENGTH) longRepetitions++
                    }
                    character.isDigit() -> {
                        digits++
                        visible++
                        previousLetter = null
                        runChar = '\u0000'
                        runLength = 0
                    }
                    character.isWhitespace() -> Unit
                    character in PUNCTUATION -> {
                        punctuation++
                        visible++
                        // These are legal punctuation, but in bulk they signal a
                        // broken OCR line rather than real typography.
                        if (character in SUSPICIOUS_SYMBOLS) suspiciousSymbols++
                        previousLetter = null
                    }
                    else -> {
                        junk++
                        visible++
                        if (character in SUSPICIOUS_SYMBOLS) suspiciousSymbols++
                        previousLetter = null
                    }
                }
            }
            if (tokenLetters.isNotEmpty()) {
                words++
                letterWords++
                letterTokens++
                val lettersCount = tokenLetters.length
                if (lettersCount == 1) {
                    singleCharTokens++
                    if (tokenLetters.toString().lowercase() in COMMON_SINGLE_WORDS) commonSingleWords++
                }
                if (tokenCyrillic.length >= 2 && tokenLatin.length >= 2) {
                    // Such a word is usually a recogniser confusion, not real bilingual text.
                    mixedScriptWords++
                }
                val scriptLetters = when {
                    tokenCyrillic.length >= tokenLatin.length -> tokenCyrillic
                    else -> tokenLatin
                }.toString().lowercase()
                if (scriptLetters.length >= 3 && (tokenLatin.length == 0 || tokenCyrillic.length == 0)) {
                    val vowels = scriptLetters.count { it in VOWELS }
                    val ratio = vowels.toFloat() / scriptLetters.length
                    if (ratio < MIN_VOWEL_RATIO || ratio > MAX_VOWEL_RATIO) vowelAnomalyWords++
                }
                if (tokenCyrillic.length >= 2 || tokenLatin.length >= 2) {
                    val word = (if (tokenCyrillic.length >= tokenLatin.length) tokenCyrillic else tokenLatin)
                        .toString()
                        .lowercase()
                    val dictionary = if (isCyrillic(tokenCyrillic.firstOrNull() ?: 'a')) CYRILLIC_BIGRAMS else LATIN_BIGRAMS
                    for (index in 0 until word.lastIndex) {
                        val pair = word.substring(index, index + 2)
                        bigramTotal++
                        if (pair in dictionary) bigramHits++
                    }
                }
            } else if (normalize(token).isNotEmpty()) {
                words++
            }
        }
        return TextStats(
            visible = visible,
            junk = junk,
            punctuation = punctuation,
            suspiciousSymbols = suspiciousSymbols,
            words = words,
            letterWords = letterWords,
            letterTokens = letterTokens,
            mixedScriptWords = mixedScriptWords,
            vowelAnomalyWords = vowelAnomalyWords,
            caseFlips = caseFlips,
            longRepetitions = longRepetitions,
            singleCharTokens = singleCharTokens,
            commonSingleWords = commonSingleWords,
            bigramHits = bigramHits,
            bigramTotal = bigramTotal,
        )
    }

    private fun isCyrillic(character: Char): Boolean = character in '\u0400'..'\u052F'

    private fun isLatin(character: Char): Boolean =
        character in 'A'..'Z' || character in 'a'..'z' || character in '\u00C0'..'\u024F'

    private data class TextStats(
        val visible: Int,
        val junk: Int,
        val punctuation: Int,
        val suspiciousSymbols: Int,
        val words: Int,
        val letterWords: Int,
        val letterTokens: Int,
        val mixedScriptWords: Int,
        val vowelAnomalyWords: Int,
        val caseFlips: Int,
        val longRepetitions: Int,
        val singleCharTokens: Int,
        val commonSingleWords: Int,
        val bigramHits: Int,
        val bigramTotal: Int,
    )

    private const val WEIGHT_CONFIDENCE = 0.25f
    private const val WEIGHT_CONSENSUS = 0.22f
    private const val WEIGHT_COVERAGE = 0.23f
    private const val WEIGHT_LINGUISTIC = 0.20f
    private const val WEIGHT_LAYOUT = 0.10f
    private const val NOISE_WEIGHT = 0.25f
    private const val NEUTRAL_CONSENSUS = 0.62f
    private const val MAX_CONSENSUS_PEERS = 3
    private const val ABSOLUTE_COVERAGE_CHARS = 110f
    private const val TINY_REGION_AREA = 240f
    private const val MANY_REGIONS = 160
    private const val MAX_OVERLAP_REGIONS = 60
    private const val DUPLICATE_OVERLAP_RATIO = 0.62f
    private const val MAX_REASONABLE_PUNCTUATION = 0.22f
    private const val LONG_REPEAT_LENGTH = 4
    private const val MIN_VOWEL_RATIO = 0.14f
    private const val MAX_VOWEL_RATIO = 0.78f

    private val PUNCTUATION = setOf(
        '.', ',', ';', ':', '!', '?', '-', '–', '—', '(', ')', '[', ']', '{', '}', '"',
        '\'', '«', '»', '“', '”', '„', '…', '/', '\\', '№', '#', '%', '°', '+', '=', '_',
        '@', '&', '$', '€', '₽', '£', '*', '·', '•', '|', '~', '^', '`', '<', '>',
    )

    private val SUSPICIOUS_SYMBOLS = setOf('|', '~', '^', '`', '<', '>', '\\', '{', '}', '§', '©', '®')

    private val VOWELS = setOf(
        'а', 'е', 'ё', 'и', 'о', 'у', 'ы', 'э', 'ю', 'я',
        'a', 'e', 'i', 'o', 'u', 'y',
    )

    private val COMMON_SINGLE_WORDS = setOf(
        "а", "в", "и", "к", "о", "с", "у", "я",
        "a", "i",
    )

    private val CYRILLIC_BIGRAMS = setOf(
        "ст", "но", "то", "на", "ен", "ов", "ни", "ра", "ко", "ро", "по", "ос", "го",
        "пр", "ал", "ли", "ре", "от", "та", "ер", "ан", "ор", "не", "ат", "ол", "те",
        "ла", "во", "де", "ит", "ьн", "ый", "ий", "ой", "ая", "ые", "ет", "ся", "сь",
        "ль", "ть", "ка", "мо", "ме", "ми", "ел", "ил", "ид", "из", "за", "до", "об",
        "со", "од", "ин", "ич", "ск", "кт", "тр", "ру", "ус", "че", "чн", "же", "ши",
        "ше", "ще", "ща", "ця", "ци", "чи", "ны", "но", "ну", "ля", "ле", "лю", "ля",
    )

    private val LATIN_BIGRAMS = setOf(
        "th", "he", "in", "er", "an", "re", "on", "at", "en", "nd", "ti", "es", "or",
        "te", "of", "ed", "is", "it", "al", "ar", "st", "to", "nt", "ng", "se", "ha",
        "as", "ou", "io", "le", "ve", "co", "me", "de", "hi", "ri", "ro", "ic", "ne",
        "ea", "ra", "ce", "li", "ch", "ll", "be", "ma", "si", "om", "ur", "ca", "el",
        "ta", "la", "ns", "di", "fo", "ho", "pe", "rc", "ss", "un", "wi", "ly", "ck",
    )
}
