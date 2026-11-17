package com.tx24.spicyplayer.wear.proto

/** Data Layer paths shared by the phone and the watch. */
object WearPaths {
    /** DataItem: the song being played (and its cover as assets). */
    const val TRACK = "/spicy/track"
    /** DataItem: the song's lyrics, or why there are none. */
    const val LYRICS = "/spicy/lyrics"
    /** DataItem: where the song is, stamped on the phone's clock. */
    const val CLOCK = "/spicy/clock"
    /** Message, watch → phone: a [WearCommand]. */
    const val COMMAND = "/spicy/cmd"
    /** Capability the watch app advertises (res/values/wear.xml). */
    const val WATCH_CAPABILITY = "spicy_lyrics_watch"
    /** Capability the phone app advertises. */
    const val PHONE_CAPABILITY = "spicy_lyrics_phone"

    const val KEY_PAYLOAD = "payload"
    const val KEY_COVER = "cover"
    const val KEY_BACKDROP = "backdrop"
}

/** The song the phone follows. [colors]: three shades of the cover's main colour (ARGB), darkest last. */
data class WearTrack(
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val playerPackage: String,
    val playerName: String,
    val colors: List<Int>,
)

enum class WearSyncType { SYLLABLE, LINE, STATIC }

/** Why the watch shows what it does in place of lyrics, or [READY]. */
enum class WearStatus { WAITING, NO_ACCESS, NO_SONG_INFO, LOOKING, READY, NO_LYRICS, FAILED }

data class WearWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** Joined to the word before it with no space (a syllable of the same word). */
    val attached: Boolean,
    val romanized: String? = null,
)

data class WearLine(
    val startMs: Long,
    val endMs: Long,
    val words: List<WearWord>,
    /** Background vocals, drawn smaller under their lead line. */
    val background: Boolean = false,
    /** Lines of one group are sung together; -1 for none. */
    val groupId: Int = -1,
    /** A duet partner sings it, leaning to the other side. */
    val opposite: Boolean = false,
    /** Timed only as a whole line, even in word-synced lyrics. */
    val lineTimed: Boolean = false,
    val translation: String? = null,
)

data class WearCredit(
    val provider: String,
    val source: String? = null,
    val maker: String? = null,
    val uploader: String? = null,
    val songwriters: List<String> = emptyList(),
) {
    /** "Lyrics from Spicy Lyrics · uploaded by X, made by Y", as the API's attribution asks. */
    fun text(): String {
        val origin = source?.takeIf { it.isNotBlank() } ?: provider
        val people = listOfNotNull(uploader?.let { "uploaded by $it" }, maker?.let { "made by $it" })
        return if (people.isEmpty()) "Lyrics from $origin" else "Lyrics from $origin · ${people.joinToString(", ")}"
    }
}

data class WearLyrics(
    val key: String,
    val status: WearStatus,
    val type: WearSyncType = WearSyncType.SYLLABLE,
    val lines: List<WearLine> = emptyList(),
    val credit: WearCredit? = null,
)

/**
 * Where song [key] is: [positionMs] (lyric time, the phone's delays already taken off) at
 * [atPhoneMs] on the phone's `elapsedRealtime`. [phoneWallMs] is the phone's wall clock at the
 * same moment, a fallback for the offset until a ping has measured it. [seq] grows with every
 * send, so an unchanged state still syncs as a heartbeat.
 */
data class WearClock(
    val key: String,
    val positionMs: Long,
    val atPhoneMs: Long,
    val phoneWallMs: Long,
    val speed: Float,
    val playing: Boolean,
    val canSeek: Boolean,
    val seq: Long,
) {
    fun positionAtPhone(phoneNowMs: Long): Long {
        val elapsed = if (playing) (phoneNowMs - atPhoneMs).coerceAtLeast(0L) else 0L
        return (positionMs + elapsed * speed).toLong().coerceAtLeast(0L)
    }
}

enum class WearCommandType {
    PLAY_PAUSE, NEXT, PREVIOUS, SEEK, VOLUME_UP, VOLUME_DOWN,
    /** The watch app is in view: send everything, keep the clock fresh. [WearCommand.flags] carries its wants. */
    HELLO,
    /** The watch app left view. */
    BYE,
    /** A heartbeat: the watch app is open. */
    PING,
    OPEN_ON_PHONE,
}

/** [arg]: the seek target (lyric ms). [flags]: what the watch shows (HELLO). */
data class WearCommand(
    val type: WearCommandType,
    val arg: Long = 0L,
    val flags: Int = 0,
) {
    companion object {
        /** HELLO flag: romanize lyrics on the phone. */
        const val WANTS_ROMANIZATION = 1
    }
}
