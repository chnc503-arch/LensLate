package com.lenslate.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.lenslate.app.model.AffineTransform2D
import com.lenslate.app.model.ScanImageInfo

data class DecodedImage(
    val bitmap: Bitmap,
    val imageInfo: ScanImageInfo,
)

object BitmapUtils {
    fun decode(context: Context, uri: Uri, maxDimension: Int = 2048): Bitmap =
        decodeWithMetadata(context, uri, maxDimension).bitmap

    fun decodeWithMetadata(context: Context, uri: Uri, maxDimension: Int = 2048): DecodedImage {
        require(maxDimension > 0) { "maxDimension must be positive." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Unable to open image." }
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image." }

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Unable to open image." }
            requireNotNull(BitmapFactory.decodeStream(stream, null, options)) { "Unable to decode image." }
        }
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri).use { stream ->
                requireNotNull(stream) { "Unable to open image." }
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = orientationMatrix(orientation)
        val oriented = if (matrix == null) decoded else Bitmap.createBitmap(
            decoded,
            0,
            0,
            decoded.width,
            decoded.height,
            matrix,
            true,
        ).also { if (it !== decoded) decoded.recycle() }

        val swapsDimensions = orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
        val orientedWidth = if (swapsDimensions) bounds.outHeight else bounds.outWidth
        val orientedHeight = if (swapsDimensions) bounds.outWidth else bounds.outHeight
        val imageInfo = ScanImageInfo(
            sourceUri = uri.toString(),
            encodedWidth = bounds.outWidth,
            encodedHeight = bounds.outHeight,
            exifOrientation = orientation,
            orientedWidth = orientedWidth,
            orientedHeight = orientedHeight,
            workingWidth = oriented.width,
            workingHeight = oriented.height,
            orientedSourceToWorking = AffineTransform2D.scale(
                x = oriented.width.toFloat() / orientedWidth,
                y = oriented.height.toFloat() / orientedHeight,
            ),
        )
        return DecodedImage(oriented, imageInfo)
    }

    private fun orientationMatrix(orientation: Int): Matrix? = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                setRotate(180f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(270f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            else -> return null
        }
    }
}
