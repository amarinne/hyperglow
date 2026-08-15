package com.eza.hyperglow.bridge

import android.os.Bundle
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

data class SpicyBridgeWord(
    val text: String,
    val romanized: String,
    val startMs: Long,
    val endMs: Long,
    val boundaryAfter: Boolean,
    val sourceStart: Int = -1,
    val sourceEnd: Int = -1
)

internal fun normalizeSpicySourceRange(textLength: Int, start: Int, end: Int): Pair<Int, Int> =
    if (start == -1 && end == -1 || start >= 0 && start < end && end <= textLength) {
        start to end
    } else {
        -1 to -1
    }

internal fun normalizeSpicyBoundaryAfter(
    documentVersion: Int,
    boundaryAfter: Boolean?,
    legacyPartOfWord: Boolean?
): Boolean? = if (documentVersion >= 2) boundaryAfter else legacyPartOfWord?.not()

data class SpicyBridgeRuby(val start: Int, val end: Int, val reading: String)

data class SpicyBridgeLayoutGroup(
    val start: Int,
    val end: Int,
    val kind: String,
    val keepTogether: Boolean,
    val confidence: Double
)

data class SpicyBridgeRow(
    val role: String,
    val startMs: Long,
    val endMs: Long,
    val fillEndMs: Long,
    val alignedRight: Boolean,
    val text: String,
    val romanized: String,
    val translated: String,
    val words: List<SpicyBridgeWord>,
    val ruby: List<SpicyBridgeRuby> = emptyList(),
    val layoutGroups: List<SpicyBridgeLayoutGroup> = emptyList()
)

data class SpicyBridgeDocument(
    val producerId: String,
    val generation: Int,
    val trackUri: String,
    val provider: String,
    val language: String,
    val type: String,
    val durationMs: Long,
    val processingVersion: Int,
    val rows: List<SpicyBridgeRow>
) {
    fun matches(state: SpicyBridgeState): Boolean =
        producerId == state.producerId && generation == state.generation &&
            trackUri == state.trackUri && durationMs == state.durationMs

    fun primaryRowAt(positionMs: Long): SpicyBridgeRow? {
        var lead: SpicyBridgeRow? = null
        var other: SpicyBridgeRow? = null
        for (row in rows) {
            if (positionMs < row.startMs || positionMs >= row.endMs) continue
            if (row.role == "LEAD") {
                if (lead == null || row.startMs >= lead.startMs) lead = row
            } else if (other == null) {
                other = row
            }
        }
        return lead ?: other
    }
}
/**
 * Which identity field stops a held document from belonging to the producer state, or null when it
 * still matches. Duration is reported last because it is the field that diverges without any visible
 * song change: a document parsed before the track metadata settled keeps a stale duration and is
 * discarded for the rest of the song.
 */
internal fun spicyBridgeDocumentMismatch(
    document: SpicyBridgeDocument,
    state: SpicyBridgeState
): String? = when {
    document.matches(state) -> null
    document.producerId != state.producerId ->
        "producer document=${document.producerId} state=${state.producerId}"
    document.generation != state.generation ->
        "generation document=${document.generation} state=${state.generation}"
    document.trackUri != state.trackUri ->
        "track document=${document.trackUri} state=${state.trackUri}"
    else -> "duration document=${document.durationMs} state=${state.durationMs} " +
        "track=${state.trackUri}"
}

internal fun isValidSpicyBridgeDocumentTiming(
    document: SpicyBridgeDocument,
    acceptedDurationMs: Long
): Boolean = spicyBridgeDocumentTimingFault(document, acceptedDurationMs) == null

/**
 * Why a document's timing cannot be trusted against the accepted state, or null when it can.
 *
 * The reason is reported rather than folded into a boolean because a rejected document is dropped
 * for the rest of the song: nothing re-requests it, so the difference between "the producer sent a
 * duration the state does not agree with" and "one row runs past the end of the track" is the whole
 * diagnosis when keepalive silently falls back to the song-change lease.
 */
