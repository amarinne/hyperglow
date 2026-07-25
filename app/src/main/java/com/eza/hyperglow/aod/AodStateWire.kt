package com.eza.hyperglow.aod

import android.os.Bundle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object AodStateWireLimits {
    const val MAX_LYRIC_CHARS = 500
    const val MAX_METADATA_CHARS = 200
    const val MAX_STYLE_CHARS = 200
    const val MAX_WORDS = 128
    const val MAX_RUBY = 128
    const val MAX_LAYOUT_GROUPS = 256
    const val MAX_AGGREGATE_TEXT_UTF8_BYTES = 48 * 1024
    const val MAX_ENCODED_BODY_BYTES = 64 * 1024
    const val MAX_MEDIA_DURATION_MS = 24L * 60L * 60L * 1000L
    const val MAX_PLAYBACK_SPEED = 4f
}

internal object AodStateWireContract {
    const val PROTOCOL_VERSION = 1
    const val KIND_SNAPSHOT = 1
    const val KIND_HIDDEN = 2
    const val KIND_KEEPALIVE = 3
}

internal data class AodStateWireWord(
    val text: String,
    val romanized: String,
    val startMs: Long,
    val endMs: Long,
    val boundaryAfter: Boolean,
    val sourceStart: Int,
    val sourceEnd: Int
)

internal data class AodStateWireRuby(
    val start: Int,
    val end: Int,
    val reading: String
)

internal data class AodStateWireLayoutGroup(
    val start: Int,
    val end: Int,
    val kind: String,
    val keepTogether: Boolean,
    val confidence: Double
)

internal data class AodStateWireSnapshot(
    val trackGeneration: Long,
    val aodEnabled: Boolean,
    val lockscreenEnabled: Boolean,
    val seamlessTransitionEnabled: Boolean,
    val positionFollowingEnabled: Boolean,
    val burnInPattern: String,
    val burnInIntervalMs: Long,
    val original: String,
    val romanized: String,
    val translated: String,
    val metadata: String,
    val alignedRight: Boolean,
    val lineLevelSync: Boolean,
    val lineStartMs: Long,
    val lineEndMs: Long,
    val durationMs: Long,
    val positionMs: Long,
    val sampledAtElapsedMs: Long,
    val speed: Float,
    val words: List<AodStateWireWord>,
    val ruby: List<AodStateWireRuby>,
    val layoutGroups: List<AodStateWireLayoutGroup>,
    val weight: String,
    val textSizeMode: String,
    val textSizeCustom: Int,
    val secondaryMode: String,
    val animationMode: String,
    val glowMode: String,
    val motionMode: String,
    val lineSyncFillMode: String,
    val overflowMode: String,
    val transitionMode: String,
    val fontFamily: String,
    val alignmentMode: String,
    val metadataVisible: Boolean,
    val metadataAnchor: String,
    val adaptiveSectioning: Boolean
)

internal sealed interface AodStateWireMessage {
    val revision: Long
    val userId: Int
    val updatedAtElapsedMs: Long
    val keepAlive: Boolean
    val wakeSignal: Long
    val playbackActive: Boolean

    data class Snapshot(
        override val revision: Long,
        override val userId: Int,
        override val updatedAtElapsedMs: Long,
        override val keepAlive: Boolean,
        override val wakeSignal: Long,
        override val playbackActive: Boolean = false,
        val value: AodStateWireSnapshot
    ) : AodStateWireMessage

    data class Hidden(
        override val revision: Long,
        override val userId: Int,
        override val updatedAtElapsedMs: Long,
        override val keepAlive: Boolean,
        override val wakeSignal: Long,
        override val playbackActive: Boolean = false
    ) : AodStateWireMessage

    data class KeepAlive(
        override val revision: Long,
        override val userId: Int,
        override val updatedAtElapsedMs: Long,
        override val keepAlive: Boolean,
        override val wakeSignal: Long,
        override val playbackActive: Boolean = false
    ) : AodStateWireMessage
}

