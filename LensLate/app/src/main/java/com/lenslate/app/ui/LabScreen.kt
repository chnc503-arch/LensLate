package com.lenslate.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenslate.app.model.DocumentAnalysis
import com.lenslate.app.model.DocumentKind
import com.lenslate.app.model.TextRegionRecord
import com.lenslate.app.pipeline.OcrEngineChoice
import com.lenslate.app.pipeline.OcrExperimentResult
import com.lenslate.app.pipeline.StyleStressLab
import java.util.Locale
import kotlin.math.roundToInt

/**
 * "Тестовые функции": прогоняет все гипотезы предобработки через выбранный OCR
 * и позволяет применить лучшую к обычному скану. Обычный скан эти настройки
 * не использует, пока пользователь явно не нажмёт «Применить».
 */
@Composable
internal fun LabScreen(
    state: ScanUiState,
    onRun: (OcrEngineChoice) -> Unit,
    onPreview: (String) -> Unit,
    onApply: (OcrEngineChoice, String) -> Unit,
    onClearApplied: () -> Unit,
    onClearPreview: () -> Unit,
    onGoToScan: () -> Unit,
    onProtectEmbeddedImages: (Boolean) -> Unit,
    onAggressiveSanitizer: (Boolean) -> Unit,
    onPreviewProtection: () -> Unit,
    onRunStyleStress: () -> Unit,
) {
    var engineName by rememberSaveable { mutableStateOf(OcrEngineChoice.AUTO.name) }
    val engine = OcrEngineChoice.entries.firstOrNull { it.name == engineName }
        ?: OcrEngineChoice.AUTO

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = MAX_CONTENT_WIDTH)
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            LabIntroCard()
        }

        state.appliedExperiment?.let { applied ->
            item {
                AppliedExperimentBanner(
                    applied = applied,
                    onClear = onClearApplied,
                )
            }
        }

        item {
            LabSectionHeader(
                title = "Качество наложения",
                subtitle = "Синтетические сложные снимки: градиент, замыленность, пересвет, низкий контраст",
            )
        }

        item {
            OverlayStressCard(
                report = state.styleStress,
                running = state.styleStressRunning,
                onRun = onRunStyleStress,
            )
        }

        if (!state.hasImage) {
            item {
                LabEmptyState(onGoToScan = onGoToScan)
            }
            return@LazyColumn
        }

        item {
            LabSectionHeader(
                title = "Интеллект документа",
                subtitle = "Определяем тип страницы и решаем, что переводить нельзя",
            )
        }

        item {
            DocumentInsightCard(
                analysis = state.document?.analysis,
                regions = state.regions,
                protectionEnabled = state.protectEmbeddedImages,
                onGoToScan = onGoToScan,
            )
        }

        item {
            LabSafetySwitches(
                protectionEnabled = state.protectEmbeddedImages,
                aggressiveSanitizer = state.aggressiveSanitizer,
                enabled = !state.isBusy,
                hasAnalysis = state.document?.analysis != null,
                candidateCount = state.document?.analysis?.protectedCount ?: 0,
                onProtectEmbeddedImages = onProtectEmbeddedImages,
                onAggressiveSanitizer = onAggressiveSanitizer,
                onPreviewProtection = onPreviewProtection,
            )
        }

        item {
            LabSectionHeader(
                title = "Распознавание",
                subtitle = "Комбинированные рецепты предобработки и их оценка",
            )
        }

        item {
            LabEngineSelector(
                selected = engine,
                enabled = !state.isBusy,
                onSelect = { engineName = it.name },
            )
        }

        item {
            LabRunCard(
                state = state,
                engine = engine,
                onRun = onRun,
            )
        }

        state.labPreview?.let { preview ->
            item {
                LabPreviewCard(
                    name = state.labPreviewName.orEmpty(),
                    preview = preview,
                    onClose = onClearPreview,
                )
            }
        }

        if (state.labResults.isNotEmpty()) {
            item {
                ResultSectionHeader(state = state)
            }
        }

        if (state.labRunning && state.labResults.isEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Готовим варианты предобработки…",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val sorted = state.labResults.sortedWith(
            compareByDescending<OcrExperimentResult> { it.score }
                .thenByDescending { it.regionCount },
        )
        items(count = sorted.size, key = { sorted[it].variantName }) { index ->
            val result = sorted[index]
            LabResultCard(
                result = result,
                best = index == 0 && result.regionCount > 0,
                enabled = !state.isBusy,
                onPreview = { onPreview(result.variantName) },
                onApply = { onApply(result.engine, result.variantName) },
            )
        }

        if (!state.labRunning && state.labResults.isNotEmpty() &&
            state.labResults.all { it.regionCount == 0 }
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                ) {
                    Text(
                        text = "Ни один вариант не нашёл текст. Проверьте, что выбран " +
                            "подходящий OCR-движок: для кириллицы нужен Tesseract, для " +
                            "японского — ML Kit Japanese.",
                        modifier = Modifier.padding(14.dp),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun LabIntroCard() {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Science,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Лаборатория качества",
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Text(
                    text = "экспериментально",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Здесь живут функции, которые ещё проверяются. Анализ документа " +
                    "определяет литературу, мангу, технический текст и документы с фото, " +
                    "защита изображений не даёт переводить и закрашивать надписи внутри " +
                    "снимков, на логотипах и графике, а агрессивная очистка убирает " +
                    "выдуманные OCR слова на текстуре. Рецепты предобработки комбинируют " +
                    "шумоподавление, контраст, резкость, CLAHE и апскейл, отдельные " +
                    "гипотезы выравнивают наклон страницы, а оцениваются по уверенности, " +
                    "согласию, покрытию, языку, вёрстке и штрафу за шум.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FeaturePill("тип документа", Icons.Outlined.AutoAwesome)
                FeaturePill("защита фото", Icons.Outlined.Image)
                FeaturePill("очистка OCR", Icons.Outlined.Tune)
                FeaturePill("наклон", Icons.Outlined.RotateRight)
            }
        }
    }
}