internal fun spicyBridgeDocumentTimingFault(
    document: SpicyBridgeDocument,
    acceptedDurationMs: Long
): String? {
    if (acceptedDurationMs <= 0L) return "state-duration=$acceptedDurationMs"
    if (document.durationMs != acceptedDurationMs) {
        return "duration document=${document.durationMs} state=$acceptedDurationMs"
    }
    document.rows.forEachIndexed { index, row ->
        if (row.startMs < 0L || row.endMs !in row.startMs..acceptedDurationMs) {
            return "row[$index] start=${row.startMs} end=${row.endMs} duration=$acceptedDurationMs"
        }
        if (row.fillEndMs !in row.startMs..row.endMs) {
            return "row[$index] fillEnd=${row.fillEndMs} window=${row.startMs}..${row.endMs}"
        }
        row.words.forEachIndexed { wordIndex, word ->
            if (word.startMs < 0L || word.endMs !in word.startMs..acceptedDurationMs) {
                return "row[$index].word[$wordIndex] start=${word.startMs} end=${word.endMs} " +
                    "duration=$acceptedDurationMs"
            }
        }
    }
    return null
}

internal data class SpicyBridgeDocumentMetadata(
    val documentVersion: Int,
    val producerId: String,
    val generation: Int,
    val trackUri: String,
    val compressedBytes: Int
) {
    val sessionIdentity = SpicyDocumentSessionIdentity(producerId, generation, trackUri)

    companion object {
        fun from(bundle: Bundle): SpicyBridgeDocumentMetadata? {
            val metadata = SpicyBridgeDocumentMetadata(
                documentVersion = bundle.getInt("documentVersion", -1),
                producerId = bundle.getString("producerId").orEmpty(),
                generation = bundle.getInt("generation", -1),
                trackUri = bundle.getString("trackUri").orEmpty(),
                compressedBytes = bundle.getInt("compressedBytes", -1)
            )
            return metadata.takeIf(::isValidSpicyBridgeDocumentMetadata)
        }
    }
}

internal fun isValidSpicyBridgeDocumentMetadata(metadata: SpicyBridgeDocumentMetadata): Boolean =
    metadata.documentVersion in 1..2 &&
        metadata.producerId.isNotBlank() && metadata.producerId.length <= 64 &&
        metadata.generation >= 0 &&
        metadata.trackUri.startsWith("spotify:track:") && metadata.trackUri.length <= 512 &&
        metadata.compressedBytes in 1..MAX_DOCUMENT_COMPRESSED_BYTES

internal data class SpicyDocumentSessionIdentity(
    val producerId: String,
    val generation: Int,
    val trackUri: String
)

internal class SpicyDocumentCommitOrder {
    private var session: SpicyDocumentSessionIdentity? = null
    private var revision = -1L

    @Synchronized
    fun accept(identity: SpicyDocumentSessionIdentity, nextRevision: Long): Boolean {
        if (nextRevision < 0L) return false
        if (identity == session && nextRevision <= revision) return false
        session = identity
        revision = nextRevision
        return true
    }
}

private const val MAX_DOCUMENT_COMPRESSED_BYTES = 1024 * 1024
private const val MAX_DOCUMENT_UNCOMPRESSED_BYTES = 4 * 1024 * 1024

internal fun readBoundedSpicyDocumentGzip(
    input: InputStream,
    expectedCompressedBytes: Int
): ByteArray {
    if (expectedCompressedBytes !in 1..MAX_DOCUMENT_COMPRESSED_BYTES) {
        throw IOException("invalid compressed document size")
    }
    val exactInput = ExactLengthInputStream(input, expectedCompressedBytes)
    val output = ByteArrayOutputStream()
    GZIPInputStream(exactInput).use { gzip ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = gzip.read(buffer)
            if (read < 0) break
            if (output.size() + read > MAX_DOCUMENT_UNCOMPRESSED_BYTES) {
                throw IOException("document exceeds limit")
            }
            output.write(buffer, 0, read)
        }
    }
    if (exactInput.bytesRead != expectedCompressedBytes) {
        throw IOException("compressed document size mismatch")
    }
    return output.toByteArray()
}

private class ExactLengthInputStream(
    private val input: InputStream,
    private val expectedBytes: Int
) : InputStream() {
    var bytesRead: Int = 0
        private set

    override fun read(): Int {
        if (bytesRead >= expectedBytes) return -1
        val value = input.read()
        if (value >= 0) bytesRead++
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRead >= expectedBytes) return -1
        val remaining = expectedBytes - bytesRead
        val read = input.read(buffer, offset, minOf(length, remaining))
        if (read > 0) bytesRead += read
        return read
    }

    override fun close() {
        input.close()
    }
}

