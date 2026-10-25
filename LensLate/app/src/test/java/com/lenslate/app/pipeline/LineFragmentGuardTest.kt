package com.lenslate.app.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Windows are 200x60 with a flat grey backdrop. The line's own band is rows
 * 20..40, the box covers columns 20..80, and ink is drawn only inside the band
 * so the rows above and below stay a clean backdrop.
 */
class LineFragmentGuardTest {

    private val width = 200
    private val height = 60
    private val backdrop = 0xFF404040.toInt()
    private val ink = 0xFF000000.toInt()
    private val faint = 0xFF2C2C2C.toInt()
    private val box = intArrayOf(20, 20, 80, 40)

    @Test
    fun flagsBoxThatEndsInMiddleOfInk() {
        val window = window(box, continuation = 82..150, faint = true)
        assertTrue(
            "ink continues straight past the right edge, so the box is a fragment",
            LineFragmentGuard.isFragment(window, width, height, box[0], box[1], box[2], box[3], SIZE),
        )
    }

    @Test
    fun keepsBoxThatSpansItsWholeLine() {
        val window = window(box, continuation = null, faint = false)
        assertFalse(
            LineFragmentGuard.isFragment(window, width, height, box[0], box[1], box[2], box[3], SIZE),
        )
    }

    @Test
    fun keepsBoxSeparatedFromNeighbourByAWordGap() {
        val window = window(box, continuation = 110..150, faint = true)
        assertFalse(
            "a wide gap means the next ink is another word, not a cut-off tail",
            LineFragmentGuard.isFragment(window, width, height, box[0], box[1], box[2], box[3], SIZE),
        )
    }

    @Test
    fun flagsBoxWhoseInkStartsLeftOfTheBox() {
        val pixels = IntArray(width * height) { backdrop }
        paint(pixels, 20..79, 25..35, ink)
        paint(pixels, 12..18, 25..35, faint)
        assertFalse(
            "the tail is shorter than a glyph, so it is spacing noise",
            LineFragmentGuard.isFragment(pixels, width, height, box[0], box[1], box[2], box[3], SIZE),
        )
        val longer = IntArray(width * height) { backdrop }
        paint(longer, 20..79, 25..35, ink)
        paint(longer, 2..19, 25..35, faint)
        assertTrue(
            LineFragmentGuard.isFragment(longer, width, height, box[0], box[1], box[2], box[3], SIZE),
        )
    }

    @Test
    fun flagsBoxCutOffJustBeforeTheNextWord() {
        val window = window(box, continuation = 88..120, faint = true)
        assertTrue(
            "a word space is not an excuse: the line carries on past the box",
            LineFragmentGuard.isFragment(window, width, height, box[0], box[1], box[2], box[3], SIZE),
        )
    }

    @Test
    fun keepsSmoothBackdropWithoutInk() {
        val pixels = IntArray(width * height) { backdrop }
        paint(pixels, 20..79, 25..35, ink)
        // A vignette that never reaches the strength of a glyph must not fragment.
        for (y in 0 until height) {
            for (x in 80 until width) {
                pixels[y * width + x] = 0xFF484848.toInt()
            }
        }
        assertFalse(
            LineFragmentGuard.isFragment(pixels, width, height, box[0], box[1], box[2], box[3], SIZE),
        )
    }

    private fun window(box: IntArray, continuation: IntRange?, faint: Boolean): IntArray {
        val pixels = IntArray(width * height) { backdrop }
        paint(pixels, box[0] until box[2], 25..35, ink)
        continuation?.let { paint(pixels, it, 25..35, if (faint) this.faint else ink) }
        return pixels
    }

    private fun paint(pixels: IntArray, columns: IntRange, rows: IntRange, color: Int) {
        for (y in rows) {
            for (x in columns) {
                pixels[y * width + x] = color
            }
        }
    }

    private companion object {
        /** Cap height of the test line: a fragment run must be about this wide. */
        const val SIZE = 20f
    }
}
