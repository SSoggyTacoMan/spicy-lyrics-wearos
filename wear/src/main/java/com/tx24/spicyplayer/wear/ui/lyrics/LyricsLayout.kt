package com.tx24.spicyplayer.wear.ui.lyrics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.tx24.spicyplayer.wear.proto.WearLine
import com.tx24.spicyplayer.wear.proto.WearTimeline
import com.tx24.spicyplayer.wear.proto.WearWord
import com.tx24.spicyplayer.wear.ui.theme.LyricsFont

/**
 * The lyrics as the watch draws them: every line measured once for the size and width it's drawn
 * at, with its words' places in the string and their times.
 *
 * A line is measured twice, in the unsung and the sung colour, so the sweep can clip either; both
 * layouts share a width, so a rectangle of one applies to the other.
 */
internal class LyricsLayout(
    val timeline: WearTimeline,
    private val measurer: TextMeasurer,
    private val widthPx: Int,
    fontSize: TextUnit,
    romanization: Boolean,
    translations: Boolean,
    backgroundVocals: Boolean,
    leftAligned: Boolean,
    private val density: androidx.compose.ui.unit.Density,
) {
    sealed interface Row {
        val index: Int
        val heightPx: Float
        val startMs: Long
    }

    /** A line of sung text: its two drawn colours, its words and what goes under it. */
    class Line(
        val dim: TextLayoutResult,
        val lit: TextLayoutResult,
        val words: List<Word>,
        override val startMs: Long,
        val endMs: Long,
        val opposite: Boolean,
        val lineTimed: Boolean,
        val under: List<TextLayoutResult>,
        val fontSizePx: Float,
        val lift: Boolean,
    ) : Row {
        override val index: Int = 0
        override val heightPx: Float
            get() = lit.size.height.toFloat() + under.sumOf { it.size.height.toDouble() }.toFloat() + UNDER_GAP_PX
    }

    class Vocal(override val startMs: Long, val lead: Line, val background: List<Line>, rowIndex: Int) : Row {
        override val index: Int = rowIndex
        override val heightPx: Float = lead.heightPx + background.sumOf { it.heightPx.toDouble() }.toFloat()
    }

    class Break(override val startMs: Long, override val index: Int, val dots: List<Dot>, dotSizePx: Float) : Row {
        override val heightPx: Float = dotSizePx * 2.2f
    }

    /** A word's slice of its line: the characters it covers, and when it's sung. */
    class Word(val first: Int, val end: Int, val startMs: Long, val endMs: Long) {
        fun progressAt(positionMs: Long): Float = when {
            positionMs <= startMs -> 0f
            positionMs >= endMs -> 1f
            else -> (positionMs - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1L)
        }
    }

    class Dot(val startMs: Long, val endMs: Long)

    val rows: List<Row>
    /** Every row's top, in order. */
    val tops: FloatArray

    init {
        val textAlign = if (leftAligned) TextAlign.Start else TextAlign.Center
        val lead = TextStyle(fontSize = fontSize, fontFamily = LyricsFont, fontWeight = FontWeight.Bold, textAlign = textAlign)
        val backing = lead.copy(fontSize = fontSize * BACKGROUND_SCALE, fontWeight = FontWeight.SemiBold)
        val under = TextStyle(fontSize = fontSize * UNDER_SCALE, fontFamily = LyricsFont, fontWeight = FontWeight.Medium, textAlign = textAlign)
        val constraints = Constraints(maxWidth = widthPx)
        val dotStyle = TextStyle(fontSize = fontSize * DOT_SCALE)
        val dotSize = measurer.measure(AnnotatedString("•"), dotStyle).size.height.toFloat()

        val built = ArrayList<Row>()
        for (row in timeline.rows) {
            when (row) {
                is WearTimeline.Break -> built += Break(row.startMs, built.size, row.dots.map { Dot(it.first, it.second) }, dotSize)
                is WearTimeline.Vocal -> {
                    val first = line(row.lead, lead, under, constraints, romanization, translations, rowIndex = built.size, lift = true)
                    val vocals = if (backgroundVocals) {
                        row.background.mapNotNull {
                            line(it, backing, under, constraints, romanization, translations, rowIndex = built.size, lift = false)
                        }
                    } else emptyList()
                    if (first == null) continue
                    built += Vocal(row.startMs, first, vocals, built.size)
                }
            }
        }
        rows = built
        tops = FloatArray(rows.size)
        var y = 0f
        for (row in rows) {
            tops[row.index] = y
            y += row.heightPx
        }
    }

    /** The drawn row for [timeline]'s row at [positionMs], or -1 before the first and with no timing. */
    fun active(positionMs: Long): Int {
        val source = timeline.activeIndex(positionMs).takeIf { it >= 0 } ?: return -1
        val start = timeline.rows[source].startMs
        return rows.indexOfFirst { it.startMs == start }
    }

    private fun line(
        line: WearLine,
        baseStyle: TextStyle,
        underStyle: TextStyle,
        constraints: Constraints,
        romanization: Boolean,
        translation: Boolean,
        rowIndex: Int,
        lift: Boolean,
    ): Line? {
        // A long line reads better a size down than wrapped over three rows on a watch.
        val characters = line.words.sumOf { it.text.length + 1 }
        val style = when {
            !line.background && characters > LONG_LINE -> baseStyle.copy(fontSize = baseStyle.fontSize * 0.86f)
            !line.background && characters > MEDIUM_LINE -> baseStyle.copy(fontSize = baseStyle.fontSize * 0.92f)
            else -> baseStyle
        }
        val words = ArrayList<Word>()
        var last: Char? = null
        val text = buildAnnotatedString {
            for (word in line.words) {
                val part = word.text.ifEmpty { word.romanized.orEmpty() }
                if (part.isEmpty()) continue
                if (length > 0 && !word.attached && last?.isWhitespace() != true && !part.first().isWhitespace()) append(' ')
                val first = length
                append(part)
                last = part.last()
                words += Word(first, length, word.startMs, word.endMs)
            }
        }
        if (text.isEmpty()) return null
        val dim = measurer.measure(text, style.copy(color = UNSUNG), constraints = constraints)
        val lit = measurer.measure(text, style.copy(color = Color.White), constraints = constraints)
        val below = ArrayList<TextLayoutResult>()
        if (romanization && line.words.any { !it.romanized.isNullOrBlank() }) {
            below += measurer.measure(
                AnnotatedString(romanized(line.words)),
                underStyle.copy(color = Color.White.copy(alpha = 0.5f)),
                constraints = constraints,
            )
        }
        val translated = line.translation
        if (translation && !translated.isNullOrBlank()) {
            below += measurer.measure(AnnotatedString(translated), underStyle.copy(color = Color.White.copy(alpha = 0.45f)), constraints = constraints)
        }
        return Line(
            dim = dim,
            lit = lit,
            words = words,
            startMs = line.startMs,
            endMs = line.endMs,
            opposite = line.opposite,
            lineTimed = line.lineTimed,
            under = below,
            fontSizePx = fontSizePx(style),
            lift = lift,
        )
    }

    private fun fontSizePx(style: TextStyle): Float = with(density) { style.fontSize.toPx() }

    /** The words as they read, romanized where the source had it. */
    private fun romanized(words: List<WearWord>): String = buildString {
        for (word in words) {
            val part = word.romanized?.takeIf { it.isNotBlank() } ?: word.text
            if (part.isEmpty()) continue
            if (isNotEmpty() && !word.attached && !last().isWhitespace() && !part.first().isWhitespace()) append(' ')
            append(part)
        }
    }

    companion object {
        const val BACKGROUND_SCALE = 0.74f
        const val UNDER_SCALE = 0.58f
        const val DOT_SCALE = 0.62f
        const val UNDER_GAP_PX = 6f
        /** Past this many characters a line is drawn a size down, so it wraps to two rows. */
        const val MEDIUM_LINE = 28
        const val LONG_LINE = 40
        val UNSUNG = Color(0xFF9C9DA3)
    }
}
