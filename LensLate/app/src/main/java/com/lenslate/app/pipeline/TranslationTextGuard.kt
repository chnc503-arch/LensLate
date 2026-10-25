package com.lenslate.app.pipeline

import com.lenslate.app.model.Language

/**
 * Display lettering ("ВХОД ПО РЕГИСТРАЦИИ") is the hardest input for the
 * on-device translator: it often comes back half-translated with the source
 * alphabet left in place. Lower-casing the request first and restoring the
 * display case afterwards is a cheap, reversible improvement; the script check
 * then asks for a second attempt when the model still answered in the wrong
 * alphabet.
 */
internal object TranslationTextGuard {

    fun isAllCaps(text: String): Boolean {
        val letters = text.filter(Char::isLetter)
        if (letters.length < MIN_CAPS_LETTERS) return false
        val upper = letters.count(Char::isUpperCase)
        return upper.toFloat() / letters.length >= MIN_CAPS_SHARE
    }

    /**
     * Sentence-cases all-caps text while leaving acronyms (ГОСТ, США, USB) and
     * alphanumeric codes untouched. The first letter of the text and of every
     * sentence stays uppercase.
     */
    fun sentenceCase(text: String): String {
        if (text.isEmpty()) return text
        val builder = StringBuilder(text.length)
        var sentenceStart = true
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character.isWhitespace()) {
                builder.append(character)
                if (character == '\n') sentenceStart = true
                index++
                continue
            }
            var end = index
            while (end < text.length && !text[end].isWhitespace()) end++
            val token = text.substring(index, end)
            builder.append(caseWord(token, sentenceStart))
            sentenceStart = token.lastOrNull()?.let { it in SENTENCE_ENDINGS } == true
            index = end
        }
        return builder.toString()
    }

    fun restoreDisplayCase(translated: String, allCaps: Boolean): String =
        if (allCaps) translated.uppercase() else translated

    /** Share of letters written in an alphabet that is not the target one. */
    fun foreignLetterShare(text: String, target: Language): Float {
        val letters = text.filter(Char::isLetter)
        if (letters.isEmpty()) return 0f
        val foreign = letters.count { character -> isForeign(character, target) }
        return foreign.toFloat() / letters.length
    }

    fun isMixedAnswer(text: String, target: Language): Boolean =
        foreignLetterShare(text, target) > when (target) {
            // Russian proper nouns may stay in the English answer; an actually
            // untranslated word is a much larger share than that.
            Language.ENGLISH -> ENGLISH_FOREIGN_LIMIT
            // Latin model codes and units are legitimate in a Russian answer.
            Language.RUSSIAN -> RUSSIAN_FOREIGN_LIMIT
            Language.JAPANESE -> JAPANESE_FOREIGN_LIMIT
        }

    private fun caseWord(token: String, sentenceStart: Boolean): String {
        val letters = token.filter(Char::isLetter)
        if (letters.isEmpty()) return token
        // Codes such as "ИК-4200" or "USB3" must keep their letter case.
        if (token.any(Char::isDigit)) return token
        if (token.uppercase() in ACRONYMS) return token
        if (letters.length == 1) return token
        val lower = token.lowercase()
        if (!sentenceStart) return lower
        val firstLetter = lower.indexOfFirst(Char::isLetter)
        if (firstLetter < 0) return lower
        return lower.replaceRange(
            firstLetter,
            firstLetter + 1,
            lower[firstLetter].uppercaseChar().toString(),
        )
    }

    private fun isForeign(character: Char, target: Language): Boolean {
        val cyrillic = character in '\u0400'..'\u04FF'
        val latin = character in 'a'..'z' || character in 'A'..'Z'
        val kana = character in '\u3040'..'\u30FF'
        val cjk = character in '\u4E00'..'\u9FFF'
        return when (target) {
            Language.ENGLISH -> cyrillic || kana || cjk
            Language.RUSSIAN -> latin || kana || cjk
            Language.JAPANESE -> cyrillic
        }
    }

    private const val MIN_CAPS_LETTERS = 3
    private const val MIN_CAPS_SHARE = 0.9f
    private const val ENGLISH_FOREIGN_LIMIT = 0.15f
    private const val RUSSIAN_FOREIGN_LIMIT = 0.34f
    private const val JAPANESE_FOREIGN_LIMIT = 0.34f
    private const val SENTENCE_ENDINGS = ".!?…;:"

    private val ACRONYMS = setOf(
        "ГОСТ", "ТУ", "ПУЭ", "СНИП", "ОСТ", "ЕСКД", "ТО", "ПБ", "ООО", "ОАО",
        "ЗАО", "ПАО", "АО", "ИП", "НДС", "США", "РФ", "СССР", "ВНИИ", "НИИ",
        "ЭВМ", "ПК", "ИБП", "БП", "ЧПУ", "АЗС", "ЖКХ", "МЧС", "МВД", "ФСБ",
        "IT", "AI", "PDF", "USB", "GPS", "LED", "LCD", "IP", "QR", "PIN", "SOS",
        "VIP", "FAQ",
    )
}
