package com.lenslate.app.pipeline

/**
 * Measures how soft the ink edge of a text region is.
 *
 * Sharp anti-aliased text crosses from background to solid ink within one or
 * two pixels. Motion blur or defocus spreads that transition over several
 * pixels, and the erase mask of [BackgroundRestorer] has to grow by the same
 * amount or the halo survives as a dark silhouette of the original glyphs.
 *
 * Distances are the squared normalized colour distances produced by the
 * restorer, so every threshold here is applied to squared values.
 */
internal object TextEdgeProfile {

    /**
     * Median width, in pixels, of the transition band between the floor and
     * strong thresholds inside the allowed area of the region. Returns 0 when
     * no measurable edge exists.
     */
    fun medianEdgeWidth(
        distances: FloatArray,
        allowed: BooleanArray,
        width: Int,
        height: Int,
        maxContrast: Float,
    ): Float {
        if (maxContrast <= 0f || width < 3 || height < 1) return 0f
        if (distances.size != width * height || allowed.size != width * height) return 0f
        val floorSq = EDGE_FLOOR_SHARE_SQ * maxContrast
        val strongSq = EDGE_STRONG_SHARE_SQ * maxContrast
        val widths = ArrayList<Float>(64)
        for (y in 0 until height) {
            val row = y * width
            var x = 0
            while (x < width) {
                if (!allowed[row + x]) {
                    x++
                    continue
                }
                val runStart = x
                while (x < width && allowed[row + x]) x++
                measureAllowedRun(distances, row, runStart, x, floorSq, strongSq, widths)
            }
        }
        if (widths.isEmpty()) return 0f
        widths.sort()
        return widths[widths.size / 2]
    }

    /** Erase radius that covers the measured halo plus one pixel of safety. */
    fun dilationRadius(edgeWidth: Float): Int =
        (edgeWidth + 1f).toInt().coerceIn(DEFAULT_DILATION, MAX_DILATION)

    private fun measureAllowedRun(
        distances: FloatArray,
        row: Int,
        start: Int,
        end: Int,
        floorSq: Float,
        strongSq: Float,
        widths: MutableList<Float>,
    ) {
        var x = start
        while (x < end) {
            if (distances[row + x] < floorSq) {
                x++
                continue
            }
            val bandStart = x
            while (x < end && distances[row + x] >= floorSq) x++
            val bandEnd = x
            var firstStrong = -1
            var lastStrong = -1
            for (index in bandStart until bandEnd) {
                if (distances[row + index] >= strongSq) {
                    if (firstStrong < 0) firstStrong = index
                    lastStrong = index
                }
            }
            if (firstStrong > bandStart) widths += (firstStrong - bandStart).toFloat()
            if (lastStrong in 0 until bandEnd - 1) {
                widths += (bandEnd - 1 - lastStrong).toFloat()
            }
        }
    }

    private const val EDGE_FLOOR_AMPLITUDE = 0.12f
    private const val EDGE_STRONG_AMPLITUDE = 0.55f
    const val EDGE_FLOOR_SHARE_SQ = EDGE_FLOOR_AMPLITUDE * EDGE_FLOOR_AMPLITUDE
    const val EDGE_STRONG_SHARE_SQ = EDGE_STRONG_AMPLITUDE * EDGE_STRONG_AMPLITUDE
    const val DEFAULT_DILATION = 2
    const val MAX_DILATION = 8
}