internal data class AodStateWireEnvelope(
    val protocol: Int,
    val kind: Int,
    val revision: Long,
    val userId: Int,
    val updatedAtElapsedMs: Long,
    val keepAlive: Boolean,
    val wakeSignal: Long,
    val body: ByteArray?,
    val playbackActive: Boolean = false
)

internal object AodStateWireCodec {
    fun encode(message: AodStateWireMessage): AodStateWireEnvelope? {
        if (!validEnvelopeScalars(message.revision, message.userId, message.updatedAtElapsedMs)) {
            return null
        }
        return when (message) {
            is AodStateWireMessage.Snapshot -> {
                val body = encodeSnapshotBody(message.value) ?: return null
                AodStateWireEnvelope(
                    protocol = AodStateWireContract.PROTOCOL_VERSION,
                    kind = AodStateWireContract.KIND_SNAPSHOT,
                    revision = message.revision,
                    userId = message.userId,
                    updatedAtElapsedMs = message.updatedAtElapsedMs,
                    keepAlive = message.keepAlive,
                    wakeSignal = message.wakeSignal,
                    body = body,
                    playbackActive = message.playbackActive
                )
            }
            is AodStateWireMessage.Hidden -> AodStateWireEnvelope(
                protocol = AodStateWireContract.PROTOCOL_VERSION,
                kind = AodStateWireContract.KIND_HIDDEN,
                revision = message.revision,
                userId = message.userId,
                updatedAtElapsedMs = message.updatedAtElapsedMs,
                keepAlive = message.keepAlive,
                wakeSignal = message.wakeSignal,
                body = null,
                playbackActive = message.playbackActive
            )
            is AodStateWireMessage.KeepAlive -> AodStateWireEnvelope(
                protocol = AodStateWireContract.PROTOCOL_VERSION,
                kind = AodStateWireContract.KIND_KEEPALIVE,
                revision = message.revision,
                userId = message.userId,
                updatedAtElapsedMs = message.updatedAtElapsedMs,
                keepAlive = message.keepAlive,
                wakeSignal = message.wakeSignal,
                body = null,
                playbackActive = message.playbackActive
            )
        }
    }

    fun decode(envelope: AodStateWireEnvelope): AodStateWireMessage? {
        if (envelope.protocol != AodStateWireContract.PROTOCOL_VERSION) return null
        if (!validEnvelopeScalars(
                envelope.revision,
                envelope.userId,
                envelope.updatedAtElapsedMs
            )
        ) return null
        return when (envelope.kind) {
            AodStateWireContract.KIND_SNAPSHOT -> {
                val body = envelope.body ?: return null
                val snapshot = decodeSnapshotBody(body) ?: return null
                AodStateWireMessage.Snapshot(
                    revision = envelope.revision,
                    userId = envelope.userId,
                    updatedAtElapsedMs = envelope.updatedAtElapsedMs,
                    keepAlive = envelope.keepAlive,
                    wakeSignal = envelope.wakeSignal,
                    playbackActive = envelope.playbackActive,
                    value = snapshot
                )
            }
            AodStateWireContract.KIND_HIDDEN -> AodStateWireMessage.Hidden(
                revision = envelope.revision,
                userId = envelope.userId,
                updatedAtElapsedMs = envelope.updatedAtElapsedMs,
                keepAlive = envelope.keepAlive,
                wakeSignal = envelope.wakeSignal,
                playbackActive = envelope.playbackActive
            )
            AodStateWireContract.KIND_KEEPALIVE -> AodStateWireMessage.KeepAlive(
                revision = envelope.revision,
                userId = envelope.userId,
                updatedAtElapsedMs = envelope.updatedAtElapsedMs,
                keepAlive = envelope.keepAlive,
                wakeSignal = envelope.wakeSignal,
                playbackActive = envelope.playbackActive
            )
            else -> null
        }
    }

