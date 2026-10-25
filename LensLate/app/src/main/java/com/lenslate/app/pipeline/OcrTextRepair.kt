package com.lenslate.app.pipeline

/**
 * Repairs OCR readback errors that change meaning: a missing space after a
 * colon ("ВАЖНО:Перед" → "ВАЖНО: Перед") and Cyrillic lookalikes inside
 * alphanumeric model codes ("ЪХ—4200" → "LX-4200").
 */
internal object OcrTextRepair {

    fun repair(text: String): String {
        if (text.isBlank()) return text
        // Tesseract turns the middle dot of "акустика · свет" into a spaced
        // apostrophe; the translator then reads it as quotation marks and
        // traduces the sentence. Restore the decorative separator.
        val separated = DECORATIVE_QUOTE.replace(text, "·")
        val spaced = MISSING_COLON_GAP.replace(separated, ": ")
        val cleaned = spaced
            .lines()
            .joinToString("\n") { line -> repairLine(line) }
            .trim()
        return TOKEN.replace(cleaned) { match -> repairCodeToken(match.value) }
    }

    private fun repairLine(line: String): String {
        if (line.isBlank()) return ""
        // The last glyph of a faded display heading survives as a caret, and in
        // caps the only letter that resembles one is А. Restoring it before the
        // noise trim keeps the word whole instead of truncating it.
        val braced = if (isDisplayCaps(line)) CAPS_TRAILING_CARET.replace(line, "А") else line
        var trimmed = TRAILING_NOISE.replace(LEADING_NOISE.replace(braced, ""), "")
        // Sparse segmentation sometimes glues a slash and a period to the end of
        // a sentence ("ЯРКОСТИ ./Проверка"), which then breaks the translation.
        trimmed = SENTENCE_SLASH.replace(trimmed) { match ->
            "${match.groupValues[1]} "
        }
        trimmed = trimmed.replace(SPACED_COLON_INSIDE, ":")
        // The Russian Tesseract data has no ruble glyph, so a price ends with
        // the letter that shares its shape. Only a trailing one counts: a
        // region code ("12 Р 34") keeps its letter.
        trimmed = RUBLE_SIGN.replace(trimmed, "₽")
        if (isDisplayCaps(trimmed)) {
            trimmed = CAPS_SEPARATOR.replace(trimmed, " · ")
        }
        return trimmed.replace(DOUBLE_SPACE, " ").trim()
    }

    private fun isDisplayCaps(line: String): Boolean {
        val letters = line.filter(Char::isLetter)
        if (letters.length < CAPS_MIN_LETTERS) return false
        return letters.all { it.isUpperCase() }
    }

    private fun repairCodeToken(token: String): String {
        if (token.length !in MIN_CODE_LENGTH..MAX_CODE_LENGTH) return token
        if (token.none(Char::isDigit)) return token
        if (token.none { it in CYRILLIC_RANGE }) return token
        if (token.any { !it.isLetterOrDigit() && it !in CODE_PUNCTUATION }) return token
        val rebuilt = StringBuilder(token.length)
        token.forEach { character ->
            val replacement = CODE_LOOKALIKES[character] ?: when (character) {
                '—', '–' -> '-'
                else -> character
            }
            rebuilt.append(replacement)
        }
        return rebuilt.toString()
    }

    private val MISSING_COLON_GAP = Regex(":(?=\\p{L})")
    private val DECORATIVE_QUOTE = Regex("(?<=\\s)['’‘`´](?=\\s)")
    private val LEADING_NOISE = Regex("^[\\s_^`~\\\\|<>{}\\[\\]]+[.,;:/\\-–—]*")
    private val TRAILING_NOISE = Regex("[\\s_^`~\\\\|<>{}\\[\\]]+$")
    private val SENTENCE_SLASH = Regex("\\s*([.!?…])\\s*[/\\\\]+\\s*(?=[А-ЯЁA-Z])")
    private val CAPS_TRAILING_CARET = Regex("(?<=\\p{L})\\s+\\^\\s*$")
    private val SPACED_COLON_INSIDE = Regex("(?<=\\d):\\s+(?=\\d{2}(?!\\d))")
    private val RUBLE_SIGN = Regex("(?<=\\d[ \u00A0]?)[Рр](?=[\\s.,;:!?)]*$)")
    private val CAPS_SEPARATOR = Regex("\\s+[-–]\\s+")
    private val DOUBLE_SPACE = Regex(" {2,}")
    private val TOKEN = Regex("\\S+")
    private const val CAPS_MIN_LETTERS = 3
    private val CYRILLIC_RANGE = '\u0400'..'\u04FF'
    private val CODE_PUNCTUATION = charArrayOf('-', '—', '–', '/', '.', ',')
    private val CODE_LOOKALIKES = mapOf(
        'А' to 'A', 'В' to 'B', 'Е' to 'E', 'К' to 'K', 'М' to 'M', 'Н' to 'H',
        'О' to 'O', 'Р' to 'P', 'С' to 'C', 'Т' to 'T', 'У' to 'Y', 'Х' to 'X',
        'Ъ' to 'L', 'Ь' to 'b',
        'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y', 'х' to 'x',
    )
    private const val MIN_CODE_LENGTH = 2
    private const val MAX_CODE_LENGTH = 24
}
