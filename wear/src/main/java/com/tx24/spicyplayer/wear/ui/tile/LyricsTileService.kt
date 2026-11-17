package com.tx24.spicyplayer.wear.ui.tile

import android.content.ComponentName
import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.tx24.spicyplayer.wear.R
import com.tx24.spicyplayer.wear.WearActivity
import com.tx24.spicyplayer.wear.data.WatchRepository
import com.tx24.spicyplayer.wear.proto.WearTimeline

/**
 * A tile with the line being sung: the one thing worth a glance from the watch face.
 *
 * A tile repaints on its own schedule and a line changes far more often, so each build reads the
 * phone's last position through the watch's clock and shows where the song is then.
 */
class LyricsTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val repository = WatchRepository.of(this)
        val track = repository.cachedTrack()
        val line = lineNow(repository)
        return CallbackToFutureAdapter.getFuture { completer ->
            completer.set(
                TileBuilders.Tile.Builder()
                    .setResourcesVersion(RESOURCES)
                    .setTileTimeline(
                        TimelineBuilders.Timeline.Builder().addTimelineEntry(
                            TimelineBuilders.TimelineEntry.Builder()
                                .setLayout(
                                    LayoutElementBuilders.Layout.Builder()
                                        .setRoot(layout(line, track?.title, track?.artist))
                                        .build(),
                                )
                                .build(),
                        ).build(),
                    )
                    .build(),
            )
            "spicy-tile"
        }
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(
                ResourceBuilders.Resources.Builder()
                    .addIdToImageMapping(
                        "icon",
                        ResourceBuilders.ImageResource.Builder()
                            .setAndroidResourceByResId(
                                ResourceBuilders.AndroidImageResourceByResId.Builder()
                                    .setResourceId(R.drawable.tile_icon)
                                    .build(),
                            )
                            .build(),
                    )
                    .setVersion(RESOURCES)
                    .build(),
            )
            "spicy-resources"
        }

    private fun layout(line: String?, title: String?, artist: String?): LayoutElementBuilders.LayoutElement {
        val open = ModifiersBuilders.Clickable.Builder()
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setClassName(WearActivity::class.java.name)
                            .setPackageName(packageName)
                            .build(),
                    )
                    .build(),
            )
            .build()
        val main = LayoutElementBuilders.Text.Builder()
            .setText(line ?: if (title != null) "Tap for the lyrics" else "Play something")
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .setSize(DimensionBuilders.sp(17f))
                    .setColor(ColorBuilders.argb(0xFFFFFFFF.toInt()))
                    .build(),
            )
            .setMaxLines(3)
            .build()
        val sub = LayoutElementBuilders.Text.Builder()
            .setText(listOfNotNull(title, artist).joinToString(" · "))
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.sp(11f))
                    .setColor(ColorBuilders.argb(0xFF8D9099.toInt()))
                    .build(),
            )
            .setMaxLines(1)
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(open)
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(0xFF000000.toInt()))
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setPadding(
                                ModifiersBuilders.Padding.Builder()
                                    .setStart(DimensionBuilders.dp(14f))
                                    .setEnd(DimensionBuilders.dp(14f))
                                    .setTop(DimensionBuilders.dp(10f))
                                    .build(),
                            )
                            .build(),
                    )
                    .addContent(main)
                    .addContent(
                        LayoutElementBuilders.Spacer.Builder()
                            .setHeight(DimensionBuilders.dp(4f))
                            .build(),
                    )
                    .addContent(sub)
                    .build(),
            )
            .build()
    }

    /** The line being sung at the phone's last position, read on the watch's clock. */
    private fun lineNow(repository: WatchRepository): String? {
        val track = repository.cachedTrack() ?: return null
        val lyrics = repository.cachedLyrics() ?: return null
        val clock = repository.cachedClock() ?: return null
        if (lyrics.key != track.key || clock.key != track.key) return null
        val timeline = WearTimeline(lyrics)
        val elapsed = if (clock.playing) (System.currentTimeMillis() - clock.phoneWallMs).coerceAtLeast(0L) else 0L
        val position = clock.positionMs + (elapsed * clock.speed).toLong()
        val row = timeline.rows.getOrNull(timeline.activeIndex(position)) as? WearTimeline.Vocal ?: return null
        return row.lead.words.joinToString(separator = "") { (if (it.attached) "" else " ") + it.text }.trim().ifEmpty { null }
    }

    companion object {
        private const val RESOURCES = "1"

        /** Asks the system to build the tile again. */
        fun requestRefresh(context: Context) {
            runCatching { TileService.getUpdater(context).requestUpdate(LyricsTileService::class.java) }
        }
    }
}
