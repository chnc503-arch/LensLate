package com.lenslate.app.pipeline

import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.Point2D
import com.lenslate.app.model.TextElementRecord
import com.lenslate.app.model.TextLineRecord
import com.lenslate.app.model.TextRegionRecord
import com.lenslate.app.model.TextSymbolRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CurvedTextAnalyzerTest {

    @Test
    fun straightLineIsNeverCurved() {
        val centers = (0 until 9).map { index -> Point2D(index * 22f, 60f) }
        assertNull(CurvedTextAnalyzer.analyze(regionThrough(centers)))
    }

    @Test
    fun arcFitsCircleWithSourceGeometry() {
        val centers = arcCenters()
        val curve = CurvedTextAnalyzer.analyze(regionThrough(centers))
        assertNotNull(curve)
        val fitted = curve!!
        assertEquals(160f, fitted.centerX, 6f)
        assertEquals(220f, fitted.centerY, 6f)
        assertEquals(90f, fitted.radius, 8f)
        assertTrue(fitted.spanDegrees > 100f)
    }

    @Test
    fun multiLineRegionIsNotTreatedAsArc() {
        val line = regionThrough(arcCenters())
        val multiLine = line.copy(lines = line.lines + line.lines)
        assertNull(CurvedTextAnalyzer.analyze(multiLine))
    }

    private fun arcCenters(): List<Point2D> = (0 until 9).map { index ->
        val radians = Math.toRadians(-160.0 + index * 20.0)
        Point2D(160f + 90f * cos(radians).toFloat(), 220f + 90f * sin(radians).toFloat())
    }

    private fun regionThrough(centers: List<Point2D>, glyphHeight: Float = 18f): TextRegionRecord {
        val symbols = centers.mapIndexed { index, center ->
            val halfWidth = 9f
            val halfHeight = glyphHeight / 2f
            val bounds = Bounds2D(
                center.x - halfWidth,
                center.y - halfHeight,
                center.x + halfWidth,
                center.y + halfHeight,
            )
            TextSymbolRecord(
                text = ('A' + index).toString(),
                bounds = bounds,
                polygon = listOf(
                    Point2D(bounds.left, bounds.top),
                    Point2D(bounds.right, bounds.top),
                    Point2D(bounds.right, bounds.bottom),
                    Point2D(bounds.left, bounds.bottom),
                ),
                confidence = 0.9f,
            )
        }
        val left = symbols.minOf { it.bounds!!.left }
        val top = symbols.minOf { it.bounds!!.top }
        val right = symbols.maxOf { it.bounds!!.right }
        val bottom = symbols.maxOf { it.bounds!!.bottom }
        val bounds = Bounds2D(left, top, right, bottom)
        val polygon = listOf(
            Point2D(left, top),
            Point2D(right, top),
            Point2D(right, bottom),
            Point2D(left, bottom),
        )
        val element = TextElementRecord(
            text = symbols.joinToString("") { it.text },
            bounds = bounds,
            polygon = polygon,
            confidence = 0.9f,
            symbols = symbols,
        )
        val line = TextLineRecord(
            text = element.text,
            bounds = bounds,
            polygon = polygon,
            angleDegrees = 0f,
            confidence = 0.9f,
            elements = listOf(element),
        )
        return TextRegionRecord(
            id = 1,
            text = line.text,
            bounds = bounds,
            polygon = polygon,
            script = "Latin",
            readingOrder = 0,
            confidence = 0.9f,
            overallConfidence = 0.9f,
            lines = listOf(line),
        )
    }
}
