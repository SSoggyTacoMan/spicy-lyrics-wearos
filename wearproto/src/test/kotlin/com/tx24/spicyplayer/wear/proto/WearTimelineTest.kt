package com.tx24.spicyplayer.wear.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearTimelineTest {
    private fun line(start: Long, end: Long, background: Boolean = false, group: Int = -1) =
        WearLine(start, end, listOf(WearWord("x", start, end, false)), background = background, groupId = group)

    @Test fun breaksAndBackground() {
        val timeline = WearTimeline(
            WearLyrics(
                "k", WearStatus.READY, WearSyncType.SYLLABLE,
                listOf(line(8_000, 10_000, group = 1), line(9_000, 10_500, background = true, group = 1), line(11_000, 12_000), line(20_000, 22_000)),
            ),
        )
        val rows = timeline.rows
        assertEquals(5, rows.size)
        assertTrue(rows[0] is WearTimeline.Break)
        assertEquals(1, (rows[1] as WearTimeline.Vocal).background.size)
        assertEquals(10_500L, rows[1].endMs)
        assertTrue(rows[3] is WearTimeline.Break)
        assertEquals(12_000L, rows[3].startMs)
        assertEquals(19_500L, rows[3].endMs)
        assertEquals(-1, timeline.activeIndex(-5))
        assertEquals(0, timeline.activeIndex(100))
        assertEquals(1, timeline.activeIndex(8_000))
        assertEquals(3, timeline.activeIndex(15_000))
        assertEquals(4, timeline.activeIndex(99_000))
    }

    @Test fun staticHasNoBreaks() {
        val timeline = WearTimeline(WearLyrics("k", WearStatus.READY, WearSyncType.STATIC, listOf(line(0, 0), line(0, 0))))
        assertEquals(2, timeline.rows.size)
        assertEquals(-1, timeline.activeIndex(1_000))
    }
}
