package com.lenslate.app.pipeline

import com.lenslate.app.model.TextRegionRecord
import kotlin.math.min

/**
 * Keeps typographic micro-spacing of the source. A source like "(b)" often has
 * a visible but non-space gap between the bracket and the letter; a translation
 * rendered with default kerning looks tighter than the original page. The gap
 * is measured inside a word only, so real spaces are never promoted or removed.
 */
internal object MicroSpacing {

    fun transfer(region: TextRegionRecord, translation: String): String {
        if (translation.isEmpty()) return translation
        val patterns = gapPatterns(region)
        if (!patterns.afterOpening && !patterns.beforeClosing) return translation
        val builder = StringBuilder(translation.length + 4)
        translation.forEachIndexed { index, character ->
            if (patterns.beforeClosing && character in CLOSING && index > 0) {
                val previous = translation[index - 1]
                if (!previous.isWhitespace() && previous != HAIR_SPACE) builder.append(HAIR_SPACE)
            }
            builder.append(character)
            if (patterns.afterOpening && character in OPENING && index < translation.lastIndex) {
                val next = translation[index + 1]
                if (!next.isWhitespace() && next != HAIR_SPACE) builder.append(HAIR_SPACE)
            }
        }
        return builder.toString()
    }

    private fun gapPatterns(region: TextRegionRecord): GapPatterns {
        var afterOpening = false
        var beforeClosing = false
        region.lines.forEach { line ->
            line.elements.forEach { element ->
                val symbols = element.symbols
                    .filter { it.bounds != null }
                    .sortedBy { it.bounds?.left ?: 0f }
                symbols.zipWithNext().forEach { (first, second) ->
                    val a = first.bounds ?: return@forEach
                    val b = second.bounds ?: return@forEach
                    val gap = b.left - a.right
                    val reference = min(a.height, b.height).coerceAtLeast(1f)
                    if (gap < reference * MIN_LOOSE_GAP ||
                        gap > reference * MAX_LOOSE_GAP
                    ) {
                        return@forEach
                    }
                    val previous = lastGlyph(first.text) ?: return@forEach
                    val next = firstGlyph(second.text) ?: return@forEach
                    if (previous in OPENING && next.isLetterOrDigit()) afterOpening = true
                    if (next in CLOSING && previous.isLetterOrDigit()) beforeClosing = true
                }
            }
        }
        return GapPatterns(afterOpening, beforeClosing)
    }

    private fun firstGlyph(text: String): Char? = text.firstOrNull { !it.isWhitespace() }

    private fun lastGlyph(text: String): Char? = text.lastOrNull { !it.isWhitespace() }

    private data class GapPatterns(
        val afterOpening: Boolean,
        val beforeClosing: Boolean,
    )

    private const val HAIR_SPACE = '\u200A'
    private const val MIN_LOOSE_GAP = 0.08f
    private const val MAX_LOOSE_GAP = 0.55f

    private val OPENING = setOf(
        '(', '[', '{', '«', '“', '„', '‘',
        '（', '［', '｛', '「', '『', '【', '〈', '《', '〔',
    )
    private val CLOSING = setOf(
        ')', ']', '}', '»', '”', '’',
        '）', '］', '｝', '」', '』', '】', '〉', '》', '〕',
    )
}
