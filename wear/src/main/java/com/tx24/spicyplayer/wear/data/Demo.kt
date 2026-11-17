package com.tx24.spicyplayer.wear.data

import com.tx24.spicyplayer.wear.proto.WearClock
import com.tx24.spicyplayer.wear.proto.WearCredit
import com.tx24.spicyplayer.wear.proto.WearLine
import com.tx24.spicyplayer.wear.proto.WearLyrics
import com.tx24.spicyplayer.wear.proto.WearStatus
import com.tx24.spicyplayer.wear.proto.WearSyncType
import com.tx24.spicyplayer.wear.proto.WearTrack
import com.tx24.spicyplayer.wear.proto.WearWord

/**
 * A song made up for looking at the screen on a watch with no phone: word-synced lines, a duet,
 * background vocals, a break and a held word. Debug builds only (see [WatchRepository.startDemo]).
 */
internal object Demo {
    private const val SONG = "Midnight City (Demo)"

    private data class Spec(val startMs: Long, val text: String, val background: String? = null, val opposite: Boolean = false)

    private val specs = listOf(
        Spec(2_400, "Waiting in a car, waiting for a ride in the dark"),
        Spec(6_200, "The night city grows, look and see her eyes, they glow"),
        Spec(10_600, "Waiting in a car, waiting for a ride in the dark"),
        Spec(15_400, "Drinking in the lights, the shadows start to bite"),
        Spec(19_200, "Midnight city", background = "(the city, the city)", opposite = true),
        Spec(24_000, "Where the streets all seem to glow"),
        Spec(29_500, "I've been waiting", background = "(ooh, ooh)"),
        Spec(36_000, "for the night to take me home"),
        Spec(42_500, "Midnight city, don't you let me go"),
        Spec(48_000, "Every light a promise, every promise", background = "(hold on)"),
        Spec(54_000, "keeps me walking on"),
        Spec(61_000, "Midni-i-i-ight", opposite = false),
        Spec(68_000, "City lights are calling out my name"),
        Spec(74_000, "and I'm still here", background = "(still here, still here)", opposite = true),
    )

    /** The duration of the demo song, in lyric ms. */
    val durationMs: Long = 82_000

    fun track(): WearTrack = WearTrack(
        key = "demo\u001fspicy lyrics",
        title = SONG,
        artist = "M83",
        album = "Hurry Up, We're Dreaming",
        durationMs = durationMs,
        playerPackage = "com.spotify.music",
        playerName = "Spotify",
        colors = listOf(0xFF8E6BD9.toInt(), 0xFF44276B.toInt(), 0xFF120A1F.toInt()),
    )

    fun lyrics(track: WearTrack): WearLyrics = WearLyrics(
        key = track.key,
        status = WearStatus.READY,
        type = WearSyncType.SYLLABLE,
        lines = specs.flatMapIndexed { index, spec ->
            val background = spec.background
            val lead = line(index, spec.startMs, spec.text, background = false, opposite = spec.opposite)
            if (background == null) listOf(lead)
            else listOf(lead, line(index, spec.startMs + 300, background, background = true, opposite = spec.opposite))
        },
        credit = WearCredit(
            provider = "Spicy Lyrics",
            source = "spicy_lyrics",
            maker = "demo",
            uploader = "demo",
            songwriters = listOf("Anthony Gonzalez", "Justin Meldal-Johnsen"),
        ),
    )

    /**
     * A line word by word: each word takes a share of the line's length, with a short gap after
     * it, and a word in the middle of a line is held, as singers do.
     */
    private fun line(index: Int, startMs: Long, text: String, background: Boolean, opposite: Boolean): WearLine {
        val words = text.split(' ').filter(String::isNotBlank)
        val nextStart = specs.getOrNull(index + 1)?.startMs ?: durationMs
        val endMs = minOf(nextStart - 200, startMs + (if (background) 3_200 else 4_600))
        val span = (endMs - startMs).coerceAtLeast(800)
        val slot = span / words.size.toLong().coerceAtLeast(1)
        var at = startMs
        val timed = words.mapIndexed { wordIndex, word ->
            val length = slot * 0.86f
            val held = !background && wordIndex == words.size / 2 && words.size > 3
            val wordEnd = at + (if (held) length * 1.5f else length).toLong()
            WearWord(word, at, wordEnd, attached = false).also { at = wordEnd + (slot * 0.14f).toLong() }
        }
        return WearLine(
            startMs = startMs,
            endMs = maxOf(endMs, timed.lastOrNull()?.endMs ?: endMs),
            words = timed,
            background = background,
            groupId = if (background) index else -1,
            opposite = opposite,
        )
    }

    /** Where the demo song is at watch time [nowMs], starting from [originMs]. */
    fun clock(track: WearTrack, nowMs: Long, originMs: Long): WearClock {
        val elapsed = (nowMs - originMs).coerceAtLeast(0L) % durationMs
        return WearClock(
            key = track.key,
            positionMs = elapsed,
            atPhoneMs = nowMs,
            phoneWallMs = System.currentTimeMillis(),
            speed = 1f,
            playing = true,
            canSeek = true,
            seq = nowMs,
        )
    }
}
