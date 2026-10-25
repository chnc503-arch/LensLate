package com.lenslate.app.pipeline

import android.graphics.Bitmap
import com.lenslate.app.model.TextRegionRecord
import kotlin.math.max

internal object ScanTranslationPolicy {

    /** Narrowest share of the font size a glyph advance can take in a real face. */
    private const val MIN_ADVANCE_RATIO = 0.35f

    /** How far past the box ink is still considered the same line. */
    private const val FRAGMENT_WINDOW_PAD = 3

    @JvmOverloads
    fun selectableIndices(
        regions: List<TextRegionRecord>,
        bitmap: Bitmap? = null,
    ): List<Int> = regions.indices.filter { index ->
        rejectionReason(regions[index], bitmap) == null
    }

    /** Why a region must stay exactly as photographed, or null when it can be replaced. */
    internal fun rejectionReason(region: TextRegionRecord, bitmap: Bitmap? = null): String? = when {
        region.text.isBlank() -> "blank"
        region.isFormula -> "formula"
        region.isProtected -> "protected"
        !fitsItsBox(region) -> "overflow"
        isLineFragment(region, bitmap) -> "fragment"
        else -> null
    }

    /**
     * A reading that needs more horizontal room than the box it was read from
     * gives cannot describe that box — the glyphs were never all there. Such a
     * region is left alone: rendering it would paint invented words next to the
     * original ink the box never covered.
     *
     * A box only ever spans one row, so the length that has to fit is the
     * longest row of the reading, not its whole text: a merged paragraph can be
     * carried by a single record that holds every row of the block.
     */
    private fun fitsItsBox(region: TextRegionRecord): Boolean {
        val size = region.appearance?.textHeightPx ?: return true
        if (size <= 0f || region.isVertical || region.isCurved) return true
        val rows = fitRows(region)
        if (rows.isEmpty()) return true
        val floor = size * MIN_ADVANCE_RATIO
        val overflowing = rows.count { it.widthPx / max(1, it.charCount) < floor }
        // One row can carry another row's box after a geometry pass re-cut the
        // line. A reading whose rows mostly fit the block does describe it.
        return overflowing * 2 <= rows.size
    }

    /**
     * The rows the fit check measured: one per line record, keyed by the widest
     * row of text that record holds. Exposed so a report can show the same
     * numbers the decision used.
     */
    internal fun fitRows(region: TextRegionRecord): List<SmartRunTrace.Row> {
        val rows = region.lines.mapNotNull { line ->
            val bounds = line.bounds ?: return@mapNotNull null
            val chars = longestRow(line.text) ?: return@mapNotNull null
            SmartRunTrace.Row(bounds.width, chars)
        }
        return rows.ifEmpty {
            val chars = longestRow(region.text) ?: return emptyList()
            listOf(SmartRunTrace.Row(region.bounds.width, chars))
        }
    }

    private fun longestRow(text: String): Int? {
        val rows = text.split('\n').filter { row -> row.any { it != ' ' } }
        return rows.maxOfOrNull { it.length }
    }

    private fun isLineFragment(region: TextRegionRecord, bitmap: Bitmap?): Boolean {
        if (bitmap == null) return false
        val size = region.appearance?.textHeightPx ?: return false
        if (size <= 0f || region.isVertical || region.isCurved) return false
        val box = region.bounds.toRect()
        val padX = box.height() * FRAGMENT_WINDOW_PAD
        val padY = box.height()
        val left = (box.left - padX).coerceAtLeast(0)
        val top = (box.top - padY).coerceAtLeast(0)
        val right = (box.right + padX).coerceAtMost(bitmap.width)
        val bottom = (box.bottom + padY).coerceAtMost(bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width < 8 || height < 8) return false
        val pixels = IntArray(width * height)
        return try {
            bitmap.getPixels(pixels, 0, width, left, top, width, height)
            LineFragmentGuard.isFragment(
                pixels = pixels,
                width = width,
                height = height,
                boxLeft = box.left - left,
                boxTop = box.top - top,
                boxRight = box.right - left,
                boxBottom = box.bottom - top,
                sizePx = size,
            )
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
