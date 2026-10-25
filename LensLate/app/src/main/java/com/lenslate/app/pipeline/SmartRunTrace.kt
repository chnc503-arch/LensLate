package com.lenslate.app.pipeline

import java.util.Locale

/**
 * Plain-text anatomy of one smart run. Written to disk so a lab page can be
 * inspected without a logcat stream, which a device can drop or rate-limit.
 */
object SmartRunTrace {

    data class Block(
        val text: String,
        val translation: String?,
        val confidence: Float?,
        val style: String,
        val bounds: String,
        val note: String? = null,
        val rows: List<Row> = emptyList(),
    )

    /** One text row of a block, as the fit check sees it. */
    data class Row(
        val widthPx: Float,
        val charCount: Int,
    )

    data class Stage(
        val name: String,
        val durationMs: Long,
        val detail: String,
    )

    fun report(
        page: String,
        debuggable: Boolean,
        blocks: List<Block>,
        stages: List<Stage>,
        png: String?,
    ): String = buildString {
        append("page=").append(page).append(" debuggable=").append(debuggable).append('\n')
        blocks.forEachIndexed { index, block ->
            append('#').append(index)
                .append(" conf=")
                .append(block.confidence?.let { String.format(Locale.US, "%.2f", it) } ?: "none")
                .append(" text=").append(block.text.replace('\n', '/'))
                .append(" tr=").append(block.translation?.replace('\n', '/') ?: "none")
            if (block.translation == null && block.note != null) {
                append(" note=").append(block.note)
            }
            append(" style=").append(block.style)
                .append(" bounds=").append(block.bounds)
                .append('\n')
            block.rows.forEach { row ->
                val advance = row.widthPx / row.charCount.coerceAtLeast(1)
                append("  row w=").append(String.format(Locale.US, "%.1f", row.widthPx))
                    .append(" chars=").append(row.charCount)
                    .append(" adv=").append(String.format(Locale.US, "%.1f", advance))
                    .append('\n')
            }
        }
        stages.forEach { stage ->
            append("stage=").append(stage.name)
                .append(' ').append(stage.durationMs).append("ms · ").append(stage.detail)
                .append('\n')
        }
        append("png=").append(png ?: "missing")
        append('\n')
    }
}
