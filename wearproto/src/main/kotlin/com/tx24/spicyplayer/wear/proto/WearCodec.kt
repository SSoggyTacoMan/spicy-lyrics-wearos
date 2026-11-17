package com.tx24.spicyplayer.wear.proto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Packs the Data Layer payloads: a version byte, then fields in a fixed order. Lyrics are
 * gzipped, with word times as deltas from their line's start, which keeps a whole song to a few kB.
 * A payload of another version reads as null, so the phone and the watch can update apart.
 */
object WearCodec {
    const val VERSION = 1

    fun encodeTrack(track: WearTrack): ByteArray = write { out ->
        out.writeUTF(track.key)
        out.writeUTF(track.title)
        out.writeUTF(track.artist)
        out.writeUTF(track.album)
        out.writeLong(track.durationMs)
        out.writeUTF(track.playerPackage)
        out.writeUTF(track.playerName)
        out.writeByte(track.colors.size)
        track.colors.forEach(out::writeInt)
    }

    fun decodeTrack(bytes: ByteArray): WearTrack? = read(bytes) { input ->
        WearTrack(
            key = input.readUTF(),
            title = input.readUTF(),
            artist = input.readUTF(),
            album = input.readUTF(),
            durationMs = input.readLong(),
            playerPackage = input.readUTF(),
            playerName = input.readUTF(),
            colors = List(input.readUnsignedByte()) { input.readInt() },
        )
    }

    fun encodeLyrics(lyrics: WearLyrics): ByteArray {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).use { out ->
            out.writeUTF(lyrics.key)
            out.writeByte(lyrics.status.ordinal)
            out.writeByte(lyrics.type.ordinal)
            out.writeVarLong(lyrics.lines.size.toLong())
            var lastStart = 0L
            for (line in lyrics.lines) {
                out.writeSignedVarLong(line.startMs - lastStart)
                lastStart = line.startMs
                out.writeVarLong((line.endMs - line.startMs).coerceAtLeast(0L))
                var flags = 0
                if (line.background) flags = flags or 1
                if (line.opposite) flags = flags or 2
                if (line.lineTimed) flags = flags or 4
                if (line.translation != null) flags = flags or 8
                out.writeByte(flags)
                out.writeSignedVarLong(line.groupId.toLong())
                line.translation?.let(out::writeUTF)
                out.writeVarLong(line.words.size.toLong())
                for (word in line.words) {
                    out.writeUTF(word.text)
                    out.writeSignedVarLong(word.startMs - line.startMs)
                    out.writeVarLong((word.endMs - word.startMs).coerceAtLeast(0L))
                    out.writeByte((if (word.attached) 1 else 0) or (if (word.romanized != null) 2 else 0))
                    word.romanized?.let(out::writeUTF)
                }
            }
            val credit = lyrics.credit
            out.writeBoolean(credit != null)
            if (credit != null) {
                out.writeUTF(credit.provider)
                out.writeNullable(credit.source)
                out.writeNullable(credit.maker)
                out.writeNullable(credit.uploader)
                out.writeVarLong(credit.songwriters.size.toLong())
                credit.songwriters.forEach(out::writeUTF)
            }
        }
        val packed = ByteArrayOutputStream()
        packed.write(VERSION)
        GZIPOutputStream(packed).use { it.write(raw.toByteArray()) }
        return packed.toByteArray()
    }

    fun decodeLyrics(bytes: ByteArray): WearLyrics? {
        if (bytes.isEmpty() || bytes[0].toInt() != VERSION) return null
        return runCatching {
            val body = GZIPInputStream(ByteArrayInputStream(bytes, 1, bytes.size - 1)).readBytes()
            DataInputStream(ByteArrayInputStream(body)).use { input ->
                val key = input.readUTF()
                val status = WearStatus.entries[input.readUnsignedByte()]
                val type = WearSyncType.entries[input.readUnsignedByte()]
                var lastStart = 0L
                val lines = List(input.readVarLong().toInt()) {
                    val start = lastStart + input.readSignedVarLong()
                    lastStart = start
                    val end = start + input.readVarLong()
                    val flags = input.readUnsignedByte()
                    val group = input.readSignedVarLong().toInt()
                    val translation = if (flags and 8 != 0) input.readUTF() else null
                    val words = List(input.readVarLong().toInt()) {
                        val text = input.readUTF()
                        val wordStart = start + input.readSignedVarLong()
                        val wordEnd = wordStart + input.readVarLong()
                        val wordFlags = input.readUnsignedByte()
                        val romanized = if (wordFlags and 2 != 0) input.readUTF() else null
                        WearWord(text, wordStart, wordEnd, wordFlags and 1 != 0, romanized)
                    }
                    WearLine(start, end, words, flags and 1 != 0, group, flags and 2 != 0, flags and 4 != 0, translation)
                }
                val credit = if (input.readBoolean()) {
                    WearCredit(
                        provider = input.readUTF(),
                        source = input.readNullable(),
                        maker = input.readNullable(),
                        uploader = input.readNullable(),
                        songwriters = List(input.readVarLong().toInt()) { input.readUTF() },
                    )
                } else null
                WearLyrics(key, status, type, lines, credit)
            }
        }.getOrNull()
    }

    fun encodeClock(clock: WearClock): ByteArray = write { out ->
        out.writeUTF(clock.key)
        out.writeLong(clock.positionMs)
        out.writeLong(clock.atPhoneMs)
        out.writeLong(clock.phoneWallMs)
        out.writeFloat(clock.speed)
        out.writeBoolean(clock.playing)
        out.writeBoolean(clock.canSeek)
        out.writeLong(clock.seq)
    }

    fun decodeClock(bytes: ByteArray): WearClock? = read(bytes) { input ->
        WearClock(
            key = input.readUTF(),
            positionMs = input.readLong(),
            atPhoneMs = input.readLong(),
            phoneWallMs = input.readLong(),
            speed = input.readFloat(),
            playing = input.readBoolean(),
            canSeek = input.readBoolean(),
            seq = input.readLong(),
        )
    }

    fun encodeCommand(command: WearCommand): ByteArray = write { out ->
        out.writeByte(command.type.ordinal)
        out.writeLong(command.arg)
        out.writeInt(command.flags)
    }

    fun decodeCommand(bytes: ByteArray): WearCommand? = read(bytes) { input ->
        WearCommand(WearCommandType.entries[input.readUnsignedByte()], input.readLong(), input.readInt())
    }

    private inline fun write(block: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(VERSION)
            block(out)
        }
        return bytes.toByteArray()
    }

    private inline fun <T> read(bytes: ByteArray, block: (DataInputStream) -> T): T? {
        if (bytes.isEmpty() || bytes[0].toInt() != VERSION) return null
        return runCatching { DataInputStream(ByteArrayInputStream(bytes, 1, bytes.size - 1)).use(block) }.getOrNull()
    }

    private fun DataOutputStream.writeNullable(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readUTF() else null

    private fun DataOutputStream.writeVarLong(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            writeByte(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        writeByte(v.toInt())
    }

    private fun DataInputStream.readVarLong(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = readUnsignedByte()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "varint too long" }
        }
    }

    private fun DataOutputStream.writeSignedVarLong(value: Long) = writeVarLong((value shl 1) xor (value shr 63))

    private fun DataInputStream.readSignedVarLong(): Long {
        val raw = readVarLong()
        return (raw ushr 1) xor -(raw and 1)
    }
}
