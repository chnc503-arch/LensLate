package com.lenslate.app.pipeline

import com.lenslate.app.model.AffineTransform2D
import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.Point2D
import com.lenslate.app.model.TextRegionRecord
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Pulls recognizer output back into working-bitmap coordinates. Enhanced OCR
 * inputs can be upscaled or rotated, so merging raw input-space geometry would
 * shift/scale every box and make inpainting erase the wrong pixels.
 */
internal object OcrGeometryMapper {

    fun mapRegions(
        regions: List<TextRegionRecord>,
        transform: AffineTransform2D,
    ): List<TextRegionRecord> {
        if (transform.isIdentity()) return regions
        val rotationDegrees = Math.toDegrees(
            atan2(transform.m10.toDouble(), transform.m00.toDouble()),
        ).toFloat()
        return regions.map { region ->
            val lines = region.lines.map { line ->
                line.copy(
                    bounds = mapBounds(line.polygon, line.bounds, transform),
                    polygon = mapPolygon(line.polygon, line.bounds, transform),
                    angleDegrees = line.angleDegrees?.let {
                        normalizeAngle(it + rotationDegrees)
                    },
                    elements = line.elements.map { element ->
                        element.copy(
                            bounds = mapBounds(element.polygon, element.bounds, transform),
                            polygon = mapPolygon(element.polygon, element.bounds, transform),
                            symbols = element.symbols.map { symbol ->
                                symbol.copy(
                                    bounds = mapBounds(symbol.polygon, symbol.bounds, transform),
                                    polygon = mapPolygon(symbol.polygon, symbol.bounds, transform),
                                )
                            },
                        )
                    },
                )
            }
            val polygon = mapPolygon(region.polygon, region.bounds, transform)
            region.copy(
                bounds = Bounds2D.fromPoints(polygon) ?: region.bounds,
                polygon = polygon,
                lines = lines,
            )
        }
    }

    fun normalizeAngle(angle: Float): Float {
        var normalized = angle
        while (normalized <= -90f) normalized += 180f
        while (normalized > 90f) normalized -= 180f
        return if (abs(normalized) < MIN_ANGLE_DEGREES) 0f else normalized
    }

    private fun mapPolygon(
        polygon: List<Point2D>,
        bounds: Bounds2D?,
        transform: AffineTransform2D,
    ): List<Point2D> {
        val points = polygon.takeIf { it.isNotEmpty() }
            ?: bounds?.toPolygon()
            ?: return emptyList()
        return points.map(transform::map)
    }

    private fun mapBounds(
        polygon: List<Point2D>,
        bounds: Bounds2D?,
        transform: AffineTransform2D,
    ): Bounds2D? = Bounds2D.fromPoints(mapPolygon(polygon, bounds, transform))

    private fun Bounds2D.toPolygon(): List<Point2D> = listOf(
        Point2D(left, top),
        Point2D(right, top),
        Point2D(right, bottom),
        Point2D(left, bottom),
    )

    private fun AffineTransform2D.isIdentity(): Boolean =
        m00 == 1f && m01 == 0f && m02 == 0f && m10 == 0f && m11 == 1f && m12 == 0f

    private const val MIN_ANGLE_DEGREES = 0.75f
}
