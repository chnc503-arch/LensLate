package com.lenslate.app.pipeline

/**
 * Repairs OCR readback errors that change meaning: a missing space after a
 * colon ("ВАЖНО:Перед" → "ВАЖНО: Перед") and Cyrillic lookalikes inside
 * alphanumeric model codes ("ЪХ—4200" → "LX-4200").
 */
internal object OcrTextRepair {

    fun repair(text: String): String {
        if (text.isBlank()) return text
        val spaced = MISSING_COLON_GAP.replace(text, ": ")
        return TOKEN.replace(spaced) { match -> repairCodeToken(match.value) }
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
    private val TOKEN = Regex("\\S+")
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
