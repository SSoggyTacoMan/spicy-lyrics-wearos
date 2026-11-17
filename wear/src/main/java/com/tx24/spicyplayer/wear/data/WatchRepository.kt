package com.tx24.spicyplayer.wear.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.tx24.spicyplayer.wear.BuildConfig
import com.tx24.spicyplayer.wear.proto.ClockSync
import com.tx24.spicyplayer.wear.proto.WatchClock
import com.tx24.spicyplayer.wear.proto.WearClock
import com.tx24.spicyplayer.wear.proto.WearCodec
import com.tx24.spicyplayer.wear.proto.WearCommand
import com.tx24.spicyplayer.wear.proto.WearCommandType
import com.tx24.spicyplayer.wear.proto.WearLyrics
import com.tx24.spicyplayer.wear.proto.WearPaths
import com.tx24.spicyplayer.wear.proto.WearTrack
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * What the phone last told the watch, kept from the Data Layer so the app opens on the last song
 * even with the phone away, and the transport sent the other way.
 *
 * The song and lyrics are DataItems the phone writes; the clock comes as Messages while the app is
 * open, so it only has to be read once. Main thread: the Data Layer calls back here, and the
 * screen reads plain [StateFlow]s.
 */
internal class WatchRepository private constructor(private val context: Context) {
    data class State(
        val track: WearTrack? = null,
        val lyrics: WearLyrics? = null,
        val clock: WearClock? = null,
        val cover: Bitmap? = null,
        val backdrop: Bitmap? = null,
        /** A phone with the app on it is reachable. */
        val connected: Boolean = false,
        /** The phone app is installed at all (a node advertises it). */
        val phoneKnown: Boolean = false,
        /** Why the last command didn't go. */
        val commandError: String? = null,
    ) {
        /** The lyrics belong to the song being played. */
        val lyricsMatch: Boolean get() = lyrics != null && track != null && lyrics.key == track.key
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val clock = WatchClock()
    val sync = ClockSync()

    private val main = Handler(Looper.getMainLooper())
    /** Play services block until they answer, so their callbacks are made off the screen's thread. */
    private val io = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )
    private var listening = false
    private var dataListener: DataClient.OnDataChangedListener? = null
    private var messageListener: MessageClient.OnMessageReceivedListener? = null
    private var capabilityListener: CapabilityClient.OnCapabilityChangedListener? = null
    private var phone: Node? = null
    private var pendingClock: WearClock? = null
    private var lastClockSeq = -1L
    private val cacheDir = File(context.filesDir, "now-playing").apply { mkdirs() }

    /** Debug builds: a made-up song so the screen can be seen without a phone. */
    @Volatile private var demoOriginMs = 0L
    val demo: Boolean get() = BuildConfig.DEMO && demoOriginMs != 0L

    fun startDemo() {
        if (!BuildConfig.DEMO) return
        demoOriginMs = SystemClock.elapsedRealtime()
        val track = Demo.track()
        val lyrics = Demo.lyrics(track)
        val clock = Demo.clock(track, demoOriginMs, demoOriginMs)
        this.clock.update(clock, 0L, demoOriginMs)
        _state.value = State(track = track, lyrics = lyrics, clock = clock, connected = true, phoneKnown = true)
    }

    /** Moves the demo song on, once every few hundred milliseconds is enough. */
    fun tickDemo() {
        if (!demo) return
        val track = _state.value.track ?: return
        val now = SystemClock.elapsedRealtime()
        val clock = Demo.clock(track, now, demoOriginMs)
        this.clock.update(clock, 0L, now)
        _state.value = _state.value.copy(clock = clock)
    }

