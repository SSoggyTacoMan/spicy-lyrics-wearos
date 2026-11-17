package com.tx24.spicyplayer.wear.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.PagerDefaults
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.tx24.spicyplayer.wear.BuildConfig
import com.tx24.spicyplayer.wear.data.WatchRepository
import com.tx24.spicyplayer.wear.proto.WearCommandType
import com.tx24.spicyplayer.wear.proto.WearStatus
import com.tx24.spicyplayer.wear.proto.WearSyncType
import com.tx24.spicyplayer.wear.proto.WearTimeline
import com.tx24.spicyplayer.wear.settings.BackgroundStyle
import com.tx24.spicyplayer.wear.settings.WearSettings
import com.tx24.spicyplayer.wear.ui.lyrics.LyricsCanvas
import com.tx24.spicyplayer.wear.ui.theme.Glyphs
import com.tx24.spicyplayer.wear.ui.theme.LyricsFont
import com.tx24.spicyplayer.wear.ui.theme.LocalAccent
import com.tx24.spicyplayer.wear.ui.theme.WearColors
import kotlin.math.sin

/**
 * The watch's screen: the lyrics with the transport a tap away, and the song on the next page.
 * Settings open on their own screen.
 */
@Composable
internal fun WearApp(
    state: WatchRepository.State,
    repository: WatchRepository,
    settings: WearSettings,
    ambient: Boolean,
    onOpenSettings: () -> Unit,
) {
    AppScaffold {
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val pager = rememberPagerState(pageCount = { 2 })
        // No page dots: they'd sit on the last line of the lyrics.
        HorizontalPagerScaffold(pagerState = pager, pageIndicator = null) {
            HorizontalPager(
                state = pager,
                flingBehavior = PagerDefaults.snapFlingBehavior(state = pager),
                rotaryScrollableBehavior = null,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> LyricsPage(state, repository, settings, ambient, onOpenSettings)
                    else -> SongPage(state, repository, settings, ambient)
                }
            }
        }
    }
}

