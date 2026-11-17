package com.tx24.spicyplayer.wear.data

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.tx24.spicyplayer.wear.proto.WearCodec
import com.tx24.spicyplayer.wear.proto.WearPaths
import com.tx24.spicyplayer.wear.ui.complication.NowPlayingComplicationService
import com.tx24.spicyplayer.wear.ui.tile.LyricsTileService

/**
 * Keeps the watch's copy of the song current while the app isn't open: the tile, the complication
 * and the next launch open on what was playing. The screen itself reads the same Data Layer when
 * it starts, so this only has to remember it.
 */
class WatchDataService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val repository = WatchRepository.of(this)
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            val payload = DataMapItem.fromDataItem(event.dataItem).dataMap.getByteArray(WearPaths.KEY_PAYLOAD) ?: continue
            when (path) {
                WearPaths.TRACK -> repository.cache(WearPaths.TRACK, payload)
                WearPaths.LYRICS -> repository.cache(WearPaths.LYRICS, payload)
                WearPaths.CLOCK -> repository.cache(WearPaths.CLOCK, payload)
            }
        }
        val track = repository.cachedTrack()
        if (track != null) {
            LyricsTileService.requestRefresh(this)
            NowPlayingComplicationService.requestRefresh(this)
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearPaths.CLOCK) return
        val clock = WearCodec.decodeClock(event.data) ?: return
        WatchRepository.of(this).cache(WearPaths.CLOCK, event.data)
        // Which line is on the tile is read when the tile asks; nothing to push.
        Log.d("SpicyWear", "clock ${clock.key} at ${clock.positionMs}")
    }
}
