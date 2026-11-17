package com.tx24.spicyplayer.wear.proto

/**
 * The rows the watch scrolls through: each lead line with the background vocals sung over it,
 * and a break (three dots) wherever the singing stops for [BREAK_GAP_MS] or more, the song's
 * start included. Plain Kotlin.
 */
class WearTimeline(lyrics: WearLyrics) {
    sealed interface Row {
        val startMs: Long
        val endMs: Long
    }

    data class Vocal(
        val lead: WearLine,
        val background: List<WearLine>,
        override val startMs: Long = lead.startMs,
        override val endMs: Long = maxOf(lead.endMs, background.maxOfOrNull { it.endMs } ?: 0L),
    ) : Row

    /** A break; [dots] are the three dots' [start, end) times. */
    data class Break(override val startMs: Long, override val endMs: Long, val dots: List<Pair<Long, Long>>) : Row

    val synced = lyrics.type != WearSyncType.STATIC
    val wordSynced = lyrics.type == WearSyncType.SYLLABLE
    val rows: List<Row> = build(lyrics.lines, synced)

    /** The row being sung at [positionMs]: the last that has started, -1 before any. */
    fun activeIndex(positionMs: Long): Int {
        if (!synced) return -1
        var lo = 0
        var hi = rows.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (rows[mid].startMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        return found
    }

    companion object {
        const val BREAK_GAP_MS = 3_000L
        /** A break ends this long before the next line, so the dots are gone as it starts. */
        const val BREAK_LEAD_OUT_MS = 500L

        fun dotTimes(startMs: Long, endMs: Long): List<Pair<Long, Long>> {
            val total = (endMs - startMs).toDouble()
            val base = total / 3.0
            val padding = -550.0 / 3.0
            val dot1 = maxOf(startMs.toDouble(), startMs + base + padding)
            val dot2 = maxOf(dot1, startMs + base * 2 + padding * 2)
            val dot3 = maxOf(dot2, startMs + total - 550.0)
            val ends = listOf(dot1, dot2, dot3).map { Math.round(it) }
            return listOf(startMs to ends[0], ends[0] to ends[1], ends[1] to ends[2])
        }

        private fun build(lines: List<WearLine>, synced: Boolean): List<Row> {
            val vocals = ArrayList<Vocal>()
            val pendingBackground = ArrayList<WearLine>()
            for (line in lines) {
                if (!line.background) {
                    vocals += Vocal(line, emptyList())
                    continue
                }
                val owner = if (line.groupId >= 0) vocals.indexOfLast { it.lead.groupId == line.groupId }.takeIf { it >= 0 } else null
                val index = owner ?: vocals.lastIndex
                if (index < 0) pendingBackground += line
                else vocals[index] = vocals[index].let { Vocal(it.lead, it.background + line) }
            }
            if (pendingBackground.isNotEmpty() && vocals.isNotEmpty()) {
                vocals[0] = Vocal(vocals[0].lead, pendingBackground + vocals[0].background)
            }
            if (!synced) return vocals
            val rows = ArrayList<Row>()
            var sungUntil = 0L
            for (vocal in vocals) {
                val gapStart = sungUntil
                val gapEnd = vocal.startMs - BREAK_LEAD_OUT_MS
                if (vocal.startMs - gapStart >= BREAK_GAP_MS && gapEnd > gapStart) {
                    rows += Break(gapStart, gapEnd, dotTimes(gapStart, gapEnd))
                }
                rows += vocal
                sungUntil = maxOf(sungUntil, vocal.endMs)
            }
            return rows
        }
    }
}
