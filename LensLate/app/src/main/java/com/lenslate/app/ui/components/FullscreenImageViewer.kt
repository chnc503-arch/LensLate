package com.lenslate.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lenslate.app.model.TextRegionRecord
import kotlin.math.roundToInt

/**
 * Полноэкранный просмотр фото с пинч-зумом, панорамированием и двойным
 * касанием. В режиме оригинала остаются кликабельными OCR-блоки.
 */
@Composable
fun FullscreenImageViewer(
    title: String,
    original: Bitmap,
    translated: Bitmap?,
    regions: List<TextRegionRecord>,
    selectedRegionId: Int?,
    initialShowTranslated: Boolean,
    onRegionSelected: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var showTranslated by remember(translated) {
        mutableStateOf(translated != null && initialShowTranslated)
    }
    var resetKey by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val translationAvailable = translated != null
    val image = if (showTranslated && translated != null) translated else original
    val showRegions = !showTranslated && regions.isNotEmpty()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VIEWER_BACKGROUND),
        ) {
            OcrImageOverlay(
                bitmap = image,
                regions = if (showRegions) regions else emptyList(),
                selectedRegionId = if (showRegions) selectedRegionId else null,
                onRegionSelected = if (showRegions) onRegionSelected else null,
                showRegions = showRegions,
                resetKey = resetKey,
                allowDoubleTapZoom = true,
                maxZoom = VIEWER_MAX_ZOOM,
                onZoomChanged = { zoom = it },
                contentDescription = if (showRegions) {
                    "Полноэкранный оригинал с областями текста: ${regions.size}"
                } else {
                    "Полноэкранный просмотр изображения"
                },
                modifier = Modifier.fillMaxSize(),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.72f),
                                Color.Black.copy(alpha = 0.32f),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                        ),
                    )
                    .padding(start = 2.dp, end = 6.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Закрыть",
                        tint = Color.White,
                        modifier = Modifier.size(21.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp),
                ) {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.92f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${(zoom * 100f).roundToInt()}% · щипок или двойной тап — масштаб",
                        fontSize = 9.5.sp,
                        color = Color.White.copy(alpha = 0.55f),
                        maxLines = 1,
                    )
                }
                IconButton(onClick = { resetKey++ }) {
                    Icon(
                        imageVector = Icons.Outlined.FitScreen,
                        contentDescription = "Сбросить масштаб",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            if (translationAvailable) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.55f),
                                ),
                            ),
                        )
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                            ),
                        )
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ViewerModeToggle(
                        showTranslated = showTranslated,
                        onSelect = { translatedMode ->
                            if (translatedMode != showTranslated) {
                                showTranslated = translatedMode
                                resetKey++
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerModeToggle(
    showTranslated: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.58f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(50),
            )
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ViewerModeChip(
            label = "Оригинал",
            selected = !showTranslated,
            onClick = { onSelect(false) },
        )
        ViewerModeChip(
            label = "Перевод",
            selected = showTranslated,
            onClick = { onSelect(true) },
        )
    }
}

@Composable
private fun ViewerModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
        maxLines = 1,
    )
}

private val VIEWER_BACKGROUND = Color(0xFF07080A)
private const val VIEWER_MAX_ZOOM = 9f
