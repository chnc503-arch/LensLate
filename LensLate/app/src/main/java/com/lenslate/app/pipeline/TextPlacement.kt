package com.lenslate.app.pipeline

import com.lenslate.app.model.Bounds2D
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vertical envelope for a translated block. A translation that wraps into more
 * lines than the source may extend beyond its original box only as far as the
 * free space before the nearest neighbouring block allows.
 */
object TextPlacement {

    data class Room(val above: Float, val below: Float)

    data class Window(val top: Float, val bottom: Float, val maxHeight: Int)

    fun room(
        bounds: Bounds2D,
        obstacles: List<Bounds2D>,
        canvasHeight: Float,
    ): Room {
        var above = bounds.top.coerceAtLeast(0f)
        var below = (canvasHeight - bounds.bottom).coerceAtLeast(0f)
        obstacles.forEach { other ->
            if (other.right <= bounds.left + EDGE_TOLERANCE ||
                other.left >= bounds.right - EDGE_TOLERANCE
            ) {
                return@forEach
            }
            if (other.bottom <= bounds.top + EDGE_TOLERANCE) {
                above = min(above, (bounds.top - other.bottom).coerceAtLeast(0f))
            } else if (other.top >= bounds.bottom - EDGE_TOLERANCE) {
                below = min(below, (other.top - bounds.bottom).coerceAtLeast(0f))
            }
        }
        return Room(above, below)
    }

    fun window(
        bounds: Bounds2D,
        room: Room,
        gap: Float,
        maxGrowthRatio: Float,
    ): Window {
        val rawAbove = (room.above - gap).coerceAtLeast(0f)
        val rawBelow = (room.below - gap).coerceAtLeast(0f)
        val maxGrowth = (bounds.height * maxGrowthRatio).coerceAtLeast(0f)
        val available = rawAbove + rawBelow
        val scale = if (available > maxGrowth && available > 0f) {
            maxGrowth / available
        } else {
            1f
        }
        val extraAbove = rawAbove * scale
        val extraBelow = rawBelow * scale
        return Window(
            top = bounds.top - extraAbove,
            bottom = bounds.bottom + extraBelow,
            maxHeight = (bounds.height + extraAbove + extraBelow)
                .roundToInt()
                .coerceAtLeast(2),
        )
    }

    fun multilineTop(
        anchoredTop: Float,
        layoutHeight: Float,
        layoutLines: Int,
        sourceLines: Int,
        sourceHeight: Float,
        window: Window,
    ): Float {
        var top = anchoredTop
        val overflow = layoutHeight - sourceHeight
        if (overflow > 0f && layoutLines > sourceLines) {
            val roomAbove = (anchoredTop - window.top).coerceAtLeast(0f)
            top -= min(overflow / 2f, roomAbove)
        }
        val maxTop = window.bottom - layoutHeight
        return if (maxTop >= window.top) {
            top.coerceIn(window.top, maxTop)
        } else {
            window.top
        }
    }

    private const val EDGE_TOLERANCE = 0.5f
}
