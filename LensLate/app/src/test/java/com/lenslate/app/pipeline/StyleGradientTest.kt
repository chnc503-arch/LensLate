package com.lenslate.app.pipeline

import com.lenslate.app.model.TextStyleSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Перенос пословного градиента цвета и размера с исходных глифов на перевод. */
class StyleGradientTest {

    private val light = 0xFF404040.toInt()
    private val dark = 0xFF101010.toInt()
    private val red = 0xFFFF0000.toInt()

    @Test
    fun colorGradientKeepsBothEndpointsExactly() {
        val samples = listOf(
            TextStyleSample(color = light),
            TextStyleSample(color = dark),
        )
        assertEquals(light, StyleGradient.colorAt(samples, 0f, fallback = red))
        assertEquals(dark, StyleGradient.colorAt(samples, 1f, fallback = red))
    }

    @Test
    fun colorGradientInterpolatesTheMiddle() {
        val samples = listOf(
            TextStyleSample(color = 0xFF000000.toInt()),
            TextStyleSample(color = 0xFFFFFFFF.toInt()),
        )
        val middle = StyleGradient.colorAt(samples, 0.5f, fallback = red)
        assertEquals(0xFF7F7F7F.toInt(), middle)
    }

    @Test
    fun transparentSamplesAreIgnored() {
        val samples = listOf(
            TextStyleSample(color = 0x00FFFFFF),
            TextStyleSample(color = light),
            TextStyleSample(color = dark),
        )
        assertEquals(light, StyleGradient.colorAt(samples, 0f, fallback = red))
        assertEquals(dark, StyleGradient.colorAt(samples, 1f, fallback = red))
    }

    @Test
    fun missingColorsFallBackToTheBaseColor() {
        val samples = listOf(TextStyleSample(scale = 1.1f), TextStyleSample(scale = 1.2f))
        assertEquals(red, StyleGradient.colorAt(samples, 0.4f, fallback = red))
    }

    @Test
    fun singleSampleAppliesToTheWholeLine() {
        val samples = listOf(TextStyleSample(color = dark))
        assertEquals(dark, StyleGradient.colorAt(samples, 0f, fallback = red))
        assertEquals(dark, StyleGradient.colorAt(samples, 1f, fallback = red))
    }

    @Test
    fun scaleGradientIsInterpolated() {
        val samples = listOf(
            TextStyleSample(scale = 0.9f),
            TextStyleSample(scale = 1.1f),
        )
        assertEquals(0.9f, StyleGradient.scaleAt(samples, 0f), 0.001f)
        assertEquals(1.1f, StyleGradient.scaleAt(samples, 1f), 0.001f)
        assertEquals(1.0f, StyleGradient.scaleAt(samples, 0.5f), 0.001f)
        assertEquals(1f, StyleGradient.scaleAt(emptyList(), 0.5f), 0.001f)
    }

    @Test
    fun driftBelowNoiseThresholdIsNotReported() {
        assertFalse(StyleGradient.hasScaleDrift(listOf(TextStyleSample(scale = 1.01f))))
        assertTrue(StyleGradient.hasScaleDrift(listOf(TextStyleSample(scale = 1.08f))))
    }
}
