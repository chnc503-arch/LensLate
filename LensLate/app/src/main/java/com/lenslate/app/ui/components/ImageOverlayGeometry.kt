package com.lenslate.app.ui.components

import com.lenslate.app.model.AffineTransform2D
import com.lenslate.app.model.Point2D
import kotlin.math.min
import kotlin.math.roundToInt

data class ImageFitRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    fun contains(point: Point2D): Boolean =
        point.x in left..right && point.y in top..bottom
}

internal object ImageOverlayGeometry {

    fun fitRect(
        canvasWidth: Float,
        canvasHeight: Float,
        imageWidth: Int,
        imageHeight: Int,
    ): ImageFitRect? {
        if (canvasWidth <= 0f || canvasHeight <= 0f || imageWidth <= 0 || imageHeight <= 0) return null
        val scale = min(canvasWidth / imageWidth.toFloat(), canvasHeight / imageHeight.toFloat())
        val width = (imageWidth.toFloat() * scale).roundToInt().coerceAtLeast(1).toFloat()
        val height = (imageHeight.toFloat() * scale).roundToInt().coerceAtLeast(1).toFloat()
        val left = ((canvasWidth - width) / 2f).roundToInt().toFloat()
        val top = ((canvasHeight - height) / 2f).roundToInt().toFloat()
        return ImageFitRect(left, top, width, height)
    }

    fun sourceToCanvas(
        point: Point2D,
        sourceToWorking: AffineTransform2D,
        workingWidth: Int,
        workingHeight: Int,
        imageRect: ImageFitRect,
    ): Point2D {
        val working = sourceToWorking.map(point)
        return Point2D(
            x = imageRect.left + working.x * imageRect.width / workingWidth.toFloat(),
            y = imageRect.top + working.y * imageRect.height / workingHeight.toFloat(),
        )
    }

    fun canvasToSource(
        point: Point2D,
        imageRect: ImageFitRect,
        workingToSource: AffineTransform2D,
        workingWidth: Int,
        workingHeight: Int,
    ): Point2D? {
        if (!imageRect.contains(point) || imageRect.width <= 0f || imageRect.height <= 0f) return null
        val working = Point2D(
            x = (point.x - imageRect.left) * workingWidth.toFloat() / imageRect.width,
            y = (point.y - imageRect.top) * workingHeight.toFloat() / imageRect.height,
        )
        return workingToSource.map(working)
    }

    fun containsPoint(point: Point2D, polygon: List<Point2D>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var previousIndex = polygon.lastIndex
        for (index in polygon.indices) {
            val current = polygon[index]
            val previous = polygon[previousIndex]
            val intersects = (current.y > point.y) != (previous.y > point.y) &&
                point.x < (previous.x - current.x) * (point.y - current.y) /
                (previous.y - current.y) + current.x
            if (intersects) inside = !inside
            previousIndex = index
        }
        return inside
    }
}
