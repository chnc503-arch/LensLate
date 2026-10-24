package com.lenslate.app.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.lenslate.app.ui.theme.LensLatePalette
import com.lenslate.app.ui.theme.LensLateTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val incomingImageUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        incomingImageUri.value = imageUriFrom(intent)
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        setContent {
            var paletteId by rememberSaveable {
                mutableStateOf(
                    preferences.getString(PALETTE_KEY, LensLatePalette.DEFAULT.preferenceId)
                        ?: LensLatePalette.DEFAULT.preferenceId,
                )
            }
            val palette = LensLatePalette.fromPreferenceId(paletteId)
            LensLateTheme(palette) {
                LensLateApp(
                    incomingImageUri = incomingImageUri.value,
                    palette = palette,
                    onPaletteSelected = { selected ->
                        paletteId = selected.preferenceId
                        preferences.edit().putString(PALETTE_KEY, selected.preferenceId).apply()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingImageUri.value = imageUriFrom(intent)
    }

    @Suppress("DEPRECATION")
    private fun imageUriFrom(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "lenslate_preferences"
        const val PALETTE_KEY = "selected_palette"
    }
}
