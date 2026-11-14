package com.tx24.spicyplayer.lyrics.spicy.canvas

import com.tx24.spicyplayer.lyrics.spicy.animation.LineSweepGeometry
import com.tx24.spicyplayer.lyrics.spicy.animation.BloomMotion
import com.tx24.spicyplayer.lyrics.spicy.parser.GraphemeSegmenter

/**
 * What Bloom needs to run one fill edge along a line: each word's width, the space after it, each
 * held word's letter widths, the wrapped row each word starts on and how many letters it draws.
 * Null where the line has no such edge (right-to-left, interludes), so the words fill one by one.
 */
internal fun sweepGeometry(layout: LineLayout): LineSweepGeometry? {
    if (layout.isRtl || layout.isInterlude || layout.isSongwriter) return null
    val words = layout.line.words
    if (words.isEmpty()) return null
    val widths = FloatArray(words.size)
    val letters = arrayOfNulls<FloatArray>(words.size)
    val tops = FloatArray(words.size) { Float.NaN }
    val counts = IntArray(words.size)
    var fontPx = 0f
    for (piece in layout.words) {
        val i = piece.sourceWordIndex
        if (i !in words.indices) continue
        if (tops[i].isNaN()) tops[i] = piece.relativeOffset.y
        counts[i] += GraphemeSegmenter.segment(piece.textLayoutResult.layoutInput.text.text.trim()).size
        val width = piece.textLayoutResult.size.width.toFloat()
        if (fontPx == 0f) with(piece.textLayoutResult.layoutInput) { fontPx = with(density) { style.fontSize.toPx() } }
        val word = words[i]
        if (word.isLetterGroup && word.letters.isNotEmpty()) {
            val perLetter = letters[i] ?: FloatArray(word.letters.size).also { letters[i] = it }
            if (piece.charIndex in perLetter.indices) perLetter[piece.charIndex] = width
            widths[i] += width
        } else {
            widths[i] = maxOf(widths[i], piece.fullWordWidth, width)
        }
    }
    if (fontPx <= 0f) return null
    // The space between two words on the same row, as laid out.
    var gap = BloomMotion.DEFAULT_GAP_EM * fontPx
    for (n in 1 until layout.words.size) {
        val before = layout.words[n - 1]
        val after = layout.words[n]
        if (after.sourceWordIndex == before.sourceWordIndex || kotlin.math.abs(after.relativeOffset.y - before.relativeOffset.y) > fontPx / 2f) continue
        val between = after.relativeOffset.x - (before.relativeOffset.x + before.textLayoutResult.size.width)
        if (between > 0f) { gap = between; break }
    }
    // Rows: a word sitting lower than the one before it starts a new one.
    val rows = IntArray(words.size)
    var row = 0
    var lastTop = Float.NaN
    for (i in words.indices) {
        val top = tops[i]
        if (!top.isNaN()) {
            if (!lastTop.isNaN() && top > lastTop + fontPx / 2f) row++
            lastTop = top
        }
        rows[i] = row
    }
    return LineSweepGeometry(widths, gap, letters, fontPx, rows, counts)
}
