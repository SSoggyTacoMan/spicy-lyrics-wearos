package com.tx24.spicyplayer.wear.proto

import kotlin.math.abs

/**
 * The offset between the watch's and the phone's `elapsedRealtime`, measured by pings: the phone
 * stamps its time on the answer, and the ping's round trip bounds the error. The sample with the
 * shortest round trip among the last few wins, since Bluetooth's delay comes in bursts.
 *
 * Until a ping has answered, the offset comes from the wall clocks (both synced to the network,
 * usually within tens of milliseconds).
 */
class ClockSync {
    private data class Sample(val offsetMs: Long, val rttMs: Long, val atMs: Long)

    private val samples = ArrayDeque<Sample>()
    private var wallOffsetMs: Long? = null

    /** phone elapsed = watch elapsed + [offsetMs]; null before anything's known. */
    val offsetMs: Long?
        get() = samples.minByOrNull { it.rttMs }?.offsetMs ?: wallOffsetMs

    /** The best round trip measured, for the debug page. */
    val bestRttMs: Long? get() = samples.minByOrNull { it.rttMs }?.rttMs

    val measured: Boolean get() = samples.isNotEmpty()

    /** A ping sent at [sentWatchMs] came back at [receivedWatchMs] with the phone's [phoneMs]. */
    fun onPong(sentWatchMs: Long, phoneMs: Long, receivedWatchMs: Long) {
        val rtt = receivedWatchMs - sentWatchMs
        if (rtt < 0 || rtt > MAX_RTT_MS) return
        samples.addLast(Sample(phoneMs - (sentWatchMs + rtt / 2), rtt, receivedWatchMs))
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
        // Clocks drift apart slowly; an hour-old sample no longer counts.
        samples.removeAll { receivedWatchMs - it.atMs > MAX_AGE_MS }
    }

    /** A wall-clock estimate from a [WearClock], used until a ping answers. */
    fun onWallClock(clock: WearClock, watchElapsedMs: Long, watchWallMs: Long) {
        val phoneBoot = clock.phoneWallMs - clock.atPhoneMs
        val watchBoot = watchWallMs - watchElapsedMs
        wallOffsetMs = watchBoot - phoneBoot
    }

    fun reset() {
        samples.clear()
        wallOffsetMs = null
    }

    companion object {
        const val MAX_SAMPLES = 8
        const val MAX_RTT_MS = 2_000L
        const val MAX_AGE_MS = 60 * 60_000L
    }
}

/**
 * The song position on the watch, from the phone's [WearClock]s and [ClockSync]. A small
 * correction is eased in over [EASE_MS] so the lyrics never visibly jump; a large one (a seek)
 * is taken at once. Plain Kotlin: times are the watch's `elapsedRealtime`, passed in.
 */
class WatchClock {
    var clock: WearClock? = null
        private set
    private var residualMs = 0L
    private var residualAtMs = 0L
    private var offsetMs = 0L

    val playing: Boolean get() = clock?.playing == true

    /** Takes [next], with phone elapsed = watch elapsed + [offset]. */
    fun update(next: WearClock, offset: Long, nowMs: Long) {
        val before = clock?.takeIf { it.key == next.key }?.let { positionAt(nowMs) }
        clock = next
        offsetMs = offset
        residualMs = 0L
        val target = positionAt(nowMs)
        if (before != null && next.playing) {
            val drift = before - target
            if (abs(drift) in 1 until SNAP_MS) {
                residualMs = drift
                residualAtMs = nowMs
            }
        }
    }

    /** The offset was re-measured: the current anchor is read through it, eased in. */
    fun setOffset(offset: Long, nowMs: Long) {
        val current = clock ?: run { offsetMs = offset; return }
        update(current, offset, nowMs)
    }

    /** Our own seek: the song is at [positionMs] now, until the phone says otherwise. */
    fun seekTo(positionMs: Long, nowMs: Long) {
        val current = clock ?: return
        clock = current.copy(positionMs = positionMs, atPhoneMs = nowMs + offsetMs)
        residualMs = 0L
    }

    fun positionAt(nowMs: Long): Long {
        val current = clock ?: return 0L
        val base = current.positionAtPhone(nowMs + offsetMs)
        if (residualMs == 0L) return base
        val t = (nowMs - residualAtMs).coerceAtLeast(0L)
        if (t >= EASE_MS) {
            residualMs = 0L
            return base
        }
        return (base + residualMs * (EASE_MS - t) / EASE_MS).coerceAtLeast(0L)
    }

    fun reset() {
        clock = null
        residualMs = 0L
    }

    companion object {
        const val EASE_MS = 350L
        const val SNAP_MS = 400L
    }
}
