package com.tx24.spicyplayer.wear.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearCodecTest {
    private val lyrics = WearLyrics(
        key = "song\u001fartist",
        status = WearStatus.READY,
        type = WearSyncType.SYLLABLE,
        lines = listOf(
            WearLine(
                1_000, 3_000,
                listOf(WearWord("Hel", 1_000, 1_400, false), WearWord("lo", 1_400, 1_800, true, "ro"), WearWord("world", 2_000, 3_000, false)),
                groupId = 3, translation = "Hallo Welt",
            ),
            WearLine(1_200, 2_800, listOf(WearWord("(ooh)", 1_200, 2_800, false)), background = true, groupId = 3, opposite = true),
            WearLine(500, 900, emptyList(), lineTimed = true),
        ),
        credit = WearCredit("Spicy Lyrics", "spicy_lyrics", maker = "gc", uploader = "spikerko", songwriters = listOf("A", "B")),
    )

    @Test fun lyricsRoundTrip() {
        assertEquals(lyrics, WearCodec.decodeLyrics(WearCodec.encodeLyrics(lyrics)))
    }

    @Test fun lyricsArePacked() {
        val words = List(400) { WearWord("word$it", it * 500L, it * 500L + 400, false) }
        val big = lyrics.copy(lines = words.chunked(8).map { WearLine(it.first().startMs, it.last().endMs, it) })
        val bytes = WearCodec.encodeLyrics(big)
        assertTrue("${bytes.size} bytes", bytes.size < 8_000)
        assertEquals(big, WearCodec.decodeLyrics(bytes))
    }

    @Test fun otherRoundTrips() {
        val track = WearTrack("k", "Title", "Artist", "Album", 200_000, "com.spotify.music", "Spotify", listOf(1, -2, 3))
        assertEquals(track, WearCodec.decodeTrack(WearCodec.encodeTrack(track)))
        val clock = WearClock("k", 12_345, 99_000, 1_700_000_000_000, 1f, true, true, 7)
        assertEquals(clock, WearCodec.decodeClock(WearCodec.encodeClock(clock)))
        val command = WearCommand(WearCommandType.SEEK, 42_000, WearCommand.WANTS_ROMANIZATION)
        assertEquals(command, WearCodec.decodeCommand(WearCodec.encodeCommand(command)))
    }

    @Test fun otherVersionReadsAsNull() {
        val bytes = WearCodec.encodeTrack(WearTrack("k", "", "", "", 0, "", "", emptyList()))
        bytes[0] = 99
        assertNull(WearCodec.decodeTrack(bytes))
        assertNull(WearCodec.decodeLyrics(byteArrayOf(99, 1, 2)))
        assertNull(WearCodec.decodeClock(ByteArray(0)))
    }

    @Test fun credit() {
        assertEquals("Lyrics from spicy_lyrics · uploaded by spikerko, made by gc", lyrics.credit!!.text())
        assertEquals("Lyrics from LRCLIB", WearCredit("LRCLIB").text())
    }
}
