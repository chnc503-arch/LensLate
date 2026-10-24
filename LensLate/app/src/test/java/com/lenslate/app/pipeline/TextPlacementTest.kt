package com.lenslate.app.pipeline

import com.lenslate.app.model.Bounds2D
import org.junit.Assert.assertEquals
import org.junit.Test

class TextPlacementTest {

    private val bounds = Bounds2D(left = 100f, top = 400f, right = 900f, bottom = 500f)

    @Test
    fun `room stops at the nearest horizontally overlapping neighbour`() {
        val room = TextPlacement.room(
            bounds = bounds,
            obstacles = listOf(
                Bounds2D(120f, 300f, 500f, 380f),
                Bounds2D(700f, 320f, 880f, 390f),
                Bounds2D(120f, 530f, 400f, 600f),
            ),
            canvasHeight = 2000f,
        )

        assertEquals(10f, room.above, 0.01f)
        assertEquals(30f, room.below, 0.01f)
    }

    @Test
    fun `obstacles beside the block do not limit vertical growth`() {
        val room = TextPlacement.room(
            bounds = bounds,
            obstacles = listOf(
                Bounds2D(0f, 300f, 90f, 390f),
                Bounds2D(910f, 510f, 1000f, 600f),
            ),
            canvasHeight = 2000f,
        )

        assertEquals(400f, room.above, 0.01f)
        assertEquals(1500f, room.below, 0.01f)
    }

    @Test
    fun `window keeps a gap and caps total growth`() {
        val window = TextPlacement.window(
            bounds = bounds,
            room = TextPlacement.Room(above = 100f, below = 100f),
            gap = 10f,
            maxGrowthRatio = 0.4f,
        )

        assertEquals(380f, window.top, 0.01f)
        assertEquals(520f, window.bottom, 0.01f)
        assertEquals(140, window.maxHeight)
    }

    @Test
    fun `growth is split proportionally when one side has less room`() {
        val window = TextPlacement.window(
            bounds = bounds,
            room = TextPlacement.Room(above = 10f, below = 210f),
            gap = 10f,
            maxGrowthRatio = 0.5f,
        )

        assertEquals(400f, window.top, 0.01f)
        assertEquals(550f, window.bottom, 0.01f)
    }

    @Test
    fun `matching line count leaves the first baseline untouched`() {
        val window = TextPlacement.Window(top = 380f, bottom = 540f, maxHeight = 160)
        val top = TextPlacement.multilineTop(
            anchoredTop = 395f,
            layoutHeight = 110f,
            layoutLines = 3,
            sourceLines = 3,
            sourceHeight = 100f,
            window = window,
        )

        assertEquals(395f, top, 0.01f)
    }

    @Test
    fun `extra lines are raised into free space above`() {
        val window = TextPlacement.Window(top = 340f, bottom = 540f, maxHeight = 200)
        val top = TextPlacement.multilineTop(
            anchoredTop = 400f,
            layoutHeight = 150f,
            layoutLines = 4,
            sourceLines = 3,
            sourceHeight = 100f,
            window = window,
        )

        assertEquals(375f, top, 0.01f)
    }

    @Test
    fun `block never extends past the available bottom`() {
        val window = TextPlacement.Window(top = 350f, bottom = 480f, maxHeight = 130)
        val top = TextPlacement.multilineTop(
            anchoredTop = 400f,
            layoutHeight = 150f,
            layoutLines = 4,
            sourceLines = 3,
            sourceHeight = 100f,
            window = window,
        )

        assertEquals(350f, top, 0.01f)
    }
}
