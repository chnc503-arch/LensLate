package com.lenslate.app.pipeline

import kotlin.math.min

/**
 * Rates how much a Tesseract reading looks like real Russian.
 *
 * A poster can be read differently by every preprocessing pass, and the pass with
 * the highest per-word confidence is often the one that invented an acronym
 * ("СПЦСКЭМ") or a number ("088771") out of thin ink. Word-shape statistics catch
 * those inventions much more reliably than confidence does, so the merger uses
 * this score to break ties between overlapping readings.
 */
internal object CyrillicReadingPlausibility {

    private val WORD_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    private const val VOWELS = "аеёиоуыэюяАЕЁИОУЫЭЮЯ"
    private const val ALLOWED_SYMBOLS = " .,:;!?-'\"«»„“”()[]{}/%+&=—–…°№#@*|\\<>_"
    private const val DIGIT_TOKEN_SCORE = 0.72f
    private const val LONG_DIGIT_TOKEN_SCORE = 0.45f
    private const val LONG_DIGIT_TOKEN_LENGTH = 5
    private const val LATIN_TOKEN_SCORE = 0.62f
    private const val MIXED_SCRIPT_TOKEN_SCORE = 0.12f
    private const val SHORT_TOKEN_SCORE = 0.92f
    private const val PLAUSIBLE_TOKEN = 0.6f
    private const val MIN_USABLE_ALNUM = 2
    private const val MAX_SYMBOL_SHARE = 0.4f
    private const val MIN_WORD_LETTERS = 3
    private const val MAX_SINGLE_LETTER_TOKENS = 2
    private const val MIN_NUMBER_DIGITS = 2

    /**
     * Structural admissibility of a reading. Confidence alone admits punctuation
     * trails and isolated glyph noise that happen to contain a Cyrillic letter;
     * real lines contain at least one word or a multi-digit number.
     */
    fun isUsableReading(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val letters = trimmed.count(Char::isLetter)
        val digits = trimmed.count(Char::isDigit)
        if (letters + digits < MIN_USABLE_ALNUM) return false
        val nonSpace = trimmed.count { !it.isWhitespace() }
        val symbols = trimmed.count { !it.isLetterOrDigit() && !it.isWhitespace() }
        if (nonSpace > 0 && symbols.toFloat() / nonSpace > MAX_SYMBOL_SHARE) return false
        val tokens = trimmed
            .split(WORD_SEPARATORS)
            .filter { token -> token.any(Char::isLetterOrDigit) }
        if (tokens.isEmpty()) return false
        val longTokens = tokens.count { token -> token.count(Char::isLetter) >= MIN_WORD_LETTERS }
        val singleLetterTokens = tokens.count { token ->
            token.count(Char::isLetter) == 1 && token.none(Char::isDigit)
        }
        if (singleLetterTokens > MAX_SINGLE_LETTER_TOKENS && longTokens <= 1) return false
        val numeric = tokens.any { token -> token.count(Char::isDigit) >= MIN_NUMBER_DIGITS }
        return longTokens > 0 || numeric
    }

    fun score(text: String): Float {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0f
        val tokens = trimmed
            .split(WORD_SEPARATORS)
            .filter { token -> token.any(Char::isLetterOrDigit) }
        if (tokens.isEmpty()) return 0f
        val tokenScores = tokens.map(::tokenScore).sorted()
        val plausibleShare = tokenScores.count { it >= PLAUSIBLE_TOKEN } / tokenScores.size.toFloat()
        val weakest = tokenScores.first()
        val letters = trimmed.count(Char::isLetter)
        var score = 0.68f * plausibleShare + 0.32f * weakest
        // A Russian line naturally contains isolated Latin brand names; a line that is
        // mostly Latin is a different kind of page and must not be ranked as a valid
        // Russian reading here.
        if (letters > 0) {
            val cyrillic = trimmed.count { it in '\u0400'..'\u04FF' }
            if (cyrillic.toFloat() / letters < 0.3f) score *= 0.55f
        }
        val junk = trimmed.count { character ->
            !character.isLetterOrDigit() && !character.isWhitespace() &&
                character !in ALLOWED_SYMBOLS
        }
        if (junk > 0) {
            score *= 1f - min(0.5f, junk.toFloat() / trimmed.length * 1.5f)
        }
        return score.coerceIn(0f, 1f)
    }

    /**
     * Structural score blended with offline word evidence. The lab uses this
     * to rank competing passes: every garbled word costs more here than in
     * [score], while real rare vocabulary stays close to the structural value.
     * The regular scan keeps calling [score] and is unaffected.
     */
    fun lexicalQuality(text: String): Float {
        val structural = score(text)
        if (structural <= 0f) return 0f
        val lexical = RussianLanguageModel.lexicalScore(text)
        return (0.55f * structural + 0.45f * lexical).coerceIn(0f, 1f)
    }

    fun isSuspectReading(text: String): Boolean =
        RussianLanguageModel.isSuspectReading(text)

    fun tokenScore(token: String): Float {
        val letters = token.filter(Char::isLetter)
        if (letters.isEmpty()) {
            // A long unbroken digit string on a Russian poster is usually a misread
            // word ("088771" instead of "свет"); short numbers are kept neutral.
            val digits = token.count(Char::isDigit)
            return if (digits >= LONG_DIGIT_TOKEN_LENGTH) LONG_DIGIT_TOKEN_SCORE else DIGIT_TOKEN_SCORE
        }
        val cyrillic = letters.count { it in '\u0400'..'\u04FF' }
        val latin = letters.count { it.code < 128 }
        if (cyrillic == 0) return LATIN_TOKEN_SCORE
        // A single word written in two alphabets at once ("cлoвo") is Tesseract noise.
        if (latin > 0) return MIXED_SCRIPT_TOKEN_SCORE
        if (letters.length <= 2) return SHORT_TOKEN_SCORE
        val vowels = letters.count { it in VOWELS }
        val ratio = vowels.toFloat() / letters.length
        var score = when {
            vowels == 0 -> 0.12f
            ratio < 0.2f -> 0.45f
            ratio > 0.75f -> 0.65f
            else -> 1f
        }
        val consonants = longestConsonantRun(letters)
        if (consonants >= 6) {
            score *= 0.25f
        } else if (consonants == 5) {
            score *= 0.7f
        }
        return score
    }

    private fun longestConsonantRun(letters: String): Int {
        var longest = 0
        var current = 0
        letters.forEach { character ->
            if (character in VOWELS) {
                current = 0
            } else {
                current++
                if (current > longest) longest = current
            }
        }
        return longest
    }
}
