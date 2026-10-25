package com.lenslate.app.pipeline

/**
 * The on-device translator treats the decorative middle dot of poster lines as
 * a hyphen or drops the space after a colon, which visibly changes the layout
 * of a title even when every word is right. The source punctuation is known,
 * so the same separators are restored in the answer.
 */
internal object TranslationSeparatorGuard {

    fun restore(source: String, translated: String): String {
        if (translated.isBlank()) return translated
        val separators = source.filter { it == MIDDLE_DOT || it == BULLET }
        var result = TIME_GAP.replace(translated) { match ->
            "${match.groupValues[1]}:${match.groupValues[2]}"
        }
        if (separators.isNotEmpty()) {
            var index = 0
            result = SPACED_SEPARATOR.replace(result) { match ->
                if (index < separators.length) {
                    " ${separators[index++]} "
                } else {
                    match.value
                }
            }
        }
        return result
    }

    private const val MIDDLE_DOT = '\u00B7'
    private const val BULLET = '\u2022'
    private val TIME_GAP = Regex("(\\d):\\s+(\\d)")
    private val SPACED_SEPARATOR = Regex("\\s+[-–—•|]\\s+")
}
