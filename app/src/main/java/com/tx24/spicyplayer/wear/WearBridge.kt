package com.tx24.spicyplayer.wear

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.tx24.spicyplayer.lyrics.LyricsState
import com.tx24.spicyplayer.lyrics.TimedLine
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import com.tx24.spicyplayer.wear.proto.WearClock
import com.tx24.spicyplayer.wear.proto.WearCodec
import com.tx24.spicyplayer.wear.proto.WearCommand
import com.tx24.spicyplayer.wear.proto.WearCommandType
import com.tx24.spicyplayer.wear.proto.WearCredit
import com.tx24.spicyplayer.wear.proto.WearLine
import com.tx24.spicyplayer.wear.proto.WearLyrics
import com.tx24.spicyplayer.wear.proto.WearPaths
import com.tx24.spicyplayer.wear.proto.WearStatus
import com.tx24.spicyplayer.wear.proto.WearSyncType
import com.tx24.spicyplayer.wear.proto.WearTrack
import com.tx24.spicyplayer.wear.proto.WearWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * The phone half of the watch bridge: writes the song, its lyrics and the clock to the Wear OS
 * Data Layer, and hands the watch's commands to [WatchFeed].
 *
 * The song and its lyrics go as DataItems, which wait for the watch when it isn't connected; the
 * clock and the pings go as Messages, which want to be neither queued nor replayed. Nothing is
 * written until the watch app has said hello (or a watch with the app is found), so a phone with
 * no watch pays nothing, and the clock only flows while the watch app is open.
 *
 * Main thread, except the writes themselves.
 */
internal object WearBridge : WatchFeed {
    private const val TAG = "SpicyWear"
    private const val PREFS = "wear_bridge"
    private const val ENABLED = "enabled"
    private const val PAIRED = "paired"
    private const val ROMANIZATION = "romanization"
    /** How long the watch app counts as open after its last hello. */
    private const val OPEN_FOR_MS = 90_000L
    /** The clock goes at most this often while playing, besides play, pause and seeks. */
    private const val CLOCK_MIN_GAP_MS = 4_000L
    private const val CLOCK_DRIFT_MS = 40L
    private const val COVER_PX = 300
    private const val BACKDROP_PX = 180

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var context: Context? = null
    private var feed: WatchFeed? = null

    /** A watch with the app on it has been seen: the bridge may run. */
    @Volatile var paired = false
        private set
    @Volatile private var helloAtMs = 0L
    @Volatile private var open = false
    @Volatile var romanization = true
        private set
    @Volatile var enabled = true
        private set

    private var track: WearTrack? = null
    private var cover: Bitmap? = null
    private var backdrop: Bitmap? = null
    /** The last lyrics written, and the song they were for. */
    private var sentLyrics: Pair<String, WearLyrics>? = null
    private var clockSeq = 0L
    private var lastClockKey: String? = null
    private var lastClockPositionMs = Long.MIN_VALUE
    private var lastClockAtMs = 0L
    private var lastClockSpeed = 0f
    private var lastClockPlaying = false

    /** Whether the watch app is open. */
    val watchOpen: Boolean get() = open

    /** Whether the watch app is open: the bridge follows the player only then. */
    val wanted: Boolean get() = enabled && open && context != null

    @Volatile private var listening = false

    /** Called as the app process starts, and again when the settings change. */
    fun init(context: Context) {
        val app = context.applicationContext
        val first = this.context == null
        this.context = app
        val prefs = app.getSharedPreferences(PREFS, 0)
        enabled = prefs.getBoolean(ENABLED, true)
        paired = prefs.getBoolean(PAIRED, false)
        romanization = prefs.getBoolean(ROMANIZATION, true)
        if (!enabled || (!first && listening)) return
        listening = true
        runCatching {
            Wearable.getCapabilityClient(app).addListener(
                { info -> if (info.nodes?.isNotEmpty() == true) setPaired() },
                WearPaths.WATCH_CAPABILITY,
            )
        }.onFailure { Log.w(TAG, "no Wearable capability client", it) }
        scope.launch {
            runCatching {
                val found = Tasks.await(
                    Wearable.getCapabilityClient(app).getCapability(WearPaths.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE),
                )
                if (found.nodes.isNotEmpty()) setPaired()
            }
        }
    }

    private fun setPaired() {
        paired = true
        context?.getSharedPreferences(PREFS, 0)?.edit()?.putBoolean(PAIRED, true)?.apply()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        context?.getSharedPreferences(PREFS, 0)?.edit()?.putBoolean(ENABLED, value)?.apply()
        if (!value) open = false
    }

