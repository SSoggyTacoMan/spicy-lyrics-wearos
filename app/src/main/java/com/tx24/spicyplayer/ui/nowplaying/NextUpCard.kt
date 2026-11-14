package com.tx24.spicyplayer.ui.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tx24.spicyplayer.playback.QueueEntry
import com.tx24.spicyplayer.ui.components.LocalUiAnimations
import com.tx24.spicyplayer.ui.settings.QueueCover
import com.tx24.spicyplayer.ui.theme.SpicyColors
import com.tx24.spicyplayer.ui.theme.SpicyMotion
import com.tx24.spicyplayer.ui.theme.SpicySpacing
import com.tx24.spicyplayer.ui.theme.SpicyType

/**
 * The song queued next, as plain type beside its cover and without a plate, like the queue's
 * first row peeking in under the lyrics. Display only. When the next song changes while it
 * shows, the two cross-fade. [shown] is the card's own opacity, 0..1; [onHeight] reports its
 * measured height so the lyrics can fade out above it.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun NextUpCard(entry: QueueEntry?, shown: () -> Float, onHeight: (Int) -> Unit, modifier: Modifier = Modifier) {
    val animate = LocalUiAnimations.current
    Box(modifier.graphicsLayer { alpha = shown() }.onSizeChanged { onHeight(it.height) }) {
        AnimatedContent(
            targetState = entry,
            transitionSpec = {
                val ms = if (animate) SpicyMotion.FAST_MS else 0
                fadeIn(tween(ms, delayMillis = 0)) togetherWith fadeOut(tween(ms))
            },
            contentKey = { it?.id },
            label = "nextUp",
        ) { shownEntry ->
            if (shownEntry != null) NextUpBody(shownEntry, animate)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NextUpBody(entry: QueueEntry, animate: Boolean) {
    val iterations = if (animate) Int.MAX_VALUE else 0
    Row(
        Modifier.fillMaxWidth().padding(horizontal = SpicySpacing.S3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpicySpacing.S3),
    ) {
        QueueCover(entry, size = 48.dp)
        Column(Modifier.weight(1f)) {
            Text("Up Next", maxLines = 1, style = SpicyType.Footnote.copy(color = SpicyColors.TextTertiary, fontWeight = FontWeight.SemiBold))
            Text(
                entry.title,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.basicMarquee(iterations = iterations),
                style = SpicyType.Body.copy(fontWeight = FontWeight.SemiBold),
            )
            if (entry.subtitle.isNotBlank()) {
                Text(
                    entry.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.basicMarquee(iterations = iterations),
                    style = SpicyType.Caption.copy(color = SpicyColors.TextSecondary),
                )
            }
        }
    }
}
