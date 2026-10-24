package com.lenslate.app.pipeline

import com.lenslate.app.model.TextStyleSample
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Interpolates the per-glyph parameters measured on the source text so a
 * translation inherits a gradual colour/size drift (e.g. the right end of a
 * sign being slightly darker) instead of a single averaged colour.
 *
 * Pure Kotlin on purpose: the renderer and unit tests share the exact same
 * interpolation.
 */
internal object StyleGradient {

    fun colorAt(samples: List<TextStyleSample>, position: Float, fallback: Int): Int {
        val colors = samples.mapNotNull { it.color?.takeIf { color -> alpha(color) > MIN_SAMPLE_ALPHA } }
        if (colors.isEmpty()) return fallback
        if (colors.size == 1) return colors.first()
        val scaled = position.coerceIn(0f, 1f) * (colors.size - 1)
        val low = floor(scaled).toInt().coerceIn(0, colors.lastIndex)
        val high = min(low + 1, colors.lastIndex)
        val fraction = scaled - low
        return lerpColor(colors[low], colors[high], fraction)
    }

    fun scaleAt(samples: List<TextStyleSample>, position: Float): Float {
        if (samples.isEmpty()) return 1f
        if (samples.size == 1) return samples.first().scale
        val scaled = position.coerceIn(0f, 1f) * (samples.size - 1)
        val low = floor(scaled).toInt().coerceIn(0, samples.lastIndex)
        val high = min(low + 1, samples.lastIndex)
        val fraction = scaled - low
        val lowScale = samples[low].scale
        return lowScale + (samples[high].scale - lowScale) * fraction
    }

    /** A sub-percent drift is measurement noise, not a visible design choice. */
    fun hasScaleDrift(samples: List<TextStyleSample>): Boolean =
        samples.any { abs(it.scale - 1f) >= MIN_VISIBLE_SCALE_DRIFT }

    fun lerpColor(first: Int, second: Int, fraction: Float): Int {
        if (fraction <= 0f) return first
        if (fraction >= 1f) return second
        return argb(
            lerp(alpha(first), alpha(second), fraction),
            lerp(red(first), red(second), fraction),
            lerp(green(first), green(second), fraction),
            lerp(blue(first), blue(second), fraction),
        )
    }

    private fun lerp(first: Int, second: Int, fraction: Float): Int =
        (first + (second - first) * fraction).toInt().coerceIn(0, 255)

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private fun alpha(color: Int): Int = (color ushr 24) and 0xFF
    private fun red(color: Int): Int = (color shr 16) and 0xFF
    private fun green(color: Int): Int = (color shr 8) and 0xFF
    private fun blue(color: Int): Int = color and 0xFF

    private const val MIN_SAMPLE_ALPHA = 32
    private const val MIN_VISIBLE_SCALE_DRIFT = 0.025f
}
