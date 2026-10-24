package com.lenslate.app.pipeline

import com.lenslate.app.model.TextStyleSample
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

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

    /**
     * True when glyph colours drift smoothly from one end of the line to the
     * other, as opposed to a coloured first word followed by a uniform body.
     * In a gradient every neighbour step is a fraction of the total colour
     * span; an accent word ends with one full-span jump into the body colour.
     */
    fun looksLikeGradient(samples: List<TextStyleSample>): Boolean {
        val colors = samples.mapNotNull { sample ->
            sample.color?.takeIf { color -> alpha(color) > MIN_SAMPLE_ALPHA }
        }
        if (colors.size < MIN_GRADIENT_SAMPLES) return false
        val span = colorDistance(colors.first(), colors.last())
        if (span < MIN_GRADIENT_SPAN) return false
        var maximumStep = 0f
        for (index in 1 until colors.size) {
            val step = colorDistance(colors[index - 1], colors[index])
            if (step > maximumStep) maximumStep = step
        }
        return maximumStep <= span * MAX_GRADIENT_STEP_SHARE
    }

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

    private fun colorDistance(first: Int, second: Int): Float {
        val dr = (red(first) - red(second)) / 255f
        val dg = (green(first) - green(second)) / 255f
        val db = (blue(first) - blue(second)) / 255f
        return sqrt((dr * dr + dg * dg + db * db) / 3f)
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
    private const val MIN_GRADIENT_SAMPLES = 6
    private const val MIN_GRADIENT_SPAN = 0.12f
    private const val MAX_GRADIENT_STEP_SHARE = 0.6f
}
