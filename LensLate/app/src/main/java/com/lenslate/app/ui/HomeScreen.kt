package com.lenslate.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.lenslate.app.model.Language
import com.lenslate.app.model.LanguagePackInfo
import com.lenslate.app.model.PipelineProgress
import com.lenslate.app.model.StepState
import com.lenslate.app.model.TextRegionRecord
import com.lenslate.app.ui.components.FullscreenImageViewer
import com.lenslate.app.ui.components.OcrImageOverlay
import com.lenslate.app.ui.components.PalettePickerDialog
import com.lenslate.app.ui.components.RegionDetailsDialog
import com.lenslate.app.ui.theme.LensLatePalette
import com.lenslate.app.util.CameraCapture
import com.lenslate.app.util.ImageExport
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Композиция повторяет структуру ui_prototype.html: фиксированная шапка,
 * панель языков, зона загрузки, просмотр с OCR-рамками, панель обработки,
 * результаты, сохранение и менеджер on-device моделей.
 */
@Composable
fun LensLateApp(
    incomingImageUri: Uri?,
    palette: LensLatePalette,
    onPaletteSelected: (LensLatePalette) -> Unit,
) {
    val viewModel: ScanViewModel = hiltViewModel()
    val state = viewModel.state
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var previewMode by rememberSaveable { mutableStateOf(PreviewMode.ORIGINAL) }
    var selectedRegionId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showPalettePicker by rememberSaveable { mutableStateOf(false) }
    var detailsRegion by remember { mutableStateOf<Pair<Int, TextRegionRecord>?>(null) }
    var fitKey by rememberSaveable { mutableIntStateOf(0) }
    var handledIncomingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSaveBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var toastText by remember { mutableStateOf("") }
    var activeTab by rememberSaveable { mutableIntStateOf(0) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var showFullscreen by rememberSaveable { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            uri?.let {
                selectedRegionId = null
                detailsRegion = null
                showFullscreen = false
                fitKey++
                viewModel.loadImage(it)
            }
        },
    )

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            val uri = pendingCameraUri
            pendingCameraUri = null
            if (success && uri != null) {
                selectedRegionId = null
                detailsRegion = null
                showFullscreen = false
                fitKey++
                viewModel.loadImage(uri)
            } else if (!success) {
                viewModel.showMessage("Снимок не сохранён")
            }
        },
    )

    val takePhoto: () -> Unit = {
        val uri = runCatching { CameraCapture.newCaptureUri(context) }.getOrNull()
        if (uri == null) {
            viewModel.showMessage("Не удалось подготовить файл для камеры")
        } else {
            pendingCameraUri = uri
            runCatching { cameraLauncher.launch(uri) }
                .onFailure {
                    pendingCameraUri = null
                    viewModel.showMessage("Камера недоступна на этом устройстве")
                }
        }
    }

    val saveToGallery: (Bitmap) -> Unit = { bitmap ->
        scope.launch {
            ImageExport.savePng(context, bitmap, state.sourceName)
                .onSuccess {
                    viewModel.showMessage("Сохранено в Pictures/LensLate")
                }
                .onFailure {
                    viewModel.showMessage(it.message ?: "Не удалось сохранить изображение")
                }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            val bitmap = pendingSaveBitmap
            pendingSaveBitmap = null
            if (granted && bitmap != null) {
                saveToGallery(bitmap)
            } else if (!granted) {
                viewModel.showMessage("Без доступа к файлам сохранение в галерею невозможно")
            }
        },
    )
    val requestSave: (Bitmap) -> Unit = { bitmap ->
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingSaveBitmap = bitmap
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveToGallery(bitmap)
        }
    }
    val shareImage: (Bitmap) -> Unit = { bitmap ->
        scope.launch {
            ImageExport.share(context, bitmap, state.sourceName)
                .onFailure {
                    viewModel.showMessage(it.message ?: "Не удалось поделиться изображением")
                }
        }
    }

    LaunchedEffect(incomingImageUri) {
        val incoming = incomingImageUri?.toString() ?: return@LaunchedEffect
        if (handledIncomingUri != incoming) {
            handledIncomingUri = incoming
            previewMode = PreviewMode.ORIGINAL
            selectedRegionId = null
            detailsRegion = null
            fitKey++
            viewModel.loadImage(incomingImageUri)
        }
    }
    LaunchedEffect(state.original) {
        if (state.original == null) {
            previewMode = PreviewMode.ORIGINAL
            selectedRegionId = null
            detailsRegion = null
            showFullscreen = false
        }
    }
    LaunchedEffect(state.rendered) {
        if (state.rendered != null) {
            previewMode = PreviewMode.TRANSLATED
            selectedRegionId = null
            detailsRegion = null
            fitKey++
        }
    }
    LaunchedEffect(state.phase) {
        if (state.phase == ScanPhase.PROCESSING) {
            selectedRegionId = null
            detailsRegion = null
        }
    }
    val message = state.message
    LaunchedEffect(message?.id) {
        if (message != null) {
            toastText = message.text
            delay(TOAST_DURATION_MS)
            viewModel.consumeMessage(message.id)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppHeader(
                palette = palette,
                onPaletteClick = { showPalettePicker = true },
            )
            AppTabs(
                selectedTab = activeTab,
                onSelect = { activeTab = it },
            )
            if (activeTab == 0) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = MAX_CONTENT_WIDTH)
                        .align(Alignment.CenterHorizontally)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                            ),
                        ),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item { LanguageBar(state = state, viewModel = viewModel) }

                    state.appliedExperiment?.let { applied ->
                        item {
                            AppliedExperimentBanner(
                                applied = applied,
                                onClear = viewModel::clearAppliedExperiment,
                            )
                        }
                    }

                    state.error?.let { error ->
                        item {
                            ErrorBanner(
                                message = error,
                                onDismiss = viewModel::dismissError,
                            )
                        }
                    }

                    if (state.phase == ScanPhase.LOADING) {
                        item { LoadingImageCard() }
                    } else if (!state.hasImage) {
                        item {
                            UploadZone(
                                onPick = {
                                    imagePicker.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                                        ),
                                    )
                                },
                                onCamera = takePhoto,
                            )
                        }
                    } else {
                        item {
                            PreviewCard(
                                state = state,
                                previewMode = previewMode,
                                fitKey = fitKey,
                                selectedRegionId = selectedRegionId,
                                onPreviewMode = { mode ->
                                    val needsTranslation =
                                        mode == PreviewMode.TRANSLATED || mode == PreviewMode.COMPARE
                                    if (needsTranslation && !state.hasTranslation) {
                                        viewModel.showMessage("Сначала выполните перевод")
                                    } else {
                                        previewMode = mode
                                    }
                                },
                                onRegionSelected = { regionId ->
                                    selectedRegionId = regionId
                                    if (regionId != null && previewMode == PreviewMode.TRANSLATED &&
                                        state.hasTranslation
                                    ) {
                                        previewMode = PreviewMode.COMPARE
                                    }
                                },
                                onFit = { fitKey++ },
                                onCamera = takePhoto,
                                onExpand = { showFullscreen = true },
                                onClear = {
                                    viewModel.clear()
                                    selectedRegionId = null
                                    detailsRegion = null
                                    showFullscreen = false
                                    previewMode = PreviewMode.ORIGINAL
                                    fitKey++
                                },
                            )
                        }

                        item {
                            ProcessingPanel(state = state)
                        }

                        item {
                            TranslateButton(
                                state = state,
                                onClick = viewModel::runPipeline,
                            )
                        }

                        if (state.phase == ScanPhase.DONE && state.regions.isEmpty()) {
                            item { EmptyTextCard() }
                        }

                        if (state.regions.isNotEmpty() &&
                            (state.phase == ScanPhase.DONE || state.phase == ScanPhase.ERROR)
                        ) {
                            item {
                                ResultsCard(
                                    regions = state.regions,
                                    averageConfidence = state.averageConfidence,
                                    selectedRegionId = selectedRegionId,
                                    onRegionSelected = { regionId ->
                                        selectedRegionId = regionId
                                        if (regionId != null &&
                                            previewMode == PreviewMode.TRANSLATED
                                        ) {
                                            previewMode = PreviewMode.COMPARE
                                        }
                                    },
                                    onShowDetails = { index, region ->
                                        detailsRegion = index to region
                                    },
                                )
                            }
                        }

                        state.rendered?.let { rendered ->
                            item {
                                SaveBar(
                                    onSavePng = { requestSave(rendered) },
                                    onShare = { shareImage(rendered) },
                                )
                            }
                        }
                    }

                    item {
                        ModelManager(
                            packs = state.packs,
                            refreshing = state.refreshingModels,
                            enabled = !state.isBusy,
                            onDownload = viewModel::downloadModel,
                        )
                    }
                }
            } else {
                LabScreen(
                    state = state,
                    onRun = viewModel::runLabExperiments,
                    onPreview = viewModel::previewLabVariant,
                    onApply = viewModel::applyLabExperiment,
                    onClearApplied = viewModel::clearAppliedExperiment,
                    onClearPreview = viewModel::clearLabPreview,
                    onGoToScan = { activeTab = 0 },
                    onProtectEmbeddedImages = viewModel::setProtectEmbeddedImages,
                    onAggressiveSanitizer = viewModel::setAggressiveSanitizer,
                    onPreviewProtection = viewModel::previewProtectionMap,
                    onRunStyleStress = viewModel::runStyleStressTest,
                    onRunSmart = viewModel::runSmartLab,
                    onClearSmart = viewModel::clearSmartResult,
                    onLoadLabPage = viewModel::loadLabPage,
                    onRefreshLabPages = viewModel::refreshLabPages,
                )
            }
        }

        AnimatedVisibility(
            visible = message != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 2 },
        ) {
            ToastCard(text = toastText)
        }
    }

    if (showPalettePicker) {
        PalettePickerDialog(
            selectedPalette = palette,
            onDismiss = { showPalettePicker = false },
            onPaletteSelected = { selected ->
                onPaletteSelected(selected)
                showPalettePicker = false
            },
        )
    }

    detailsRegion?.let { (index, region) ->
        RegionDetailsDialog(
            region = region,
            index = index + 1,
            onDismiss = { detailsRegion = null },
        )
    }

    val fullscreenOriginal = state.original
    if (showFullscreen && fullscreenOriginal != null) {
        FullscreenImageViewer(
            title = state.sourceName ?: "document.png",
            original = fullscreenOriginal,
            translated = state.rendered,
            regions = state.regions,
            selectedRegionId = selectedRegionId,
            initialShowTranslated = previewMode != PreviewMode.ORIGINAL && state.hasTranslation,
            onRegionSelected = { selectedRegionId = it },
            onDismiss = { showFullscreen = false },
        )
    }
}