@Composable
private fun FeaturePill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(11.dp),
            tint = colors.primary,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabSectionHeader(title: String, subtitle: String) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(top = 2.dp)) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun DocumentInsightCard(
    analysis: DocumentAnalysis?,
    regions: List<TextRegionRecord>,
    protectionEnabled: Boolean,
    onGoToScan: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (analysis == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = colors.primary,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "Портрет ещё не построен",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Запустите скан в основной вкладке. После распознавания здесь " +
                        "появятся тип документа, доли фотографий, графики и штриха, а " +
                        "также блоки, которые лучше оставить как на исходнике.",
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onGoToScan,
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    Text("Перейти к сканированию", fontSize = 11.5.sp)
                }
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = colors.primary,
                )
                Spacer(Modifier.width(7.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = analysis.kind.displayName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    Text(
                        text = kindDescription(analysis.kind),
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        color = colors.onSurfaceVariant,
                    )
                }
                ConfidencePill(analysis.confidence)
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(
                    label = "текст",
                    value = formatShare(analysis.text.textCoverage),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "фото",
                    value = formatShare(analysis.image.photoLikeShare),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "старина",
                    value = formatShare(analysis.image.historicalPhotoShare),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(
                    label = "графика",
                    value = formatShare(analysis.image.graphicShare),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "цвет",
                    value = formatShare(analysis.image.colorfulShare),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "штрих",
                    value = formatShare(analysis.image.lineArtShare),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))
            analysis.scores.entries
                .sortedByDescending { it.value }
                .forEach { (kind, score) ->
                    KindScoreBar(kind = kind, score = score, highlighted = kind == analysis.kind)
                    Spacer(Modifier.height(5.dp))
                }

            Spacer(Modifier.height(4.dp))
            if (analysis.protectedCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Image,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = colors.secondary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (protectionEnabled) {
                            "Защищено ${analysis.protectedCount} блоков · " +
                                "${formatShare(analysis.protectedAreaShare)} площади"
                        } else {
                            "Кандидаты на защиту: ${analysis.protectedCount} блоков"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurface,
                    )
                }
                val candidates = regions
                    .filter { analysis.protectionReasons.containsKey(it.id) }
                    .take(3)
                candidates.forEach { region ->
                    val reason = analysis.protectionReasons[region.id].orEmpty()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = reason,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.secondary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = region.text.replace('\n', ' '),
                            modifier = Modifier.weight(1f),
                            fontSize = 10.sp,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (analysis.protectedCount > candidates.size) {
                    Text(
                        text = "и ещё ${analysis.protectedCount - candidates.size}…",
                        modifier = Modifier.padding(top = 3.dp),
                        fontSize = 9.5.sp,
                        color = colors.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = "Встроенных изображений, логотипов и графики с текстом не " +
                        "найдено — все блоки можно переводить.",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConfidencePill(confidence: Float) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        confidence >= 0.66f -> colors.primary
        confidence >= 0.4f -> colors.tertiary
        else -> colors.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.13f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = "${formatShare(confidence)} · уверенность",
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            color = tint,
        )
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.42f))
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Text(
            text = value,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        Text(
            text = label,
            fontSize = 9.sp,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun KindScoreBar(kind: DocumentKind, score: Float, highlighted: Boolean) {
    val colors = MaterialTheme.colorScheme
    val tint = if (highlighted) colors.primary else colors.outline
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = kind.displayName,
            modifier = Modifier.width(128.dp),
            fontSize = 10.sp,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlighted) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(
            progress = { score.coerceIn(0f, 1f) },
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = tint,
            trackColor = colors.surfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = formatShare(score),
            modifier = Modifier.width(38.dp),
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            color = if (highlighted) colors.primary else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabSafetySwitches(
    protectionEnabled: Boolean,
    aggressiveSanitizer: Boolean,
    enabled: Boolean,
    hasAnalysis: Boolean,
    candidateCount: Int,
    onProtectEmbeddedImages: (Boolean) -> Unit,
    onAggressiveSanitizer: (Boolean) -> Unit,
    onPreviewProtection: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.primary,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "Экспериментальная защита",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Text(
                    text = if (protectionEnabled) "включена" else "выключена",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (protectionEnabled) colors.primary else colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            SwitchRow(
                title = "Не трогать текст внутри изображений",
                subtitle = "Блоки, чей фон похож на фотографию, логотип или плотную " +
                    "графику, не переводятся и не закрашиваются. Подписи на чистой " +
                    "бумаге под фото остаются переводимыми.",
                checked = protectionEnabled,
                enabled = enabled,
                onCheckedChange = onProtectEmbeddedImages,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 9.dp),
                color = colors.outlineVariant.copy(alpha = 0.45f),
            )
            SwitchRow(
                title = "Агрессивная очистка галлюцинаций",
                subtitle = "Строже отбрасывает выдуманные слова на текстуре, одежде и " +
                    "логотипах: проверяет строчную структуру глифов и однотипность " +
                    "форм. Может удалить и настоящий текст на очень шумном фоне.",
                checked = aggressiveSanitizer,
                enabled = enabled,
                onCheckedChange = onAggressiveSanitizer,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onPreviewProtection,
                enabled = enabled && hasAnalysis && candidateCount > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                shape = RoundedCornerShape(7.dp),
                border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.5f)),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (candidateCount > 0) {
                        "Показать карту защиты · $candidateCount"
                    } else {
                        "Карта защиты появится после скана"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
            ),
        )
    }
}

@Composable
private fun OverlayStressCard(
    report: StyleStressLab.Report?,
    running: Boolean,
    onRun: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Palette,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = colors.primary,
                )
                Spacer(Modifier.width(7.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Стресс-тест цвета",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    Text(
                        text = "4 сложных кейса считаются прямо на телефоне",
                        fontSize = 10.sp,
                        color = colors.onSurfaceVariant,
                    )
                }
                report?.let { StressVerdictBadge(it.verdict) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Один и тот же пиксельный кейс прогоняется тремя способами: " +
                    "общий фон страницы (как было), локальный фон под каждым глифом " +
                    "(сейчас в скане) и пиковый сэмплер — экспериментальный режим для " +
                    "замыленного, пересвеченного и почти сливающегося текста.",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onRun,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(vertical = 9.dp),
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = colors.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Считаем кейсы…", fontSize = 12.sp)
                } else {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (report == null) "Прогнать стресс-тест" else "Прогнать заново",
                        fontSize = 12.sp,
                    )
                }
            }
            report?.let { result ->
                Spacer(Modifier.height(12.dp))
                result.cases.forEach { case ->
                    StressCaseRow(case)
                    Spacer(Modifier.height(8.dp))
                }
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (result.verdict == StyleStressLab.Verdict.FAIL) {
                            Icons.Outlined.Close
                        } else {
                            Icons.Outlined.Check
                        },
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = verdictColor(result.verdict),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stressSummary(result),
                        modifier = Modifier.weight(1f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurface,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Пиковый способ пока живёт только в этом тесте. Скан " +
                        "продолжает использовать локальный фон; включаем пиковый " +
                        "только после вашего одобрения.",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StressCaseRow(case: StyleStressLab.CaseResult) {
    val colors = MaterialTheme.colorScheme
    val winner = when {
        !case.passed -> "риск"
        case.peakError < case.localError -> "пик лучше"
        else -> "скан лучше"
    }
    val winnerTint = when {
        !case.passed -> colors.error
        case.peakError < case.localError -> colors.primary
        else -> colors.onSurfaceVariant
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = case.title,
                modifier = Modifier.weight(1f),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Text(
                text = winner,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                color = winnerTint,
            )
        }
        Text(
            text = case.detail,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            StressMetric(
                label = "общий фон",
                value = case.globalError,
                highlighted = false,
                modifier = Modifier.weight(1f),
            )
            StressMetric(
                label = "локально · скан",
                value = case.localError,
                highlighted = case.localError <= case.peakError,
                modifier = Modifier.weight(1f),
            )
            StressMetric(
                label = "пик · тест",
                value = case.peakError,
                highlighted = case.peakError < case.localError,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StressMetric(
    label: String,
    value: Float,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(
                if (highlighted) {
                    colors.primary.copy(alpha = 0.12f)
                } else {
                    colors.surfaceVariant.copy(alpha = 0.42f)
                },
            )
            .padding(horizontal = 7.dp, vertical = 5.dp),
    ) {
        Text(
            text = formatPercent(value),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = if (highlighted) colors.primary else colors.onSurface,
        )
        Text(
            text = label,
            fontSize = 8.5.sp,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StressVerdictBadge(verdict: StyleStressLab.Verdict) {
    val colors = MaterialTheme.colorScheme
    val tint = verdictColor(verdict)
    val label = when (verdict) {
        StyleStressLab.Verdict.PASS -> "стабильно"
        StyleStressLab.Verdict.MIXED -> "частично"
        StyleStressLab.Verdict.FAIL -> "риск"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.13f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            color = tint,
        )
    }
}

@Composable
private fun verdictColor(verdict: StyleStressLab.Verdict): Color = when (verdict) {
    StyleStressLab.Verdict.PASS -> MaterialTheme.colorScheme.primary
    StyleStressLab.Verdict.MIXED -> MaterialTheme.colorScheme.tertiary
    StyleStressLab.Verdict.FAIL -> MaterialTheme.colorScheme.error
}

private fun stressSummary(report: StyleStressLab.Report): String {
    val base = "В допуске ${report.passedCount} из ${report.cases.size} кейсов"
    return if (report.peakWins > 0) {
        "$base · пиковый сэмплер точнее в ${report.peakWins}"
    } else {
        base
    }
}

private fun kindDescription(kind: DocumentKind): String = when (kind) {
    DocumentKind.LITERARY_TEXT -> "Сплошной текст: переводим всё, формулы сохраняем."
    DocumentKind.MANGA -> "Комикс: диалоги переводим, рисунок и звуковые эффекты не трогаем."
    DocumentKind.TECHNICAL -> "Наука и техника: числа, формулы и схемы остаются как есть."
    DocumentKind.DOCUMENT ->
        "Документ с фото и графикой: надписи внутри снимков и на логотипах можно защитить."
}

private fun formatShare(value: Float): String =
    "${(value.coerceIn(0f, 1f) * 100f).roundToInt()}%"

@Composable
private fun LabEmptyState(onGoToScan: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Сначала загрузите изображение",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Тесты используют текущий снимок или документ из основной вкладки.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onGoToScan,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                ),
            ) {
                Text("Перейти к сканированию", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun LabEngineSelector(
    selected: OcrEngineChoice,
    enabled: Boolean,
    onSelect: (OcrEngineChoice) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Движок распознавания",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            val rows = ENGINE_OPTIONS.chunked(2)
            rows.forEachIndexed { rowIndex, rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowOptions.forEach { option ->
                        EngineChip(
                            option = option,
                            selected = selected == option.engine,
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(option.engine) },
                        )
                    }
                    if (rowOptions.size == 1) Spacer(Modifier.weight(1f))
                }
                if (rowIndex < rows.lastIndex) {
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun EngineChip(
    option: EngineOption,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(9.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) colors.primary.copy(alpha = 0.14f) else colors.surfaceVariant.copy(alpha = 0.4f),
            )
            .border(
                width = 1.dp,
                color = if (selected) colors.primary.copy(alpha = 0.65f) else colors.outlineVariant.copy(alpha = 0.5f),
                shape = shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(
            text = option.title,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) colors.primary else colors.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = option.subtitle,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabRunCard(
    state: ScanUiState,
    engine: OcrEngineChoice,
    onRun: (OcrEngineChoice) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Проверка вариантов",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "~22 комбинированных рецепта: контраст + шумоподавление + резкость, " +
                    "гамма + CLAHE, flat-field + CLAHE, top-hat + контраст, апскейл ×1.5–3 " +
                    "с последующей резкостью и другие сочетания. Гипотезы наклона " +
                    "выравнивают страницу с шагом 3° и уточняют угол до ±2°.",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onRun(engine) },
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (state.labRunning) "Идёт проверка…" else "Запустить автопоиск",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (state.labRunning) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = colors.primary,
                    trackColor = colors.surfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Проверено вариантов: ${state.labResults.size}",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LabPreviewCard(
    name: String,
    preview: android.graphics.Bitmap,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Превью · $name",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Скрыть превью",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onClose)
                        .padding(6.dp)
                        .size(16.dp),
                    tint = colors.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = "Результат предобработки $name",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .background(colors.surfaceVariant.copy(alpha = 0.3f)),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun ResultSectionHeader(state: ScanUiState) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Результаты",
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        Text(
            text = "${state.labResults.size} вариантов · по оценке",
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabResultCard(
    result: OcrExperimentResult,
    best: Boolean,
    enabled: Boolean,
    onPreview: () -> Unit,
    onApply: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val confidenceText = result.averageConfidence?.let { formatPercent(it) } ?: "—"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (result.regionCount > 0) {
                colors.outlineVariant.copy(alpha = 0.7f)
            } else {
                colors.error.copy(alpha = 0.35f)
            },
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.variantName,
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${engineDisplayName(result.engine)} · ${result.durationMs} мс · OCR $confidenceText",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariant,
                    )
                    if (result.variantSummary.isNotBlank()) {
                        Text(
                            text = result.variantSummary,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (best) {
                    BestBadge()
                    Spacer(Modifier.width(6.dp))
                }
                ScoreBadge(score = result.score)
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${result.regionCount} бл.",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariant,
                    )
                    if (result.droppedRegions > 0) {
                        Text(
                            text = "−${result.droppedRegions}",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.secondary,
                        )
                    }
                }
            }
            result.breakdown?.let { breakdown ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "уверенность ${formatPercent(breakdown.confidence)} · " +
                        "согласие ${formatPercent(breakdown.consensus)} · " +
                        "покрытие ${formatPercent(breakdown.coverage)} · " +
                        "язык ${formatPercent(breakdown.linguistic)} · " +
                        "вёрстка ${formatPercent(breakdown.layout)} · " +
                        "шум ${formatPercent(breakdown.noise)}",
                    fontSize = 9.5.sp,
                    lineHeight = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceVariant,
                )
            }
            if (result.dropReasons.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    result.dropReasons.entries
                        .sortedByDescending { it.value }
                        .take(4)
                        .forEach { (reason, count) ->
                            DropReasonChip(reason = reason, count = count)
                        }
                }
            }
            if (result.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = result.error,
                    fontSize = 11.sp,
                    color = colors.error,
                )
            } else if (result.text.isBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Текст не найден",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = excerpt(result.text),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurface,
                    maxLines = 7,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onPreview,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Превью", fontSize = 11.5.sp)
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = onApply,
                    enabled = enabled && result.regionCount > 0,
                    modifier = Modifier.height(32.dp),
                    shape = RoundedCornerShape(7.dp),
                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.55f)),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = colors.primary,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "Применить",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DropReasonChip(reason: String, count: Int) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(colors.secondary.copy(alpha = 0.11f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = "${dropReasonLabel(reason)} $count",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.secondary,
        )
    }
}

private fun dropReasonLabel(reason: String): String = when (reason) {
    "no-text" -> "нет текста"
    "symbol-noise" -> "символы"
    "repetition" -> "повторы"
    "case-noise" -> "регистр"
    "mixed-script" -> "алфавиты"
    "flat-area" -> "плоско"
    "no-ink" -> "нет чернил"
    "uniform-field" -> "фон"
    "blank-glyphs" -> "пусто"
    "solid-graphic" -> "логотип"
    "uniform-glyphs" -> "однотипно"
    else -> reason
}

@Composable
private fun ScoreBadge(score: Float) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        score >= 0.62f -> colors.primary
        score >= 0.42f -> colors.tertiary
        else -> colors.error
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = formatScore(score),
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = tint,
        )
    }
}

@Composable
private fun BestBadge() {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.primary.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = "лучший",
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.primary,
        )
    }
}

