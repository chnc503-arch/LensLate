package com.lenslate.app.pipeline

import android.graphics.Typeface
import android.os.Build
import java.io.File
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
 *
 * Generic family lookups go through the theme engine, which on some OEM builds
 * replaces every family with one decorative font. That silently collapses the
 * catalogue to a single face, so the concrete font files are loaded directly
 * whenever they exist; the family lookup remains as a last resort.
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

    private val FONT_DIRS = listOf("/system/fonts/", "/product/fonts/", "/system/font/")

    private val SERIF_FILES = mapOf(
        "serif-regular" to listOf("NotoSerif-Regular.ttf", "NotoSerif-Regular.otf", "DroidSerif-Regular.ttf"),
        "serif-bold" to listOf("NotoSerif-Bold.ttf", "NotoSerif-Bold.otf", "DroidSerif-Bold.ttf"),
        "serif-italic" to listOf("NotoSerif-Italic.ttf", "NotoSerif-Italic.otf", "DroidSerif-Italic.ttf"),
    )

    private val MONO_FILES = mapOf(
        "mono-regular" to listOf("DroidSansMono.ttf", "NotoSansMono-Regular.ttf", "RobotoMono-Regular.ttf"),
        "mono-bold" to listOf("NotoSansMono-Bold.ttf", "RobotoMono-Bold.ttf", "DroidSansMono.ttf"),
    )

    private val SANS_FILES = listOf("Roboto-Regular.ttf", "RobotoStatic-Regular.ttf", "DroidSans.ttf")

    private val CASUAL_FILES = listOf("ComingSoon.ttf")
    private val CURSIVE_FILES = listOf("DancingScript-Regular.ttf")

    /** Novelty faces are never chosen by the automatic matcher: on documents
     *  their rounded silhouettes win too often at low comparison resolution. */
    val matchable: List<FontVariant> = variants.filter { it.key != "casual" && it.key != "cursive" }

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
        val direct = directTypeface(variant)
        if (direct != null) return direct
        val base = Typeface.create(variant.family, Typeface.NORMAL)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                Typeface.create(base, variant.weight, variant.italic)
            variant.weight >= 600 || variant.italic ->
                Typeface.create(base, if (variant.italic) Typeface.BOLD_ITALIC else Typeface.BOLD)
            else -> base
        }
    }

    private fun directTypeface(variant: FontVariant): Typeface? {
        if (variant.key.startsWith("serif")) {
            return findFile(SERIF_FILES[variant.key].orEmpty())?.let { typedFrom(it, variant) }
        }
        if (variant.key.startsWith("mono")) {
            return findFile(MONO_FILES[variant.key].orEmpty())?.let { typedFrom(it, variant) }
        }
        if (variant.key == "casual") return findFile(CASUAL_FILES)?.let { fileTypeface(it) }
        if (variant.key == "cursive") return findFile(CURSIVE_FILES)?.let { fileTypeface(it) }
        // Roboto ships as one variable font whose wght/wdth/ital axes cover the
        // whole sans branch of the catalogue.
        val file = findFile(SANS_FILES) ?: return null
        return variableSans(file, variant)
    }

    private fun variableSans(file: File, variant: FontVariant): Typeface? {
        val width = if (variant.family == "sans-serif-condensed") 75 else 100
        val ital = if (variant.italic) 1 else 0
        val settings = "'wght' ${variant.weight} 'wdth' $width 'ital' $ital"
        val varied = runCatching {
            Typeface.Builder(file).setFontVariationSettings(settings).build()
        }.getOrNull()
        if (varied != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return runCatching {
                    Typeface.create(varied, variant.weight, variant.italic)
                }.getOrDefault(varied)
            }
            return varied
        }
        return fileTypeface(file)?.let { base ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { Typeface.create(base, variant.weight, variant.italic) }.getOrDefault(base)
            } else if (variant.weight >= 600 || variant.italic) {
                Typeface.create(base, if (variant.italic) Typeface.BOLD_ITALIC else Typeface.BOLD)
            } else {
                base
            }
        }
    }

    private fun typedFrom(file: File, variant: FontVariant): Typeface? {
        val base = fileTypeface(file) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching { Typeface.create(base, variant.weight, variant.italic) }.getOrDefault(base)
        }
        return if (variant.weight >= 600) Typeface.create(base, Typeface.BOLD) else base
    }

    private fun fileTypeface(file: File): Typeface? =
        runCatching { Typeface.createFromFile(file) }.getOrNull()

    private fun findFile(names: List<String>): File? {
        if (names.isEmpty()) return null
        FONT_DIRS.forEach { dir ->
            names.forEach { name ->
                val file = File(dir, name)
                if (file.isFile && file.canRead()) return file
            }
        }
        return null
    }
}