/* ───────────────────────────── Header ───────────────────────────── */

@Composable
private fun AppTabs(
    selectedTab: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = MAX_CONTENT_WIDTH)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceVariant.copy(alpha = 0.5f))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            AppTab(
                label = "Сканирование",
                icon = Icons.Outlined.DocumentScanner,
                selected = selectedTab == 0,
                onClick = { onSelect(0) },
                modifier = Modifier.weight(1f),
            )
            AppTab(
                label = "Тестовые функции",
                icon = Icons.Outlined.Science,
                selected = selectedTab == 1,
                onClick = { onSelect(1) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AppTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.primary.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppHeader(
    palette: LensLatePalette,
    onPaletteClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = MAX_CONTENT_WIDTH)
                .align(Alignment.CenterHorizontally)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                    ),
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoMark()
            Spacer(Modifier.width(9.dp))
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.onSurface)) { append("Lens") }
                    withStyle(SpanStyle(color = colors.primary)) { append("Late") }
                },
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.height(28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                palette.swatches.take(3).forEach { swatch ->
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(swatch),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable(onClick = onPaletteClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Palette,
                    contentDescription = "Выбрать палитру",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = colors.outlineVariant.copy(alpha = 0.65f),
        )
    }
}

@Composable
private fun LogoMark() {
    val colors = MaterialTheme.colorScheme
    Canvas(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.primary),
    ) {
        val s = size.minDimension
        val inset = s * 0.21f
        val stroke = (s * 0.07f).coerceAtLeast(1f)
        drawRoundRect(
            color = colors.onPrimary,
            topLeft = Offset(inset, inset),
            size = Size(s - inset * 2f, s - inset * 2f),
            cornerRadius = CornerRadius(s * 0.14f),
            style = Stroke(width = stroke),
        )
        val lineStart = s * 0.34f
        listOf(0.62f, 0.57f, 0.52f).forEachIndexed { index, end ->
            val y = s * (0.36f + index * 0.13f)
            drawLine(
                color = colors.onPrimary,
                start = Offset(lineStart, y),
                end = Offset(s * end, y),
                strokeWidth = stroke * 0.9f,
                cap = StrokeCap.Round,
            )
        }
        val lensCenter = Offset(s * 0.69f, s * 0.66f)
        drawCircle(
            color = colors.onPrimary.copy(alpha = 0.30f),
            radius = s * 0.14f,
            center = lensCenter,
        )
        drawLine(
            color = colors.onPrimary,
            start = Offset(lensCenter.x - s * 0.07f, lensCenter.y),
            end = Offset(lensCenter.x + s * 0.07f, lensCenter.y),
            strokeWidth = stroke * 0.6f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = colors.onPrimary,
            start = Offset(lensCenter.x, lensCenter.y - s * 0.07f),
            end = Offset(lensCenter.x, lensCenter.y + s * 0.07f),
            strokeWidth = stroke * 0.6f,
            cap = StrokeCap.Round,
        )
    }
}

