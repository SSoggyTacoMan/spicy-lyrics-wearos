package com.tx24.spicyplayer.wear.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchClockTest {
    private fun clock(position: Long, atPhone: Long, playing: Boolean = true, seq: Long = 0) =
        WearClock("k", position, atPhone, 0L, 1f, playing, true, seq)

    @Test fun pingPicksShortestRoundTrip() {
        val sync = ClockSync()
        // Phone is 10_000 ahead. First ping slow and lopsided, second fast.
        sync.onPong(sentWatchMs = 0, phoneMs = 10_400, receivedWatchMs = 500)
        sync.onPong(sentWatchMs = 1_000, phoneMs = 11_020, receivedWatchMs = 1_040)
        assertEquals(10_000L, sync.offsetMs)
        assertEquals(40L, sync.bestRttMs)
    }

    @Test fun followsThePhone() {
        val watch = WatchClock()
        watch.update(clock(5_000, atPhone = 20_000), offset = 10_000, nowMs = 10_000)
        assertEquals(5_000L, watch.positionAt(10_000))
        assertEquals(6_500L, watch.positionAt(11_500))
    }

    @Test fun smallDriftEasesIn() {
        val watch = WatchClock()
        watch.update(clock(5_000, atPhone = 20_000), offset = 10_000, nowMs = 10_000)
        // Phone says we're 100 ms behind.
        watch.update(clock(6_100, atPhone = 21_000, seq = 1), offset = 10_000, nowMs = 11_000)
        assertEquals(6_000L, watch.positionAt(11_000))
        val mid = watch.positionAt(11_175)
        assertTrue("$mid", mid in 6_220..6_230)
        assertEquals(6_100L + 400, watch.positionAt(11_400))
    }

    @Test fun seekSnaps() {
        val watch = WatchClock()
        watch.update(clock(5_000, atPhone = 20_000), offset = 10_000, nowMs = 10_000)
        watch.update(clock(60_000, atPhone = 21_000, seq = 1), offset = 10_000, nowMs = 11_000)
        assertEquals(60_000L, watch.positionAt(11_000))
    }

    @Test fun pausedHolds() {
        val watch = WatchClock()
        watch.update(clock(5_000, atPhone = 20_000, playing = false), offset = 10_000, nowMs = 10_000)
        assertEquals(5_000L, watch.positionAt(30_000))
    }
}
