package com.lenslate.app.ui.components

import com.lenslate.app.model.AffineTransform2D
import com.lenslate.app.model.Point2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOverlayGeometryTest {

    @Test
    fun fitRectCentersLetterboxedImage() {
        val rect = ImageOverlayGeometry.fitRect(
            canvasWidth = 400f,
            canvasHeight = 300f,
            imageWidth = 200,
            imageHeight = 100,
        )

        assertEquals(ImageFitRect(left = 0f, top = 50f, width = 400f, height = 200f), rect)
    }

    @Test
    fun sourceCanvasConversionIncludesWorkingScaleAndLetterboxing() {
        val rect = ImageOverlayGeometry.fitRect(400f, 300f, 200, 100)!!
        val sourcePoint = Point2D(100f, 50f)
        val canvasPoint = ImageOverlayGeometry.sourceToCanvas(
            point = sourcePoint,
            sourceToWorking = AffineTransform2D.scale(0.5f, 0.5f),
            workingWidth = 200,
            workingHeight = 100,
            imageRect = rect,
        )

        assertEquals(Point2D(100f, 100f), canvasPoint)
        assertEquals(
            sourcePoint,
            ImageOverlayGeometry.canvasToSource(
                point = canvasPoint,
                imageRect = rect,
                workingToSource = AffineTransform2D.scale(2f, 2f),
                workingWidth = 200,
                workingHeight = 100,
            ),
        )
        assertNull(
            ImageOverlayGeometry.canvasToSource(
                point = Point2D(20f, 20f),
                imageRect = rect,
                workingToSource = AffineTransform2D.scale(2f, 2f),
                workingWidth = 200,
                workingHeight = 100,
            ),
        )
    }

    @Test
    fun polygonHitTestingFindsRotatedRegions() {
        val polygon = listOf(
            Point2D(10f, 20f),
            Point2D(70f, 10f),
            Point2D(80f, 50f),
            Point2D(20f, 60f),
        )

        assertTrue(ImageOverlayGeometry.containsPoint(Point2D(40f, 35f), polygon))
        assertFalse(ImageOverlayGeometry.containsPoint(Point2D(90f, 35f), polygon))
        assertFalse(ImageOverlayGeometry.containsPoint(Point2D(40f, 35f), polygon.take(2)))
    }
}