/* ─────────────────────────── Language bar ───────────────────────── */

@Composable
private fun LanguageBar(
    state: ScanUiState,
    viewModel: ScanViewModel,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            val wide = maxWidth >= 520.dp
            if (wide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "ПЕРЕВОД",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.9.sp,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    LanguageDropdown(
                        selected = state.fromLanguage,
                        allowAuto = true,
                        detected = state.detectedLanguage,
                        enabled = !state.isBusy,
                        onSelected = viewModel::setFromLanguage,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    SwapButton(
                        enabled = !state.isBusy && state.fromLanguage != null,
                        onClick = viewModel::swapLanguages,
                    )
                    Spacer(Modifier.width(8.dp))
                    LanguageDropdown(
                        selected = state.toLanguage,
                        allowAuto = false,
                        enabled = !state.isBusy,
                        onSelected = { language -> language?.let(viewModel::setToLanguage) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(14.dp))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(26.dp)
                            .background(colors.outlineVariant),
                    )
                    Spacer(Modifier.width(14.dp))
                    QualityBadge()
                }
            } else {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "ПЕРЕВОД",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.9.sp,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        QualityBadge()
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LanguageDropdown(
                            selected = state.fromLanguage,
                            allowAuto = true,
                            detected = state.detectedLanguage,
                            enabled = !state.isBusy,
                            onSelected = viewModel::setFromLanguage,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        SwapButton(
                            enabled = !state.isBusy && state.fromLanguage != null,
                            onClick = viewModel::swapLanguages,
                        )
                        Spacer(Modifier.width(8.dp))
                        LanguageDropdown(
                            selected = state.toLanguage,
                            allowAuto = false,
                            enabled = !state.isBusy,
                            onSelected = { language -> language?.let(viewModel::setToLanguage) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityBadge() {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.secondary.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(colors.secondary),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "ML Kit · Офлайн",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
            color = colors.secondary,
        )
    }
}

@Composable
private fun LanguageDropdown(
    selected: Language?,
    allowAuto: Boolean,
    enabled: Boolean,
    onSelected: (Language?) -> Unit,
    modifier: Modifier = Modifier,
    detected: Language? = null,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val label = when {
        selected != null -> selected.displayName
        detected != null -> "Авто · ${detected.code.uppercase(Locale.US)}"
        else -> "Авто"
    }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .background(colors.surfaceVariant.copy(alpha = if (enabled) 0.55f else 0.30f))
                .border(
                    width = if (expanded) 1.5.dp else 1.dp,
                    color = if (expanded) colors.primary
                    else colors.outlineVariant.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(7.dp),
                )
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 9.dp, vertical = 8.dp)
                .alpha(if (enabled) 1f else 0.55f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected == null) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.8f)),
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(min = 218.dp),
        ) {
            if (allowAuto) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Авто-определение",
                            fontSize = 13.sp,
                            fontWeight = if (selected == null) FontWeight.SemiBold
                            else FontWeight.Normal,
                            color = colors.onSurface,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                    },
                    trailingIcon = {
                        if (selected == null) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelected(null)
                    },
                )
            }
            Language.entries.forEach { language ->
                val isSelected = language == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            language.displayName,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold
                            else FontWeight.Normal,
                            color = colors.onSurface,
                        )
                    },
                    leadingIcon = {
                        LanguageTag(
                            code = language.code.uppercase(Locale.US),
                            highlighted = isSelected,
                        )
                    },
                    trailingIcon = {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelected(language)
                    },
                )
            }
        }
    }
}