    fun setRomanization(value: Boolean) {
        romanization = value
        context?.getSharedPreferences(PREFS, 0)?.edit()?.putBoolean(ROMANIZATION, value)?.apply()
    }

    /** The holder follows the player: it hands over the song, the lyrics and the clock. */
    fun attach(feed: WatchFeed) {
        this.feed = feed
        // A song already playing when the watch opens: it gets the whole state at once.
        sentLyrics = null
        lastClockPositionMs = Long.MIN_VALUE
    }

    /** The holder stopped: forget the song with it. */
    fun detach(feed: WatchFeed) {
        if (this.feed !== feed) return
        this.feed = null
        track = null
        cover = null
        backdrop = null
        sentLyrics = null
        lastClockKey = null
        lastClockPositionMs = Long.MIN_VALUE
    }

    /** The holder reads the song's lyrics again, now that the watch has said what it shows. */
    var onReloadLyrics: (() -> Unit)? = null

    /** The holder republishes the song it's on, for a watch that just opened. */
    var onRepublish: (() -> Unit)? = null

    // ---- The watch app's messages (its listener service, main thread) ------------------------

    /** The watch app opened: it gets everything again, and what it shows is asked for anew. */
    fun onHello(wantsRomanization: Boolean) {
        helloAtMs = SystemClock.elapsedRealtime()
        open = true
        setPaired()
        if (wantsRomanization != romanization) {
            romanization = wantsRomanization
            context?.getSharedPreferences(PREFS, 0)?.edit()?.putBoolean(ROMANIZATION, wantsRomanization)?.apply()
            onReloadLyrics?.invoke()
        }
        sentLyrics = null
        lastClockPositionMs = Long.MIN_VALUE
        onRepublish?.invoke()
    }

    fun onBye() {
        open = false
    }

    fun onCommand(command: WearCommand) {
        val feed = feed ?: return
        when (command.type) {
            WearCommandType.PLAY_PAUSE -> feed.onCommand("playPause", 0L)
            WearCommandType.NEXT -> feed.onCommand("next", 0L)
            WearCommandType.PREVIOUS -> feed.onCommand("previous", 0L)
            WearCommandType.SEEK -> feed.onCommand("seek", command.arg)
            else -> Unit
        }
    }

    /** The watch app's heartbeats: it's open while they keep coming. */
    fun keepAlive() {
        helloAtMs = SystemClock.elapsedRealtime()
        open = true
    }

    /** The watch app has gone quiet, so it's closed. Called from the holder's timer. */
    fun checkOpen() {
        if (open && SystemClock.elapsedRealtime() - helloAtMs > OPEN_FOR_MS) open = false
    }

    // ---- WatchFeed, from the holder ---------------------------------------------------------

    override fun onTrack(track: WatchTrack) {
        val next = WearTrack(
            key = track.key,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            playerPackage = track.playerPackage,
            playerName = track.playerName,
            colors = track.colors,
        )
        val sameSong = this.track?.key == next.key
        val coverChanged = !samePixels(cover, track.cover)
        this.track = next
        cover = track.cover
        backdrop = track.backdrop
        if (sameSong && !coverChanged) return
        sentLyrics = null
        lastClockKey = null
        lastClockPositionMs = Long.MIN_VALUE
        publish()
    }

    override fun onLyrics(key: String?, lyrics: LyricsState?, lines: List<TimedLine>?, notice: WearStatus?) {
        val ready = lyrics as? LyricsState.Ready
        val payload = WearLyrics(
            key = key.orEmpty(),
            status = when {
                ready != null && lines.isNullOrEmpty() -> WearStatus.NO_LYRICS
                ready != null -> WearStatus.READY
                notice != null -> notice
                else -> status(lyrics)
            },
            type = ready?.let { syncType(it.lyricsType) } ?: WearSyncType.SYLLABLE,
            lines = if (ready != null && !lines.isNullOrEmpty()) watchLines(lines) else emptyList(),
            credit = ready?.let(::credit),
        )
        if (sentLyrics == (payload.key to payload)) return
        sentLyrics = payload.key to payload
        val bytes = WearCodec.encodeLyrics(payload)
        sendData(WearPaths.LYRICS) { it.putByteArray(WearPaths.KEY_PAYLOAD, bytes) }
    }