object SpicyBridgeDocumentStore {
    private const val DOCUMENT_VERSION = 2
    private const val MIN_DOCUMENT_VERSION = 1
    private const val MAX_COMPRESSED_BYTES = MAX_DOCUMENT_COMPRESSED_BYTES
    private const val MAX_ROWS = 5_000
    private const val MAX_WORDS = 20_000
    private const val MAX_LAYOUT_GROUPS = 20_000
    private const val MAX_TEXT = 8_192

    private val mutableState = MutableStateFlow<SpicyBridgeDocument?>(null)
    val state = mutableState.asStateFlow()
    private val commitOrder = SpicyDocumentCommitOrder()

    /** Null when the document was committed; otherwise why it was dropped. */
    internal fun accept(
        metadata: SpicyBridgeDocumentMetadata,
        descriptor: ParcelFileDescriptor,
        arrivalRevision: Long
    ): String? {
        descriptor.use { fd ->
            if (metadata.documentVersion !in MIN_DOCUMENT_VERSION..DOCUMENT_VERSION ||
                metadata.compressedBytes !in 1..MAX_COMPRESSED_BYTES
            ) return "metadata version=${metadata.documentVersion} bytes=${metadata.compressedBytes}"
            val producerId = metadata.producerId
            val generation = metadata.generation
            val trackUri = metadata.trackUri

            val bridgeState = SpicyBridgeStore.state.value
                ?: return "no-state generation=$generation track=$trackUri"
            if (bridgeState.producerId != producerId || bridgeState.generation != generation ||
                bridgeState.trackUri != trackUri) {
                return "state-mismatch document=$generation:$trackUri " +
                    "state=${bridgeState.generation}:${bridgeState.trackUri}"
            }

            val bytes = readBoundedSpicyDocumentGzip(
                ParcelFileDescriptor.AutoCloseInputStream(fd),
                metadata.compressedBytes
            )
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            val documentVersion = root.requiredInt("version")
            if (documentVersion != metadata.documentVersion ||
                root.requiredString("producerId") != producerId ||
                root.requiredInt("generation") != generation ||
                root.requiredString("trackUri") != trackUri) return "payload-identity-mismatch"

            val rowsJson = root["rows"]?.jsonArray ?: return "malformed rows-missing"
            if (rowsJson.size > MAX_ROWS) return "oversized rows=${rowsJson.size}"
            var wordCount = 0
            var layoutGroupCount = 0
            val rows = rowsJson.map { element ->
                val row = element.jsonObject
                val wordsJson = row["words"]?.jsonArray ?: JsonArray(emptyList())
                wordCount += wordsJson.size
                if (wordCount > MAX_WORDS) return "oversized words=$wordCount"
                val startMs = row.requiredLong("startMs")
                val endMs = row.requiredLong("endMs")
                val fillEndMs = row.requiredLong("fillEndMs")
                if (startMs < 0L || endMs < startMs || fillEndMs < startMs) {
                    return "malformed row start=$startMs end=$endMs fillEnd=$fillEndMs"
                }
                SpicyBridgeRow(
                    role = row.requiredString("role").also {
                        if (it !in setOf("LEAD", "BACKGROUND", "INTERLUDE")) {
                            return "malformed role=$it"
                        }
                    },
                    startMs = startMs,
                    endMs = endMs,
                    fillEndMs = fillEndMs,
                    alignedRight = row.requiredBoolean("alignedRight"),
                    text = row.boundedString("text"),
                    romanized = row.boundedString("romanized"),
                    translated = row.boundedString("translated"),
                    words = wordsJson.map { wordElement ->
                        val word = wordElement.jsonObject
                        val wordStart = word.requiredLong("startMs")
                        val wordEnd = word.requiredLong("endMs")
                        if (wordStart < 0L || wordEnd < wordStart) {
                            return "malformed word start=$wordStart end=$wordEnd"
                        }
                        val sourceRange = normalizeSpicySourceRange(
                            row.requiredString("text").length,
                            word.optionalInt("sourceStart", -1),
                            word.optionalInt("sourceEnd", -1)
                        )
                        SpicyBridgeWord(
                            word.boundedString("text"),
                            word.boundedString("romanized"),
                            wordStart,
                            wordEnd,
                            normalizeSpicyBoundaryAfter(
                                documentVersion,
                                word["boundaryAfter"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                                word["partOfWord"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                            ) ?: return "malformed word-boundary",
                            sourceRange.first,
                            sourceRange.second
                        )
                    },
                    ruby = (row["furigana"]?.jsonArray ?: JsonArray(emptyList())).map { rubyElement ->
                        val ruby = rubyElement.jsonObject
                        val start = ruby.requiredInt("start")
                        val end = ruby.requiredInt("end")
                        if (start < 0 || end < start) return "malformed furigana $start..$end"
                        SpicyBridgeRuby(start, end, ruby.boundedString("reading"))
                    },
                    layoutGroups = (row["layoutGroups"]?.jsonArray ?: JsonArray(emptyList())).map { groupElement ->
                        layoutGroupCount++
                        if (layoutGroupCount > MAX_LAYOUT_GROUPS) {
                            return "oversized layoutGroups=$layoutGroupCount"
                        }
                        val group = groupElement.jsonObject
                        val start = group.requiredInt("start")
                        val end = group.requiredInt("end")
                        if (start < 0 || end <= start ||
                            end > row.requiredString("text").length
                        ) return "malformed layoutGroup $start..$end"
                        SpicyBridgeLayoutGroup(
                            start,
                            end,
                            group.boundedString("kind"),
                            group.requiredBoolean("keepTogether"),
                            group.requiredDouble("confidence").coerceIn(0.0, 1.0)
                        )
                    }
                )
            }

            val document = SpicyBridgeDocument(
                producerId,
                generation,
                trackUri,
                root.boundedString("provider"),
                root.boundedString("language"),
                root.boundedString("type"),
                root.requiredLong("durationMs"),
                root.requiredInt("processingVersion"),
                rows
            )
            return commit(metadata, arrivalRevision, document)
        }
    }

    @Synchronized
    private fun commit(
        metadata: SpicyBridgeDocumentMetadata,
        arrivalRevision: Long,
        document: SpicyBridgeDocument
    ): String? {
        val current = SpicyBridgeStore.state.value
            ?: return "commit-no-state generation=${metadata.generation}"
        if (current.producerId != metadata.producerId ||
            current.generation != metadata.generation ||
            current.trackUri != metadata.trackUri
        ) {
            return "commit-state-mismatch document=${metadata.generation}:${metadata.trackUri} " +
                "state=${current.generation}:${current.trackUri}"
        }
        spicyBridgeDocumentTimingFault(document, current.durationMs)?.let {
            return "commit-timing $it"
        }
        if (!commitOrder.accept(metadata.sessionIdentity, arrivalRevision)) {
            return "commit-order revision=$arrivalRevision"
        }
        mutableState.value = document
        return null
    }

    @Synchronized
    fun clear(producerId: String, generation: Long) {
        mutableState.value?.let {
            if (it.producerId == producerId && generation >= it.generation) mutableState.value = null
        }
    }

    @Synchronized
    fun clear() {
        mutableState.value = null
    }

    private fun JsonObject.requiredString(key: String): String =
        get(key)?.jsonPrimitive?.contentOrNull ?: error("missing $key")
    private fun JsonObject.boundedString(key: String): String =
        requiredString(key).also { if (it.length > MAX_TEXT) error("oversized $key") }
    private fun JsonObject.requiredLong(key: String): Long =
        get(key)?.jsonPrimitive?.long ?: error("missing $key")
    private fun JsonObject.requiredInt(key: String): Int =
        get(key)?.jsonPrimitive?.int ?: error("missing $key")
    private fun JsonObject.optionalInt(key: String, fallback: Int): Int =
        get(key)?.jsonPrimitive?.int ?: fallback
    private fun JsonObject.requiredBoolean(key: String): Boolean =
        get(key)?.jsonPrimitive?.boolean ?: error("missing $key")
    private fun JsonObject.requiredDouble(key: String): Double =
        get(key)?.jsonPrimitive?.double ?: error("missing $key")
}