@Composable
private fun LanguageTag(
    code: String,
    highlighted: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (highlighted) colors.primary.copy(alpha = 0.16f)
                else colors.surface.copy(alpha = 0.55f),
            )
            .padding(horizontal = 5.dp, vertical = 2.5.dp),
    ) {
        Text(
            text = code,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
            color = if (highlighted) colors.primary else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwapButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (enabled) colors.primary.copy(alpha = 0.14f)
                else colors.surfaceVariant.copy(alpha = 0.4f),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.SwapHoriz,
            contentDescription = "Поменять языки",
            tint = if (enabled) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
    }
}

/* ─────────────────────────── Upload zone ────────────────────────── */

@Composable
private fun LoadingImageCard() {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = colors.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Открываем изображение…",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Text(
                    text = "Чтение файла и поворот по EXIF",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UploadZone(
    onPick: () -> Unit,
    onCamera: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .dashedBorder(
                width = 1.6.dp,
                color = colors.outlineVariant,
                cornerRadius = 16.dp,
            )
            .clickable(onClick = onPick)
            .padding(horizontal = 24.dp, vertical = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Загрузите изображение",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Технический чертёж, документ, схема — любой формат с текстом",
                modifier = Modifier.widthIn(max = 330.dp),
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("PNG", "JPG", "WEBP", "BMP").forEach { type ->
                    TypeChip(type)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onCamera,
                    modifier = Modifier.height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.65f)),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoCamera,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Сделать фото",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Button(
                    onClick = onPick,
                    modifier = Modifier.height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                ) {
                    Text(
                        text = "Выбрать файл",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Камера открывается на весь экран",
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TypeChip(type: String) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.85f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = type,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
            color = colors.onSurfaceVariant,
        )
    }
}

private fun Modifier.dashedBorder(
    width: Dp,
    color: Color,
    cornerRadius: Dp,
): Modifier = drawBehind {
    val stroke = width.toPx()
    val dash = 9.dp.toPx()
    val gap = 5.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(
            (size.width - stroke).coerceAtLeast(0f),
            (size.height - stroke).coerceAtLeast(0f),
        ),
        cornerRadius = CornerRadius(cornerRadius.toPx()),
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), 0f),
        ),
    )
}

/* ─────────────────────────── Preview card ───────────────────────── */

