package com.tx24.spicyplayer.wear.ui.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.ComplicationDisplayPolicy
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.tx24.spicyplayer.wear.R
import com.tx24.spicyplayer.wear.WearActivity
import com.tx24.spicyplayer.wear.data.WatchRepository

/**
 * The song on the watch face: its name, and the line being sung where the watch face has room.
 * A complication repaints on its own schedule, so it shows what the phone last said.
 */
class NowPlayingComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, "Spicy Lyrics", "Midnight City", null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val repository = WatchRepository.of(this)
        val track = repository.cachedTrack() ?: return build(request.complicationType, "Spicy Lyrics", null, null)
        val lyrics = repository.cachedLyrics()?.takeIf { it.key == track.key }
        val clock = repository.cachedClock()?.takeIf { it.key == track.key }
        val line = if (lyrics != null && clock != null) {
            val timeline = com.tx24.spicyplayer.wear.proto.WearTimeline(lyrics)
            val elapsed = if (clock.playing) (System.currentTimeMillis() - clock.phoneWallMs).coerceAtLeast(0L) else 0L
            val row = timeline.rows.getOrNull(timeline.activeIndex(clock.positionMs + (elapsed * clock.speed).toLong())) as? com.tx24.spicyplayer.wear.proto.WearTimeline.Vocal
            row?.lead?.words?.joinToString(separator = "") { (if (it.attached) "" else " ") + it.text }?.trim()
        } else null
        return build(request.complicationType, track.title, track.artist, line)
    }

    private fun build(type: ComplicationType, title: String, artist: String?, line: String?): ComplicationData? {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WearActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = PlainComplicationText.Builder(title).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = text,
                contentDescription = PlainComplicationText.Builder("$title${artist?.let { " by $it" } ?: ""}").build(),
            ).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(listOfNotNull(artist, line?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { title }).build(),
                contentDescription = PlainComplicationText.Builder("$title${artist?.let { " by $it" } ?: ""}").build(),
            ).setTitle(text).setTapAction(tap).build()
            else -> null
        }
    }

    companion object {
        /** Asks every watch face wearing it to ask again. */
        fun requestRefresh(context: Context) {
            runCatching {
                ComplicationDataSourceUpdateRequester.create(
                    context,
                    ComponentName(context, NowPlayingComplicationService::class.java),
                ).requestUpdateAll()
            }
        }
    }
}