    /** Reads what the phone last left, then follows the Data Layer. */
    fun start() {
        if (listening) return
        listening = true
        readCached()
        runCatching {
            val listener = DataClient.OnDataChangedListener { events -> onData(events) }
            dataListener = listener
            Wearable.getDataClient(context).addListener(listener)
        }.onFailure { Log.w(TAG, "no Data Layer", it) }
        runCatching {
            val listener = CapabilityClient.OnCapabilityChangedListener { info ->
                    val nodes = info.nodes.orEmpty()
                    phone = nodes.firstOrNull()
                _state.value = _state.value.copy(connected = nodes.isNotEmpty(), phoneKnown = nodes.isNotEmpty() || _state.value.phoneKnown)
            }
            capabilityListener = listener
            Wearable.getCapabilityClient(context).addListener(listener, WearPaths.PHONE_CAPABILITY)
        }
        runCatching {
            val listener = MessageClient.OnMessageReceivedListener { event ->
                if (event.path == WearPaths.CLOCK) WearCodec.decodeClock(event.data)?.let(::onClock)
            }
            messageListener = listener
            Wearable.getMessageClient(context).addListener(listener)
        }
        main.post(refreshNodes)
        main.postDelayed(heartbeat, HEARTBEAT_MS)
        askForState()
    }

    fun stop() {
        listening = false
        main.removeCallbacks(heartbeat)
        runCatching { dataListener?.let { Wearable.getDataClient(context).removeListener(it) } }
        runCatching { messageListener?.let { Wearable.getMessageClient(context).removeListener(it) } }
        runCatching { capabilityListener?.let { Wearable.getCapabilityClient(context).removeListener(it) } }
        dataListener = null
        messageListener = null
        capabilityListener = null
        // The lyrics carry on from the clock the watch already knows.
    }

    /** The song, the lyrics and the clock from the start, for a phone that may have been closed. */
    fun askForState() {
        send(WearCommand(WearCommandType.HELLO, flags = if (romanizationWanted) WearCommand.WANTS_ROMANIZATION else 0))
    }

    var romanizationWanted = true

    fun command(type: WearCommandType, arg: Long = 0L) {
        send(WearCommand(type, arg))
    }

    fun ping() = send(WearCommand(WearCommandType.PING))

    fun seekTo(positionMs: Long) {
        command(WearCommandType.SEEK, positionMs)
        val now = SystemClock.elapsedRealtime()
        clock.seekTo(positionMs, now)
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!listening) return
            ping()
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val refreshNodes = object : Runnable {
        override fun run() {
            io.launch {
                runCatching {
                    Tasks.await(
                        Wearable.getCapabilityClient(context).getCapability(WearPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE),
                    ).nodes
                }.onSuccess { nodes ->
                    main.post {
                        phone = nodes.firstOrNull()
                        _state.value = _state.value.copy(
                            connected = nodes.isNotEmpty(),
                            phoneKnown = nodes.isNotEmpty() || hasCached() || _state.value.phoneKnown,
                        )
                    }
                }.onFailure { Log.w(TAG, "capability lookup failed", it) }
            }
        }
    }

    private fun hasCached(): Boolean = File(cacheDir, "track").exists()