@Composable
private fun PreviewCard(
    state: ScanUiState,
    previewMode: PreviewMode,
    fitKey: Int,
    selectedRegionId: Int?,
    onPreviewMode: (PreviewMode) -> Unit,
    onRegionSelected: (Int?) -> Unit,
    onFit: () -> Unit,
    onCamera: () -> Unit,
    onExpand: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 520.dp
        val image = state.original ?: return@BoxWithConstraints
        val aspect = image.width.toFloat().coerceAtLeast(1f) /
            image.height.toFloat().coerceAtLeast(1f)
        val imageHeight = if (previewMode == PreviewMode.COMPARE && state.hasTranslation) {
            ((maxWidth / aspect) * 1.8f).coerceIn(420.dp, 640.dp)
        } else {
            (maxWidth / aspect).coerceIn(200.dp, 420.dp)
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
        ) {
            Column {
                if (compact) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PreviewFileName(
                                name = state.sourceName ?: "document.png",
                                modifier = Modifier.weight(1f),
                            )
                            ToolbarIcon(
                                icon = Icons.Outlined.FitScreen,
                                label = "Вписать",
                                tint = colors.onSurfaceVariant,
                                onClick = onFit,
                            )
                            ToolbarIcon(
                                icon = Icons.Outlined.Fullscreen,
                                label = "На весь экран",
                                tint = colors.onSurfaceVariant,
                                onClick = onExpand,
                            )
                            ToolbarIcon(
                                icon = Icons.Outlined.PhotoCamera,
                                label = "Сделать фото",
                                tint = colors.onSurfaceVariant,
                                onClick = onCamera,
                            )
                            ToolbarIcon(
                                icon = Icons.Outlined.Close,
                                label = "Очистить",
                                tint = colors.error,
                                onClick = onClear,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        ViewToggle(
                            mode = previewMode,
                            onSelect = onPreviewMode,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PreviewFileName(
                            name = state.sourceName ?: "document.png",
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        ViewToggle(mode = previewMode, onSelect = onPreviewMode)
                        Spacer(Modifier.width(8.dp))
                        ToolbarLabel(
                            icon = Icons.Outlined.FitScreen,
                            label = "Вписать",
                            tint = colors.onSurfaceVariant,
                            onClick = onFit,
                        )
                        ToolbarIcon(
                            icon = Icons.Outlined.Fullscreen,
                            label = "На весь экран",
                            tint = colors.onSurfaceVariant,
                            onClick = onExpand,
                        )
                        ToolbarIcon(
                            icon = Icons.Outlined.PhotoCamera,
                            label = "Сделать фото",
                            tint = colors.onSurfaceVariant,
                            onClick = onCamera,
                        )
                        ToolbarLabel(
                            icon = Icons.Outlined.Close,
                            label = "Очистить",
                            tint = colors.error,
                            onClick = onClear,
                        )
                    }
                }

                HorizontalDivider(
                    thickness = 1.dp,
                    color = colors.outlineVariant.copy(alpha = 0.55f),
                )

                ImagePane(
                    state = state,
                    previewMode = previewMode,
                    fitKey = fitKey,
                    selectedRegionId = selectedRegionId,
                    onRegionSelected = onRegionSelected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(imageHeight)
                        .background(colors.surfaceVariant.copy(alpha = 0.32f))
                        .clipToBounds(),
                )
            }
        }
    }
}