    private fun encodeSnapshotBody(snapshot: AodStateWireSnapshot): ByteArray? {
        if (!isValidSnapshot(snapshot)) return null
        return try {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                output.writeInt(BODY_MAGIC)
                output.writeInt(BODY_VERSION)
                output.writeInt(snapshot.words.size)
                output.writeInt(snapshot.ruby.size)
                output.writeInt(snapshot.layoutGroups.size)
                output.writeLong(snapshot.trackGeneration)
                output.writeStrictBoolean(snapshot.aodEnabled)
                output.writeStrictBoolean(snapshot.lockscreenEnabled)
                output.writeStrictBoolean(snapshot.seamlessTransitionEnabled)
                output.writeStrictBoolean(snapshot.positionFollowingEnabled)
                output.writeBoundedString(snapshot.burnInPattern)
                output.writeLong(snapshot.burnInIntervalMs)
                output.writeBoundedString(snapshot.original)
                output.writeBoundedString(snapshot.romanized)
                output.writeBoundedString(snapshot.translated)
                output.writeBoundedString(snapshot.metadata)
                output.writeStrictBoolean(snapshot.alignedRight)
                output.writeStrictBoolean(snapshot.lineLevelSync)
                output.writeLong(snapshot.lineStartMs)
                output.writeLong(snapshot.lineEndMs)
                output.writeLong(snapshot.durationMs)
                output.writeLong(snapshot.positionMs)
                output.writeLong(snapshot.sampledAtElapsedMs)
                output.writeFloat(snapshot.speed)
                output.writeBoundedString(snapshot.weight)
                output.writeBoundedString(snapshot.textSizeMode)
                output.writeInt(snapshot.textSizeCustom)
                output.writeBoundedString(snapshot.secondaryMode)
                output.writeBoundedString(snapshot.animationMode)
                output.writeBoundedString(snapshot.glowMode)
                output.writeBoundedString(snapshot.motionMode)
                output.writeBoundedString(snapshot.lineSyncFillMode)
                output.writeBoundedString(snapshot.overflowMode)
                output.writeBoundedString(snapshot.transitionMode)
                output.writeBoundedString(snapshot.fontFamily)
                output.writeBoundedString(snapshot.alignmentMode)
                output.writeStrictBoolean(snapshot.metadataVisible)
                output.writeBoundedString(snapshot.metadataAnchor)
                output.writeStrictBoolean(snapshot.adaptiveSectioning)
                snapshot.words.forEach { word ->
                    output.writeBoundedString(word.text)
                    output.writeBoundedString(word.romanized)
                    output.writeLong(word.startMs)
                    output.writeLong(word.endMs)
                    output.writeStrictBoolean(word.boundaryAfter)
                    output.writeInt(word.sourceStart)
                    output.writeInt(word.sourceEnd)
                }
                snapshot.ruby.forEach { ruby ->
                    output.writeInt(ruby.start)
                    output.writeInt(ruby.end)
                    output.writeBoundedString(ruby.reading)
                }
                snapshot.layoutGroups.forEach { group ->
                    output.writeInt(group.start)
                    output.writeInt(group.end)
                    output.writeBoundedString(group.kind)
                    output.writeStrictBoolean(group.keepTogether)
                    output.writeDouble(group.confidence)
                }
            }
            bytes.toByteArray().takeIf {
                it.isNotEmpty() && it.size <= AodStateWireLimits.MAX_ENCODED_BODY_BYTES
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSnapshotBody(body: ByteArray): AodStateWireSnapshot? {
        if (body.isEmpty() || body.size > AodStateWireLimits.MAX_ENCODED_BODY_BYTES) return null
        return try {
            val input = DataInputStream(ByteArrayInputStream(body))
            if (input.readInt() != BODY_MAGIC || input.readInt() != BODY_VERSION) return null
            val wordCount = input.readBoundedCount(AodStateWireLimits.MAX_WORDS) ?: return null
            val rubyCount = input.readBoundedCount(AodStateWireLimits.MAX_RUBY) ?: return null
            val layoutCount = input.readBoundedCount(AodStateWireLimits.MAX_LAYOUT_GROUPS) ?: return null
            val budget = Utf8Budget()
            val trackGeneration = input.readLong()
            val aodEnabled = input.readStrictBoolean() ?: return null
            val lockscreenEnabled = input.readStrictBoolean() ?: return null
            val seamlessTransitionEnabled = input.readStrictBoolean() ?: return null
            val positionFollowingEnabled = input.readStrictBoolean() ?: return null
            val burnInPattern = input.readBoundedString(
                AodStateWireLimits.MAX_STYLE_CHARS,
                allowEmpty = false,
                budget = budget
            ) ?: return null
            val burnInIntervalMs = input.readLong()
            val original = input.readBoundedString(
                AodStateWireLimits.MAX_LYRIC_CHARS,
                allowEmpty = false,
                budget = budget
            ) ?: return null
            val romanized = input.readBoundedString(
                AodStateWireLimits.MAX_LYRIC_CHARS,
                allowEmpty = true,
                budget = budget
            ) ?: return null
            val translated = input.readBoundedString(
                AodStateWireLimits.MAX_LYRIC_CHARS,
                allowEmpty = true,
                budget = budget
            ) ?: return null
            val metadata = input.readBoundedString(
                AodStateWireLimits.MAX_METADATA_CHARS,
                allowEmpty = true,
                budget = budget
            ) ?: return null
            val alignedRight = input.readStrictBoolean() ?: return null
            val lineLevelSync = input.readStrictBoolean() ?: return null
            val lineStartMs = input.readLong()
            val lineEndMs = input.readLong()
            val durationMs = input.readLong()
            val positionMs = input.readLong()
            val sampledAtElapsedMs = input.readLong()
            val speed = input.readFloat()
            val weight = input.readStyleString(budget) ?: return null
            val textSizeMode = input.readStyleString(budget) ?: return null
            val textSizeCustom = input.readInt()
            val secondaryMode = input.readStyleString(budget) ?: return null
            val animationMode = input.readStyleString(budget) ?: return null
            val glowMode = input.readStyleString(budget) ?: return null
            val motionMode = input.readStyleString(budget) ?: return null
            val lineSyncFillMode = input.readStyleString(budget) ?: return null
            val overflowMode = input.readStyleString(budget) ?: return null
            val transitionMode = input.readStyleString(budget) ?: return null
            val fontFamily = input.readStyleString(budget) ?: return null
            val alignmentMode = input.readStyleString(budget) ?: return null
            val metadataVisible = input.readStrictBoolean() ?: return null
            val metadataAnchor = input.readStyleString(budget) ?: return null
            val adaptiveSectioning = input.readStrictBoolean() ?: return null
            val words = ArrayList<AodStateWireWord>(wordCount)
            repeat(wordCount) {
                val text = input.readBoundedString(
                    AodStateWireLimits.MAX_LYRIC_CHARS,
                    allowEmpty = true,
                    budget = budget
                ) ?: return null
                val wordRomanized = input.readBoundedString(
                    AodStateWireLimits.MAX_LYRIC_CHARS,
                    allowEmpty = true,
                    budget = budget
                ) ?: return null
                words += AodStateWireWord(
                    text = text,
                    romanized = wordRomanized,
                    startMs = input.readLong(),
                    endMs = input.readLong(),
                    boundaryAfter = input.readStrictBoolean() ?: return null,
                    sourceStart = input.readInt(),
                    sourceEnd = input.readInt()
                )
            }
            val ruby = ArrayList<AodStateWireRuby>(rubyCount)
            repeat(rubyCount) {
                ruby += AodStateWireRuby(
                    start = input.readInt(),
                    end = input.readInt(),
                    reading = input.readBoundedString(
                        AodStateWireLimits.MAX_LYRIC_CHARS,
                        allowEmpty = true,
                        budget = budget
                    ) ?: return null
                )
            }
            val layoutGroups = ArrayList<AodStateWireLayoutGroup>(layoutCount)
            repeat(layoutCount) {
                layoutGroups += AodStateWireLayoutGroup(
                    start = input.readInt(),
                    end = input.readInt(),
                    kind = input.readBoundedString(
                        AodStateWireLimits.MAX_METADATA_CHARS,
                        allowEmpty = true,
                        budget = budget
                    ) ?: return null,
                    keepTogether = input.readStrictBoolean() ?: return null,
                    confidence = input.readDouble()
                )
            }
            if (input.available() != 0) return null
            AodStateWireSnapshot(
                trackGeneration = trackGeneration,
                aodEnabled = aodEnabled,
                lockscreenEnabled = lockscreenEnabled,
                seamlessTransitionEnabled = seamlessTransitionEnabled,
                positionFollowingEnabled = positionFollowingEnabled,
                burnInPattern = burnInPattern,
                burnInIntervalMs = burnInIntervalMs,
                original = original,
                romanized = romanized,
                translated = translated,
                metadata = metadata,
                alignedRight = alignedRight,
                lineLevelSync = lineLevelSync,
                lineStartMs = lineStartMs,
                lineEndMs = lineEndMs,
                durationMs = durationMs,
                positionMs = positionMs,
                sampledAtElapsedMs = sampledAtElapsedMs,
                speed = speed,
                words = words.toList(),
                ruby = ruby.toList(),
                layoutGroups = layoutGroups.toList(),
                weight = weight,
                textSizeMode = textSizeMode,
                textSizeCustom = textSizeCustom,
                secondaryMode = secondaryMode,
                animationMode = animationMode,
                glowMode = glowMode,
                motionMode = motionMode,
                lineSyncFillMode = lineSyncFillMode,
                overflowMode = overflowMode,
                transitionMode = transitionMode,
                fontFamily = fontFamily,
                alignmentMode = alignmentMode,
                metadataVisible = metadataVisible,
                metadataAnchor = metadataAnchor,
                adaptiveSectioning = adaptiveSectioning
            ).takeIf(::isValidSnapshot)
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidSnapshot(snapshot: AodStateWireSnapshot): Boolean {
        if (snapshot.words.size > AodStateWireLimits.MAX_WORDS ||
            snapshot.ruby.size > AodStateWireLimits.MAX_RUBY ||
            snapshot.layoutGroups.size > AodStateWireLimits.MAX_LAYOUT_GROUPS
        ) return false
        if (snapshot.trackGeneration < 0L || snapshot.lineStartMs < 0L ||
            snapshot.lineEndMs < snapshot.lineStartMs ||
            snapshot.durationMs !in 1L..AodStateWireLimits.MAX_MEDIA_DURATION_MS ||
            snapshot.lineEndMs > snapshot.durationMs ||
            snapshot.positionMs !in 0L..snapshot.durationMs ||
            snapshot.sampledAtElapsedMs < 0L || !snapshot.speed.isFinite() ||
            snapshot.speed !in 0f..AodStateWireLimits.MAX_PLAYBACK_SPEED ||
            snapshot.textSizeCustom !in 0..500
        ) return false
        if (snapshot.burnInPattern != normalizeAodBurnInPattern(snapshot.burnInPattern) ||
            snapshot.burnInIntervalMs != normalizeAodBurnInInterval(snapshot.burnInIntervalMs) ||
            snapshot.original != snapshot.original.trim() ||
            snapshot.romanized != snapshot.romanized.trim() ||
            snapshot.translated != snapshot.translated.trim() ||
            snapshot.metadata != snapshot.metadata.trim() ||
            snapshot.weight != normalizeAodWeight(snapshot.weight) ||
            snapshot.textSizeMode != normalizeAodTextSize(snapshot.textSizeMode) ||
            snapshot.secondaryMode != normalizeAodSecondary(snapshot.secondaryMode) ||
            snapshot.animationMode != normalizeAodAnimation(snapshot.animationMode) ||
            snapshot.glowMode != normalizeAodGlow(snapshot.glowMode) ||
            snapshot.motionMode != "Fluid" ||
            snapshot.lineSyncFillMode != normalizeAodLineSyncFill(snapshot.lineSyncFillMode) ||
            snapshot.overflowMode != normalizeAodOverflow(snapshot.overflowMode) ||
            snapshot.transitionMode != normalizeAodTransition(snapshot.transitionMode) ||
            snapshot.fontFamily != normalizeAodFontFamily(snapshot.fontFamily) ||
            snapshot.alignmentMode != normalizeAodAlignment(snapshot.alignmentMode) ||
            snapshot.metadataAnchor != normalizeAodMetadataAnchor(snapshot.metadataAnchor)
        ) return false
        val budget = Utf8Budget()
        if (!budget.accept(snapshot.original, AodStateWireLimits.MAX_LYRIC_CHARS, false) ||
            !budget.accept(snapshot.romanized, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
            !budget.accept(snapshot.translated, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
            !budget.accept(snapshot.metadata, AodStateWireLimits.MAX_METADATA_CHARS, true)
        ) return false
        val styles = listOf(
            snapshot.burnInPattern,
            snapshot.weight,
            snapshot.textSizeMode,
            snapshot.secondaryMode,
            snapshot.animationMode,
            snapshot.glowMode,
            snapshot.motionMode,
            snapshot.lineSyncFillMode,
            snapshot.overflowMode,
            snapshot.transitionMode,
            snapshot.fontFamily,
            snapshot.alignmentMode,
            snapshot.metadataAnchor
        )
        if (styles.any { !budget.accept(it, AodStateWireLimits.MAX_STYLE_CHARS, false) }) return false
        for (word in snapshot.words) {
            if (!budget.accept(word.text, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
                !budget.accept(word.romanized, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
                word.startMs < 0L || word.endMs < word.startMs ||
                word.endMs > snapshot.durationMs ||
                !validSourceRange(word.sourceStart, word.sourceEnd, snapshot.original.length)
            ) return false
        }
        for (ruby in snapshot.ruby) {
            if (!budget.accept(ruby.reading, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
                ruby.start < 0 || ruby.end <= ruby.start || ruby.end > snapshot.original.length
            ) return false
        }
        for (group in snapshot.layoutGroups) {
            if (!budget.accept(group.kind, AodStateWireLimits.MAX_METADATA_CHARS, true) ||
                group.start < 0 || group.end <= group.start ||
                group.end > snapshot.original.length || !group.confidence.isFinite() ||
                group.confidence !in 0.0..1.0
            ) return false
        }
        return true
    }

    private fun validEnvelopeScalars(revision: Long, userId: Int, updatedAt: Long): Boolean =
        revision >= 0L && userId >= 0 && updatedAt >= 0L

    private fun validSourceRange(start: Int, end: Int, sourceLength: Int): Boolean =
        start == -1 && end == -1 || start >= 0 && end > start && end <= sourceLength

    private class Utf8Budget {
        private var used = 0

        fun accept(value: String, maxChars: Int, allowEmpty: Boolean): Boolean {
            if ((!allowEmpty && value.isEmpty()) || value.length > maxChars ||
                !value.isWellFormedUtf16()
            ) return false
            val size = value.toByteArray(Charsets.UTF_8).size
            if (size > AodStateWireLimits.MAX_AGGREGATE_TEXT_UTF8_BYTES - used) return false
            used += size
            return true
        }

        fun acceptBytes(size: Int): Boolean {
            if (size < 0 || size > AodStateWireLimits.MAX_AGGREGATE_TEXT_UTF8_BYTES - used) {
                return false
            }
            used += size
            return true
        }
    }

    private fun DataOutputStream.writeStrictBoolean(value: Boolean) = writeByte(if (value) 1 else 0)

    private fun DataOutputStream.writeBoundedString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedCount(maximum: Int): Int? =
        readInt().takeIf { it in 0..maximum }

    private fun DataInputStream.readStrictBoolean(): Boolean? = when (readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> null
    }

    private fun DataInputStream.readStyleString(budget: Utf8Budget): String? = readBoundedString(
        AodStateWireLimits.MAX_STYLE_CHARS,
        allowEmpty = false,
        budget = budget
    )

    private fun DataInputStream.readBoundedString(
        maxChars: Int,
        allowEmpty: Boolean,
        budget: Utf8Budget
    ): String? {
        val size = readInt()
        if (size < 0 || size > maxChars * MAX_UTF8_BYTES_PER_UTF16_CHAR ||
            size > available() || !budget.acceptBytes(size)
        ) return null
        val bytes = ByteArray(size)
        readFully(bytes)
        val value = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        return value.takeIf { (allowEmpty || it.isNotEmpty()) && it.length <= maxChars }
    }

    private const val BODY_MAGIC = 0x414F4453
    private const val BODY_VERSION = 1
    private const val MAX_UTF8_BYTES_PER_UTF16_CHAR = 4
}

internal fun normalizeAodLineSyncFill(value: String): String = when (value) {
    "None",
    "Top to bottom",
    "Left to right",
    "Left to right (sentence)",
    "Left to right (main only)",
    "Left to right (whole block)" -> value
    else -> "Top to bottom"
}

internal fun normalizeAodTransition(value: String): String = when (value) {
    "Fade up", "None" -> value
    else -> "Fade up"
}

internal fun String.takeUtf16Prefix(maxChars: Int): String {
    if (length <= maxChars) return this
    val prefix = take(maxChars)
    return if (prefix.lastOrNull()?.isHighSurrogate() == true) prefix.dropLast(1) else prefix
}

private fun String.isWellFormedUtf16(): Boolean {
    var index = 0
    while (index < length) {
        val current = this[index]
        when {
            current.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }
            current.isLowSurrogate() -> return false
            else -> index++
        }
    }
    return true
}

internal object AodStateWireBundleCodec {
    fun toBundle(envelope: AodStateWireEnvelope): Bundle = Bundle().apply {
        putInt(KEY_PROTOCOL, envelope.protocol)
        putInt(KEY_KIND, envelope.kind)
        putLong(KEY_REVISION, envelope.revision)
        putInt(KEY_USER_ID, envelope.userId)
        putLong(KEY_UPDATED_AT, envelope.updatedAtElapsedMs)
        putBoolean(KEY_KEEP_ALIVE, envelope.keepAlive)
        putLong(KEY_WAKE_SIGNAL, envelope.wakeSignal)
        putBoolean(KEY_PLAYBACK_ACTIVE, envelope.playbackActive)
        envelope.body?.let { putByteArray(KEY_BODY, it) }
    }

    fun snapshotFromBundle(bundle: Bundle): AodStateWireMessage? {
        val kind = bundle.getInt(KEY_KIND, 0)
        val envelope = AodStateWireEnvelope(
            protocol = bundle.getInt(KEY_PROTOCOL, 0),
            kind = kind,
            revision = bundle.getLong(KEY_REVISION, -1L),
            userId = bundle.getInt(KEY_USER_ID, -1),
            updatedAtElapsedMs = bundle.getLong(KEY_UPDATED_AT, -1L),
            keepAlive = bundle.getBoolean(KEY_KEEP_ALIVE, false),
            wakeSignal = bundle.getLong(KEY_WAKE_SIGNAL, 0L),
            body = if (kind == AodStateWireContract.KIND_SNAPSHOT) {
                bundle.getByteArray(KEY_BODY)
            } else {
                null
            },
            playbackActive = bundle.getBoolean(KEY_PLAYBACK_ACTIVE, false)
        )
        return AodStateWireCodec.decode(envelope)
    }

    private const val KEY_PROTOCOL = "stateProtocol"
    private const val KEY_KIND = "stateKind"
    private const val KEY_REVISION = "revision"
    private const val KEY_USER_ID = "userId"
    private const val KEY_UPDATED_AT = "updatedAtElapsed"
    private const val KEY_KEEP_ALIVE = "keepAlive"
    private const val KEY_WAKE_SIGNAL = "wakeSignal"
    private const val KEY_PLAYBACK_ACTIVE = "playbackActive"
    private const val KEY_BODY = "stateBody"
}
