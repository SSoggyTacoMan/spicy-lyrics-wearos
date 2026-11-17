package com.tx24.spicyplayer.wear.ui.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tx24.spicyplayer.wear.proto.WearTimeline
import com.tx24.spicyplayer.wear.settings.Alignment
import com.tx24.spicyplayer.wear.settings.EffectLevel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The lyrics, word by word, on a watch's round screen.
 *
 * The line being sung sits a little above the middle, with the one to come just under it: the eye
 * follows the sweep more than the scroll. Lines further out fade. Every word lights as it's sung,
 * and the word being sung lifts a touch, as the phone's renderer does.
 *
 * One frame loop drives it. The scroll is kept in the lyrics' own coordinates and eased towards
 * the line being sung by the frame's own duration, so it moves the same at any frame rate and
 * never jumps twice for one line.
 */
@Composable
internal fun LyricsCanvas(
    timeline: WearTimeline,
    lyricsKey: String,
    fontSize: TextUnit,
    accent: androidx.compose.ui.graphics.Color,
    effectLevel: EffectLevel,
    alignment: Alignment,
    romanization: Boolean,
    translation: Boolean,
    backgroundVocals: Boolean,
    positionProvider: () -> Long,
    onSeek: (Long) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 128)
    val widthPx = with(density) { CONTENT_WIDTH.toPx() }.roundToInt()
    val layout = remember(timeline, lyricsKey, fontSize, alignment, romanization, translation, backgroundVocals, widthPx) {
        LyricsLayout(
            timeline = timeline,
            measurer = measurer,
            widthPx = widthPx,
            fontSize = fontSize,
            romanization = romanization,
            translations = translation,
            backgroundVocals = backgroundVocals,
            leftAligned = alignment == Alignment.Start,
            density = density,
        )
    }
    val position = rememberUpdatedState(positionProvider)
    val effects = effectLevel != EffectLevel.None
    // The duet partner's colour: the song's, softened so a whole line of it still reads as text.
    val tint = androidx.compose.ui.graphics.ColorFilter.tint(
        androidx.compose.ui.graphics.lerp(Color.White, accent, 0.5f),
    )
    val textWidthPx = widthPx.toFloat()

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val state = remember(layout) { ScrollState() }

    LaunchedEffect(layout) {
        // Opens on the line being sung rather than the top of the song.
        val first = layout.active(position.value())
        if (first >= 0) state.scroll = focusFor(layout, first)
        var lastFrame = 0L
        while (true) {
            withFrameNanos { frame ->
                val nowMs = frame / 1_000_000
                val dtMs = if (lastFrame == 0L) 16L else (nowMs - lastFrame).coerceIn(1L, 100L)
                lastFrame = nowMs
                val positionMs = position.value()
                val active = layout.active(positionMs)
                if (state.manual != null && nowMs - state.lastManualMs > RESUME_AFTER_MS) state.manual = null
                val target = state.manual ?: if (active >= 0) focusFor(layout, active) else state.scroll
                val gap = target - state.scroll
                state.scroll = when {
                    // A seek or a new song: taken at once, so the whole song doesn't slide past.
                    abs(gap) > state.viewport * 1.2f -> target
                    else -> state.scroll + gap * (1f - exp(-dtMs / FOLLOW_MS))
                }
                state.nowMs = nowMs
            }
        }
    }

    Canvas(
        modifier = modifier
            .focusRequester(focus)
            .onRotaryScrollEvent { event ->
                state.manual = (state.manual ?: state.scroll) + event.verticalScrollPixels
                state.lastManualMs = state.nowMs
                true
            }
            .pointerInput(layout) {
                detectDragGestures(
                    onDragStart = { state.manual = state.manual ?: state.scroll },
                    onDragEnd = { state.lastManualMs = state.nowMs },
                    onDragCancel = { state.lastManualMs = state.nowMs },
                ) { change, drag ->
                    change.consume()
                    state.manual = (state.manual ?: state.scroll) - drag.y
                    state.lastManualMs = state.nowMs
                }
            }
            .pointerInput(layout) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                ) { tap ->
                    // Browsing the lyrics (crown or a drag) and then tapping a line jumps to it;
                    // any other tap brings up the transport.
                    val row = if (timeline.synced && state.manual != null) rowAt(layout, state.scroll, tap.y, state.viewport) else -1
                    val line = (layout.rows.getOrNull(row) as? LyricsLayout.Vocal)?.lead
                    if (line != null) {
                        onSeek(line.startMs)
                        state.manual = null
                    } else {
                        onTap()
                    }
                }
            },
    ) {
        state.viewport = size.height
        val left = (size.width - textWidthPx) / 2f
        drawRows(
            layout = layout,
            positionMs = position.value(),
            active = layout.active(position.value()),
            scroll = state.scroll,
            focusY = size.height * FOCUS_Y,
            left = left,
            textWidth = textWidthPx,
            effects = effects,
            tint = tint,
        )
    }
}

