package com.lenslate.app.pipeline

import android.graphics.Typeface
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

data class FontVariant(
    val key: String,
    val family: String,
    val weight: Int,
    val italic: Boolean,
    /** sans | serif | monospace */
    val fontClass: String,
    val label: String,
)

/**
 * System font families exposed as a small catalogue. Android ships Roboto,
 * Noto Serif and Noto Sans Mono with Cyrillic coverage, so matching stays fully
 * offline while still covering the common technical-document typefaces.
 */
object FontCatalog {

    val variants: List<FontVariant> = listOf(
        FontVariant("sans-regular", "sans-serif", 400, false, "sans", "Roboto Regular"),
        FontVariant("sans-medium", "sans-serif", 500, false, "sans", "Roboto Medium"),
        FontVariant("sans-bold", "sans-serif", 700, false, "sans", "Roboto Bold"),
        FontVariant("sans-black", "sans-serif", 900, false, "sans", "Roboto Black"),
        FontVariant("sans-light", "sans-serif", 300, false, "sans", "Roboto Light"),
        FontVariant("sans-italic", "sans-serif", 400, true, "sans", "Roboto Italic"),
        FontVariant("sans-condensed", "sans-serif-condensed", 400, false, "sans", "Roboto Condensed"),
        FontVariant("sans-condensed-bold", "sans-serif-condensed", 700, false, "sans", "Roboto Condensed Bold"),
        FontVariant("serif-regular", "serif", 400, false, "serif", "Noto Serif"),
        FontVariant("serif-bold", "serif", 700, false, "serif", "Noto Serif Bold"),
        FontVariant("serif-italic", "serif", 400, true, "serif", "Noto Serif Italic"),
        FontVariant("mono-regular", "monospace", 400, false, "monospace", "Noto Sans Mono"),
        FontVariant("mono-bold", "monospace", 700, false, "monospace", "Noto Sans Mono Bold"),
        FontVariant("casual", "casual", 400, false, "sans", "Casual"),
        FontVariant("cursive", "cursive", 400, false, "serif", "Cursive"),
    )

    private val byKey = variants.associateBy { it.key }
    private val typefaces = ConcurrentHashMap<String, Typeface>()

    fun variant(key: String?): FontVariant? = key?.let(byKey::get)

    fun typeface(key: String?): Typeface? {
        val variant = variant(key) ?: return null
        return typefaces.getOrPut(variant.key) { create(variant) }
    }

    fun fallbackKey(fontFamily: String, weight: Int, bold: Boolean = false): String {
        val effectiveWeight = if (bold) maxOf(weight, 700) else weight
        val family = fontFamily.lowercase()
        val base = when (family) {
            "serif" -> "serif"
            "monospace" -> "mono"
            else -> "sans"
        }
        val candidates = variants.filter { it.key.startsWith(base) && !it.italic }
        if (candidates.isEmpty()) return "sans-regular"
        return candidates.minByOrNull { kotlin.math.abs(it.weight - effectiveWeight) }?.key ?: candidates.first().key
    }

    fun label(key: String?): String = variant(key)?.label ?: "Roboto"

    private fun create(variant: FontVariant): Typeface {
        val base = Typeface.create(variant.family, Typeface.NORMAL)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                Typeface.create(base, variant.weight, variant.italic)
            variant.weight >= 600 || variant.italic ->
                Typeface.create(base, if (variant.italic) Typeface.BOLD_ITALIC else Typeface.BOLD)
            else -> base
        }
    }
}