@Composable
internal fun AppliedExperimentBanner(
    applied: AppliedExperiment,
    onClear: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.primary.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Тестовый режим · ${applied.variantName}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Text(
                    text = "${engineDisplayName(applied.engine)} · следующий скан использует эту настройку",
                    fontSize = 10.5.sp,
                    color = colors.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onClear,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Сбросить", fontSize = 11.5.sp)
            }
        }
    }
}

private data class EngineOption(
    val engine: OcrEngineChoice,
    val title: String,
    val subtitle: String,
)

private val ENGINE_OPTIONS = listOf(
    EngineOption(OcrEngineChoice.AUTO, "Авто", "как в обычном скане"),
    EngineOption(OcrEngineChoice.CYRILLIC, "Tesseract", "русская модель"),
    EngineOption(OcrEngineChoice.LATIN, "ML Kit Latin", "английский"),
    EngineOption(OcrEngineChoice.JAPANESE, "ML Kit Japanese", "японский"),
)

internal fun engineDisplayName(engine: OcrEngineChoice): String = when (engine) {
    OcrEngineChoice.AUTO -> "Авто"
    OcrEngineChoice.LATIN -> "ML Kit Latin"
    OcrEngineChoice.JAPANESE -> "ML Kit Japanese"
    OcrEngineChoice.CYRILLIC -> "Tesseract Rus"
}

private fun formatPercent(value: Float): String =
    String.format(Locale.US, "%.1f%%", value * 100f)

private fun formatScore(value: Float): String =
    String.format(Locale.US, "%.2f", value)

private fun excerpt(text: String): String {
    val compact = text.trim()
    return if (compact.length <= 700) compact else compact.take(700) + "…"
}