/** What the canvas keeps between frames. */
private class ScrollState {
    var scroll = 0f
    var manual: Float? = null
    var lastManualMs = 0L
    var nowMs = 0L
    var viewport = 1f
}

/** The scroll at which [row]'s middle sits where the eye is. */
private fun focusFor(layout: LyricsLayout, row: Int): Float =
    layout.tops.getOrElse(row) { 0f } + layout.rows.getOrNull(row)?.heightPx.orZero() / 2f

private fun Float?.orZero(): Float = this ?: 0f

private fun DrawScope.drawRows(
    layout: LyricsLayout,
    positionMs: Long,
    active: Int,
    scroll: Float,
    focusY: Float,
    left: Float,
    textWidth: Float,
    effects: Boolean,
    tint: androidx.compose.ui.graphics.ColorFilter?,
) {
    for (row in layout.rows) {
        // In the lyrics' own coordinates: where this row's middle lands on screen.
        val centre = focusY - scroll + layout.tops[row.index] + row.heightPx / 2f
        val top = centre - row.heightPx / 2f
        if (top > size.height + 120f || top + row.heightPx < -120f) continue
        val distance = abs(centre - focusY) / (size.height * 0.62f)
        val isActive = row.index == active
        val alpha = (if (isActive) 1f else 0.5f) * (1f - distance * 0.55f).coerceIn(0.25f, 1f)
        // The line being sung is the biggest thing on the screen; the rest sit back from it.
        val rowScale = if (isActive) 1f else 0.84f
        scale(scaleX = rowScale, scaleY = rowScale, pivot = Offset(size.width / 2f, centre)) {
            translate(top = top) {
            when (row) {
                is LyricsLayout.Break -> drawBreak(row, positionMs, alpha * if (isActive) 1f else 0.55f)
                is LyricsLayout.Vocal -> {
                    drawLine(row.lead, positionMs, isActive, alpha, left, textWidth, effects, tint)
                    var under = row.lead.heightPx
                    for (background in row.background) {
                        translate(top = under) {
                            drawLine(background, positionMs, false, alpha * 0.8f, left, textWidth, effects = false, tint = null)
                        }
                        under += background.heightPx
                    }
                }
                is LyricsLayout.Line -> Unit
                }
            }
        }
    }
}

/** One line: the unsung text under, the sung text over it clipped to where the voice is. */
private fun DrawScope.drawLine(
    line: LyricsLayout.Line,
    positionMs: Long,
    isActive: Boolean,
    alpha: Float,
    left: Float,
    textWidth: Float,
    effects: Boolean,
    tint: androidx.compose.ui.graphics.ColorFilter?,
) {
    val layout = line.lit
    // The duet partner's line takes the song's colour, so the two voices are told apart.
    val lineTint = if (line.opposite && isActive) tint else null
    val tinted = lineTint != null
    val x = left + (textWidth - layout.size.width.toFloat()) / 2f
    val top = (line.fontSizePx * 0.12f)
    val lineTop = top - line.fontSizePx * 0.55f
    val lineBottom = top + layout.size.height + line.fontSizePx * 0.35f

    // The unsung text, everywhere the sweep hasn't reached.
    drawText(line.dim, alpha = alpha * (if (isActive) 0.62f else 0.85f), topLeft = Offset(x, top))

    if (tinted) {
        drawContext.canvas.saveLayer(
            Rect(0f, 0f, size.width, size.height),
            androidx.compose.ui.graphics.Paint().apply { colorFilter = lineTint },
        )
    }
    when {
        // Line-synced: the line lights across, left to right.
        isActive && line.lineTimed -> sweepBand(line, positionMs, alpha, x, top, lineTop, lineBottom, lineTint)
        isActive -> drawWords(line, positionMs, alpha, x, top, lineTop, lineBottom, effects, lineTint)
        // Sung already: it stays lit, dimmed by distance.
        else -> if (positionMs >= line.endMs) {
            drawText(line.lit, alpha = alpha * 0.55f, topLeft = Offset(x, top))
        }
    }
    if (tinted) drawContext.canvas.restore()

    // What hangs under the line: its romanization, then a translation.
    var y = top + layout.size.height
    for (under in line.under) {
        drawText(under, alpha = alpha * 0.55f, topLeft = Offset(x, y))
        y += under.size.height
    }
}

