package com.tx24.spicyplayer.playback

import android.service.notification.NotificationListenerService
import android.content.ComponentName
import android.os.Build
import com.tx24.spicyplayer.wear.WearBridge
import com.tx24.spicyplayer.widget.NowPlayingHolder

/**
 * Exists for its grant: `MediaSessionManager` lets a notification listener read other apps'
 * sessions. While access is on, the system keeps it bound and the app's process alive, so the
 * home screen widget starts following the music here.
 */
class SessionAccessService : NotificationListenerService() {
    override fun onListenerConnected() {
        // The watch bridge looks for a watch, and the widget for a placed one: either runs the holder.
        WearBridge.init(this)
        NowPlayingHolder.start(this)
    }

    override fun onListenerDisconnected() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, SessionAccessService::class.java))
        }
    }
}
