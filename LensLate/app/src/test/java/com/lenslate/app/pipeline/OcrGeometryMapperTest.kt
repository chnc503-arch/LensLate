package com.lenslate.app.pipeline

import com.lenslate.app.model.AffineTransform2D
import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.Point2D
import com.lenslate.app.model.TextElementRecord
import com.lenslate.app.model.TextLineRecord
import com.lenslate.app.model.TextRegionRecord
import com.lenslate.app.model.TextSymbolRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OcrGeometryMapperTest {

    private fun rectPolygon(left: Float, top: Float, right: Float, bottom: Float) = listOf(
        Point2D(left, top),
        Point2D(right, top),
        Point2D(right, bottom),
        Point2D(left, bottom),
    )

    private fun region(): TextRegionRecord {
        val symbol = TextSymbolRecord(
            text = "Ж",
            bounds = Bounds2D(10f, 20f, 30f, 60f),
            polygon = rectPolygon(10f, 20f, 30f, 60f),
            confidence = 0.8f,
        )
        val element = TextElementRecord(
            text = "Жук",
            bounds = Bounds2D(10f, 20f, 90f, 60f),
            polygon = rectPolygon(10f, 20f, 90f, 60f),
            confidence = 0.8f,
            symbols = listOf(symbol),
        )
        val line = TextLineRecord(
            text = "Жук",
            bounds = Bounds2D(10f, 20f, 90f, 60f),
            polygon = rectPolygon(10f, 20f, 90f, 60f),
            angleDegrees = 10f,
            confidence = 0.8f,
            elements = listOf(element),
        )
        return TextRegionRecord(
            id = 1,
            text = "Жук",
            bounds = Bounds2D(10f, 20f, 90f, 60f),
            polygon = rectPolygon(10f, 20f, 90f, 60f),
            script = "Cyrillic",
            readingOrder = 0,
            confidence = 0.8f,
            overallConfidence = 0.8f,
            lines = listOf(line),
        )
    }

    @Test
    fun identityTransformReturnsSameList() {
        val regions = listOf(region())
        assertSame(regions, OcrGeometryMapper.mapRegions(regions, AffineTransform2D()))
    }

    @Test
    fun scaleMapsEveryLevelOfGeometry() {
        val mapped = OcrGeometryMapper
            .mapRegions(listOf(region()), AffineTransform2D.scale(2f, 2f))
            .single()
        assertEquals(20f, mapped.bounds.left, 0.001f)
        assertEquals(120f, mapped.bounds.bottom, 0.001f)
        val line = mapped.lines.single()
        assertEquals(20f, line.bounds!!.left, 0.001f)
        assertEquals(20f, line.elements.single().symbols.single().bounds!!.left, 0.001f)
        assertEquals(120f, line.elements.single().symbols.single().bounds!!.bottom, 0.001f)
        assertEquals(10f, line.angleDegrees!!, 0.001f)
    }

    @Test
    fun translationShiftsBoundsWithoutChangingAngle() {
        val mapped = OcrGeometryMapper
            .mapRegions(listOf(region()), AffineTransform2D.translation(100f, -5f))
            .single()
        assertEquals(110f, mapped.bounds.left, 0.001f)
        assertEquals(15f, mapped.bounds.top, 0.001f)
        assertEquals(10f, mapped.lines.single().angleDegrees!!, 0.001f)
    }

    @Test
    fun rotationMapsPolygonAndLineAngle() {
        val rotation = AffineTransform2D(
            m00 = 0f,
            m01 = -1f,
            m10 = 1f,
            m11 = 0f,
        )
        val mapped = OcrGeometryMapper.mapRegions(listOf(region()), rotation).single()
        assertEquals(-20f, mapped.polygon.first().x, 0.001f)
        assertEquals(10f, mapped.polygon.first().y, 0.001f)
        // 10° in the rotated input becomes 100° in working space, i.e. -80°.
        assertEquals(-80f, mapped.lines.single().angleDegrees!!, 0.001f)
        assertEquals(40f, mapped.bounds.width, 0.001f)
        assertEquals(80f, mapped.bounds.height, 0.001f)
    }

    @Test
    fun angleNormalisationWrapsAndDropsNegligibleTilt() {
        assertEquals(0f, OcrGeometryMapper.normalizeAngle(0.4f), 0.001f)
        assertEquals(-85f, OcrGeometryMapper.normalizeAngle(95f), 0.001f)
        assertEquals(80f, OcrGeometryMapper.normalizeAngle(-100f), 0.001f)
        assertEquals(90f, OcrGeometryMapper.normalizeAngle(-90f), 0.001f)
    }
}
