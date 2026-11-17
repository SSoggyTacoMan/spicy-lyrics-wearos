package com.tx24.spicyplayer.wear

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.tx24.spicyplayer.wear.proto.WearCodec
import com.tx24.spicyplayer.wear.proto.WearCommand
import com.tx24.spicyplayer.wear.proto.WearCommandType
import com.tx24.spicyplayer.wear.proto.WearPaths
import com.tx24.spicyplayer.widget.NowPlayingHolder

/**
 * The watch app's messages: hello, bye, pings and the transport. Wakes the app's process if it
 * isn't running, so a watch opening starts the bridge even with the phone in a pocket.
 */
class WearCommandService : WearableListenerService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onMessageReceived(event: MessageEvent) {
        if (!event.path.startsWith("/spicy/")) return
        val command = WearCodec.decodeCommand(event.data) ?: run {
            Log.w(TAG, "unreadable command on ${event.path}")
            return
        }
        // The holder and the bridge are main-thread: this arrives on the binder's.
        main.post {
            WearBridge.init(applicationContext)
            when (command.type) {
                WearCommandType.HELLO -> WearBridge.onHello(command.flags and WearCommand.WANTS_ROMANIZATION != 0)
                WearCommandType.BYE -> {
                    WearBridge.onBye()
                    // No widget either: nothing needs the player followed now.
                    NowPlayingHolder.stopUnlessWatched()
                }
                WearCommandType.PING -> WearBridge.keepAlive()
                else -> WearBridge.onCommand(command)
            }
            // The holder follows the player for the watch, and stops with the widget when neither wants it.
            NowPlayingHolder.startForWatch(applicationContext)
        }
    }

    private companion object {
        const val TAG = "SpicyWear"
    }
}
