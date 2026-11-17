package com.tx24.spicyplayer.wear

import android.graphics.Bitmap
import com.tx24.spicyplayer.lyrics.TimedLine
import com.tx24.spicyplayer.lyrics.LyricsState

/**
 * What the phone hands the watch, and what the watch can ask of it:
 * [com.tx24.spicyplayer.widget.NowPlayingHolder] calls the first three as the song plays, and
 * [onCommand] arrives from the watch. The Data Layer stays in [WearBridge]. Main thread only.
 */
internal interface WatchFeed {
    /** A new song or a new cover for it. */
    fun onTrack(track: WatchTrack)

    /**
     * The lyrics for [key] (a [WatchTrack.key], or null before a song), or why there are none:
     * [notice] names a state that isn't in [lyrics], such as missing notification access.
     */
    fun onLyrics(key: String?, lyrics: LyricsState?, lines: List<TimedLine>?, notice: com.tx24.spicyplayer.wear.proto.WearStatus? = null)

    /** Where [key] is in lyric time at [atPhoneMs] on the phone's clock. [playable]: it's not a notice. */
    fun onClock(key: String?, positionMs: Long, atPhoneMs: Long, speed: Float, playing: Boolean, canSeek: Boolean)

    /** "playPause", "next", "previous", "seek" ([arg] ms of lyric time). */
    fun onCommand(type: String, arg: Long)
}

internal data class WatchTrack(
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val playerPackage: String,
    val playerName: String,
    val cover: Bitmap?,
    val backdrop: Bitmap?,
    /** Three shades of the cover's main colour, darkest last. */
    val colors: List<Int>,
)