    private fun send(command: WearCommand) {
        val bytes = WearCodec.encodeCommand(command)
        val client = runCatching { Wearable.getMessageClient(context) }.getOrNull() ?: return
        val target = phone
        if (target != null) {
            client.sendMessage(target.id, WearPaths.COMMAND, bytes).addOnFailureListener { fail(it.message) }
            return
        }
        // No capability answer yet: every node gets it, and the phone ignores what isn't its own.
        runCatching {
            Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) fail("The phone isn't connected") else nodes.forEach { node ->
                    client.sendMessage(node.id, WearPaths.COMMAND, bytes).addOnFailureListener { fail(it.message) }
                }
            }.addOnFailureListener { fail(it.message) }
        }
    }

    private fun fail(message: String?) {
        main.post { _state.value = _state.value.copy(commandError = message) }
    }

    private fun onData(events: DataEventBuffer) {
        for (event in events) {
            val path = event.dataItem.uri.path ?: continue
            if (event.type == DataEvent.TYPE_DELETED) continue
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            val payload = map.getByteArray(WearPaths.KEY_PAYLOAD) ?: continue
            when (path) {
                WearPaths.TRACK -> {
                    val track = WearCodec.decodeTrack(payload) ?: continue
                    if (_state.value.track?.key != track.key) {
                        clock.reset()
                        // The lyrics can arrive before their song: keep them if they're its own.
                        val kept = _state.value.lyrics?.takeIf { it.key == track.key }
                        _state.value = _state.value.copy(track = track, lyrics = kept, cover = null, backdrop = null)
                        pendingClock?.let { late ->
                            pendingClock = null
                            onClock(late)
                        }
                        null
                    } else _state.value = _state.value.copy(track = track)
                    save("track", payload)
                    loadAsset(map.getAsset(WearPaths.KEY_COVER), "cover") { _state.value = _state.value.copy(cover = it) }
                    loadAsset(map.getAsset(WearPaths.KEY_BACKDROP), "backdrop") { _state.value = _state.value.copy(backdrop = it) }
                }
                WearPaths.LYRICS -> {
                    val lyrics = WearCodec.decodeLyrics(payload) ?: continue
                    _state.value = _state.value.copy(lyrics = lyrics)
                    save("lyrics", payload)
                }
                WearPaths.CLOCK -> {
                    WearCodec.decodeClock(payload)?.let(::onClock)
                }
            }
        }
    }

    private fun onClock(clock: WearClock) {
        val current = _state.value
        if (current.track?.key != clock.key) {
            // The clock for a song that isn't the one shown (yet): the song's DataItem may follow.
            pendingClock = clock
            return
        }
        lastClockSeq = clock.seq
        _state.value = _state.value.copy(clock = clock)
        val now = SystemClock.elapsedRealtime()
        sync.onWallClock(clock, now, System.currentTimeMillis())
        this.clock.update(clock, sync.offsetMs ?: 0L, now)
    }

    private fun loadAsset(asset: Asset?, name: String, deliver: (Bitmap?) -> Unit) {
        val file = File(cacheDir, name)
        val cached = file.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        if (cached != null && asset == null) {
            deliver(cached)
            return
        }
        if (asset == null) {
            deliver(null)
            return
        }
        runCatching {
            Wearable.getDataClient(context).getFdForAsset(asset).addOnSuccessListener { result ->
                val bitmap = result.inputStream?.use { BitmapFactory.decodeStream(it) }
                if (bitmap != null) {
                    file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    deliver(bitmap)
                }
            }
        }
    }

    /** A DataItem the app isn't open for: the next launch (and the tile) opens on it. */
    fun cache(path: String, payload: ByteArray) {
        val name = when (path) {
            WearPaths.TRACK -> "track"
            WearPaths.LYRICS -> "lyrics"
            WearPaths.CLOCK -> "clock"
            else -> return
        }
        save(name, payload)
    }

    fun cachedTrack(): WearTrack? = runCatching {
        File(cacheDir, "track").takeIf { it.exists() }?.readBytes()?.let(WearCodec::decodeTrack)
    }.getOrNull()

    fun cachedLyrics(): WearLyrics? = runCatching {
        File(cacheDir, "lyrics").takeIf { it.exists() }?.readBytes()?.let(WearCodec::decodeLyrics)
    }.getOrNull()

    fun cachedClock(): WearClock? = runCatching {
        File(cacheDir, "clock").takeIf { it.exists() }?.readBytes()?.let(WearCodec::decodeClock)
    }.getOrNull()

    private fun save(name: String, bytes: ByteArray) {
        runCatching { File(cacheDir, name).writeBytes(bytes) }
    }

    private fun readCached() {
        runCatching {
            val track = File(cacheDir, "track").takeIf { it.exists() }?.readBytes()?.let(WearCodec::decodeTrack)
            val lyrics = File(cacheDir, "lyrics").takeIf { it.exists() }?.readBytes()?.let(WearCodec::decodeLyrics)
            val cover = File(cacheDir, "cover").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            val backdrop = File(cacheDir, "backdrop").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            val clock = File(cacheDir, "clock").takeIf { it.exists() }?.readBytes()?.let(WearCodec::decodeClock)
            _state.value = _state.value.copy(track = track, lyrics = lyrics, clock = clock, cover = cover, backdrop = backdrop)
            if (clock != null && track?.key == clock.key) {
                sync.onWallClock(clock, SystemClock.elapsedRealtime(), System.currentTimeMillis())
                this.clock.update(clock, sync.offsetMs ?: 0L, SystemClock.elapsedRealtime())
            }
        }
    }

    companion object {
        private const val TAG = "SpicyWear"
        private const val HEARTBEAT_MS = 30_000L

        @Volatile private var instance: WatchRepository? = null

        fun of(context: Context): WatchRepository = instance ?: synchronized(this) {
            instance ?: WatchRepository(context.applicationContext).also { instance = it }
        }
    }
}
