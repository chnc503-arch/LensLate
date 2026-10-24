package com.lenslate.app.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Creates a cache file exposed through FileProvider for the system camera. */
internal object CameraCapture {

    fun newCaptureUri(context: Context): Uri {
        val directory = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("lenslate-", ".jpg", directory)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
