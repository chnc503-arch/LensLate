package com.lenslate.app.ui.components

import android.graphics.Bitmap
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenslate.app.model.Bounds2D
import com.lenslate.app.model.Point2D
import com.lenslate.app.model.TextRegionRecord
import kotlin.math.roundToInt

/**
 * Полотно предпросмотра: изображение, рамки OCR-блоков и выбор региона.
 * Регионы приходят в координатах working-bitmap, поэтому никаких
 * EXIF-преобразований здесь не выполняется. Поддерживается пинч-зум и панорама.
 */
@Composable
fun OcrImageOverlay(
    bitmap: Bitmap,
    regions: List<TextRegionRecord> = emptyList(),
    selectedRegionId: Int? = null,
    onRegionSelected: ((Int?) -> Unit)? = null,
    modifier: Modifier = Modifier,
    showRegions: Boolean = true,
    resetKey: Int = 0,
    contentDescription: String? = null,
    allowDoubleTapZoom: Boolean = false,
    maxZoom: Float = MAX_ZOOM,
    onZoomChanged: (Float) -> Unit = {},
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val badgeTextSize = with(density) { 8.sp.toPx() }
    val badgeHeight = with(density) { 11.dp.toPx() }
    val badgePadding = with(density) { 2.5.dp.toPx() }
    val outlineWidth = with(density) { 1.dp.toPx() }
    val selectedOutlineWidth = with(density) { 1.8.dp.toPx() }

    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(resetKey, bitmap) {
        zoom = 1f
        pan = Offset.Zero
    }
    LaunchedEffect(zoom) { onZoomChanged(zoom) }

    val currentRegions by rememberUpdatedState(regions)
    val currentSelection by rememberUpdatedState(onRegionSelected)

    val badgeFillPaint = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.FILL }
    }
    val badgeTextPaint = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            textAlign = AndroidPaint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    val description = contentDescription ?: if (regions.isEmpty()) {
        "Изображение"
    } else {
        "Изображение с распознанными областями: ${regions.size}"
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .semantics { this.contentDescription = description }
                .pointerInput(bitmap, showRegions, allowDoubleTapZoom) {
                    detectTapGestures(
                        onDoubleTap = if (allowDoubleTapZoom) {
                            { tap ->
                                val rect = ImageOverlayGeometry.fitRect(
                                    canvasWidth = size.width.toFloat(),
                                    canvasHeight = size.height.toFloat(),
                                    imageWidth = bitmap.width,
                                    imageHeight = bitmap.height,
                                )
                                if (rect != null && rect.contains(Point2D(tap.x, tap.y))) {
                                    val center = Offset(
                                        rect.left + rect.width / 2f,
                                        rect.top + rect.height / 2f,
                                    )
                                    if (zoom > 1.01f) {
                                        zoom = 1f
                                        pan = Offset.Zero
                                    } else {
                                        val base = Offset(
                                            x = (tap.x - center.x - pan.x) / zoom + center.x,
                                            y = (tap.y - center.y - pan.y) / zoom + center.y,
                                        )
                                        val target = DOUBLE_TAP_ZOOM.coerceAtMost(maxZoom)
                                        val nextPan = Offset(
                                            x = tap.x - center.x - (base.x - center.x) * target,
                                            y = tap.y - center.y - (base.y - center.y) * target,
                                        )
                                        pan = clampPan(nextPan, rect, target)
                                        zoom = target
                                    }
                                }
                            }
                        } else {
                            null
                        },
                        onTap = { tap ->
                            val rect = ImageOverlayGeometry.fitRect(
                                canvasWidth = size.width.toFloat(),
                                canvasHeight = size.height.toFloat(),
                                imageWidth = bitmap.width,
                                imageHeight = bitmap.height,
                            ) ?: return@detectTapGestures
                            val center = Offset(
                                rect.left + rect.width / 2f,
                                rect.top + rect.height / 2f,
                            )
                            val basePoint = Offset(
                                x = (tap.x - center.x - pan.x) / zoom + center.x,
                                y = (tap.y - center.y - pan.y) / zoom + center.y,
                            )
                            if (!rect.contains(Point2D(basePoint.x, basePoint.y))) {
                                currentSelection?.invoke(null)
                                return@detectTapGestures
                            }
                            val working = Point2D(
                                x = (basePoint.x - rect.left) * bitmap.width / rect.width,
                                y = (basePoint.y - rect.top) * bitmap.height / rect.height,
                            )
                            val hit = if (!showRegions) {
                                null
                            } else {
                                currentRegions.asReversed().firstOrNull { region ->
                                    val polygon = region.polygon.takeIf { it.size >= 3 }
                                    if (polygon != null) {
                                        ImageOverlayGeometry.containsPoint(working, polygon)
                                    } else {
                                        working.x in region.bounds.left..region.bounds.right &&
                                            working.y in region.bounds.top..region.bounds.bottom
                                    }
                                }
                            }
                            currentSelection?.invoke(hit?.id)
                        },
                    )
                }
                .pointerInput(bitmap, maxZoom) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val rect = ImageOverlayGeometry.fitRect(
                            canvasWidth = size.width.toFloat(),
                            canvasHeight = size.height.toFloat(),
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height,
                        ) ?: return@detectTransformGestures
                        val center = Offset(
                            rect.left + rect.width / 2f,
                            rect.top + rect.height / 2f,
                        )
                        val previousZoom = zoom
                        val nextZoom = (previousZoom * zoomChange).coerceIn(1f, maxZoom)
                        val factor = nextZoom / previousZoom
                        val nextPan = pan +
                            (centroid - center - pan) * (1f - factor) +
                            panChange
                        pan = clampPan(nextPan, rect, nextZoom)
                        zoom = nextZoom
                    }
                },
        ) {
            val rect = ImageOverlayGeometry.fitRect(
                canvasWidth = size.width,
                canvasHeight = size.height,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
            ) ?: return@Canvas
            val center = Offset(rect.left + rect.width / 2f, rect.top + rect.height / 2f)

            clipRect(0f, 0f, size.width, size.height) {
                withTransform({
                    translate(pan.x, pan.y)
                    scale(zoom, zoom, center)
                }) {
                    drawImage(
                        image = image,
                        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                        dstSize = IntSize(
                            rect.width.roundToInt().coerceAtLeast(1),
                            rect.height.roundToInt().coerceAtLeast(1),
                        ),
                        filterQuality = FilterQuality.Medium,
                    )

                    if (showRegions && regions.isNotEmpty()) {
                        clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                            drawRegions(
                                regions = regions,
                                selectedRegionId = selectedRegionId,
                                bitmapWidth = bitmap.width,
                                bitmapHeight = bitmap.height,
                                imageRect = rect,
                                zoom = zoom,
                                badgeTextPx = badgeTextSize,
                                badgeHeightPx = badgeHeight,
                                badgePaddingPx = badgePadding,
                                outlinePx = outlineWidth,
                                selectedOutlinePx = selectedOutlineWidth,
                                badgeFillPaint = badgeFillPaint,
                                badgeTextPaint = badgeTextPaint,
                                accent = colors.primary,
                                selectedAccent = colors.tertiary,
                                onAccent = colors.onPrimary,
                                onSelectedAccent = colors.onTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawRegions(
    regions: List<TextRegionRecord>,
    selectedRegionId: Int?,
    bitmapWidth: Int,
    bitmapHeight: Int,
    imageRect: ImageFitRect,
    zoom: Float,
    badgeTextPx: Float,
    badgeHeightPx: Float,
    badgePaddingPx: Float,
    outlinePx: Float,
    selectedOutlinePx: Float,
    badgeFillPaint: AndroidPaint,
    badgeTextPaint: AndroidPaint,
    accent: androidx.compose.ui.graphics.Color,
    selectedAccent: androidx.compose.ui.graphics.Color,
    onAccent: androidx.compose.ui.graphics.Color,
    onSelectedAccent: androidx.compose.ui.graphics.Color,
) {
    badgeFillPaint.strokeWidth = 0f

    regions.forEachIndexed { index, region ->
        val polygon = region.polygon.takeIf { it.size >= 3 } ?: region.bounds.toPolygon()
        val points = polygon.map { point ->
            Point2D(
                x = imageRect.left + point.x / bitmapWidth * imageRect.width,
                y = imageRect.top + point.y / bitmapHeight * imageRect.height,
            )
        }
        if (points.size < 3) return@forEachIndexed

        val selected = region.id == selectedRegionId
        val stroke = if (selected) selectedAccent else accent
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path = path, color = stroke.copy(alpha = if (selected) 0.13f else 0.035f))
        drawPath(
            path = path,
            color = stroke.copy(alpha = if (selected) 0.95f else 0.6f),
            style = Stroke(width = (if (selected) selectedOutlinePx else outlinePx) / zoom),
        )

        // A small chip just outside the top-left corner: number stays readable
        // while covering as little of the recognised text as possible.
        val label = (index + 1).toString()
        badgeTextPaint.textSize = badgeTextPx / zoom *
            if (label.length >= 3) 0.72f else 1f
        val chipHeight = badgeHeightPx / zoom
        val chipWidth = (badgeTextPaint.measureText(label) + badgePaddingPx * 2f / zoom)
            .coerceAtLeast(chipHeight)
        val maxChipLeft = (imageRect.right - chipWidth).coerceAtLeast(imageRect.left)
        val chipLeft = (points.minOf { it.x } - chipWidth * 0.22f)
            .coerceIn(imageRect.left, maxChipLeft)
        val maxChipTop = (imageRect.bottom - chipHeight).coerceAtLeast(imageRect.top)
        val chipTop = (points.minOf { it.y } - chipHeight * 0.72f)
            .coerceIn(imageRect.top, maxChipTop)
        badgeFillPaint.color = (if (selected) selectedAccent else accent).toArgb()
        drawContext.canvas.nativeCanvas.drawRoundRect(
            chipLeft,
            chipTop,
            chipLeft + chipWidth,
            chipTop + chipHeight,
            chipHeight / 2f,
            chipHeight / 2f,
            badgeFillPaint,
        )
        badgeTextPaint.color = (if (selected) onSelectedAccent else onAccent).toArgb()
        drawContext.canvas.nativeCanvas.drawText(
            label,
            chipLeft + chipWidth / 2f,
            chipTop + chipHeight / 2f -
                (badgeTextPaint.ascent() + badgeTextPaint.descent()) / 2f,
            badgeTextPaint,
        )

        if (selected) {
            val swatches = region.appearance?.alternativeColors?.takeIf { it.size > 1 }
                ?: region.characterColors.distinct().takeIf { it.size > 1 }
            swatches?.take(MAX_SWATCHES)?.let { colors ->
                val radius = chipHeight * 0.19f
                val step = radius * 2.5f
                var x = chipLeft + chipWidth / 2f - step * (colors.size - 1) / 2f
                val y = chipTop + chipHeight + radius * 1.7f
                colors.forEach { color ->
                    badgeFillPaint.color = color
                    drawContext.canvas.nativeCanvas.drawCircle(x, y, radius, badgeFillPaint)
                    x += step
                }
            }
        }
    }
}

private fun clampPan(pan: Offset, rect: ImageFitRect, zoom: Float): Offset {
    val maxX = rect.width * (zoom - 1f) / 2f
    val maxY = rect.height * (zoom - 1f) / 2f
    return Offset(
        x = pan.x.coerceIn(-maxX, maxX),
        y = pan.y.coerceIn(-maxY, maxY),
    )
}

private fun Bounds2D.toPolygon(): List<Point2D> = listOf(
    Point2D(left, top),
    Point2D(right, top),
    Point2D(right, bottom),
    Point2D(left, bottom),
)

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.6f
private const val MAX_SWATCHES = 4
