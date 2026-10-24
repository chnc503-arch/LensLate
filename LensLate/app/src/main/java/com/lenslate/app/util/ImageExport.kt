package com.lenslate.app.util

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Сохранение результата в галерею и передача через FileProvider. */
object ImageExport {

    suspend fun savePng(
        context: Context,
        bitmap: Bitmap,
        baseName: String?,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = buildFileName(baseName)
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/LensLate",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать файл в галерее")
            try {
                resolver.openOutputStream(uri)?.use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        "Не удалось записать PNG"
                    }
                } ?: error("Не удалось открыть файл для записи")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                uri
            } catch (exception: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw exception
            }
        }
    }

    suspend fun share(
        context: Context,
        bitmap: Bitmap,
        baseName: String?,
    ): Result<Unit> {
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                val directory = File(context.cacheDir, "shared").apply { mkdirs() }
                val file = File(directory, buildFileName(baseName))
                FileOutputStream(file).use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        "Не удалось подготовить изображение"
                    }
                }
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }
        }
        return prepared.mapCatching { uri ->
            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(context.contentResolver, "LensLate", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Поделиться переводом"))
            }
        }
    }

    private fun buildFileName(baseName: String?): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${sanitize(baseName)}_translated_$stamp.png"
    }

    private fun sanitize(baseName: String?): String {
        val withoutExtension = baseName
            ?.substringBeforeLast('.', baseName)
            ?.trim()
            .orEmpty()
        return withoutExtension
            .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "lenslate" }
    }
}