@Composable
private fun PreviewFileName(name: String, modifier: Modifier = Modifier) {
    Text(
        text = name,
        modifier = modifier,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ViewToggle(
    mode: PreviewMode,
    onSelect: (PreviewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.55f))
            .padding(2.dp),
    ) {
        PreviewMode.entries.forEach { option ->
            val selected = option == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        if (selected) colors.primary.copy(alpha = 0.16f)
                        else Color.Transparent,
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) colors.primary else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ToolbarIcon(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ToolbarLabel(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = tint,
        )
    }
}

@Composable
private fun ImagePane(
    state: ScanUiState,
    previewMode: PreviewMode,
    fitKey: Int,
    selectedRegionId: Int?,
    onRegionSelected: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val original = state.original ?: return
    val rendered = state.rendered
    Box(modifier = modifier) {
        when (previewMode) {
            PreviewMode.ORIGINAL -> OcrImageOverlay(
                bitmap = original,
                regions = state.regions,
                selectedRegionId = selectedRegionId,
                onRegionSelected = onRegionSelected,
                showRegions = true,
                resetKey = fitKey,
                modifier = Modifier.fillMaxSize(),
            )

            PreviewMode.TRANSLATED -> OcrImageOverlay(
                bitmap = rendered ?: original,
                regions = emptyList(),
                selectedRegionId = null,
                onRegionSelected = null,
                showRegions = false,
                resetKey = fitKey,
                contentDescription = if (rendered != null) {
                    "Переведённое изображение"
                } else {
                    "Изображение"
                },
                modifier = Modifier.fillMaxSize(),
            )

            PreviewMode.COMPARE -> if (rendered == null) {
                OcrImageOverlay(
                    bitmap = original,
                    regions = state.regions,
                    selectedRegionId = selectedRegionId,
                    onRegionSelected = onRegionSelected,
                    showRegions = true,
                    resetKey = fitKey,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val sideBySide = original.width >= original.height
                if (sideBySide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            OcrImageOverlay(
                                bitmap = original,
                                regions = state.regions,
                                selectedRegionId = selectedRegionId,
                                onRegionSelected = onRegionSelected,
                                showRegions = true,
                                resetKey = fitKey,
                                contentDescription = "Оригинал с областями текста",
                                modifier = Modifier.fillMaxSize(),
                            )
                            PaneLabel(
                                text = "ОРИГИНАЛ",
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            OcrImageOverlay(
                                bitmap = rendered,
                                regions = emptyList(),
                                selectedRegionId = null,
                                onRegionSelected = null,
                                showRegions = false,
                                resetKey = fitKey,
                                contentDescription = "Переведённое изображение",
                                modifier = Modifier.fillMaxSize(),
                            )
                            PaneLabel(
                                text = "ПЕРЕВОД",
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp),
                            )
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            OcrImageOverlay(
                                bitmap = original,
                                regions = state.regions,
                                selectedRegionId = selectedRegionId,
                                onRegionSelected = onRegionSelected,
                                showRegions = true,
                                resetKey = fitKey,
                                contentDescription = "Оригинал с областями текста",
                                modifier = Modifier.fillMaxSize(),
                            )
                            PaneLabel(
                                text = "ОРИГИНАЛ",
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp),
                            )
                        }
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            OcrImageOverlay(
                                bitmap = rendered,
                                regions = emptyList(),
                                selectedRegionId = null,
                                onRegionSelected = null,
                                showRegions = false,
                                resetKey = fitKey,
                                contentDescription = "Переведённое изображение",
                                modifier = Modifier.fillMaxSize(),
                            )
                            PaneLabel(
                                text = "ПЕРЕВОД",
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaneLabel(text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(5.dp),
        color = colors.surface.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = colors.onSurfaceVariant,
        )
    }
}

/* ───────────────────────── Processing panel ─────────────────────── */

@Composable
private fun ProcessingPanel(state: ScanUiState) {
    val colors = MaterialTheme.colorScheme
    val progress = state.progress
    val steps = remember(state.progress) {
        listOf(
            PipelineStep(
                name = "OCR — распознавание текста",
                defaultDetail = "ML Kit Text Recognition v2 · Tesseract",
                icon = Icons.Outlined.TextSnippet,
                progress = progress.ocr,
            ),
            PipelineStep(
                name = "Определение языка",
                defaultDetail = "Анализ скрипта и символов",
                icon = Icons.Outlined.Language,
                progress = progress.langDetect,
            ),
            PipelineStep(
                name = "Перевод",
                defaultDetail = "ML Kit Translation · on-device",
                icon = Icons.Outlined.Translate,
                progress = progress.translate,
            ),
            PipelineStep(
                name = "Восстановление фона",
                defaultDetail = "Маска по глифам · направленная интерполяция",
                icon = Icons.Outlined.AutoFixHigh,
                progress = progress.inpaint,
            ),
            PipelineStep(
                name = "Рендеринг текста",
                defaultDetail = "Шрифт, цвет, масштаб, трекинг",
                icon = Icons.Outlined.TextFields,
                progress = progress.render,
            ),
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Обработка",
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                ProcessingStatus(state = state)
            }

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                steps.forEach { step ->
                    PipelineStepRow(step = step)
                }
            }

            Spacer(Modifier.height(14.dp))

            val fraction by animateFloatAsState(
                targetValue = when (state.phase) {
                    ScanPhase.PROCESSING -> progress.progressFraction.coerceIn(0.04f, 0.96f)
                    ScanPhase.DONE -> 1f
                    ScanPhase.ERROR -> progress.progressFraction.coerceIn(0f, 0.96f)
                    else -> 0f
                },
                animationSpec = tween(420),
                label = "pipeline-progress",
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = colors.primary,
                trackColor = colors.surfaceVariant,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProcessingStatus(state: ScanUiState) {
    val colors = MaterialTheme.colorScheme
    val (text, tint) = when {
        state.phase == ScanPhase.PROCESSING -> "В процессе" to colors.primary
        state.phase == ScanPhase.ERROR -> "Ошибка" to colors.error
        state.phase == ScanPhase.DONE && state.regions.isEmpty() ->
            "Текст не найден" to colors.tertiary
        state.phase == ScanPhase.DONE -> "Готово" to colors.secondary
        else -> null to colors.onSurfaceVariant
    }
    if (text != null) {
        StatChip(text = text, tint = tint)
    }
}

private data class PipelineStep(
    val name: String,
    val defaultDetail: String,
    val icon: ImageVector,
    val progress: StepState,
)

@Composable
private fun PipelineStepRow(step: PipelineStep) {
    val colors = MaterialTheme.colorScheme
    val state = step.progress
    val detail = when (state) {
        is StepState.Running -> state.detail.ifBlank { step.defaultDetail }
        is StepState.Done -> state.detail.ifBlank { step.defaultDetail }
        is StepState.Error -> state.message
        StepState.Idle -> step.defaultDetail
    }
    val detailColor = when (state) {
        is StepState.Running -> colors.primary
        is StepState.Error -> colors.error
        else -> colors.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepBadge(step = step, state = state)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.name,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
            )
            Text(
                text = detail,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = detailColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val time = (state as? StepState.Done)?.timeMs?.let(::formatStepTime)
        if (!time.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = time,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepBadge(step: PipelineStep, state: StepState) {
    val colors = MaterialTheme.colorScheme
    val background = when (state) {
        is StepState.Running -> colors.primary.copy(alpha = 0.14f)
        is StepState.Done -> colors.secondary.copy(alpha = 0.15f)
        is StepState.Error -> colors.errorContainer
        StepState.Idle -> colors.surfaceVariant.copy(alpha = 0.6f)
    }
    val foreground = when (state) {
        is StepState.Running -> colors.primary
        is StepState.Done -> colors.secondary
        is StepState.Error -> colors.onErrorContainer
        StepState.Idle -> colors.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is StepState.Running -> CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                strokeWidth = 1.8.dp,
                color = foreground,
            )
            is StepState.Done -> Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(15.dp),
            )
            is StepState.Error -> Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(15.dp),
            )
            StepState.Idle -> Icon(
                imageVector = step.icon,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/* ─────────────────────────── Translate CTA ──────────────────────── */

@Composable
private fun TranslateButton(
    state: ScanUiState,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = state.hasImage && !state.isBusy,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            disabledContainerColor = colors.primary.copy(alpha = 0.35f),
            disabledContentColor = colors.onPrimary.copy(alpha = 0.55f),
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 2.dp,
            pressedElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
    ) {
        if (state.isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = colors.onPrimary.copy(alpha = 0.8f),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = "Обработка…",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Translate,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = if (state.hasTranslation) "Перевести снова" else "Перевести",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun EmptyTextCard() {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = colors.tertiary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Текст не найден. Попробуйте более чёткое фото или выберите " +
                    "подходящий язык распознавания.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/* ───────────────────────────── Results ──────────────────────────── */

@Composable
private fun ResultsCard(
    regions: List<TextRegionRecord>,
    averageConfidence: Float?,
    selectedRegionId: Int?,
    onRegionSelected: (Int?) -> Unit,
    onShowDetails: (Int, TextRegionRecord) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val listHeight = (regions.size * 84 + 24).dp.coerceIn(120.dp, 300.dp)
    val averagePercent = averageConfidence?.let { (it * 100f).roundToInt() }

    LaunchedEffect(selectedRegionId) {
        val index = regions.indexOfFirst { it.id == selectedRegionId }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                val compactHeader = maxWidth < 380.dp
                val title: @Composable () -> Unit = {
                    Text(
                        text = "Найденные блоки текста",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                }
                val chips: @Composable () -> Unit = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatChip(
                            text = blocksLabel(regions.size),
                            tint = colors.secondary,
                        )
                        if (averagePercent != null) {
                            StatChip(
                                text = "$averagePercent% уверенность",
                                tint = colors.primary,
                            )
                        }
                    }
                }
                if (compactHeader) {
                    Column {
                        title()
                        Spacer(Modifier.height(7.dp))
                        chips()
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) { title() }
                        chips()
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(listHeight),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(regions, key = { _, region -> region.id }) { index, region ->
                    ResultItem(
                        index = index + 1,
                        region = region,
                        selected = region.id == selectedRegionId,
                        onClick = {
                            onRegionSelected(if (region.id == selectedRegionId) null else region.id)
                        },
                        onShowDetails = { onShowDetails(index, region) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultItem(
    index: Int,
    region: TextRegionRecord,
    selected: Boolean,
    onClick: () -> Unit,
    onShowDetails: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val confidence = region.overallConfidence ?: region.confidence
    val dotColor = when {
        confidence == null -> colors.outline
        confidence >= 0.92f -> colors.secondary
        confidence >= 0.8f -> colors.tertiary
        else -> colors.error
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.surface,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.7f),
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceVariant.copy(alpha = 0.52f))
                    .clickable(onClick = onClick)
                    .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = index.toString(),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.onPrimary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = region.text.replace('\n', ' '),
                        fontSize = 11.5.sp,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = confidence?.let {
                                "${(it.coerceIn(0f, 1f) * 100f).roundToInt()}% уверенность"
                            } ?: "уверенность неизвестна",
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceVariant,
                        )
                        if (region.isFormula) {
                            Spacer(Modifier.width(6.dp))
                            StatChip("ФОРМУЛА", colors.tertiary)
                        }
                        if (region.isProtected) {
                            Spacer(Modifier.width(6.dp))
                            StatChip("ИЗОБРАЖЕНИЕ", colors.secondary)
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onShowDetails),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Параметры блока",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    text = when {
                        region.isProtected ->
                            region.protectionReason?.let { "$it — оставлено без изменений" }
                                ?: "Текст внутри изображения — оставлено без изменений"

                        region.isFormula ->
                            "Формула / числовое обозначение — оставлено без изменений"

                        else -> region.translation?.takeIf { it.isNotBlank() }
                            ?: "Перевод недоступен"
                    },
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (region.isFormula || region.isProtected ||
                        region.translation.isNullOrBlank()
                    ) {
                        colors.onSurfaceVariant
                    } else {
                        colors.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun StatChip(
    text: String,
    tint: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
            color = tint,
            maxLines = 1,
        )
    }
}

/* ───────────────────────────── Save bar ─────────────────────────── */

@Composable
private fun SaveBar(
    onSavePng: () -> Unit,
    onShare: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 520.dp
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
        ) {
            if (compact) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Результат готов — сохраните переведённое изображение",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SavePngButton(onClick = onSavePng, modifier = Modifier.weight(1f))
                        ShareButton(onClick = onShare, modifier = Modifier.weight(1f))
                    }
                }
            } else {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Результат готов — сохраните переведённое изображение",
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SavePngButton(onClick = onSavePng)
                        ShareButton(onClick = onShare)
                    }
                }
            }
        }
    }
}

@Composable
private fun SavePngButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Save,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Сохранить PNG",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ShareButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, colors.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Share,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Поделиться",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/* ─────────────────────────── Model manager ──────────────────────── */

@Composable
private fun ModelManager(
    packs: List<LanguagePackInfo>,
    refreshing: Boolean,
    enabled: Boolean,
    onDownload: (Language) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val rows = remember(packs) {
        if (packs.isNotEmpty()) {
            packs
        } else {
            Language.entries.map { LanguagePackInfo(language = it, isInstalled = false) }
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Языковые модели",
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.6.dp,
                        color = colors.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                StatChip(text = "ML Kit", tint = colors.primary)
            }
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                rows.forEach { pack ->
                    ModelRow(
                        pack = pack,
                        enabled = enabled && !refreshing,
                        onDownload = { onDownload(pack.language) },
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun ModelRow(
    pack: LanguagePackInfo,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceVariant.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = pack.language.code.uppercase(Locale.US),
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "ML Kit · ${pack.language.displayName}",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
            )
            Text(
                text = if (pack.isInstalled) {
                    "загружена · ≈ ${pack.approximateSizeMb} МБ"
                } else {
                    "≈ ${pack.approximateSizeMb} МБ · офлайн после загрузки"
                },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            pack.isDownloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .width(52.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = colors.primary,
                    trackColor = colors.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "загрузка",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceVariant,
                )
            }
            pack.isInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = colors.secondary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Установлена",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.secondary,
                )
            }
            else -> DownloadPill(enabled = enabled, onClick = onDownload)
        }
    }
}

@Composable
private fun DownloadPill(enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (enabled) colors.primary.copy(alpha = 0.14f)
                else colors.surfaceVariant.copy(alpha = 0.4f),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = "Скачать",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) colors.primary else colors.onSurfaceVariant,
        )
    }
}

/* ───────────────────────────── Auxiliary ────────────────────────── */

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.errorContainer,
        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = colors.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colors.onErrorContainer,
            )
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Скрыть сообщение",
                    tint = colors.onErrorContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ToastCard(text: String) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.widthIn(max = 340.dp),
        shape = RoundedCornerShape(10.dp),
        color = colors.inverseSurface,
        contentColor = colors.inverseOnSurface,
        shadowElevation = 8.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = colors.inverseOnSurface,
            textAlign = TextAlign.Center,
        )
    }
}

private val PreviewMode.label: String
    get() = when (this) {
        PreviewMode.ORIGINAL -> "Оригинал"
        PreviewMode.TRANSLATED -> "Перевод"
        PreviewMode.COMPARE -> "Оба"
    }

private fun blocksLabel(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val word = when {
        mod100 in 11..14 -> "блоков"
        mod10 == 1 -> "блок"
        mod10 in 2..4 -> "блока"
        else -> "блоков"
    }
    return "$count $word"
}

private fun formatStepTime(milliseconds: Long): String {
    if (milliseconds <= 0L) return ""
    val seconds = milliseconds / 1000f
    return if (seconds < 10f) {
        String.format(Locale.US, "%.2f с", seconds)
    } else {
        "${seconds.roundToInt()} с"
    }
}

internal val MAX_CONTENT_WIDTH = 960.dp
private const val TOAST_DURATION_MS = 3000L
