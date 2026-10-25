package com.lenslate.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRunTraceTest {

    private val blocks = listOf(
        SmartRunTrace.Block(
            text = "ТЕМНОТА",
            translation = "THE DARKNESS",
            confidence = 0.83f,
            style = "color=#FFD9A0 weight=700 size=42",
            bounds = "Rect(64, 300 - 512, 372)",
        ),
        SmartRunTrace.Block(
            text = "вход",
            translation = null,
            confidence = 0.41f,
            style = "color=#101010 weight=400 size=18",
            bounds = "Rect(20, 900 - 120, 940)",
        ),
    )
    private val stages = listOf(
        SmartRunTrace.Stage("OCR", 4210L, "11 блоков"),
        SmartRunTrace.Stage("Отрисовка", 120L, "готова"),
    )

    @Test
    fun namesPageAndWhetherTheBuildIsDebuggable() {
        val text = SmartRunTrace.report("07_dark_ink_ru.jpg", debuggable = false, blocks, stages, null)
        assertEquals(
            "page=07_dark_ink_ru.jpg debuggable=false",
            text.lines().first(),
        )
    }

    @Test
    fun writesOneLinePerBlockKeepingMultilineTextOnASingleLine() {
        val text = SmartRunTrace.report(
            "09_multifont_ru.jpg",
            debuggable = true,
            blocks + SmartRunTrace.Block("а\nб", "a b", 0.7f, "size=12", "Rect(0, 0 - 9, 9)"),
            stages,
            null,
        )
        val lines = text.lines().filter { it.startsWith("#") }
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("#0 conf=0.83 text=ТЕМНОТА tr=THE DARKNESS"))
        assertTrue("style and bounds trail the block line", lines[0].endsWith("style=color=#FFD9A0 weight=700 size=42 bounds=Rect(64, 300 - 512, 372)"))
        assertEquals("#2 conf=0.70 text=а/б tr=a b style=size=12 bounds=Rect(0, 0 - 9, 9)", lines[2])
    }

    @Test
    fun marksBlocksWithoutTranslationAsKeptOriginal() {
        val text = SmartRunTrace.report("07_dark_ink_ru.jpg", true, blocks, stages, null)
        assertTrue(
            "a kept block must read tr=none so it is findable in the report",
            text.lines().any { it.startsWith("#1") && it.contains("tr=none") },
        )
    }

    @Test
    fun recordsStagesThenPngOrItsAbsence() {
        val withPng = SmartRunTrace.report(
            "07_dark_ink_ru.jpg",
            true,
            blocks,
            stages,
            "/sdcard/lab/debug/smart_07_dark_ink_ru_out.png",
        )
        assertEquals("stage=OCR 4210ms · 11 блоков", withPng.lines().filter { it.startsWith("stage=") }.first())
        assertEquals(
            "png=/sdcard/lab/debug/smart_07_dark_ink_ru_out.png",
            withPng.trimEnd('\n').lines().last(),
        )

        val withoutPng = SmartRunTrace.report("07_dark_ink_ru.jpg", true, blocks, stages, null)
        assertEquals("png=missing", withoutPng.trimEnd('\n').lines().last())
    }

    @Test
    fun showsWhyABlockWasKeptAndSaysNothingWhenItWasTranslated() {
        val text = SmartRunTrace.report(
            "09_multifont_ru.jpg",
            true,
            listOf(blocks[1].copy(note = "fragment"), blocks[0].copy(note = "fragment")),
            stages,
            null,
        )
        val lines = text.lines().filter { it.startsWith("#") }
        assertTrue(lines[0].contains("tr=none note=fragment"))
        assertFalse("a translated block needs no excuse line", lines[1].contains("note="))
    }

    @Test
    fun printsNoneForAMissingConfidence() {
        val text = SmartRunTrace.report(
            "08_overexposure_extreme_ru.jpg",
            true,
            listOf(
                blocks.first().copy(translation = null, confidence = null),
                blocks.first().copy(confidence = 0.0f),
            ),
            stages,
            null,
        )
        val lines = text.lines().filter { it.startsWith("#") }
        assertEquals(
            "#0 conf=none text=ТЕМНОТА tr=none style=color=#FFD9A0 weight=700 size=42 bounds=Rect(64, 300 - 512, 372)",
            lines[0],
        )
        assertEquals("#1 conf=0.00", lines[1].take(12))
    }

    @Test
    fun indentsTheRowsThatDecidedTheFitCheck() {
        val text = SmartRunTrace.report(
            "07_dark_ink_ru.jpg",
            true,
            listOf(
                blocks[0].copy(
                    rows = listOf(
                        SmartRunTrace.Row(widthPx = 860f, charCount = 56),
                        SmartRunTrace.Row(widthPx = 487f, charCount = 32),
                    ),
                ),
                blocks[1],
            ),
            stages,
            null,
        )
        val lines = text.lines()
        assertEquals(
            listOf(
                "#0 conf=0.83 text=ТЕМНОТА tr=THE DARKNESS style=color=#FFD9A0 weight=700 size=42 bounds=Rect(64, 300 - 512, 372)",
                "  row w=860.0 chars=56 adv=15.4",
                "  row w=487.0 chars=32 adv=15.2",
                "#1 conf=0.41",
            ),
            listOf(lines[1], lines[2], lines[3], lines[4].take(12)),
        )
    }

    @Test
    fun saysNothingAboutRowsWhenTheBlockHasNone() {
        val text = SmartRunTrace.report("07_dark_ink_ru.jpg", true, blocks, stages, null)
        assertFalse(text.lines().any { it.startsWith("  row") })
    }
}
