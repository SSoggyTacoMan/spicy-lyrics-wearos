package com.tx24.spicyplayer.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeMetricsTest {
    // A phone on its side, in dp (density 1 keeps px = dp).
    private val phone = LandscapeMetrics(pageWidthPx = 914f, pageHeightPx = 411f, density = 1f)

    @Test
    fun `cover is the now bar width, from the shorter side on a phone`() {
        // min(30cqw, 52cqh) * 1.1: the height wins here.
        assertEquals(411f * 0.52f * 1.1f, phone.coverSizePx, 0.01f)
        assertEquals(411f / 2f - phone.coverSizePx * 0.6f, phone.coverTopPx, 0.01f)
    }

    @Test
    fun `panel and song text fit on the page`() {
        val panel = phone.panel
        val textBottom = panel.artTopPx + panel.artSizePx * 1.05f + panel.textHeightPx
        assertTrue("text runs off the bottom: $textBottom", textBottom < phone.pageHeightPx)
        assertTrue(panel.artTopPx > 0f)
    }

    @Test
    fun `sides mirror each other`() {
        val left = phone.coverLeftPx(0f)
        val right = phone.coverLeftPx(1f)
        assertEquals(phone.pageWidthPx - phone.coverSizePx - left, right, 0.01f)
        val lyricsLeft = phone.lyricsBox(withPanel = true, panelOnRight = false)
        val lyricsRight = phone.lyricsBox(withPanel = true, panelOnRight = true)
        assertEquals(lyricsLeft.widthPx, lyricsRight.widthPx, 0.01f)
        assertEquals(phone.pageWidthPx - lyricsLeft.leftPx - lyricsLeft.widthPx, lyricsRight.leftPx, 0.01f)
    }

    @Test
    fun `lyrics stay beside the cover and on the page`() {
        val box = phone.lyricsBox(withPanel = true, panelOnRight = false)
        val textStart = box.leftPx + box.widthPx * CompactHeaderMetrics.LYRICS_SIDE_INSET
        assertTrue("lyrics text overlaps the cover", textStart > phone.coverLeftPx(0f) + phone.coverSizePx)
        assertTrue(box.leftPx + box.widthPx <= phone.pageWidthPx + 0.01f)
    }

    @Test
    fun `big cover is centred at the panel's size`() {
        assertEquals(phone.coverSizePx, phone.centred.artSizePx, 0.01f)
        assertEquals((phone.pageWidthPx - phone.coverSizePx) / 2f, phone.centred.artLeftPx, 0.01f)
    }

    @Test
    fun `without the panel the lyrics span the page`() {
        val box = phone.lyricsBox(withPanel = false, panelOnRight = false)
        assertEquals(phone.pageWidthPx * 0.18f, box.leftPx + box.widthPx * CompactHeaderMetrics.LYRICS_SIDE_INSET, 0.01f)
    }

    private val below = LandscapeMetrics(pageWidthPx = 914f, pageHeightPx = 411f, density = 1f, playerBelow = true)

    @Test
    fun `player below moves the cover out to the edge and leaves the lyrics where they were`() {
        // min(max(24, 4.2cqw), base): 4.2% of 914 is 38.4, well under the room beside the cover.
        assertEquals(914f * 0.042f, below.coverEdgePx, 0.01f)
        assertTrue(below.coverEdgePx < phone.coverEdgePx)
        assertEquals(below.coverEdgePx, below.coverLeftPx(0f), 0.01f)
        assertEquals(914f - below.coverSizePx - below.coverEdgePx, below.coverLeftPx(1f), 0.01f)
        assertEquals(phone.lyricsBox(true, false), below.lyricsBox(true, false))
        assertEquals(phone.lyricsBox(true, true), below.lyricsBox(true, true))
        assertEquals(phone.stageSolidPx, below.stageSolidPx, 0.01f)
        // The centred cover (no lyrics) stays where it was.
        assertEquals(phone.centred.artLeftPx, below.centred.artLeftPx, 0.01f)
        assertEquals(phone.centred.artTopPx, below.centred.artTopPx, 0.01f)
    }

    @Test
    fun `edge keeps a floor of 24dp and never passes the room beside the cover`() {
        val dense = LandscapeMetrics(pageWidthPx = 1200f, pageHeightPx = 540f, density = 3f, playerBelow = true)
        // 4.2% of 1200 is 50px, under 24dp = 72px.
        assertEquals(72f, dense.coverEdgePx, 0.01f)
        val tight = LandscapeMetrics(pageWidthPx = 400f, pageHeightPx = 1000f, density = 1f, playerBelow = true)
        val room = 400f * 0.5f - tight.coverSizePx - tight.coverSizePx / 4f
        assertEquals(minOf(maxOf(24f, 400f * 0.042f), room), tight.coverEdgePx, 0.01f)
    }

    @Test
    fun `player below stacks the rows under the cover and centres the whole`() {
        val rows = requireNotNull(below.belowRows)
        assertEquals(
            rows.metaGapPx + rows.metaHeightPx + rows.timelineGapPx + rows.timelineHeightPx + rows.controlsGapPx + rows.controlsHeightPx,
            rows.heightPx, 0.01f,
        )
        val top = below.panelTopPx
        val bottom = top + below.coverSizePx + rows.heightPx
        assertEquals(411f / 2f, (top + bottom) / 2f, 0.5f)
        assertTrue("runs off the page: $bottom", bottom <= 411f)
        assertTrue(top >= 0f)
        assertEquals(top, below.panel.artTopPx, 0.01f)
    }

    @Test
    fun `player below keeps touch size for the transport and never starts above the page`() {
        val dense = LandscapeMetrics(pageWidthPx = 2400f, pageHeightPx = 1080f, density = 2.6f, topPx = 30f, playerBelow = true)
        val rows = requireNotNull(dense.belowRows)
        assertTrue(rows.controlsHeightPx >= 48f * 2.6f)
        // A page too short for the stack puts the cover at the top rather than above it.
        val short = LandscapeMetrics(pageWidthPx = 900f, pageHeightPx = 200f, density = 3f, topPx = 20f, playerBelow = true)
        assertEquals(20f, short.panelTopPx, 0.01f)
    }

    @Test
    fun `without player below nothing changes`() {
        assertEquals(null, phone.belowRows)
        assertEquals(phone.coverTopPx, phone.panelTopPx, 0.01f)
        assertEquals(phone.panel.artLeftPx, phone.coverLeftPx(0f), 0.01f)
    }
}