@Composable
private fun LyricsPage(
    state: WatchRepository.State,
    repository: WatchRepository,
    settings: WearSettings,
    ambient: Boolean,
    onOpenSettings: () -> Unit,
) {
    var controls by remember { mutableStateOf(false) }
    val timeline = remember(state.lyrics, settings.romanization, settings.translation, settings.backgroundVocals) {
        state.lyrics?.let { WearTimeline(it) }
    }
    val showsLyrics = timeline != null && state.lyricsMatch && timeline.rows.isNotEmpty() && timeline.synced
    val widthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val fontSize = remember(settings.textSize, widthDp) {
        (settings.textSize.scale * (widthDp * 0.088f).coerceIn(15f, 21f)).sp
    }
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(repository, settings, ambient) {
        // Read every frame, outside composition: only the canvas redraws.
        while (true) {
            withFrameNanos { position = currentPosition(repository, settings) }
        }
    }
    LaunchedEffect(controls) {
        if (!controls) return@LaunchedEffect
        kotlinx.coroutines.delay(4_000)
        controls = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(WearColors.Background)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (showsLyrics) controls = !controls },
                    onLongPress = { onOpenSettings() },
                )
            },
    ) {
        if (!ambient) SongBackdrop(state, settings, dim = 0.62f)
        when {
            showsLyrics && timeline != null && ambient -> AmbientLine(timeline, position)
            showsLyrics && timeline != null -> LyricsCanvas(
                timeline = timeline,
                lyricsKey = state.lyrics?.key.orEmpty(),
                fontSize = fontSize,
                accent = LocalAccent.current,
                effectLevel = settings.effects,
                alignment = settings.alignment,
                romanization = settings.romanization,
                translation = settings.translation,
                backgroundVocals = settings.backgroundVocals,
                positionProvider = { position },
                onSeek = { target -> if (settings.tapToSeek) repository.seekTo(target) },
                onTap = { controls = true },
                onLongPress = onOpenSettings,
                // Under the transport the lyrics go: the transport is what's wanted then.
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (controls) 0f else 1f },
            )
            else -> Notice(state, ambient, onDemo = if (BuildConfig.DEMO) repository::startDemo else null)
        }
        if (!ambient) {
            if (controls) {
                Transport(state, repository, onDone = { controls = false }, onSettings = onOpenSettings)
            } else {
                TimeText(modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

/** The line being sung, plain white on black, for ambient mode. */
@Composable
private fun AmbientLine(timeline: WearTimeline, positionMs: Long) {
    val row = timeline.rows.getOrNull(timeline.activeIndex(positionMs)) as? WearTimeline.Vocal
    val text = row?.lead?.words?.joinToString(separator = "") { word ->
        (if (word.attached) "" else " ") + word.text
    }?.trim().orEmpty()
    Column(
        Modifier.fillMaxSize().padding(horizontal = 26.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text.ifEmpty { "Spicy Lyrics" },
            color = Color.White,
            fontFamily = LyricsFont,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 24.sp,
        )
    }
}

/** The transport over a dark scrim: the song, play or pause, and where it is. */
@Composable
private fun Transport(state: WatchRepository.State, repository: WatchRepository, onDone: () -> Unit, onSettings: () -> Unit) {
    val playing = state.clock?.playing == true
    val progress = rememberProgress(repository, state.track?.durationMs ?: 0L)
    val accent = LocalAccent.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .pointerInput(Unit) { detectTapGestures { onDone() } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f - 26f
            val stroke = 3.dp.toPx()
            drawCircle(color = Color.White.copy(alpha = 0.12f), radius = radius, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(size.width / 2f - radius, size.height / 2f - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = state.track?.title.orEmpty().ifEmpty { "Nothing playing" },
                color = WearColors.Text,
                fontFamily = LyricsFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { repository.command(WearCommandType.PREVIOUS) }, modifier = Modifier.size(36.dp)) {
                    Icon(Glyphs.Previous, contentDescription = "Previous", tint = WearColors.Text, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = { repository.command(WearCommandType.PLAY_PAUSE) },
                    modifier = Modifier.size(54.dp).background(LocalAccent.current, CircleShape),
                ) {
                    Icon(
                        if (playing) Glyphs.Pause else Glyphs.Play,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = { repository.command(WearCommandType.NEXT) }, modifier = Modifier.size(36.dp)) {
                    Icon(Glyphs.Next, contentDescription = "Next", tint = WearColors.Text, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.track?.artist.orEmpty(),
                color = WearColors.TextDim,
                style = MaterialTheme.typography.labelSmall.copy(textAlign = TextAlign.Center),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).size(38.dp)) {
            Icon(Glyphs.Settings, contentDescription = "Settings", tint = WearColors.TextDim, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun rememberProgress(repository: WatchRepository, durationMs: Long): Float {
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(repository, durationMs) {
        while (true) {
            withFrameNanos {
                progress = if (durationMs > 0) {
                    (repository.clock.positionAt(it / 1_000_000).toFloat() / durationMs).coerceIn(0f, 1f)
                } else 0f
            }
        }
    }
    return progress
}

/** What the screen says when there are no lyrics to show. */
@Composable
private fun Notice(state: WatchRepository.State, ambient: Boolean, onDemo: (() -> Unit)? = null) {
    val track = state.track
    val (title, body) = when {
        state.lyrics?.status == WearStatus.NO_ACCESS -> "Allow notification access" to
            "Open Spicy Lyrics on your phone and let it see what's playing; then the lyrics come here."
        track == null && !state.connected -> "No song yet" to "Play something on your phone."
        track == null -> "Play something" to "The lyrics follow whatever plays on your phone."
        state.lyrics?.status == WearStatus.NO_SONG_INFO -> "The player doesn't name the song" to "Skip to the next one and back."
        state.lyrics == null || state.lyrics?.status == WearStatus.LOOKING -> "Looking for lyrics" to track.title
        state.lyrics?.status == WearStatus.NO_LYRICS -> "No lyrics for this song" to "${track.title} · ${track.artist}"
        state.lyrics?.status == WearStatus.FAILED -> "Couldn't load the lyrics" to "Open Spicy Lyrics on your phone to try again."
        state.lyrics?.type == WearSyncType.STATIC -> "These lyrics have no timing" to track.title
        !state.lyricsMatch -> "Getting the lyrics" to track.title
        else -> "Play something" to ""
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
        )
        if (body.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                color = if (ambient) Color.White.copy(alpha = 0.6f) else WearColors.TextDim,
                style = MaterialTheme.typography.labelMedium.copy(textAlign = TextAlign.Center),
            )
        }
        // Debug builds only: a song to look at with no phone around.
        if (onDemo != null) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onDemo,
                colors = androidx.wear.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = WearColors.SurfaceHigh,
                    contentColor = WearColors.Text,
                ),
            ) { Text("See a demo song", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** The cover, drifting slowly behind the lyrics: the phone's background, held still. */
@Composable
private fun SongBackdrop(state: WatchRepository.State, settings: WearSettings, dim: Float) {
    val image = remember(state.backdrop, state.cover) { (state.backdrop ?: state.cover)?.asImageBitmap() }
    val accent = remember(state.track?.colors) {
        state.track?.colors?.firstOrNull()?.let { Color(it) } ?: WearColors.Accent
    }
    var time by remember { mutableLongStateOf(0L) }
    LaunchedEffect(settings.motion, settings.background) {
        if (!settings.motion) return@LaunchedEffect
        while (true) withFrameNanos { time = it }
    }
    Canvas(Modifier.fillMaxSize()) {
        val style = settings.background
        if (style == BackgroundStyle.Song) drawSongColors(accent, time, dim)
        if (style != BackgroundStyle.Black && image != null) drawCover(image, style == BackgroundStyle.Cover, settings.motion, time, dim)
    }
}

private fun DrawScope.drawSongColors(accent: Color, timeMs: Long, dim: Float) {
    val phase = (timeMs / 1_000f) * 0.08f
    val centre = Offset(
        size.width * (0.5f + 0.16f * sin(phase)),
        size.height * (0.34f + 0.12f * sin(phase * 1.7f + 1.2f)),
    )
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.5f), accent.copy(alpha = 0.16f), Color.Black),
            center = centre,
            radius = size.maxDimension * 0.8f,
        ),
    )
    drawRect(Color.Black.copy(alpha = dim * 0.5f))
}

private fun DrawScope.drawCover(image: ImageBitmap, strong: Boolean, motion: Boolean, timeMs: Long, dim: Float) {
    val time = timeMs / 1_000f
    val zoom = if (motion) 1.2f + 0.05f * sin(time * 0.09f) else 1.2f
    rotate(degrees = if (motion) sin(time * 0.05f) * 4f else 0f) {
        scale(scaleX = zoom, scaleY = zoom) {
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.Low,
            )
        }
    }
    drawRect(Color.Black.copy(alpha = if (strong) 1f - 0.35f else dim))
}

/** Where the song is on the watch: the watch's own clock, from the phone's last position. */
internal fun currentPosition(repository: WatchRepository, settings: com.tx24.spicyplayer.wear.settings.WearSettings): Long {
    return (repository.clock.positionAt(SystemClock.elapsedRealtime()) - settings.delayMs).coerceAtLeast(0L)
}

/** The song page: cover, names, transport and where it is. */
@Composable
private fun SongPage(state: WatchRepository.State, repository: WatchRepository, settings: WearSettings, ambient: Boolean) {
    Box(Modifier.fillMaxSize().background(WearColors.Background)) {
        if (!ambient) SongBackdrop(state, settings, dim = 0.55f)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 26.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val cover = remember(state.cover) { state.cover?.asImageBitmap() }
            if (cover != null && !ambient) {
                Canvas(Modifier.size(84.dp).clip(CircleShape)) {
                    drawImage(
                        image = cover,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(cover.width, cover.height),
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = state.track?.title ?: "Nothing playing",
                color = WearColors.Text,
                fontFamily = LyricsFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.track?.artist.orEmpty(),
                color = WearColors.TextDim,
                style = MaterialTheme.typography.labelSmall.copy(textAlign = TextAlign.Center),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { repository.command(WearCommandType.PREVIOUS) }, modifier = Modifier.size(38.dp)) {
                    Icon(Glyphs.Previous, contentDescription = "Previous", tint = WearColors.Text, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(2.dp))
                IconButton(
                    onClick = { repository.command(WearCommandType.PLAY_PAUSE) },
                    modifier = Modifier.size(46.dp).background(LocalAccent.current, CircleShape),
                ) {
                    Icon(
                        if (state.clock?.playing == true) Glyphs.Pause else Glyphs.Play,
                        contentDescription = "Play or pause",
                        tint = Color.Black,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(2.dp))
                IconButton(onClick = { repository.command(WearCommandType.NEXT) }, modifier = Modifier.size(38.dp)) {
                    Icon(Glyphs.Next, contentDescription = "Next", tint = WearColors.Text, modifier = Modifier.size(22.dp))
                }
            }
            Timeline(state, repository)
        }
    }
}

@Composable
private fun Timeline(state: WatchRepository.State, repository: WatchRepository) {
    val duration = state.track?.durationMs ?: 0L
    if (duration <= 0L) return
    val progress = rememberProgress(repository, duration)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(time((progress * duration).toFloat()), color = WearColors.TextDim, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f).height(3.dp).background(Color.White.copy(alpha = 0.14f), CircleShape)) {
            Box(Modifier.fillMaxWidth(progress).height(3.dp).background(LocalAccent.current, CircleShape))
        }
        Spacer(Modifier.width(6.dp))
        Text(time(duration.toFloat()), color = WearColors.TextDim, style = MaterialTheme.typography.labelSmall)
    }
}

private fun time(ms: Float): String {
    val total = (ms / 1000).toInt().coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