/** The active line, word by word: each word fills as it's sung, with a soft leading edge. */
private fun DrawScope.drawWords(
    line: LyricsLayout.Line,
    positionMs: Long,
    alpha: Float,
    x: Float,
    top: Float,
    lineTop: Float,
    lineBottom: Float,
    effects: Boolean,
    tint: androidx.compose.ui.graphics.ColorFilter?,
) {
    val layout = line.lit
    for (word in line.words) {
        val progress = word.progressAt(positionMs)
        if (progress <= 0f) continue
        val rect = wordRect(layout, word.first, word.end) ?: continue
        val left = x + rect.left
        val right = x + rect.right
        // The word being sung rises; the rest of the line doesn't move.
        val lift = if (effects && line.lift && progress < 1f) {
            -LIFT.toPx() * sin(progress * Math.PI.toFloat())
        } else 0f
        // Clipped to the word's own row as well as its own letters: a wrapped line would otherwise
        // light whatever sits above or below it in the same columns.
        // The word's own row, moved with it: a margin any bigger lets the rows above and below
        // show through their own columns.
        val wordTop = top + rect.top + lift - 2f
        val wordBottom = top + rect.bottom + lift + 2f
        fun draw(from: Float, to: Float, opacity: Float = 1f) {
            if (to - from < 0.5f) return
            clipRect(left = from, top = wordTop, right = to, bottom = wordBottom) {
                drawText(layout, alpha = alpha * opacity, topLeft = Offset(x, top + lift))
            }
        }
        // A crisp edge where the voice is: fading white into the grey underneath reads as a band,
        // not a fade, so the word is simply lit up to the beat.
        val edge = if (progress >= 1f) right + 2f else left + rect.width * progress + 1.5f
        draw(left - 2f, edge)
    }
}

/** A whole line filling as it's sung, for lyrics timed only by line. */
private fun DrawScope.sweepBand(
    line: LyricsLayout.Line,
    positionMs: Long,
    alpha: Float,
    x: Float,
    top: Float,
    lineTop: Float,
    lineBottom: Float,
    tint: androidx.compose.ui.graphics.ColorFilter?,
) {
    val width = line.lit.size.width.toFloat()
    val progress = progressOf(line.startMs, line.endMs, positionMs)
    val feather = min(width * 0.12f, line.fontSizePx)
    val right = x + width * progress
    clipRect(left = x - 2f, top = lineTop, right = right - feather, bottom = lineBottom) {
        drawText(line.lit, alpha = alpha, topLeft = Offset(x, top))
    }
    if (progress < 1f) {
        clipRect(left = right - feather, top = lineTop, right = right, bottom = lineBottom) {
            drawText(line.lit, alpha = alpha * 0.5f, topLeft = Offset(x, top))
        }
    }
}

/** The dots of a break, filling one after another as the silence goes on. */
private fun DrawScope.drawBreak(row: LyricsLayout.Break, positionMs: Long, alpha: Float) {
    val dot = row.heightPx * 0.18f
    val gap = dot * 1.2f
    val total = dot * 3 + gap * 2
    var x = (size.width - total) / 2f
    val y = row.heightPx / 2f
    for (d in row.dots) {
        val fill = when {
            positionMs >= d.endMs -> 1f
            positionMs <= d.startMs -> 0.16f
            else -> 0.16f + 0.84f * ((positionMs - d.startMs).toFloat() / (d.endMs - d.startMs).coerceAtLeast(1L))
        }
        drawCircle(
            color = Color.White.copy(alpha = alpha * (0.3f + 0.7f * fill)),
            radius = dot / 2f * (0.72f + 0.28f * fill),
            center = Offset(x + dot / 2f, y),
        )
        x += dot + gap
    }
}

/** The rectangle [first, end) covers, across the rows it wraps over. */
private fun wordRect(layout: androidx.compose.ui.text.TextLayoutResult, first: Int, end: Int): Rect? {
    if (end <= first) return null
    var top = Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    var left = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    val length = layout.layoutInput.text.length
    if (length == 0) return null
    val startLine = layout.getLineForOffset(first.coerceIn(0, length - 1))
    val endLine = layout.getLineForOffset((end - 1).coerceIn(0, length - 1))
    for (line in startLine..endLine) {
        val from = maxOf(first, layout.getLineStart(line))
        val to = minOf(end, layout.getLineEnd(line))
        if (to <= from) continue
        top = minOf(top, layout.getLineTop(line))
        bottom = maxOf(bottom, layout.getLineBottom(line))
        left = minOf(left, if (line == startLine) layout.getBoundingBox(from).left else layout.getLineLeft(line))
        right = maxOf(right, if (line == endLine) layout.getBoundingBox(to - 1).right else layout.getLineRight(line))
    }
    if (right <= left || bottom <= top) return null
    return Rect(left, top, right, bottom)
}

/** The row at [y] on the screen, for a tap. */
private fun rowAt(layout: LyricsLayout, scroll: Float, y: Float, viewport: Float): Int {
    val focusY = viewport * FOCUS_Y
    for (row in layout.rows) {
        val centre = focusY - scroll + layout.tops[row.index] + row.heightPx / 2f
        if (y >= centre - row.heightPx / 2f && y <= centre + row.heightPx / 2f) return row.index
    }
    return -1
}

private fun progressOf(startMs: Long, endMs: Long, positionMs: Long): Float = when {
    positionMs <= startMs -> 0f
    positionMs >= endMs -> 1f
    else -> (positionMs - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1L)
}

/** The line being sung sits here, as a fraction of the screen's height. */
private const val FOCUS_Y = 0.46f
private val CONTENT_WIDTH = 194.dp
private val LIFT = 3.dp
/** How long the scroll takes to close most of a line's gap: a quick, soft settle. */
private const val FOLLOW_MS = 190f
private const val RESUME_AFTER_MS = 4_000L