    override fun onClock(key: String?, positionMs: Long, atPhoneMs: Long, speed: Float, playing: Boolean, canSeek: Boolean) {
        if (key == null) return
        val moved = if (lastClockPositionMs == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(positionMs - lastClockPositionMs)
        val changed = key != lastClockKey || playing != lastClockPlaying || speed != lastClockSpeed
        if (!changed && moved < CLOCK_DRIFT_MS && SystemClock.elapsedRealtime() - lastClockAtMs < CLOCK_MIN_GAP_MS) return
        lastClockKey = key
        lastClockPositionMs = positionMs
        lastClockAtMs = SystemClock.elapsedRealtime()
        lastClockSpeed = speed
        lastClockPlaying = playing
        writeClock(
            WearClock(key, positionMs, atPhoneMs, System.currentTimeMillis(), speed, playing, canSeek, ++clockSeq),
        )
    }

    override fun onCommand(type: String, arg: Long) = Unit

    /** A new song for a watch: its names, its cover and a still of the background. */
    private fun publish() {
        val track = track ?: return
        val bytes = WearCodec.encodeTrack(track)
        val coverBytes = cover?.let { jpeg(it, COVER_PX) }
        val backdropBytes = backdrop?.let { jpeg(it, BACKDROP_PX) }
        sendData(WearPaths.TRACK) { map ->
            map.putByteArray(WearPaths.KEY_PAYLOAD, bytes)
            if (coverBytes != null) map.putAsset(WearPaths.KEY_COVER, Asset.createFromBytes(coverBytes))
            if (backdropBytes != null) map.putAsset(WearPaths.KEY_BACKDROP, Asset.createFromBytes(backdropBytes))
        }
    }

    private fun writeClock(clock: WearClock) {
        if (open) sendMessage(WearPaths.CLOCK, WearCodec.encodeClock(clock))
        val bytes = WearCodec.encodeClock(clock)
        sendData(WearPaths.CLOCK) { it.putByteArray(WearPaths.KEY_PAYLOAD, bytes) }
    }

    private fun sendData(path: String, fill: (DataMap) -> Unit) {
        val app = context ?: return
        val request = PutDataMapRequest.create(path).apply { fill(dataMap) }.asPutDataRequest().setUrgent()
        scope.launch {
            runCatching { Tasks.await(Wearable.getDataClient(app).putDataItem(request)) }
                .onFailure { Log.w(TAG, "DataItem $path failed", it) }
        }
    }

    private fun sendMessage(path: String, payload: ByteArray) {
        val app = context ?: return
        scope.launch {
            runCatching {
                val nodes = Tasks.await(Wearable.getNodeClient(app).connectedNodes)
                val client = Wearable.getMessageClient(app)
                for (node in nodes) Tasks.await(client.sendMessage(node.id, path, payload))
            }.onFailure { Log.w(TAG, "message $path failed", it) }
        }
    }

    private fun credit(lyrics: LyricsState.Ready): WearCredit = WearCredit(
        provider = lyrics.provider,
        source = lyrics.source,
        maker = lyrics.maker?.username,
        uploader = lyrics.uploader?.username,
        songwriters = lyrics.songwriters,
    )

    private fun syncType(type: LyricsType): WearSyncType = when (type) {
        LyricsType.Syllable -> WearSyncType.SYLLABLE
        LyricsType.Line -> WearSyncType.LINE
        LyricsType.Static -> WearSyncType.STATIC
    }

    private fun status(lyrics: LyricsState?): WearStatus = when (lyrics) {
        is LyricsState.Ready -> if (lyrics.lines.isEmpty()) WearStatus.NO_LYRICS else WearStatus.READY
        is LyricsState.Error -> WearStatus.FAILED
        is LyricsState.Loading -> WearStatus.LOOKING
        else -> WearStatus.WAITING
    }

    /** The lines to draw, without the interludes the watch makes for itself. */
    private fun watchLines(lines: List<TimedLine>): List<WearLine> = lines
        .filter { it.role != LineRole.INTERLUDE }
        .map { line ->
            WearLine(
                startMs = line.startMs,
                endMs = line.endMs,
                words = line.words.map { word ->
                    WearWord(word.text, word.startMs, word.endMs, word.attached, word.romanized)
                },
                background = line.role == LineRole.BACKGROUND,
                groupId = line.groupId ?: -1,
                opposite = line.oppositeAligned,
                lineTimed = line.lineTimed,
                translation = line.translation,
            )
        }

    private fun samePixels(a: Bitmap?, b: Bitmap?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        return runCatching { a.sameAs(b) }.getOrDefault(false)
    }

    /** The cover as a JPEG of at most [px] on its long side: small enough for the Data Layer. */
    private fun jpeg(source: Bitmap, px: Int): ByteArray? = runCatching {
        val long = maxOf(source.width, source.height)
        val scaled = if (long <= px) source else {
            val scale = px.toFloat() / long
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }
        ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }.getOrNull()
}
