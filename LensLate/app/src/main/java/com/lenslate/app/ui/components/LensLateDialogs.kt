package com.lenslate.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lenslate.app.model.TextRegionRecord
import com.lenslate.app.ui.theme.LensLatePalette
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun PalettePickerDialog(
    selectedPalette: LensLatePalette,
    onDismiss: () -> Unit,
    onPaletteSelected: (LensLatePalette) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.8f)),
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Palette,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Палитра",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                        )
                        Text(
                            "Оформление интерфейса",
                            fontSize = 11.sp,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LensLatePalette.entries.forEach { option ->
                        PaletteCard(
                            option = option,
                            selected = option == selectedPalette,
                            onClick = { onPaletteSelected(option) },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Готово", color = colors.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PaletteCard(
    option: LensLatePalette,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) colors.primary.copy(alpha = 0.10f)
                else colors.surfaceVariant.copy(alpha = 0.45f),
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.7f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            option.swatches.take(3).forEach { swatch ->
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(1.dp, colors.outline.copy(alpha = 0.35f), CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                option.title,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Text(
                option.description,
                fontSize = 10.5.sp,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(
                    1.5.dp,
                    if (selected) colors.primary else colors.outlineVariant,
                    CircleShape,
                )
                .padding(4.dp)
                .clip(CircleShape)
                .background(if (selected) colors.primary else Color.Transparent),
        )
    }
}

@Composable
fun RegionDetailsDialog(
    region: TextRegionRecord,
    index: Int,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val appearance = region.appearance
    val confidence = region.overallConfidence ?: region.confidence
    val lines = region.lines
    val symbolCount = lines.sumOf { line -> line.elements.sumOf { it.symbols.size } }
    val angle = lines.mapNotNull { it.angleDegrees }
        .takeIf { it.isNotEmpty() }
        ?.average()
        ?.toFloat()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.8f)),
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Блок #$index",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                        )
                        Text(
                            region.text.replace('\n', ' ').take(80),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Закрыть",
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    DetailSection(
                        title = "ТЕКСТ",
                        rows = buildList {
                            add("Оригинал" to region.text.ifBlank { "—" })
                            add("Перевод" to (region.translation ?: "—"))
                            if (region.isFormula) {
                                add("Формула" to "да · сохранена без изменений")
                            }
                        },
                    )
                    DetailSection(
                        title = "РАСПОЗНАВАНИЕ",
                        rows = listOf(
                            "Скрипт" to (region.script ?: "—"),
                            "Уверенность" to confidenceText(confidence),
                            "Вариант" to (region.preprocessingVariant ?: "—"),
                            "Строк" to lines.size.toString(),
                            "Символов" to symbolCount.toString(),
                            "Ориентация" to when {
                                region.isVertical -> "вертикальная"
                                region.isCurved -> "изогнутая"
                                angle != null && kotlin.math.abs(angle) >= 1f ->
                                    "наклон ${formatFloat(angle, 1)}°"
                                else -> "горизонтальная"
                            },
                        ),
                    )
                    DetailSection(
                        title = "СТИЛЬ",
                        rows = listOf(
                            "Шрифт" to buildString {
                                append(appearance?.likelyFontClass ?: "sans")
                                val weight = appearance?.fontWeight
                                if (weight != null) append(" · $weight")
                                else if (appearance?.weight != null) append(" · ${appearance.weight}")
                            },
                            "Кегль" to formatFloatOrDash(appearance?.textHeightPx, "px"),
                            "Cap height" to formatFloatOrDash(appearance?.capHeightPx, "px"),
                            "Трекинг" to formatFloatOrDash(appearance?.spacingPx, "px"),
                            "Интерлиния" to formatFloatOrDash(
                                appearance?.lineSpacingMultiplier,
                                "×",
                            ),
                            "Наклон" to formatFloatOrDash(appearance?.slantDegrees, "°"),
                            "Ширина" to formatFloatOrDash(appearance?.horizontalScale, "×"),
                            "Обводка" to formatFloatOrDash(appearance?.strokeWidthPx, "px"),
                            "Выравнивание" to (appearance?.alignment ?: "left"),
                            "Подчёркивание" to yesNo(appearance?.isUnderlined),
                            "Зачёркивание" to yesNo(appearance?.isStruckThrough),
                        ),
                    )
                    DetailSection(
                        title = "ГЕОМЕТРИЯ · WORKING PX",
                        rows = listOf(
                            "Границы" to "L ${formatFloat(region.bounds.left, 0)} · " +
                                "T ${formatFloat(region.bounds.top, 0)} · " +
                                "R ${formatFloat(region.bounds.right, 0)} · " +
                                "B ${formatFloat(region.bounds.bottom, 0)}",
                            "Полигон" to "${region.polygon.size} точек",
                            "Порядок" to region.readingOrder.toString(),
                        ),
                    )
                    DetailSection(
                        title = "ЦВЕТА",
                        rows = emptyList(),
                        colorRows = buildList {
                            appearance?.foregroundArgb?.let { add("Текст" to it) }
                            appearance?.localBackgroundArgb?.let { add("Фон" to it) }
                            appearance?.alternativeColors.orEmpty()
                                .distinct()
                                .take(5)
                                .forEachIndexed { colorIndex, color ->
                                    add("Альтернатива ${colorIndex + 1}" to color)
                                }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailSection(
    title: String,
    rows: List<Pair<String, String>>,
    colorRows: List<Pair<String, Int>> = emptyList(),
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            color = colors.outlineVariant.copy(alpha = 0.45f),
        )
        Text(
            title,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = colors.primary,
        )
        Spacer(Modifier.height(6.dp))
        rows.forEach { (label, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.5.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    label,
                    modifier = Modifier.width(104.dp),
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    value,
                    modifier = Modifier.weight(1f),
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurface,
                )
            }
        }
        colorRows.forEach { (label, argb) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    modifier = Modifier.width(104.dp),
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(argb))
                        .border(1.dp, colors.outline.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    argbToHex(argb),
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurface,
                )
            }
        }
    }
}

private fun confidenceText(value: Float?): String =
    value?.let { "${(it.coerceIn(0f, 1f) * 100f).roundToInt()}%" } ?: "—"

private fun formatFloat(value: Float, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

private fun formatFloatOrDash(value: Float?, suffix: String): String =
    value?.let { "${formatFloat(it, 2)} $suffix" } ?: "—"

private fun yesNo(value: Boolean?): String = when (value) {
    true -> "да"
    false -> "нет"
    null -> "—"
}

private fun argbToHex(argb: Int): String =
    String.format(Locale.US, "#%06X", argb and 0xFFFFFF)
