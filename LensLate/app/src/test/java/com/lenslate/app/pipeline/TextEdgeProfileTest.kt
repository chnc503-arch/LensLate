package com.lenslate.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ширина ореола на кромке глифа: у резкого текста переход занимает 1–2 px,
 * у размытого — 4–8 px, и радиус стирания должен расти вместе с ним.
 */
class TextEdgeProfileTest {

    @Test
    fun sharpEdgeNeedsOnlyTheDefaultDilation() {
        val width = 24
        val allowed = BooleanArray(width) { true }
        val distances = FloatArray(width) { index ->
            when (index) {
                in 0..8 -> 0f
                in 9..15 -> 1f
                else -> 0f
            }
        }
        val edge = TextEdgeProfile.medianEdgeWidth(distances, allowed, width, 1, maxContrast = 1f)
        assertEquals(0f, edge, 0.01f)
        assertEquals(TextEdgeProfile.DEFAULT_DILATION, TextEdgeProfile.dilationRadius(edge))
    }

    @Test
    fun blurredEdgeMeasuresTheHaloWidth() {
        val width = 30
        val allowed = BooleanArray(width) { true }
        // Рампа: пол 0.0144 пересекается на индексе 3, strong 0.3025 — на 7.
        val ramp = floatArrayOf(0f, 0.001f, 0.005f, 0.015f, 0.05f, 0.1f, 0.18f, 0.31f, 0.7f, 1f)
        val distances = FloatArray(width)
        var index = 0
        while (index < width) {
            distances[index] = if (index < ramp.size) ramp[index] else 1f
            index++
        }
        val edge = TextEdgeProfile.medianEdgeWidth(distances, allowed, width, 1, maxContrast = 1f)
        assertEquals(4f, edge, 0.01f)
        assertEquals(5, TextEdgeProfile.dilationRadius(edge))
    }

    @Test
    fun dilationIsCappedForExtremeBlur() {
        assertEquals(TextEdgeProfile.MAX_DILATION, TextEdgeProfile.dilationRadius(40f))
    }

    @Test
    fun backgroundWithoutInkHasNoMeasurableEdge() {
        val width = 20
        val allowed = BooleanArray(width) { true }
        val distances = FloatArray(width) { 0.001f * it }
        val edge = TextEdgeProfile.medianEdgeWidth(distances, allowed, width, 1, maxContrast = 1f)
        assertEquals(0f, edge, 0.01f)
    }

    @Test
    fun medianIgnoresRowsWithoutStrongInk() {
        val width = 30
        val allowed = BooleanArray(width * 2) { true }
        val distances = FloatArray(width * 2)
        // Первая строка — только слабый шум ниже strong-порога.
        for (x in 0 until width) distances[x] = if (x in 10..18) 0.05f else 0f
        // Вторая строка — резкий глиф.
        for (x in 0 until width) distances[width + x] = if (x in 10..18) 1f else 0f
        val edge = TextEdgeProfile.medianEdgeWidth(
            distances, allowed, width, 2, maxContrast = 1f,
        )
        assertEquals(0f, edge, 0.01f)
    }

    @Test
    fun gradientBackgroundInsideCountersIsNotAnEdge() {
        // Счётчик глифа «О»: фон внутри буквы не должен считаться ореолом.
        val width = 40
        val allowed = BooleanArray(width) { true }
        val distances = FloatArray(width) { index ->
            when {
                index in 4..12 -> 1f
                index in 26..34 -> 1f
                else -> 0f
            }
        }
        val edge = TextEdgeProfile.medianEdgeWidth(distances, allowed, width, 1, maxContrast = 1f)
        assertEquals(0f, edge, 0.01f)
    }

    @Test
    fun multipleRowsUseTheMedianWidth() {
        val width = 20
        val allowed = BooleanArray(width * 3) { true }
        val distances = FloatArray(width * 3)
        // Три строки: ореол 2 px, 4 px и 2 px.
        repeat(3) { row ->
            val offset = row * width
            val halo = listOf(2, 4, 2)[row]
            for (x in 0 until width) {
                distances[offset + x] = when {
                    x < 8 -> 0f
                    x < 8 + halo -> 0.1f
                    else -> 1f
                }
            }
        }
        val edge = TextEdgeProfile.medianEdgeWidth(distances, allowed, width, 3, maxContrast = 1f)
        assertEquals(2f, edge, 0.01f)
    }

    @Test
    fun degenerateInputsAreRejected() {
        assertEquals(0f, TextEdgeProfile.medianEdgeWidth(FloatArray(4), BooleanArray(4), 2, 2, 0f), 0.01f)
        assertEquals(
            0f,
            TextEdgeProfile.medianEdgeWidth(FloatArray(4), BooleanArray(4), 2, 2, 1f),
            0.01f,
        )
        assertTrue(TextEdgeProfile.dilationRadius(0f) >= TextEdgeProfile.DEFAULT_DILATION)
    }
}
